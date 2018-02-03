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

import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Provides access to device usage history and statistics. Usage data is aggregated into
 * time intervals: days, weeks, months, and years.
 * <p />
 * When requesting usage data since a particular time, the request might look something like this:
 * <pre>
 * PAST                   REQUEST_TIME                    TODAY                   FUTURE
 * ————————————————————————————||———————————————————————————¦-----------------------|
 *                        YEAR ||                           ¦                       |
 * ————————————————————————————||———————————————————————————¦-----------------------|
 *  MONTH            |         ||                MONTH      ¦                       |
 * ——————————————————|—————————||———————————————————————————¦-----------------------|
 *   |      WEEK     |     WEEK||    |     WEEK     |     WE¦EK     |      WEEK     |
 * ————————————————————————————||———————————————————|———————¦-----------------------|
 *                             ||           |DAY|DAY|DAY|DAY¦DAY|DAY|DAY|DAY|DAY|DAY|
 * ————————————————————————————||———————————————————————————¦-----------------------|
 * </pre>
 * A request for data in the middle of a time interval will include that interval.
 * <p/>
 * <b>NOTE:</b> This API requires the permission android.permission.PACKAGE_USAGE_STATS.
 * However, declaring the permission implies intention to use the API and the user of the device
 * still needs to grant permission through the Settings application.
 * See {@link android.provider.Settings#ACTION_USAGE_ACCESS_SETTINGS}
 */
@SystemService(Context.USAGE_STATS_SERVICE)
public final class UsageStatsManager {

    /**
     * An interval type that spans a day. See {@link #queryUsageStats(int, long, long)}.
     */
    public static final int INTERVAL_DAILY = 0;

    /**
     * An interval type that spans a week. See {@link #queryUsageStats(int, long, long)}.
     */
    public static final int INTERVAL_WEEKLY = 1;

    /**
     * An interval type that spans a month. See {@link #queryUsageStats(int, long, long)}.
     */
    public static final int INTERVAL_MONTHLY = 2;

    /**
     * An interval type that spans a year. See {@link #queryUsageStats(int, long, long)}.
     */
    public static final int INTERVAL_YEARLY = 3;

    /**
     * An interval type that will use the best fit interval for the given time range.
     * See {@link #queryUsageStats(int, long, long)}.
     */
    public static final int INTERVAL_BEST = 4;

    /**
     * The number of available intervals. Does not include {@link #INTERVAL_BEST}, since it
     * is a pseudo interval (it actually selects a real interval).
     * {@hide}
     */
    public static final int INTERVAL_COUNT = 4;


    /**
     * The app is whitelisted for some reason and the bucket cannot be changed.
     * {@hide}
     */
    @SystemApi
    public static final int STANDBY_BUCKET_EXEMPTED = 5;

    /**
     * The app was used very recently, currently in use or likely to be used very soon.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_ACTIVE = 10;

    /**
     * The app was used recently and/or likely to be used in the next few hours.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_WORKING_SET = 20;

    /**
     * The app was used in the last few days and/or likely to be used in the next few days.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_FREQUENT = 30;

    /**
     * The app has not be used for several days and/or is unlikely to be used for several days.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_RARE = 40;

    /**
     * The app has never been used.
     * {@hide}
     */
    @SystemApi
    public static final int STANDBY_BUCKET_NEVER = 50;

    /** {@hide} Reason for bucketing -- default initial state */
    public static final String REASON_DEFAULT = "default";

    /** {@hide} Reason for bucketing -- timeout */
    public static final String REASON_TIMEOUT = "timeout";

    /** {@hide} Reason for bucketing -- usage */
    public static final String REASON_USAGE = "usage";

    /** {@hide} Reason for bucketing -- forced by user / shell command */
    public static final String REASON_FORCED = "forced";

    /**
     * {@hide}
     * Reason for bucketing -- predicted. This is a prefix and the UID of the bucketeer will
     * be appended.
     */
    public static final String REASON_PREDICTED = "predicted";

    /** @hide */
    @IntDef(flag = false, prefix = { "STANDBY_BUCKET_" }, value = {
            STANDBY_BUCKET_EXEMPTED,
            STANDBY_BUCKET_ACTIVE,
            STANDBY_BUCKET_WORKING_SET,
            STANDBY_BUCKET_FREQUENT,
            STANDBY_BUCKET_RARE,
            STANDBY_BUCKET_NEVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StandbyBuckets {}

    private static final UsageEvents sEmptyResults = new UsageEvents();

    private final Context mContext;
    private final IUsageStatsManager mService;

    /**
     * {@hide}
     */
    public UsageStatsManager(Context context, IUsageStatsManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Gets application usage stats for the given time range, aggregated by the specified interval.
     * <p>The returned list will contain a {@link UsageStats} object for each package that
     * has data for an interval that is a subset of the time range given. To illustrate:</p>
     * <pre>
     * intervalType = INTERVAL_YEARLY
     * beginTime = 2013
     * endTime = 2015 (exclusive)
     *
     * Results:
     * 2013 - com.example.alpha
     * 2013 - com.example.beta
     * 2014 - com.example.alpha
     * 2014 - com.example.beta
     * 2014 - com.example.charlie
     * </pre>
     *
     * @param intervalType The time interval by which the stats are aggregated.
     * @param beginTime The inclusive beginning of the range of stats to include in the results.
     * @param endTime The exclusive end of the range of stats to include in the results.
     * @return A list of {@link UsageStats}
     *
     * @see #INTERVAL_DAILY
     * @see #INTERVAL_WEEKLY
     * @see #INTERVAL_MONTHLY
     * @see #INTERVAL_YEARLY
     * @see #INTERVAL_BEST
     */
    public List<UsageStats> queryUsageStats(int intervalType, long beginTime, long endTime) {
        try {
            @SuppressWarnings("unchecked")
            ParceledListSlice<UsageStats> slice = mService.queryUsageStats(intervalType, beginTime,
                    endTime, mContext.getOpPackageName());
            if (slice != null) {
                return slice.getList();
            }
        } catch (RemoteException e) {
            // fallthrough and return the empty list.
        }
        return Collections.emptyList();
    }

    /**
     * Gets the hardware configurations the device was in for the given time range, aggregated by
     * the specified interval. The results are ordered as in
     * {@link #queryUsageStats(int, long, long)}.
     *
     * @param intervalType The time interval by which the stats are aggregated.
     * @param beginTime The inclusive beginning of the range of stats to include in the results.
     * @param endTime The exclusive end of the range of stats to include in the results.
     * @return A list of {@link ConfigurationStats}
     */
    public List<ConfigurationStats> queryConfigurations(int intervalType, long beginTime,
            long endTime) {
        try {
            @SuppressWarnings("unchecked")
            ParceledListSlice<ConfigurationStats> slice = mService.queryConfigurationStats(
                    intervalType, beginTime, endTime, mContext.getOpPackageName());
            if (slice != null) {
                return slice.getList();
            }
        } catch (RemoteException e) {
            // fallthrough and return the empty list.
        }
        return Collections.emptyList();
    }

    /**
     * Query for events in the given time range. Events are only kept by the system for a few
     * days.
     *
     * @param beginTime The inclusive beginning of the range of events to include in the results.
     * @param endTime The exclusive end of the range of events to include in the results.
     * @return A {@link UsageEvents}.
     */
    public UsageEvents queryEvents(long beginTime, long endTime) {
        try {
            UsageEvents iter = mService.queryEvents(beginTime, endTime,
                    mContext.getOpPackageName());
            if (iter != null) {
                return iter;
            }
        } catch (RemoteException e) {
            // fallthrough and return empty result.
        }
        return sEmptyResults;
    }

    /**
     * A convenience method that queries for all stats in the given range (using the best interval
     * for that range), merges the resulting data, and keys it by package name.
     * See {@link #queryUsageStats(int, long, long)}.
     *
     * @param beginTime The inclusive beginning of the range of stats to include in the results.
     * @param endTime The exclusive end of the range of stats to include in the results.
     * @return A {@link java.util.Map} keyed by package name
     */
    public Map<String, UsageStats> queryAndAggregateUsageStats(long beginTime, long endTime) {
        List<UsageStats> stats = queryUsageStats(INTERVAL_BEST, beginTime, endTime);
        if (stats.isEmpty()) {
            return Collections.emptyMap();
        }

        ArrayMap<String, UsageStats> aggregatedStats = new ArrayMap<>();
        final int statCount = stats.size();
        for (int i = 0; i < statCount; i++) {
            UsageStats newStat = stats.get(i);
            UsageStats existingStat = aggregatedStats.get(newStat.getPackageName());
            if (existingStat == null) {
                aggregatedStats.put(newStat.mPackageName, newStat);
            } else {
                existingStat.add(newStat);
            }
        }
        return aggregatedStats;
    }

    /**
     * Returns whether the specified app is currently considered inactive. This will be true if the
     * app hasn't been used directly or indirectly for a period of time defined by the system. This
     * could be of the order of several hours or days.
     * @param packageName The package name of the app to query
     * @return whether the app is currently considered inactive
     */
    public boolean isAppInactive(String packageName) {
        try {
            return mService.isAppInactive(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            // fall through and return default
        }
        return false;
    }

    /**
     * {@hide}
     */
    public void setAppInactive(String packageName, boolean inactive) {
        try {
            mService.setAppInactive(packageName, inactive, mContext.getUserId());
        } catch (RemoteException e) {
            // fall through
        }
    }

    /**
     * Returns the current standby bucket of the calling app. The system determines the standby
     * state of the app based on app usage patterns. Standby buckets determine how much an app will
     * be restricted from running background tasks such as jobs, alarms and certain PendingIntent
     * callbacks.
     * <p>Restrictions increase progressively from {@link #STANDBY_BUCKET_ACTIVE} to
     * {@link #STANDBY_BUCKET_RARE}, with {@link #STANDBY_BUCKET_ACTIVE} being the least
     * restrictive. The battery level of the device might also affect the restrictions.
     *
     * @return the current standby bucket of the calling app. One of STANDBY_BUCKET_* constants.
     */
    public @StandbyBuckets int getAppStandbyBucket() {
        try {
            return mService.getAppStandbyBucket(mContext.getOpPackageName(),
                    mContext.getOpPackageName(),
                    mContext.getUserId());
        } catch (RemoteException e) {
        }
        return STANDBY_BUCKET_ACTIVE;
    }

    /**
     * {@hide}
     * Returns the current standby bucket of the specified app. The caller must hold the permission
     * android.permission.PACKAGE_USAGE_STATS.
     * @param packageName the package for which to fetch the current standby bucket.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
    public @StandbyBuckets int getAppStandbyBucket(String packageName) {
        try {
            return mService.getAppStandbyBucket(packageName, mContext.getOpPackageName(),
                    mContext.getUserId());
        } catch (RemoteException e) {
        }
        return STANDBY_BUCKET_ACTIVE;
    }

    /**
     * {@hide}
     * Changes an app's standby bucket to the provided value. The caller can only set the standby
     * bucket for a different app than itself.
     * @param packageName the package name of the app to set the bucket for. A SecurityException
     *                    will be thrown if the package name is that of the caller.
     * @param bucket the standby bucket to set it to, which should be one of STANDBY_BUCKET_*.
     *               Setting a standby bucket outside of the range of STANDBY_BUCKET_ACTIVE to
     *               STANDBY_BUCKET_NEVER will result in a SecurityException.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_APP_IDLE_STATE)
    public void setAppStandbyBucket(String packageName, @StandbyBuckets int bucket) {
        try {
            mService.setAppStandbyBucket(packageName, bucket, mContext.getUserId());
        } catch (RemoteException e) {
            // Nothing to do
        }
    }

    /**
     * {@hide}
     * Returns the current standby bucket of every app that has a bucket assigned to it.
     * The caller must hold the permission android.permission.PACKAGE_USAGE_STATS. The key of the
     * returned Map is the package name and the value is the bucket assigned to the package.
     * @see #getAppStandbyBucket()
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
    public Map<String, Integer> getAppStandbyBuckets() {
        try {
            return (Map<String, Integer>) mService.getAppStandbyBuckets(
                    mContext.getOpPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
        }
        return Collections.EMPTY_MAP;
    }

    /**
     * {@hide}
     * Changes the app standby bucket for multiple apps at once. The Map is keyed by the package
     * name and the value is one of STANDBY_BUCKET_*.
     * @param appBuckets a map of package name to bucket value.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_APP_IDLE_STATE)
    public void setAppStandbyBuckets(Map<String, Integer> appBuckets) {
        try {
            mService.setAppStandbyBuckets(appBuckets, mContext.getUserId());
        } catch (RemoteException e) {
        }
    }

    /**
     * {@hide}
     * Temporarily whitelist the specified app for a short duration. This is to allow an app
     * receiving a high priority message to be able to access the network and acquire wakelocks
     * even if the device is in power-save mode or the app is currently considered inactive.
     * @param packageName The package name of the app to whitelist.
     * @param duration Duration to whitelist the app for, in milliseconds. It is recommended that
     * this be limited to 10s of seconds. Requested duration will be clamped to a few minutes.
     * @param user The user for whom the package should be whitelisted. Passing in a user that is
     * not the same as the caller's process will require the INTERACT_ACROSS_USERS permission.
     * @see #isAppInactive(String)
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    public void whitelistAppTemporarily(String packageName, long duration, UserHandle user) {
        try {
            mService.whitelistAppTemporarily(packageName, duration, user.getIdentifier());
        } catch (RemoteException re) {
        }
    }

    /**
     * Inform usage stats that the carrier privileged apps access rules have changed.
     * @hide
     */
    public void onCarrierPrivilegedAppsChanged() {
        try {
            mService.onCarrierPrivilegedAppsChanged();
        } catch (RemoteException re) {
        }
    }

    /**
     * Reports a Chooser action to the UsageStatsManager.
     *
     * @param packageName The package name of the app that is selected.
     * @param userId The user id of who makes the selection.
     * @param contentType The type of the content, e.g., Image, Video, App.
     * @param annotations The annotations of the content, e.g., Game, Selfie.
     * @param action The action type of Intent that invokes ChooserActivity.
     * {@link UsageEvents}
     * @hide
     */
    public void reportChooserSelection(String packageName, int userId, String contentType,
                                       String[] annotations, String action) {
        try {
            mService.reportChooserSelection(packageName, userId, contentType, annotations, action);
        } catch (RemoteException re) {
        }
    }
}
