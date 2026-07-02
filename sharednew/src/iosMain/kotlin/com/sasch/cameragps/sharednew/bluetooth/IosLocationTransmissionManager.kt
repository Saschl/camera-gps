package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationDataConfig
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Manages CoreLocation updates and periodic location transmission to ready BLE peripherals.
 *
 * Extracted from [IosBluetoothController] so the controller only deals with
 * CoreBluetooth wiring. This class owns:
 * - CLLocationManager + its delegate
 * - Location freshness/accuracy filtering
 * - Periodic retransmission timer
 * - Building and dispatching location packets through [Host]
 *
 * Must be used from the main thread only (matches CoreBluetooth threading contract).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosLocationTransmissionManager(
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /**
     * Callback interface so the controller can supply session state
     * without exposing its internal types.
     */
    internal interface Host {
        /** Identifiers of peripherals whose BLE handshake is complete. */
        fun getReadySessionIdentifiers(): Set<String>

        /** Camera-specific location packet config (from the handshake config-read step). */
        fun getLocationDataConfig(identifier: String): LocationDataConfig?

        /** Write a raw BLE packet to the location characteristic. */
        fun writeLocationPacket(identifier: String, packet: ByteArray)

        /** Whether the user has enabled app-level transmission. */
        fun isAppEnabledForTransmission(): Boolean

        /** Called when location tracking state changes so the controller can refresh UI. */
        fun onLocationTrackingChanged()
    }

    private val logging = logging()

    private var latestLocation: CLLocation? = null
    private var transmissionJob: Job? = null
    private var hasSessionLocation = false

    /** `true` while CLLocationManager is actively delivering updates. */
    var isLocationUpdatesStarted: Boolean = false
        private set

    private companion object {
        const val MAX_IMMEDIATE_FIX_AGE_SECONDS = 5 * 60L
    }

    // ---------------------------------------------------------------------------
    // CoreLocation delegate
    // ---------------------------------------------------------------------------

    private val locationDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            logging.d { "Received new location" }

            if (!host.isAppEnabledForTransmission()) return
            if (!shouldUpdateLocation(location)) return

            hasSessionLocation = true

            if (latestLocation == null || !isFreshFix(latestLocation!!)) {
                runCatching {
                    sendLocationToReadyPeripherals(location)
                }.onFailure {
                    logging.e(it, msg = { "Error sending location to peripherals" })
                }
            }
            latestLocation = location
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            logging.e { "Location error" }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus() == kCLAuthorizationStatusAuthorizedWhenInUse) {
                manager.requestAlwaysAuthorization()
            }
            updateLocationTracking()
        }
    }

    fun hasPreciseAccuracyAuthorization(): Boolean {
        return locationManager.accuracyAuthorization() == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy
    }



    private val locationManager = CLLocationManager().apply {
        delegate = locationDelegate
        desiredAccuracy = platform.CoreLocation.kCLLocationAccuracyBest
        distanceFilter = 2.0
        pausesLocationUpdatesAutomatically = false
        allowsBackgroundLocationUpdates = true
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Re-evaluate whether location updates and the periodic timer should be running.
     * Called after handshake completion, disconnect, or app-enable toggle.
     */
    fun updateLocationTracking() {
        val appEnabled = host.isAppEnabledForTransmission()
        val hasReadyPeripheral = host.getReadySessionIdentifiers().isNotEmpty()

        if (!hasReadyPeripheral || !appEnabled) {
            if (isLocationUpdatesStarted) {
                locationManager.stopUpdatingLocation()
                isLocationUpdatesStarted = false
                hasSessionLocation = false
            }
            transmissionJob?.cancel()
            transmissionJob = null
            host.onLocationTrackingChanged()
            return
        }

        if (!isLocationUpdatesStarted) {
            if (locationManager.authorizationStatus() == kCLAuthorizationStatusAuthorizedWhenInUse) {
                locationManager.requestAlwaysAuthorization()
            } else {
                locationManager.requestWhenInUseAuthorization()
            }
            locationManager.startUpdatingLocation()
            isLocationUpdatesStarted = true
        }

        if (transmissionJob == null) {
            transmissionJob = scope.launch {
                while (isActive) {
                    delay(SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS)
                    logging.d { "Periodic timer – sending location to ready peripherals" }
                    runCatching {
                        latestLocation?.let { sendLocationToReadyPeripherals(it) }
                    }.onFailure { e ->
                        NSLog("error, %s", e.toString())
                    }
                }
            }
        }

        host.onLocationTrackingChanged()
    }

    /**
     * If we already have a cached location, send it immediately to [identifier].
     * Used right after a handshake completes so the camera gets a fix without
     * waiting for the next periodic tick.
     */
    fun sendImmediateIfCached(identifier: String) {
        if (!host.isAppEnabledForTransmission()) return
        latestLocation?.let {
            if (hasSessionLocation || isFreshFix(it)) {
                sendLocationToPeripheral(identifier, it)
            }
        }
    }

    /**
     * Tear down all location state. Called when the app is disabled or
     * all connections are force-shutdown.
     */
    fun shutdown() {
        if (isLocationUpdatesStarted) {
            locationManager.stopUpdatingLocation()
            isLocationUpdatesStarted = false
        }
        transmissionJob?.cancel()
        transmissionJob = null
        latestLocation = null
        hasSessionLocation = false
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private fun sendLocationToReadyPeripherals(location: CLLocation) {
        host.getReadySessionIdentifiers().forEach { sendLocationToPeripheral(it, location) }
    }

    private fun sendLocationToPeripheral(identifier: String, location: CLLocation) {
        val config = host.getLocationDataConfig(identifier)
            ?: LocationDataConfig(shouldSendTimeZoneAndDst = false)

        val lat = location.coordinate.useContents { latitude }
        val lng = location.coordinate.useContents { longitude }

        val packet = LocationPacketBuilder.buildLocationDataPacket(
            config, lat, lng, PlatformTimeZoneInfo(),
        )

        host.writeLocationPacket(identifier, packet)
    }

    private fun shouldUpdateLocation(newLocation: CLLocation): Boolean {
        val current = latestLocation ?: return true
        if (newLocation.horizontalAccuracy < 0 || current.horizontalAccuracy < 0) return true
        val accuracyDifference = newLocation.horizontalAccuracy - current.horizontalAccuracy
        if (accuracyDifference <= SonyBluetoothConstants.ACCURACY_THRESHOLD_METERS) return true
        val ageMs =
            (newLocation.timestamp.timeIntervalSince1970 - current.timestamp.timeIntervalSince1970) * 1000.0
        return ageMs > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
    }

    private fun isFreshFix(location: CLLocation): Boolean {
        val nowSeconds = NSDate().timeIntervalSince1970.toLong()
        val locationSeconds = location.timestamp.timeIntervalSince1970.toLong()
        return (nowSeconds - locationSeconds) <= MAX_IMMEDIATE_FIX_AGE_SECONDS
    }
}

