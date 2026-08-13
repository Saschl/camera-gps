package com.sasch.cameragps.sharednew.bluetooth.coordinator

/**
 * Platform abstraction over BLE GATT I/O operations.
 *
 * Both Android (via BluetoothGatt) and iOS (via CBPeripheral) implement this interface
 * to let the shared coordinator drive BLE writes, reads and notification subscriptions
 * without knowing about platform-specific types.
 *
 * All identifiers are uppercase MAC addresses (Android) or UUID strings (iOS).
 */
interface BleGattPort {
    /**
     * Write [value] to the characteristic identified by [characteristicUuid] on device [identifier].
     * Returns `true` if the write was successfully enqueued.
     */
    fun writeCharacteristic(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray
    ): Boolean

    /**
     * Subscribe to notifications for [characteristicUuid] on device [identifier].
     * On Android this also writes the CCCD descriptor. Returns `true` on success.
     */
    fun subscribeToNotifications(identifier: String, characteristicUuid: String): Boolean

    /**
     * Returns `true` if the device [identifier] has an active BLE connection.
     */
    fun isConnected(identifier: String): Boolean

    /**
     * Returns `true` if the remote control characteristic is available for device [identifier].
     */
    fun hasRemoteControlCharacteristic(identifier: String): Boolean

    /**
     * Returns `true` if the remote feature is currently active on device [identifier].
     */
    fun isRemoteFeatureActive(identifier: String): Boolean

    /**
     * Update the stored remote feature active state for device [identifier].
     */
    fun setRemoteFeatureActive(identifier: String, active: Boolean)

    /**
     * Update the stored shutter-sequence-running state for device [identifier]
     * (drives the shutter button feedback in both UIs).
     */
    fun setShutterSequenceActive(identifier: String, active: Boolean)

    /**
     * Read the value of [characteristicUuid] on device [identifier].
     * The result arrives asynchronously via the platform's characteristic-read callback.
     * Returns `true` if the read was successfully initiated.
     */
    fun readCharacteristic(identifier: String, characteristicUuid: String): Boolean

    /**
     * Returns `true` if a characteristic with [characteristicUuid] exists on device [identifier].
     * Searches through discovered services and their characteristics.
     */
    fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean
}


