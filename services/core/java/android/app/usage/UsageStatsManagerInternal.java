/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager.ProcessState;
import android.app.usage.UsageStatsManager.StandbyBuckets;
import android.content.ComponentName;
import android.content.LocusId;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.List;
import java.util.Set;

/**
 * UsageStatsManager local system service interface.
 *
 * {@hide} Only for use within the system server.
 */
public abstract class UsageStatsManagerInternal {

    /**
     * Reports an event to the UsageStatsManager. <br/>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then this event will be added to a queue and processed once the device is unlocked.</em>
     *
     * @param component The component for which this event occurred.
     * @param userId The user id to which the component belongs to.
     * @param eventType The event that occurred. Valid values can be found at
     *                  {@link UsageEvents}
     * @param instanceId For activity, hashCode of ActivityRecord's appToken.
     *                   For non-activity, it is not used.
     * @param taskRoot For activity, the name of the package at the root of the task
     *                 For non-activity, it is not used.
     */
    public abstract void reportEvent(ComponentName component, @UserIdInt int userId, int eventType,
            int instanceId, ComponentName taskRoot);

    /**
     * Reports an event to the UsageStatsManager. <br/>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then this event will be added to a queue and processed once the device is unlocked.</em>
     *
     * @param packageName The package for which this event occurred.
     * @param userId The user id to which the component belongs to.
     * @param eventType The event that occurred. Valid values can be found at
     * {@link UsageEvents}
     */
    public abstract void reportEvent(String packageName, @UserIdInt int userId, int eventType);

    /**
     * Reports a configuration change to the UsageStatsManager. <br/>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then this event will be added to a queue and processed once the device is unlocked.</em>
     *
     * @param config The new device configuration.
     */
    public abstract void reportConfigurationChange(Configuration config, @UserIdInt int userId);

    /**
     * Reports that an application has posted an interruptive notification. <br/>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then this event will be added to a queue and processed once the device is unlocked.</em>
     *
     * @param packageName The package name of the app that posted the notification
     * @param channelId The ID of the NotificationChannel to which the notification was posted
     * @param userId The user in which the notification was posted
     */
    public abstract void reportInterruptiveNotification(String packageName, String channelId,
            @UserIdInt int userId);

    /**
     * Reports that an action equivalent to a ShortcutInfo is taken by the user. <br/>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then this event will be added to a queue and processed once the device is unlocked.</em>
     *
     * @param packageName The package name of the shortcut publisher
     * @param shortcutId The ID of the shortcut in question
     * @param userId The user in which the content provider was accessed.
     *
     * @see android.content.pm.ShortcutManager#reportShortcutUsed(String)
     */
    public abstract void reportShortcutUsage(String packageName, String shortcutId,
            @UserIdInt int userId);

    /**
     * Reports that a content provider has been accessed by a foreground app.
     * @param name The authority of the content provider
     * @param pkgName The package name of the content provider
     * @param userId The user in which the content provider was accessed.
     */
    public abstract void reportContentProviderUsage(String name, String pkgName,
            @UserIdInt int userId);


    /**
     * Reports locusId update for a given activity.
     *
     * @param activity The component name of the app.
     * @param userId The user id of who uses the app.
     * @param locusId The locusId a unique, stable id that identifies this activity.
     * @param appToken ActivityRecord's appToken.
     * {@link UsageEvents}
     * @hide
     */
    public abstract void reportLocusUpdate(@NonNull ComponentName activity, @UserIdInt int userId,
            @Nullable LocusId locusId, @NonNull IBinder appToken);

    /**
     * Report a user interaction event to UsageStatsManager
     *
     * @param pkgName The package for which this user interaction event occurred.
     * @param userId The user id to which component belongs to.
     * @param extras The extra details about this user interaction event.
     * {@link UsageEvents.Event#USER_INTERACTION}
     * {@link UsageStatsManager#reportUserInteraction(String, int, PersistableBundle)}
     */
    public abstract void reportUserInteractionEvent(@NonNull String pkgName, @UserIdInt int userId,
            @NonNull PersistableBundle extras);

    /**
     * Prepares the UsageStatsService for shutdown.
     */
    public abstract void prepareShutdown();

    /**
     * When the device power button is long pressed for 3.5 seconds, prepareForPossibleShutdown()
     * is called.
     */
    public abstract void prepareForPossibleShutdown();

    /**
     * Returns true if the app has not been used for a certain amount of time. How much time?
     * Could be hours, could be days, who knows?
     *
     * @param packageName
     * @param uidForAppId The uid of the app, which will be used for its app id
     * @param userId
     * @return
     */
    public abstract boolean isAppIdle(String packageName, int uidForAppId, @UserIdInt int userId);

    /**
     * Returns the app standby bucket that the app is currently in.  This accessor does
     * <em>not</em> obfuscate instant apps.
     *
     * @param packageName
     * @param userId
     * @param nowElapsed The current time, in the elapsedRealtime time base
     * @return the AppStandby bucket code the app currently resides in.  If the app is
     *     unknown in the given user, STANDBY_BUCKET_NEVER is returned.
     */
    @StandbyBuckets public abstract int getAppStandbyBucket(String packageName,
            @UserIdInt int userId, long nowElapsed);

