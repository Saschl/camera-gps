package com.sasch.cameragps.sharednew.bluetooth.coordinator

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Shared BLE session coordinator. Drives the handshake state machine:
 *
 *   services discovered → config read → GPS enable → GPS lock → time sync → ready
 *
 * Platform-specific BLE I/O is delegated to [BleGattPort].
 * The platform shell (Android service / iOS controller) routes GATT callbacks
 * here and collects emitted [BleSessionEvent]s for side effects.
 *
 * This coordinator also routes remote-status and remote-control callbacks
 * to the [RemoteControlCoordinator].
 */
class BleSessionCoordinator(
    private val port: BleGattPort,
    private val remoteControlCoordinator: RemoteControlCoordinator,
) {
    private val log = logging()

    private val _events = Channel<BleSessionEvent>(Channel.UNLIMITED)

    /** Collect this to receive session phase changes and handshake completion events. */
    val events: Flow<BleSessionEvent> = _events.receiveAsFlow()

    /** Per-device location data config (populated during config-read step). */
    private val locationConfigs = mutableMapOf<String, LocationDataConfig>()

    // ---- Public API: called by platform GATT callbacks ----

    /**
     * Begin the BLE handshake for [identifier].
     *
     * Call this after:
     * - Android: `onServicesDiscovered` with GATT_SUCCESS
     * - iOS: service discovery + pairing complete (`proceedAfterPairing`)
     *
     * The coordinator reads config, enables GPS, syncs time, and marks the device
     * ready for transmission — all through [BleGattPort].
     */
    fun beginHandshake(identifier: String) {
        val id = identifier.uppercase()
        port.setRemoteFeatureActive(id, false)
        emitPhase(id, BleSessionPhase.DiscoveringServices, remoteActive = false)

        if (port.hasCharacteristic(id, SonyBluetoothConstants.CHARACTERISTIC_READ_UUID)) {
            log.d { "Handshake[$id]: requesting config read" }
            emitPhase(id, BleSessionPhase.ReadingConfig)
            port.readCharacteristic(id, SonyBluetoothConstants.CHARACTERISTIC_READ_UUID)
        } else {
            log.d { "Handshake[$id]: no config characteristic, skipping read" }
            locationConfigs[id] = LocationDataConfig(shouldSendTimeZoneAndDst = false)
            enableGps(id)
        }
    }

    /**
     * Called when a characteristic read completes (config-read result).
     */
    fun onCharacteristicRead(identifier: String, value: ByteArray, success: Boolean) {
        val id = identifier.uppercase()
        if (!success) {
            log.w { "Handshake[$id]: config read failed, aborting" }
            emitPhase(id, BleSessionPhase.Error)
            return
        }
        val hasTimeZone = RemoteControlCoordinator.hasTimeZoneDstFlag(value)
        log.d { "Handshake[$id]: config read done (timeZoneAndDst=$hasTimeZone)" }
        locationConfigs[id] = LocationDataConfig(shouldSendTimeZoneAndDst = hasTimeZone)
        enableGps(id)
    }

    /**
     * Called when a characteristic write completes. Routes based on characteristic UUID.
     *
     * The platform shell should handle authentication/pairing errors itself and
     * only forward successful (or non-auth-error) writes here.
     */
    fun onCharacteristicWrite(identifier: String, characteristicUuid: String, success: Boolean) {
        val id = identifier.uppercase()
        val uuid = characteristicUuid.lowercase()

        when {
            uuid == SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND.lowercase() -> {
                if (!success) return
                handleGpsUnlockResponse(id)
            }

            uuid == SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND.lowercase() -> {
                if (!success) return
                sendTimeSyncOrComplete(id)
            }

            uuid == SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID.lowercase() -> {
                // Time sync failed → still proceed to transmission (matches existing behavior)
                markReadyForTransmission(id)
            }

            uuid == SonyBluetoothConstants.CHARACTERISTIC_UUID.lowercase() -> {
                // Location data write response — nothing to do
            }

            uuid == SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID.lowercase() -> {
                remoteControlCoordinator.onRemoteControlWriteResponse(id, success)
            }
        }
    }

    /**
     * Called when a characteristic value changes (notification from camera).
     * Returns `true` if the event was handled.
     */
    fun onCharacteristicChanged(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean {
        val id = identifier.uppercase()
        if (characteristicUuid.equals(
                SonyBluetoothConstants.REMOTE_STATUS_UUID,
                ignoreCase = true
            )
        ) {
            val shouldSendShutterUp = remoteControlCoordinator.onRemoteStatusChanged(id, value)
            if (shouldSendShutterUp) {
                remoteControlCoordinator.sendShutterUp(id)
            }
            return true
        }
        return false
    }

    /**
     * Trigger a remote shutter action (down command only).
     */
    fun triggerRemoteShutter(identifier: String): Boolean {
        return remoteControlCoordinator.triggerRemoteShutter(identifier)
    }

    /**
     * Get the location data config for a device (populated during the handshake).
     */
    fun getLocationDataConfig(identifier: String): LocationDataConfig? {
        return locationConfigs[identifier.uppercase()]
    }

    /**
     * Clear session data for a single device (e.g. on disconnect).
     */
    fun clearSession(identifier: String) {
        val id = identifier.uppercase()
        locationConfigs.remove(id)
        remoteControlCoordinator.cancelProbe(id)
    }

    /**
     * Clear all session data (e.g. on service destroy / force shutdown).
     */
    fun clearAllSessions() {
        locationConfigs.clear()
        remoteControlCoordinator.cancelAllProbes()
    }

    // ---- Private handshake flow ----

    private fun enableGps(identifier: String) {
        if (port.hasCharacteristic(
                identifier,
                SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND
            )
        ) {
            log.d { "Handshake[$identifier]: writing GPS unlock" }
            emitPhase(identifier, BleSessionPhase.EnablingGps)
            port.writeCharacteristic(
                identifier,
                SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND,
                SonyBluetoothConstants.GPS_ENABLE_COMMAND,
            )
        } else {
            log.d { "Handshake[$identifier]: no GPS unlock characteristic, skipping" }
            sendTimeSyncOrComplete(identifier)
        }
    }

    private fun handleGpsUnlockResponse(identifier: String) {
        if (port.hasCharacteristic(
                identifier,
                SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND
            )
        ) {
            log.d { "Handshake[$identifier]: writing GPS lock" }
            emitPhase(identifier, BleSessionPhase.LockingGps)
            port.writeCharacteristic(
                identifier,
                SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND,
                SonyBluetoothConstants.GPS_ENABLE_COMMAND,
            )
        } else {
            log.d { "Handshake[$identifier]: no GPS lock characteristic, skipping" }
            sendTimeSyncOrComplete(identifier)
        }
    }

    private fun sendTimeSyncOrComplete(identifier: String) {
        if (port.hasCharacteristic(
                identifier,
                SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID
            )
        ) {
            log.d { "Handshake[$identifier]: writing time sync" }
            emitPhase(identifier, BleSessionPhase.SyncingTime)
            val packet = LocationPacketBuilder.buildTimeSyncPacket(PlatformTimeZoneInfo())
            port.writeCharacteristic(
                identifier,
                SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID,
                packet,
            )
        } else {
            log.d { "Handshake[$identifier]: no time-sync characteristic, skipping" }
            markReadyForTransmission(identifier)
        }
    }

    private fun markReadyForTransmission(identifier: String) {
        log.d { "Handshake[$identifier]: complete, ready for transmission" }
        _events.trySend(BleSessionEvent.HandshakeComplete(identifier))
        emitPhase(identifier, BleSessionPhase.Transmitting)

    }

    private fun emitPhase(
        identifier: String,
        phase: BleSessionPhase,
        remoteActive: Boolean? = null,
    ) {
        _events.trySend(BleSessionEvent.PhaseChanged(identifier, phase, remoteActive))
    }
}

