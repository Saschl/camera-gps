package com.sasch.cameragps.sharednew.bluetooth.accessory

import com.sasch.cameragps.sharednew.database.devices.CameraDevice

/**
 * A saved camera that predates AccessorySetupKit and still needs the user to
 * re-authorize it through the system picker.
 *
 * [identifier] is the uppercased `CBPeripheral.identifier.UUIDString`, which is
 * what iOS stores in [CameraDevice.mac] and what
 * `ASMigrationDisplayItem.peripheralIdentifier` expects.
 */
data class PendingMigration(
    val identifier: String,
    val displayName: String,
)

/**
 * Works out which saved cameras still have to be migrated into
 * AccessorySetupKit, and under what name to show them in the migration picker.
 *
 * Pure on purpose: this decides whether users keep their cameras across the
 * update, so it is the part that must be covered by tests rather than by a
 * device sitting on a desk.
 */
object AccessoryMigrationPlanner {

    /**
     * Shown when the stored row has no usable name. Devices saved by older
     * versions can carry the literal placeholder `"N/A"`, written when the
     * peripheral name could not be resolved at the time.
     */
    const val FALLBACK_NAME = "Sony camera"

    private const val LEGACY_PLACEHOLDER_NAME = "N/A"

    /**
     * 8-4-4-4-12 hex, the shape of a `CBPeripheral.identifier.UUIDString`.
     * `ASMigrationDisplayItem.peripheralIdentifier` takes an `NSUUID`, and
     * `NSUUID(uUIDString:)` returns nil for anything else — which Kotlin/Native
     * types as non-null and would blow up at the picker. Rows that are not
     * UUID-shaped cannot be migrated, so they are dropped here instead.
     */
    private val UUID_SHAPE =
        Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$")

    /** True when [identifier] can be handed to `NSUUID(uUIDString:)`. */
    fun isMigratableIdentifier(identifier: String): Boolean =
        UUID_SHAPE.matches(identifier.uppercase())

    /**
     * Saved devices that are not yet authorized through AccessorySetupKit, in
     * stable order and without duplicates.
     *
     * Identifier comparison is case-insensitive throughout: the database, the
     * CoreBluetooth maps and `ASAccessory.bluetoothIdentifier` all render the
     * same UUID and have historically differed in case.
     */
    fun planMigrations(
        saved: Collection<CameraDevice>,
        authorized: Set<String>,
    ): List<PendingMigration> {
        val authorizedNormalized = authorized.mapTo(mutableSetOf()) { it.uppercase() }
        val seen = mutableSetOf<String>()

        return saved.mapNotNull { device ->
            val identifier = device.mac.uppercase()
            when {
                identifier.isBlank() -> null
                !isMigratableIdentifier(identifier) -> null
                identifier in authorizedNormalized -> null
                !seen.add(identifier) -> null
                else -> PendingMigration(identifier, displayNameFor(device))
            }
        }
    }

    /** True when nothing is left to migrate, so the prompt can stay away for good. */
    fun isMigrationComplete(
        saved: Collection<CameraDevice>,
        authorized: Set<String>,
    ): Boolean = planMigrations(saved, authorized).isEmpty()

    private fun displayNameFor(device: CameraDevice): String {
        val name = device.deviceName.trim()
        return if (name.isEmpty() || name == LEGACY_PLACEHOLDER_NAME) FALLBACK_NAME else name
    }
}
