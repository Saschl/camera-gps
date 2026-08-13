package com.saschl.cameragps.service.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * Android [LocationSource] backed only by the platform [LocationManager] —
 * no Play Services. The only source in the foss flavor; the fallback provider
 * in the gplay flavor. Owns only provider plumbing — gating and periodic
 * sends live in the shared LocationTransmissionManager.
 */
class PlatformLocationSource(
    private val context: Context,
) : LocationSource {

    private val locationChannel = Channel<GeoLocation>(Channel.UNLIMITED)
    override val locations: Flow<GeoLocation> = locationChannel.receiveAsFlow()

    private var started = false
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (started) return true
        if (!hasAnyLocationProviderEnabled(context)) {
            Timber.e("No location providers enabled, cannot start location updates")
            return false
        }
        locationManager = context.getSystemService(LocationManager::class.java)

        return try {
            startPlatformLocationUpdates()
            started = true
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start location updates")
            false
        }
    }

    override fun stop() {
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
            locationListener = null
        }
        started = false
    }

    override fun hasPreciseAuthorization(): Boolean = true

    // --- private helpers ---

    private fun emitLocation(location: Location) {
        locationChannel.trySend(
            GeoLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracyMeters = location.accuracy.toDouble(),
                timestampMillis = location.time,
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun startPlatformLocationUpdates() {
        val locManager = locationManager ?: run {
            Timber.e("LocationManager not initialized")
            return
        }

        val lastKnownLocation = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (lastKnownLocation != null) {
            if (isLocationTooOld(lastKnownLocation)) {
                Timber.w("Ignoring stale initial location from platform provider")
            } else {
                Timber.d("Emitting initial location from platform provider")
                emitLocation(lastKnownLocation)
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Timber.d("Got a new location from platform provider")
                emitLocation(location)
            }

            override fun onProviderEnabled(provider: String) {
                Timber.i("Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Timber.w("Location provider disabled: $provider")
            }
        }

        locationListener = listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (locManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    10f,
                    listener,
                    Looper.getMainLooper(),
                )
                Timber.i("Started location updates from FUSED provider (Android 12+)")
            } else {
                Timber.w("FUSED provider not available, falling back to GPS")
                requestGpsLocationUpdates(locManager, listener)
            }
        } else {
            requestGpsLocationUpdates(locManager, listener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGpsLocationUpdates(locManager: LocationManager, listener: LocationListener) {
        if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            Timber.i("Started location updates from GPS provider")
        } else if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            Timber.i("GPS disabled, using Network provider as fallback")
        } else {
            Timber.e("No location providers available")
        }
    }
}

internal fun hasAnyLocationProviderEnabled(context: Context): Boolean {
    val manager = context.getSystemService(LocationManager::class.java)
    val gpsEnabled = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    val networkEnabled = manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    return gpsEnabled || networkEnabled
}

internal fun isLocationTooOld(location: Location): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        location.elapsedRealtimeAgeMillis > 30000
    } else {
        (System.currentTimeMillis() - location.time) > 30000
    }
}
