package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosLaunchContext
import com.sasch.cameragps.sharednew.bluetooth.restore.PeripheralRestoreState
import com.sasch.cameragps.sharednew.bluetooth.restore.RestorePolicy
import com.sasch.cameragps.sharednew.bluetooth.restore.ScanAction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCentralManagerRestoredStateScanOptionsKey
import platform.CoreBluetooth.CBCentralManagerRestoredStateScanServicesKey
import platform.CoreBluetooth.CBConnectPeripheralOptionEnableAutoReconnect
import platform.CoreBluetooth.CBConnectPeripheralOptionNotifyOnConnectionKey
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBPeripheralStateConnecting
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState.UIApplicationStateActive
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * CoreBluetooth mechanics: the CBCentralManager, its delegate, the
 * discovered/connected peripheral maps, state-restoration parking, scanning and
 * the connect/disconnect await machinery. NO policy — auto-reconnect decisions,
 * persistence and device-list assembly live in [IosBluetoothController], reached
 * through the constructor lambdas.
 *
 * Reentrancy contract: [central] is this class's LAST property, and
 * `willRestoreState` can fire synchronously DURING its construction — which is
 * during the controller's `shell` property initializer. Callbacks that can fire
 * on that path therefore receive the shell as a PARAMETER
 * ([onKnownPeripheralsChanged]) and must not read the controller's `shell`
 * field. [onPoweredOn] and the reconnect lambdas only ever fire async after
 * construction (CoreBluetooth dispatches them; that is why
 * [restoredAwaitingPowerOn] exists) and may use the controller's field. First
 * touch must happen on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosCentralShell(
    private val scope: CoroutineScope,
    private val transport: IosBleTransport,
    private val isAppEnabled: () -> Boolean,
    private val shouldAutoReconnect: suspend (String) -> Boolean,
    private val onPoweredOn: (restored: List<CBPeripheral>) -> Unit,
    /**
     * Central power state changed. Like [onPoweredOn] this only ever fires
     * asynchronously after construction, so it may read the controller's `shell`
     * field.
     */
    private val onCentralStateChanged: (poweredOn: Boolean) -> Unit,
    private val onPeripheralConnected: (String) -> Unit,
    private val onKnownPeripheralsChanged: (IosCentralShell) -> Unit,
) {

    private val log = logging()

    /**
     * Service filter of a scan the system restored on our behalf, and whether
     * there was one at all. Read in `willRestoreState`, acted on at power-on:
     * a restored scan keeps running until somebody stops it, and on a background
     * launch there is no UI to do that.
     */
    private var restoredScanServices: List<CBUUID>? = null
    private var scanWasRestored = false

    // UUID string -> CBPeripheral
    private val discovered = mutableMapOf<String, CBPeripheral>()
    private val connected = mutableMapOf<String, CBPeripheral>()

    /** Read-only views for device-list assembly and name resolution. */
    val discoveredPeripherals: Map<String, CBPeripheral> get() = discovered
    val connectedPeripherals: Map<String, CBPeripheral> get() = connected

    /** How many restored peripherals are waiting for power-on. Diagnostics only. */
    val parkedRestoredCount: Int get() = restoredAwaitingPowerOn.size

    /**
     * Peripherals handed back by state restoration before the central reported
     * PoweredOn. Announcing them (attach → discovery) or cancelling them must
     * wait for PoweredOn — CoreBluetooth drops both discoverServices and
     * cancelPeripheralConnection issued earlier.
     */
    private val restoredAwaitingPowerOn = mutableListOf<CBPeripheral>()

    /**
     * Central-level connection lifecycle, one stream for all devices.
     * [awaitConnect]/[awaitDisconnect] await their device's event here instead
     * of parking per-device callbacks — the same pattern the transport layer
     * uses for GATT operations. Multiple concurrent waiters per device are fine.
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

    private companion object {
        /** CoreBluetooth connects never time out on their own; bound the callers. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val DISCONNECT_TIMEOUT_MS = 10_000L

        /** Bluetooth SIG company identifier for Sony, as advertised by the cameras. */
        const val SONY_COMPANY_ID = 0x012D
    }

    // ---------------------------------------------------------------------------
    // CoreBluetooth central delegate
    // ---------------------------------------------------------------------------
    private val delegate: CBCentralManagerDelegateProtocol =
        object : NSObject(), CBCentralManagerDelegateProtocol {

            override fun centralManagerDidUpdateState(central: CBCentralManager) {
                val poweredOn = central.state == CBManagerStatePoweredOn
                log.i { "Central state: ${stateName(central.state)}" }
                onCentralStateChanged(poweredOn)

                if (poweredOn) {
                    applyRestoredScanPolicy()
                    val restored = restoredAwaitingPowerOn.toList()
                    restoredAwaitingPowerOn.clear()
                    onPoweredOn(restored)
                    return
                }

                // PoweredOff/Resetting/Unauthorized/Unsupported invalidate every
                // peripheral, and CoreBluetooth does not reliably deliver
                // didDisconnectPeripheral for them. Without an explicit teardown
                // the connected map keeps stale entries forever: the list shows
                // "connected", sessions stay ready, and
                // LocationTransmissionManager never stops location.
                //
                // Unknown is deliberately excluded. It is the pre-initialization
                // state and does not invalidate anything, and on a restoration
                // launch it can arrive after willRestoreState has already handed
                // us live peripherals — tearing those down would throw away the
                // restoration this whole path exists to serve.
                if (central.state != CBManagerStateUnknown) tearDownAllConnections()
            }

            override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
                @Suppress("UNCHECKED_CAST")
                restoredScanServices =
                    willRestoreState[CBCentralManagerRestoredStateScanServicesKey] as? List<CBUUID>
                scanWasRestored = restoredScanServices != null ||
                        willRestoreState[CBCentralManagerRestoredStateScanOptionsKey] != null

                val restoredPeripherals =
                    willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>
                        ?: emptyList<Any?>()

                log.i {
                    "willRestoreState: ${restoredPeripherals.size} peripheral(s), " +
                            "scanRestored=$scanWasRestored " +
                            "scanServices=${restoredScanServices?.map { it.UUIDString }}, " +
                            "appEnabled=${isAppEnabled()}"
                }

                val appEnabled = isAppEnabled()
                restoredPeripherals.forEach { any ->
                    val peripheral = any as? CBPeripheral ?: return@forEach
                    val id = peripheral.identifier.UUIDString
                    val state = restoreStateOf(peripheral)
                    log.i { "Restored peripheral $id in state $state" }

                    val action = RestorePolicy.onWillRestore(state, appEnabled)
                    // Re-set the delegate right away: CoreBluetooth delivers the
                    // callbacks that relaunched the app immediately after
                    // restoration, and they are dropped while the delegate is unset.
                    // This covers a peripheral restored mid-connect too, which is
                    // the most common restoration shape here — registering only
                    // assigns the delegate, it does not announce a connection.
                    if (action.registerDelegate) transport.registerPeripheral(peripheral)
                    if (action.markConnected) connected[id] = peripheral
                    if (action.trackAsDiscovered) discovered[id] = peripheral
                    // Announcing/cancelling is deferred to PoweredOn (see
                    // restoredAwaitingPowerOn) — the central cannot act yet.
                    if (action.park) restoredAwaitingPowerOn += peripheral
                }
                onKnownPeripheralsChanged(this@IosCentralShell)
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
                if (companyId != SONY_COMPANY_ID) return

                val id = didDiscoverPeripheral.identifier.UUIDString
                if (id !in discovered) {
                    log.d { "Discovered Sony peripheral $id (${didDiscoverPeripheral.name})" }
                }
                discovered[id] = didDiscoverPeripheral
                onKnownPeripheralsChanged(this@IosCentralShell)
            }

            override fun centralManager(
                central: CBCentralManager,
                didConnectPeripheral: CBPeripheral,
            ) {
                val id = didConnectPeripheral.identifier.UUIDString
                log.i { "Connected to $id (${didConnectPeripheral.name})" }
                connected[id] = didConnectPeripheral
                // The orchestrator drives service discovery + handshake from here
                transport.attachPeripheral(didConnectPeripheral)
                connectionEvents.tryEmit(ConnectionEvent.Connected(id))
                onPeripheralConnected(id)
            }

            @ObjCSignatureOverride
            override fun centralManager(
                central: CBCentralManager,
                didFailToConnectPeripheral: CBPeripheral,
                error: NSError?,
            ) {
                val id = didFailToConnectPeripheral.identifier.UUIDString
                log.w { "Failed to connect $id: ${error?.localizedDescription}" }
                connectionEvents.tryEmit(ConnectionEvent.ConnectFailed(id))
                scope.launch {
                    if (central.state == CBManagerStatePoweredOn && shouldAutoReconnect(id)) {
                        log.i { "Re-issuing pending connect for $id after connect failure" }
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
                log.i {
                    "Disconnected $id isReconnecting=$isReconnecting " +
                            "error=${error?.localizedDescription}"
                }
                connected.remove(id)
                // Emits Disconnected → orchestrator clears the session, cancels queued
                // operations and re-evaluates location tracking
                transport.detachPeripheral(id)
                connectionEvents.tryEmit(ConnectionEvent.Disconnected(id))

                scope.launch {
                    if (central.state == CBManagerStatePoweredOn && shouldAutoReconnect(id)) {
                        log.i { "Re-issuing pending connect for $id after disconnect" }
                        central.connectPeripheral(didDisconnectPeripheral, options = null)
                    }
                }
                onKnownPeripheralsChanged(this@IosCentralShell)
            }
        }

    /**
     * Nulled by [teardown]. Held nullably so the CBCentralManager is released
     * even if something still holds this shell: AccessorySetupKit refuses its
     * picker while a manager is alive, and Kotlin/Native releases Objective-C
     * objects on its collector rather than immediately, so every reference to it
     * has to be dropped explicitly.
     */
    private var centralOrNull: CBCentralManager? = CBCentralManager(
        delegate = delegate,
        queue = null,
        options = mapOf(CBCentralManagerOptionRestoreIdentifierKey to "com.saschl.cameragps.central"),
    )

    private val central: CBCentralManager? get() = centralOrNull

    /**
     * Release the manager and stop it calling back.
     *
     * Called before the shell reference is dropped, so nothing can arrive on a
     * half-dismantled shell and the manager itself is freed promptly.
     */
    fun teardown() {
        val manager = centralOrNull ?: return
        log.i { "Tearing down the CBCentralManager" }
        runCatching { if (manager.isScanning) manager.stopScan() }
        manager.delegate = null
        centralOrNull = null
        connected.clear()
        discovered.clear()
        restoredAwaitingPowerOn.clear()
    }

    // ---------------------------------------------------------------------------
    // Primitives composed by the controller
    // ---------------------------------------------------------------------------

    val isPoweredOn: Boolean get() = central?.state == CBManagerStatePoweredOn

    fun isConnected(identifier: String): Boolean = connected.containsKey(identifier)

    fun discoveredPeripheral(identifier: String): CBPeripheral? = discovered[identifier]

    fun resolveKnownIdentifier(identifier: String): String {
        val normalized = identifier.uppercase()
        return connected.keys.firstOrNull { it.uppercase() == normalized }
            ?: discovered.keys.firstOrNull { it.uppercase() == normalized }
            ?: identifier
    }

    /** Case-insensitive peripheral-name lookup across both maps. */
    fun peripheralName(identifier: String): String? =
        discovered.entries.firstOrNull { it.key.equals(identifier, ignoreCase = true) }?.value?.name
            ?: connected.entries.firstOrNull { it.key.equals(identifier, ignoreCase = true) }
                ?.value?.name

    /** Resolve peripheral UUIDs to names; empty when the central is not powered on. */
    fun retrieveNames(identifiers: List<String>): Map<String, String?> {
        if (!isPoweredOn) return emptyMap()
        return central?.retrievePeripheralsWithIdentifiers(identifiers.map { NSUUID(uUIDString = it) })
            ?.filterIsInstance<CBPeripheral>()
            ?.associate { it.identifier.UUIDString.uppercase() to it.name }
            ?: emptyMap()
    }

    fun retrievePeripherals(identifiers: List<String>): List<CBPeripheral> =
        central?.retrievePeripheralsWithIdentifiers(identifiers.map { NSUUID(uUIDString = it) })
            ?.filterIsInstance<CBPeripheral>()
            .orEmpty()

    /**
     * Start scanning, if it is both needed and useful.
     *
     * iOS delivers no discoveries at all from an unfiltered scan once the app
     * leaves the foreground, and it strips manufacturer data from background
     * results, so an unfiltered background scan burns radio for nothing. Refuse
     * it rather than letting the mistake recur; a caller that has a real service
     * filter may scan anywhere.
     */
    fun startScanIfNeeded(serviceUUIDs: List<CBUUID>? = null) {
        if (!isPoweredOn || central?.isScanning != false) return
        if (!RestorePolicy.allowsScan(hasServiceFilter = serviceUUIDs != null, appActive = isAppActive())) {
            log.w { "Refusing an unfiltered scan while the app is not foreground-active" }
            return
        }
        log.i { "Starting scan (filter=${serviceUUIDs?.map { it.UUIDString } ?: "none"})" }
        central?.scanForPeripheralsWithServices(serviceUUIDs = serviceUUIDs, options = null)
    }

    fun stopScan() {
        if (isPoweredOn) central?.stopScan()
    }

    fun stopScanIfNeeded() {
        if (isPoweredOn && central?.isScanning == true) central?.stopScan()
    }

    fun cancelConnection(peripheral: CBPeripheral) {
        if (isPoweredOn) central?.cancelPeripheralConnection(peripheral)
    }

    fun cancelConnections(peripherals: List<CBPeripheral>) {
        peripherals.forEach { cancelConnection(it) }
    }

    fun cancelAllKnownConnections() {
        if (!isPoweredOn) return
        val peripherals = mutableMapOf<String, CBPeripheral>()
        connected.forEach { (id, p) -> peripherals[id] = p }
        discovered.forEach { (id, p) -> peripherals[id] = p }
        peripherals.values.forEach { central?.cancelPeripheralConnection(it) }
    }

    /**
     * Initiate a connect and await its outcome. Returns `true` on Connected.
     * The caller has already done the discovered/connected/powered-on guards.
     */
    suspend fun awaitConnect(peripheral: CBPeripheral, resolvedIdentifier: String): Boolean {
        var event: ConnectionEvent? = null
        try {
            event = withTimeoutOrNull(CONNECT_TIMEOUT_MS.milliseconds) {
                connectionEvents
                    .onSubscription {
                        // Initiate only once the waiter is subscribed so the
                        // completion event cannot slip past it
                        central?.connectPeripheral(
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
            if (event == null && isPoweredOn) {
                central?.cancelPeripheralConnection(peripheral)
            }
        }
        return event is ConnectionEvent.Connected
    }

    /** Cancel the connection for [resolvedIdentifier] and await the disconnect. */
    suspend fun awaitDisconnect(resolvedIdentifier: String) {
        val peripheral = connected[resolvedIdentifier] ?: discovered[resolvedIdentifier] ?: return
        if (!isPoweredOn) {
            connected.remove(resolvedIdentifier)
            transport.detachPeripheral(resolvedIdentifier)
            onKnownPeripheralsChanged(this)
            return
        }
        // Best effort: a pending (never-established) connect produces no
        // didDisconnect callback when cancelled — return after the timeout
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS.milliseconds) {
            connectionEvents
                .onSubscription { central?.cancelPeripheralConnection(peripheral) }
                .first {
                    it is ConnectionEvent.Disconnected &&
                            it.identifier.equals(resolvedIdentifier, ignoreCase = true)
                }
        }
    }

    /**
     * Targeted connect for one device: retrieve (or reuse) the peripheral,
     * register it as discovered and issue a pending connect. Returns `true` if
     * a connect was initiated (or the device is already connected/unavailable
     * handling matches the previous requestConnection semantics).
     */
    fun retrieveAndConnect(identifier: String): Boolean {
        // Not powered on: the next power-on reconnects all saved devices anyway
        if (!isPoweredOn) return false
        val resolvedIdentifier = resolveKnownIdentifier(identifier)
        if (connected.containsKey(resolvedIdentifier)) return false
        val peripheral = discovered[resolvedIdentifier]
            ?: central?.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = identifier)))
                ?.filterIsInstance<CBPeripheral>()
                ?.firstOrNull()
            ?: return false
        discovered[peripheral.identifier.UUIDString] = peripheral
        central?.connectPeripheral(
            peripheral,
            options = mapOf(
                CBConnectPeripheralOptionNotifyOnConnectionKey to true,
                // CBConnectPeripheralOptionEnableAutoReconnect to true
            ),
        )
        return true
    }

    /** Reconnect-sweep step: register [peripheral] and connect unless already connected. */
    fun registerAndConnect(peripheral: CBPeripheral) {
        val id = peripheral.identifier.UUIDString
        discovered[id] = peripheral
        if (!connected.containsKey(id)) {
            central?.connectPeripheral(
                peripheral,
                options = mapOf(
                    CBConnectPeripheralOptionNotifyOnConnectionKey to true,
                    // CBConnectPeripheralOptionEnableAutoReconnect to true
                ),
            )
        }
    }

    /** Drop one device from both maps and the transport (forget flow). */
    fun forget(resolvedIdentifier: String) {
        discovered.remove(resolvedIdentifier)
        connected.remove(resolvedIdentifier)
        transport.detachPeripheral(resolvedIdentifier)
    }

    /**
     * Cancelled pending connects produce no CoreBluetooth callback — resolve
     * every parked awaitConnect/awaitDisconnect waiter with a synthetic
     * disconnect and clear the connected map (force-shutdown flow).
     */
    fun resolveAllWaitersDisconnected() {
        (connected.keys + discovered.keys).forEach {
            connectionEvents.tryEmit(ConnectionEvent.Disconnected(it))
        }
        connected.clear()
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private fun isAppActive(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationStateActive

    private fun restoreStateOf(peripheral: CBPeripheral): PeripheralRestoreState =
        when (peripheral.state) {
            CBPeripheralStateConnected -> PeripheralRestoreState.Connected
            CBPeripheralStateConnecting -> PeripheralRestoreState.Connecting
            else -> PeripheralRestoreState.Other
        }

    /**
     * A scan the system restored keeps running on the app's behalf. On a
     * background launch no UI is ever attached, so the device-list scan effect
     * that normally owns scanning never runs and would never stop it.
     */
    private fun applyRestoredScanPolicy() {
        if (!scanWasRestored) return
        val action = RestorePolicy.scanOnPowerOn(
            launchKind = IosLaunchContext.launchKind,
            appActive = isAppActive(),
            appEnabled = isAppEnabled(),
            scanWasRestored = true,
        )
        if (action == ScanAction.Stop) {
            log.i { "Stopping the scan restored by the system" }
            stopScanIfNeeded()
        }
        scanWasRestored = false
        restoredScanServices = null
    }

    /**
     * Tear down every known link after the central left PoweredOn. Mirrors what
     * a real disconnect does, because CoreBluetooth will not send one here.
     */
    private fun tearDownAllConnections() {
        val known = LinkedHashSet<String>().apply {
            addAll(connected.keys)
            addAll(discovered.keys)
        }
        if (known.isEmpty()) return
        log.i { "Central left PoweredOn — tearing down ${connected.size} connection(s)" }

        connected.keys.toList().forEach { transport.detachPeripheral(it) }
        known.forEach { connectionEvents.tryEmit(ConnectionEvent.Disconnected(it)) }

        connected.clear()
        discovered.clear()
        // restoredAwaitingPowerOn is deliberately kept: it is drained on the next
        // PoweredOn, and dropping it would lose the disabled-device cancel path
        // for peripherals the system restored.
        onKnownPeripheralsChanged(this@IosCentralShell)
    }

    private fun stateName(state: CBManagerState): String = when (state) {
        CBManagerStatePoweredOn -> "PoweredOn"
        CBManagerStatePoweredOff -> "PoweredOff"
        CBManagerStateResetting -> "Resetting"
        CBManagerStateUnauthorized -> "Unauthorized"
        CBManagerStateUnsupported -> "Unsupported"
        CBManagerStateUnknown -> "Unknown"
        else -> "state=$state"
    }
}
