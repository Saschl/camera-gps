package com.sasch.cameragps.sharednew.bluetooth.transport

import kotlinx.coroutines.flow.Flow

/**
 * Outcome of a single BLE I/O operation as reported by the platform stack.
 */
enum class BleOperationStatus {
    Success,
    Failure,

    /**
     * Authentication/encryption error (ATT error 5 or 15). On iOS this means the
     * device is not paired (or pairing was rejected); the orchestrator applies the
     * pairing retry policy. Effectively never fires on Android because devices are
     * bonded before connecting.
     */
    AuthError,
}

/**
 * Events emitted by a [BlePeripheralTransport].
 *
 * Identifiers are uppercase MAC addresses (Android) or peripheral UUID strings (iOS).
 * Platform callbacks may fire on any thread; events are delivered through a
 * Channel-backed flow and consumed by the single orchestrator collector.
 */
sealed interface BleTransportEvent {
    val identifier: String

    /** A peripheral connection was established (fresh connect or state restoration). */
    data class Connected(override val identifier: String) : BleTransportEvent

    /**
     * A peripheral disconnected. [statusCode] is the platform status when available
     * (Android GATT status), or `null` for synthetic disconnects (e.g. pause).
     */
    data class Disconnected(
        override val identifier: String,
        val statusCode: Int?,
    ) : BleTransportEvent

    /**
     * Service discovery finished.
     * Android: `onServicesDiscovered`. iOS: two-phase service/characteristic
     * discovery AND the pairing gate both completed.
     */
    data class ServicesDiscovered(
        override val identifier: String,
        val success: Boolean,
    ) : BleTransportEvent

    /** A characteristic read completed (read response). */
    data class CharacteristicRead(
        override val identifier: String,
        val characteristicUuid: String,
        val value: ByteArray,
        val status: BleOperationStatus,
    ) : BleTransportEvent

    /** A characteristic write completed. */
    data class CharacteristicWritten(
        override val identifier: String,
        val characteristicUuid: String,
        val status: BleOperationStatus,
    ) : BleTransportEvent

    /** A notification subscription change completed (Android: CCCD descriptor write). */
    data class SubscriptionChanged(
        override val identifier: String,
        val characteristicUuid: String,
        val enabled: Boolean,
        val status: BleOperationStatus,
    ) : BleTransportEvent

    /** Camera-initiated notification (e.g. remote status changes). */
    data class CharacteristicChanged(
        override val identifier: String,
        val characteristicUuid: String,
        val value: ByteArray,
    ) : BleTransportEvent
}

/**
 * Low-level, per-platform BLE contract. Implementations own the raw platform
 * objects (BluetoothGatt / CBPeripheral) and do nothing but initiate operations
 * and report their completion via [events].
 *
 * All orchestration (sequencing, retries, session state) lives above this
 * interface in common code — implementations must NOT chain operations
 * themselves. Exactly one operation per device is in flight at any time,
 * guaranteed by [com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationQueue].
 */
interface BlePeripheralTransport {

    /**
     * Channel-backed event stream. Single collector: the orchestrator.
     * Safe to feed from any thread.
     */
    val events: Flow<BleTransportEvent>

    /** Returns `true` if the device has an active connection. */
    fun isConnected(identifier: String): Boolean

    /** Returns `true` if a characteristic with [characteristicUuid] was discovered on the device. */
    fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean

    // ---- Operation initiation. Completion arrives via [events]. ----
    // Returning false means the operation could not even be started
    // (no connection, unknown characteristic, platform refusal).

    fun initiateWrite(identifier: String, characteristicUuid: String, value: ByteArray): Boolean

    fun initiateRead(identifier: String, characteristicUuid: String): Boolean

    fun initiateSubscribe(identifier: String, characteristicUuid: String, enable: Boolean): Boolean

    /**
     * Android: `gatt.discoverServices()`.
     * iOS: two-phase service/characteristic discovery followed by the pairing gate.
     */
    fun initiateDiscoverServices(identifier: String): Boolean
}
