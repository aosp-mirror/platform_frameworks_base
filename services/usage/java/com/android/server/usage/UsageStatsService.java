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

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.IUsageStatsManager;
import android.app.usage.PackageUsageStats;
import android.app.usage.TimeSparseArray;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArraySet;
import android.util.Slog;
import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;

public class UsageStatsService extends SystemService {
    static final String TAG = "UsageStatsService";

    static final boolean DEBUG = false;
    private static final long TEN_SECONDS = 10 * 1000;
    private static final long TWENTY_MINUTES = 20 * 60 * 1000;
    private static final long FLUSH_INTERVAL = DEBUG ? TEN_SECONDS : TWENTY_MINUTES;
    private static final int USAGE_STAT_RESULT_LIMIT = 10;
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // Handler message types.
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_FLUSH_TO_DISK = 1;

    final Object mLock = new Object();
    Handler mHandler;
    AppOpsManager mAppOps;

    private UsageStatsDatabase mDatabase;
    private UsageStats[] mCurrentStats = new UsageStats[UsageStatsManager.BUCKET_COUNT];
    private TimeSparseArray<UsageStats.Event> mCurrentEvents = new TimeSparseArray<>();
    private boolean mStatsChanged = false;
    private Calendar mDailyExpiryDate;

    public UsageStatsService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mDailyExpiryDate = Calendar.getInstance();
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mHandler = new H(BackgroundThread.get().getLooper());

        File systemDataDir = new File(Environment.getDataDirectory(), "system");
        mDatabase = new UsageStatsDatabase(new File(systemDataDir, "usagestats"));
        mDatabase.init();

