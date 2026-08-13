package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosAppPreferences
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
 * Declaration order is load-bearing: [shell] must be the LAST property —
 * constructing it creates the CBCentralManager, and `willRestoreState` can fire
 * synchronously inside that constructor, calling back into this object while
 * every earlier member is already initialized (see the reentrancy contract on
 * [IosCentralShell]).
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
        // Accessing `shell` is enough – its initializer creates the
        // CBCentralManager and registers it for state restoration.
        shell
        KmLogging.setLoggers(
            DatabaseLogger(
                LogRepository(getDatabaseBuilder()),
                VariableLogLevel(LogLevel.valueOf(IosAppPreferences.getLogLevel()))
            )
        )
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

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    private var appEnabled = IosAppPreferences.isAppEnabled()

    // --- Collaborators (shell LAST — see the class KDoc) ---

    private val repository = IosDeviceRepository(
        deviceDao = deviceDao,
        resolveNames = { ids -> shell.retrieveNames(ids) },
    )

    private val transport: IosBleTransport = IosBleTransport(
        scope = controllerScope,
        onPairingGateExhausted = { peripheral ->
            reportPairingFailure(
                identifier = peripheral.identifier.UUIDString,
                displayName = peripheral.name,
            )
            shell.cancelConnection(peripheral)
        },
    )

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
            orchestrator.locationManager.updateTracking()
        }
        orchestrator.start()

        controllerScope.launch {
            repository.sync()
            refreshDeviceListFrom(shell)
        }
        controllerScope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.PairingFailed -> {
                        val knownIdentifier = shell.resolveKnownIdentifier(event.identifier)
                        val peripheral = shell.connectedPeripherals[knownIdentifier]
                        reportPairingFailure(
                            identifier = knownIdentifier,
                            displayName = peripheral?.name,
                        )
                        if (peripheral != null) {
                            shell.cancelConnection(peripheral)
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

    /** LAST property — constructing it may synchronously fire `willRestoreState`. */
    private val shell = IosCentralShell(
        scope = controllerScope,
        transport = transport,
        isAppEnabled = { appEnabled },
        shouldAutoReconnect = { id -> shouldAutoReconnect(id) },
        onPoweredOn = { restored -> handlePoweredOn(restored) },
        onPeripheralConnected = { id -> handlePeripheralConnected(id) },
        // Fires during shell construction (restoration) — must use the parameter,
        // never this object's `shell` field
        onKnownPeripheralsChanged = { s -> refreshDeviceListFrom(s) },
    )

    // ---------------------------------------------------------------------------
    // Shell event handlers (post-construction only — may use `shell`)
    // ---------------------------------------------------------------------------

    private fun handlePoweredOn(restored: List<CBPeripheral>) {
        if (!appEnabled) {
            shell.stopScanIfNeeded()
            shell.cancelConnections(restored)
            shell.cancelAllKnownConnections()
            controllerScope.launch {
                repository.loadStoreFromDisk()
                repository.migrateLegacyDevicesToDatabase()
                repository.sync()
                refreshDeviceListFrom(shell)
            }
            refreshDeviceListFrom(shell)
            return
        }
        shell.startScanIfNeeded()
        controllerScope.launch {
            attachRestoredPeripherals(restored)
            reconnectToPersistedPeripherals()
        }
    }

    private fun handlePeripheralConnected(identifier: String) {
        repository.markAutoReconnect(identifier)
        refreshDeviceListFrom(shell)
    }

    // ---------------------------------------------------------------------------
    // BluetoothController implementation
    // ---------------------------------------------------------------------------

    override suspend fun startScan() {
        shell.startScanIfNeeded()
    }

    override suspend fun stopScan() {
        shell.stopScan()
    }

    override suspend fun connect(identifier: String): Boolean {
        val resolvedIdentifier = shell.resolveKnownIdentifier(identifier)
        val peripheral = shell.discoveredPeripheral(resolvedIdentifier) ?: return false
        if (shell.isConnected(resolvedIdentifier)) return true
        if (!shell.isPoweredOn) {
            logging.e { "Cannot connect: CBCentralManager is not powered on" }
            return false
        }
        ensureDeviceRecord(resolvedIdentifier, peripheral.name)
        return shell.awaitConnect(peripheral, resolvedIdentifier)
    }

    override suspend fun disconnect(identifier: String) {
        disconnectInternal(identifier, removeFromAutoReconnect = true)
    }

    private suspend fun disconnectInternal(identifier: String, removeFromAutoReconnect: Boolean) {
        val resolvedIdentifier = shell.resolveKnownIdentifier(identifier)
        if (removeFromAutoReconnect) {
            repository.removeAutoReconnect(identifier)
        }
        shell.awaitDisconnect(resolvedIdentifier)
    }

    override suspend fun forgetDevice(identifier: String) {
        val resolvedIdentifier = shell.resolveKnownIdentifier(identifier)
        disconnect(identifier)
        shell.forget(resolvedIdentifier)
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
            if (shell.retrieveAndConnect(normalized)) {
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
        shell.stopScanIfNeeded()
        shell.cancelAllKnownConnections()
        orchestrator.shutdownAll()
        transport.detachAll()
        shell.resolveAllWaitersDisconnected()
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
                shell.cancelConnection(peripheral)
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
        shell.retrievePeripherals(ids).forEach { peripheral ->
            val id = peripheral.identifier.UUIDString
            if (!repository.isDeviceEnabled(id)) {
                return@forEach
            }
            shell.registerAndConnect(peripheral)
        }
        refreshDeviceListFrom(shell)
    }

    // Shell-owned identity/connection state only; per-device session state is
    // observed by the UI straight from [sessions]. Takes the shell as a parameter
    // because the restoration path invokes this while this object's `shell` field
    // is still unassigned (see IosCentralShell's reentrancy contract).
    private fun refreshDeviceListFrom(shell: IosCentralShell) {
        val persistedByNormalized = repository.savedDevices
        val discoveredByNormalized =
            shell.discoveredPeripherals.entries.associateBy { it.key.uppercase() }
        val connectedByNormalized = shell.connectedPeripherals.keys.associateBy { it.uppercase() }
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
                    name = peripheral?.name ?: persistedEntry?.deviceName ?: "Unknown device",
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
        return appEnabled &&
                repository.isAutoReconnectEnabled(id) &&
                repository.isDeviceEnabled(id)
    }

    suspend fun ensureDeviceRecord(identifier: String, deviceName: String? = null) {
        val resolvedName = deviceName
            ?: shell.peripheralName(identifier)
            ?: "N/A"
        repository.ensureDeviceRecord(identifier, resolvedName)
        repository.sync()
        refreshDeviceListFrom(shell)
    }
}
