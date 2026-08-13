package com.saschl.cameragps.ui.review

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.model.ReviewErrorCode
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

/**
 * Launches the Play in-app review flow when due (at most every 30 days, at
 * most 3 times overall). [onFlowActiveChange] mirrors whether the review UI is
 * showing so callers can suppress competing dialogs. The foss counterpart is a
 * no-op — there is no Play Store to review on.
 */
fun launchInAppReviewIfDue(
    context: Context,
    activity: Activity?,
    onFlowActiveChange: (Boolean) -> Unit,
) {
    val appContext = context.applicationContext
    if (PreferencesManager.reviewHintLastShownDaysAgo(appContext, true) < 30) return
    if (PreferencesManager.reviewHintShownTimes(appContext) >= 3) return

    val manager = ReviewManagerFactory.create(context)
    val request = manager.requestReviewFlow()
    PreferencesManager.setReviewHintShownNow(appContext)
    PreferencesManager.increaseReviewHintShownTimes(appContext)

    request.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            onFlowActiveChange(true)
            // We got the ReviewInfo object
            val reviewInfo = task.result
            val flow = manager.launchReviewFlow(activity!!, reviewInfo)
            flow.addOnCompleteListener { _ ->
                Timber.i("Review done!")
                onFlowActiveChange(false)
            }
        } else {
            @ReviewErrorCode val reviewErrorCode =
                (task.exception as ReviewException).errorCode
            Timber.e("Review flow failed with error code: $reviewErrorCode")
            PreferencesManager.resetReviewHintShown(appContext)
            PreferencesManager.decreaseReviewHintShownTimes(appContext)
        }
    }
}
