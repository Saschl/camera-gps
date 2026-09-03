package com.sasch.cameragps.sharednew.bluetooth.restore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestorePolicyTest {

    // --- willRestoreState ----------------------------------------------------

    @Test
    fun connectingPeripheralGetsDelegateButIsNotMarkedConnected() {
        val action = RestorePolicy.onWillRestore(PeripheralRestoreState.Connecting, appEnabled = true)

        // Regression guard: the shell used to register the delegate only for
        // already-connected peripherals, so a pending connect came back deaf.
        assertTrue(action.registerDelegate)
        assertFalse(action.markConnected)
        assertTrue(action.trackAsDiscovered)
        assertTrue(action.park)
    }

    @Test
    fun connectedPeripheralGetsDelegateAndIsMarkedConnected() {
        val action = RestorePolicy.onWillRestore(PeripheralRestoreState.Connected, appEnabled = true)

        assertTrue(action.registerDelegate)
        assertTrue(action.markConnected)
        assertTrue(action.trackAsDiscovered)
    }

    @Test
    fun idlePeripheralGetsNoDelegate() {
        val action = RestorePolicy.onWillRestore(PeripheralRestoreState.Other, appEnabled = true)

        assertFalse(action.registerDelegate)
        assertFalse(action.markConnected)
        assertTrue(action.trackAsDiscovered)
    }

    @Test
    fun appDisabledTouchesNothingButStillParks() {
        PeripheralRestoreState.entries.forEach { state ->
            val action = RestorePolicy.onWillRestore(state, appEnabled = false)

            assertFalse(action.registerDelegate, "registerDelegate for $state")
            assertFalse(action.markConnected, "markConnected for $state")
            assertFalse(action.trackAsDiscovered, "trackAsDiscovered for $state")
            // Parked peripherals still have to reach the power-on cancel path.
            assertTrue(action.park, "park for $state")
        }
    }

    // --- scanning on power-on ------------------------------------------------

    @Test
    fun restoredScanIsStoppedOnABluetoothRelaunch() {
        val action = RestorePolicy.scanOnPowerOn(
            launchKind = LaunchKind.BluetoothRestoration,
            appActive = false,
            appEnabled = true,
            scanWasRestored = true,
        )

        assertEquals(ScanAction.Stop, action)
    }

    @Test
    fun restoredScanIsStoppedWhenTheAppIsNotActive() {
        val action = RestorePolicy.scanOnPowerOn(
            launchKind = LaunchKind.UserForeground,
            appActive = false,
            appEnabled = true,
            scanWasRestored = true,
        )

        assertEquals(ScanAction.Stop, action)
    }

    @Test
    fun restoredScanIsLeftAloneWhileForegroundActive() {
        val action = RestorePolicy.scanOnPowerOn(
            launchKind = LaunchKind.UserForeground,
            appActive = true,
            appEnabled = true,
            scanWasRestored = true,
        )

        assertEquals(ScanAction.Leave, action)
    }

    @Test
    fun scanIsStoppedWheneverTheAppIsDisabled() {
        LaunchKind.entries.forEach { kind ->
            listOf(true, false).forEach { active ->
                val action = RestorePolicy.scanOnPowerOn(kind, active, appEnabled = false, scanWasRestored = false)
                assertEquals(ScanAction.Stop, action, "kind=$kind active=$active")
            }
        }
    }

    @Test
    fun nothingIsTouchedWhenNoScanWasRestored() {
        val action = RestorePolicy.scanOnPowerOn(
            launchKind = LaunchKind.BluetoothRestoration,
            appActive = false,
            appEnabled = true,
            scanWasRestored = false,
        )

        assertEquals(ScanAction.Leave, action)
    }

    // --- scan filter guard ---------------------------------------------------

    @Test
    fun unfilteredScanIsRefusedOutsideTheForeground() {
        assertFalse(RestorePolicy.allowsScan(hasServiceFilter = false, appActive = false))
        assertTrue(RestorePolicy.allowsScan(hasServiceFilter = false, appActive = true))
    }

    @Test
    fun filteredScanIsAllowedEverywhere() {
        assertTrue(RestorePolicy.allowsScan(hasServiceFilter = true, appActive = false))
        assertTrue(RestorePolicy.allowsScan(hasServiceFilter = true, appActive = true))
    }
}
