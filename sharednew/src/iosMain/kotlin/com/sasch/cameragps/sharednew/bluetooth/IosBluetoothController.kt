package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.ensureInitialized
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionCoordinator
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionEvent
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationDataConfig
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteControlCoordinator
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.DatabaseLogger
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBConnectPeripheralOptionNotifyOnConnectionKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * iOS Bluetooth controller — thin shell backed by CoreBluetooth.
 *
 * All BLE session handshake logic lives in shared [BleSessionCoordinator].
 * Location transmission is handled by [IosLocationTransmissionManager].
 * Auto-reconnect persistence is handled by [IosAutoReconnectStore].
 *
 * This class owns only:
 * - CoreBluetooth central + peripheral delegate wiring & state restoration
 * - iOS-specific pairing retry (auth error handling)
 * - Session map (PeripheralSession)
 * - Device list / UI state
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

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val deviceDao: CameraDeviceDAO by lazy {
        LogDatabase.getRoomDatabase(getDatabaseBuilder()).cameraDeviceDao()
    }

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    // UUID string -> CBPeripheral
    private val discovered = mutableMapOf<String, CBPeripheral>()
    private val connected = mutableMapOf<String, CBPeripheral>()
    private val sessions = mutableMapOf<String, PeripheralSession>()

    // Pending callbacks
    private val connectCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    private val disconnectCallbacks = mutableMapOf<String, () -> Unit>()

    private var appEnabled = IosAppPreferences.isAppEnabled()
    private val deviceEnabledOverrides = mutableMapOf<String, Boolean>()
    private val persistedDevices = mutableMapOf<String, CameraDevice>()

    // --- Extracted collaborators ---
    private val autoReconnectStore = IosAutoReconnectStore()

    private val gattPort = IosBleGattPort(object : IosBleGattPort.SessionProvider {
        override fun getSession(identifier: String): IosBleGattPort.IosBleSession? {
            return sessions[identifier]
        }
    })

    private val remoteControlCoordinator = RemoteControlCoordinator(
        port = gattPort,
        scope = controllerScope,
    )

    private val bleSessionCoordinator = BleSessionCoordinator(
        port = gattPort,
        remoteControlCoordinator = remoteControlCoordinator,
    )

    private val locationTransmissionManager = IosLocationTransmissionManager(
        scope = controllerScope,
        host = object : IosLocationTransmissionManager.Host {
            override fun getReadySessionIdentifiers(): Set<String> =
                sessions.entries
                    .filter { it.value.phase == PeripheralPhase.Ready }
                    .map { it.key }
                    .toSet()

            override fun getLocationDataConfig(identifier: String): LocationDataConfig? =
                bleSessionCoordinator.getLocationDataConfig(identifier)

            override fun writeLocationPacket(identifier: String, packet: ByteArray) {
                gattPort.writeCharacteristic(
                    identifier,
                    SonyBluetoothConstants.CHARACTERISTIC_UUID,
                    packet,
                )
            }

            override fun isAppEnabledForTransmission(): Boolean = appEnabled

            override fun onLocationTrackingChanged() = refreshDeviceList()
        },
    )

    fun hasPreciseAccuracyAuthorization(): Boolean =
        locationTransmissionManager.hasPreciseAccuracyAuthorization()

    init {
        controllerScope.launch {
            syncPersistedDevices()
        }
        // Collect shared coordinator events → update UI / session state
        controllerScope.launch {
            bleSessionCoordinator.events.collect { event ->
                when (event) {
                    is BleSessionEvent.HandshakeComplete -> {
                        if (!isDeviceEnabled(event.identifier)) {
                            disconnect(event.identifier)
                            return@collect
                        }
                        sessions[event.identifier]?.phase = PeripheralPhase.Ready
                        locationTransmissionManager.updateLocationTracking()
                        refreshDeviceList()
                        locationTransmissionManager.sendImmediateIfCached(event.identifier)
                        if (deviceDao.isRemoteControlEnabled(event.identifier.uppercase())) {
                            remoteControlCoordinator.startRemoteStatusMonitoring(event.identifier)
                        }
                    }

                    is BleSessionEvent.PhaseChanged -> {
                        refreshDeviceList()
                    }

                    is BleSessionEvent.RemoteFeatureActivated,
                    is BleSessionEvent.RemoteFeatureDeactivated -> {
                    }
                }
            }
        }
        controllerScope.launch {
            remoteControlCoordinator.events.collect { event ->
                when (event) {
                    is BleSessionEvent.RemoteFeatureActivated -> {
                        sessions[event.identifier]?.remoteFeatureActive = true
                        refreshDeviceList()
                    }

                    is BleSessionEvent.RemoteFeatureDeactivated -> {
                        sessions[event.identifier]?.remoteFeatureActive = false
                        refreshDeviceList()
                    }

                    is BleSessionEvent.PhaseChanged -> {

                    }
                    is BleSessionEvent.HandshakeComplete -> {
                        if (deviceDao.isRemoteControlEnabled(event.identifier.uppercase())) {
                            remoteControlCoordinator.startRemoteStatusMonitoring(event.identifier)
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // CoreBluetooth peripheral delegate — routes callbacks to shared coordinators
    // ---------------------------------------------------------------------------
    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            peripheral.services?.forEach { service ->
                peripheral.discoverCharacteristics(
                    characteristicUUIDs = null,
                    forService = service as CBService,
                )
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val session =
                sessions.getOrPut(peripheral.identifier.UUIDString) { PeripheralSession(peripheral) }

            didDiscoverCharacteristicsForService.characteristics?.forEach { characteristicAny ->
                val characteristic = characteristicAny as CBCharacteristic
                logging.v {
                    "  Discovered characteristic ${characteristic.UUID}  props=0x${
                        characteristic.properties.toString(16)
                    }"
                }

                // Cache remote-specific characteristics for IosBleGattPort
                when (characteristic.UUID) {
                    IosSonyBleConstants.REMOTE_CHARACTERISTIC_UUID_STRING ->
                        session.remoteControlCharacteristic = characteristic

                    IosSonyBleConstants.REMOTE_STATUS_UUID_STRING ->
                        session.remoteStatusCharacteristic = characteristic
                }

                val supportsNotify =
                    (characteristic.properties and CBCharacteristicPropertyNotify) != 0uL ||
                            (characteristic.properties and CBCharacteristicPropertyIndicate) != 0uL
                if (supportsNotify) {
                    session.notifiableCharacteristics.add(characteristic)
                }
            }

            // Subscribe to remote status updates early (optimization for iOS pairing flow)
            //subscribeToRemoteStatusUpdates(session)

            // Trigger pairing via notification subscription
            if (session.phase == PeripheralPhase.Connected) {
                val pairingTarget = session.notifiableCharacteristics.firstOrNull()
                if (pairingTarget != null) {
                    session.phase = PeripheralPhase.WaitingForPairing
                    logging.d {
                        "Subscribing to notifications on ${pairingTarget.UUID.UUIDString} to trigger pairing"
                    }
                    peripheral.setNotifyValue(true, forCharacteristic = pairingTarget)
                    // proceedAfterPairing(session, peripheral)

                } else {
                    logging.d { "No notifiable characteristic – proceeding without explicit pairing" }
                    proceedAfterPairing(session, peripheral)
                }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val session = sessions[peripheral.identifier.UUIDString] ?: return
            logging.v { "Read value for ${didUpdateValueForCharacteristic.UUID.UUIDString} (error=${error?.code} / ${error?.localizedDescription})" }

            if (isAuthenticationError(error)) {
                retryAfterPairing(session) {
                    peripheral.readValueForCharacteristic(didUpdateValueForCharacteristic)
                }
                return
            }

            val value = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            session.pairingRetryCount = 0
            val id = peripheral.identifier.UUIDString

            // Route to shared coordinators based on characteristic UUID
            when (didUpdateValueForCharacteristic.UUID) {
                IosSonyBleConstants.READ_CHARACTERISTIC_UUID_STRING -> {
                    bleSessionCoordinator.onCharacteristicRead(id, value, true)
                }

                IosSonyBleConstants.REMOTE_STATUS_UUID_STRING -> {
                    bleSessionCoordinator.onCharacteristicChanged(
                        id, SonyBluetoothConstants.REMOTE_STATUS_UUID, value,
                    )
                    refreshDeviceList()
                }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val session = sessions[peripheral.identifier.UUIDString] ?: return
            logging.v { "Write value for ${didWriteValueForCharacteristic.UUID.UUIDString} completed (error=${error?.code} / ${error?.localizedDescription})" }

            if (isAuthenticationError(error)) {
                retryAfterPairing(session) {
                    // Restart the handshake after pairing
                    session.phase = PeripheralPhase.Handshaking
                    bleSessionCoordinator.beginHandshake(peripheral.identifier.UUIDString)
                }
                return
            }

            if (error != null) {
                logging.e { "BLE write failed for ${didWriteValueForCharacteristic.UUID.UUIDString}: ${error.localizedDescription}" }
                return
            }

            session.pairingRetryCount = 0
            val id = peripheral.identifier.UUIDString

            // Map iOS CBUUID to shared UUID string and forward to shared coordinator
            when (didWriteValueForCharacteristic.UUID) {
                IosSonyBleConstants.ENABLE_UNLOCK_GPS_UUID_STRING ->
                    bleSessionCoordinator.onCharacteristicWrite(
                        id, SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND, true,
                    )

                IosSonyBleConstants.ENABLE_LOCK_GPS_UUID_STRING ->
                    bleSessionCoordinator.onCharacteristicWrite(
                        id, SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND, true,
                    )

                IosSonyBleConstants.TIME_SYNC_CHARACTERISTIC_UUID_STRING ->
                    bleSessionCoordinator.onCharacteristicWrite(
                        id, SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID, true,
                    )

                IosSonyBleConstants.REMOTE_CHARACTERISTIC_UUID_STRING ->
                    bleSessionCoordinator.onCharacteristicWrite(
                        id, SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID, true,
                    )
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val session = sessions[peripheral.identifier.UUIDString] ?: return

            // Remote status subscription result
            if (didUpdateNotificationStateForCharacteristic.UUID == IosSonyBleConstants.REMOTE_STATUS_UUID_STRING) {
                if (isAuthenticationError(error)) {
                    retryAfterPairing(session) {
                        peripheral.setNotifyValue(
                            true,
                            forCharacteristic = didUpdateNotificationStateForCharacteristic,
                        )
                    }
                    return
                }
                if (error == null) {
                    session.remoteStatusNotificationsEnabled = true
                    logging.i { "Subscribed to remote status notifications for ${peripheral.identifier.UUIDString}" }
                } else {
                    logging.w { "Remote status notification subscription failed: ${error.localizedDescription}" }
                }
                // only return if we're already active, otherwise let the handshake proceed
                if (session.phase !== PeripheralPhase.WaitingForPairing) {
                    return
                }
            }

            // Pairing result
            if (session.phase != PeripheralPhase.WaitingForPairing) return
            logging.d { "Notification subscription result for ${didUpdateNotificationStateForCharacteristic.UUID.UUIDString}: error=${error?.code}" }

            if (isAuthenticationError(error)) {
                retryAfterPairing(session) {
                    peripheral.setNotifyValue(
                        true,
                        forCharacteristic = didUpdateNotificationStateForCharacteristic,
                    )
                }
                return
            }

            session.pairingRetryCount = 0

            if (error != null) {
                logging.d { "Notification subscription failed (non-auth): ${error.localizedDescription} – continuing" }
            } else {
                logging.d { "Notification subscription succeeded – device is paired" }
                peripheral.setNotifyValue(
                    false,
                    forCharacteristic = didUpdateNotificationStateForCharacteristic,
                )
            }

            proceedAfterPairing(session, peripheral)
        }
    }

    // ---------------------------------------------------------------------------
    // CoreBluetooth central delegate
    // ---------------------------------------------------------------------------
    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                if (!appEnabled) {
                    stopScanIfNeeded()
                    cancelAllKnownConnections()
                    refreshDeviceList()
                    return
                }
                if (!central.isScanning) {
                    central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
                }
                controllerScope.launch {
                    reconnectToPersistedPeripherals()
                }
            }
        }

        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            @Suppress("UNCHECKED_CAST")
            val restoredPeripherals =
                willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>
                    ?: return

            if (appEnabled) {
                restoredPeripherals.forEach { any ->
                    val peripheral = any as? CBPeripheral ?: return@forEach
                    controllerScope.launch {
                        val id = peripheral.identifier.UUIDString
                        if (!isDeviceEnabled(id)) {
                            if (central.state == CBManagerStatePoweredOn) {
                                central.cancelPeripheralConnection(peripheral)
                            }
                            return@launch
                        }

                        discovered[id] = peripheral
                        peripheral.delegate = peripheralDelegate
                        if (peripheral.state == CBPeripheralStateConnected) {
                            connected[id] = peripheral
                            sessions.getOrPut(id) { PeripheralSession(peripheral) }
                            peripheral.discoverServices(
                                listOf(
                                    IosSonyBleConstants.LOCATION_SERVICE_UUID,
                                    IosSonyBleConstants.CONTROL_SERVICE_UUID_STRING,
                                    IosSonyBleConstants.REMOTE_SERVICE_UUID,
                                )
                            )
                        }
                        refreshDeviceList()
                    }
                }
                refreshDeviceList()
            } else {
                restoredPeripherals.forEach { any ->
                    val peripheral = any as? CBPeripheral ?: return@forEach
                    if (central.state == CBManagerStatePoweredOn) {
                        central.cancelPeripheralConnection(peripheral)
                    }
                }
            }
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
            sessions.getOrPut(id) { PeripheralSession(didConnectPeripheral) }
            didConnectPeripheral.delegate = peripheralDelegate
            didConnectPeripheral.discoverServices(
                listOf(
                    IosSonyBleConstants.LOCATION_SERVICE_UUID,
                    IosSonyBleConstants.CONTROL_SERVICE_UUID_STRING,
                    IosSonyBleConstants.REMOTE_SERVICE_UUID,
                )
            )
            connectCallbacks.remove(id)?.invoke(true)
            refreshDeviceList()
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val id = didFailToConnectPeripheral.identifier.UUIDString
            connectCallbacks.remove(id)?.invoke(false)
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
            error: NSError?,
        ) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            connected.remove(id)
            sessions.remove(id)
            connectCallbacks.remove(id)?.invoke(false)
            disconnectCallbacks.remove(id)?.invoke()
            bleSessionCoordinator.clearSession(id)

            controllerScope.launch {
                if (shouldAutoReconnect(id)) {
                    central.connectPeripheral(didDisconnectPeripheral, options = null)
                }
            }

            locationTransmissionManager.updateLocationTracking()
            refreshDeviceList()
        }
    }

    private val central = CBCentralManager(
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
        return suspendCancellableCoroutine { cont ->
            connectCallbacks[resolvedIdentifier] = { success ->
                if (cont.isActive) cont.resume(success)
            }
            central.connectPeripheral(peripheral, options = null)
            cont.invokeOnCancellation {
                connectCallbacks.remove(resolvedIdentifier)
                if (central.state == CBManagerStatePoweredOn) {
                    central.cancelPeripheralConnection(peripheral)
                }
            }
        }
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
            sessions.remove(resolvedIdentifier)
            bleSessionCoordinator.clearSession(resolvedIdentifier)
            locationTransmissionManager.updateLocationTracking()
            refreshDeviceList()
            return
        }
        suspendCancellableCoroutine { cont ->
            disconnectCallbacks[resolvedIdentifier] = {
                if (cont.isActive) cont.resume(Unit)
            }
            central.cancelPeripheralConnection(peripheral)
            cont.invokeOnCancellation {
                disconnectCallbacks.remove(resolvedIdentifier)
            }
        }
    }

    override suspend fun forgetDevice(identifier: String) {
        val resolvedIdentifier = resolveKnownIdentifier(identifier)
        val normalized = resolvedIdentifier.uppercase()
        disconnect(identifier)
        discovered.remove(resolvedIdentifier)
        connected.remove(resolvedIdentifier)
        sessions.remove(resolvedIdentifier)
        deviceEnabledOverrides.remove(normalized)
        persistedDevices.remove(normalized)
        deviceDao.deleteDevice(CameraDevice(mac = normalized))
        refreshDeviceList()
    }

    fun triggerRemoteShutter(identifier: String): Boolean {
        val session = sessions[identifier] ?: return false
        if (session.phase != PeripheralPhase.Ready) return false
        return bleSessionCoordinator.triggerRemoteShutter(identifier)
    }

    fun setRemoteStatusMonitoringEnabled(identifier: String, enabled: Boolean) {
        val normalized = identifier.uppercase()
        if (enabled) {
            remoteControlCoordinator.startRemoteStatusMonitoring(normalized)
        } else {
            remoteControlCoordinator.cancelProbe(normalized)
        }
    }

    fun applyDeviceEnabledState(identifier: String, enabled: Boolean) {
        val normalized = identifier.uppercase()
        deviceEnabledOverrides[normalized] = enabled
        persistedDevices[normalized] =
            persistedDevices[normalized]?.copy(deviceEnabled = enabled)
                ?: CameraDevice(mac = normalized, deviceEnabled = enabled)

        if (!enabled) {
            remoteControlCoordinator.cancelProbe(normalized)
            controllerScope.launch {
                disconnectInternal(identifier, removeFromAutoReconnect = false)
            }
            return
        }

        if (appEnabled) {
            controllerScope.launch {
                reconnectToPersistedPeripherals()
            }
        }
    }

    suspend fun applyAppEnabledState(enabled: Boolean) {
        appEnabled = enabled
        if (enabled) {
            startScan()
            reconnectToPersistedPeripherals()
            locationTransmissionManager.updateLocationTracking()
            refreshDeviceList()
            return
        }
        forceShutdownAllConnections()
    }

    // ---------------------------------------------------------------------------
    // iOS-specific pairing helpers
    // ---------------------------------------------------------------------------

    private fun isAuthenticationError(error: NSError?): Boolean {
        if (error == null) return false
        return error.code == IosSonyBleConstants.ATT_ERROR_INSUFFICIENT_AUTHENTICATION ||
                error.code == IosSonyBleConstants.ATT_ERROR_INSUFFICIENT_ENCRYPTION
    }

    private fun retryAfterPairing(session: PeripheralSession, operation: () -> Unit) {
        if (session.pairingRetryCount >= IosSonyBleConstants.MAX_PAIRING_RETRIES) {
            logging.e { "Pairing failed after ${IosSonyBleConstants.MAX_PAIRING_RETRIES} retries, disconnecting" }
            if (central.state == CBManagerStatePoweredOn) {
                central.cancelPeripheralConnection(session.peripheral)
            }
            return
        }
        session.pairingRetryCount++
        session.phase = PeripheralPhase.WaitingForPairing
        logging.d { "Auth error – retrying in ${IosSonyBleConstants.PAIRING_RETRY_DELAY_MS}ms (attempt ${session.pairingRetryCount}/${IosSonyBleConstants.MAX_PAIRING_RETRIES})" }
        controllerScope.launch {
            delay(IosSonyBleConstants.PAIRING_RETRY_DELAY_MS)
            operation()
        }
    }

    private fun proceedAfterPairing(session: PeripheralSession, peripheral: CBPeripheral) {
        session.phase = PeripheralPhase.Handshaking
        bleSessionCoordinator.beginHandshake(peripheral.identifier.UUIDString)
    }

    private fun subscribeToRemoteStatusUpdates(session: PeripheralSession) {
        val statusCharacteristic = session.remoteStatusCharacteristic ?: return
        if (session.remoteStatusNotificationsEnabled) return
        session.peripheral.setNotifyValue(true, forCharacteristic = statusCharacteristic)
    }

    // ---------------------------------------------------------------------------
    // Shutdown / cleanup
    // ---------------------------------------------------------------------------

    private fun forceShutdownAllConnections() {
        stopScanIfNeeded()
        cancelAllKnownConnections()
        bleSessionCoordinator.clearAllSessions()

        connectCallbacks.values.forEach { it(false) }
        connectCallbacks.clear()
        disconnectCallbacks.values.forEach { it() }
        disconnectCallbacks.clear()

        connected.clear()
        sessions.clear()

        locationTransmissionManager.shutdown()

        refreshDeviceList()
    }

    private fun cancelAllKnownConnections() {
        if (central.state != CBManagerStatePoweredOn) return
        val peripherals = mutableMapOf<String, CBPeripheral>()
        connected.forEach { (id, p) -> peripherals[id] = p }
        discovered.forEach { (id, p) -> peripherals[id] = p }
        sessions.forEach { (id, s) -> peripherals[id] = s.peripheral }
        peripherals.values.forEach { central.cancelPeripheralConnection(it) }
    }

    private fun stopScanIfNeeded() {
        if (central.state == CBManagerStatePoweredOn && central.isScanning) {
            central.stopScan()
        }
    }

    // ---------------------------------------------------------------------------
    // Auto-reconnect
    // ---------------------------------------------------------------------------

    private suspend fun reconnectToPersistedPeripherals() {
        autoReconnectStore.loadFromDisk()
        syncPersistedDevices()
        val ids = autoReconnectStore.getAll()
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
                    options = mapOf(CBConnectPeripheralOptionNotifyOnConnectionKey to true),
                )
            }
        }
        refreshDeviceList()
    }

    // ---------------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------------

    private fun refreshDeviceList() {
        val persistedByNormalized = persistedDevices
        val discoveredByNormalized = discovered.entries.associateBy { it.key.uppercase() }
        val connectedByNormalized = connected.keys.associateBy { it.uppercase() }
        val sessionsByNormalized = sessions.entries.associateBy { it.key.uppercase() }
        val allIdentifiers = LinkedHashSet<String>()
        allIdentifiers.addAll(discoveredByNormalized.keys)
        allIdentifiers.addAll(persistedByNormalized.keys)

        _devices.update {
            allIdentifiers.map { normalizedId ->
                val discoveredEntry = discoveredByNormalized[normalizedId]
                val persistedEntry = persistedByNormalized[normalizedId]
                val peripheral = discoveredEntry?.value
                val identifier = discoveredEntry?.key ?: (persistedEntry?.mac ?: normalizedId)
                val session = sessionsByNormalized[normalizedId]?.value
                BluetoothDeviceInfo(
                    identifier = identifier,
                    name = peripheral?.name ?: persistedEntry?.deviceName ?: "Unknown device",
                    isConnected = connectedByNormalized.containsKey(normalizedId),
                    isSaved = autoReconnectStore.contains(identifier) ||
                            autoReconnectStore.contains(normalizedId) ||
                            persistedEntry != null,
                    isTransmissionActive =
                        session?.phase == PeripheralPhase.Ready &&
                                locationTransmissionManager.isLocationUpdatesStarted,
                    isRemoteFeatureActive = session?.remoteFeatureActive == true,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------

    private suspend fun shouldAutoReconnect(id: String): Boolean {
        return appEnabled &&
                autoReconnectStore.contains(id) &&
                central.state == CBManagerStatePoweredOn &&
                isDeviceEnabled(id)
    }

    private suspend fun isDeviceEnabled(identifier: String): Boolean {
        val normalized = identifier.uppercase()
        deviceEnabledOverrides[normalized]?.let { return it }

        val enabled = deviceDao.isDeviceEnabled(normalized)
        deviceEnabledOverrides[normalized] = enabled
        return enabled
    }

    private fun resolveKnownIdentifier(identifier: String): String {
        val normalized = identifier.uppercase()
        return connected.keys.firstOrNull { it.uppercase() == normalized }
            ?: discovered.keys.firstOrNull { it.uppercase() == normalized }
            ?: sessions.keys.firstOrNull { it.uppercase() == normalized }
            ?: identifier
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

// ---------------------------------------------------------------------------
// iOS-specific session state
// ---------------------------------------------------------------------------

private enum class PeripheralPhase {
    Connected,
    WaitingForPairing,
    Handshaking,
    Ready,
}

@OptIn(ExperimentalForeignApi::class)
private data class PeripheralSession(
    override val peripheral: CBPeripheral,
    override var remoteControlCharacteristic: CBCharacteristic? = null,
    override var remoteStatusCharacteristic: CBCharacteristic? = null,
    override var remoteStatusNotificationsEnabled: Boolean = false,
    override var remoteFeatureActive: Boolean = false,
    var phase: PeripheralPhase = PeripheralPhase.Connected,
    var pairingRetryCount: Int = 0,
    val notifiableCharacteristics: MutableList<CBCharacteristic> = mutableListOf(),
) : IosBleGattPort.IosBleSession

private object IosSonyBleConstants {
    val LOCATION_SERVICE_UUID = CBUUID.UUIDWithString(SonyBluetoothConstants.SERVICE_UUID)
    val CONTROL_SERVICE_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CONTROL_SERVICE_UUID)
    val READ_CHARACTERISTIC_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_READ_UUID)
    val ENABLE_UNLOCK_GPS_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND)
    val ENABLE_LOCK_GPS_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND)
    val TIME_SYNC_CHARACTERISTIC_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID)
    val REMOTE_SERVICE_UUID =
        CBUUID.UUIDWithString(SonyBluetoothConstants.REMOTE_SERVICE_UUID)
    val REMOTE_CHARACTERISTIC_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID)
    val REMOTE_STATUS_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.REMOTE_STATUS_UUID)

    const val ATT_ERROR_INSUFFICIENT_AUTHENTICATION = 5L
    const val ATT_ERROR_INSUFFICIENT_ENCRYPTION = 15L
    const val MAX_PAIRING_RETRIES = 3
    const val PAIRING_RETRY_DELAY_MS = 3_000L
}


@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)

    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
