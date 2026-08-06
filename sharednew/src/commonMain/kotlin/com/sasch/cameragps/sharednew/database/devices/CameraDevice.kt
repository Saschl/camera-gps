package com.sasch.cameragps.sharednew.database.devices

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_devices")
data class CameraDevice(
    @PrimaryKey(autoGenerate = false)
    val mac: String,
    @ColumnInfo(defaultValue = "1")
    val deviceEnabled: Boolean = true,
    val alwaysOnEnabled: Boolean = false,
    val deviceName: String = "N/A",
    @ColumnInfo(defaultValue = "0")
    val remoteControlEnabled: Boolean = false,
    /**
     * Wait this long after connecting before starting discovery + handshake.
     * Workaround for cameras that stall their own boot while servicing the
     * BLE traffic burst (reported on A7R IV). 0 = start immediately.
     */
    @ColumnInfo(defaultValue = "0")
    val handshakeDelayMs: Long = 0,
)

