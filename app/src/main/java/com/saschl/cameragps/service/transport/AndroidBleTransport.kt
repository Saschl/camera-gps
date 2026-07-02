package com.saschl.cameragps.service.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import java.util.Collections
import java.util.UUID

/**
 * Android implementation of [BlePeripheralTransport] over BluetoothGatt.
 *
 * Owns the GATT connection registry (bond check + `autoConnect = true`,
 * absorbed from the former `CameraConnectionManager`) and the GATT callback;
 * all sequencing/orchestration lives in the shared layer. GATT callbacks
 * arrive on binder threads and only feed the event channel — they never touch
 * shared state directly.
 *
 * A disconnect keeps the GATT handle open so `autoConnect` can resume the
 * connection when the camera reappears; [disconnectAll] is the only close path.
 */
class AndroidBleTransport(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
) : BlePeripheralTransport {

    private val eventChannel = Channel<BleTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<BleTransportEvent> = eventChannel.receiveAsFlow()

    private class Connection(val gatt: BluetoothGatt) {
        @Volatile
        var isActive = false
    }

    /** Uppercased MAC → connection. Entries survive disconnects (autoConnect). */
    private val connections = Collections.synchronizedMap(mutableMapOf<String, Connection>())

    /** Last requested enable state per "MAC:UUID", to label SubscriptionChanged events. */
    private val pendingSubscribeEnable =
        Collections.synchronizedMap(mutableMapOf<String, Boolean>())

    // ---------------------------------------------------------------------------
    // Connection management (called by the service / shutdown coordinator)
    // ---------------------------------------------------------------------------

    fun connect(mac: String): Boolean {
        val address = mac.uppercase()
        if (connections.containsKey(address)) {
            return true
        }

        try {
            val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                Timber.w("Device $address is not paired. Cannot connect.")
                return false
            }
            val gatt = device.connectGatt(context, true, gattCallback)
                ?: throw IllegalStateException("Failed to connect to device $address: GATT is null")
            connections[address] = Connection(gatt)
        } catch (e: SecurityException) {
            Timber.e("SecurityException while connecting to device $address: ${e.message}")
            return false
        }

        return true
    }

    /** `true` if a GATT handle exists for this device (connected or waiting for autoConnect). */
    fun hasConnection(mac: String): Boolean = connections.containsKey(mac.uppercase())

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectAll() {
        val snapshot: List<Connection> = synchronized(connections) {
            connections.values.toList().also { connections.clear() }
        }
        snapshot.forEach { connection ->
            runCatching {
                try {
                    connection.gatt.disconnect()
                } finally {
                    connection.gatt.close()
                }
            }.onFailure { Timber.w(it) }
        }
    }

    /**
     * Mark a device inactive without closing the GATT (autoConnect keeps
     * listening for the camera). Emits a synthetic Disconnected event so the
     * orchestrator clears the session.
     */
    fun pauseDevice(mac: String) {
        val address = mac.uppercase()
        val connection = connections[address] ?: return
        if (connection.isActive) {
            connection.isActive = false
            eventChannel.trySend(BleTransportEvent.Disconnected(address, statusCode = null))
        }
    }

    /** Number of devices with a live (resumed) connection. */
    fun connectedCount(): Int = synchronized(connections) {
        connections.values.count { it.isActive }
    }

    // ---------------------------------------------------------------------------
    // BlePeripheralTransport
    // ---------------------------------------------------------------------------

    override fun isConnected(identifier: String): Boolean =
        connections[identifier.uppercase()]?.isActive == true

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        return findCharacteristic(connection.gatt, characteristicUuid) != null
    }

    override fun initiateWrite(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        return writeCharacteristicCompat(connection.gatt, characteristic, value)
    }

    @SuppressLint("MissingPermission")
    override fun initiateRead(identifier: String, characteristicUuid: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        return connection.gatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    override fun initiateSubscribe(
        identifier: String,
        characteristicUuid: String,
        enable: Boolean,
    ): Boolean {
        val address = identifier.uppercase()
        val connection = connections[address] ?: return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        val descriptor =
            characteristic.getDescriptor(UUID.fromString(SonyBluetoothConstants.CCCD_UUID))
                ?: return false

        if (!connection.gatt.setCharacteristicNotification(characteristic, enable)) return false

        pendingSubscribeEnable["$address:${characteristicUuid.lowercase()}"] = enable
        val descriptorValue = if (enable) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        return writeDescriptorCompat(connection.gatt, descriptor, descriptorValue)
    }

    @SuppressLint("MissingPermission")
    override fun initiateDiscoverServices(identifier: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        return connection.gatt.discoverServices()
    }

    // ---------------------------------------------------------------------------
    // GATT callback — feeds the event channel only, from binder threads
    // ---------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address.uppercase()

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Connected to device with status %d", status)
                connections[address]?.isActive = true
                eventChannel.trySend(BleTransportEvent.Connected(address))
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                if (status == 19 || status == 8 || status == 0) {
                    Timber.i("Device disconnected in callback due to device turned off or out of range: $status")
                } else {
                    Timber.e("An error happened: $status")
                }
                connections[address]?.isActive = false
                eventChannel.trySend(BleTransportEvent.Disconnected(address, status))
                return
            }

            Timber.d("Ignoring connection callback with status=$status and state=$newState")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Timber.i("Services discovered for ${gatt.device.address} with status $status")
            eventChannel.trySend(
                BleTransportEvent.ServicesDiscovered(
                    gatt.device.address.uppercase(),
                    status == BluetoothGatt.GATT_SUCCESS,
                )
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            val uuid = characteristic?.uuid?.toString() ?: return
            eventChannel.trySend(
                BleTransportEvent.CharacteristicWritten(
                    gatt.device.address.uppercase(),
                    uuid,
                    statusOf(status),
                )
            )
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                emitCharacteristicRead(
                    gatt,
                    characteristic,
                    characteristic.value ?: ByteArray(0),
                    status
                )
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            emitCharacteristicRead(gatt, characteristic, value, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Timber.i("Characteristic changed: ${characteristic.uuid}, value=${value.joinToString(",")}")
            eventChannel.trySend(
                BleTransportEvent.CharacteristicChanged(
                    gatt.device.address.uppercase(),
                    characteristic.uuid.toString(),
                    value,
                )
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val address = gatt.device.address.uppercase()
            val characteristicUuid = descriptor.characteristic.uuid.toString()
            Timber.d("Descriptor write completed for ${gatt.device.address} with status $status")
            val enabled =
                pendingSubscribeEnable.remove("$address:${characteristicUuid.lowercase()}")
                    ?: true
            eventChannel.trySend(
                BleTransportEvent.SubscriptionChanged(
                    address,
                    characteristicUuid,
                    enabled,
                    statusOf(status),
                )
            )
        }

        private fun emitCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            eventChannel.trySend(
                BleTransportEvent.CharacteristicRead(
                    gatt.device.address.uppercase(),
                    characteristic.uuid.toString(),
                    value,
                    statusOf(status),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers (write compat absorbed from the former BluetoothGattUtils)
    // ---------------------------------------------------------------------------

    private fun statusOf(status: Int): BleOperationStatus = when (status) {
        BluetoothGatt.GATT_SUCCESS -> BleOperationStatus.Success

        SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_AUTHENTICATION,
        SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_ENCRYPTION,
            -> BleOperationStatus.AuthError

        else -> BleOperationStatus.Failure
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        uuid: String,
    ): BluetoothGattCharacteristic? {
        val target = UUID.fromString(uuid)
        return gatt.services?.flatMap { it.characteristics }?.find { it.uuid == target }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(characteristic, value, writeType)
            // 201 == device busy, spams sentry, but I do not know the cause yet
            if (result != 0 && result != 201) {
                Timber.e("Writing characteristic failed. Result: $result")
                false
            } else {
                Timber.d("Characteristic written successfully (API 33+)")
                true
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            val result = gatt.writeCharacteristic(characteristic)
            if (!result) {
                Timber.e("Writing characteristic failed (legacy API)")
            }
            result
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, value)
            if (result != 0 && result != 201) {
                Timber.e("Writing descriptor failed. Result: $result")
                false
            } else {
                Timber.d("Descriptor written successfully (API 33+)")
                true
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            val result = gatt.writeDescriptor(descriptor)
            if (!result) {
                Timber.e("Writing descriptor failed (legacy API)")
            }
            result
        }
    }
}
