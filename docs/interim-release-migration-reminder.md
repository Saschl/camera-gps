# Interim release: arming the migration reminder

**Ship this release BEFORE the AccessorySetupKit release.** Once the
AccessorySetupKit build reaches a user, nothing can be scheduled on their device
any more, because after an app update iOS runs no code at all until someone
launches the app. A local notification armed by the release they are running
*now* is the only thing that can reach them afterwards; pending local
notifications survive an app update as long as the bundle identifier is unchanged.

## What the interim build is

The current pre-AccessorySetupKit code, plus exactly these three things:

1. `sharednew/src/iosMain/kotlin/com/sasch/cameragps/sharednew/IosMigrationReminder.kt`
   — self-contained, no AccessorySetupKit dependency.
2. The four `ios_update_reminder_*` / `ios_migration_reminder_*` strings in
   `sharednew/src/iosMain/composeResources/values/strings.xml` and `values-de/`.
3. The call sites below.

It must NOT contain the Info.plist AccessorySetupKit keys, the deployment-target
bump, or anything else from the AccessorySetupKit change.

If the interim branch is cut from a commit that still pins
`logging = "2.1.2"`, it needs the `2.1.1` pin from `gradle/libs.versions.toml`
as well, or the iOS targets will not compile against Kotlin 2.3.21.

## Call sites to add

### `IosBluetoothController.ensureInitialized()`

Every run pushes the reminder further out, so it only fires once this version
stops running — which is what installing the update causes.

Permission is asked for here rather than only on a camera connection: a
connection usually happens while the app is backgrounded, where the system alert
cannot be shown, so a request made there would only ever be parked. Launch is
itself inactive rather than active, so this normally parks too and is run by the
foreground pass a moment later.

```kotlin
IosMigrationReminder.requestAuthorizationIfForeground()
controllerScope.launch { IosMigrationReminder.armUpdateSwitch() }
```

### `CameraGpsIosApp`, on every return to the foreground

Arming only at launch is not enough: an app that is opened once and then left
resident for days would let its reminder go stale and fire at a user who never
updated.

```kotlin
LaunchedEffect(isAppInForeground) {
    if (!isAppInForeground) return@LaunchedEffect
    IosMigrationReminder.flushDeferredAuthorizationRequest()
    IosMigrationReminder.armUpdateSwitch()
}
```

### `IosBluetoothController.handlePeripheralConnected(identifier)`

Re-arms the switch, and gives the permission request a second chance in case the
one at launch was still parked. After the first prompt that call only reads back
the existing status; iOS never asks twice.

```kotlin
IosMigrationReminder.requestAuthorizationIfForeground()
controllerScope.launch { IosMigrationReminder.armUpdateSwitch() }
```

This call also runs while the app is backgrounded, which is what keeps the
switch pushed forward for an actively-used install. Scheduling a notification
from the background is fine; only the permission alert needs the foreground.

## Timing

`addNotificationRequest` replaces any pending request with the same identifier,
so each rung's fire date is always **last arm plus its delay**. The ladder is
3, 10 and 30 days.

A ladder rather than a single reminder, because once the update installs this
code never runs again and cannot re-arm. One reminder would give the user
exactly one chance; if they ignored it they would stay silently broken for good.
The rungs keep firing until they open the app, which cancels all of them.

The delay is measured from the last arm, NOT from the update, because the old
version stops running the instant the update installs and cannot re-arm. So the
wait after an update depends on when the user last ran this release:

| Last ran this release | Reminder arrives |
|---|---|
| Right up to the update | ~3 days after the update |
| 2 days before the update | ~1 day after the update |
| Longer ago than the delay | already fired, before the update |

That worst case is also how long an updated user stays silently broken, which is
why the first rung is short. The price is that someone who simply stops opening
this release gets up to three reminders over a month; the copy tells them to
ignore it if everything already works.

## Testing it

The delays make this untestable by waiting, and every launch and foreground pass
re-arms the ladder, so simply running the app hides the behaviour.

Use the hidden debug card in Settings: tap the screen title five times, then
**Arm update reminder (30s / 2m / 5m)**. Then **force-quit the app**. That is the
part that matters — force-quitting is what stops the app re-arming with the real
delays, and it is the same condition an installed update creates. Wait, and the
rungs arrive 30 seconds, 2 minutes and 5 minutes later.

To rehearse the real path end to end, arm the short ladder on this build, then
install the AccessorySetupKit build over it **without launching it**, and wait.
Opening the new build instead should cancel every rung once migration completes.

## What happens on the AccessorySetupKit release

Already implemented there, nothing to do:

- Migration completes, or there was nothing to migrate → `IosMigrationReminder.cancel()`
  clears whatever this release armed.
- Cameras still awaiting confirmation → the switch is replaced by the shorter
  `armMigrationPending()` reminder, for someone who saw the sheet and dismissed it.

## Limits worth knowing before shipping

- Only reaches users who take BOTH updates and who grant notification permission.
- A user who declines the permission prompt gets nothing, and iOS will not ask again.
- Pending notifications are normally preserved across an update, but there are
  scattered reports of auto-updates dropping them. Treat this as best effort, not
  a guarantee.
- Consider a phased App Store rollout for the AccessorySetupKit release so any
  migration problem is contained to a slice of users.
