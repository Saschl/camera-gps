package com.sasch.cameragps.sharednew.bluetooth

/**
 * Shell-owned device identity/connection state. Per-device session state
 * (phase, remote feature, shutter) lives in `CameraSession` — observe the
 * sessions StateFlow instead of mirroring fields here.
 */
data class BluetoothDeviceInfo(
    val identifier: String,
    val name: String,
    val isConnected: Boolean,
    val isSaved: Boolean = false,
    /** Android CDM bond state; iOS always true (pairing is part of connecting). */
    val isPaired: Boolean = true,
)

enum class BluetoothCapability {
    Scan,
    Connect,
    ObserveConnection,
}

