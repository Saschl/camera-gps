package com.saschl.cameragps.service.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.ui.settings.LocationProvider
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * Android [LocationSource]: FusedLocationProviderClient (Play Services) with a
 * platform LocationManager fallback, selected via the location-provider
 * preference. Owns only provider plumbing — gating and periodic sends live in
 * the shared LocationTransmissionManager.
 */
class AndroidLocationSource(
    private val context: Context,
) : LocationSource {

    private val locationChannel = Channel<GeoLocation>(Channel.UNLIMITED)
    override val locations: Flow<GeoLocation> = locationChannel.receiveAsFlow()

    private var initialized = false
    private var started = false
    private var usePlayServices = true

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (started) return true
        if (!hasAnyLocationProviderEnabled()) {
            Timber.e("No location providers enabled, cannot start location updates")
            return false
        }
        initializeIfNeeded()

        return try {
            if (usePlayServices) {
                startPlayServicesLocationUpdates()
            } else {
                startFallbackLocationUpdates()
            }
            started = true
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start location updates")
            false
        }
    }

    override fun stop() {
        locationCallback?.let { callback ->
            if (usePlayServices) {
                fusedLocationClient?.removeLocationUpdates(callback)
            }
        }
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
            locationListener = null
        }
        started = false
    }

    override fun hasPreciseAuthorization(): Boolean = true

    // --- private helpers ---

    private fun initializeIfNeeded() {
        if (initialized) return
        initialized = true

        usePlayServices =
            PreferencesManager.getLocationProvider(context) == LocationProvider.PLAY_SERVICES

        if (usePlayServices) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            locationManager = context.getSystemService(LocationManager::class.java)

            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)
            if (resultCode != ConnectionResult.SUCCESS) {
                Timber.w("Google Play Services unavailable (code: $resultCode). Check location provider setting.")
            } else {
                Timber.i("Google Play Services available, using FusedLocationProviderClient")
            }
        } else {
            Timber.i("Using platform LocationManager provider")
            locationManager = context.getSystemService(LocationManager::class.java)
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(fetchedLocation: LocationResult) {
                super.onLocationResult(fetchedLocation)
                Timber.d("Got a new location")
                fetchedLocation.lastLocation?.let { emitLocation(it) }
            }
        }
    }

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

    private fun isLocationTooOld(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            location.elapsedRealtimeAgeMillis > 30000
        } else {
            (System.currentTimeMillis() - location.time) > 30000
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPlayServicesLocationUpdates() {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        if (resultCode != ConnectionResult.SUCCESS) {
            Timber.w("Google Play Services unavailable (code: $resultCode). Check location provider setting.")
            return
        }

        val fusedClient = fusedLocationClient
        if (fusedClient == null) {
            Timber.e("FusedLocationProviderClient not initialized")
            return
        }

        // One-shot seed so the camera gets a fix without waiting for the first update
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    if (isLocationTooOld(location)) {
                        Timber.w("Ignoring stale initial location from Play Services")
                    } else {
                        emitLocation(location)
                    }
                }
            }.addOnFailureListener { e ->
                Timber.e(e, "Failed to get initial location from Play Services")
            }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS,
        )
            .setWaitForAccurateLocation(true)
            .setMinUpdateDistanceMeters(2f)
            .build()

        val callback = locationCallback ?: return
        val locationSettings = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)

        LocationServices.getSettingsClient(context).checkLocationSettings(locationSettings.build())
            .addOnSuccessListener {
                Timber.d("Location Settings are satisfied, starting location request")
                fusedClient.requestLocationUpdates(
                    locationRequest,
                    callback,
                    Looper.getMainLooper()
                )
            }.addOnFailureListener { exception ->
                Timber.e(
                    exception,
                    "Location settings are not satisfied, cannot start location updates"
                )
            }
    }

    @SuppressLint("MissingPermission")
    private fun startFallbackLocationUpdates() {
        val locManager = locationManager ?: run {
            Timber.e("LocationManager not initialized")
            return
        }

        val lastKnownLocation = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (lastKnownLocation != null) {
            if (isLocationTooOld(lastKnownLocation)) {
                Timber.w("Ignoring stale initial location from fallback provider")
            } else {
                Timber.d("Emitting initial location from fallback provider")
                emitLocation(lastKnownLocation)
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Timber.d("Got a new location from fallback provider")
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

    private fun hasAnyLocationProviderEnabled(): Boolean {
        val manager =
            locationManager ?: context.getSystemService(LocationManager::class.java).also {
                locationManager = it
            }
        val gpsEnabled = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val networkEnabled = manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        return gpsEnabled || networkEnabled
    }
}
