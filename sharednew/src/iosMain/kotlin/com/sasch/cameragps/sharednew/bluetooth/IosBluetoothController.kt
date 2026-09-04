package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.IosLaunchContext
import com.sasch.cameragps.sharednew.IosMigrationReminder
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import platform.AccessorySetupKit.ASErrorCodePickerAlreadyActive
import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryMigrationPlanner
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.clearPairingFailedDevice
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.ensureInitialized
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.reconnectToPersistedPeripherals
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.shell
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import com.sasch.cameragps.sharednew.bluetooth.session.OrchestratorEvent
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.DatabaseLogger
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected

/**
 * iOS Bluetooth policy layer and the stable facade the shared Compose UI
 * consumes. The pieces underneath:
 * - [IosCentralShell] — CBCentralManager, delegate, state restoration, scan and
 *   connect/disconnect mechanics
 * - [IosDeviceRepository] — device database, legacy auto-reconnect store
 *   (migration only), enabled-state caches
 * - the shared [CameraSessionOrchestrator] — sequential BLE queue, handshake,
 *   pairing retries, location transmission
 *
 * This object keeps the decisions: auto-reconnect policy, app/device-enabled
 * lifecycle sweeps, pairing-failure UI state and device-list assembly.
 *
 * Declaration order is load-bearing, in both directions:
 * - [transport] and [repository] must come BEFORE [shell], because
 *   `willRestoreState` can fire synchronously inside the CBCentralManager
 *   constructor and reaches both (see the reentrancy contract on
 *   [IosCentralShell]).
 * - everything else must come AFTER [shell]. Property initializers run in
 *   declaration order, so anything declared above it delays the central's
 *   creation — and iOS gives a restoration launch roughly ten seconds to
 *   recreate it. The location manager and the orchestrator (which opens the
 *   Room database) are therefore declared below, together with the init block.
 *
 * Call [ensureInitialized] from AppDelegate as early as possible.
 */
@OptIn(ExperimentalForeignApi::class)
object IosBluetoothController : BluetoothController {

    /**
     * Touch this property from the Swift `AppDelegate.didFinishLaunchingWithOptions`
     * to guarantee the CBCentralManager is alive before the restoration timeout.
     *
     * Usage from Swift:
     * ```swift
     * IosBluetoothController.shared.ensureInitialized()
     * ```
     */
    fun ensureInitialized() {
        // Logging first: everything below is worth a log line, and on a
        // background launch the Compose UI never starts, so this is the only
        // place logging gets configured at all.
        // An unparseable stored level used to throw straight out of
        // didFinishLaunchingWithOptions, which is a crash loop on every
        // background relaunch with no UI to notice it.
        val level = runCatching { LogLevel.valueOf(IosAppPreferences.getLogLevel()) }
            .getOrDefault(LogLevel.Info)
        KmLogging.setLoggers(
            DatabaseLogger(
                LogRepository(getDatabaseBuilder()),
                VariableLogLevel(level)
            )
        )
        logging.i {
            "Launch (${IosLaunchContext.describe()}): appEnabled=$appEnabled " +
                    "migrationDone=${IosAppPreferences.isAccessoryMigrationDone()}"
        }

        // The central comes up on every launch, unconditionally. State
        // restoration only delivers willRestoreState if the manager is recreated
        // inside didFinishLaunchingWithOptions, so anything conditional or
        // asynchronous here kills background reconnect.
        //
        // AccessorySetupKit refuses to migrate while a central exists, so the
        // migration flow tears it down when the app reaches the foreground (see
        // consumeAutoMigrationPrompt) rather than withholding it at launch.
        accessorySession.activate()
        startCentralIfNeeded()
        controllerScope.launch { evaluateMigration() }
    }

    private val logging = logging()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val deviceDao: CameraDeviceDAO by lazy {
        LogDatabase.getRoomDatabase(getDatabaseBuilder()).cameraDeviceDao()
    }

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices

    /** Per-device session state for the UI — read [CameraSession] fields directly. */
    val sessions: StateFlow<Map<String, CameraSession>> get() = orchestrator.sessions

