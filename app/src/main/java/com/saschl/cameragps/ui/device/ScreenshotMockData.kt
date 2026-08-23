package com.saschl.cameragps.ui.device

import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.saschl.cameragps.service.AssociatedDeviceCompat

/**
 * Toggle this flag to true before taking Play Store screenshots,
 * then set it back to false before shipping production builds.
 */
internal const val SCREENSHOT_MODE = false

internal val mockDevices = listOf(
    AssociatedDeviceCompat(
        id = 1,
        address = "AA:BB:CC:DD:EE:01",
        name = "ILCE-7M4",
        device = null,
        isPaired = true,
    ),
    AssociatedDeviceCompat(
        id = 2,
        address = "AA:BB:CC:DD:EE:02",
        name = "ILCE-6700",
        device = null,
        isPaired = true,
    ),
    AssociatedDeviceCompat(
        id = 3,
        address = "AA:BB:CC:DD:EE:03",
        name = "ZV-E10M2",
        device = null,
        isPaired = true,
    ),
    AssociatedDeviceCompat(
        id = 4,
        address = "AA:BB:CC:DD:EE:04",
        name = "ILCE-9M3",
        device = null,
        isPaired = true,
    ),
)

/**
 * Per-row state for the mock devices: the first camera is fully active
 * (transmitting + remote feature on) so screenshots show the green status
 * and the large shutter button. Devices without an entry render all-off.
 */
internal val mockDeviceListItems = mapOf(
    "AA:BB:CC:DD:EE:01" to DeviceListItem(
        identifier = "AA:BB:CC:DD:EE:01",
        isAlwaysOnEnabled = true,
        isTransmissionActive = true,
        isRemoteFeatureActive = true,
        isShutterActive = true,
    ),
)

