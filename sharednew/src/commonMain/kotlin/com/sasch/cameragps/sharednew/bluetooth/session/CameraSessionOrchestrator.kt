package com.sasch.cameragps.sharednew.bluetooth.session

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionCoordinator
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionEvent
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteCommand
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteControlCoordinator
import com.sasch.cameragps.sharednew.bluetooth.location.LocationEvent
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.bluetooth.location.LocationTransmissionManager
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationQueue
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The common session orchestrator. Owns the per-device session registry, the
 * sequential operation queue and the location transmission manager; routes
 * transport events into the existing shared [BleSessionCoordinator] and
 * [RemoteControlCoordinator].
 *
 * This unifies what `LocationSenderService` (+ its adapter coordinators) did
 * on Android and what `IosBluetoothController`'s delegates/init-block did on
 * iOS. Platform shells only own sockets and lifecycle:
 * they feed connects/disconnects in via the transport and react to [events].
 *
 * Must be driven from a single-threaded [scope]
 * (`Dispatchers.Main.immediate` on both platforms).
 */
class CameraSessionOrchestrator(
    private val transport: BlePeripheralTransport,
    locationSource: LocationSource,
    private val deviceDao: CameraDeviceDAO,
    private val scope: CoroutineScope,
    private val pairingPolicy: PairingRetryPolicy = PairingRetryPolicy(),
    /**
     * Consulted when a handshake completes. Return `false` to abort the session
     * (iOS passes its device-enabled check here and disconnects inside the
     * lambda when disabled).
     */
    private val shouldRemainConnected: suspend (String) -> Boolean = { true },
    /** iOS: app-level transmission toggle for the location manager. */
    isTransmissionAllowed: () -> Boolean = { true },
) {
    private val log = logging()

    val registry = CameraSessionRegistry()

    private val queue = BleOperationQueue(transport, scope)
    private val port: BleGattPort = QueuedBleGattPort(queue, transport, registry)
    private val remoteControl = RemoteControlCoordinator(port, scope)
    private val sessionCoordinator = BleSessionCoordinator(port, remoteControl)

    val locationManager = LocationTransmissionManager(
        source = locationSource,
        readySessions = registry::readyIdentifiers,
        configFor = sessionCoordinator::getLocationDataConfig,
        port = port,
        scope = scope,
        isTransmissionAllowed = isTransmissionAllowed,
    )

    private val _events = Channel<OrchestratorEvent>(Channel.UNLIMITED)
    val events: Flow<OrchestratorEvent> = _events.receiveAsFlow()

    val sessions: StateFlow<Map<String, CameraSession>> get() = registry.sessions

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            transport.events.collect { event ->
                // Completion matching must run before any other routing so the
                // queue can release its lane for the next operation.
                queue.onTransportEvent(event)
                handleTransportEvent(event)
            }
        }
        scope.launch {
            sessionCoordinator.events.collect { handleSessionEvent(it) }
        }
        scope.launch {
            remoteControl.events.collect { handleSessionEvent(it) }
        }
        scope.launch {
            locationManager.events.collect { event ->
                when (event) {
                    LocationEvent.FirstFixAcquired ->
                        _events.trySend(OrchestratorEvent.FirstLocationAcquired)

                    LocationEvent.NoLocationAvailable ->
                        _events.trySend(OrchestratorEvent.LocationUnavailable)
                }
            }
        }
    }

    // ---- Public API for platform shells ----

    /** A connection attempt was started by the shell. */
    fun onConnectRequested(identifier: String) {
        registry.upsert(identifier) { it.copy(phase = BleSessionPhase.Connecting) }
    }

    /** A connection attempt failed before any transport event (e.g. Bluetooth off). */
    fun onConnectFailed(identifier: String) {
        registry.updateIfPresent(identifier) { it.copy(phase = BleSessionPhase.Error) }
    }

    fun triggerRemoteShutter(identifier: String): Boolean =
        sendRemoteCommand(identifier, RemoteCommand.ShutterFullPress)

    /**
     * Send a remote-control command. The coordinator gates on an active
     * connection and remote feature; the write is serialized via the queue.
     */
    fun sendRemoteCommand(identifier: String, command: RemoteCommand): Boolean =
        remoteControl.sendCommand(identifier, command)

    /**
     * Run one full shutter sequence (half press → focus delay → full press →
     * releases → wait for camera ready), as opposed to [triggerRemoteShutter]'s
     * single press with ack-driven release.
     */
    fun triggerShutterSequence(identifier: String): Boolean =
        remoteControl.startShutterSequence(identifier)

    fun setRemoteMonitoring(identifier: String, enabled: Boolean) {
        val id = identifier.uppercase()
        if (enabled) {
            remoteControl.startRemoteStatusMonitoring(id)
        } else {
            remoteControl.cancelProbe(id)
        }
    }

    /** Drop all session state for a device (disconnect/forget). */
    fun clearDevice(identifier: String) {
        val id = identifier.uppercase()
        queue.cancelOperations(id, "session cleared")
        sessionCoordinator.clearSession(id)
        registry.remove(id)
        locationManager.updateTracking()
    }

    /** Tear everything down (service destroy / force shutdown). */
    fun shutdownAll() {
        sessionCoordinator.clearAllSessions()
        queue.shutdown("shutdown")
        locationManager.shutdown()
        registry.clear()
    }

    fun connectedDeviceCount(): Int = registry.activeCount()

    // ---- Transport event routing ----

    private fun handleTransportEvent(event: BleTransportEvent) {
        when (event) {
            is BleTransportEvent.Connected -> handleConnected(event.identifier)

            is BleTransportEvent.Disconnected -> {
                log.i { "Device ${event.identifier} disconnected (status=${event.statusCode})" }
                clearDevice(event.identifier)
                _events.trySend(OrchestratorEvent.DeviceDisconnected(event.identifier.uppercase()))
            }

            is BleTransportEvent.CharacteristicWritten -> handleWritten(event)

            is BleTransportEvent.CharacteristicRead -> handleRead(event)

            is BleTransportEvent.SubscriptionChanged -> handleSubscriptionChanged(event)

            is BleTransportEvent.CharacteristicChanged ->
                sessionCoordinator.onCharacteristicChanged(
                    event.identifier,
                    event.characteristicUuid,
                    event.value,
                )

            is BleTransportEvent.ServicesDiscovered -> Unit // consumed by the queue
        }
    }

    private fun handleConnected(identifier: String) {
        val id = identifier.uppercase()
        log.i { "Device $id connected" }
        registry.upsert(id) {
            it.copy(
                phase = BleSessionPhase.Connected,
                pairingRetryCount = 0,
                hasRetriedConfigRead = false,
            )
        }
        _events.trySend(OrchestratorEvent.DeviceConnected(id))

        scope.launch {
            val delayMs = runCatching { deviceDao.getHandshakeDelayMs(id) }.getOrNull() ?: 0L
            if (delayMs > 0) {
                // Some cameras stall their own boot while servicing BLE traffic;
                // the per-device delay lets them finish starting first
                log.i { "Delaying connection setup for $id by ${delayMs}ms" }
                delay(delayMs.milliseconds)
                if (registry.get(id) == null || !transport.isConnected(id)) return@launch
            }
            runDiscoveryAndHandshake(id)
        }
    }

    private suspend fun runDiscoveryAndHandshake(id: String) {
        registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.DiscoveringServices) }
        when (queue.execute(id, BleOperation.DiscoverServices)) {
            is BleOperationResult.Success -> sessionCoordinator.beginHandshake(id)
            is BleOperationResult.Cancelled -> Unit // disconnected meanwhile
            else -> {
                log.e { "Service discovery failed for $id" }
                registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Error) }
            }
        }
    }

    private fun handleWritten(event: BleTransportEvent.CharacteristicWritten) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                // Restart the handshake after pairing settles (previous iOS behavior)
                sessionCoordinator.beginHandshake(id)
            }

            else -> {
                resetPairingRetries(id)
                sessionCoordinator.onCharacteristicWrite(
                    id,
                    event.characteristicUuid,
                    event.status == BleOperationStatus.Success,
                )
            }
        }
    }

    private fun handleRead(event: BleTransportEvent.CharacteristicRead) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                port.readCharacteristic(id, event.characteristicUuid)
            }

            BleOperationStatus.Failure -> {
                val isConfigRead = event.characteristicUuid.equals(
                    SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
                    ignoreCase = true,
                )
                val session = registry.get(id)
                if (isConfigRead && session != null && !session.hasRetriedConfigRead) {
                    // One-shot retry for the intermittent GATT 133 read failure
                    log.w { "Config read failed for $id, retrying once" }
                    registry.updateIfPresent(id) { it.copy(hasRetriedConfigRead = true) }
                    port.readCharacteristic(id, event.characteristicUuid)
                } else {
                    sessionCoordinator.onCharacteristicRead(id, event.value, false)
                }
            }

            BleOperationStatus.Success -> {
                resetPairingRetries(id)
                sessionCoordinator.onCharacteristicRead(id, event.value, true)
            }
        }
    }

    private fun handleSubscriptionChanged(event: BleTransportEvent.SubscriptionChanged) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                port.subscribeToNotifications(id, event.characteristicUuid)
            }

            BleOperationStatus.Failure ->
                log.w { "Subscription change failed for $id (${event.characteristicUuid})" }

            BleOperationStatus.Success -> resetPairingRetries(id)
        }
    }

    private fun retryAfterAuthError(identifier: String, retry: () -> Unit) {
        val session = registry.get(identifier) ?: return
        val attempts = session.pairingRetryCount + 1
        if (attempts > pairingPolicy.maxRetries) {
            log.e { "Pairing retries exhausted for $identifier" }
            _events.trySend(OrchestratorEvent.PairingFailed(identifier))
            return
        }
        log.w { "Auth error for $identifier, retry $attempts/${pairingPolicy.maxRetries}" }
        registry.updateIfPresent(identifier) { it.copy(pairingRetryCount = attempts) }
        scope.launch {
            val delayMs = if (attempts == 1) {
                pairingPolicy.firstRetryDelayMs
            } else {
                pairingPolicy.retryDelayMs
            }
            if (delayMs > 0) {
                delay(delayMs.milliseconds)
            }
            if (transport.isConnected(identifier)) {
                retry()
            }
        }
    }

    private fun resetPairingRetries(identifier: String) {
        val session = registry.get(identifier) ?: return
        if (session.pairingRetryCount != 0) {
            registry.updateIfPresent(identifier) { it.copy(pairingRetryCount = 0) }
        }
    }

    // ---- Coordinator event routing ----

    private suspend fun handleSessionEvent(event: BleSessionEvent) {
        when (event) {
            is BleSessionEvent.PhaseChanged -> {
                registry.updateIfPresent(event.identifier) { session ->
                    session.copy(
                        phase = event.phase,
                        remoteFeatureActive = event.remoteActive
                            ?: session.remoteFeatureActive,
                    )
                }
            }

            is BleSessionEvent.HandshakeComplete -> handleHandshakeComplete(event.identifier)

            is BleSessionEvent.RemoteFeatureActivated ->
                registry.updateIfPresent(event.identifier) { it.copy(remoteFeatureActive = true) }

            is BleSessionEvent.RemoteFeatureDeactivated ->
                registry.updateIfPresent(event.identifier) { it.copy(remoteFeatureActive = false) }
        }
    }

    private suspend fun handleHandshakeComplete(identifier: String) {
        val id = identifier.uppercase()
        if (!shouldRemainConnected(id)) {
            log.i { "Device $id should not remain connected, skipping session start" }
            return
        }
        log.i { "Handshake complete for $id" }
        registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Transmitting) }
        locationManager.onDeviceReady(id)

        val remoteEnabled = runCatching { deviceDao.isRemoteControlEnabled(id) }
            .getOrDefault(false)
        if (remoteEnabled) {
            remoteControl.startRemoteStatusMonitoring(id)
        }
        _events.trySend(OrchestratorEvent.HandshakeCompleted(id))
    }
}
