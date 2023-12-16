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

import android.Manifest;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.PowerWhitelistManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
 * <b>NOTE:</b> Most methods on this API require the permission
 * android.permission.PACKAGE_USAGE_STATS. However, declaring the permission implies intention to
 * use the API and the user of the device still needs to grant permission through the Settings
 * application.
 * See {@link android.provider.Settings#ACTION_USAGE_ACCESS_SETTINGS}.
 * Methods which only return the information for the calling package do not require this permission.
 * E.g. {@link #getAppStandbyBucket()} and {@link #queryEventsForSelf(long, long)}.
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
     * The app is exempted for some reason and the bucket cannot be changed.
     * {@hide}
     */
    @SystemApi
    public static final int STANDBY_BUCKET_EXEMPTED = 5;

    /**
     * The app was used very recently, currently in use or likely to be used very soon. Standby
     * bucket values that are &le; {@link #STANDBY_BUCKET_ACTIVE} will not be throttled by the
     * system while they are in this bucket. Buckets &gt; {@link #STANDBY_BUCKET_ACTIVE} will most
     * likely be restricted in some way. For instance, jobs and alarms may be deferred.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_ACTIVE = 10;

    /**
     * The app was used recently and/or likely to be used in the next few hours. Restrictions will
     * apply to these apps, such as deferral of jobs and alarms.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_WORKING_SET = 20;

    /**
     * The app was used in the last few days and/or likely to be used in the next few days.
     * Restrictions will apply to these apps, such as deferral of jobs and alarms. The delays may be
     * greater than for apps in higher buckets (lower bucket value). Bucket values &gt;
     * {@link #STANDBY_BUCKET_FREQUENT} may additionally have network access limited.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_FREQUENT = 30;

    /**
     * The app has not be used for several days and/or is unlikely to be used for several days.
     * Apps in this bucket will have more restrictions, including network restrictions, except
     * during certain short periods (at a minimum, once a day) when they are allowed to execute
     * jobs, access the network, etc.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_RARE = 40;

    /**
     * The app has not be used for several days, is unlikely to be used for several days, and has
     * been misbehaving in some manner.
     * Apps in this bucket will have the most restrictions, including network restrictions and
     * additional restrictions on jobs.
     * <p> Note: this bucket is not enabled in {@link Build.VERSION_CODES#R}.
     * @see #getAppStandbyBucket()
     */
    public static final int STANDBY_BUCKET_RESTRICTED = 45;

    /**
     * The app has never been used.
     * {@hide}
     */
    @SystemApi
    public static final int STANDBY_BUCKET_NEVER = 50;

    /** @hide */
    public static final int REASON_MAIN_MASK = 0xFF00;
    /** @hide */
    public static final int REASON_MAIN_DEFAULT =   0x0100;
    /**
     * The app spent sufficient time in the old bucket without any substantial event so it reached
     * the timeout threshold to have its bucket lowered.
     * @hide
     */
    public static final int REASON_MAIN_TIMEOUT =   0x0200;
    /**
     * The app was used in some way. Look at the REASON_SUB_USAGE_ reason for more details.
     * @hide
     */
    public static final int REASON_MAIN_USAGE =     0x0300;
    /**
     * Forced by the user/developer, either explicitly or implicitly through some action. If user
     * action was not involved and this is purely due to the system,
     * {@link #REASON_MAIN_FORCED_BY_SYSTEM} should be used instead.
     * @hide
     */
    public static final int REASON_MAIN_FORCED_BY_USER = 0x0400;
    /**
     * Set by a privileged system app. This may be overridden by
     * {@link #REASON_MAIN_FORCED_BY_SYSTEM} or user action.
     * @hide
     */
    public static final int REASON_MAIN_PREDICTED = 0x0500;
    /**
     * Forced by the system, independent of user action. If user action is involved,
     * {@link #REASON_MAIN_FORCED_BY_USER} should be used instead. When this is used, only
     * {@link #REASON_MAIN_FORCED_BY_SYSTEM} or user action can change the bucket.
     * @hide
     */
    public static final int REASON_MAIN_FORCED_BY_SYSTEM = 0x0600;

    /** @hide */
    public static final int REASON_SUB_MASK = 0x00FF;
    /**
     * The reason for using the default main reason is unknown or undefined.
     * @hide
     */
    public static final int REASON_SUB_DEFAULT_UNDEFINED = 0x0000;
    /**
     * The app was updated.
     * @hide
     */
    public static final int REASON_SUB_DEFAULT_APP_UPDATE = 0x0001;
    /**
     * The app was restored.
     * @hide
     */
    public static final int REASON_SUB_DEFAULT_APP_RESTORED = 0x0002;
    /**
     * The app was interacted with in some way by the system.
     * @hide
     */
    public static final int REASON_SUB_USAGE_SYSTEM_INTERACTION = 0x0001;
    /**
     * A notification was viewed by the user. This does not mean the user interacted with the
     * notification.
     * @hide
     */
    public static final int REASON_SUB_USAGE_NOTIFICATION_SEEN  = 0x0002;
    /**
     * The app was interacted with in some way by the user. This includes interacting with
     * notification.
     * @hide
     */
    public static final int REASON_SUB_USAGE_USER_INTERACTION   = 0x0003;
    /**
     * An {@link android.app.Activity} moved to the foreground.
     * @hide
     */
    public static final int REASON_SUB_USAGE_MOVE_TO_FOREGROUND = 0x0004;
    /**
     * An {@link android.app.Activity} moved to the background.
     * @hide
     */
    public static final int REASON_SUB_USAGE_MOVE_TO_BACKGROUND = 0x0005;
    /**
     * There was a system update.
     * @hide
     */
    public static final int REASON_SUB_USAGE_SYSTEM_UPDATE      = 0x0006;
    /**
     * An app is in an elevated bucket because of an active timeout preventing it from being placed
     * in a lower bucket.
     * @hide
     */
    public static final int REASON_SUB_USAGE_ACTIVE_TIMEOUT     = 0x0007;
    /**
     * This system package's sync adapter has been used for another package's content provider.
     * @hide
     */
    public static final int REASON_SUB_USAGE_SYNC_ADAPTER       = 0x0008;
    /**
     * A slice was pinned by an app.
     * @hide
     */
    public static final int REASON_SUB_USAGE_SLICE_PINNED       = 0x0009;
    /** /**
     * A slice was pinned by the default launcher or the default assistant.
     * @hide
     */
    public static final int REASON_SUB_USAGE_SLICE_PINNED_PRIV  = 0x000A;
    /**
     * A sync operation that is exempt from app standby was scheduled when the device wasn't Dozing.
     * @hide
     */
    public static final int REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE = 0x000B;
    /**
     * A sync operation that is exempt from app standby was scheduled while the device was Dozing.
     * @hide
     */
    public static final int REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_DOZE = 0x000C;
    /**
     * A sync operation that is exempt from app standby started.
     * @hide
     */
    public static final int REASON_SUB_USAGE_EXEMPTED_SYNC_START = 0x000D;
    /**
     * A sync operation that is not exempt from app standby was scheduled.
     * @hide
     */
    public static final int REASON_SUB_USAGE_UNEXEMPTED_SYNC_SCHEDULED = 0x000E;
    /**
     * A foreground service started.
     * @hide
     */
    public static final int REASON_SUB_USAGE_FOREGROUND_SERVICE_START = 0x000F;
    /**
     * The predicted bucket was restored after the app's temporary elevation to the ACTIVE bucket
     * ended.
     * @hide
     */
    public static final int REASON_SUB_PREDICTED_RESTORED       = 0x0001;
    /**
     * The reason the system forced the app into the bucket is unknown or undefined.
     * @hide
     */
    public static final int REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED = 0;
    /**
     * The app was unnecessarily using system resources (battery, memory, etc) in the background.
     * @hide
     */
    public static final int REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE = 1 << 0;
    /**
     * The app was deemed to be intentionally abusive.
     * @hide
     */
    public static final int REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE = 1 << 1;
    /**
     * The app was displaying buggy behavior.
     * @hide
     */
    public static final int REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY = 1 << 2;
    /**
     * The app was moved to restricted bucket due to user interaction, i.e., toggling FAS.
     *
     * <p>
     * Note: This should be coming from the more end-user facing UX, not from developer
     * options nor adb command.
     </p>
     *
     * @hide
     */
    public static final int REASON_SUB_FORCED_USER_FLAG_INTERACTION = 1 << 1;


    /** @hide */
    @IntDef(flag = false, prefix = { "STANDBY_BUCKET_" }, value = {
            STANDBY_BUCKET_EXEMPTED,
            STANDBY_BUCKET_ACTIVE,
            STANDBY_BUCKET_WORKING_SET,
            STANDBY_BUCKET_FREQUENT,
            STANDBY_BUCKET_RARE,
            STANDBY_BUCKET_RESTRICTED,
            STANDBY_BUCKET_NEVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StandbyBuckets {}

    /** @hide */
    @IntDef(flag = true, prefix = {"REASON_SUB_FORCED_"}, value = {
            REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED,
            REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE,
            REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE,
            REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY,
            REASON_SUB_FORCED_USER_FLAG_INTERACTION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForcedReasons {
    }

    /**
     * Observer id of the registered observer for the group of packages that reached the usage
     * time limit. Included as an extra in the PendingIntent that was registered.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_OBSERVER_ID = "android.app.usage.extra.OBSERVER_ID";

    /**
     * Original time limit in milliseconds specified by the registered observer for the group of
     * packages that reached the usage time limit. Included as an extra in the PendingIntent that
     * was registered.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_TIME_LIMIT = "android.app.usage.extra.TIME_LIMIT";

    /**
     * Actual usage time in milliseconds for the group of packages that reached the specified time
     * limit. Included as an extra in the PendingIntent that was registered.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_TIME_USED = "android.app.usage.extra.TIME_USED";

    /**
     * A String extra, when used with {@link UsageEvents.Event#getExtras}, that indicates
     * the category of the user interaction associated with the event. The category cannot
     * be more than 127 characters, longer value will be truncated to 127 characters.
     */
    @FlaggedApi(Flags.FLAG_USER_INTERACTION_TYPE_API)
    public static final String EXTRA_EVENT_CATEGORY =
            "android.app.usage.extra.EVENT_CATEGORY";

    /**
     * A String extra, when used with {@link UsageEvents.Event#getExtras}, that indicates
     * the action of the user interaction associated with the event. The action cannot be
     * more than 127 characters, longer value will be truncated to 127 characters.
     */
    @FlaggedApi(Flags.FLAG_USER_INTERACTION_TYPE_API)
    public static final String EXTRA_EVENT_ACTION =
            "android.app.usage.extra.EVENT_ACTION";

    /**
     * App usage observers will consider the task root package the source of usage.
     * @hide
     */
    @SystemApi
    public static final int USAGE_SOURCE_TASK_ROOT_ACTIVITY = 1;

    /**
     * App usage observers will consider the visible activity's package the source of usage.
     * @hide
     */
    @SystemApi
    public static final int USAGE_SOURCE_CURRENT_ACTIVITY = 2;

    /** @hide */
    @IntDef(prefix = { "USAGE_SOURCE_" }, value = {
            USAGE_SOURCE_TASK_ROOT_ACTIVITY,
            USAGE_SOURCE_CURRENT_ACTIVITY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UsageSource {}

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static final UsageEvents sEmptyResults = new UsageEvents();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final Context mContext;
    @UnsupportedAppUsage
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
     *
     * <p>
     * The returned list will contain one or more {@link UsageStats} objects for each package, with
     * usage data that covers at least the given time range.
     * Note: The begin and end times of the time range may be expanded to the nearest whole interval
     * period.
     * </p>
     *
     * <p> The caller must have {@link android.Manifest.permission#PACKAGE_USAGE_STATS} </p>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then {@code null} will be returned.</em>
     *
     * @param intervalType The time interval by which the stats are aggregated.
     * @param beginTime The inclusive beginning of the range of stats to include in the results.
     *                  Defined in terms of "Unix time", see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime The exclusive end of the range of stats to include in the results. Defined
     *                in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
     * @return A list of {@link UsageStats}
     *
     * @see #INTERVAL_DAILY
     * @see #INTERVAL_WEEKLY
     * @see #INTERVAL_MONTHLY
     * @see #INTERVAL_YEARLY
     * @see #INTERVAL_BEST
     */
    @UserHandleAware
    public List<UsageStats> queryUsageStats(int intervalType, long beginTime, long endTime) {
        try {
            @SuppressWarnings("unchecked")
            ParceledListSlice<UsageStats> slice = mService.queryUsageStats(intervalType, beginTime,
                    endTime, mContext.getOpPackageName(), mContext.getUserId());
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
     * <p> The caller must have {@link android.Manifest.permission#PACKAGE_USAGE_STATS} </p>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then {@code null} will be returned.</em>
     *
     * @param intervalType The time interval by which the stats are aggregated.
     * @param beginTime The inclusive beginning of the range of stats to include in the results.
     *                  Defined in terms of "Unix time", see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime The exclusive end of the range of stats to include in the results. Defined
     *                in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
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
     * Gets aggregated event stats for the given time range, aggregated by the specified interval.
     * <p>The returned list will contain a {@link EventStats} object for each event type that
     * is being aggregated and has data for an interval that is a subset of the time range given.
     *
     * <p>The current event types that will be aggregated here are:</p>
     * <ul>
     *     <li>{@link UsageEvents.Event#SCREEN_INTERACTIVE}</li>
     *     <li>{@link UsageEvents.Event#SCREEN_NON_INTERACTIVE}</li>
     *     <li>{@link UsageEvents.Event#KEYGUARD_SHOWN}</li>
     *     <li>{@link UsageEvents.Event#KEYGUARD_HIDDEN}</li>
     * </ul>
     *
     * <p> The caller must have {@link android.Manifest.permission#PACKAGE_USAGE_STATS} </p>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then {@code null} will be returned.</em>
     *
     * @param intervalType The time interval by which the stats are aggregated.
     * @param beginTime The inclusive beginning of the range of stats to include in the results.
     *                  Defined in terms of "Unix time", see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime The exclusive end of the range of stats to include in the results. Defined
     *                in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
     * @return A list of {@link EventStats}
     *
     * @see #INTERVAL_DAILY
     * @see #INTERVAL_WEEKLY
     * @see #INTERVAL_MONTHLY
     * @see #INTERVAL_YEARLY
     * @see #INTERVAL_BEST
     */
    public List<EventStats> queryEventStats(int intervalType, long beginTime, long endTime) {
        try {
            @SuppressWarnings("unchecked")
            ParceledListSlice<EventStats> slice = mService.queryEventStats(intervalType, beginTime,
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
     * Query for events in the given time range. Events are only kept by the system for a few
     * days.
     * <p> The caller must have {@link android.Manifest.permission#PACKAGE_USAGE_STATS} </p>
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then {@code null} will be returned.</em>
     *
     * @param beginTime The inclusive beginning of the range of events to include in the results.
     *                  Defined in terms of "Unix time", see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime The exclusive end of the range of events to include in the results. Defined
     *                in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
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
     * Query for events with specific UsageEventsQuery object.
     * <em>Note: if the user's device is not in an unlocked state (as defined by
     * {@link UserManager#isUserUnlocked()}), then {@code null} will be returned.</em>
     *
     * @param query The query object used to specify the query parameters.
     * @return A {@link UsageEvents}.
     */
    @FlaggedApi(Flags.FLAG_FILTER_BASED_EVENT_QUERY_API)
    @NonNull
    @RequiresPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
    public UsageEvents queryEvents(@NonNull UsageEventsQuery query) {
        try {
            UsageEvents iter = mService.queryEventsWithFilter(query, mContext.getOpPackageName());
            if (iter != null) {
                return iter;
            }
        } catch (RemoteException e) {
            // fallthrough and return empty result.
        }
        return sEmptyResults;
    }

    /**
     * Like {@link #queryEvents(long, long)}, but only returns events for the calling package.
     * <em>Note: Starting from {@link android.os.Build.VERSION_CODES#R Android R}, if the user's
     * device is not in an unlocked state (as defined by {@link UserManager#isUserUnlocked()}),
     * then {@code null} will be returned.</em>
     *
     * @param beginTime The inclusive beginning of the range of events to include in the results.
     *                  Defined in terms of "Unix time", see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime The exclusive end of the range of events to include in the results. Defined
     *                in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
     * @return A {@link UsageEvents} object.
     *
     * @see #queryEvents(long, long)
     */
    public UsageEvents queryEventsForSelf(long beginTime, long endTime) {
        try {
            final UsageEvents events = mService.queryEventsForPackage(beginTime, endTime,
                    mContext.getOpPackageName());
            if (events != null) {
                return events;
            }
        } catch (RemoteException e) {
            // fallthrough
        }
        return sEmptyResults;
    }

    /**
     * A convenience method that queries for all stats in the given range (using the best interval
     * for that range), merges the resulting data, and keys it by package name.
     * See {@link #queryUsageStats(int, long, long)}.
     * <p> The caller must have {@link android.Manifest.permission#PACKAGE_USAGE_STATS} </p>
     *
     * @param beginTime The inclusive beginning of the range of stats to include in the results.
     *                  Defined in terms of "Unix time", see
     *                  {@link java.lang.System#currentTimeMillis}.
     * @param endTime The exclusive end of the range of stats to include in the results. Defined
     *                in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
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
     * Returns whether the app standby bucket feature is enabled.
     * @hide
     */
    @TestApi
    public boolean isAppStandbyEnabled() {
        try {
            return mService.isAppStandbyEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the specified app is currently considered inactive. This will be true if the
     * app hasn't been used directly or indirectly for a period of time defined by the system. This
     * could be of the order of several hours or days. Apps are not considered inactive when the
     * device is charging.
     * <p> The caller must have {@link android.Manifest.permission#PACKAGE_USAGE_STATS} to query the
     * inactive state of other apps</p>
     *
     * @param packageName The package name of the app to query
     * @return whether the app is currently considered inactive or false if querying another app
     * without {@link android.Manifest.permission#PACKAGE_USAGE_STATS}
     */
    public boolean isAppInactive(String packageName) {
        try {
            return mService.isAppInactive(packageName, mContext.getUserId(),
                    mContext.getOpPackageName());
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
     * be restricted from running background tasks such as jobs and alarms.
     * <p>Restrictions increase progressively from {@link #STANDBY_BUCKET_ACTIVE} to
     * {@link #STANDBY_BUCKET_RESTRICTED}, with {@link #STANDBY_BUCKET_ACTIVE} being the least
     * restrictive. The battery level of the device might also affect the restrictions.
     * <p>Apps in buckets &le; {@link #STANDBY_BUCKET_ACTIVE} have no standby restrictions imposed.
     * Apps in buckets &gt; {@link #STANDBY_BUCKET_FREQUENT} may have network access restricted when
     * running in the background.
     * <p>The standby state of an app can change at any time either due to a user interaction or a
     * system interaction or some algorithm determining that the app can be restricted for a period
     * of time before the user has a need for it.
     * <p>You can also query the recent history of standby bucket changes by calling
     * {@link #queryEventsForSelf(long, long)} and searching for
     * {@link UsageEvents.Event#STANDBY_BUCKET_CHANGED}.
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
     * bucket for a different app than itself. The caller will not be able to change an app's
     * standby bucket if that app is in the {@link #STANDBY_BUCKET_RESTRICTED} bucket.
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
            throw e.rethrowFromSystemServer();
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
            final ParceledListSlice<AppStandbyInfo> slice = mService.getAppStandbyBuckets(
                    mContext.getOpPackageName(), mContext.getUserId());
            final List<AppStandbyInfo> bucketList = slice.getList();
            final ArrayMap<String, Integer> bucketMap = new ArrayMap<>();
            final int n = bucketList.size();
            for (int i = 0; i < n; i++) {
                final AppStandbyInfo bucketInfo = bucketList.get(i);
                bucketMap.put(bucketInfo.mPackageName, bucketInfo.mStandbyBucket);
            }
            return bucketMap;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * {@hide}
     * Changes the app standby bucket for multiple apps at once. The Map is keyed by the package
     * name and the value is one of STANDBY_BUCKET_*. The caller will not be able to change an
     * app's standby bucket if that app is in the {@link #STANDBY_BUCKET_RESTRICTED} bucket.
     * @param appBuckets a map of package name to bucket value.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_APP_IDLE_STATE)
    public void setAppStandbyBuckets(Map<String, Integer> appBuckets) {
        if (appBuckets == null) {
            return;
        }
        final List<AppStandbyInfo> bucketInfoList = new ArrayList<>(appBuckets.size());
        for (Map.Entry<String, Integer> bucketEntry : appBuckets.entrySet()) {
            bucketInfoList.add(new AppStandbyInfo(bucketEntry.getKey(), bucketEntry.getValue()));
        }
        final ParceledListSlice<AppStandbyInfo> slice = new ParceledListSlice<>(bucketInfoList);
        try {
            mService.setAppStandbyBuckets(slice, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the lowest bucket this app can ever enter.
     *
     * @param packageName the package for which to fetch the minimum allowed standby bucket.
     * {@hide}
     */
    @StandbyBuckets
    @RequiresPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
    public int getAppMinStandbyBucket(String packageName) {
        try {
            return mService.getAppMinStandbyBucket(packageName, mContext.getOpPackageName(),
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Changes an app's estimated launch time. An app is considered "launched" when a user opens
     * one of its {@link android.app.Activity Activities}. The provided time is persisted across
     * reboots and is used unless 1) the time is more than a week in the future and the platform
     * thinks the app will be launched sooner, 2) the estimated time has passed. Passing in
     * {@link Long#MAX_VALUE} effectively clears the previously set launch time for the app.
     *
     * @param packageName               The package name of the app to set the bucket for.
     * @param estimatedLaunchTimeMillis The next time the app is expected to be launched. Units are
     *                                  in milliseconds since epoch (the same as
     *                                  {@link System#currentTimeMillis()}).
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_APP_LAUNCH_TIME_ESTIMATE)
    public void setEstimatedLaunchTimeMillis(@NonNull String packageName,
            @CurrentTimeMillisLong long estimatedLaunchTimeMillis) {
        if (packageName == null) {
            throw new NullPointerException("package name cannot be null");
        }
        if (estimatedLaunchTimeMillis <= 0) {
            throw new IllegalArgumentException("estimated launch time must be positive");
        }
        try {
            mService.setEstimatedLaunchTime(
                    packageName, estimatedLaunchTimeMillis, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Changes the estimated launch times for multiple apps at once. The map is keyed by the
     * package name and the value is the estimated launch time.
     *
     * @param estimatedLaunchTimesMillis A map of package name to estimated launch time.
     * @see #setEstimatedLaunchTimeMillis(String, long)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_APP_LAUNCH_TIME_ESTIMATE)
    public void setEstimatedLaunchTimesMillis(
            @NonNull Map<String, Long> estimatedLaunchTimesMillis) {
        if (estimatedLaunchTimesMillis == null) {
            throw new NullPointerException("estimatedLaunchTimesMillis cannot be null");
        }
        final List<AppLaunchEstimateInfo> estimateList =
                new ArrayList<>(estimatedLaunchTimesMillis.size());
        for (Map.Entry<String, Long> estimateEntry : estimatedLaunchTimesMillis.entrySet()) {
            final String pkgName = estimateEntry.getKey();
            if (pkgName == null) {
                throw new NullPointerException("package name cannot be null");
            }
            final Long estimatedLaunchTime = estimateEntry.getValue();
            if (estimatedLaunchTime == null || estimatedLaunchTime <= 0) {
                throw new IllegalArgumentException("estimated launch time must be positive");
            }
            estimateList.add(new AppLaunchEstimateInfo(pkgName, estimatedLaunchTime));
        }
        final ParceledListSlice<AppLaunchEstimateInfo> slice =
                new ParceledListSlice<>(estimateList);
        try {
            mService.setEstimatedLaunchTimes(slice, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Register an app usage limit observer that receives a callback on the provided intent when
     * the sum of usages of apps and tokens in the {@code observed} array exceeds the
     * {@code timeLimit} specified. The structure of a token is a String with the reporting
     * package's name and a token the reporting app will use, separated by the forward slash
     * character. Example: com.reporting.package/5OM3*0P4QU3-7OK3N
     * The observer will automatically be unregistered when the time limit is reached and the
     * intent is delivered. Registering an {@code observerId} that was already registered will
     * override the previous one. No more than 1000 unique {@code observerId} may be registered by
     * a single uid at any one time.
     * @param observerId A unique id associated with the group of apps to be monitored. There can
     *                  be multiple groups with common packages and different time limits.
     * @param observedEntities The list of packages and token to observe for usage time. Cannot be
     *                         null and must include at least one package or token.
     * @param timeLimit The total time the set of apps can be in the foreground before the
     *                  callbackIntent is delivered. Must be at least one minute.
     * @param timeUnit The unit for time specified in {@code timeLimit}. Cannot be null.
     * @param callbackIntent The PendingIntent that will be dispatched when the usage limit is
     *                       exceeded by the group of apps. The delivered Intent will also contain
     *                       the extras {@link #EXTRA_OBSERVER_ID}, {@link #EXTRA_TIME_LIMIT} and
     *                       {@link #EXTRA_TIME_USED}. Cannot be null.
     * @throws SecurityException if the caller doesn't have the OBSERVE_APP_USAGE permission and
     *                           is not the profile owner of this user.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.OBSERVE_APP_USAGE)
    public void registerAppUsageObserver(int observerId, @NonNull String[] observedEntities,
            long timeLimit, @NonNull TimeUnit timeUnit, @NonNull PendingIntent callbackIntent) {
        try {
            mService.registerAppUsageObserver(observerId, observedEntities,
                    timeUnit.toMillis(timeLimit), callbackIntent, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Unregister the app usage observer specified by the {@code observerId}. This will only apply
     * to any observer registered by this application. Unregistering an observer that was already
     * unregistered or never registered will have no effect.
     * @param observerId The id of the observer that was previously registered.
     * @throws SecurityException if the caller doesn't have the OBSERVE_APP_USAGE permission and is
     *                           not the profile owner of this user.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.OBSERVE_APP_USAGE)
    public void unregisterAppUsageObserver(int observerId) {
        try {
            mService.unregisterAppUsageObserver(observerId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register a usage session observer that receives a callback on the provided {@code
     * limitReachedCallbackIntent} when the sum of usages of apps and tokens in the {@code
     * observed} array exceeds the {@code timeLimit} specified within a usage session. The
     * structure of a token is a String with the reporting packages' name and a token the
     * reporting app will use, separated by the forward slash character.
     * Example: com.reporting.package/5OM3*0P4QU3-7OK3N
     * After the {@code timeLimit} has been reached, the usage session observer will receive a
     * callback on the provided {@code sessionEndCallbackIntent} when the usage session ends.
     * Registering another session observer against a {@code sessionObserverId} that has already
     * been registered will override the previous session observer.
     *
     * @param sessionObserverId A unique id associated with the group of apps to be
     *                          monitored. There can be multiple groups with common
     *                          packages and different time limits.
     * @param observedEntities The list of packages and token to observe for usage time. Cannot be
     *                         null and must include at least one package or token.
     * @param timeLimit The total time the set of apps can be used continuously before the {@code
     *                  limitReachedCallbackIntent} is delivered. Must be at least one minute.
     * @param sessionThresholdTime The time that can take place between usage sessions before the
     *                             next session is considered a new session. Must be non-negative.
     * @param limitReachedCallbackIntent The {@link PendingIntent} that will be dispatched when the
     *                                   usage limit is exceeded by the group of apps. The
     *                                   delivered Intent will also contain the extras {@link
     *                                   #EXTRA_OBSERVER_ID}, {@link #EXTRA_TIME_LIMIT} and {@link
     *                                   #EXTRA_TIME_USED}. Cannot be null.
     * @param sessionEndCallbackIntent The {@link PendingIntent}  that will be dispatched when the
     *                                 session has ended after the usage limit has been exceeded.
     *                                 The session is considered at its end after the {@code
     *                                 observed} usage has stopped and an additional {@code
     *                                 sessionThresholdTime} has passed. The delivered Intent will
     *                                 also contain the extras {@link #EXTRA_OBSERVER_ID} and {@link
     *                                 #EXTRA_TIME_USED}. Can be null.
     * @throws SecurityException if the caller doesn't have the OBSERVE_APP_USAGE permission and
     *                           is not the profile owner of this user.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.OBSERVE_APP_USAGE)
    public void registerUsageSessionObserver(int sessionObserverId,
            @NonNull String[] observedEntities, @NonNull Duration timeLimit,
            @NonNull Duration sessionThresholdTime,
            @NonNull PendingIntent limitReachedCallbackIntent,
            @Nullable PendingIntent sessionEndCallbackIntent) {
        try {
            mService.registerUsageSessionObserver(sessionObserverId, observedEntities,
                    timeLimit.toMillis(), sessionThresholdTime.toMillis(),
                    limitReachedCallbackIntent, sessionEndCallbackIntent,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister the usage session observer specified by the {@code sessionObserverId}. This will
     * only apply to any app session observer registered by this application. Unregistering an
     * observer that was already unregistered or never registered will have no effect.
     *
     * @param sessionObserverId The id of the observer that was previously registered.
     * @throws SecurityException if the caller doesn't have the OBSERVE_APP_USAGE permission and
     *                           is not the profile owner of this user.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.OBSERVE_APP_USAGE)
    public void unregisterUsageSessionObserver(int sessionObserverId) {
        try {
            mService.unregisterUsageSessionObserver(sessionObserverId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register a usage limit observer that receives a callback on the provided intent when the
     * sum of usages of apps and tokens in the provided {@code observedEntities} array exceeds the
     * {@code timeLimit} specified. The structure of a token is a {@link String} with the reporting
     * package's name and a token that the calling app will use, separated by the forward slash
     * character. Example: com.reporting.package/5OM3*0P4QU3-7OK3N
     * <p>
     * Registering an {@code observerId} that was already registered will override the previous one.
     * No more than 1000 unique {@code observerId} may be registered by a single uid
     * at any one time.
     * A limit is not cleared when the usage time is exceeded. It needs to be unregistered via
     * {@link #unregisterAppUsageLimitObserver}.
     * <p>
     * Note: usage limits are not persisted in the system and are cleared on reboots. Callers
     * must reset any limits that they need on reboots.
     * <p>
     * This method is similar to {@link #registerAppUsageObserver}, but the usage limit set here
     * will be visible to the launcher so that it can report the limit to the user and how much
     * of it is remaining.
     * @see android.content.pm.LauncherApps#getAppUsageLimit
     *
     * @param observerId A unique id associated with the group of apps to be monitored. There can
     *                   be multiple groups with common packages and different time limits.
     * @param observedEntities The list of packages and token to observe for usage time. Cannot be
     *                         null and must include at least one package or token.
     * @param timeLimit The total time the set of apps can be in the foreground before the
     *                  {@code callbackIntent} is delivered. Must be at least one minute.
     * @param timeUsed The time that has already been used by the set of apps in
     *                 {@code observedEntities}. Note: a time used equal to or greater than
     *                 {@code timeLimit} can be set to indicate that the user has already exhausted
     *                 the limit for a group, in which case, the given {@code callbackIntent} will
     *                 be ignored.
     * @param callbackIntent The PendingIntent that will be dispatched when the usage limit is
     *                       exceeded by the group of apps. The delivered Intent will also contain
     *                       the extras {@link #EXTRA_OBSERVER_ID}, {@link #EXTRA_TIME_LIMIT} and
     *                       {@link #EXTRA_TIME_USED}. Cannot be {@code null} unless the observer is
     *                       being registered with a {@code timeUsed} equal to or greater than
     *                       {@code timeLimit}.
     * @throws SecurityException if the caller is neither the active supervision app nor does it
     *                           have both SUSPEND_APPS and OBSERVE_APP_USAGE permissions.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.SUSPEND_APPS,
            android.Manifest.permission.OBSERVE_APP_USAGE})
    public void registerAppUsageLimitObserver(int observerId, @NonNull String[] observedEntities,
            @NonNull Duration timeLimit, @NonNull Duration timeUsed,
            @Nullable PendingIntent callbackIntent) {
        try {
            mService.registerAppUsageLimitObserver(observerId, observedEntities,
                    timeLimit.toMillis(), timeUsed.toMillis(), callbackIntent,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister the app usage limit observer specified by the {@code observerId}.
     * This will only apply to any observer registered by this application. Unregistering
     * an observer that was already unregistered or never registered will have no effect.
     *
     * @param observerId The id of the observer that was previously registered.
     * @throws SecurityException if the caller is neither the active supervision app nor does it
     *                         have both SUSPEND_APPS and OBSERVE_APP_USAGE permissions.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.SUSPEND_APPS,
            android.Manifest.permission.OBSERVE_APP_USAGE})
    public void unregisterAppUsageLimitObserver(int observerId) {
        try {
            mService.unregisterAppUsageLimitObserver(observerId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports user interaction with a given package in the given user.
     *
     * <p><em>This method is only for use by the system</em>
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REPORT_USAGE_STATS)
    public void reportUserInteraction(@NonNull String packageName, int userId) {
        try {
            mService.reportUserInteraction(packageName, userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Reports user interaction with given package and a particular {@code extras}
     * in the given user.
     *
     * <p>
     * Note: The structure of {@code extras} is a {@link PersistableBundle} with the
     * category {@link #EXTRA_EVENT_CATEGORY} and the action {@link #EXTRA_EVENT_ACTION}.
     * Category provides additional detail about the user interaction, the value
     * is defined in namespace based. Example: android.app.notification could be used to
     * indicate that the reported user interaction is related to notification. Action
     * indicates the general action that performed.
     * </p>
     *
     * @param packageName The package name of the app
     * @param userId The user id who triggers the user interaction
     * @param extras The {@link PersistableBundle} that will be used to specify the
     *               extra details for the user interaction event. The {@link PersistableBundle}
     *               must contain the extras {@link #EXTRA_EVENT_CATEGORY},
     *               {@link #EXTRA_EVENT_ACTION}. Cannot be empty.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_USER_INTERACTION_TYPE_API)
    @RequiresPermission(android.Manifest.permission.REPORT_USAGE_STATS)
    public void reportUserInteraction(@NonNull String packageName, @UserIdInt int userId,
            @NonNull PersistableBundle extras) {
        try {
            mService.reportUserInteractionWithBundle(packageName, userId, extras);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Report usage associated with a particular {@code token} has started. Tokens are app defined
     * strings used to represent usage of in-app features. Apps with the {@link
     * android.Manifest.permission#OBSERVE_APP_USAGE} permission can register time limit observers
     * to monitor the usage of a token. In app usage can only associated with an {@code activity}
     * and usage will be considered stopped if the activity stops or crashes.
     * @see #registerAppUsageObserver
     * @see #registerUsageSessionObserver
     * @see #registerAppUsageLimitObserver
     *
     * @param activity The activity {@code token} is associated with.
     * @param token The token to report usage against.
     * @hide
     */
    @SystemApi
    public void reportUsageStart(@NonNull Activity activity, @NonNull String token) {
        try {
            mService.reportUsageStart(activity.getActivityToken(), token,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report usage associated with a particular {@code token} had started some amount of time in
     * the past. Tokens are app defined strings used to represent usage of in-app features. Apps
     * with the {@link android.Manifest.permission#OBSERVE_APP_USAGE} permission can register time
     * limit observers to monitor the usage of a token. In app usage can only associated with an
     * {@code activity} and usage will be considered stopped if the activity stops or crashes.
     * @see #registerAppUsageObserver
     * @see #registerUsageSessionObserver
     * @see #registerAppUsageLimitObserver
     *
     * @param activity The activity {@code token} is associated with.
     * @param token The token to report usage against.
     * @param timeAgoMs How long ago the start of usage took place
     * @hide
     */
    @SystemApi
    public void reportUsageStart(@NonNull Activity activity, @NonNull String token,
                                 long timeAgoMs) {
        try {
            mService.reportPastUsageStart(activity.getActivityToken(), token, timeAgoMs,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report the usage associated with a particular {@code token} has stopped.
     *
     * @param activity The activity {@code token} is associated with.
     * @param token The token to report usage against.
     * @hide
     */
    @SystemApi
    public void reportUsageStop(@NonNull Activity activity, @NonNull String token) {
        try {
            mService.reportUsageStop(activity.getActivityToken(), token,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get what App Usage Observers will consider the source of usage for an activity. Usage Source
     * is decided at boot and will not change until next boot.
     * @see #USAGE_SOURCE_TASK_ROOT_ACTIVITY
     * @see #USAGE_SOURCE_CURRENT_ACTIVITY
     *
     * @throws SecurityException if the caller doesn't have the OBSERVE_APP_USAGE permission and
     *                           is not the profile owner of this user.
     * @hide
     */
    @SystemApi
    public @UsageSource int getUsageSource() {
        try {
            return mService.getUsageSource();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Force the Usage Source be reread from global settings.
     * @hide
     */
    @TestApi
    public void forceUsageSourceSettingRead() {
        try {
            mService.forceUsageSourceSettingRead();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static String reasonToString(int standbyReason) {
        final int subReason = standbyReason & REASON_SUB_MASK;
        StringBuilder sb = new StringBuilder();
        switch (standbyReason & REASON_MAIN_MASK) {
            case REASON_MAIN_DEFAULT:
                sb.append("d");
                switch (subReason) {
                    case REASON_SUB_DEFAULT_UNDEFINED:
                        // Historically, undefined didn't have a string, so don't add anything here.
                        break;
                    case REASON_SUB_DEFAULT_APP_UPDATE:
                        sb.append("-au");
                        break;
                    case REASON_SUB_DEFAULT_APP_RESTORED:
                        sb.append("-ar");
                        break;
                }
                break;
            case REASON_MAIN_FORCED_BY_SYSTEM:
                sb.append("s");
                if (subReason > 0) {
                    sb.append("-").append(Integer.toBinaryString(subReason));
                }
                break;
            case REASON_MAIN_FORCED_BY_USER:
                sb.append("f");
                if (subReason > 0) {
                    sb.append("-").append(Integer.toBinaryString(subReason));
                }
                break;
            case REASON_MAIN_PREDICTED:
                sb.append("p");
                switch (subReason) {
                    case REASON_SUB_PREDICTED_RESTORED:
                        sb.append("-r");
                        break;
                }
                break;
            case REASON_MAIN_TIMEOUT:
                sb.append("t");
                break;
            case REASON_MAIN_USAGE:
                sb.append("u");
                switch (subReason) {
                    case REASON_SUB_USAGE_SYSTEM_INTERACTION:
                        sb.append("-si");
                        break;
                    case REASON_SUB_USAGE_NOTIFICATION_SEEN:
                        sb.append("-ns");
                        break;
                    case REASON_SUB_USAGE_USER_INTERACTION:
                        sb.append("-ui");
                        break;
                    case REASON_SUB_USAGE_MOVE_TO_FOREGROUND:
                        sb.append("-mf");
                        break;
                    case REASON_SUB_USAGE_MOVE_TO_BACKGROUND:
                        sb.append("-mb");
                        break;
                    case REASON_SUB_USAGE_SYSTEM_UPDATE:
                        sb.append("-su");
                        break;
                    case REASON_SUB_USAGE_ACTIVE_TIMEOUT:
                        sb.append("-at");
                        break;
                    case REASON_SUB_USAGE_SYNC_ADAPTER:
                        sb.append("-sa");
                        break;
                    case REASON_SUB_USAGE_SLICE_PINNED:
                        sb.append("-lp");
                        break;
                    case REASON_SUB_USAGE_SLICE_PINNED_PRIV:
                        sb.append("-lv");
                        break;
                    case REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE:
                        sb.append("-en");
                        break;
                    case REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_DOZE:
                        sb.append("-ed");
                        break;
                    case REASON_SUB_USAGE_EXEMPTED_SYNC_START:
                        sb.append("-es");
                        break;
                    case REASON_SUB_USAGE_UNEXEMPTED_SYNC_SCHEDULED:
                        sb.append("-uss");
                        break;
                    case REASON_SUB_USAGE_FOREGROUND_SERVICE_START:
                        sb.append("-fss");
                        break;
                }
                break;
        }
        return sb.toString();
    }

    /** @hide */
    public static String usageSourceToString(int usageSource) {
        switch (usageSource) {
            case USAGE_SOURCE_TASK_ROOT_ACTIVITY:
                return "TASK_ROOT_ACTIVITY";
            case USAGE_SOURCE_CURRENT_ACTIVITY:
                return "CURRENT_ACTIVITY";
            default:
                StringBuilder sb = new StringBuilder();
                sb.append("UNKNOWN(");
                sb.append(usageSource);
                sb.append(")");
                return sb.toString();
        }
    }

    /** @hide */
    public static String standbyBucketToString(int standbyBucket) {
        switch (standbyBucket) {
            case STANDBY_BUCKET_EXEMPTED:
                return "EXEMPTED";
            case STANDBY_BUCKET_ACTIVE:
                return "ACTIVE";
            case STANDBY_BUCKET_WORKING_SET:
                return "WORKING_SET";
            case STANDBY_BUCKET_FREQUENT:
                return "FREQUENT";
            case STANDBY_BUCKET_RARE:
                return "RARE";
            case STANDBY_BUCKET_RESTRICTED:
                return "RESTRICTED";
            case STANDBY_BUCKET_NEVER:
                return "NEVER";
            default:
                return String.valueOf(standbyBucket);
        }
    }

    /**
     * {@hide}
     * Temporarily allowlist the specified app for a short duration. This is to allow an app
     * receiving a high priority message to be able to access the network and acquire wakelocks
     * even if the device is in power-save mode or the app is currently considered inactive.
     * @param packageName The package name of the app to allowlist.
     * @param duration Duration to allowlist the app for, in milliseconds. It is recommended that
     * this be limited to 10s of seconds. Requested duration will be clamped to a few minutes.
     * @param user The user for whom the package should be allowlisted. Passing in a user that is
     * not the same as the caller's process will require the INTERACT_ACROSS_USERS permission.
     * @see #isAppInactive(String)
     *
     * @deprecated Use
     * {@link android.os.PowerWhitelistManager#whitelistAppTemporarily(String, long)} instead.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST)
    @Deprecated
    public void whitelistAppTemporarily(String packageName, long duration, UserHandle user) {
        mContext.getSystemService(PowerWhitelistManager.class)
                .whitelistAppTemporarily(packageName, duration);
    }

    /**
     * Inform usage stats that the carrier privileged apps access rules have changed.
     * <p> The caller must have {@link android.Manifest.permission#BIND_CARRIER_SERVICES} </p>
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BIND_CARRIER_SERVICES)
    public void onCarrierPrivilegedAppsChanged() {
        try {
            mService.onCarrierPrivilegedAppsChanged();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
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
    @RequiresPermission(android.Manifest.permission.REPORT_USAGE_STATS)
    public void reportChooserSelection(String packageName, int userId, String contentType,
                                       String[] annotations, String action) {
        try {
            mService.reportChooserSelection(packageName, userId, contentType, annotations, action);
        } catch (RemoteException re) {
        }
    }

    /**
     * Get the last time a package is used by any users including explicit user interaction and
     * component usage, measured in milliseconds since the epoch and truncated to the boundary of
     * last day before the exact time. For packages that are never used, the time will be the epoch.
     * <p> Note that this usage stats is user-agnostic. </p>
     * <p>
     * Also note that component usage is only reported for component bindings (e.g. broadcast
     * receiver, service, content provider) and only when such a binding would cause an app to leave
     * the stopped state.
     * See {@link UsageEvents.Event.USER_INTERACTION}, {@link UsageEvents.Event.APP_COMPONENT_USED}.
     * </p>
     *
     * @param packageName The name of the package to be queried.
     * @return last time the queried package is used since the epoch.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.PACKAGE_USAGE_STATS})
    public long getLastTimeAnyComponentUsed(@NonNull String packageName) {
        try {
            return mService.getLastTimeAnyComponentUsed(packageName, mContext.getOpPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the broadcast response stats since the last boot corresponding to
     * {@code packageName} and {@code id}.
     *
     * <p> Broadcast response stats will include the aggregated data of what actions an app took
     * upon receiving a broadcast. This data will consider the broadcasts that the caller sent to
     * {@code packageName} and explicitly requested to record the response events using
     * {@link BroadcastOptions#recordResponseEventWhileInBackground(long)}.
     *
     * <p> The returned list could one or more {@link BroadcastResponseStats} objects or be empty
     * depending on the {@code packageName} and {@code id} and whether there is any data
     * corresponding to these. If the {@code packageName} is not {@code null} and {@code id} is
     * {@code > 0}, then the returned list would contain at most one {@link BroadcastResponseStats}
     * object. Otherwise, the returned list could contain more than one
     * {@link BroadcastResponseStats} object in no particular order.
     *
     * <p> Note: It is possible that same {@code id} was used for broadcasts sent to different
     * packages. So, callers can query the data corresponding to
     * all broadcasts with a particular {@code id} by passing {@code packageName} as {@code null}.
     *
     * @param packageName The name of the package that the caller wants to query for
     *                    or {@code null} to indicate that data corresponding to all packages
     *                    should be returned.
     * @param id The ID corresponding to the broadcasts that the caller wants to query for, or
     *           {@code 0} to indicate that data corresponding to all IDs should be returned.
     *           This is the ID the caller specifies when requesting a broadcast response event
     *           to be recorded using
     *           {@link BroadcastOptions#recordResponseEventWhileInBackground(long)}.
     *
     * @return the list of broadcast response stats corresponding to {@code packageName}
     *         and {@code id}.
     *
     * @see #clearBroadcastResponseStats(String, long)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS)
    @UserHandleAware
    @NonNull
    public List<BroadcastResponseStats> queryBroadcastResponseStats(
            @Nullable String packageName, @IntRange(from = 0) long id) {
        try {
            return mService.queryBroadcastResponseStats(packageName, id,
                    mContext.getOpPackageName(), mContext.getUserId()).getList();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Clears the broadcast response stats corresponding to {@code packageName} and {@code id}.
     *
     * <p> When a caller uses this API, stats related to the events occurring till that point will
     * be cleared and subsequent calls to {@link #queryBroadcastResponseStats(String, long)} will
     * return stats related to events occurring after this.
     *
     * @param packageName The name of the package that the caller wants to clear the data for or
     *                    {@code null} to indicate that data corresponding to all packages should
     *                    be cleared.
     * @param id The ID corresponding to the broadcasts that the caller wants to clear the data
     *           for, or {code 0} to indicate that data corresponding to all IDs should be deleted.
     *           This is the ID the caller specifies when requesting a broadcast response event
     *           to be recorded using
     *           {@link BroadcastOptions#recordResponseEventWhileInBackground(long)}.
     *
     * @see #queryBroadcastResponseStats(String, long)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS)
    @UserHandleAware
    public void clearBroadcastResponseStats(@Nullable String packageName,
            @IntRange(from = 0) long id) {
        try {
            mService.clearBroadcastResponseStats(packageName, id,
                    mContext.getOpPackageName(), mContext.getUserId());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Clears the broadcast events that were sent by the caller uid.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS)
    @UserHandleAware
    public void clearBroadcastEvents() {
        try {
            mService.clearBroadcastEvents(mContext.getOpPackageName(), mContext.getUserId());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the given {@code packageName} is exempted from broadcast response tracking.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    @UserHandleAware
    public boolean isPackageExemptedFromBroadcastResponseStats(@NonNull String packageName) {
        try {
            return mService.isPackageExemptedFromBroadcastResponseStats(packageName,
                    mContext.getUserId());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    @Nullable
    public String getAppStandbyConstant(@NonNull String key) {
        try {
            return mService.getAppStandbyConstant(key);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }
}
