package com.saschl.cameragps

import android.app.Application
import com.saschl.cameragps.service.FileTree
import com.saschl.cameragps.service.GlobalExceptionHandler
import com.saschl.cameragps.utils.CrashReporting
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

/**
 * Process entry point: one-time logging/crash-reporting setup and the
 * app-scoped service graph ([AppServices]). Application.onCreate precedes every
 * other component, so the init blocks previously duplicated across the
 * activity, services and receivers live only here.
 */
class CameraGpsApplication : Application() {

    val services: AppServices by lazy { AppServices(this) }

    override fun onCreate() {
        super.onCreate()

        FileTree.initialize(this)
        Timber.plant(FileTree(this, PreferencesManager.logLevel(this)))

        // Crash reporting only after consent — the consent dialog does the
        // first init. No-op in the foss flavor.
        if (CrashReporting.AVAILABLE &&
            PreferencesManager.sentryEnabled(this) &&
            PreferencesManager.isSentryConsentDialogDismissed(this)
        ) {
            CrashReporting.init(this)
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))
    }
}
