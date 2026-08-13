package com.saschl.cameragps.utils

import android.content.Context
import io.sentry.SentryLevel
import io.sentry.SentryLogLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberIntegration

/**
 * gplay crash reporting: Sentry. Only ever initialized after the user consented
 * (see SentryConsentDialog / CameraGpsApplication). The foss flavor ships a
 * no-op counterpart of this object.
 */
object CrashReporting {

    /** Gates the consent dialog and the Sentry settings entry. */
    const val AVAILABLE = true

    fun init(context: Context) {
        SentryAndroid.init(context) { options ->
            options.isSendDefaultPii = false
            val macRegex = Regex("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})")

            options.logs.isEnabled = true

            options.addIntegration(
                SentryTimberIntegration(
                    minEventLevel = SentryLevel.ERROR,
                    minBreadcrumbLevel = SentryLevel.INFO,
                    minLogsLevel = SentryLogLevel.INFO
                )
            )
            options.logs.beforeSend = SentryOptions.Logs.BeforeSendLogCallback { event ->
                // Modify the event here if needed
                event.body = event.body.replace(macRegex, "XX:XX:XX:XX:XX:XX")
                event
            }
        }
    }
}