    /** Global transmission gate (location updates running). */
    val transmissionActive: StateFlow<Boolean> get() = orchestrator.locationManager.isActive

    /**
     * Display name of the last device whose pairing was rejected by the camera
     * (pairing gate or auth retries exhausted). The UI shows a troubleshooting
     * dialog while this is non-null and clears it via [clearPairingFailedDevice].
     * A later successful handshake with the same device also clears it.
     */
    private val _pairingFailedDevice = MutableStateFlow<String?>(null)
    val pairingFailedDevice: StateFlow<String?> = _pairingFailedDevice

    private var pairingFailedIdentifier: String? = null

    fun clearPairingFailedDevice() {
        pairingFailedIdentifier = null
        _pairingFailedDevice.value = null
    }

    private fun reportPairingFailure(identifier: String, displayName: String?) {
        pairingFailedIdentifier = identifier
        _pairingFailedDevice.value = displayName
            ?: repository.deviceNameFor(identifier)
                    ?: identifier
    }

    /**
     * True when location authorization is stuck below Always and only the Settings
     * app can raise it: iOS shows the WhenInUse→Always upgrade prompt at most once,
     * and a denied/restricted status never prompts again. Seeded in init and kept
     * current from CLLocationManager's authorization-change callback.
     */
    private val _needsAlwaysLocationAuthorization = MutableStateFlow(false)
    val needsAlwaysLocationAuthorization: StateFlow<Boolean> = _needsAlwaysLocationAuthorization

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    private var appEnabled = IosAppPreferences.isAppEnabled()

    /**
     * Whether the central is powered on. The device-list screen keys its scan
     * effect on this: power-on no longer starts a scan itself, so without this
     * signal nothing would resume scanning after the user toggles Bluetooth off
     * and on again while the app is in the foreground.
     */
    private val _bluetoothPoweredOn = MutableStateFlow(false)
    val bluetoothPoweredOn: StateFlow<Boolean> = _bluetoothPoweredOn

    // --- Collaborators (shell LAST — see the class KDoc) ---

    private val repository = IosDeviceRepository(
        deviceDao = { deviceDao },
        resolveNames = { ids -> shell?.retrieveNames(ids) ?: emptyMap() },
    )

    private val transport: IosBleTransport = IosBleTransport(
        scope = controllerScope,
        onPairingGateExhausted = { peripheral ->
            reportPairingFailure(
                identifier = peripheral.identifier.UUIDString,
                displayName = peripheral.name,
            )
            shell?.cancelConnection(peripheral)
        },
    )

    /**
     * AccessorySetupKit. Constructed here but INERT until [ensureInitialized]
     * activates it, so it cannot call back before the rest of the graph exists.
     */
    private val accessorySession = IosAccessoryShell(
        onAccessoriesChanged = { refreshDeviceListFrom(shell) },
        onAccessoryAdded = { id, name -> handleAccessoryAdded(id, name) },
        onAccessoryRemoved = { id -> handleAccessoryRemoved(id) },
        onMigrationComplete = { handleMigrationComplete() },
    )

    /**
     * Cameras saved before the AccessorySetupKit switch that still need the user
     * to re-authorize them in the system picker. Non-empty means the device list
     * shows the migration card instead of expecting the central to work.
     */
    private val _migrationCandidates = MutableStateFlow<List<PendingMigration>>(emptyList())
    val migrationCandidates: StateFlow<List<PendingMigration>> = _migrationCandidates

    /**
     * True once the central had to be created while candidates were still
     * pending. AccessorySetupKit will not migrate with a live CBCentralManager,
     * so the remaining cameras can only be migrated after an app restart.
     */
    private val _migrationNeedsRestart = MutableStateFlow(false)
    val migrationNeedsRestart: StateFlow<Boolean> = _migrationNeedsRestart

    /** Guards the automatic migration sheet to one attempt per launch. */
    private var migrationAutoAttempted = false

    /**
     * How long to wait after releasing the central before showing the migration
     * picker, so Kotlin/Native's collector can actually deallocate the
     * CBCentralManager. The picker is refused while one is alive.
     */
    private const val CENTRAL_RELEASE_GRACE_MS = 500L

