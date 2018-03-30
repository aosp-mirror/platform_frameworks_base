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

import android.annotation.UserIdInt;
import android.app.usage.UsageStatsManager.StandbyBuckets;
import android.content.ComponentName;
import android.content.res.Configuration;

import java.util.List;
import java.util.Set;

/**
 * UsageStatsManager local system service interface.
 *
 * {@hide} Only for use within the system server.
 */
public abstract class UsageStatsManagerInternal {

    /**
     * Reports an event to the UsageStatsManager.
     *
     * @param component The component for which this event occurred.
     * @param userId The user id to which the component belongs to.
     * @param eventType The event that occurred. Valid values can be found at
     * {@link UsageEvents}
     */
    public abstract void reportEvent(ComponentName component, @UserIdInt int userId, int eventType);

    /**
     * Reports an event to the UsageStatsManager.
     *
     * @param packageName The package for which this event occurred.
     * @param userId The user id to which the component belongs to.
     * @param eventType The event that occurred. Valid values can be found at
     * {@link UsageEvents}
     */
    public abstract void reportEvent(String packageName, @UserIdInt int userId, int eventType);

    /**
     * Reports a configuration change to the UsageStatsManager.
     *
     * @param config The new device configuration.
     */
    public abstract void reportConfigurationChange(Configuration config, @UserIdInt int userId);

    /**
     * Reports that an application has posted an interruptive notification.
     *
     * @param packageName The package name of the app that posted the notification
     * @param channelId The ID of the NotificationChannel to which the notification was posted
     * @param userId The user in which the notification was posted
     */
    public abstract void reportInterruptiveNotification(String packageName, String channelId,
            @UserIdInt int userId);

    /**
     * Reports that an action equivalent to a ShortcutInfo is taken by the user.
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
     * Prepares the UsageStatsService for shutdown.
     */
    public abstract void prepareShutdown();

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

    /**
     * @return True if currently app idle parole mode is on.  This means all idle apps are allow to
     * run for a short period of time.
     */
    public abstract boolean isAppIdleParoleOn();

    /**
     * Sets up a listener for changes to packages being accessed.
     * @param listener A listener within the system process.
     */
    public abstract void addAppIdleStateChangeListener(
            AppIdleStateChangeListener listener);

    /**
     * Removes a listener that was previously added for package usage state changes.
     * @param listener The listener within the system process to remove.
     */
    public abstract void removeAppIdleStateChangeListener(
            AppIdleStateChangeListener listener);

    public static abstract class AppIdleStateChangeListener {

        /** Callback to inform listeners that the idle state has changed to a new bucket. */
        public abstract void onAppIdleStateChanged(String packageName, @UserIdInt int userId,
                boolean idle, int bucket, int reason);

        /**
         * Callback to inform listeners that the parole state has changed. This means apps are
         * allowed to do work even if they're idle or in a low bucket.
         */
        public abstract void onParoleStateChanged(boolean isParoleOn);

        /**
         * Optional callback to inform the listener that the app has transitioned into
         * an active state due to user interaction.
         */
        public void onUserInteractionStarted(String packageName, @UserIdInt int userId) {
            // No-op by default
        }
    }

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
     * Report a sync that was scheduled by an active app is about to be executed.
     *
     * @param packageName name of the package that owns the sync adapter.
     * @param userId which user the app is associated with
     */
    public abstract void reportExemptedSyncStart(String packageName, @UserIdInt int userId);
}
