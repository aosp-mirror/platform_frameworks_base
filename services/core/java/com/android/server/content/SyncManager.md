# Sync Manager notes

## App-standby and Sync Manager

Android 9 Pie introduced
["App Standby Buckets"](https://developer.android.com/topic/performance/appstandby), which throttles various things
including
[JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler)
and [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager),
[among other things](https://developer.android.com/topic/performance/power/power-details),
for background applications.

Because SyncManager executes sync operations as JobScheduler jobs, sync operations are subject
to the same throttling.

However, unlike JobScheduler jobs, any apps (with the proper permission) can schedule a sync
operation in any other apps using
[ContentResolver.requestSync()](https://developer.android.com/reference/android/content/ContentResolver#requestSync(android.content.SyncRequest)),
whch means it's possible for a foreground app to request a sync in another app that is either in the
background or is not even running.
For example, when the user hits the refresh button on the Contacts app, it'll
request sync to all the contacts sync adapters, which are implemented in other packages (and they're
likely not in the foreground).

Because of this, calls to
[ContentResolver.requestSync()](https://developer.android.com/reference/android/content/ContentResolver#requestSync(android.content.SyncRequest))
made by foreground apps are special cased such that the resulting sync operations will be
exempted from app-standby throttling.

### Two Levels of Exemption
Specifically, there are two different levels of exemption, depending on the state of the caller:
1. `ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET`.
  This is shown as `STANDBY-EXEMPTED` in `dumpsys content`.
2. `ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP`, which is more powerful than 1.
  This is shown as `STANDBY-EXEMPTED(TOP)` in `dumpsys content`.

The exemption level is calculated in
[ContentService.getSyncExemptionAndCleanUpExtrasForCaller()](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/content/ContentService.java?q=%22int%20getSyncExemptionAndCleanUpExtrasForCaller%22&ss=android%2Fplatform%2Fsuperproject),
which was [implemented slightly differently](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/content/ContentService.java?q=%22int%20getSyncExemptionAndCleanUpExtrasForCaller%22&ss=android%2Fplatform%2Fsuperproject)
in Android 9, compared to Android 10 and later.

The logic is as follows:
- When the caller's procstate is `PROCESS_STATE_TOP` or above,
  meaning if the caller has a foreground activity,
  `SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP` will be set.

- Otherwise, when the caller's procstate is `PROCESS_STATE_IMPORTANT_FOREGROUND` or above,
  e.g. when the caller has a foreground service, a service bound by the system of a specific kind,
  `SYNC_EXEMPTION_PROMOTE_BUCKET` will be set.

- Additionally, on Android 10 and later, when the caller is
  "UID-active" (but the procstate is below `PROCESS_STATE_TOP`),
  `SYNC_EXEMPTION_PROMOTE_BUCKET` will be set.
  This is what happens when the app has just received a high-priority FCM, for example.
  Temp-allowlist is also used in various other situations.

### Behavior of Each Exemption

The exemptions are tracked in `SyncOperation.syncExemptionFlag`.

- Behavior of `SYNC_EXEMPTION_PROMOTE_BUCKET`
  - This will add `JobInfo.FLAG_EXEMPT_FROM_APP_STANDBY` to the sync job. This makes the job
    subject to "ACTIVE" app quota, so minimum deferral will be applied to it.

  - This also reports `AppStandbyController.reportExemptedSyncStart()`, so the package that owns
    the sync adapter is temporarily put in the "ACTIVE" bucket for the
    duration of `mExemptedSyncStartTimeoutMillis`, whose default is 10 minutes as of 2020-10-23.

    This will allow the app to access network, even if it has been in the `RARE` bucket
    (in which case, the system cuts its network access).

    Note if the device is dozing or in battery saver, promoting to the "ACTIVE" bucket will still
    _not_ give the app network access.

- Behavior of `SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP`
  - This gives all the perks given by `SYNC_EXEMPTION_PROMOTE_BUCKET`, plus puts the target app
    in the temp-allowlist (by calling `DeviceIdleInternal.addPowerSaveTempWhitelistApp()`)
    for the duration of `SyncManagerConstants.getKeyExemptionTempWhitelistDurationInSeconds()`,
    whose default is 10 minutes.

    Temp-allowlist will grant the app network access even if the device is in doze or in battery
    saver.

    (However, note that when the device is dozing, sync jobs will not run anyway.)

### How Retries Are Handled

- When a sync operation needs a retry, SyncManager creates a new operation (job) with a back-off
  (in `SyncManager.maybeRescheduleSync()`). In this case, the new sync operation will inherit
  `SyncOperation.syncExemptionFlag`, unless the number of retries (not counting the original sync
  job) is equal to or greater than `SyncManagerConstants.getMaxRetriesWithAppStandbyExemption()`,
  whose default is 5.

### Special-handling of Pre-installed Packages

- When a content provider is accessed, `AppStandbyController.reportContentProviderUsage()` is
  triggered, which elevates the standby bucket of the associated sync adapters' packages to `ACTIVE`
  for the duration of `mSyncAdapterTimeoutMillis`, whose default is 10 minutes, but _only for_
  pre-installed packages. This is to help pre-installed sync adapters, which often don't have UI,
  sync properly.

- Also, since Android 11, all the pre-installed apps with no activities will be kept in
  the `ACTIVE` bucket, which greatly relaxes app-standby throttling. But they're still subject
  to doze and battery saver.

### Summary

- When the device is dozing, no sync operations will be executed.

- Normally, sync operations are subject to App-Standby, which throttles jobs owned by background
  apps. Jobs owned by foreground apps are not affected.

- A sync operation requested by a foreground activity will be executed immediately even if the
  app owning the sync adapter is in RARE bucket, and the device is in battery saver.

- A sync operation requested by a foreground service (or a "bound foreground" service)
  will be executed immediately even if the app owning the sync adapter is in RARE bucket,
  *unless* the device is in battery saver.

  Since Android 9 and later, the same thing will happen if the requester is temp-allowlisted (e.g.
  when it has just received a "high-priority FCM").

- There are certain exemptions for pre-installed apps, but doze and battery saver will still
  block their sync adapters.