package com.sasch.cameragps.sharednew.bluetooth.location

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationDataConfig
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

sealed interface LocationEvent {
    /** First fix of the session was accepted. */
    data object FirstFixAcquired : LocationEvent

    /** A periodic tick fired with no fix available. */
    data object NoLocationAvailable : LocationEvent
}

/**
 * Shared location transmission: freshness/accuracy gating, periodic resend and
 * send-to-ready-sessions. Unifies the former Android
 * `LocationTransmissionCoordinator` and iOS `IosLocationTransmissionManager`.
 *
 * All packet sends go through [port] (the queue-backed [BleGattPort]) so they
 * are serialized with every other BLE operation.
 */
class LocationTransmissionManager(
    private val source: LocationSource,
    private val readySessions: () -> Set<String>,
    private val configFor: (String) -> LocationDataConfig?,
    private val port: BleGattPort,
    private val scope: CoroutineScope,
    /** iOS: app-level transmission toggle. Android: always allowed. */
    private val isTransmissionAllowed: () -> Boolean = { true },
) {
    private val log = logging()

    private val _events = Channel<LocationEvent>(Channel.UNLIMITED)
    val events: Flow<LocationEvent> = _events.receiveAsFlow()

    private val _isActive = MutableStateFlow(false)

    /** `true` while location updates and the periodic loop are running. */
    val isActive: StateFlow<Boolean> = _isActive

    private var latest: GeoLocation? = null
    private var hasSessionLocation = false
    private var collectJob: Job? = null
    private var periodicJob: Job? = null

    /**
     * A device finished its handshake: make sure tracking runs and give the
     * camera a cached fix immediately instead of waiting for the next tick.
     */
    fun onDeviceReady(identifier: String) {
        startIfNeeded()
        sendImmediateIfCached(identifier.uppercase())
    }

    /**
     * Re-evaluate whether tracking should run. Called after disconnects or
     * app-enable toggles; stops everything when no ready session remains.
     */
    fun updateTracking() {
        if (readySessions().isEmpty() || !isTransmissionAllowed()) {
            stopUpdates()
        } else {
            startIfNeeded()
        }
    }

    /** Tear down all location state (service destroy / force shutdown). */
    fun shutdown() {
        stopUpdates()
        latest = null
    }

    // ---- internals ----

    private fun startIfNeeded() {
        if (!isTransmissionAllowed()) return
        if (_isActive.value) return

        // Discard a fix cached from a previous session if it is too old
        latest = latest?.takeIf { hasSessionLocation || !isTooOld(it) }

        if (!source.start()) {
            log.e { "Location source could not be started, cannot begin transmission" }
            return
        }
        _isActive.value = true
        log.i { "Starting location transmission" }

        collectJob = scope.launch {
            source.locations.collect { onNewLocation(it) }
        }
        periodicJob = scope.launch {
            while (isActive) {
                delay(SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS)
                val current = latest
                if (current != null) {
                    log.d { "Periodic timer – sending location to ready sessions" }
                    runCatching { sendToReadySessions(current) }
                        .onFailure { log.e(it, msg = { "Error sending location" }) }
                } else {
                    log.w { "Periodic timer – no location available to send" }
                    _events.trySend(LocationEvent.NoLocationAvailable)
                }
            }
        }
    }

    private fun stopUpdates() {
        if (_isActive.value) {
            source.stop()
            log.i { "Stopped location transmission" }
        }
        collectJob?.cancel()
        collectJob = null
        periodicJob?.cancel()
        periodicJob = null
        _isActive.value = false
        hasSessionLocation = false
    }

    private fun onNewLocation(location: GeoLocation) {
        if (!isTransmissionAllowed()) return
        val current = latest
        if (!shouldUpdateLocation(location, current)) return

        val hadLocationBefore = current != null
        hasSessionLocation = true

        // Send right away when there was no usable fix yet; otherwise the
        // periodic loop picks the new fix up on its next tick.
        if (current == null || !isFreshFix(current)) {
            runCatching { sendToReadySessions(location) }
                .onFailure { log.e(it, msg = { "Error sending location" }) }
        }
        latest = location

        if (!hadLocationBefore) {
            _events.trySend(LocationEvent.FirstFixAcquired)
        }
    }

    private fun sendImmediateIfCached(identifier: String) {
        if (!isTransmissionAllowed()) return
        latest?.let {
            if (hasSessionLocation || isFreshFix(it)) {
                sendToDevice(identifier, it)
            }
        }
    }

    private fun sendToReadySessions(location: GeoLocation) {
        readySessions().forEach { sendToDevice(it, location) }
    }

    private fun sendToDevice(identifier: String, location: GeoLocation) {
        val config = configFor(identifier) ?: LocationDataConfig(shouldSendTimeZoneAndDst = false)
        val packet = LocationPacketBuilder.buildLocationDataPacket(
            config,
            location.latitude,
            location.longitude,
            PlatformTimeZoneInfo(),
        )
        port.writeCharacteristic(identifier, SonyBluetoothConstants.CHARACTERISTIC_UUID, packet)
    }

    private fun shouldUpdateLocation(new: GeoLocation, current: GeoLocation?): Boolean {
        current ?: return true
        if (new.horizontalAccuracyMeters < 0 || current.horizontalAccuracyMeters < 0) return true

        val accuracyDifference = new.horizontalAccuracyMeters - current.horizontalAccuracyMeters
        if (accuracyDifference <= SonyBluetoothConstants.ACCURACY_THRESHOLD_METERS) return true

        val ageMs = new.timestampMillis - current.timestampMillis
        return ageMs > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
    }

    private fun isTooOld(location: GeoLocation): Boolean =
        nowMillis() - location.timestampMillis > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS

    private fun isFreshFix(location: GeoLocation): Boolean =
        (nowMillis() - location.timestampMillis) / 1000 <= MAX_IMMEDIATE_FIX_AGE_SECONDS

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val MAX_IMMEDIATE_FIX_AGE_SECONDS = 5 * 60L
    }
}
