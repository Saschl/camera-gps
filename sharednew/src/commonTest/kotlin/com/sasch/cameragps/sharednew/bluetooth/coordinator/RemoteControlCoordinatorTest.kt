package com.sasch.cameragps.sharednew.bluetooth.coordinator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlCoordinatorTest {

    // ---- Pure function tests ----

    @Test
    fun isRemoteFeatureActive_emptyArray_returnsFalse() {
        assertFalse(RemoteControlCoordinator.isRemoteFeatureActive(byteArrayOf()))
    }

    @Test
    fun isRemoteFeatureActive_inactiveValue_returnsFalse() {
        val inactive = byteArrayOf(0x02, 0xC3.toByte(), 0x00)
        assertFalse(RemoteControlCoordinator.isRemoteFeatureActive(inactive))
    }

    @Test
    fun isRemoteFeatureActive_activeValue_returnsTrue() {
        val active = byteArrayOf(0x02, 0xA0.toByte(), 0x00)
        assertTrue(RemoteControlCoordinator.isRemoteFeatureActive(active))
    }

    @Test
    fun shouldSendShutterUp_matchingValue_returnsTrue() {
        val value = byteArrayOf(0x02, 0xA0.toByte(), 0x00)
        assertTrue(RemoteControlCoordinator.shouldSendShutterUp(value))
    }

    @Test
    fun shouldSendShutterUp_nonMatchingValue_returnsFalse() {
        val value = byteArrayOf(0x02, 0xC3.toByte(), 0x00)
        assertFalse(RemoteControlCoordinator.shouldSendShutterUp(value))
    }

    @Test
    fun hasTimeZoneDstFlag_shortArray_returnsFalse() {
        assertFalse(RemoteControlCoordinator.hasTimeZoneDstFlag(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun hasTimeZoneDstFlag_flagSet_returnsTrue() {
        val value = byteArrayOf(0, 0, 0, 0, 0x02)
        assertTrue(RemoteControlCoordinator.hasTimeZoneDstFlag(value))
    }

    @Test
    fun hasTimeZoneDstFlag_flagNotSet_returnsFalse() {
        val value = byteArrayOf(0, 0, 0, 0, 0x01)
        assertFalse(RemoteControlCoordinator.hasTimeZoneDstFlag(value))
    }

    // ---- Coordinator integration tests with fake port ----

    @Test
    fun handleRemoteShutterRequest_notConnected_returnsFalse() = runTest {
        val port = FakeBleGattPort()
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        assertFalse(coordinator.handleRemoteShutterRequest("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun handleRemoteShutterRequest_connectedButRemoteInactive_returnsFalse() = runTest {
        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        assertFalse(coordinator.handleRemoteShutterRequest("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun handleRemoteShutterRequest_connectedAndActive_returnsTrue() = runTest {
        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")
        port.remoteActiveDevices.add("AA:BB:CC:DD:EE:FF")
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        assertTrue(coordinator.handleRemoteShutterRequest("AA:BB:CC:DD:EE:FF"))
        assertEquals(1, port.writtenCharacteristics.size)
    }

    @Test
    fun onRemoteStatusChanged_activeValue_stopsProbeLoop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)

        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")

        val coordinator = RemoteControlCoordinator(port, scope.backgroundScope)
        coordinator.startRemoteStatusMonitoring("AA:BB:CC:DD:EE:FF")

        // Advance past initial delay + one probe
        scope.advanceTimeBy(4_000)
        val probeCountBefore = port.writtenCharacteristics.size

        // Report feature as active → should stop probing
        val active = byteArrayOf(0x02, 0xA0.toByte(), 0x00)
        coordinator.onRemoteStatusChanged("AA:BB:CC:DD:EE:FF", active)

        // Advance more time — no new probes should appear
        scope.advanceTimeBy(10_000)
        assertEquals(probeCountBefore, port.writtenCharacteristics.size)
    }

    @Test
    fun identifiers_are_uppercased() = runTest {
        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")
        port.remoteActiveDevices.add("AA:BB:CC:DD:EE:FF")
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        // Call with lowercase — should still work
        assertTrue(coordinator.handleRemoteShutterRequest("aa:bb:cc:dd:ee:ff"))
    }
}

/**
 * Minimal fake for testing the shared coordinator without real BLE.
 */
private class FakeBleGattPort : BleGattPort {
    val connectedDevices = mutableSetOf<String>()
    val devicesWithRemoteControl = mutableSetOf<String>()
    val remoteActiveDevices = mutableSetOf<String>()
    val shutterActiveDevices = mutableSetOf<String>()
    val writtenCharacteristics = mutableListOf<Triple<String, String, ByteArray>>()
    val subscriptions = mutableListOf<Pair<String, String>>()

    override fun writeCharacteristic(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean {
        writtenCharacteristics.add(Triple(identifier, characteristicUuid, value))
        return true
    }

    override fun subscribeToNotifications(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        subscriptions.add(identifier to characteristicUuid)
        return true
    }

    override fun isConnected(identifier: String): Boolean =
        identifier in connectedDevices

    override fun hasRemoteControlCharacteristic(identifier: String): Boolean =
        identifier in devicesWithRemoteControl

    override fun isRemoteFeatureActive(identifier: String): Boolean =
        identifier in remoteActiveDevices

    override fun setRemoteFeatureActive(identifier: String, active: Boolean) {
        if (active) remoteActiveDevices.add(identifier) else remoteActiveDevices.remove(identifier)
    }

    override fun setShutterSequenceActive(identifier: String, active: Boolean) {
        if (active) shutterActiveDevices.add(identifier) else shutterActiveDevices.remove(identifier)
    }

    override fun readCharacteristic(identifier: String, characteristicUuid: String): Boolean = true

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean =
        identifier in devicesWithRemoteControl
}

