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

package com.android.server.usage;

import static android.app.usage.UsageEvents.Event.DEVICE_SHUTDOWN;
import static android.app.usage.UsageStatsManager.INTERVAL_BEST;
import static android.app.usage.UsageStatsManager.INTERVAL_COUNT;
import static android.app.usage.UsageStatsManager.INTERVAL_DAILY;
import static android.app.usage.UsageStatsManager.INTERVAL_MONTHLY;
import static android.app.usage.UsageStatsManager.INTERVAL_WEEKLY;
import static android.app.usage.UsageStatsManager.INTERVAL_YEARLY;

import android.app.usage.ConfigurationStats;
import android.app.usage.EventList;
import android.app.usage.EventStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usage.UsageStatsDatabase.StatCombiner;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A per-user UsageStatsService. All methods are meant to be called with the main lock held
 * in UsageStatsService.
 */
class UserUsageStatsService {
    private static final String TAG = "UsageStatsService";
    private static final boolean DEBUG = UsageStatsService.DEBUG;
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final int sDateFormatFlags =
            DateUtils.FORMAT_SHOW_DATE
            | DateUtils.FORMAT_SHOW_TIME
            | DateUtils.FORMAT_SHOW_YEAR
            | DateUtils.FORMAT_NUMERIC_DATE;

    private final Context mContext;
    private final UsageStatsDatabase mDatabase;
    private final IntervalStats[] mCurrentStats;
    private boolean mStatsChanged = false;
    private final UnixCalendar mDailyExpiryDate;
    private final StatsUpdatedListener mListener;
    private final String mLogPrefix;
    private String mLastBackgroundedPackage;
    private final int mUserId;

    // STOPSHIP: Temporary member variable for debugging b/110930764.
    private Event mLastEvent;

    private static final long[] INTERVAL_LENGTH = new long[] {
            UnixCalendar.DAY_IN_MILLIS, UnixCalendar.WEEK_IN_MILLIS,
            UnixCalendar.MONTH_IN_MILLIS, UnixCalendar.YEAR_IN_MILLIS
    };

    interface StatsUpdatedListener {
        void onStatsUpdated();
        void onStatsReloaded();
        /**
         * Callback that a system update was detected
         * @param mUserId user that needs to be initialized
         */
        void onNewUpdate(int mUserId);
    }

    UserUsageStatsService(Context context, int userId, File usageStatsDir,
            StatsUpdatedListener listener) {
        mContext = context;
        mDailyExpiryDate = new UnixCalendar(0);
        mDatabase = new UsageStatsDatabase(usageStatsDir);
        mCurrentStats = new IntervalStats[INTERVAL_COUNT];
        mListener = listener;
        mLogPrefix = "User[" + Integer.toString(userId) + "] ";
        mUserId = userId;
    }

    void init(final long currentTimeMillis) {
        mDatabase.init(currentTimeMillis);

        int nullCount = 0;
        for (int i = 0; i < mCurrentStats.length; i++) {
            mCurrentStats[i] = mDatabase.getLatestUsageStats(i);
            if (mCurrentStats[i] == null) {
                // Find out how many intervals we don't have data for.
                // Ideally it should be all or none.
                nullCount++;
            }
        }

        if (nullCount > 0) {
            if (nullCount != mCurrentStats.length) {
                // This is weird, but we shouldn't fail if something like this
                // happens.
                Slog.w(TAG, mLogPrefix + "Some stats have no latest available");
            } else {
                // This must be first boot.
            }

            // By calling loadActiveStats, we will
            // generate new stats for each bucket.
            loadActiveStats(currentTimeMillis);
        } else {
            // Set up the expiry date to be one day from the latest daily stat.
            // This may actually be today and we will rollover on the first event
            // that is reported.
            updateRolloverDeadline();
        }

        // During system reboot, add a DEVICE_SHUTDOWN event to the end of event list, the timestamp
        // is last time UsageStatsDatabase is persisted to disk.
        final IntervalStats currentDailyStats = mCurrentStats[INTERVAL_DAILY];
        if (currentDailyStats != null) {
            final int size = currentDailyStats.events.size();
            if (size == 0 || currentDailyStats.events.get(size - 1).mEventType != DEVICE_SHUTDOWN) {
                // The last event in event list is not DEVICE_SHUTDOWN, then we insert one.
                final Event event = new Event(DEVICE_SHUTDOWN, currentDailyStats.lastTimeSaved);
                event.mPackage = Event.DEVICE_EVENT_PACKAGE_NAME;
                currentDailyStats.addEvent(event);
            }
        }

        if (mDatabase.isNewUpdate()) {
            notifyNewUpdate();
        }
    }