    /**
     * The CBCentralManager, created on demand by [startCentralIfNeeded].
     *
     * It is deliberately NOT created at object initialization: AccessorySetupKit
     * refuses to run its migration flow if a CBCentralManager already exists, and
     * an upgrading user has to migrate before the central is of any use anyway
     * (with AccessorySetupKit declared, CoreBluetooth only ever sees authorized
     * accessories). Constructing it may synchronously fire `willRestoreState`.
     */
    private var centralShell: IosCentralShell? = null

    /**
     * Scope for the central's own delegate work. Its coroutines capture the
     * CBCentralManager, so cancelling this is what actually lets the manager be
     * released; dropping [centralShell] alone would leave in-flight reconnect
     * coroutines holding it alive.
     */
    private var centralScope: CoroutineScope? = null

    private fun createCentralShell(scope: CoroutineScope) = IosCentralShell(
        scope = scope,
        transport = transport,
        isAppEnabled = { appEnabled },
        shouldAutoReconnect = { id -> shouldAutoReconnect(id) },
        onPoweredOn = { restored -> handlePoweredOn(restored) },
        onCentralStateChanged = { poweredOn -> _bluetoothPoweredOn.value = poweredOn },
        onPeripheralConnected = { id -> handlePeripheralConnected(id) },
        // Fires during shell construction (restoration) — must use the parameter,
        // never this object's `shell` field
        onKnownPeripheralsChanged = { s -> refreshDeviceListFrom(s) },
    )

    /**
     * The central, or null while it has not been created yet. Reading this never
     * creates it — only [startCentralIfNeeded] does, so no incidental call can
     * bring the central up underneath the migration flow.
     */
    private val shell: IosCentralShell? get() = centralShell

    /**
     * Resolve [identifier] against the central's known peripherals, falling back
     * to the normalized form while the central does not exist yet.
     */
    private fun resolved(identifier: String): String =
        shell?.resolveKnownIdentifier(identifier) ?: identifier.uppercase()

