package com.saschl.cameragps.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.sasch.cameragps.sharednew.ui.settings.LocationProvider
import com.saschl.cameragps.service.TransmissionSoundEvent
import com.saschl.cameragps.service.TransmissionSoundMode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

object PreferencesManager {
    private const val PREFS_NAME = "camera_gps_prefs"
    private const val KEY_FIRST_LAUNCH = "is_first_launch"
    private const val KEY_APP_ENABLED = "app_enabled"
    private const val KEY_BATTERY_OPTIMIZATION_DIALOG_DISMISSED = "battery_optimization_dialog_dismissed"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_REVIEW_HINT_LAST_SHOWN = "review_hint_last_shown"
    private const val KEY_REVIEW_HINT_SHOWN_TIMES = "review_hint_shown_times"
    private const val KEY_DONATION_HINT_LAST_SHOWN = "donation_hint_last_shown"
    private const val KEY_DONATION_HINT_SHOWN_TIMES = "donation_hint_shown_times"
    private const val KEY_FORCE_DONATION_DIALOG_ON_NEXT_START =
        "force_donation_dialog_on_next_start"
    private const val KEY_IGNORE_PERMISSIONS = "ignore_permissions"
    private const val KEY_LOCATION_PROVIDER = "location_provider"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_TRANSMISSION_EVENT_SOUNDS_ENABLED = "transmission_event_sounds_enabled"
    private const val KEY_SOUND_LOCATION_ACQUIRED = "sound_location_acquired"
    private const val KEY_SOUND_LOCATION_INVALID = "sound_location_invalid"
    private const val KEY_SOUND_CAMERA_CONNECTED = "sound_camera_connected"
    private const val KEY_SOUND_CAMERA_DISCONNECTED = "sound_camera_disconnected"
    private const val KEY_SOUND_MODE_LOCATION_ACQUIRED = "sound_mode_location_acquired"
    private const val KEY_SOUND_MODE_LOCATION_INVALID = "sound_mode_location_invalid"
    private const val KEY_SOUND_MODE_CAMERA_CONNECTED = "sound_mode_camera_connected"
    private const val KEY_SOUND_MODE_CAMERA_DISCONNECTED = "sound_mode_camera_disconnected"
    private const val KEY_SOUND_URI_LOCATION_ACQUIRED = "sound_uri_location_acquired"
    private const val KEY_SOUND_URI_LOCATION_INVALID = "sound_uri_location_invalid"
    private const val KEY_SOUND_URI_CAMERA_CONNECTED = "sound_uri_camera_connected"
    private const val KEY_SOUND_URI_CAMERA_DISCONNECTED = "sound_uri_camera_disconnected"
    private const val LOCATION_PROVIDER_PLAY_SERVICES = "play_services"
    private const val LOCATION_PROVIDER_PLATFORM = "platform"
    private const val SOUND_MODE_DEFAULT = "default"
    private const val SOUND_MODE_SILENT = "silent"
    private const val SOUND_MODE_CUSTOM = "custom"

