package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO

/**
 * Persistence and per-device policy state for the iOS shell: the device
 * database, the legacy NSUserDefaults auto-reconnect store (kept only for
 * migration), the in-memory caches over both, and the device-enabled state.
 *
 * Main-thread confined like the rest of the controller. Never calls back into
 * the controller or CoreBluetooth — peripheral name resolution for the legacy
 * migration is injected via [resolveNames].
 */
internal class IosDeviceRepository(
    private val deviceDao: CameraDeviceDAO,
    /** Resolves peripheral UUID strings to display names (empty map if unavailable). */
    private val resolveNames: (List<String>) -> Map<String, String?>,
) {
    private val log = logging()

    private val autoReconnectStore = IosAutoReconnectStore()

    /** Normalized (uppercase) id -> DB row snapshot. */
    private val persistedDevices = mutableMapOf<String, CameraDevice>()

    /** Normalized id -> enabled, in-memory cache over the DAO. */
    private val deviceEnabledOverrides = mutableMapOf<String, Boolean>()

    /** Saved devices by normalized id (read-only view of the cache). */
    val savedDevices: Map<String, CameraDevice> get() = persistedDevices

    fun loadStoreFromDisk() = autoReconnectStore.loadFromDisk()

    fun markAutoReconnect(id: String) = autoReconnectStore.add(id)

    fun removeAutoReconnect(id: String) = autoReconnectStore.remove(id)

    fun isAutoReconnectEnabled(id: String): Boolean = autoReconnectStore.contains(id)

    fun isSaved(id: String): Boolean =
        autoReconnectStore.contains(id) || persistedDevices.containsKey(id.uppercase())

    fun deviceNameFor(id: String): String? = persistedDevices[id.uppercase()]?.deviceName

    suspend fun isDeviceEnabled(identifier: String): Boolean {
        val normalized = identifier.uppercase()
        deviceEnabledOverrides[normalized]?.let { return it }

        val enabled = deviceDao.findDeviceEnabled(normalized)
        if (enabled != null) {
            deviceEnabledOverrides[normalized] = enabled
            return enabled
        }
        // No record yet: devices saved by pre-database versions only exist in
        // NSUserDefaults until migrateLegacyDevicesToDatabase runs at power-on
        // (state restoration can reach this earlier). They were always enabled.
        return true
    }

    /** In-memory half of a device-enabled toggle (the DAO write happens in the detail UI). */
    fun setDeviceEnabled(identifier: String, enabled: Boolean) {
        val normalized = identifier.uppercase()
        deviceEnabledOverrides[normalized] = enabled
        persistedDevices[normalized] =
            persistedDevices[normalized]?.copy(deviceEnabled = enabled)
                ?: CameraDevice(mac = normalized, deviceEnabled = enabled)
    }

    /** Insert-if-absent a device record and update the cache with [resolvedName]. */
    suspend fun ensureDeviceRecord(identifier: String, resolvedName: String) {
        val normalized = identifier.uppercase()
        val entry = CameraDevice(mac = normalized, deviceName = resolvedName)
        deviceDao.insertDevice(entry)
        persistedDevices[normalized] =
            persistedDevices[normalized]?.copy(deviceName = resolvedName) ?: entry
    }

    suspend fun deleteDevice(identifier: String) {
        val normalized = identifier.uppercase()
        deviceEnabledOverrides.remove(normalized)
        persistedDevices.remove(normalized)
        deviceDao.deleteDevice(CameraDevice(mac = normalized))
    }

    /**
     * Older app versions persisted saved devices only as peripheral UUIDs in
     * NSUserDefaults ([IosAutoReconnectStore]). Seed a database record for any
     * persisted peripheral that has none, so those devices keep their saved
     * state, name and toggles after the upgrade. Idempotent; a no-op once every
     * persisted peripheral has a record. Requires the store to be loaded from
     * disk ([loadStoreFromDisk]).
     */
    suspend fun migrateLegacyDevicesToDatabase() {
        val ids = autoReconnectStore.getAll()
        if (ids.isEmpty()) return

        val knownMacs = deviceDao.getAllCameraDevices().mapTo(mutableSetOf()) { it.mac.uppercase() }
        val missing = ids.filterNot { it.uppercase() in knownMacs }
        if (missing.isEmpty()) return

        val namesByNormalizedId = resolveNames(missing)

        missing.forEach { id ->
            val normalized = id.uppercase()
            deviceDao.insertDevice(
                CameraDevice(
                    mac = normalized,
                    deviceEnabled = true,
                    deviceName = namesByNormalizedId[normalized] ?: "N/A",
                    remoteControlEnabled = false,
                )
            )
            log.i { "Migrated legacy saved device $normalized to the device database" }
        }
    }

    /** Reload the caches from the database (the DB is the source of truth). */
    suspend fun sync() {
        val devicesFromDb = deviceDao.getAllCameraDevices()
        persistedDevices.clear()
        devicesFromDb.forEach { device ->
            val normalized = device.mac.uppercase()
            persistedDevices[normalized] = device.copy(mac = normalized)
            deviceEnabledOverrides[normalized] = device.deviceEnabled
        }
    }
}
