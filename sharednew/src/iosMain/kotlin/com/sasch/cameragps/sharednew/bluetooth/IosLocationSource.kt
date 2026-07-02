package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * iOS [LocationSource] over CLLocationManager. Owns the manager configuration,
 * the delegate and the WhenInUse→Always authorization escalation; all gating
 * and periodic-send logic lives in the shared LocationTransmissionManager.
 *
 * Main-thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosLocationSource : LocationSource {

    private val log = logging()

    /** Set by the controller to re-evaluate tracking when authorization changes. */
    var onAuthorizationChanged: (() -> Unit)? = null

    private val locationChannel = Channel<GeoLocation>(Channel.UNLIMITED)
    override val locations: Flow<GeoLocation> = locationChannel.receiveAsFlow()

    private var started = false

    private val locationDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            log.d { "Received new location" }
            val lat = location.coordinate.useContents { latitude }
            val lng = location.coordinate.useContents { longitude }
            locationChannel.trySend(
                GeoLocation(
                    latitude = lat,
                    longitude = lng,
                    horizontalAccuracyMeters = location.horizontalAccuracy,
                    timestampMillis = (location.timestamp.timeIntervalSince1970 * 1000.0).toLong(),
                )
            )
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            log.e { "Location error" }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus() == kCLAuthorizationStatusAuthorizedWhenInUse) {
                manager.requestAlwaysAuthorization()
            }
            onAuthorizationChanged?.invoke()
        }
    }

    private val locationManager = CLLocationManager().apply {
        delegate = locationDelegate
        desiredAccuracy = platform.CoreLocation.kCLLocationAccuracyBest
        distanceFilter = 2.0
        pausesLocationUpdatesAutomatically = false
        allowsBackgroundLocationUpdates = true
    }

    override fun start(): Boolean {
        if (!started) {
            if (locationManager.authorizationStatus() == kCLAuthorizationStatusAuthorizedWhenInUse) {
                locationManager.requestAlwaysAuthorization()
            } else {
                locationManager.requestWhenInUseAuthorization()
            }
            locationManager.startUpdatingLocation()
            started = true
        }
        return true
    }

    override fun stop() {
        if (started) {
            locationManager.stopUpdatingLocation()
            started = false
        }
    }

    override fun hasPreciseAuthorization(): Boolean =
        locationManager.accuracyAuthorization() ==
                CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy
}