    /**
     * Create the CBCentralManager.
     *
     * Deliberately unconditional. Two rules pull in opposite directions here and
     * both matter:
     *
     * - The picker is refused with `ASErrorCodePickerRestricted` when the app
     *   holds global Bluetooth permission AND a central is alive. That is why
     *   `NSBluetoothAlwaysUsageDescription` is not declared, and why the callers
     *   below never invoke this while migration is still pending.
     * - State restoration needs the central recreated SYNCHRONOUSLY inside
     *   `didFinishLaunchingWithOptions`, within roughly ten seconds, or iOS
     *   never delivers `willRestoreState` and background reconnect is dead.
     *
     * So this must not wait on anything asynchronous. In particular it must not
     * gate on `accessorySession.authorizedIdentifiers()`: that set is only filled
     * when AccessorySetupKit delivers its `activated` event, long after launch,
     * so such a guard is always empty at the one moment restoration needs the
     * central and silently breaks background reconnect. Keep the decision in the
     * callers, which know whether migration is settled.
     */
    private fun startCentralIfNeeded() {
        if (centralShell != null) return
        logging.i { "Creating the CBCentralManager" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        centralScope = scope
        centralShell = createCentralShell(scope)
    }

    @OptIn(NativeRuntimeApi::class)
    private fun stopCentral() {
        if (centralShell == null) return
        logging.i { "Releasing the CBCentralManager" }
        forceShutdownAllConnections()
        // Cancel first: a suspended reconnect coroutine captures the manager and
        // would keep it alive no matter what happens to the reference below.
        centralScope?.cancel()
        centralScope = null
        centralShell = null
        _bluetoothPoweredOn.value = false
        // Dropping the Kotlin reference does not release the Objective-C object
        // straight away: Kotlin/Native hands that to its garbage collector. The
        // picker checks for a live CBCentralManager, so nudge the collector
        // rather than hoping. This is a backstop — startCentralIfNeeded not
        // creating one in the first place is the actual fix.
        GC.collect()
    }

    private val locationSource = IosLocationSource()

    private val orchestrator = CameraSessionOrchestrator(
        transport = transport,
        locationSource = locationSource,
        deviceDao = deviceDao,
        scope = controllerScope,
        shouldRemainConnected = { identifier ->
            if (!repository.isDeviceEnabled(identifier)) {
                disconnect(identifier)
                false
            } else {
                true
            }
        },
        isTransmissionAllowed = { appEnabled },
    )

    fun hasPreciseAccuracyAuthorization(): Boolean = locationSource.hasPreciseAuthorization()

    init {
        locationSource.onAuthorizationChanged = {
            _needsAlwaysLocationAuthorization.value =
                locationSource.needsAlwaysAuthorizationFromSettings()
            orchestrator.locationManager.updateTracking()
        }
        _needsAlwaysLocationAuthorization.value =
            locationSource.needsAlwaysAuthorizationFromSettings()
        orchestrator.start()

        controllerScope.launch {
            repository.sync()
            refreshDeviceListFrom(shell)
        }
        controllerScope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.PairingFailed -> {
                        val knownIdentifier = resolved(event.identifier)
                        val peripheral = shell?.connectedPeripherals?.get(knownIdentifier)
                        reportPairingFailure(
                            identifier = knownIdentifier,
                            displayName = peripheral?.name,
                        )
                        if (peripheral != null) {
                            shell?.cancelConnection(peripheral)
                        }
                    }

                    is OrchestratorEvent.HandshakeCompleted -> {
                        val failed = pairingFailedIdentifier
                        if (failed != null && failed.equals(event.identifier, ignoreCase = true)) {
                            clearPairingFailedDevice()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Shell event handlers (post-construction only — may use `shell`)
    // ---------------------------------------------------------------------------

    private fun handlePoweredOn(restored: List<CBPeripheral>) {
        if (!appEnabled) {
            shell?.stopScanIfNeeded()
            shell?.cancelConnections(restored)
            shell?.cancelAllKnownConnections()
            controllerScope.launch {
                withDatabase("power-on sweep (app disabled)") {
                    repository.loadStoreFromDisk()
                    repository.migrateLegacyDevicesToDatabase()
                    repository.sync()
                }
                refreshDeviceListFrom(shell)
            }
            refreshDeviceListFrom(shell)
            return
        }
        // No scan here on purpose: reconnecting goes through
        // retrievePeripheralsWithIdentifiers plus a pending connect and never
        // needed one, while discovering new cameras belongs to the device-list
        // screen. Starting one here also leaked a permanently running background
        // scan, because on a background launch no UI ever runs to stop it.
        controllerScope.launch {
            withDatabase("power-on sweep") {
                // One query up front so attachRestoredPeripherals reads the
                // in-memory enabled cache instead of hitting the DAO per device
                // on the launch critical path.
                repository.sync()
                attachRestoredPeripherals(restored)
                reconnectToPersistedPeripherals()
            }
        }
    }

    /**
     * On a background restoration launch before the device has been unlocked
     * since boot, file protection can make the Room file unopenable. An uncaught
     * throw there kills the launch silently, so every launch-path database
     * access goes through here.
     */
    private suspend fun withDatabase(what: String, block: suspend () -> Unit): Boolean =
        runCatching { block() }
            .onFailure { logging.e(it, msg = { "Database work failed during $what" }) }
            .isSuccess

    private fun handlePeripheralConnected(identifier: String) {
        repository.markAutoReconnect(identifier)
        refreshDeviceListFrom(shell)
    }

    // ---------------------------------------------------------------------------
    // BluetoothController implementation
    // ---------------------------------------------------------------------------

    /**
     * No-op: discovery is the AccessorySetupKit picker's job. With
     * AccessorySetupKit declared, a CoreBluetooth scan only ever returns
     * accessories the user has already authorized, so scanning for new cameras
     * cannot work and starting one would just burn radio.
     */
    override suspend fun startScan() = Unit

    override suspend fun stopScan() {
        shell?.stopScan()
    }

    override suspend fun connect(identifier: String): Boolean {
        val central = shell ?: run {
            logging.e { "Cannot connect: the central has not been created yet" }
            return false
        }
        val resolvedIdentifier = central.resolveKnownIdentifier(identifier)
        val peripheral = central.discoveredPeripheral(resolvedIdentifier) ?: return false
        if (central.isConnected(resolvedIdentifier)) return true
        if (!central.isPoweredOn) {
            logging.e { "Cannot connect: CBCentralManager is not powered on" }
            return false
        }
        ensureDeviceRecord(resolvedIdentifier, peripheral.name)
        return central.awaitConnect(peripheral, resolvedIdentifier)
    }

    override suspend fun disconnect(identifier: String) {
        disconnectInternal(identifier, removeFromAutoReconnect = true)
    }

    private suspend fun disconnectInternal(identifier: String, removeFromAutoReconnect: Boolean) {
        val resolvedIdentifier = resolved(identifier)
        if (removeFromAutoReconnect) {
            repository.removeAutoReconnect(identifier)
        }
        shell?.awaitDisconnect(resolvedIdentifier)
    }

    override suspend fun forgetDevice(identifier: String) {
        val resolvedIdentifier = resolved(identifier)
        disconnect(identifier)
        shell?.forget(resolvedIdentifier)
        // Also drop the AccessorySetupKit authorization, which removes the
        // Bluetooth bond. Without this the camera stays paired at the OS level
        // and users have to finish the job in Settings.
        accessorySession.remove(resolvedIdentifier)
        repository.deleteDevice(resolvedIdentifier)
        refreshDeviceListFrom(shell)
    }

    /** Run a full shutter cycle (half press → focus delay → full press → releases). */
    fun triggerShutterSequence(identifier: String): Boolean {
        val session = orchestrator.registry.get(identifier) ?: return false
        if (session.phase != BleSessionPhase.Transmitting) return false
        return orchestrator.triggerShutterSequence(identifier)
    }

    fun setRemoteStatusMonitoringEnabled(identifier: String, enabled: Boolean) {
        orchestrator.setRemoteMonitoring(identifier, enabled)
    }

    fun applyDeviceEnabledState(identifier: String, enabled: Boolean) {
        val normalized = identifier.uppercase()
        repository.setDeviceEnabled(normalized, enabled)

        if (!enabled) {
            orchestrator.setRemoteMonitoring(normalized, false)
            controllerScope.launch {
                disconnectInternal(identifier, removeFromAutoReconnect = false)
            }
            return
        }

        // Targeted connect instead of [reconnectToPersistedPeripherals]: only this
        // device changed, and it relies on the already updated in-memory enabled
        // state rather than the sweep's database re-sync.
        // FIXME do we still need this or use reconnectToPersistedPeripherals() instead?
        if (appEnabled) {
            if (shell?.retrieveAndConnect(normalized) == true) {
                refreshDeviceListFrom(shell)
            }
        }
    }

    suspend fun applyAppEnabledState(enabled: Boolean) {
        appEnabled = enabled
        if (enabled) {
            startScan()
            reconnectToPersistedPeripherals()
            orchestrator.locationManager.updateTracking()
            refreshDeviceListFrom(shell)
            return
        }
        forceShutdownAllConnections()
    }

    // ---------------------------------------------------------------------------
    // Shutdown / cleanup
    // ---------------------------------------------------------------------------

    private fun forceShutdownAllConnections() {
        shell?.stopScanIfNeeded()
        shell?.cancelAllKnownConnections()
        orchestrator.shutdownAll()
        transport.detachAll()
        shell?.resolveAllWaitersDisconnected()
        refreshDeviceListFrom(shell)
    }

    /**
     * A peripheral restored already connected gets no didConnectPeripheral —
     * announce it here so the orchestrator runs discovery + handshake. This
     * must not happen during willRestoreState: discoverServices issued before
     * the central is powered on is dropped and the session dead-ends in a
     * discovery timeout. Disabled devices are cancelled instead (also only
     * possible once powered on). Runs before [reconnectToPersistedPeripherals]
     * so the sweep sees restored devices as connected and skips them.
     */
    private suspend fun attachRestoredPeripherals(restored: List<CBPeripheral>) {
        restored.forEach { peripheral ->
            val id = peripheral.identifier.UUIDString
            if (!repository.isDeviceEnabled(id)) {
                shell?.cancelConnection(peripheral)
            } else if (peripheral.state == CBPeripheralStateConnected) {
                transport.attachPeripheral(peripheral)
            }
        }
        refreshDeviceListFrom(shell)
    }

    private suspend fun reconnectToPersistedPeripherals() {
        repository.loadStoreFromDisk()
        repository.migrateLegacyDevicesToDatabase()
        repository.sync()
        // The device database is the source of truth for saved devices; the
        // NSUserDefaults store is only read above to migrate legacy entries.
        val ids = repository.savedDevices.keys.toList()
        if (ids.isEmpty()) return
        val central = shell ?: return
        central.retrievePeripherals(ids).forEach { peripheral ->
            val id = peripheral.identifier.UUIDString
            if (!repository.isDeviceEnabled(id)) {
                return@forEach
            }
            central.registerAndConnect(peripheral)
        }
        refreshDeviceListFrom(shell)
    }

    // Shell-owned identity/connection state only; per-device session state is
    // observed by the UI straight from [sessions]. Takes the shell as a parameter
    // because the restoration path invokes this while this object's `shell` field
    // is still unassigned (see IosCentralShell's reentrancy contract).
    private fun refreshDeviceListFrom(shell: IosCentralShell?) {
        val persistedByNormalized = repository.savedDevices
        val discoveredByNormalized =
            shell?.discoveredPeripherals.orEmpty().entries.associateBy { it.key.uppercase() }
        val connectedByNormalized =
            shell?.connectedPeripherals?.keys.orEmpty().associateBy { it.uppercase() }
        val allIdentifiers = LinkedHashSet<String>()
        allIdentifiers.addAll(discoveredByNormalized.keys)
        allIdentifiers.addAll(persistedByNormalized.keys)

        _devices.update {
            allIdentifiers.map { normalizedId ->
                val discoveredEntry = discoveredByNormalized[normalizedId]
                val persistedEntry = persistedByNormalized[normalizedId]
                val peripheral = discoveredEntry?.value
                val identifier = discoveredEntry?.key ?: (persistedEntry?.mac ?: normalizedId)
                BluetoothDeviceInfo(
                    identifier = identifier,
                    name = accessorySession.displayName(normalizedId)
                        ?: peripheral?.name
                        ?: persistedEntry?.deviceName
                        ?: "Unknown device",
                    isConnected = connectedByNormalized.containsKey(normalizedId),
                    isSaved = repository.isSaved(identifier),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Policy
    // ---------------------------------------------------------------------------

    private suspend fun shouldAutoReconnect(id: String): Boolean {
        // A camera that rejected pairing is disconnected on purpose; reconnecting
        // would restart the pairing gate and loop the camera's pairing prompt.
        // Cleared when the user dismisses the dialog or a handshake succeeds.
        if (id.equals(pairingFailedIdentifier, ignoreCase = true)) return false
        // A de-authorized accessory can never connect again; retrying would be a
        // reconnect storm against a device CoreBluetooth is not allowed to see.
        if (!accessorySession.isAuthorized(id)) return false
        return appEnabled &&
                repository.isAutoReconnectEnabled(id) &&
                repository.isDeviceEnabled(id)
    }

    // ---------------------------------------------------------------------------
    // AccessorySetupKit
    // ---------------------------------------------------------------------------

    /**
     * Work out whether any saved camera still predates AccessorySetupKit. Runs on
     * every launch until migration is recorded as done, because a user can have
     * saved devices from several older versions.
     */
    private suspend fun evaluateMigration() {
        if (IosAppPreferences.isAccessoryMigrationDone()) return
        if (!accessorySession.awaitActivated()) {
            // Deliberately does NOT fall back to creating a central. Without
            // AccessorySetupKit there are no authorized accessories, so a central
            // would have nothing to see, and creating one is what gets the picker
            // refused on the next attempt. Migration is retried on the next
            // launch. This is also the simulator's path.
            logging.e { "AccessorySetupKit did not activate; leaving migration pending" }
            return
        }
        val read = withDatabase("migration check") {
            repository.loadStoreFromDisk()
            repository.migrateLegacyDevicesToDatabase()
            repository.sync()
        }
        if (!read) {
            // An empty device list from a failed read would look like "nothing to
            // migrate" and permanently record migration as done, stranding every
            // saved camera. Leave it pending and retry on the next launch.
            logging.e { "Could not read saved devices; leaving migration pending" }
            return
        }
        val candidates = AccessoryMigrationPlanner.planMigrations(
            saved = repository.savedDevices.values,
            authorized = accessorySession.authorizedIdentifiers(),
        )
        _migrationCandidates.value = candidates
        if (candidates.isEmpty()) {
            // Nothing saved, or everything already authorized. Never ask again.
            finishMigration()
        } else {
            logging.i { "${candidates.size} saved camera(s) await AccessorySetupKit authorization" }
            // There is now a concrete reason to ask: without a reminder, someone
            // who dismisses the sheet has no working app and no way to know.
            IosMigrationReminder.requestAuthorizationIfForeground()
            IosMigrationReminder.armMigrationPending()
        }
        refreshDeviceListFrom(shell)
    }

    /**
     * Whether to raise the migration explainer on this launch, consuming the
     * one attempt it gets.
     *
     * iOS has no post-update hook, so bringing this up by itself the first time
     * the updated app is opened is the closest thing to automatic migration. It
     * is an in-app dialog rather than the system sheet directly: the sheet
     * appearing unannounced explains nothing, and routing through a Continue
     * button also means the picker is opened by an explicit user action, which
     * is what AccessorySetupKit asks for.
     *
     * Once per launch, so declining does not trap the user in a loop; the card
     * in the device list stays available for a manual retry.
     */
    fun consumeAutoMigrationPrompt(): Boolean {
        if (migrationAutoAttempted) return false
        if (_migrationCandidates.value.isEmpty()) return false
        if (_migrationNeedsRestart.value) return false
        migrationAutoAttempted = true
        // Release the central now, while the explainer is still being read.
        // Kotlin/Native hands the Objective-C release to its collector, so the
        // manager is not gone the instant the reference drops; doing this here
        // rather than immediately before showPicker gives it those seconds.
        stopCentral()
        logging.i { "Raising the migration explainer" }
        return true
    }

    /**
     * Show the AccessorySetupKit migration flow for the saved cameras. Driven by
     * an explicit user action, as the framework requires.
     */
    suspend fun presentMigrationPicker(): Boolean {
        migrationAutoAttempted = true
        val candidates = _migrationCandidates.value
        if (candidates.isEmpty()) return true

        // AccessorySetupKit will not migrate while a CBCentralManager exists, so
        // release it for the duration of the picker. Without this the flow fails
        // for anyone whose central came up first, which is every user who added
        // a camera before migrating.
        // The automatic path already released the central while the explainer was
        // on screen. This covers the manual retry from the device-list card,
        // where the release would otherwise be milliseconds before the picker.
        // Kotlin/Native releases the Objective-C manager on its collector, so
        // give that a moment to actually happen.
        if (centralShell != null) {
            stopCentral()
            delay(CENTRAL_RELEASE_GRACE_MS)
        }

        val outcome = accessorySession.showMigrationPicker(candidates)
        logging.i { "Migration picker finished: $outcome" }
        recomputeMigrationCandidates()

        if (outcome is IosAccessoryShell.PickerOutcome.Failed &&
            outcome.code != ASErrorCodePickerAlreadyActive
        ) {
            // Releasing the central may not be enough if CoreBluetooth is still
            // holding it internally. Point at a relaunch rather than failing
            // silently. An already-active picker is transient and excluded, so a
            // double tap does not strand the user on that message.
            _migrationNeedsRestart.value = true
        }
        // Always bring the central back: a cancelled or failed migration must not
        // leave the app without one. No-op when finishMigration already did it.
        startCentralIfNeeded()
        return outcome is IosAccessoryShell.PickerOutcome.Completed
    }

    /**
     * Show the AccessorySetupKit picker so the user can authorize a new camera.
     * This is the replacement for the old in-app scan list.
     */
    suspend fun presentAccessoryPicker(): Boolean {
        val outcome = accessorySession.showDiscoveryPicker()
        logging.i { "Discovery picker finished: $outcome" }
        // The central is needed to talk to whatever was just authorized. If
        // migration candidates remain, creating it now closes the migration door
        // until the app is restarted, so say so rather than failing silently.
        startCentralForAccessoryUse()
        return outcome is IosAccessoryShell.PickerOutcome.Completed
    }

    /** Debug only: forget that migration was done so the flow can be re-tested. */
    fun resetAccessoryMigrationForTesting() {
        logging.i { "Resetting AccessorySetupKit migration state" }
        IosAppPreferences.setAccessoryMigrationDone(false)
        migrationAutoAttempted = false
        _migrationNeedsRestart.value = false
        controllerScope.launch { evaluateMigration() }
    }

    private fun handleMigrationComplete() {
        controllerScope.launch { recomputeMigrationCandidates() }
    }

    private suspend fun recomputeMigrationCandidates() {
        withDatabase("migration recheck") { repository.sync() }
        val authorized = accessorySession.authorizedIdentifiers()
        // Both sides logged verbatim: if AccessorySetupKit ever hands back an
        // identifier that differs from the CBPeripheral UUID we stored, the
        // candidate list can never drain and this is the only way to see it.
        logging.i {
            "Migration recheck: authorized=$authorized " +
                    "saved=${repository.savedDevices.keys}"
        }
        val remaining = AccessoryMigrationPlanner.planMigrations(
            saved = repository.savedDevices.values,
            authorized = authorized,
        )
        _migrationCandidates.value = remaining
        if (remaining.isEmpty()) {
            finishMigration()
        } else {
            logging.i { "${remaining.size} camera(s) still await authorization" }
            IosMigrationReminder.armMigrationPending()
        }
        refreshDeviceListFrom(shell)
    }

    /** Migration is settled: record it and bring the central up for good. */
    private fun finishMigration() {
        IosAppPreferences.setAccessoryMigrationDone(true)
        _migrationCandidates.value = emptyList()
        _migrationNeedsRestart.value = false
        // Nothing left to nudge about, including a dead man's switch armed by
        // the previous release.
        IosMigrationReminder.cancel()
        logging.i { "AccessorySetupKit migration settled" }
        // When the central is created here, its own power-on runs the reconnect
        // sweep. Sweeping now would fire retrieve/connect before PoweredOn, where
        // CoreBluetooth drops both.
        val alreadyRunning = centralShell != null
        startCentralIfNeeded()
        if (alreadyRunning) controllerScope.launch { reconnectToPersistedPeripherals() }
    }

    /**
     * Bring the central up because the user wants to use an authorized camera,
     * even though some old cameras have not been migrated yet.
     */
    private fun startCentralForAccessoryUse() {
        if (centralShell != null) return
        // Safe to start even with cameras left to migrate: the migration picker
        // releases the central again when it runs.
        startCentralIfNeeded()
    }

    private fun handleAccessoryAdded(identifier: String, displayName: String?) {
        controllerScope.launch {
            startCentralForAccessoryUse()
            withDatabase("accessory added") {
                repository.ensureDeviceRecord(identifier.uppercase(), displayName ?: "N/A")
                repository.sync()
            }
            repository.markAutoReconnect(identifier)
            shell?.retrieveAndConnect(identifier)
            // The user may have re-paired a camera rather than migrating it;
            // without this it would sit in the candidate list for ever.
            recomputeMigrationCandidates()
            refreshDeviceListFrom(shell)
        }
    }

    /** The user unpaired the camera in Settings; drop our row so the two agree. */
    private fun handleAccessoryRemoved(identifier: String) {
        controllerScope.launch {
            val normalized = identifier.uppercase()
            disconnectInternal(normalized, removeFromAutoReconnect = true)
            shell?.forget(normalized)
            withDatabase("accessory removed") {
                repository.deleteDevice(normalized)
                repository.sync()
            }
            refreshDeviceListFrom(shell)
        }
    }

    suspend fun ensureDeviceRecord(identifier: String, deviceName: String? = null) {
        val resolvedName = deviceName
            ?: shell?.peripheralName(identifier)
            ?: "N/A"
        repository.ensureDeviceRecord(identifier, resolvedName)
        repository.sync()
        refreshDeviceListFrom(shell)
    }
}
