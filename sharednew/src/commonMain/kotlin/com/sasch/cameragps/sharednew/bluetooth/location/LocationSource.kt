package com.sasch.cameragps.sharednew.bluetooth.location

import kotlinx.coroutines.flow.Flow

/**
 * A platform-neutral location fix.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in meters; `< 0` means invalid (iOS convention). */
    val horizontalAccuracyMeters: Double,
    /** Epoch millis of the fix. */
    val timestampMillis: Long,
)

/**
 * Platform location provider. Android wraps FusedLocationProviderClient with a
 * LocationManager fallback; iOS wraps CLLocationManager.
 */
interface LocationSource {

    /** Hot stream of fixes while started. Emissions may come from any thread/looper. */
    val locations: Flow<GeoLocation>

    /**
     * Start delivering fixes. Idempotent. Android also fires its one-shot
     * `getCurrentLocation`/last-known seed here.
     * Returns `false` if updates could not be started (e.g. no provider enabled).
     */
    fun start(): Boolean

    fun stop()

    /** iOS: `CLAccuracyAuthorizationFullAccuracy`. Android: always `true`. */
    fun hasPreciseAuthorization(): Boolean
}
