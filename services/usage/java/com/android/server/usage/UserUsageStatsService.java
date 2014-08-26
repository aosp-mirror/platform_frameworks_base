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

import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.util.ArraySet;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
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
    private final Calendar mDailyExpiryDate;
    private final StatsUpdatedListener mListener;
    private final String mLogPrefix;

    interface StatsUpdatedListener {
        void onStatsUpdated();
    }

    UserUsageStatsService(int userId, File usageStatsDir, StatsUpdatedListener listener) {
        mDailyExpiryDate = Calendar.getInstance();
        mDatabase = new UsageStatsDatabase(usageStatsDir);
        mCurrentStats = new IntervalStats[UsageStatsManager.INTERVAL_COUNT];
        mListener = listener;
        mLogPrefix = "User[" + Integer.toString(userId) + "] ";
    }

    void init() {
        mDatabase.init();

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
            loadActiveStats();
        } else {
            // Set up the expiry date to be one day from the latest daily stat.
            // This may actually be today and we will rollover on the first event
            // that is reported.
            mDailyExpiryDate.setTimeInMillis(
                    mCurrentStats[UsageStatsManager.INTERVAL_DAILY].beginTime);
            mDailyExpiryDate.add(Calendar.DAY_OF_YEAR, 1);
            UsageStatsUtils.truncateDateTo(UsageStatsManager.INTERVAL_DAILY, mDailyExpiryDate);
            Slog.i(TAG, mLogPrefix + "Rollover scheduled for "
                    + sDateFormat.format(mDailyExpiryDate.getTime()));
        }

        // Now close off any events that were open at the time this was saved.
        for (IntervalStats stat : mCurrentStats) {
            final int pkgCount = stat.stats.size();
            for (int i = 0; i < pkgCount; i++) {
                UsageStats pkgStats = stat.stats.valueAt(i);
                if (pkgStats.mLastEvent == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        pkgStats.mLastEvent == UsageEvents.Event.CONTINUE_PREVIOUS_DAY) {
                    stat.update(pkgStats.mPackageName, stat.lastTimeSaved,
                            UsageEvents.Event.END_OF_DAY);
                    notifyStatsChanged();
                }
            }
        }
    }

    void reportEvent(UsageEvents.Event event) {
        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "Got usage event for " + event.mPackage
                    + "[" + event.getTimeStamp() + "]: "
                    + eventToString(event.getEventType()));
        }

        if (event.getTimeStamp() >= mDailyExpiryDate.getTimeInMillis()) {
            // Need to rollover
            rolloverStats();
        }

        if (mCurrentStats[UsageStatsManager.INTERVAL_DAILY].events == null) {
            mCurrentStats[UsageStatsManager.INTERVAL_DAILY].events = new TimeSparseArray<>();
        }
        mCurrentStats[UsageStatsManager.INTERVAL_DAILY].events.put(event.getTimeStamp(), event);

        for (IntervalStats stats : mCurrentStats) {
            stats.update(event.mPackage, event.getTimeStamp(),
                    event.getEventType());
        }

        notifyStatsChanged();
    }

    List<UsageStats> queryUsageStats(int bucketType, long beginTime, long endTime) {
        if (bucketType == UsageStatsManager.INTERVAL_BEST) {
            bucketType = mDatabase.findBestFitBucket(beginTime, endTime);
        }

        if (bucketType < 0 || bucketType >= mCurrentStats.length) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Bad bucketType used " + bucketType);
            }
            return null;
        }

        if (beginTime >= mCurrentStats[bucketType].endTime) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Requesting stats after " + beginTime + " but latest is "
                        + mCurrentStats[bucketType].endTime);
            }
            // Nothing newer available.
            return null;

        } else if (beginTime >= mCurrentStats[bucketType].beginTime) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Returning in-memory stats for bucket " + bucketType);
            }
            // Fast path for retrieving in-memory state.
            ArrayList<UsageStats> results = new ArrayList<>();
            final int packageCount = mCurrentStats[bucketType].stats.size();
            for (int i = 0; i < packageCount; i++) {
                results.add(new UsageStats(mCurrentStats[bucketType].stats.valueAt(i)));
            }
            return results;
        }

        // Flush any changes that were made to disk before we do a disk query.
        // If we're not grabbing the ongoing stats, no need to persist.
        persistActiveStats();

        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "SELECT * FROM " + bucketType + " WHERE beginTime >= "
                    + beginTime + " AND endTime < " + endTime);
        }

        final List<UsageStats> results = mDatabase.queryUsageStats(bucketType, beginTime, endTime);
        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "Results: " + (results == null ? 0 : results.size()));
        }
        return results;
    }

    UsageEvents queryEvents(long beginTime, long endTime) {
        if (endTime > mCurrentStats[UsageStatsManager.INTERVAL_DAILY].beginTime) {
            if (beginTime > mCurrentStats[UsageStatsManager.INTERVAL_DAILY].endTime) {
                return null;
            }

            TimeSparseArray<UsageEvents.Event> events =
                    mCurrentStats[UsageStatsManager.INTERVAL_DAILY].events;
            if (events == null) {
                return null;
            }

            final int startIndex = events.closestIndexOnOrAfter(beginTime);
            if (startIndex < 0) {
                return null;
            }

            ArraySet<String> names = new ArraySet<>();
            ArrayList<UsageEvents.Event> results = new ArrayList<>();
            final int size = events.size();
            for (int i = startIndex; i < size; i++) {
                if (events.keyAt(i) >= endTime) {
                    break;
                }
                final UsageEvents.Event event = events.valueAt(i);
                names.add(event.mPackage);
                if (event.mClass != null) {
                    names.add(event.mClass);
                }
                results.add(event);
            }
            String[] table = names.toArray(new String[names.size()]);
            Arrays.sort(table);
            return new UsageEvents(results, table);
        }

        // TODO(adamlesinski): Query the previous days.
        return null;
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

    private void rolloverStats() {
        final long startTime = System.currentTimeMillis();
        Slog.i(TAG, mLogPrefix + "Rolling over usage stats");

        // Finish any ongoing events with an END_OF_DAY event. Make a note of which components
        // need a new CONTINUE_PREVIOUS_DAY entry.
        ArraySet<String> continuePreviousDay = new ArraySet<>();
        for (IntervalStats stat : mCurrentStats) {
            final int pkgCount = stat.stats.size();
            for (int i = 0; i < pkgCount; i++) {
                UsageStats pkgStats = stat.stats.valueAt(i);
                if (pkgStats.mLastEvent == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        pkgStats.mLastEvent == UsageEvents.Event.CONTINUE_PREVIOUS_DAY) {
                    continuePreviousDay.add(pkgStats.mPackageName);
                    stat.update(pkgStats.mPackageName,
                            mDailyExpiryDate.getTimeInMillis() - 1, UsageEvents.Event.END_OF_DAY);
                    mStatsChanged = true;
                }
            }
        }

        persistActiveStats();
        mDatabase.prune();
        loadActiveStats();

        final int continueCount = continuePreviousDay.size();
        for (int i = 0; i < continueCount; i++) {
            String name = continuePreviousDay.valueAt(i);
            for (IntervalStats stat : mCurrentStats) {
                stat.update(name, mCurrentStats[UsageStatsManager.INTERVAL_DAILY].beginTime,
                        UsageEvents.Event.CONTINUE_PREVIOUS_DAY);
                mStatsChanged = true;
            }
        }
        persistActiveStats();

        final long totalTime = System.currentTimeMillis() - startTime;
        Slog.i(TAG, mLogPrefix + "Rolling over usage stats complete. Took " + totalTime
                + " milliseconds");
    }

    private void notifyStatsChanged() {
        if (!mStatsChanged) {
            mStatsChanged = true;
            mListener.onStatsUpdated();
        }
    }

    private void loadActiveStats() {
        final long timeNow = System.currentTimeMillis();

        Calendar tempCal = mDailyExpiryDate;
        for (int bucketType = 0; bucketType < mCurrentStats.length; bucketType++) {
            tempCal.setTimeInMillis(timeNow);
            UsageStatsUtils.truncateDateTo(bucketType, tempCal);

            if (mCurrentStats[bucketType] != null &&
                    mCurrentStats[bucketType].beginTime == tempCal.getTimeInMillis()) {
                // These are the same, no need to load them (in memory stats are always newer
                // than persisted stats).
                continue;
            }

            final long lastBeginTime = mDatabase.getLatestUsageStatsBeginTime(bucketType);
            if (lastBeginTime >= tempCal.getTimeInMillis()) {
                if (DEBUG) {
                    Slog.d(TAG, mLogPrefix + "Loading existing stats (" + lastBeginTime +
                            ") for bucket " + bucketType);
                }
                mCurrentStats[bucketType] = mDatabase.getLatestUsageStats(bucketType);
                if (DEBUG) {
                    if (mCurrentStats[bucketType] != null) {
                        Slog.d(TAG, mLogPrefix + "Found " +
                                (mCurrentStats[bucketType].events == null ?
                                        0 : mCurrentStats[bucketType].events.size()) +
                                " events");
                    }
                }
            } else {
                mCurrentStats[bucketType] = null;
            }

            if (mCurrentStats[bucketType] == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Creating new stats (" + tempCal.getTimeInMillis() +
                            ") for bucket " + bucketType);

                }
                mCurrentStats[bucketType] = new IntervalStats();
                mCurrentStats[bucketType].beginTime = tempCal.getTimeInMillis();
                mCurrentStats[bucketType].endTime = timeNow;
            }
        }
        mStatsChanged = false;
        mDailyExpiryDate.setTimeInMillis(timeNow);
        mDailyExpiryDate.add(Calendar.DAY_OF_YEAR, 1);
        UsageStatsUtils.truncateDateTo(UsageStatsManager.INTERVAL_DAILY, mDailyExpiryDate);
        Slog.i(TAG, mLogPrefix + "Rollover scheduled for "
                + sDateFormat.format(mDailyExpiryDate.getTime()));
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
            default:
                return "UNKNOWN";
        }
    }
}
