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

import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;

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
 * <b>NOTE:</b> This API requires the permission android.permission.PACKAGE_USAGE_STATS, which
 * is a system-level permission and will not be granted to third-party apps. However, declaring
 * the permission implies intention to use the API and the user of the device can grant permission
 * through the Settings application.
 */
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
     * @return A list of {@link UsageStats} or null if none are available.
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
            // fallthrough and return null.
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
     * @return A list of {@link ConfigurationStats} or null if none are available.
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
     * <p />
     * <b>NOTE:</b> The last few minutes of the event log will be truncated to prevent abuse
     * by applications.
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
            // fallthrough and return null
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
     * @return A {@link java.util.Map} keyed by package name, or null if no stats are
     *         available.
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
            return mService.isAppInactive(packageName, UserHandle.myUserId());
        } catch (RemoteException e) {
            // fall through and return default
        }
        return false;
    }

    /**
     * @hide
     */
    public void setAppInactive(String packageName, boolean inactive) {
        try {
            mService.setAppInactive(packageName, inactive, UserHandle.myUserId());
        } catch (RemoteException e) {
            // fall through
        }
    }

    /**
     * {@hide}
     * Temporarily whitelist the specified app for a short duration. This is to allow an app
     * receiving a high priority message to be able to access the network and acquire wakelocks
     * even if the device is in power-save mode or the app is currently considered inactive.
     * The caller must hold the CHANGE_DEVICE_IDLE_TEMP_WHITELIST permission.
     * @param packageName The package name of the app to whitelist.
     * @param duration Duration to whitelist the app for, in milliseconds. It is recommended that
     * this be limited to 10s of seconds. Requested duration will be clamped to a few minutes.
     * @param user The user for whom the package should be whitelisted. Passing in a user that is
     * not the same as the caller's process will require the INTERACT_ACROSS_USERS permission.
     * @see #isAppInactive(String)
     */
    @SystemApi
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
}
