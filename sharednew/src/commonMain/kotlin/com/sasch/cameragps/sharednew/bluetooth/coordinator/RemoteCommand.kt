package com.sasch.cameragps.sharednew.bluetooth.coordinator

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants

/**
 * User-facing remote-control commands, written to the camera's remote
 * characteristic through the sequential operation queue.
 *
 * Adding a command is one enum entry with its payload bytes — transport,
 * queueing, Android intent routing and the iOS controller surface are generic.
 * A command that needs a response-driven follow-up (like [ShutterFullPress]'s
 * ack-triggered release) additionally hooks into
 * [RemoteControlCoordinator.onRemoteStatusChanged].
 */
enum class RemoteCommand(internal val payload: ByteArray) {
    /**
     * Full shutter press. Outside a running sequence, the matching release is
     * sent automatically when the camera acknowledges the press via a remote
     * status notification.
     */
    ShutterFullPress(SonyBluetoothConstants.FULL_SHUTTER_DOWN_COMMAND),
    ShutterFullRelease(SonyBluetoothConstants.FULL_SHUTTER_UP_COMMAND),
    ShutterHalfPress(SonyBluetoothConstants.HALF_SHUTTER_DOWN_COMMAND),
    ShutterHalfRelease(SonyBluetoothConstants.HALF_SHUTTER_UP_COMMAND),
    AfOnPress(SonyBluetoothConstants.AF_ON_DOWN_COMMAND),
    AfOnRelease(SonyBluetoothConstants.AF_ON_UP_COMMAND),
    ;

    companion object {
        /** Resolve a command from its [name], e.g. from an Android intent extra. */
        fun fromName(name: String?): RemoteCommand? =
            entries.firstOrNull { it.name == name }
    }
}
