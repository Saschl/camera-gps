package com.sasch.cameragps.sharednew.bluetooth.coordinator

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared remote control coordinator. Owns:
 * - Remote feature status detection (pure byte-array parsing)
 * - Periodic probe loop (probe while feature inactive, stop when active)
 * - Shutter command orchestration
 *
 * Platform-specific BLE I/O is delegated to [BleGattPort].
 * Timing uses coroutines so it works identically on Android and iOS.
 */
class RemoteControlCoordinator(
    private val port: BleGattPort,
    private val scope: CoroutineScope,
) {
    private val log = logging()

    private val activeProbeJobs = mutableMapOf<String, Job>()
    private val monitoredDevices = mutableSetOf<String>()

    private val activeSequenceJobs = mutableMapOf<String, Job>()

    /** Devices with a running command sequence — suppresses the automatic shutter-up. */
    private val sequenceOwners = mutableSetOf<String>()

    private val _events = Channel<BleSessionEvent>(Channel.UNLIMITED)

    /** Collect this flow to receive remote-feature state change events. */
    val events: Flow<BleSessionEvent> = _events.receiveAsFlow()

    /** Every remote-status notification (identifier to raw value), for sequence waits. */
    private val statusUpdates = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 16)

    // ---- Public API called by platform GATT callbacks / service ----

    /**
     * Begin remote status probing for [identifier]. Subscribes to remote status
     * notifications and starts the probe loop (which polls while the feature is inactive).
     *
     * Call this after the BLE handshake is complete (GPS enabled, time synced, transmitting).
     */
    fun startRemoteStatusMonitoring(identifier: String) {
        val normalized = identifier.uppercase()
        monitoredDevices.add(normalized)

        // Subscribe to remote status characteristic notifications
        port.subscribeToNotifications(normalized, SonyBluetoothConstants.REMOTE_STATUS_UUID)

        if (!port.hasRemoteControlCharacteristic(normalized)) return

        startProbeLoop(normalized)
    }

    /**
     * Called when the remote status characteristic value changes (notification from camera).
     * Returns `true` if the platform should send a shutter-up command (specific response value).
     */
    fun onRemoteStatusChanged(identifier: String, value: ByteArray): Boolean {
        val normalized = identifier.uppercase()
        statusUpdates.tryEmit(normalized to value)

        val wasActive = port.isRemoteFeatureActive(normalized)
        val active = isRemoteFeatureActive(value)
        port.setRemoteFeatureActive(normalized, active)

        if (active) {
            stopProbeLoop(normalized)
            if (!wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureActivated(normalized))
            }
        } else {
            if (wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureDeactivated(normalized))
            }
            if (normalized in monitoredDevices) {
                startProbeLoop(normalized)
            } else {
                stopProbeLoop(normalized)
            }
        }

        // A running sequence owns the button state and sends its own releases
        if (normalized in sequenceOwners) return false

        return shouldSendShutterUp(value)
    }

    /**
     * Called when a write to the remote control characteristic completes.
     * Emits activation/deactivation events based on success.
     */
    fun onRemoteControlWriteResponse(identifier: String, success: Boolean) {
        val normalized = identifier.uppercase()
        val wasActive = port.isRemoteFeatureActive(normalized)
        if (success) {
            port.setRemoteFeatureActive(normalized, true)
            stopProbeLoop(normalized)
            if (!wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureActivated(normalized))
            }
        } else {
            port.setRemoteFeatureActive(normalized, false)
            if (wasActive) {
                _events.trySend(BleSessionEvent.RemoteFeatureDeactivated(normalized))
            }
        }
    }

    /**
     * Send a [RemoteCommand] to [identifier]. Returns `true` if the command was
     * enqueued. Fails when the device is not connected or the remote feature is
     * not active on the camera.
     */
    fun sendCommand(identifier: String, command: RemoteCommand): Boolean {
        val normalized = identifier.uppercase()

        if (!port.isConnected(normalized)) return false
        if (!port.isRemoteFeatureActive(normalized)) return false

        return port.writeCharacteristic(
            normalized,
            SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
            command.payload,
        )
    }

    /**
     * Trigger a remote shutter press for [identifier].
     * Returns `true` if the shutter-down command was sent.
     * Shutter-up is sent later when the camera reports success via characteristic change callback.
     */
    fun handleRemoteShutterRequest(identifier: String): Boolean {
        return sendCommand(identifier, RemoteCommand.ShutterFullPress)
    }

    /**
     * Run one full shutter cycle for [identifier], driven by the camera's status
     * notifications: half-press → wait for focus acquired → full-press → wait for
     * the exposure to start → full-release → half-release → wait for camera ready.
     * Timeouts are only fallbacks so a camera that skips a status (e.g. manual
     * focus) cannot stall the cycle. While a sequence owns a device the automatic
     * ack-triggered shutter-up is suppressed — the sequence sends its own releases.
     *
     * Returns `true` if the sequence was started; `false` when the device is not
     * connected, the remote feature is inactive, or a sequence is already running.
     */
    fun startShutterSequence(identifier: String): Boolean {
        val normalized = identifier.uppercase()
        if (activeSequenceJobs.containsKey(normalized)) return false
        if (!port.isConnected(normalized)) return false
        if (!port.isRemoteFeatureActive(normalized)) return false

        // Sequences rely on remote-status notifications. Subscribing is idempotent,
        // and being queued it naturally completes before the sequence's writes.
        port.subscribeToNotifications(normalized, SonyBluetoothConstants.REMOTE_STATUS_UUID)

        sequenceOwners.add(normalized)
        val job = scope.launch(start = CoroutineStart.LAZY) { runShutterCycle(normalized) }
        job.invokeOnCompletion {
            sequenceOwners.remove(normalized)
            if (activeSequenceJobs[normalized] === job) {
                activeSequenceJobs.remove(normalized)
            }
        }
        activeSequenceJobs[normalized] = job
        job.start()
        return true
    }

    private enum class StepResult { NotSent, TimedOut, Confirmed }

    private suspend fun runShutterCycle(identifier: String) = coroutineScope {
        // Half press — wait for the camera to confirm focus before the full press
        when (
            sendAndAwaitStatus(
                identifier,
                RemoteCommand.ShutterHalfPress,
                FOCUS_CONFIRM_TIMEOUT_MS
            ) {
                isFocusAcquired(it)
            }
        ) {
            StepResult.NotSent -> return@coroutineScope
            StepResult.TimedOut ->
                log.w { "Camera $identifier did not confirm focus, proceeding with full press" }

            StepResult.Confirmed -> Unit
        }

        var readyWaiter: Deferred<ByteArray?>? = null
        try {
            // Full press — wait until the camera reports the exposure started
            val fullPress =
                sendAndAwaitStatus(
                    identifier,
                    RemoteCommand.ShutterFullPress,
                    SHUTTER_ACTIVE_TIMEOUT_MS
                ) {
                    isShutterActive(it)
                }
            if (fullPress == StepResult.TimedOut) {
                log.w { "Camera $identifier did not report the shutter active" }
            }
            if (fullPress != StepResult.NotSent) {
                // Subscribe to the ready status before releasing, so a fast exposure
                // finishing right after the release is not missed
                readyWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                    awaitStatus(identifier, SHUTTER_READY_TIMEOUT_MS) { isCameraReady(it) }
                }
                sendCommand(identifier, RemoteCommand.ShutterFullRelease)
            }
        } finally {
            // Always release the half press, also on cancellation (disconnect)
            sendCommand(identifier, RemoteCommand.ShutterHalfRelease)
        }

        // Wait until the exposure finished and the camera is ready again
        if (readyWaiter != null && readyWaiter.await() == null) {
            log.w { "Camera $identifier did not report ready after the shutter cycle" }
        }
    }

    /**
     * Start waiting for a matching status notification, THEN send [command], so a
     * response arriving immediately after the write cannot be missed.
     */
    private suspend fun sendAndAwaitStatus(
        identifier: String,
        command: RemoteCommand,
        timeoutMs: Long,
        predicate: (ByteArray) -> Boolean,
    ): StepResult = coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            awaitStatus(identifier, timeoutMs, predicate)
        }
        if (!sendCommand(identifier, command)) {
            waiter.cancel()
            return@coroutineScope StepResult.NotSent
        }
        if (waiter.await() != null) StepResult.Confirmed else StepResult.TimedOut
    }

    /**
     * Suspend until a remote-status notification from [identifier] matches
     * [predicate], or return `null` after [timeoutMs].
     */
    private suspend fun awaitStatus(
        identifier: String,
        timeoutMs: Long,
        predicate: (ByteArray) -> Boolean,
    ): ByteArray? = withTimeoutOrNull(timeoutMs) {
        statusUpdates
            .filter { it.first == identifier }
            .map { it.second }
            .first(predicate)
    }

    /**
     * Send the shutter-up command for [identifier].
     */
    fun sendShutterUp(identifier: String): Boolean {
        val normalized = identifier.uppercase()
        return port.writeCharacteristic(
            normalized,
            SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
            SonyBluetoothConstants.FULL_SHUTTER_UP_COMMAND,
        )
    }

    /**
     * Trigger a remote shutter action (down command only) for [identifier].
     *
     * The shared BLE callback flow sends shutter-up only after camera acknowledgement.
     */
    fun triggerRemoteShutter(identifier: String): Boolean {
        return handleRemoteShutterRequest(identifier)
    }

    /**
     * Cancel the probe loop and any running command sequence for a single
     * device (e.g. on disconnect).
     */
    fun cancelProbe(identifier: String) {
        val normalized = identifier.uppercase()
        monitoredDevices.remove(normalized)
        activeProbeJobs.remove(normalized)?.cancel()
        activeSequenceJobs.remove(normalized)?.cancel()
        sequenceOwners.remove(normalized)
    }

    /**
     * Cancel all active probe loops and command sequences (e.g. on service destroy).
     */
    fun cancelAllProbes() {
        monitoredDevices.clear()
        activeProbeJobs.values.forEach { it.cancel() }
        activeProbeJobs.clear()
        activeSequenceJobs.values.forEach { it.cancel() }
        activeSequenceJobs.clear()
        sequenceOwners.clear()
    }

    // ---- Internal probe loop ----

    private fun startProbeLoop(identifier: String) {
        if (activeProbeJobs.containsKey(identifier)) return
        if (!port.hasRemoteControlCharacteristic(identifier)) return

        val job = scope.launch {
            delay(REMOTE_STATUS_PROBE_INITIAL_DELAY_MS)
            while (isActive) {
                if (!port.isConnected(identifier)) {
                    break
                }
                if (!port.hasRemoteControlCharacteristic(identifier)) {
                    break
                }

                port.writeCharacteristic(
                    identifier,
                    SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
                    SonyBluetoothConstants.PROBE_COMMAND,
                )

                delay(REMOTE_STATUS_PROBE_INTERVAL_MS)
            }
            // Clean up our own entry when the loop exits naturally
            val currentJob = currentCoroutineContext()[Job]
            if (activeProbeJobs[identifier] === currentJob) {
                activeProbeJobs.remove(identifier)
            }
        }
        activeProbeJobs[identifier] = job
    }

    private fun stopProbeLoop(identifier: String) {
        activeProbeJobs.remove(identifier)?.cancel()
    }

    companion object {
        const val REMOTE_STATUS_PROBE_INTERVAL_MS = 3_000L
        const val REMOTE_STATUS_PROBE_INITIAL_DELAY_MS = 500L

        // Fallback timeouts for the status-driven shutter cycle — the cycle normally
        // advances on camera status notifications, never on these expiring.
        const val FOCUS_CONFIRM_TIMEOUT_MS = 2_000L
        const val SHUTTER_ACTIVE_TIMEOUT_MS = 2_000L
        const val SHUTTER_READY_TIMEOUT_MS = 20_000L

        /**
         * Determine if the remote feature is active based on the characteristic value.
         * Shared between Android and iOS — the byte protocol is identical.
         */
        fun isRemoteFeatureActive(value: ByteArray): Boolean {
            if (value.isEmpty()) return false
            return !value.contentEquals(byteArrayOf(0x02, 0xC3.toByte(), 0x00))
        }

        /**
         * Check if this characteristic change value indicates the platform
         * should send a shutter-up command.
         */
        fun shouldSendShutterUp(value: ByteArray): Boolean {
            return value.contentEquals(SonyBluetoothConstants.STATUS_READY)
        }

        /**
         * `true` when the camera reports the ready status (exposure finished).
         */
        fun isCameraReady(value: ByteArray): Boolean =
            value.startsWith(SonyBluetoothConstants.STATUS_READY)

        /** `true` when the camera reports the shutter active (exposure running). */
        fun isShutterActive(value: ByteArray): Boolean =
            value.startsWith(SonyBluetoothConstants.STATUS_SHUTTER_ACTIVE)

        /** `true` when the camera reports focus acquired after a half press. */
        fun isFocusAcquired(value: ByteArray): Boolean =
            value.startsWith(SonyBluetoothConstants.STATUS_FOCUS_ACQUIRED)

        /** Prefix match, so longer status payloads still qualify. */
        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (size < prefix.size) return false
            return prefix.indices.all { this[it] == prefix[it] }
        }

        /**
         * Parse the config-read characteristic value for the timezone/DST flag.
         * Shared between Android and iOS.
         */
        fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
            return value.size >= 5 && (value[4].toInt() and 0x02) != 0
        }
    }
}