        synchronized (mLock) {
            initLocked();
        }

        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishBinderService(Context.USAGE_STATS_SERVICE, new BinderService());
    }

    private void initLocked() {
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
                Slog.w(TAG, "Some stats have no latest available");
            } else {
                // This must be first boot.
            }

            // By calling loadActiveStatsLocked, we will
            // generate new stats for each bucket.
            loadActiveStatsLocked();
        } else {
            // Set up the expiry date to be one day from the latest daily stat.
            // This may actually be today and we will rollover on the first event
            // that is reported.
            mDailyExpiryDate.setTimeInMillis(
                    mCurrentStats[UsageStatsManager.DAILY_BUCKET].mBeginTimeStamp);
            mDailyExpiryDate.add(Calendar.DAY_OF_YEAR, 1);
            UsageStatsUtils.truncateDateTo(UsageStatsManager.DAILY_BUCKET, mDailyExpiryDate);
            Slog.i(TAG, "Rollover scheduled for " + sDateFormat.format(mDailyExpiryDate.getTime()));
        }

        // Now close off any events that were open at the time this was saved.
        for (UsageStats stat : mCurrentStats) {
            final int pkgCount = stat.getPackageCount();
            for (int i = 0; i < pkgCount; i++) {
                PackageUsageStats pkgStats = stat.getPackage(i);
                if (pkgStats.mLastEvent == UsageStats.Event.MOVE_TO_FOREGROUND ||
                        pkgStats.mLastEvent == UsageStats.Event.CONTINUE_PREVIOUS_DAY) {
                    updateStatsLocked(stat, pkgStats.mPackageName, stat.mLastTimeSaved,
                            UsageStats.Event.END_OF_DAY);
                    notifyStatsChangedLocked();
                }
            }
        }
    }

    private void rolloverStatsLocked() {
        final long startTime = System.currentTimeMillis();
        Slog.i(TAG, "Rolling over usage stats");

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
                    updateStatsLocked(stat, pkgStats.mPackageName,
                            mDailyExpiryDate.getTimeInMillis() - 1, UsageStats.Event.END_OF_DAY);
                    mStatsChanged = true;
                }
            }
        }

        persistActiveStatsLocked();
        mDatabase.prune();
        loadActiveStatsLocked();

        final int continueCount = continuePreviousDay.size();
        for (int i = 0; i < continueCount; i++) {
            String name = continuePreviousDay.valueAt(i);
            for (UsageStats stat : mCurrentStats) {
                updateStatsLocked(stat, name,
                        mCurrentStats[UsageStatsManager.DAILY_BUCKET].mBeginTimeStamp,
                        UsageStats.Event.CONTINUE_PREVIOUS_DAY);
                mStatsChanged = true;
            }
        }
        persistActiveStatsLocked();

        final long totalTime = System.currentTimeMillis() - startTime;
        Slog.i(TAG, "Rolling over usage stats complete. Took " + totalTime + " milliseconds");
    }

    private void notifyStatsChangedLocked() {
        if (!mStatsChanged) {
            mStatsChanged = true;
            mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
        }
    }

    /**
     * Called by the Bunder stub
     */
    void shutdown() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_REPORT_EVENT);
            mHandler.removeMessages(MSG_FLUSH_TO_DISK);
            persistActiveStatsLocked();
        }
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

    /**
     * Called by the Binder stub.
     */
    void reportEvent(UsageStats.Event event) {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Got usage event for " + event.packageName
                        + "[" + event.timeStamp + "]: "
                        + eventToString(event.eventType));
            }

            if (event.timeStamp >= mDailyExpiryDate.getTimeInMillis()) {
                // Need to rollover
                rolloverStatsLocked();
            }

            mCurrentEvents.append(event.timeStamp, event);

            for (UsageStats stats : mCurrentStats) {
                updateStatsLocked(stats, event.packageName, event.timeStamp, event.eventType);
            }
            notifyStatsChangedLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageStats[] getUsageStats(int bucketType, long beginTime) {
        if (bucketType < 0 || bucketType >= mCurrentStats.length) {
            return UsageStats.EMPTY_STATS;
        }

        final long timeNow = System.currentTimeMillis();
        if (beginTime > timeNow) {
            return UsageStats.EMPTY_STATS;
        }

        synchronized (mLock) {
            if (beginTime >= mCurrentStats[bucketType].mEndTimeStamp) {
                if (DEBUG) {
                    Slog.d(TAG, "Requesting stats after " + beginTime + " but latest is "
                            + mCurrentStats[bucketType].mEndTimeStamp);
                }
                // Nothing newer available.
                return UsageStats.EMPTY_STATS;
            } else if (beginTime >= mCurrentStats[bucketType].mBeginTimeStamp) {
                if (DEBUG) {
                    Slog.d(TAG, "Returning in-memory stats");
                }
                // Fast path for retrieving in-memory state.
                // TODO(adamlesinski): This copy just to parcel the object is wasteful.
                // It would be nice to parcel it here and send that back, but the Binder API
                // would need to change.
                return new UsageStats[] { new UsageStats(mCurrentStats[bucketType]) };
            } else {
                // Flush any changes that were made to disk before we do a disk query.
                persistActiveStatsLocked();
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "SELECT * FROM " + bucketType + " WHERE beginTime >= "
                    + beginTime + " LIMIT " + USAGE_STAT_RESULT_LIMIT);
        }

        UsageStats[] results = mDatabase.getUsageStats(bucketType, beginTime,
                USAGE_STAT_RESULT_LIMIT);

        if (DEBUG) {
            Slog.d(TAG, "Results: " + results.length);
        }
        return results;
    }

    /**
     * Called by the Binder stub.
     */
    UsageStats.Event[] getEvents(long time) {
        return UsageStats.Event.EMPTY_EVENTS;
    }

    private void loadActiveStatsLocked() {
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
        Slog.i(TAG, "Rollover scheduled for " + sDateFormat.format(mDailyExpiryDate.getTime()));
    }


    private void persistActiveStatsLocked() {
        if (mStatsChanged) {
            Slog.i(TAG, "Flushing usage stats to disk");
            try {
                for (int i = 0; i < mCurrentStats.length; i++) {
                    mDatabase.putUsageStats(i, mCurrentStats[i]);
                }
                mStatsChanged = false;
                mHandler.removeMessages(MSG_FLUSH_TO_DISK);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to persist active stats", e);
            }
        }
    }

    private void updateStatsLocked(UsageStats stats, String packageName, long timeStamp,
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

    class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_EVENT:
                    reportEvent((UsageStats.Event) msg.obj);
                    break;

                case MSG_FLUSH_TO_DISK:
                    synchronized (mLock) {
                        persistActiveStatsLocked();
                    }
                    break;

                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    private class BinderService extends IUsageStatsManager.Stub {

        private boolean hasPermission(String callingPackage) {
            final int mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS,
                    Binder.getCallingUid(), callingPackage);
            if (mode == AppOpsManager.MODE_IGNORED) {
                // If AppOpsManager ignores this, still allow if we have the system level
                // permission.
                return getContext().checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        }

        @Override
        public UsageStats[] getStatsSince(int bucketType, long time, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return UsageStats.EMPTY_STATS;
            }

            long token = Binder.clearCallingIdentity();
            try {
                return getUsageStats(bucketType, time);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public UsageStats.Event[] getEventsSince(long time, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return UsageStats.Event.EMPTY_EVENTS;
            }

            long token = Binder.clearCallingIdentity();
            try {
                return getEvents(time);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /**
     * This local service implementation is primarily used by ActivityManagerService.
     * ActivityManagerService will call these methods holding the 'am' lock, which means we
     * shouldn't be doing any IO work or other long running tasks in these methods.
     */
    private class LocalService extends UsageStatsManagerInternal {

        @Override
        public void reportEvent(ComponentName component, long timeStamp, int eventType) {
            UsageStats.Event event = new UsageStats.Event(component.getPackageName(), timeStamp,
                    eventType);
            mHandler.obtainMessage(MSG_REPORT_EVENT, event).sendToTarget();
        }

        @Override
        public void prepareShutdown() {
            // This method *WILL* do IO work, but we must block until it is finished or else
            // we might not shutdown cleanly. This is ok to do with the 'am' lock held, because
            // we are shutting down.
            shutdown();
        }
    }
}
