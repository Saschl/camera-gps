package com.saschl.cameragps.service.location

import android.content.Context
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource

/** The foss flavor has no Play Services, so there is no provider to choose. */
const val LOCATION_PROVIDER_SELECTABLE = false

/** foss [LocationSource]: platform LocationManager only — no Play Services. */
fun createLocationSource(context: Context): LocationSource =
    PlatformLocationSource(context.applicationContext)
