package com.sasch.cameragps.sharednew.bluetooth

object SonyBluetoothConstants {
    // Service UUID of the sony cameras
    val SERVICE_UUID = "8000dd00-dd00-ffff-ffff-ffffffffffff"

    val CONTROL_SERVICE_UUID = "8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF"

    // Characteristic for the location services
    val CHARACTERISTIC_UUID = "0000dd11-0000-1000-8000-00805f9b34fb"
    val CHARACTERISTIC_READ_UUID = "0000dd21-0000-1000-8000-00805f9b34fb"

    // needed for some cameras to enable the functionality
    val CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND = "0000dd30-0000-1000-8000-00805f9b34fb"
    val CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND = "0000dd31-0000-1000-8000-00805f9b34fb"

    val CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA = "0000dd01-0000-1000-8000-00805f9b34fb"

    val TIME_SYNC_CHARACTERISTIC_UUID = "0000cc13-0000-1000-8000-00805f9b34fb"

    val REMOTE_SERVICE_UUID = "8000ff00-ff00-ffff-ffff-ffffffffffff"

    val REMOTE_CHARACTERISTIC_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"

    val REMOTE_STATUS_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"

    val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val ACTION_REQUEST_SHUTDOWN = "com.saschl.cameragps.ACTION_REQUEST_SHUTDOWN"
    const val ACTION_TRIGGER_REMOTE_SHUTTER = "com.saschl.cameragps.ACTION_TRIGGER_REMOTE_SHUTTER"
    const val ACTION_SEND_REMOTE_COMMAND = "com.saschl.cameragps.ACTION_SEND_REMOTE_COMMAND"
    const val ACTION_TRIGGER_SHUTTER_SEQUENCE =
        "com.saschl.cameragps.ACTION_TRIGGER_SHUTTER_SEQUENCE"
    const val ACTION_SET_REMOTE_CONTROL_MONITORING =
        "com.saschl.cameragps.ACTION_SET_REMOTE_CONTROL_MONITORING"

    // ATT error codes indicating a pairing/encryption problem (same values on Android GATT)
    const val ATT_ERROR_INSUFFICIENT_AUTHENTICATION = 5
    const val ATT_ERROR_INSUFFICIENT_ENCRYPTION = 15

    // GPS enable command bytes
    val GPS_ENABLE_COMMAND = byteArrayOf(0x01)

    // remote control commands (see tools/sony_shutter/intervalometer.py)
    val FULL_SHUTTER_DOWN_COMMAND = byteArrayOf(0x01, 0x09)
    val FULL_SHUTTER_UP_COMMAND = byteArrayOf(0x01, 0x08)
    val HALF_SHUTTER_DOWN_COMMAND = byteArrayOf(0x01, 0x07)
    val HALF_SHUTTER_UP_COMMAND = byteArrayOf(0x01, 0x06)
    val AF_ON_DOWN_COMMAND = byteArrayOf(0x01, 0x15)
    val AF_ON_UP_COMMAND = byteArrayOf(0x01, 0x14)

    // Same bytes as HALF_SHUTTER_UP — a released half-press acts as a harmless status probe
    val PROBE_COMMAND = byteArrayOf(0x01, 0x06)

    /** Remote status payload: camera idle/ready (also acks a full press → auto shutter-up). */
    val STATUS_READY = byteArrayOf(0x02, 0xA0.toByte(), 0x00)

    /** Remote status payload: shutter active — the exposure is running. */
    val STATUS_SHUTTER_ACTIVE = byteArrayOf(0x02, 0xA0.toByte(), 0x20)

    /** Remote status payload: focus acquired after a half press. */
    val STATUS_FOCUS_ACQUIRED = byteArrayOf(0x02, 0x3F, 0x20)

    // Location update interval
    const val LOCATION_UPDATE_INTERVAL_MS = 5000L

    // Accuracy threshold for location updates
    const val ACCURACY_THRESHOLD_METERS = 200.0

    const val locationTransmissionNotificationId = 404
}