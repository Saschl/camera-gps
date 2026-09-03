package com.sasch.cameragps.sharednew.bluetooth.restore

/**
 * The decisions taken on a CoreBluetooth state-restoration launch, kept out of
 * the platform shell so they are testable without a real central manager.
 *
 * The iOS shell maps CBPeripheral/CBManager values onto these types and acts on
 * the result; it must not encode any of the reasoning below itself.
 */

/** Restoration-relevant subset of `CBPeripheralState`. */
enum class PeripheralRestoreState {
    /** `CBPeripheralStateConnected` — the link survived the app's death. */
    Connected,

    /** `CBPeripheralStateConnecting` — a pending connect the system is still servicing. */
    Connecting,

    /** Disconnected or disconnecting: nothing is in flight for this peripheral. */
    Other,
}

/** Why the process is running, as reported by `didFinishLaunchingWithOptions`. */
enum class LaunchKind {
    /** The user tapped the icon, or the app has been running all along. */
    UserForeground,

    /** iOS relaunched us for a Core Bluetooth event. No UI is attached. */
    BluetoothRestoration,

    /** iOS relaunched us for a Core Location event. No UI is attached. */
    LocationEvent,
}

/**
 * What to do with one peripheral handed back in `willRestoreState`.
 *
 * [park] is always true: announcing or cancelling a restored peripheral has to
 * wait for the central to report powered-on, so every one of them is held until
 * then regardless of the other flags.
 */
data class RestoreAction(
    val registerDelegate: Boolean,
    val markConnected: Boolean,
    val trackAsDiscovered: Boolean,
    val park: Boolean = true,
)

/** What to do about scanning once the central reports powered-on. */
enum class ScanAction {
    /** Stop any scan that is running, including one the system restored for us. */
    Stop,

    /** Leave scanning alone; the device-list UI owns it. */
    Leave,
}

object RestorePolicy {

    /**
     * A peripheral restored mid-connect needs its delegate just as much as one
     * restored already connected: CoreBluetooth delivers the callbacks that
     * caused the relaunch immediately afterwards, and they are dropped while the
     * delegate is unset. Only the connected map is state-specific.
     *
     * Registering a delegate is not the same as announcing a connection, so this
     * is safe on the restoration path. Announcing (service discovery) before the
     * central is powered on dead-ends the session in a discovery timeout.
     */
    fun onWillRestore(state: PeripheralRestoreState, appEnabled: Boolean): RestoreAction =
        RestoreAction(
            registerDelegate = appEnabled && state != PeripheralRestoreState.Other,
            markConnected = appEnabled && state == PeripheralRestoreState.Connected,
            trackAsDiscovered = appEnabled,
            park = true,
        )

    /**
     * Power-on never starts a scan. Reconnecting to saved cameras goes through
     * `retrievePeripheralsWithIdentifiers` plus a pending connect and has never
     * needed one, while discovering new cameras is inherently a foreground
     * activity owned by the device-list screen.
     *
     * A scan the system restored on our behalf is stopped unless the app is
     * genuinely foreground-active, because nothing else would ever stop it: on a
     * background launch the UI is never attached, so its scan effect never runs.
     */
    fun scanOnPowerOn(
        launchKind: LaunchKind,
        appActive: Boolean,
        appEnabled: Boolean,
        scanWasRestored: Boolean,
    ): ScanAction {
        if (!appEnabled) return ScanAction.Stop
        val foreground = appActive && launchKind == LaunchKind.UserForeground
        return if (scanWasRestored && !foreground) ScanAction.Stop else ScanAction.Leave
    }

    /**
     * iOS delivers no discoveries at all from a scan with no service filter once
     * the app leaves the foreground, so such a scan burns radio for nothing.
     * Filtered scans stay allowed everywhere.
     */
    fun allowsScan(hasServiceFilter: Boolean, appActive: Boolean): Boolean =
        appActive || hasServiceFilter
}
