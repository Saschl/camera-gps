package com.saschl.cameragps.ui.review

import android.app.Activity
import android.content.Context

/** foss: no Play Store, so there is no in-app review flow to launch. */
fun launchInAppReviewIfDue(
    context: Context,
    activity: Activity?,
    onFlowActiveChange: (Boolean) -> Unit,
) {
    // intentionally empty
}
