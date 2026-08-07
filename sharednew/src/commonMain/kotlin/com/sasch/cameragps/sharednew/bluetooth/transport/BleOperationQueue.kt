package com.sasch.cameragps.sharednew.bluetooth.transport

import com.diamondedge.logging.logging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * A single BLE operation to be executed sequentially on one device.
 */
sealed interface BleOperation {
    data class Write(val characteristicUuid: String, val value: ByteArray) : BleOperation
    data class Read(val characteristicUuid: String) : BleOperation
    data class Subscribe(val characteristicUuid: String, val enable: Boolean) : BleOperation
    data object DiscoverServices : BleOperation
}

sealed interface BleOperationResult {
    data class Success(val value: ByteArray? = null) : BleOperationResult
    data class Failure(val status: BleOperationStatus) : BleOperationResult
    data object Timeout : BleOperationResult
    data object NotInitiated : BleOperationResult
    data object Cancelled : BleOperationResult
}

/**
 * Serializes all BLE operations per device onto a single lane.
 *
 * BLE stacks allow only one outstanding GATT operation per connection; before
 * this queue existed, handshake writes, the periodic location loop, the remote
 * probe loop and shutter commands all wrote concurrently and collided
 * (surfacing as GATT status 201 "device busy" on Android).
 *
 * Each device gets a lane: an unbounded channel plus a worker coroutine that
 * initiates one operation at a time via [BlePeripheralTransport] and suspends
 * until the matching completion event arrives (or the timeout fires).
 * Completion matching happens in [onTransportEvent], which the orchestrator
 * calls for every transport event before any other routing.
 *
 * All state is confined to [scope]'s dispatcher (Main.immediate on both
 * platforms) — no locks needed.
 */
