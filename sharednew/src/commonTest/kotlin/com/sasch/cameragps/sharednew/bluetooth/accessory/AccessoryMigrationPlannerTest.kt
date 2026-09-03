package com.sasch.cameragps.sharednew.bluetooth.accessory

import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessoryMigrationPlannerTest {

    private companion object {
        // Real CBPeripheral.identifier shapes: anything else cannot be migrated.
        const val ID_A = "1D2B4E1C-0000-4000-8000-0000000000A1"
        const val ID_B = "1D2B4E1C-0000-4000-8000-0000000000B2"
    }

    private fun device(mac: String, name: String = "ILCE-7M4") =
        CameraDevice(mac = mac, deviceName = name)

    @Test
    fun everySavedDeviceIsACandidateWhenNothingIsAuthorizedYet() {
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device(ID_A), device(ID_B)),
            authorized = emptySet(),
        )

        assertEquals(listOf(ID_A, ID_B), plan.map { it.identifier })
    }

    @Test
    fun authorizedDevicesAreDroppedRegardlessOfCase() {
        // The database, the CoreBluetooth maps and ASAccessory have historically
        // disagreed on case. A mismatch here would re-prompt users forever.
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device(ID_A.lowercase()), device(ID_B)),
            authorized = setOf(ID_A),
        )

        assertEquals(listOf(ID_B), plan.map { it.identifier })
    }

    @Test
    fun candidatesAreReportedInUppercase() {
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device(ID_A.lowercase())),
            authorized = emptySet(),
        )

        assertEquals(ID_A, plan.single().identifier)
    }

    @Test
    fun duplicatesCollapseToOneEntry() {
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device(ID_A.lowercase()), device(ID_A)),
            authorized = emptySet(),
        )

        assertEquals(1, plan.size)
    }

    @Test
    fun blankIdentifiersAreSkipped() {
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device("   "), device(ID_A)),
            authorized = emptySet(),
        )

        assertEquals(listOf(ID_A), plan.map { it.identifier })
    }

    @Test
    fun identifiersThatAreNotUuidsAreSkipped() {
        // An Android-style MAC, or anything else NSUUID cannot parse, would
        // crash the migration picker rather than merely failing to migrate.
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device("AA:BB:CC:DD:EE:FF"), device("not-a-uuid"), device(ID_A)),
            authorized = emptySet(),
        )

        assertEquals(listOf(ID_A), plan.map { it.identifier })
    }

    @Test
    fun uuidShapeIsCheckedCaseInsensitively() {
        assertTrue(AccessoryMigrationPlanner.isMigratableIdentifier(ID_A.lowercase()))
        assertFalse(AccessoryMigrationPlanner.isMigratableIdentifier("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun theLegacyPlaceholderNameIsReplaced() {
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device(ID_A, name = "N/A")),
            authorized = emptySet(),
        )

        assertEquals(AccessoryMigrationPlanner.FALLBACK_NAME, plan.single().displayName)
    }

    @Test
    fun aBlankNameIsReplaced() {
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device(ID_A, name = "  ")),
            authorized = emptySet(),
        )

        assertEquals(AccessoryMigrationPlanner.FALLBACK_NAME, plan.single().displayName)
    }

    @Test
    fun arealNameIsKept() {
        val plan = AccessoryMigrationPlanner.planMigrations(
            saved = listOf(device(ID_A, name = "ILCE-6700")),
            authorized = emptySet(),
        )

        assertEquals("ILCE-6700", plan.single().displayName)
    }

    @Test
    fun migrationIsCompleteOnlyWhenNoCandidatesRemain() {
        val saved = listOf(device(ID_A), device(ID_B))

        assertFalse(AccessoryMigrationPlanner.isMigrationComplete(saved, setOf(ID_A)))
        assertTrue(
            AccessoryMigrationPlanner.isMigrationComplete(saved, setOf(ID_A, ID_B))
        )
    }

    @Test
    fun aFreshInstallHasNothingToMigrate() {
        assertTrue(AccessoryMigrationPlanner.isMigrationComplete(emptyList(), emptySet()))
    }
}