    void onTimeChanged(long oldTime, long newTime) {
        persistActiveStats();
        mDatabase.onTimeChanged(newTime - oldTime);
        loadActiveStats(newTime);
    }

    void reportEvent(Event event) {
        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "Got usage event for " + event.mPackage
                    + "[" + event.mTimeStamp + "]: "
                    + eventToString(event.mEventType));
        }

        mLastEvent = new Event(event);

        if (event.mTimeStamp >= mDailyExpiryDate.getTimeInMillis()) {
            // Need to rollover
            rolloverStats(event.mTimeStamp);
        }

        final IntervalStats currentDailyStats = mCurrentStats[INTERVAL_DAILY];

        final Configuration newFullConfig = event.mConfiguration;
        if (event.mEventType == Event.CONFIGURATION_CHANGE
                && currentDailyStats.activeConfiguration != null) {
            // Make the event configuration a delta.
            event.mConfiguration = Configuration.generateDelta(
                    currentDailyStats.activeConfiguration, newFullConfig);
        }

        if (event.mEventType != Event.SYSTEM_INTERACTION
                // ACTIVITY_DESTROYED is a private event. If there is preceding ACTIVITY_STOPPED
                // ACTIVITY_DESTROYED will be dropped. Otherwise it will be converted to
                // ACTIVITY_STOPPED.
                && event.mEventType != Event.ACTIVITY_DESTROYED
                // FLUSH_TO_DISK is a private event.
                && event.mEventType != Event.FLUSH_TO_DISK
                // DEVICE_SHUTDOWN is added to event list after reboot.
                && event.mEventType != Event.DEVICE_SHUTDOWN) {
            currentDailyStats.addEvent(event);
        }

        boolean incrementAppLaunch = false;
        if (event.mEventType == Event.ACTIVITY_RESUMED) {
            if (event.mPackage != null && !event.mPackage.equals(mLastBackgroundedPackage)) {
                incrementAppLaunch = true;
            }
        } else if (event.mEventType == Event.ACTIVITY_PAUSED) {
            if (event.mPackage != null) {
                mLastBackgroundedPackage = event.mPackage;
            }
        }

        for (IntervalStats stats : mCurrentStats) {
            switch (event.mEventType) {
                case Event.CONFIGURATION_CHANGE: {
                    stats.updateConfigurationStats(newFullConfig, event.mTimeStamp);
                } break;
                case Event.CHOOSER_ACTION: {
                    stats.updateChooserCounts(event.mPackage, event.mContentType, event.mAction);
                    String[] annotations = event.mContentAnnotations;
                    if (annotations != null) {
                        for (String annotation : annotations) {
                            stats.updateChooserCounts(event.mPackage, annotation, event.mAction);
                        }
                    }
                } break;
                case Event.SCREEN_INTERACTIVE: {
                    stats.updateScreenInteractive(event.mTimeStamp);
                } break;
                case Event.SCREEN_NON_INTERACTIVE: {
                    stats.updateScreenNonInteractive(event.mTimeStamp);
                } break;
                case Event.KEYGUARD_SHOWN: {
                    stats.updateKeyguardShown(event.mTimeStamp);
                } break;
                case Event.KEYGUARD_HIDDEN: {
                    stats.updateKeyguardHidden(event.mTimeStamp);
                } break;
                default: {
                    stats.update(event.mPackage, event.getClassName(),
                            event.mTimeStamp, event.mEventType, event.mInstanceId);
                    if (incrementAppLaunch) {
                        stats.incrementAppLaunchCount(event.mPackage);
                    }
                } break;
            }
        }

        notifyStatsChanged();
    }

    private static final StatCombiner<UsageStats> sUsageStatsCombiner =
            new StatCombiner<UsageStats>() {
                @Override
                public void combine(IntervalStats stats, boolean mutable,
                                    List<UsageStats> accResult) {
                    if (!mutable) {
                        accResult.addAll(stats.packageStats.values());
                        return;
                    }

                    final int statCount = stats.packageStats.size();
                    for (int i = 0; i < statCount; i++) {
                        accResult.add(new UsageStats(stats.packageStats.valueAt(i)));
                    }
                }
            };

    private static final StatCombiner<ConfigurationStats> sConfigStatsCombiner =
            new StatCombiner<ConfigurationStats>() {
                @Override
                public void combine(IntervalStats stats, boolean mutable,
                                    List<ConfigurationStats> accResult) {
                    if (!mutable) {
                        accResult.addAll(stats.configurations.values());
                        return;
                    }

                    final int configCount = stats.configurations.size();
                    for (int i = 0; i < configCount; i++) {
                        accResult.add(new ConfigurationStats(stats.configurations.valueAt(i)));
                    }
                }
            };

    private static final StatCombiner<EventStats> sEventStatsCombiner =
            new StatCombiner<EventStats>() {
                @Override
                public void combine(IntervalStats stats, boolean mutable,
                        List<EventStats> accResult) {
                    stats.addEventStatsTo(accResult);
                }
            };

    /**
     * Generic query method that selects the appropriate IntervalStats for the specified time range
     * and bucket, then calls the {@link com.android.server.usage.UsageStatsDatabase.StatCombiner}
     * provided to select the stats to use from the IntervalStats object.
     */
    private <T> List<T> queryStats(int intervalType, final long beginTime, final long endTime,
            StatCombiner<T> combiner) {
        if (intervalType == INTERVAL_BEST) {
            intervalType = mDatabase.findBestFitBucket(beginTime, endTime);
            if (intervalType < 0) {
                // Nothing saved to disk yet, so every stat is just as equal (no rollover has
                // occurred.
                intervalType = INTERVAL_DAILY;
            }
        }

        if (intervalType < 0 || intervalType >= mCurrentStats.length) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Bad intervalType used " + intervalType);
            }
            return null;
        }

        final IntervalStats currentStats = mCurrentStats[intervalType];

        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "SELECT * FROM " + intervalType + " WHERE beginTime >= "
                    + beginTime + " AND endTime < " + endTime);
        }

        if (beginTime >= currentStats.endTime) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Requesting stats after " + beginTime + " but latest is "
                        + currentStats.endTime);
            }

            // STOPSHIP: Temporary logging for b/110930764.
            if (intervalType == INTERVAL_DAILY
                    && mLastEvent != null && mLastEvent.mTimeStamp >= beginTime) {
                final IntervalStats diskStats = mDatabase.getLatestUsageStats(
                        INTERVAL_DAILY);
                StringBuilder sb = new StringBuilder(256);
                sb.append("Last 24 hours of UsageStats missing! timeRange : ");
                sb.append(beginTime);
                sb.append(", ");
                sb.append(endTime);
                sb.append("\nLast reported Usage Event time : ");
                sb.append(mLastEvent.mTimeStamp);
                if (currentStats == null) {
                    sb.append("\nNo in memory event stats available.");
                } else {
                    sb.append("\nLast in memory event time : ");
                    sb.append(currentStats.endTime);
                    sb.append("\nLast save time: ");
                    sb.append(currentStats.lastTimeSaved);
                }
                if (diskStats == null) {
                    sb.append("\nNo on disk event stats available.");
                } else {
                    sb.append("\nLast on disk event time : ");
                    sb.append(diskStats.endTime);
                }
                Slog.wtf(TAG, sb.toString());
            }

            // Nothing newer available.
            return null;
        }

        // Truncate the endTime to just before the in-memory stats. Then, we'll append the
        // in-memory stats to the results (if necessary) so as to avoid writing to disk too
        // often.
        final long truncatedEndTime = Math.min(currentStats.beginTime, endTime);

        // Get the stats from disk.
        List<T> results = mDatabase.queryUsageStats(intervalType, beginTime,
                truncatedEndTime, combiner);
        if (DEBUG) {
            Slog.d(TAG, "Got " + (results != null ? results.size() : 0) + " results from disk");
            Slog.d(TAG, "Current stats beginTime=" + currentStats.beginTime +
                    " endTime=" + currentStats.endTime);
        }

        // Now check if the in-memory stats match the range and add them if they do.
        if (beginTime < currentStats.endTime && endTime > currentStats.beginTime) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Returning in-memory stats");
            }

            if (results == null) {
                results = new ArrayList<>();
            }
            combiner.combine(currentStats, true, results);
        }

        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "Results: " + (results != null ? results.size() : 0));
        }
        return results;
    }

    List<UsageStats> queryUsageStats(int bucketType, long beginTime, long endTime) {
        return queryStats(bucketType, beginTime, endTime, sUsageStatsCombiner);
    }

    List<ConfigurationStats> queryConfigurationStats(int bucketType, long beginTime, long endTime) {
        return queryStats(bucketType, beginTime, endTime, sConfigStatsCombiner);
    }

    List<EventStats> queryEventStats(int bucketType, long beginTime, long endTime) {
        return queryStats(bucketType, beginTime, endTime, sEventStatsCombiner);
    }

    UsageEvents queryEvents(final long beginTime, final long endTime,
                            boolean obfuscateInstantApps) {
        final ArraySet<String> names = new ArraySet<>();
        List<Event> results = queryStats(INTERVAL_DAILY,
                beginTime, endTime, new StatCombiner<Event>() {
                    @Override
                    public void combine(IntervalStats stats, boolean mutable,
                            List<Event> accumulatedResult) {
                        final int startIndex = stats.events.firstIndexOnOrAfter(beginTime);
                        final int size = stats.events.size();
                        for (int i = startIndex; i < size; i++) {
                            if (stats.events.get(i).mTimeStamp >= endTime) {
                                return;
                            }

                            Event event = stats.events.get(i);
                            if (obfuscateInstantApps) {
                                event = event.getObfuscatedIfInstantApp();
                            }
                            if (event.mPackage != null) {
                                names.add(event.mPackage);
                            }
                            if (event.mClass != null) {
                                names.add(event.mClass);
                            }
                            if (event.mTaskRootPackage != null) {
                                names.add(event.mTaskRootPackage);
                            }
                            if (event.mTaskRootClass != null) {
                                names.add(event.mTaskRootClass);
                            }
                            accumulatedResult.add(event);
                        }
                    }
                });

        if (results == null || results.isEmpty()) {
            return null;
        }

        String[] table = names.toArray(new String[names.size()]);
        Arrays.sort(table);
        return new UsageEvents(results, table, true);
    }

    UsageEvents queryEventsForPackage(final long beginTime, final long endTime,
            final String packageName, boolean includeTaskRoot) {
        final ArraySet<String> names = new ArraySet<>();
        names.add(packageName);
        final List<Event> results = queryStats(INTERVAL_DAILY,
                beginTime, endTime, (stats, mutable, accumulatedResult) -> {
                    final int startIndex = stats.events.firstIndexOnOrAfter(beginTime);
                    final int size = stats.events.size();
                    for (int i = startIndex; i < size; i++) {
                        if (stats.events.get(i).mTimeStamp >= endTime) {
                            return;
                        }

                        final Event event = stats.events.get(i);
                        if (!packageName.equals(event.mPackage)) {
                            continue;
                        }
                        if (event.mClass != null) {
                            names.add(event.mClass);
                        }
                        if (includeTaskRoot && event.mTaskRootPackage != null) {
                            names.add(event.mTaskRootPackage);
                        }
                        if (includeTaskRoot && event.mTaskRootClass != null) {
                            names.add(event.mTaskRootClass);
                        }
                        accumulatedResult.add(event);
                    }
                });

        if (results == null || results.isEmpty()) {
            return null;
        }

        final String[] table = names.toArray(new String[names.size()]);
        Arrays.sort(table);
        return new UsageEvents(results, table, includeTaskRoot);
    }

    void persistActiveStats() {
        if (mStatsChanged) {
            Slog.i(TAG, mLogPrefix + "Flushing usage stats to disk");
            try {
                for (int i = 0; i < mCurrentStats.length; i++) {
                    mDatabase.putUsageStats(i, mCurrentStats[i]);
                }
                mStatsChanged = false;
            } catch (IOException e) {
                Slog.e(TAG, mLogPrefix + "Failed to persist active stats", e);
            }
        }
    }

    private void rolloverStats(final long currentTimeMillis) {
        final long startTime = SystemClock.elapsedRealtime();
        Slog.i(TAG, mLogPrefix + "Rolling over usage stats");

        // Finish any ongoing events with an END_OF_DAY or ROLLOVER_FOREGROUND_SERVICE event.
        // Make a note of which components need a new CONTINUE_PREVIOUS_DAY or
        // CONTINUING_FOREGROUND_SERVICE entry.
        final Configuration previousConfig =
                mCurrentStats[INTERVAL_DAILY].activeConfiguration;
        ArraySet<String> continuePkgs = new ArraySet<>();
        ArrayMap<String, SparseIntArray> continueActivity =
                new ArrayMap<>();
        ArrayMap<String, ArrayMap<String, Integer>> continueForegroundService =
                new ArrayMap<>();
        for (IntervalStats stat : mCurrentStats) {
            final int pkgCount = stat.packageStats.size();
            for (int i = 0; i < pkgCount; i++) {
                final UsageStats pkgStats = stat.packageStats.valueAt(i);
                if (pkgStats.mActivities.size() > 0
                        || !pkgStats.mForegroundServices.isEmpty()) {
                    if (pkgStats.mActivities.size() > 0) {
                        continueActivity.put(pkgStats.mPackageName,
                                pkgStats.mActivities);
                        stat.update(pkgStats.mPackageName, null,
                                mDailyExpiryDate.getTimeInMillis() - 1,
                                Event.END_OF_DAY, 0);
                    }
                    if (!pkgStats.mForegroundServices.isEmpty()) {
                        continueForegroundService.put(pkgStats.mPackageName,
                                pkgStats.mForegroundServices);
                        stat.update(pkgStats.mPackageName, null,
                                mDailyExpiryDate.getTimeInMillis() - 1,
                                Event.ROLLOVER_FOREGROUND_SERVICE, 0);
                    }
                    continuePkgs.add(pkgStats.mPackageName);
                    notifyStatsChanged();
                }
            }

            stat.updateConfigurationStats(null,
                    mDailyExpiryDate.getTimeInMillis() - 1);
            stat.commitTime(mDailyExpiryDate.getTimeInMillis() - 1);
        }

        persistActiveStats();
        mDatabase.prune(currentTimeMillis);
        loadActiveStats(currentTimeMillis);

        final int continueCount = continuePkgs.size();
        for (int i = 0; i < continueCount; i++) {
            String pkgName = continuePkgs.valueAt(i);
            final long beginTime = mCurrentStats[INTERVAL_DAILY].beginTime;
            for (IntervalStats stat : mCurrentStats) {
                if (continueActivity.containsKey(pkgName)) {
                    final SparseIntArray eventMap =
                            continueActivity.get(pkgName);
                    final int size = eventMap.size();
                    for (int j = 0; j < size; j++) {
                        stat.update(pkgName, null, beginTime,
                                eventMap.valueAt(j), eventMap.keyAt(j));
                    }
                }
                if (continueForegroundService.containsKey(pkgName)) {
                    final ArrayMap<String, Integer> eventMap =
                            continueForegroundService.get(pkgName);
                    final int size = eventMap.size();
                    for (int j = 0; j < size; j++) {
                        stat.update(pkgName, eventMap.keyAt(j), beginTime,
                                eventMap.valueAt(j), 0);
                    }
                }
                stat.updateConfigurationStats(previousConfig, beginTime);
                notifyStatsChanged();
            }
        }
        persistActiveStats();

        final long totalTime = SystemClock.elapsedRealtime() - startTime;
        Slog.i(TAG, mLogPrefix + "Rolling over usage stats complete. Took " + totalTime
                + " milliseconds");
    }

    private void notifyStatsChanged() {
        if (!mStatsChanged) {
            mStatsChanged = true;
            mListener.onStatsUpdated();
        }
    }

    private void notifyNewUpdate() {
        mListener.onNewUpdate(mUserId);
    }

    private void loadActiveStats(final long currentTimeMillis) {
        for (int intervalType = 0; intervalType < mCurrentStats.length; intervalType++) {
            final IntervalStats stats = mDatabase.getLatestUsageStats(intervalType);
            if (stats != null
                    && currentTimeMillis < stats.beginTime + INTERVAL_LENGTH[intervalType]) {
                if (DEBUG) {
                    Slog.d(TAG, mLogPrefix + "Loading existing stats @ " +
                            sDateFormat.format(stats.beginTime) + "(" + stats.beginTime +
                            ") for interval " + intervalType);
                }
                mCurrentStats[intervalType] = stats;
            } else {
                // No good fit remains.
                if (DEBUG) {
                    Slog.d(TAG, "Creating new stats @ " +
                            sDateFormat.format(currentTimeMillis) + "(" +
                            currentTimeMillis + ") for interval " + intervalType);
                }

                mCurrentStats[intervalType] = new IntervalStats();
                mCurrentStats[intervalType].beginTime = currentTimeMillis;
                mCurrentStats[intervalType].endTime = currentTimeMillis + 1;
            }
        }

        mStatsChanged = false;
        updateRolloverDeadline();

        // Tell the listener that the stats reloaded, which may have changed idle states.
        mListener.onStatsReloaded();
    }

    private void updateRolloverDeadline() {
        mDailyExpiryDate.setTimeInMillis(
                mCurrentStats[INTERVAL_DAILY].beginTime);
        mDailyExpiryDate.addDays(1);
        Slog.i(TAG, mLogPrefix + "Rollover scheduled @ " +
                sDateFormat.format(mDailyExpiryDate.getTimeInMillis()) + "(" +
                mDailyExpiryDate.getTimeInMillis() + ")");
    }

    //
    // -- DUMP related methods --
    //

    void checkin(final IndentingPrintWriter pw) {
        mDatabase.checkinDailyFiles(new UsageStatsDatabase.CheckinAction() {
            @Override
            public boolean checkin(IntervalStats stats) {
                printIntervalStats(pw, stats, false, false, null);
                return true;
            }
        });
    }

    void dump(IndentingPrintWriter pw, String pkg) {
        dump(pw, pkg, false);
    }
    void dump(IndentingPrintWriter pw, String pkg, boolean compact) {
        printLast24HrEvents(pw, !compact, pkg);
        for (int interval = 0; interval < mCurrentStats.length; interval++) {
            pw.print("In-memory ");
            pw.print(intervalToString(interval));
            pw.println(" stats");
            printIntervalStats(pw, mCurrentStats[interval], !compact, true, pkg);
        }
        mDatabase.dump(pw, compact);
    }

    static String formatDateTime(long dateTime, boolean pretty) {
        if (pretty) {
            return "\"" + sDateFormat.format(dateTime)+ "\"";
        }
        return Long.toString(dateTime);
    }

    private String formatElapsedTime(long elapsedTime, boolean pretty) {
        if (pretty) {
            return "\"" + DateUtils.formatElapsedTime(elapsedTime / 1000) + "\"";
        }
        return Long.toString(elapsedTime);
    }


    void printEvent(IndentingPrintWriter pw, Event event, boolean prettyDates) {
        pw.printPair("time", formatDateTime(event.mTimeStamp, prettyDates));
        pw.printPair("type", eventToString(event.mEventType));
        pw.printPair("package", event.mPackage);
        if (event.mClass != null) {
            pw.printPair("class", event.mClass);
        }
        if (event.mConfiguration != null) {
            pw.printPair("config", Configuration.resourceQualifierString(event.mConfiguration));
        }
        if (event.mShortcutId != null) {
            pw.printPair("shortcutId", event.mShortcutId);
        }
        if (event.mEventType == Event.STANDBY_BUCKET_CHANGED) {
            pw.printPair("standbyBucket", event.getStandbyBucket());
            pw.printPair("reason", UsageStatsManager.reasonToString(event.getStandbyReason()));
        } else if (event.mEventType == Event.ACTIVITY_RESUMED
                || event.mEventType == Event.ACTIVITY_PAUSED
                || event.mEventType == Event.ACTIVITY_STOPPED) {
            pw.printPair("instanceId", event.getInstanceId());
        }

        if (event.getTaskRootPackageName() != null) {
            pw.printPair("taskRootPackage", event.getTaskRootPackageName());
        }

        if (event.getTaskRootClassName() != null) {
            pw.printPair("taskRootClass", event.getTaskRootClassName());
        }

        if (event.mNotificationChannelId != null) {
            pw.printPair("channelId", event.mNotificationChannelId);
        }
        pw.printHexPair("flags", event.mFlags);
        pw.println();
    }

    void printLast24HrEvents(IndentingPrintWriter pw, boolean prettyDates, final String pkg) {
        final long endTime = System.currentTimeMillis();
        UnixCalendar yesterday = new UnixCalendar(endTime);
        yesterday.addDays(-1);

        final long beginTime = yesterday.getTimeInMillis();

        List<Event> events = queryStats(INTERVAL_DAILY,
                beginTime, endTime, new StatCombiner<Event>() {
                    @Override
                    public void combine(IntervalStats stats, boolean mutable,
                            List<Event> accumulatedResult) {
                        final int startIndex = stats.events.firstIndexOnOrAfter(beginTime);
                        final int size = stats.events.size();
                        for (int i = startIndex; i < size; i++) {
                            if (stats.events.get(i).mTimeStamp >= endTime) {
                                return;
                            }

                            Event event = stats.events.get(i);
                            if (pkg != null && !pkg.equals(event.mPackage)) {
                                continue;
                            }
                            accumulatedResult.add(event);
                        }
                    }
                });

        pw.print("Last 24 hour events (");
        if (prettyDates) {
            pw.printPair("timeRange", "\"" + DateUtils.formatDateRange(mContext,
                    beginTime, endTime, sDateFormatFlags) + "\"");
        } else {
            pw.printPair("beginTime", beginTime);
            pw.printPair("endTime", endTime);
        }
        pw.println(")");
        if (events != null) {
            pw.increaseIndent();
            for (Event event : events) {
                printEvent(pw, event, prettyDates);
            }
            pw.decreaseIndent();
        }
    }

    void printEventAggregation(IndentingPrintWriter pw, String label,
            IntervalStats.EventTracker tracker, boolean prettyDates) {
        if (tracker.count != 0 || tracker.duration != 0) {
            pw.print(label);
            pw.print(": ");
            pw.print(tracker.count);
            pw.print("x for ");
            pw.print(formatElapsedTime(tracker.duration, prettyDates));
            if (tracker.curStartTime != 0) {
                pw.print(" (now running, started at ");
                formatDateTime(tracker.curStartTime, prettyDates);
                pw.print(")");
            }
            pw.println();
        }
    }

    void printIntervalStats(IndentingPrintWriter pw, IntervalStats stats,
            boolean prettyDates, boolean skipEvents, String pkg) {
        if (prettyDates) {
            pw.printPair("timeRange", "\"" + DateUtils.formatDateRange(mContext,
                    stats.beginTime, stats.endTime, sDateFormatFlags) + "\"");
        } else {
            pw.printPair("beginTime", stats.beginTime);
            pw.printPair("endTime", stats.endTime);
        }
        pw.println();
        pw.increaseIndent();
        pw.println("packages");
        pw.increaseIndent();
        final ArrayMap<String, UsageStats> pkgStats = stats.packageStats;
        final int pkgCount = pkgStats.size();
        for (int i = 0; i < pkgCount; i++) {
            final UsageStats usageStats = pkgStats.valueAt(i);
            if (pkg != null && !pkg.equals(usageStats.mPackageName)) {
                continue;
            }
            pw.printPair("package", usageStats.mPackageName);
            pw.printPair("totalTimeUsed",
                    formatElapsedTime(usageStats.mTotalTimeInForeground, prettyDates));
            pw.printPair("lastTimeUsed", formatDateTime(usageStats.mLastTimeUsed, prettyDates));
            pw.printPair("totalTimeVisible",
                    formatElapsedTime(usageStats.mTotalTimeVisible, prettyDates));
            pw.printPair("lastTimeVisible",
                    formatDateTime(usageStats.mLastTimeVisible, prettyDates));
            pw.printPair("totalTimeFS",
                    formatElapsedTime(usageStats.mTotalTimeForegroundServiceUsed, prettyDates));
            pw.printPair("lastTimeFS",
                    formatDateTime(usageStats.mLastTimeForegroundServiceUsed, prettyDates));
            pw.printPair("appLaunchCount", usageStats.mAppLaunchCount);
            pw.println();
        }
        pw.decreaseIndent();

        pw.println();
        pw.println("ChooserCounts");
        pw.increaseIndent();
        for (UsageStats usageStats : pkgStats.values()) {
            if (pkg != null && !pkg.equals(usageStats.mPackageName)) {
                continue;
            }
            pw.printPair("package", usageStats.mPackageName);
            if (usageStats.mChooserCounts != null) {
                final int chooserCountSize = usageStats.mChooserCounts.size();
                for (int i = 0; i < chooserCountSize; i++) {
                    final String action = usageStats.mChooserCounts.keyAt(i);
                    final ArrayMap<String, Integer> counts = usageStats.mChooserCounts.valueAt(i);
                    final int annotationSize = counts.size();
                    for (int j = 0; j < annotationSize; j++) {
                        final String key = counts.keyAt(j);
                        final int count = counts.valueAt(j);
                        if (count != 0) {
                            pw.printPair("ChooserCounts", action + ":" + key + " is " +
                                    Integer.toString(count));
                            pw.println();
                        }
                    }
                }
            }
            pw.println();
        }
        pw.decreaseIndent();

        if (pkg == null) {
            pw.println("configurations");
            pw.increaseIndent();
            final ArrayMap<Configuration, ConfigurationStats> configStats = stats.configurations;
            final int configCount = configStats.size();
            for (int i = 0; i < configCount; i++) {
                final ConfigurationStats config = configStats.valueAt(i);
                pw.printPair("config", Configuration.resourceQualifierString(
                        config.mConfiguration));
                pw.printPair("totalTime", formatElapsedTime(config.mTotalTimeActive, prettyDates));
                pw.printPair("lastTime", formatDateTime(config.mLastTimeActive, prettyDates));
                pw.printPair("count", config.mActivationCount);
                pw.println();
            }
            pw.decreaseIndent();
            pw.println("event aggregations");
            pw.increaseIndent();
            printEventAggregation(pw, "screen-interactive", stats.interactiveTracker,
                    prettyDates);
            printEventAggregation(pw, "screen-non-interactive", stats.nonInteractiveTracker,
                    prettyDates);
            printEventAggregation(pw, "keyguard-shown", stats.keyguardShownTracker,
                    prettyDates);
            printEventAggregation(pw, "keyguard-hidden", stats.keyguardHiddenTracker,
                    prettyDates);
            pw.decreaseIndent();
        }

        // The last 24 hours of events is already printed in the non checkin dump
        // No need to repeat here.
        if (!skipEvents) {
            pw.println("events");
            pw.increaseIndent();
            final EventList events = stats.events;
            final int eventCount = events != null ? events.size() : 0;
            for (int i = 0; i < eventCount; i++) {
                final Event event = events.get(i);
                if (pkg != null && !pkg.equals(event.mPackage)) {
                    continue;
                }
                printEvent(pw, event, prettyDates);
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    public static String intervalToString(int interval) {
        switch (interval) {
            case INTERVAL_DAILY:
                return "daily";
            case INTERVAL_WEEKLY:
                return "weekly";
            case INTERVAL_MONTHLY:
                return "monthly";
            case INTERVAL_YEARLY:
                return "yearly";
            default:
                return "?";
        }
    }

    private static String eventToString(int eventType) {
        switch (eventType) {
            case Event.NONE:
                return "NONE";
            case Event.ACTIVITY_PAUSED:
                return "ACTIVITY_PAUSED";
            case Event.ACTIVITY_RESUMED:
                return "ACTIVITY_RESUMED";
            case Event.FOREGROUND_SERVICE_START:
                return "FOREGROUND_SERVICE_START";
            case Event.FOREGROUND_SERVICE_STOP:
                return "FOREGROUND_SERVICE_STOP";
            case Event.ACTIVITY_STOPPED:
                return "ACTIVITY_STOPPED";
            case Event.END_OF_DAY:
                return "END_OF_DAY";
            case Event.ROLLOVER_FOREGROUND_SERVICE:
                return "ROLLOVER_FOREGROUND_SERVICE";
            case Event.CONTINUE_PREVIOUS_DAY:
                return "CONTINUE_PREVIOUS_DAY";
            case Event.CONTINUING_FOREGROUND_SERVICE:
                return "CONTINUING_FOREGROUND_SERVICE";
            case Event.CONFIGURATION_CHANGE:
                return "CONFIGURATION_CHANGE";
            case Event.SYSTEM_INTERACTION:
                return "SYSTEM_INTERACTION";
            case Event.USER_INTERACTION:
                return "USER_INTERACTION";
            case Event.SHORTCUT_INVOCATION:
                return "SHORTCUT_INVOCATION";
            case Event.CHOOSER_ACTION:
                return "CHOOSER_ACTION";
            case Event.NOTIFICATION_SEEN:
                return "NOTIFICATION_SEEN";
            case Event.STANDBY_BUCKET_CHANGED:
                return "STANDBY_BUCKET_CHANGED";
            case Event.NOTIFICATION_INTERRUPTION:
                return "NOTIFICATION_INTERRUPTION";
            case Event.SLICE_PINNED:
                return "SLICE_PINNED";
            case Event.SLICE_PINNED_PRIV:
                return "SLICE_PINNED_PRIV";
            case Event.SCREEN_INTERACTIVE:
                return "SCREEN_INTERACTIVE";
            case Event.SCREEN_NON_INTERACTIVE:
                return "SCREEN_NON_INTERACTIVE";
            case Event.KEYGUARD_SHOWN:
                return "KEYGUARD_SHOWN";
            case Event.KEYGUARD_HIDDEN:
                return "KEYGUARD_HIDDEN";
            case Event.DEVICE_SHUTDOWN:
                return "DEVICE_SHUTDOWN";
            default:
                return "UNKNOWN_TYPE_" + eventType;
        }
    }

    byte[] getBackupPayload(String key){
        return mDatabase.getBackupPayload(key);
    }

    void applyRestoredPayload(String key, byte[] payload){
        mDatabase.applyRestoredPayload(key, payload);
    }
}
