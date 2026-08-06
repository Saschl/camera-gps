package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.autoReconnectStore
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.clearPairingFailedDevice
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.connect
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.disconnect
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.ensureInitialized
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.reconnectToPersistedPeripherals
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import com.sasch.cameragps.sharednew.bluetooth.session.OrchestratorEvent
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.DatabaseLogger
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBConnectPeripheralOptionEnableAutoReconnect
import platform.CoreBluetooth.CBConnectPeripheralOptionNotifyOnConnectionKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * iOS Bluetooth controller — thin shell backed by CoreBluetooth.
 *
 * All session orchestration (sequential BLE queue, handshake, pairing retries,
 * location transmission) lives in the shared [CameraSessionOrchestrator].
 * This class owns only:
 * - CBCentralManager + central delegate wiring & state restoration
 * - Scanning (Sony manufacturer-ID filter) and connect/disconnect plumbing
 * - Auto-reconnect of saved devices (device database; the legacy
 *   [IosAutoReconnectStore] is kept in sync only for migration)
 * - App/device-enabled state and the device list for the UI
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
        // Accessing `central` is enough – the property initialiser creates the
        // CBCentralManager and registers it for state restoration.
        central
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
            ?: persistedDevices[identifier.uppercase()]?.deviceName
                    ?: identifier
    }

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    // UUID string -> CBPeripheral
    private val discovered = mutableMapOf<String, CBPeripheral>()
    private val connected = mutableMapOf<String, CBPeripheral>()

    /**
     * Peripherals handed back by state restoration before the central reported
     * PoweredOn. Announcing them (attach → discovery) or cancelling them must
     * wait for PoweredOn — CoreBluetooth drops both discoverServices and
     * cancelPeripheralConnection issued earlier.
     */
    private val restoredAwaitingPowerOn = mutableListOf<CBPeripheral>()

    /**
     * Central-level connection lifecycle, one stream for all devices.
     * [connect]/[disconnect] await their device's event here instead of parking
     * per-device callbacks — the same pattern the transport layer uses for GATT
     * operations. Multiple concurrent waiters per device are fine.
     */
    private sealed interface ConnectionEvent {
        val identifier: String

        data class Connected(override val identifier: String) : ConnectionEvent
        data class ConnectFailed(override val identifier: String) : ConnectionEvent
        data class Disconnected(override val identifier: String) : ConnectionEvent
    }

    private val connectionEvents = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** CoreBluetooth connects never time out on their own; bound the callers. */
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val DISCONNECT_TIMEOUT_MS = 10_000L

    private var appEnabled = IosAppPreferences.isAppEnabled()
    private val deviceEnabledOverrides = mutableMapOf<String, Boolean>()
    private val persistedDevices = mutableMapOf<String, CameraDevice>()

    // --- Collaborators ---
    private val autoReconnectStore = IosAutoReconnectStore()

    private val transport: IosBleTransport = IosBleTransport(
        scope = controllerScope,
        onPairingGateExhausted = { peripheral ->
            reportPairingFailure(
                identifier = peripheral.identifier.UUIDString,
                displayName = peripheral.name,
            )
            if (central.state == CBManagerStatePoweredOn) {
                central.cancelPeripheralConnection(peripheral)
            }
        },
    )

    private val locationSource = IosLocationSource()

    private val orchestrator = CameraSessionOrchestrator(
        transport = transport,
        locationSource = locationSource,
        deviceDao = deviceDao,
        scope = controllerScope,
        shouldRemainConnected = { identifier ->
            if (!isDeviceEnabled(identifier)) {
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
            syncPersistedDevices()
        }
        controllerScope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.PairingFailed -> {
                        val knownIdentifier = resolveKnownIdentifier(event.identifier)
                        val peripheral = connected[knownIdentifier]
                        reportPairingFailure(
                            identifier = knownIdentifier,
                            displayName = peripheral?.name,
                        )
                        if (peripheral != null && central.state == CBManagerStatePoweredOn) {
                            central.cancelPeripheralConnection(peripheral)
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
        // Session/transmission state drives the UI device list
        controllerScope.launch {
            orchestrator.sessions.collect { refreshDeviceList() }
        }
        controllerScope.launch {
            orchestrator.locationManager.isActive.collect { refreshDeviceList() }
        }
    }

    // ---------------------------------------------------------------------------
    // CoreBluetooth central delegate
    // ---------------------------------------------------------------------------
    private val delegate: CBCentralManagerDelegateProtocol =
        object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                val restored = restoredAwaitingPowerOn.toList()
                restoredAwaitingPowerOn.clear()
                if (!appEnabled) {
                    stopScanIfNeeded()
                    restored.forEach { central.cancelPeripheralConnection(it) }
                    cancelAllKnownConnections()
                    controllerScope.launch {
                        autoReconnectStore.loadFromDisk()
                        migrateLegacyDevicesToDatabase()
                        syncPersistedDevices()
                    }
                    refreshDeviceList()
                    return
                }
                if (!central.isScanning) {
                    central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
                }
                controllerScope.launch {
                    attachRestoredPeripherals(restored)
                    reconnectToPersistedPeripherals()
                }
            }
        }

        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            @Suppress("UNCHECKED_CAST")
            val restoredPeripherals =
                willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>
                    ?: return

            restoredPeripherals.forEach { any ->
                val peripheral = any as? CBPeripheral ?: return@forEach
                if (appEnabled) {
                    val id = peripheral.identifier.UUIDString
                    // Re-set the delegate right away: CoreBluetooth delivers the
                    // callbacks that relaunched the app immediately after
                    // restoration, and they are dropped while the delegate is unset.
                    if (peripheral.state == CBPeripheralStateConnected) {
                        transport.registerPeripheral(peripheral)
                        connected[id] = peripheral
                    }
                    discovered[id] = peripheral
                }
                // Announcing/cancelling is deferred to PoweredOn (see
                // restoredAwaitingPowerOn) — the central cannot act yet.
                restoredAwaitingPowerOn += peripheral
            }
            refreshDeviceList()
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData
            if (mfgData == null || mfgData.length < 2u) return
            val bytes = mfgData.toByteArray()
            val companyId = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            if (companyId != 0x012D) return

            val id = didDiscoverPeripheral.identifier.UUIDString
            discovered[id] = didDiscoverPeripheral
            refreshDeviceList()
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral,
        ) {
            val id = didConnectPeripheral.identifier.UUIDString
            connected[id] = didConnectPeripheral
            autoReconnectStore.add(id)
            // The orchestrator drives service discovery + handshake from here
            transport.attachPeripheral(didConnectPeripheral)
            connectionEvents.tryEmit(ConnectionEvent.Connected(id))
            refreshDeviceList()
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val id = didFailToConnectPeripheral.identifier.UUIDString
            connectionEvents.tryEmit(ConnectionEvent.ConnectFailed(id))
            controllerScope.launch {
                 if (shouldAutoReconnect(id)) {
                     central.connectPeripheral(didFailToConnectPeripheral, options = null)
                 }
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            timestamp: Double,
            isReconnecting: Boolean,
            error: NSError?,
        ) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            connected.remove(id)
            // Emits Disconnected → orchestrator clears the session, cancels queued
            // operations and re-evaluates location tracking
            transport.detachPeripheral(id)
            connectionEvents.tryEmit(ConnectionEvent.Disconnected(id))

            controllerScope.launch {
                if (shouldAutoReconnect(id)) {
                      central.connectPeripheral(didDisconnectPeripheral, options = null)
                }
            }
            refreshDeviceList()
        }
    }

    private val central: CBCentralManager = CBCentralManager(
        delegate = delegate,
        queue = null,
        options = mapOf(CBCentralManagerOptionRestoreIdentifierKey to "com.saschl.cameragps.central"),
    )

    // ---------------------------------------------------------------------------
    // BluetoothController implementation
    // ---------------------------------------------------------------------------

    override suspend fun startScan() {
        if (central.state == CBManagerStatePoweredOn && !central.isScanning) {
            central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
        }
    }

    override suspend fun stopScan() {
        if (central.state == CBManagerStatePoweredOn) {
            central.stopScan()
        }
    }

    override suspend fun connect(identifier: String): Boolean {
        val resolvedIdentifier = resolveKnownIdentifier(identifier)
        val peripheral = discovered[resolvedIdentifier] ?: return false
        if (connected.containsKey(resolvedIdentifier)) return true
        if (central.state != CBManagerStatePoweredOn) {
            logging.e { "Cannot connect: CBCentralManager is not powered on" }
            return false
        }
        ensureDeviceRecord(resolvedIdentifier, peripheral.name)

        var event: ConnectionEvent? = null
        try {
            event = withTimeoutOrNull(CONNECT_TIMEOUT_MS.milliseconds) {
                connectionEvents
                    .onSubscription {
                        // Initiate only once the waiter is subscribed so the
                        // completion event cannot slip past it
                        central.connectPeripheral(
                            peripheral,
                            options = mapOf(CBConnectPeripheralOptionEnableAutoReconnect to true)
                        )
                    }
                    .first { it.identifier.equals(resolvedIdentifier, ignoreCase = true) }
            }
        } finally {
            // No event means timeout or caller cancellation — withdraw the pending
            // connect. On ConnectFailed the attempt is already dead, and cancelling
            // would kill the auto-reconnect didFailToConnect may just have issued.
            if (event == null && central.state == CBManagerStatePoweredOn) {
                central.cancelPeripheralConnection(peripheral)
            }
        }
        return event is ConnectionEvent.Connected
    }

    override suspend fun disconnect(identifier: String) {
        disconnectInternal(identifier, removeFromAutoReconnect = true)
    }

    private suspend fun disconnectInternal(identifier: String, removeFromAutoReconnect: Boolean) {
        val resolvedIdentifier = resolveKnownIdentifier(identifier)
        if (removeFromAutoReconnect) {
            autoReconnectStore.remove(identifier)
            autoReconnectStore.remove(identifier.uppercase())
        }
        val peripheral = connected[resolvedIdentifier] ?: discovered[resolvedIdentifier] ?: return
        if (central.state != CBManagerStatePoweredOn) {
            connected.remove(resolvedIdentifier)
            transport.detachPeripheral(resolvedIdentifier)
            refreshDeviceList()
            return
        }
        // Best effort: a pending (never-established) connect produces no
        // didDisconnect callback when cancelled — return after the timeout
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS.milliseconds) {
            connectionEvents
                .onSubscription { central.cancelPeripheralConnection(peripheral) }
                .first {
                    it is ConnectionEvent.Disconnected &&
                            it.identifier.equals(resolvedIdentifier, ignoreCase = true)
                }
        }
    }

    override suspend fun forgetDevice(identifier: String) {
        val resolvedIdentifier = resolveKnownIdentifier(identifier)
        val normalized = resolvedIdentifier.uppercase()
        disconnect(identifier)
        discovered.remove(resolvedIdentifier)
        connected.remove(resolvedIdentifier)
        transport.detachPeripheral(resolvedIdentifier)
        deviceEnabledOverrides.remove(normalized)
        persistedDevices.remove(normalized)
        deviceDao.deleteDevice(CameraDevice(mac = normalized))
        refreshDeviceList()
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
        deviceEnabledOverrides[normalized] = enabled
        persistedDevices[normalized] =
            persistedDevices[normalized]?.copy(deviceEnabled = enabled)
                ?: CameraDevice(mac = normalized, deviceEnabled = enabled)

        if (!enabled) {
            orchestrator.setRemoteMonitoring(normalized, false)
            controllerScope.launch {
                disconnectInternal(identifier, removeFromAutoReconnect = false)
            }
            return
        }

        if (appEnabled) {
            requestConnection(normalized)
        }
    }

    /**
     * Issue a (pending) connection request for a single device. Used when the
     * device is re-enabled instead of [reconnectToPersistedPeripherals]: only
     * this device changed, and the targeted connect relies on the already
     * updated in-memory enabled state rather than the sweep's database re-sync.
     */
    // FIXME do we still need this or use reconnectToPersistedPeripherals() instead?
    private fun requestConnection(identifier: String) {
        // Not powered on: the next power-on reconnects all saved devices anyway
        if (central.state != CBManagerStatePoweredOn) return
        val resolvedIdentifier = resolveKnownIdentifier(identifier)
        if (connected.containsKey(resolvedIdentifier)) return
        val peripheral = discovered[resolvedIdentifier]
            ?: central.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = identifier)))
                .filterIsInstance<CBPeripheral>()
                .firstOrNull()
            ?: return
        discovered[peripheral.identifier.UUIDString] = peripheral
        central.connectPeripheral(
            peripheral,
            options = mapOf(
                CBConnectPeripheralOptionNotifyOnConnectionKey to true,
                // CBConnectPeripheralOptionEnableAutoReconnect to true
            ),
        )
        refreshDeviceList()
    }

    suspend fun applyAppEnabledState(enabled: Boolean) {
        appEnabled = enabled
        if (enabled) {
            startScan()
            reconnectToPersistedPeripherals()
            orchestrator.locationManager.updateTracking()
            refreshDeviceList()
            return
        }
        forceShutdownAllConnections()
    }

    // ---------------------------------------------------------------------------
    // Shutdown / cleanup
    // ---------------------------------------------------------------------------

    private fun forceShutdownAllConnections() {
        stopScanIfNeeded()
        cancelAllKnownConnections()
        orchestrator.shutdownAll()
        transport.detachAll()

        // Cancelled pending connects produce no CoreBluetooth callback — resolve
        // every parked connect()/disconnect() waiter with a synthetic disconnect.
        (connected.keys + discovered.keys).forEach {
            connectionEvents.tryEmit(ConnectionEvent.Disconnected(it))
        }

        connected.clear()

        refreshDeviceList()
    }

    private fun cancelAllKnownConnections() {
        if (central.state != CBManagerStatePoweredOn) return
        val peripherals = mutableMapOf<String, CBPeripheral>()
        connected.forEach { (id, p) -> peripherals[id] = p }
        discovered.forEach { (id, p) -> peripherals[id] = p }
        peripherals.values.forEach { central.cancelPeripheralConnection(it) }
    }

    private fun stopScanIfNeeded() {
        if (central.state == CBManagerStatePoweredOn && central.isScanning) {
            central.stopScan()
        }
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
            if (!isDeviceEnabled(id)) {
                central.cancelPeripheralConnection(peripheral)
            } else if (peripheral.state == CBPeripheralStateConnected) {
                transport.attachPeripheral(peripheral)
            }
        }
        refreshDeviceList()
    }

    private suspend fun reconnectToPersistedPeripherals() {
        autoReconnectStore.loadFromDisk()
        migrateLegacyDevicesToDatabase()
        syncPersistedDevices()
        // The device database is the source of truth for saved devices; the
        // NSUserDefaults store is only read above to migrate legacy entries.
        val ids = persistedDevices.keys.toList()
        if (ids.isEmpty()) return
        val nsuuids = ids.map { NSUUID(uUIDString = it) }
        val peripherals = central.retrievePeripheralsWithIdentifiers(nsuuids)
        peripherals.forEach { any ->
            val peripheral = any as? CBPeripheral ?: return@forEach
            val id = peripheral.identifier.UUIDString
            if (!isDeviceEnabled(id)) {
                return@forEach
            }
            discovered[id] = peripheral
            if (!connected.containsKey(id)) {
                central.connectPeripheral(
                    peripheral,
                    options = mapOf(
                        CBConnectPeripheralOptionNotifyOnConnectionKey to true,
                        // CBConnectPeripheralOptionEnableAutoReconnect to true
                    ),
                )
            }
        }
        refreshDeviceList()
    }

    // UI state, should be viewmodel
    private fun refreshDeviceList() {
        val persistedByNormalized = persistedDevices
        val discoveredByNormalized = discovered.entries.associateBy { it.key.uppercase() }
        val connectedByNormalized = connected.keys.associateBy { it.uppercase() }
        val sessionsByNormalized = orchestrator.sessions.value
        val transmissionActive = orchestrator.locationManager.isActive.value
        val allIdentifiers = LinkedHashSet<String>()
        allIdentifiers.addAll(discoveredByNormalized.keys)
        allIdentifiers.addAll(persistedByNormalized.keys)

        _devices.update {
            allIdentifiers.map { normalizedId ->
                val discoveredEntry = discoveredByNormalized[normalizedId]
                val persistedEntry = persistedByNormalized[normalizedId]
                val peripheral = discoveredEntry?.value
                val identifier = discoveredEntry?.key ?: (persistedEntry?.mac ?: normalizedId)
                val session = sessionsByNormalized[normalizedId]
                BluetoothDeviceInfo(
                    identifier = identifier,
                    name = peripheral?.name ?: persistedEntry?.deviceName ?: "Unknown device",
                    isConnected = connectedByNormalized.containsKey(normalizedId),
                    isSaved = autoReconnectStore.contains(identifier) ||
                            autoReconnectStore.contains(normalizedId) ||
                            persistedEntry != null,
                    isTransmissionActive =
                        session?.phase == BleSessionPhase.Transmitting && transmissionActive,
                    isRemoteFeatureActive = session?.remoteFeatureActive == true,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------

    private suspend fun shouldAutoReconnect(id: String): Boolean {
        // A camera that rejected pairing is disconnected on purpose; reconnecting
        // would restart the pairing gate and loop the camera's pairing prompt.
        // Cleared when the user dismisses the dialog or a handshake succeeds.
        if (id.equals(pairingFailedIdentifier, ignoreCase = true)) return false
        return appEnabled &&
                autoReconnectStore.contains(id) &&
                central.state == CBManagerStatePoweredOn &&
                isDeviceEnabled(id)
    }

    private suspend fun isDeviceEnabled(identifier: String): Boolean {
        val normalized = identifier.uppercase()
        deviceEnabledOverrides[normalized]?.let { return it }

        val enabled = deviceDao.findDeviceEnabled(normalized)
        if (enabled != null) {
            deviceEnabledOverrides[normalized] = enabled
            return enabled
        }
        // No record yet: devices saved by pre-database versions only exist in
        // NSUserDefaults until migrateLegacyDevicesToDatabase runs at power-on
        // (state restoration can reach this earlier). They were always enabled.
        return true
    }

    private fun resolveKnownIdentifier(identifier: String): String {
        val normalized = identifier.uppercase()
        return connected.keys.firstOrNull { it.uppercase() == normalized }
            ?: discovered.keys.firstOrNull { it.uppercase() == normalized }
            ?: identifier
    }

    /**
     * Older app versions persisted saved devices only as peripheral UUIDs in
     * NSUserDefaults ([IosAutoReconnectStore]). Seed a database record for any
     * persisted peripheral that has none, so those devices keep their saved
     * state, name and toggles after the upgrade. Idempotent; a no-op once every
     * persisted peripheral has a record. Requires [autoReconnectStore] to be
     * loaded from disk.
     */
    private suspend fun migrateLegacyDevicesToDatabase() {
        val ids = autoReconnectStore.getAll()
        if (ids.isEmpty()) return

        val knownMacs = deviceDao.getAllCameraDevices().mapTo(mutableSetOf()) { it.mac.uppercase() }
        val missing = ids.filterNot { it.uppercase() in knownMacs }
        if (missing.isEmpty()) return

        val namesByNormalizedId = if (central.state == CBManagerStatePoweredOn) {
            central.retrievePeripheralsWithIdentifiers(missing.map { NSUUID(uUIDString = it) })
                .filterIsInstance<CBPeripheral>()
                .associate { it.identifier.UUIDString.uppercase() to it.name }
        } else {
            emptyMap()
        }

        missing.forEach { id ->
            val normalized = id.uppercase()
            deviceDao.insertDevice(
                CameraDevice(
                    mac = normalized,
                    deviceEnabled = true,
                    deviceName = namesByNormalizedId[normalized] ?: "N/A",
                    remoteControlEnabled = false,
                )
            )
            logging.i { "Migrated legacy saved device $normalized to the device database" }
        }
    }

    private suspend fun syncPersistedDevices() {
        val devicesFromDb = deviceDao.getAllCameraDevices()
        persistedDevices.clear()
        devicesFromDb.forEach { device ->
            val normalized = device.mac.uppercase()
            persistedDevices[normalized] = device.copy(mac = normalized)
            deviceEnabledOverrides[normalized] = device.deviceEnabled
        }
        refreshDeviceList()
    }

    suspend fun ensureDeviceRecord(identifier: String, deviceName: String? = null) {
        val resolvedName = deviceName
            ?: discovered.entries.firstOrNull {
                it.key.equals(
                    identifier,
                    ignoreCase = true
                )
            }?.value?.name
            ?: connected.entries.firstOrNull {
                it.key.equals(
                    identifier,
                    ignoreCase = true
                )
            }?.value?.name
            ?: "N/A"
        val normalized = identifier.uppercase()
        val entry = CameraDevice(mac = normalized, deviceName = resolvedName)
        deviceDao.insertDevice(entry)
        persistedDevices[normalized] =
            persistedDevices[normalized]?.copy(deviceName = resolvedName) ?: entry
        refreshDeviceList()
        syncPersistedDevices()
    }
}