class BleOperationQueue(
    private val transport: BlePeripheralTransport,
    private val scope: CoroutineScope,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val discoveryTimeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS,
) {
    private val log = logging()

    private class QueuedOperation(
        val operation: BleOperation,
        val result: CompletableDeferred<BleOperationResult> = CompletableDeferred(),
    )

    private inner class DeviceLane(val identifier: String) {
        val channel = Channel<QueuedOperation>(Channel.UNLIMITED)
        var pending: QueuedOperation? = null
        val worker: Job = scope.launch {
            for (queued in channel) {
                if (queued.result.isCompleted) continue // cancelled while parked

                log.d { "${queued.operation.describe()} on $identifier: initiating" }
                if (!initiate(identifier, queued.operation)) {
                    log.w { "Could not initiate ${queued.operation.describe()} on $identifier" }
                    queued.result.complete(BleOperationResult.NotInitiated)
                    continue
                }

                pending = queued
                try {
                    val result = withTimeout(timeoutFor(queued.operation)) { queued.result.await() }
                    log.d { "${queued.operation.describe()} on $identifier: ${result.describe()}" }
                } catch (_: TimeoutCancellationException) {
                    log.w { "${queued.operation.describe()} on $identifier timed out" }
                    queued.result.complete(BleOperationResult.Timeout)
                } finally {
                    pending = null
                }
            }
        }
    }

    private val lanes = mutableMapOf<String, DeviceLane>()

    /**
     * Enqueue [op] and suspend until it completes, fails, times out or is cancelled.
     */
    suspend fun execute(identifier: String, op: BleOperation): BleOperationResult {
        val queued = QueuedOperation(op)
        if (!laneFor(identifier).channel.trySend(queued).isSuccess) {
            return BleOperationResult.Cancelled
        }
        return queued.result.await()
    }

    /**
     * Fire-and-forget enqueue. Returns `false` if the operation could not be queued.
     */
    fun enqueue(identifier: String, op: BleOperation): Boolean {
        return laneFor(identifier).channel.trySend(QueuedOperation(op)).isSuccess
    }

    /**
     * Completion matching. Must be called for EVERY transport event, before the
     * event is routed anywhere else.
     */
    fun onTransportEvent(event: BleTransportEvent) {
        if (event is BleTransportEvent.Disconnected) {
            cancelOperations(event.identifier, "disconnected")
            return
        }

        val pending = lanes[event.identifier.uppercase()]?.pending ?: return
        val result = matchCompletion(pending.operation, event) ?: return
        pending.result.complete(result)
    }

    /**
     * Fail the pending operation and drain all parked operations for [identifier].
     */
    fun cancelOperations(identifier: String, reason: String) {
        val lane = lanes.remove(identifier.uppercase()) ?: return
        log.d { "Cancelling BLE operations for $identifier: $reason" }
        lane.pending?.result?.complete(BleOperationResult.Cancelled)
        lane.channel.close()
        while (true) {
            val parked = lane.channel.tryReceive().getOrNull() ?: break
            parked.result.complete(BleOperationResult.Cancelled)
        }
        lane.worker.cancel()
    }

    fun shutdown(reason: String) {
        lanes.keys.toList().forEach { cancelOperations(it, reason) }
    }

    // ---- internals ----

    private fun laneFor(identifier: String): DeviceLane {
        val id = identifier.uppercase()
        return lanes.getOrPut(id) { DeviceLane(id) }
    }

    private fun initiate(identifier: String, op: BleOperation): Boolean = when (op) {
        is BleOperation.Write ->
            transport.initiateWrite(identifier, op.characteristicUuid, op.value)

        is BleOperation.Read ->
            transport.initiateRead(identifier, op.characteristicUuid)

        is BleOperation.Subscribe ->
            transport.initiateSubscribe(identifier, op.characteristicUuid, op.enable)

        is BleOperation.DiscoverServices ->
            transport.initiateDiscoverServices(identifier)
    }

    private fun timeoutFor(op: BleOperation): Long = when (op) {
        is BleOperation.DiscoverServices -> discoveryTimeoutMs
        else -> operationTimeoutMs
    }

    private fun matchCompletion(
        pending: BleOperation,
        event: BleTransportEvent,
    ): BleOperationResult? = when {
        pending is BleOperation.Write && event is BleTransportEvent.CharacteristicWritten &&
                pending.characteristicUuid.equals(event.characteristicUuid, ignoreCase = true) ->
            event.status.toResult()

        pending is BleOperation.Read && event is BleTransportEvent.CharacteristicRead &&
                pending.characteristicUuid.equals(event.characteristicUuid, ignoreCase = true) ->
            if (event.status == BleOperationStatus.Success) {
                BleOperationResult.Success(event.value)
            } else {
                BleOperationResult.Failure(event.status)
            }

        pending is BleOperation.Subscribe && event is BleTransportEvent.SubscriptionChanged &&
                pending.characteristicUuid.equals(event.characteristicUuid, ignoreCase = true) ->
            event.status.toResult()

        pending is BleOperation.DiscoverServices && event is BleTransportEvent.ServicesDiscovered ->
            if (event.success) {
                BleOperationResult.Success()
            } else {
                BleOperationResult.Failure(BleOperationStatus.Failure)
            }

        else -> null
    }

    private fun BleOperationStatus.toResult(): BleOperationResult = when (this) {
        BleOperationStatus.Success -> BleOperationResult.Success()
        else -> BleOperationResult.Failure(this)
    }

    private fun BleOperation.describe(): String = when (this) {
        is BleOperation.Write -> "Write($characteristicUuid)"
        is BleOperation.Read -> "Read($characteristicUuid)"
        is BleOperation.Subscribe -> "Subscribe($characteristicUuid, enable=$enable)"
        is BleOperation.DiscoverServices -> "DiscoverServices"
    }

    private fun BleOperationResult.describe(): String = when (this) {
        is BleOperationResult.Success -> "Success"
        is BleOperationResult.Failure -> "Failure($status)"
        BleOperationResult.Timeout -> "Timeout"
        BleOperationResult.NotInitiated -> "NotInitiated"
        BleOperationResult.Cancelled -> "Cancelled"
    }

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 15_000L

        /** Discovery includes the iOS pairing gate, which may retry 3× with 3s delays. */
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 30_000L
    }
}
