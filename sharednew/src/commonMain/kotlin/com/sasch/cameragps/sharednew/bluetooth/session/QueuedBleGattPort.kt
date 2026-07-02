package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationQueue
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport

/**
 * The single [BleGattPort] implementation, backed by the sequential
 * [BleOperationQueue]. Every write the shared coordinators make (handshake,
 * remote probe, shutter) and every location packet flows through one
 * per-device lane — no more concurrent GATT writers.
 */
internal class QueuedBleGattPort(
    private val queue: BleOperationQueue,
    private val transport: BlePeripheralTransport,
    private val registry: CameraSessionRegistry,
) : BleGattPort {

    override fun writeCharacteristic(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean =
        queue.enqueue(identifier.uppercase(), BleOperation.Write(characteristicUuid, value))

    override fun readCharacteristic(identifier: String, characteristicUuid: String): Boolean =
        queue.enqueue(identifier.uppercase(), BleOperation.Read(characteristicUuid))

    override fun subscribeToNotifications(identifier: String, characteristicUuid: String): Boolean =
        queue.enqueue(
            identifier.uppercase(),
            BleOperation.Subscribe(characteristicUuid, enable = true)
        )

    override fun isConnected(identifier: String): Boolean =
        transport.isConnected(identifier.uppercase())

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean =
        transport.hasCharacteristic(identifier.uppercase(), characteristicUuid)

    override fun hasRemoteControlCharacteristic(identifier: String): Boolean =
        transport.hasCharacteristic(
            identifier.uppercase(),
            SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
        )

    override fun isRemoteFeatureActive(identifier: String): Boolean =
        registry.get(identifier)?.remoteFeatureActive == true

    override fun setRemoteFeatureActive(identifier: String, active: Boolean) {
        registry.updateIfPresent(identifier) { it.copy(remoteFeatureActive = active) }
    }
}
