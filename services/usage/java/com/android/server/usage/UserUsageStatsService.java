package com.android.server.usage;

import android.app.usage.PackageUsageStats;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.util.ArraySet;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * A per-user UsageStatsService. All methods are meant to be called with the main lock held
 * in UsageStatsService.
 */
class UserUsageStatsService {
    private static final String TAG = "UsageStatsService";
    private static final boolean DEBUG = UsageStatsService.DEBUG;
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final UsageStatsDatabase mDatabase;
    private final UsageStats[] mCurrentStats = new UsageStats[UsageStatsManager.BUCKET_COUNT];
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
        mListener = listener;
        mLogPrefix = "User[" + Integer.toString(userId) + "] ";
    }

    void init() {
        mDatabase.init();

        int nullCount = 0;
        for (int i = 0; i < mCurrentStats.length; i++) {
            mCurrentStats[i] = mDatabase.getLatestUsageStats(i);
            if (mCurrentStats[i] == null) {
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
                    mCurrentStats[UsageStatsManager.DAILY_BUCKET].mBeginTimeStamp);
            mDailyExpiryDate.add(Calendar.DAY_OF_YEAR, 1);
            UsageStatsUtils.truncateDateTo(UsageStatsManager.DAILY_BUCKET, mDailyExpiryDate);
            Slog.i(TAG, mLogPrefix + "Rollover scheduled for "
                    + sDateFormat.format(mDailyExpiryDate.getTime()));
        }

        // Now close off any events that were open at the time this was saved.
        for (UsageStats stat : mCurrentStats) {
            final int pkgCount = stat.getPackageCount();
            for (int i = 0; i < pkgCount; i++) {
                PackageUsageStats pkgStats = stat.getPackage(i);
                if (pkgStats.mLastEvent == UsageStats.Event.MOVE_TO_FOREGROUND ||
                        pkgStats.mLastEvent == UsageStats.Event.CONTINUE_PREVIOUS_DAY) {
                    updateStats(stat, pkgStats.mPackageName, stat.mLastTimeSaved,
                            UsageStats.Event.END_OF_DAY);
                    notifyStatsChanged();
                }
            }
        }
    }

    void reportEvent(UsageStats.Event event) {
        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "Got usage event for " + event.packageName
                    + "[" + event.timeStamp + "]: "
                    + eventToString(event.eventType));
        }

        if (event.timeStamp >= mDailyExpiryDate.getTimeInMillis()) {
            // Need to rollover
            rolloverStats();
        }

        for (UsageStats stats : mCurrentStats) {
            updateStats(stats, event.packageName, event.timeStamp, event.eventType);
        }

        notifyStatsChanged();
    }

    UsageStats[] getUsageStats(int bucketType, long beginTime) {
        if (beginTime >= mCurrentStats[bucketType].mEndTimeStamp) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Requesting stats after " + beginTime + " but latest is "
                        + mCurrentStats[bucketType].mEndTimeStamp);
            }
            // Nothing newer available.
            return UsageStats.EMPTY_STATS;

        } else if (beginTime >= mCurrentStats[bucketType].mBeginTimeStamp) {
            if (DEBUG) {
                Slog.d(TAG, mLogPrefix + "Returning in-memory stats");
            }
            // Fast path for retrieving in-memory state.
            // TODO(adamlesinski): This copy just to parcel the object is wasteful.
            // It would be nice to parcel it here and send that back, but the Binder API
            // would need to change.
            return new UsageStats[] { new UsageStats(mCurrentStats[bucketType]) };

        } else {
            // Flush any changes that were made to disk before we do a disk query.
            persistActiveStats();
        }

        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "SELECT * FROM " + bucketType + " WHERE beginTime >= "
                    + beginTime + " LIMIT " + UsageStatsService.USAGE_STAT_RESULT_LIMIT);
        }

        final UsageStats[] results = mDatabase.getUsageStats(bucketType, beginTime,
                UsageStatsService.USAGE_STAT_RESULT_LIMIT);

        if (DEBUG) {
            Slog.d(TAG, mLogPrefix + "Results: " + results.length);
        }
        return results;
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
        for (UsageStats stat : mCurrentStats) {
            final int pkgCount = stat.getPackageCount();
            for (int i = 0; i < pkgCount; i++) {
                PackageUsageStats pkgStats = stat.getPackage(i);
                if (pkgStats.mLastEvent == UsageStats.Event.MOVE_TO_FOREGROUND ||
                        pkgStats.mLastEvent == UsageStats.Event.CONTINUE_PREVIOUS_DAY) {
                    continuePreviousDay.add(pkgStats.mPackageName);
                    updateStats(stat, pkgStats.mPackageName,
                            mDailyExpiryDate.getTimeInMillis() - 1, UsageStats.Event.END_OF_DAY);
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
            for (UsageStats stat : mCurrentStats) {
                updateStats(stat, name,
                        mCurrentStats[UsageStatsManager.DAILY_BUCKET].mBeginTimeStamp,
                        UsageStats.Event.CONTINUE_PREVIOUS_DAY);
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
        for (int i = 0; i < mCurrentStats.length; i++) {
            tempCal.setTimeInMillis(timeNow);
            UsageStatsUtils.truncateDateTo(i, tempCal);

            if (mCurrentStats[i] != null &&
                    mCurrentStats[i].mBeginTimeStamp == tempCal.getTimeInMillis()) {
                // These are the same, no need to load them (in memory stats are always newer
                // than persisted stats).
                continue;
            }

            UsageStats[] stats = mDatabase.getUsageStats(i, timeNow, 1);
            if (stats != null && stats.length > 0) {
                mCurrentStats[i] = stats[stats.length - 1];
            } else {
                mCurrentStats[i] = UsageStats.create(tempCal.getTimeInMillis(), timeNow);
            }
        }
        mStatsChanged = false;
        mDailyExpiryDate.setTimeInMillis(timeNow);
        mDailyExpiryDate.add(Calendar.DAY_OF_YEAR, 1);
        UsageStatsUtils.truncateDateTo(UsageStatsManager.DAILY_BUCKET, mDailyExpiryDate);
        Slog.i(TAG, mLogPrefix + "Rollover scheduled for "
                + sDateFormat.format(mDailyExpiryDate.getTime()));
    }

    private void updateStats(UsageStats stats, String packageName, long timeStamp,
            int eventType) {
        PackageUsageStats pkgStats = stats.getOrCreatePackageUsageStats(packageName);

        // TODO(adamlesinski): Ensure that we recover from incorrect event sequences
        // like double MOVE_TO_BACKGROUND, etc.
        if (eventType == UsageStats.Event.MOVE_TO_BACKGROUND ||
                eventType == UsageStats.Event.END_OF_DAY) {
            if (pkgStats.mLastEvent == UsageStats.Event.MOVE_TO_FOREGROUND ||
                    pkgStats.mLastEvent == UsageStats.Event.CONTINUE_PREVIOUS_DAY) {
                pkgStats.mTotalTimeSpent += timeStamp - pkgStats.mLastTimeUsed;
            }
        }
        pkgStats.mLastEvent = eventType;
        pkgStats.mLastTimeUsed = timeStamp;
        stats.mEndTimeStamp = timeStamp;
    }

    private static String eventToString(int eventType) {
        switch (eventType) {
            case UsageStats.Event.NONE:
                return "NONE";
            case UsageStats.Event.MOVE_TO_BACKGROUND:
                return "MOVE_TO_BACKGROUND";
            case UsageStats.Event.MOVE_TO_FOREGROUND:
                return "MOVE_TO_FOREGROUND";
            case UsageStats.Event.END_OF_DAY:
                return "END_OF_DAY";
            case UsageStats.Event.CONTINUE_PREVIOUS_DAY:
                return "CONTINUE_PREVIOUS_DAY";
            default:
                return "UNKNOWN";
        }
    }
}