    /**
     * Returns all of the uids for a given user where all packages associating with that uid
     * are in the app idle state -- there are no associated apps that are not idle.  This means
     * all of the returned uids can be safely considered app idle.
     */
    public abstract int[] getIdleUidsForUser(@UserIdInt int userId);

    /**  Backup/Restore API */
    public abstract byte[] getBackupPayload(@UserIdInt int userId, String key);

    /**
     * ?
     * @param userId
     * @param key
     * @param payload
     */
    public abstract void applyRestoredPayload(@UserIdInt int userId, String key, byte[] payload);

    /**
     * Called by DevicePolicyManagerService to inform that a new admin has been added.
     *
     * @param packageName the package in which the admin component is part of.
     * @param userId the userId in which the admin has been added.
     */
    public abstract void onActiveAdminAdded(String packageName, int userId);

    /**
     * Called by DevicePolicyManagerService to inform about the active admins in an user.
     *
     * @param adminApps the set of active admins in {@param userId} or null if there are none.
     * @param userId the userId to which the admin apps belong.
     */
    public abstract void setActiveAdminApps(Set<String> adminApps, int userId);

    /**
     * Called by DevicePolicyManagerService to inform about the protected packages for a user.
     * User control will be disabled for protected packages.
     *
     * @param packageNames the set of protected packages for {@code userId}.
     * @param userId the userId to which the protected packages belong.
     */
    public abstract void setAdminProtectedPackages(@Nullable Set<String> packageNames,
            @UserIdInt int userId);

    /**
     * Called by DevicePolicyManagerService during boot to inform that admin data is loaded and
     * pushed to UsageStatsService.
     */
    public abstract void onAdminDataAvailable();

    /**
     * Return usage stats.
     *
     * @param obfuscateInstantApps whether instant app package names need to be obfuscated in the
     *     result.
     */
    public abstract List<UsageStats> queryUsageStatsForUser(@UserIdInt int userId, int interval,
            long beginTime, long endTime, boolean obfuscateInstantApps);

    /**
     * Returns the events for the user in the given time period.
     *
     * @param flags defines the visibility of certain usage events - see flags defined in
     * {@link UsageEvents}.
     */
    public abstract UsageEvents queryEventsForUser(@UserIdInt int userId, long beginTime,
            long endTime, int flags);

    /**
     * Used to persist the last time a job was run for this app, in order to make decisions later
     * whether a job should be deferred until later. The time passed in should be in elapsed
     * realtime since boot.
     * @param packageName the app that executed a job.
     * @param userId the user associated with the job.
     * @param elapsedRealtime the time when the job was executed, in elapsed realtime millis since
     *                        boot.
     */
    public abstract void setLastJobRunTime(String packageName, @UserIdInt int userId,
            long elapsedRealtime);

    /** Returns the estimated time that the app will be launched, in milliseconds since epoch. */
    @CurrentTimeMillisLong
    public abstract long getEstimatedPackageLaunchTime(String packageName, @UserIdInt int userId);

    /**
     * Returns the time in millis since a job was executed for this app, in elapsed realtime
     * timebase. This value can be larger than the current elapsed realtime if the job was executed
     * before the device was rebooted. The default value is {@link Long#MAX_VALUE}.
     * @param packageName the app you're asking about.
     * @param userId the user associated with the job.
     * @return the time in millis since a job was last executed for the app, provided it was
     * indicated here before by a call to {@link #setLastJobRunTime(String, int, long)}.
     */
    public abstract long getTimeSinceLastJobRun(String packageName, @UserIdInt int userId);

    /**
     * Report a few data points about an app's job state at the current time.
     *
     * @param packageName the app whose job state is being described
     * @param userId which user the app is associated with
     * @param numDeferredJobs the number of pending jobs that were deferred
     *   due to bucketing policy
     * @param timeSinceLastJobRun number of milliseconds since the last time one of
     *   this app's jobs was executed
     */
    public abstract void reportAppJobState(String packageName, @UserIdInt int userId,
            int numDeferredJobs, long timeSinceLastJobRun);

    /**
     * Report a sync that was scheduled.
     *
     * @param packageName name of the package that owns the sync adapter.
     * @param userId which user the app is associated with
     * @param exempted is sync app standby exempted
     */
    public abstract void reportSyncScheduled(String packageName, @UserIdInt int userId,
                                             boolean exempted);

    /**
     * Report a sync that was scheduled by a foreground app is about to be executed.
     *
     * @param packageName name of the package that owns the sync adapter.
     * @param userId which user the app is associated with
     */
    public abstract void reportExemptedSyncStart(String packageName, @UserIdInt int userId);

    /**
     * Returns an object describing the app usage limit for the given package which was set via
     * {@link UsageStatsManager#registerAppUsageLimitObserver}.
     * If there are multiple limits that apply to the package, the one with the smallest
     * time remaining will be returned.
     *
     * @param packageName name of the package whose app usage limit will be returned
     * @param user the user associated with the limit
     * @return an {@link AppUsageLimitData} object describing the app time limit containing
     * the given package, with the smallest time remaining.
     */
    public abstract AppUsageLimitData getAppUsageLimit(String packageName, UserHandle user);

