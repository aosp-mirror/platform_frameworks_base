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

import android.app.usage.ConfigurationStats;
import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;

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

    private final UsageStatsDatabase mDatabase;
    private final IntervalStats[] mCurrentStats;
    private boolean mStatsChanged = false;
    private final UnixCalendar mDailyExpiryDate;
    private final StatsUpdatedListener mListener;
    private final String mLogPrefix;

    interface StatsUpdatedListener {
        void onStatsUpdated();
    }

    UserUsageStatsService(int userId, File usageStatsDir, StatsUpdatedListener listener) {
        mDailyExpiryDate = new UnixCalendar(0);
        mDatabase = new UsageStatsDatabase(usageStatsDir);
        mCurrentStats = new IntervalStats[UsageStatsManager.INTERVAL_COUNT];
        mListener = listener;
        mLogPrefix = "User[" + Integer.toString(userId) + "] ";
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
            loadActiveStats(currentTimeMillis, false);
        } else {
            // Set up the expiry date to be one day from the latest daily stat.
            // This may actually be today and we will rollover on the first event
            // that is reported.
            mDailyExpiryDate.setTimeInMillis(
                    mCurrentStats[UsageStatsManager.INTERVAL_DAILY].beginTime);
            mDailyExpiryDate.addDays(1);
            mDailyExpiryDate.truncateToDay();
            Slog.i(TAG, mLogPrefix + "Rollover scheduled @ " +
                    sDateFormat.format(mDailyExpiryDate.getTimeInMillis()) +
                    "(" + mDailyExpiryDate.getTimeInMillis() + ")");
        }

        // Now close off any events that were open at the time this was saved.
        for (IntervalStats stat : mCurrentStats) {
            final int pkgCount = stat.packageStats.size();
            for (int i = 0; i < pkgCount; i++) {
                UsageStats pkgStats = stat.packageStats.valueAt(i);
                if (pkgStats.mLastEvent == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        pkgStats.mLastEvent == UsageEvents.Event.CONTINUE_PREVIOUS_DAY) {
                    stat.update(pkgStats.mPackageName, stat.lastTimeSaved,
                            UsageEvents.Event.END_OF_DAY);
                    notifyStatsChanged();
                }
            }

            stat.updateConfigurationStats(null, stat.lastTimeSaved);
        }
    }

    void onTimeChanged(long oldTime, long newTime) {
        persistActiveStats();
        mDatabase.onTimeChanged(newTime - oldTime);
        loadActiveStats(newTime, true);
    }

    void reportEvent(UsageEvents.Event event) {
        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "Got usage event for " + event.mPackage
                    + "[" + event.mTimeStamp + "]: "
                    + eventToString(event.mEventType));
        }

        if (event.mTimeStamp >= mDailyExpiryDate.getTimeInMillis()) {
            // Need to rollover
            rolloverStats(event.mTimeStamp);
        }

        final IntervalStats currentDailyStats = mCurrentStats[UsageStatsManager.INTERVAL_DAILY];

        final Configuration newFullConfig = event.mConfiguration;
        if (event.mEventType == UsageEvents.Event.CONFIGURATION_CHANGE &&
                currentDailyStats.activeConfiguration != null) {
            // Make the event configuration a delta.
            event.mConfiguration = Configuration.generateDelta(
                    currentDailyStats.activeConfiguration, newFullConfig);
        }

        // Add the event to the daily list.
        if (currentDailyStats.events == null) {
            currentDailyStats.events = new TimeSparseArray<>();
        }
        currentDailyStats.events.put(event.mTimeStamp, event);

        for (IntervalStats stats : mCurrentStats) {
            if (event.mEventType == UsageEvents.Event.CONFIGURATION_CHANGE) {
                stats.updateConfigurationStats(newFullConfig, event.mTimeStamp);
            } else {
                stats.update(event.mPackage, event.mTimeStamp, event.mEventType);
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

    /**
     * Generic query method that selects the appropriate IntervalStats for the specified time range
     * and bucket, then calls the {@link com.android.server.usage.UsageStatsDatabase.StatCombiner}
     * provided to select the stats to use from the IntervalStats object.
     */
    private <T> List<T> queryStats(int intervalType, final long beginTime, final long endTime,
            StatCombiner<T> combiner) {
        if (intervalType == UsageStatsManager.INTERVAL_BEST) {
            intervalType = mDatabase.findBestFitBucket(beginTime, endTime);
            if (intervalType < 0) {
                // Nothing saved to disk yet, so every stat is just as equal (no rollover has
                // occurred.
                intervalType = UsageStatsManager.INTERVAL_DAILY;
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

    UsageEvents queryEvents(final long beginTime, final long endTime) {
        final ArraySet<String> names = new ArraySet<>();
        List<UsageEvents.Event> results = queryStats(UsageStatsManager.INTERVAL_DAILY,
                beginTime, endTime, new StatCombiner<UsageEvents.Event>() {
                    @Override
                    public void combine(IntervalStats stats, boolean mutable,
                            List<UsageEvents.Event> accumulatedResult) {
                        if (stats.events == null) {
                            return;
                        }

                        final int startIndex = stats.events.closestIndexOnOrAfter(beginTime);
                        if (startIndex < 0) {
                            return;
                        }

                        final int size = stats.events.size();
                        for (int i = startIndex; i < size; i++) {
                            if (stats.events.keyAt(i) >= endTime) {
                                return;
                            }

                            final UsageEvents.Event event = stats.events.valueAt(i);
                            names.add(event.mPackage);
                            if (event.mClass != null) {
                                names.add(event.mClass);
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
        return new UsageEvents(results, table);
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

        // Finish any ongoing events with an END_OF_DAY event. Make a note of which components
        // need a new CONTINUE_PREVIOUS_DAY entry.
        final Configuration previousConfig =
                mCurrentStats[UsageStatsManager.INTERVAL_DAILY].activeConfiguration;
        ArraySet<String> continuePreviousDay = new ArraySet<>();
        for (IntervalStats stat : mCurrentStats) {
            final int pkgCount = stat.packageStats.size();
            for (int i = 0; i < pkgCount; i++) {
                UsageStats pkgStats = stat.packageStats.valueAt(i);
                if (pkgStats.mLastEvent == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        pkgStats.mLastEvent == UsageEvents.Event.CONTINUE_PREVIOUS_DAY) {
                    continuePreviousDay.add(pkgStats.mPackageName);
                    stat.update(pkgStats.mPackageName, mDailyExpiryDate.getTimeInMillis() - 1,
                            UsageEvents.Event.END_OF_DAY);
                    notifyStatsChanged();
                }
            }

            stat.updateConfigurationStats(null, mDailyExpiryDate.getTimeInMillis() - 1);
        }

        persistActiveStats();
        mDatabase.prune(currentTimeMillis);
        loadActiveStats(currentTimeMillis, false);

        final int continueCount = continuePreviousDay.size();
        for (int i = 0; i < continueCount; i++) {
            String name = continuePreviousDay.valueAt(i);
            final long beginTime = mCurrentStats[UsageStatsManager.INTERVAL_DAILY].beginTime;
            for (IntervalStats stat : mCurrentStats) {
                stat.update(name, beginTime, UsageEvents.Event.CONTINUE_PREVIOUS_DAY);
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

    /**
     * @param force To force all in-memory stats to be reloaded.
     */
    private void loadActiveStats(final long currentTimeMillis, boolean force) {
        final UnixCalendar tempCal = mDailyExpiryDate;
        for (int intervalType = 0; intervalType < mCurrentStats.length; intervalType++) {
            tempCal.setTimeInMillis(currentTimeMillis);
            UnixCalendar.truncateTo(tempCal, intervalType);

            if (!force && mCurrentStats[intervalType] != null &&
                    mCurrentStats[intervalType].beginTime == tempCal.getTimeInMillis()) {
                // These are the same, no need to load them (in memory stats are always newer
                // than persisted stats).
                continue;
            }

            final long lastBeginTime = mDatabase.getLatestUsageStatsBeginTime(intervalType);
            if (lastBeginTime >= tempCal.getTimeInMillis()) {
                if (DEBUG) {
                    Slog.d(TAG, mLogPrefix + "Loading existing stats @ " +
                            sDateFormat.format(lastBeginTime) + "(" + lastBeginTime +
                            ") for interval " + intervalType);
                }
                mCurrentStats[intervalType] = mDatabase.getLatestUsageStats(intervalType);
            } else {
                mCurrentStats[intervalType] = null;
            }

            if (mCurrentStats[intervalType] == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Creating new stats @ " +
                            sDateFormat.format(tempCal.getTimeInMillis()) + "(" +
                            tempCal.getTimeInMillis() + ") for interval " + intervalType);

                }
                mCurrentStats[intervalType] = new IntervalStats();
                mCurrentStats[intervalType].beginTime = tempCal.getTimeInMillis();
                mCurrentStats[intervalType].endTime = currentTimeMillis;
            }
        }
        mStatsChanged = false;
        mDailyExpiryDate.setTimeInMillis(currentTimeMillis);
        mDailyExpiryDate.addDays(1);
        mDailyExpiryDate.truncateToDay();
        Slog.i(TAG, mLogPrefix + "Rollover scheduled @ " +
                sDateFormat.format(mDailyExpiryDate.getTimeInMillis()) + "(" +
                tempCal.getTimeInMillis() + ")");
    }


    private static String eventToString(int eventType) {
        switch (eventType) {
            case UsageEvents.Event.NONE:
                return "NONE";
            case UsageEvents.Event.MOVE_TO_BACKGROUND:
                return "MOVE_TO_BACKGROUND";
            case UsageEvents.Event.MOVE_TO_FOREGROUND:
                return "MOVE_TO_FOREGROUND";
            case UsageEvents.Event.END_OF_DAY:
                return "END_OF_DAY";
            case UsageEvents.Event.CONTINUE_PREVIOUS_DAY:
                return "CONTINUE_PREVIOUS_DAY";
            case UsageEvents.Event.CONFIGURATION_CHANGE:
                return "CONFIGURATION_CHANGE";
            default:
                return "UNKNOWN";
        }
    }
}
