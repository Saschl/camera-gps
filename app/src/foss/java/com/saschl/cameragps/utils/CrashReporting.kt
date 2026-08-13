package com.saschl.cameragps.utils

import android.content.Context
import com.saschl.cameragps.utils.CrashReporting.AVAILABLE
import com.saschl.cameragps.utils.CrashReporting.init

/**
 * foss crash reporting: none. The F-Droid flavor ships without Sentry, so the
 * consent dialog and settings entry (gated on [AVAILABLE]) never appear and
 * [init] does nothing.
 */
object CrashReporting {

    const val AVAILABLE = false

    fun init(context: Context) {
        // no crash reporter in the foss flavor
    }
}