    /** A class which is used to share the usage limit data for an app or a group of apps. */
    public static class AppUsageLimitData {
        private final long mTotalUsageLimit;
        private final long mUsageRemaining;

        public AppUsageLimitData(long totalUsageLimit, long usageRemaining) {
            this.mTotalUsageLimit = totalUsageLimit;
            this.mUsageRemaining = usageRemaining;
        }

        public long getTotalUsageLimit() {
            return mTotalUsageLimit;
        }
        public long getUsageRemaining() {
            return mUsageRemaining;
        }
    }

    /**
     * Called by {@link com.android.server.usage.UsageStatsIdleService} when the device is idle to
     * prune usage stats data for uninstalled packages.
     *
     * @param userId the user associated with the job
     * @return {@code true} if the pruning was successful, {@code false} otherwise
     */
    public abstract boolean pruneUninstalledPackagesData(@UserIdInt int userId);

    /**
     * Called by {@link com.android.server.usage.UsageStatsIdleService} between 24 to 48 hours of
     * when the user is first unlocked to update the usage stats package mappings data that might
     * be stale or have existed from a restore and belongs to packages that are not installed for
     * this user anymore.
     *
     * @param userId The user to update
     * @return {@code true} if the updating was successful, {@code false} otherwise
     */
    public abstract boolean updatePackageMappingsData(@UserIdInt int userId);

    /**
     * Listener interface for usage events.
     */
    public interface UsageEventListener {
        /** Callback to inform listeners of a new usage event. */
        void onUsageEvent(@UserIdInt int userId, @NonNull UsageEvents.Event event);
    }

    /** Register a listener that will be notified of every new usage event. */
    public abstract void registerListener(@NonNull UsageEventListener listener);

    /** Unregister a listener from being notified of every new usage event. */
    public abstract void unregisterListener(@NonNull UsageEventListener listener);

    /**
     * Listener interface for estimated launch time changes.
     */
    public interface EstimatedLaunchTimeChangedListener {
        /** Callback to inform listeners when estimated launch times change. */
        void onEstimatedLaunchTimeChanged(@UserIdInt int userId, @NonNull String packageName,
                @CurrentTimeMillisLong long newEstimatedLaunchTime);
    }

    /** Register a listener that will be notified of every estimated launch time change. */
    public abstract void registerLaunchTimeChangedListener(
            @NonNull EstimatedLaunchTimeChangedListener listener);

    /** Unregister a listener from being notified of every estimated launch time change. */
    public abstract void unregisterLaunchTimeChangedListener(
            @NonNull EstimatedLaunchTimeChangedListener listener);

    /**
     * Reports a broadcast dispatched event to the UsageStatsManager.
     *
     * @param sourceUid uid of the package that sent the broadcast.
     * @param targetPackage name of the package that the broadcast is targeted to.
     * @param targetUser user that {@code targetPackage} belongs to.
     * @param idForResponseEvent ID to be used for recording any response events corresponding
     *                           to this broadcast.
     * @param timestampMs time (in millis) when the broadcast was dispatched, in
     *                    {@link SystemClock#elapsedRealtime()} timebase.
     * @param targetUidProcState process state of the uid that the broadcast is targeted to.
     */
    public abstract void reportBroadcastDispatched(int sourceUid, @NonNull String targetPackage,
            @NonNull UserHandle targetUser, long idForResponseEvent,
            @ElapsedRealtimeLong long timestampMs, @ProcessState int targetUidProcState);

    /**
     * Reports a notification posted event to the UsageStatsManager.
     *
     * @param packageName name of the package which posted the notification.
     * @param user user that {@code packageName} belongs to.
     * @param timestampMs time (in millis) when the notification was posted, in
     *                    {@link SystemClock#elapsedRealtime()} timebase.
     */
    public abstract void reportNotificationPosted(@NonNull String packageName,
            @NonNull UserHandle user, @ElapsedRealtimeLong long timestampMs);

    /**
     * Reports a notification updated event to the UsageStatsManager.
     *
     * @param packageName name of the package which updated the notification.
     * @param user user that {@code packageName} belongs to.
     * @param timestampMs time (in millis) when the notification was updated, in
     *                    {@link SystemClock#elapsedRealtime()} timebase.
     */
    public abstract void reportNotificationUpdated(@NonNull String packageName,
            @NonNull UserHandle user, @ElapsedRealtimeLong long timestampMs);

    /**
     * Reports a notification removed event to the UsageStatsManager.
     *
     * @param packageName name of the package which removed the notification.
     * @param user user that {@code packageName} belongs to.
     * @param timestampMs time (in millis) when the notification was removed, in
     *                    {@link SystemClock#elapsedRealtime()} timebase.
     */
    public abstract void reportNotificationRemoved(@NonNull String packageName,
            @NonNull UserHandle user, @ElapsedRealtimeLong long timestampMs);
}
