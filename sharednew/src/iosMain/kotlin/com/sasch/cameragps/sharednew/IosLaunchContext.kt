package com.sasch.cameragps.sharednew

import com.sasch.cameragps.sharednew.bluetooth.restore.LaunchKind

/**
 * Why iOS started this process, recorded by the Swift `AppDelegate` from
 * `didFinishLaunchingWithOptions` before anything else runs.
 *
 * This is deliberately a separate, dependency-free object rather than a field on
 * `IosBluetoothController`: touching that object runs every one of its property
 * initializers, including the one that creates the CBCentralManager, so a flag
 * stored there would always arrive too late to influence the launch path.
 *
 * Main-thread confined like the rest of the iOS shell.
 */
object IosLaunchContext {

    /** iOS relaunched us for a Core Bluetooth event; no UI will be attached. */
    var launchedForBluetoothRestore: Boolean = false
        private set

    /** iOS relaunched us for a Core Location event; no UI will be attached. */
    var launchedForLocationEvent: Boolean = false
        private set

    /** True when the process was started by the system rather than by the user. */
    val isBackgroundLaunch: Boolean
        get() = launchedForBluetoothRestore || launchedForLocationEvent

    val launchKind: LaunchKind
        get() = when {
            launchedForBluetoothRestore -> LaunchKind.BluetoothRestoration
            launchedForLocationEvent -> LaunchKind.LocationEvent
            else -> LaunchKind.UserForeground
        }

    /**
     * Called from `AppDelegate.application(_:didFinishLaunchingWithOptions:)`
     * with the presence of `UIApplication.LaunchOptionsKey.bluetoothCentrals`
     * and `.location`.
     */
    fun record(bluetooth: Boolean, location: Boolean) {
        launchedForBluetoothRestore = bluetooth
        launchedForLocationEvent = location
    }

    /** Short description for the single launch log line. */
    fun describe(): String = when {
        launchedForBluetoothRestore && launchedForLocationEvent -> "bluetooth+location"
        launchedForBluetoothRestore -> "bluetooth"
        launchedForLocationEvent -> "location"
        else -> "user"
    }
}
