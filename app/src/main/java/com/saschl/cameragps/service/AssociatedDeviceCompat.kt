package com.saschl.cameragps.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Wrapper for the different type of classes the CDM returns
 */
data class AssociatedDeviceCompat(
    val id: Int,
    val address: String,
    var name: String,
    val device: BluetoothDevice?,
    var isPaired: Boolean = true
)


@SuppressLint("MissingPermission")
internal fun CompanionDeviceManager.getAssociatedDevices(adapter: BluetoothAdapter): List<AssociatedDeviceCompat> {
    val isBluetoothOn = adapter.isEnabled
    val associatedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        myAssociations.map {
            it.toAssociatedDevice(adapter).apply {
                // Check if device is Bluetooth paired
                isPaired = if (isBluetoothOn) {
                    adapter.bondedDevices.any { bondedDevice ->
                        bondedDevice.address.equals(address, ignoreCase = true)
                    }
                } else {
                    true
                }
            }
        }
    } else {
        // Before Android 34 we can only get the MAC.
        @Suppress("DEPRECATION")
        associations.map {
            val deviceAddress = it.uppercase(Locale.getDefault())
            AssociatedDeviceCompat(
                id = -1,
                address = deviceAddress,
                name = adapter.getRemoteDevice(it.uppercase()).name ?: "N/A",
                device = null,
                isPaired = if (isBluetoothOn) {
                    adapter.bondedDevices.any { bondedDevice ->
                        bondedDevice.address == deviceAddress
                    }
                } else {
                    true
                }
            )
        }
    }
    return associatedDevice
}


@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun AssociationInfo.toAssociatedDevice(adapter: BluetoothAdapter?): AssociatedDeviceCompat {
    val address = deviceMacAddress?.toString()?.uppercase(Locale.getDefault())
    // Android 13 stores no displayName for chooser-based associations (only
    // self-managed ones get it; Android 14+ persists the chooser name), so fall
    // back to the Bluetooth stack's cached name for the bonded device.
    val cachedName = address?.let { adapter?.getRemoteDevice(it)?.name }
    return AssociatedDeviceCompat(
        id = id,
        address = address ?: "N/A",
        name = displayName?.toString()?.takeIf { it.isNotBlank() } ?: cachedName ?: "N/A",
        device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            associatedDevice?.bleDevice?.device
        } else {
            null
        },
    )
}