    // LocationProvider enum lives in shared module (com.saschl.cameragps.shared.ui.settings)

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isFirstLaunch(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchCompleted(context: Context) {
        getPreferences(context).edit {
            putBoolean(KEY_FIRST_LAUNCH, false)
        }
    }
    fun showFirstLaunch(context: Context) {
        getPreferences(context).edit {
            putBoolean(KEY_FIRST_LAUNCH, true)
        }
    }

    fun isAppEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_APP_ENABLED, true)
    }

    fun setAppEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_APP_ENABLED, enabled)
        }
    }


    fun reviewHintLastShownDaysAgo(context: Context, initialize: Boolean = false): Long {
        val lastShown = getPreferences(context).getLong(KEY_REVIEW_HINT_LAST_SHOWN, 0L)

        var lastShownInstant: Instant
        if (lastShown == 0L && initialize) {
            // give the user one day breathing room before showing the hint after setting up the first device
            lastShownInstant = Instant.now().minus(29, ChronoUnit.DAYS)
            getPreferences(context).edit {
                putLong(KEY_REVIEW_HINT_LAST_SHOWN, lastShownInstant.epochSecond)
            }
        } else {
            lastShownInstant = Instant.ofEpochSecond(lastShown)

        }
        val daysAgo = Duration.between(lastShownInstant, Instant.now()).toDays()
        return daysAgo
    }

    fun setReviewHintShownNow(context: Context) {
        getPreferences(context).edit {
            putLong(KEY_REVIEW_HINT_LAST_SHOWN, Instant.now().epochSecond)
        }
    }

    fun resetReviewHintShown(context: Context) {
        getPreferences(context).edit {
            putLong(KEY_REVIEW_HINT_LAST_SHOWN, 0L)
        }
    }

    fun reviewHintShownTimes(applicationContext: Context): Int {
        return getPreferences(applicationContext).getInt(KEY_REVIEW_HINT_SHOWN_TIMES, 0)
    }

    fun increaseReviewHintShownTimes(applicationContext: Context) {
        val currentTimes = reviewHintShownTimes(applicationContext)
        getPreferences(applicationContext).edit {
            putInt(KEY_REVIEW_HINT_SHOWN_TIMES, currentTimes + 1)
        }
    }

    fun decreaseReviewHintShownTimes(applicationContext: Context) {
        val currentTimes = reviewHintShownTimes(applicationContext)
        getPreferences(applicationContext).edit {
            putInt(KEY_REVIEW_HINT_SHOWN_TIMES, currentTimes - 1)
        }
    }

    fun reviewHintLastShownInstant(context: Context): Instant {
        return Instant.ofEpochSecond(getPreferences(context).getLong(KEY_REVIEW_HINT_LAST_SHOWN, 0))
    }

    fun donationHintLastShownDaysAgo(context: Context, initialize: Boolean = false): Long {
        val lastShown = getPreferences(context).getLong(KEY_DONATION_HINT_LAST_SHOWN, 0L)

        val lastShownInstant = if (lastShown == 0L && initialize) {
            // Initialize so the donation prompt appears on a later app open, not immediately.
            Instant.now().minus(29, ChronoUnit.DAYS).also {
                getPreferences(context).edit {
                    putLong(KEY_DONATION_HINT_LAST_SHOWN, it.epochSecond)
                }
            }
        } else {
            Instant.ofEpochSecond(lastShown)
        }

        return Duration.between(lastShownInstant, Instant.now()).toDays()
    }

    fun setDonationHintShownNow(context: Context) {
        getPreferences(context).edit {
            putLong(KEY_DONATION_HINT_LAST_SHOWN, Instant.now().epochSecond)
        }
    }

    fun donationHintShownTimes(applicationContext: Context): Int {
        return getPreferences(applicationContext).getInt(KEY_DONATION_HINT_SHOWN_TIMES, 0)
    }

    fun increaseDonationHintShownTimes(applicationContext: Context) {
        val currentTimes = donationHintShownTimes(applicationContext)
        getPreferences(applicationContext).edit {
            putInt(KEY_DONATION_HINT_SHOWN_TIMES, currentTimes + 1)
        }
    }

    fun setForceDonationDialogOnNextAppStart(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_FORCE_DONATION_DIALOG_ON_NEXT_START, enabled)
        }
    }

    fun consumeForceDonationDialogOnNextAppStart(context: Context): Boolean {
        val shouldForce =
            getPreferences(context).getBoolean(KEY_FORCE_DONATION_DIALOG_ON_NEXT_START, false)
        if (shouldForce) {
            getPreferences(context).edit {
                remove(KEY_FORCE_DONATION_DIALOG_ON_NEXT_START)
            }
        }
        return shouldForce
    }

    fun resetReviewHintData(context: Context) {
        getPreferences(context).edit {
            putLong(KEY_REVIEW_HINT_LAST_SHOWN, 0L)
            putInt(KEY_REVIEW_HINT_SHOWN_TIMES, 0)
        }
    }

    fun logLevel(context: Context): Int {
        return getPreferences(context).getInt(KEY_LOG_LEVEL, Log.INFO)
    }

    fun setLogLevel(context: Context, logLevel: Int) {
        getPreferences(context).edit {
            putInt(KEY_LOG_LEVEL, logLevel)
        }
    }

    fun isHapticsEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_HAPTICS_ENABLED, true)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_HAPTICS_ENABLED, enabled)
        }
    }

    fun setAutoStartAfterBootEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean("auto_start_after_boot", enabled)
        }
    }

    fun getAutoStartAfterBootEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean("auto_start_after_boot", false)
    }

    fun sentryEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean("sentry_enabled", false)
    }

    fun setSentryEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean("sentry_enabled", enabled)
        }
    }

    fun isSentryConsentDialogDismissed(context: Context): Boolean {
        return getPreferences(context).getBoolean("sentry_consent_dialog_dismissed", false)
    }

    fun setSentryConsentDialogDismissed(context: Context, dismissed: Boolean) {
        getPreferences(context).edit {
            putBoolean("sentry_consent_dialog_dismissed", dismissed)
        }
    }

    fun isPermissionsIgnored(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IGNORE_PERMISSIONS, false)
    }

    fun setPermissionsIgnored(context: Context, ignored: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_IGNORE_PERMISSIONS, ignored)
        }
    }

    fun getLocationProvider(context: Context): LocationProvider {
        val stored = getPreferences(context).getString(
            KEY_LOCATION_PROVIDER,
            LOCATION_PROVIDER_PLAY_SERVICES
        )
        return if (stored == LOCATION_PROVIDER_PLATFORM) {
            LocationProvider.PLATFORM
        } else {
            LocationProvider.PLAY_SERVICES
        }
    }

    fun setLocationProvider(context: Context, provider: LocationProvider) {
        val stored = if (provider == LocationProvider.PLATFORM) {
            LOCATION_PROVIDER_PLATFORM
        } else {
            LOCATION_PROVIDER_PLAY_SERVICES
        }
        getPreferences(context).edit {
            putString(KEY_LOCATION_PROVIDER, stored)
        }
    }

    fun isTransmissionEventSoundsEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_TRANSMISSION_EVENT_SOUNDS_ENABLED, false)
    }

    fun setTransmissionEventSoundsEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_TRANSMISSION_EVENT_SOUNDS_ENABLED, enabled)
        }
    }

    fun isTransmissionEventEnabled(context: Context, event: TransmissionSoundEvent): Boolean {
        val key = when (event) {
            TransmissionSoundEvent.LOCATION_ACQUIRED -> KEY_SOUND_LOCATION_ACQUIRED
            TransmissionSoundEvent.LOCATION_INVALID -> KEY_SOUND_LOCATION_INVALID
            TransmissionSoundEvent.CAMERA_CONNECTED -> KEY_SOUND_CAMERA_CONNECTED
            TransmissionSoundEvent.CAMERA_DISCONNECTED -> KEY_SOUND_CAMERA_DISCONNECTED
        }
        return getPreferences(context).getBoolean(key, true)
    }

    fun setTransmissionEventEnabled(
        context: Context,
        event: TransmissionSoundEvent,
        enabled: Boolean
    ) {
        val key = when (event) {
            TransmissionSoundEvent.LOCATION_ACQUIRED -> KEY_SOUND_LOCATION_ACQUIRED
            TransmissionSoundEvent.LOCATION_INVALID -> KEY_SOUND_LOCATION_INVALID
            TransmissionSoundEvent.CAMERA_CONNECTED -> KEY_SOUND_CAMERA_CONNECTED
            TransmissionSoundEvent.CAMERA_DISCONNECTED -> KEY_SOUND_CAMERA_DISCONNECTED
        }
        getPreferences(context).edit {
            putBoolean(key, enabled)
        }
    }

    fun getTransmissionEventSoundMode(
        context: Context,
        event: TransmissionSoundEvent
    ): TransmissionSoundMode {
        val modeKey = soundModeKey(event)
        val stored = getPreferences(context).getString(modeKey, SOUND_MODE_DEFAULT)
        return when (stored) {
            SOUND_MODE_SILENT -> TransmissionSoundMode.SILENT
            SOUND_MODE_CUSTOM -> TransmissionSoundMode.CUSTOM
            else -> TransmissionSoundMode.DEFAULT
        }
    }

    fun setTransmissionEventSoundMode(
        context: Context,
        event: TransmissionSoundEvent,
        mode: TransmissionSoundMode
    ) {
        val modeValue = when (mode) {
            TransmissionSoundMode.DEFAULT -> SOUND_MODE_DEFAULT
            TransmissionSoundMode.SILENT -> SOUND_MODE_SILENT
            TransmissionSoundMode.CUSTOM -> SOUND_MODE_CUSTOM
        }
        getPreferences(context).edit {
            putString(soundModeKey(event), modeValue)
        }
    }

    fun getTransmissionEventSoundUri(context: Context, event: TransmissionSoundEvent): String? {
        return getPreferences(context).getString(soundUriKey(event), null)
    }

    fun setTransmissionEventSoundUri(
        context: Context,
        event: TransmissionSoundEvent,
        uri: String?
    ) {
        getPreferences(context).edit {
            if (uri == null) {
                remove(soundUriKey(event))
            } else {
                putString(soundUriKey(event), uri)
            }
        }
    }

    private fun soundModeKey(event: TransmissionSoundEvent): String = when (event) {
        TransmissionSoundEvent.LOCATION_ACQUIRED -> KEY_SOUND_MODE_LOCATION_ACQUIRED
        TransmissionSoundEvent.LOCATION_INVALID -> KEY_SOUND_MODE_LOCATION_INVALID
        TransmissionSoundEvent.CAMERA_CONNECTED -> KEY_SOUND_MODE_CAMERA_CONNECTED
        TransmissionSoundEvent.CAMERA_DISCONNECTED -> KEY_SOUND_MODE_CAMERA_DISCONNECTED
    }

    private fun soundUriKey(event: TransmissionSoundEvent): String = when (event) {
        TransmissionSoundEvent.LOCATION_ACQUIRED -> KEY_SOUND_URI_LOCATION_ACQUIRED
        TransmissionSoundEvent.LOCATION_INVALID -> KEY_SOUND_URI_LOCATION_INVALID
        TransmissionSoundEvent.CAMERA_CONNECTED -> KEY_SOUND_URI_CAMERA_CONNECTED
        TransmissionSoundEvent.CAMERA_DISCONNECTED -> KEY_SOUND_URI_CAMERA_DISCONNECTED
    }
}
