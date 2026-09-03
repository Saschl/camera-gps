package com.sasch.cameragps.sharednew

import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.ios_migration_reminder_body
import cameragps.sharednew.generated.resources.ios_migration_reminder_title
import cameragps.sharednew.generated.resources.ios_update_reminder_body
import cameragps.sharednew.generated.resources.ios_update_reminder_title
import com.diamondedge.logging.logging
import org.jetbrains.compose.resources.getString
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState.UIApplicationStateActive
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The local notification that gets a user back into the app when their cameras
 * still have to be confirmed through the AccessorySetupKit setup sheet.
 *
 * Why this exists: after an app update iOS runs nothing until someone launches
 * the app, and the setup sheet needs the foreground, so a user who updates and
 * never opens Alpha GPS would simply find geotagging silently dead. A pending
 * local notification is the only thing that can reach them, because those
 * survive an app update as long as the bundle identifier does not change.
 *
 * Two arming modes, deliberately different:
 *
 * - [armUpdateSwitch] is a dead man's switch for the release BEFORE
 *   AccessorySetupKit ships. Every run of that older version pushes the reminder
 *   further out, so it only ever fires once the old code stops running, which is
 *   exactly what installing the update causes. That release must call it on
 *   every launch, on every return to the foreground, and whenever a camera
 *   connects. Missing the foreground case would let an app that is launched
 *   once and then left resident go stale and nudge a user who never updated.
 * - [armMigrationPending] is for this release, and covers the smaller case of
 *   someone who opened the app, saw the sheet and dismissed it.
 *
 * Both use the same request identifier, so arming one replaces the other and
 * [cancel] clears whichever is outstanding.
 *
 * Main-thread confined like the rest of the iOS shell.
 */
object IosMigrationReminder {

    private const val REQUEST_ID_PREFIX = "com.saschl.cameragps.migrationReminder"

    /** The shorter reminder for someone who dismissed the setup sheet. */
    private const val PENDING_REQUEST_ID = "$REQUEST_ID_PREFIX.pending"

    /**
     * Upper bound on ladder rungs. Only used when clearing, so that a shorter
     * ladder can never leave a stale rung armed from a longer one.
     */
    private const val MAX_LADDER_RUNGS = 8

    private fun ladderId(index: Int) = "$REQUEST_ID_PREFIX.$index"

    private val ladderIds: List<String> = List(MAX_LADDER_RUNGS) { ladderId(it) }

    private val allIds: List<String> = ladderIds + PENDING_REQUEST_ID

    /**
     * How long after the last run of the pre-AccessorySetupKit release to nudge.
     *
     * This is measured from the last arm, NOT from the update, because the old
     * version stops running the moment the update installs and cannot re-arm.
     * So it also bounds how long an updated user stays silently broken: someone
     * who used the app right up to the update waits the full delay.
     *
     * Three days keeps that worst case short. The later rungs exist because once
     * the update installs nothing can re-arm: without them a user who ignores
     * the first reminder is never told again.
     */
    private val UPDATE_SWITCH_DELAYS_SECONDS =
        listOf(3.0, 10.0, 30.0).map { it * 24 * 60 * 60 }

    /**
     * Short rungs for the hidden debug button. Waiting three real days is not a
     * test, and every launch re-arms the ladder, so the only way to watch this
     * work is to arm short rungs and then stop running the app.
     */
    val TEST_DELAYS_SECONDS = listOf(30.0, 120.0, 300.0)

    /** Shorter: this user has already seen the sheet and dismissed it. */
    private const val MIGRATION_PENDING_DELAY_SECONDS = 6.0 * 60 * 60

    private val log = logging()

    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    /**
     * True when a permission request was wanted while the app was in the
     * background. The system alert cannot be shown then, and the request is
     * one-shot, so it is deferred rather than burned.
     */
    private var authorizationRequestDeferred = false

    /**
     * Ask for notification permission, but only while the app is actually
     * foreground-active. Call this from a moment that justifies it, such as a
     * camera connecting. iOS shows the alert at most once per install, so a
     * request made with no UI on screen would waste it for good.
     */
    fun requestAuthorizationIfForeground() {
        if (!isForegroundActive()) {
            authorizationRequestDeferred = true
            return
        }
        authorizationRequestDeferred = false
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
        ) { granted, error ->
            if (error != null) {
                log.w { "Notification authorization failed: ${error.localizedDescription}" }
            } else {
                log.i { "Notification authorization granted=$granted" }
            }
        }
    }

    /** Run any request that was deferred because the app was in the background. */
    fun flushDeferredAuthorizationRequest() {
        if (authorizationRequestDeferred) requestAuthorizationIfForeground()
    }

    /**
     * Dead man's switch for the pre-AccessorySetupKit release. Call on every
     * launch, foreground and camera connect: each call re-arms the whole ladder,
     * so none of it fires while this version keeps running.
     *
     * A ladder rather than one reminder, because once the update installs this
     * code never runs again and cannot re-arm. A single reminder would give the
     * user exactly one chance and then leave them broken in silence; the rungs
     * fire in sequence instead until they open the app.
     */
    suspend fun armUpdateSwitch(delaysSeconds: List<Double> = UPDATE_SWITCH_DELAYS_SECONDS) {
        val title = getString(Res.string.ios_update_reminder_title)
        val body = getString(Res.string.ios_update_reminder_body)
        // Clear first so a shorter ladder cannot leave a longer one's rungs armed.
        center.removePendingNotificationRequestsWithIdentifiers(ladderIds)
        delaysSeconds.take(MAX_LADDER_RUNGS).forEachIndexed { index, delay ->
            schedule(ladderId(index), title, body, delay)
        }
    }

    /**
     * Reminder for someone who dismissed the setup sheet in this release. It
     * supersedes the ladder: this user has seen the sheet, so the blunter
     * "open the app" nudges are no longer the right message.
     */
    suspend fun armMigrationPending() {
        center.removePendingNotificationRequestsWithIdentifiers(ladderIds)
        schedule(
            identifier = PENDING_REQUEST_ID,
            title = getString(Res.string.ios_migration_reminder_title),
            body = getString(Res.string.ios_migration_reminder_body),
            delaySeconds = MIGRATION_PENDING_DELAY_SECONDS,
        )
    }

    /** Migration is done; there is nothing left to nudge about. */
    fun cancel() {
        center.removePendingNotificationRequestsWithIdentifiers(allIds)
        center.removeDeliveredNotificationsWithIdentifiers(allIds)
    }

    private fun schedule(
        identifier: String,
        title: String,
        body: String,
        delaySeconds: Double,
    ) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = delaySeconds,
            repeats = false,
        )
        // Adding with the same identifier replaces the outstanding request, which
        // is what pushes each rung forward.
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = identifier,
            content = content,
            trigger = trigger,
        )
        center.addNotificationRequest(request) { error ->
            if (error != null) {
                log.w { "Could not schedule the reminder: ${error.localizedDescription}" }
            }
        }
    }

    private fun isForegroundActive(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationStateActive
}
