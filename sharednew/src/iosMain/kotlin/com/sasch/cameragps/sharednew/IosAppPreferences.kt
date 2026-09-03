package com.sasch.cameragps.sharednew

import com.diamondedge.logging.LogLevel
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import kotlin.math.floor

internal object IosAppPreferences {
    private const val keyShowWelcome = "ios.showWelcome"
    private const val keyAppEnabled = "ios.appEnabled"
    private const val keyAutoScanEnabled = "ios.autoScanEnabled"
    private const val keyHapticsEnabled = "ios.hapticsEnabled"
    private const val keyDonationHintLastShown = "ios.donationHintLastShown"
    private const val keyDonationHintShownTimes = "ios.donationHintShownTimes"
    private const val keyForceDonationDialogOnNextStart = "ios.forceDonationDialogOnNextStart"

    /**
     * Set once every camera saved before the AccessorySetupKit switch has been
     * re-authorized through the system picker (or once there was nothing to
     * migrate). Until then the CBCentralManager is not created, because
     * AccessorySetupKit refuses to migrate when one already exists.
     */
    private const val keyAccessoryMigrationDone = "ios.accessoryMigrationDone"

    private const val logLevel = "ios.logLevel"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    fun showWelcomeOnLaunch(): Boolean = defaults.objectForKey(keyShowWelcome)?.let {
        defaults.boolForKey(keyShowWelcome)
    } ?: true

    fun setShowWelcomeOnLaunch(show: Boolean) {
        defaults.setBool(show, forKey = keyShowWelcome)
    }

    fun isAppEnabled(): Boolean = defaults.objectForKey(keyAppEnabled)?.let {
        defaults.boolForKey(keyAppEnabled)
    } ?: true

    fun setAppEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyAppEnabled)
    }

    fun isAccessoryMigrationDone(): Boolean = defaults.boolForKey(keyAccessoryMigrationDone)

    fun setAccessoryMigrationDone(done: Boolean) {
        defaults.setBool(done, forKey = keyAccessoryMigrationDone)
    }

    fun isAutoScanEnabled(): Boolean = defaults.objectForKey(keyAutoScanEnabled)?.let {
        defaults.boolForKey(keyAutoScanEnabled)
    } ?: true

    fun setAutoScanEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyAutoScanEnabled)
    }

    fun isHapticsEnabled(): Boolean = defaults.objectForKey(keyHapticsEnabled)?.let {
        defaults.boolForKey(keyHapticsEnabled)
    } ?: true

    fun setHapticsEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyHapticsEnabled)
    }

    fun donationHintLastShownDaysAgo(initialize: Boolean = false): Long {
        val hasValue = defaults.objectForKey(keyDonationHintLastShown) != null
        val nowEpochSeconds = floor(NSDate().timeIntervalSince1970()).toLong()

        val lastShownEpochSeconds = if (!hasValue && initialize) {
            // Initialize to show the prompt on a later reopen, not right after setup.
            val initialValue = nowEpochSeconds - (29 * 24 * 60 * 60)
            defaults.setDouble(initialValue.toDouble(), forKey = keyDonationHintLastShown)
            initialValue
        } else {
            defaults.doubleForKey(keyDonationHintLastShown).toLong()
        }

        return (nowEpochSeconds - lastShownEpochSeconds) / (24 * 60 * 60)
    }

    fun setDonationHintShownNow() {
        val nowEpochSeconds = floor(NSDate().timeIntervalSince1970())
        defaults.setDouble(nowEpochSeconds, forKey = keyDonationHintLastShown)
    }

    fun donationHintShownTimes(): Int {
        return defaults.integerForKey(keyDonationHintShownTimes).toInt()
    }

    fun increaseDonationHintShownTimes() {
        defaults.setInteger(
            (donationHintShownTimes() + 1).toLong(),
            forKey = keyDonationHintShownTimes
        )
    }

    fun setForceDonationDialogOnNextAppStart(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyForceDonationDialogOnNextStart)
    }

    fun consumeForceDonationDialogOnNextAppStart(): Boolean {
        val shouldForce = defaults.boolForKey(keyForceDonationDialogOnNextStart)
        if (shouldForce) {
            defaults.removeObjectForKey(keyForceDonationDialogOnNextStart)
        }
        return shouldForce
    }

    fun getLogLevel(): String = defaults.stringForKey(logLevel) ?: LogLevel.Info.name
    fun setLogLevel(level: String) {
        defaults.setObject(level, forKey = logLevel)
    }
}

