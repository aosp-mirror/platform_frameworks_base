/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.job.controllers;

import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.NEVER_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.IUidObserver;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Controller that tracks whether an app has exceeded its standby bucket quota.
 *
 * Each job in each bucket is given 10 minutes to run within its respective time window. Active
 * jobs can run indefinitely, working set jobs can run for 10 minutes within a 2 hour window,
 * frequent jobs get to run 10 minutes in an 8 hour window, and rare jobs get to run 10 minutes in
 * a 24 hour window. The windows are rolling, so as soon as a job would have some quota based on its
 * bucket, it will be eligible to run. When a job's bucket changes, its new quota is immediately
 * applied to it.
 *
 * Jobs are throttled while an app is not in a foreground state. All jobs are allowed to run
 * freely when an app enters the foreground state and are restricted when the app leaves the
 * foreground state. However, jobs that are started while the app is in the TOP state are not
 * restricted regardless of the app's state change.
 *
 * Test: atest com.android.server.job.controllers.QuotaControllerTest
 */
public final class QuotaController extends StateController {
    private static final String TAG = "JobScheduler.Quota";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private static final long MINUTE_IN_MILLIS = 60 * 1000L;

    private static final String ALARM_TAG_CLEANUP = "*job.cleanup*";
    private static final String ALARM_TAG_QUOTA_CHECK = "*job.quota_check*";

    /**
     * A sparse array of ArrayMaps, which is suitable for holding (userId, packageName)->object
     * associations.
     */
    private static class UserPackageMap<T> {
        private final SparseArray<ArrayMap<String, T>> mData = new SparseArray<>();

        public void add(int userId, @NonNull String packageName, @Nullable T obj) {
            ArrayMap<String, T> data = mData.get(userId);
            if (data == null) {
                data = new ArrayMap<String, T>();
                mData.put(userId, data);
            }
            data.put(packageName, obj);
        }

        public void clear() {
            for (int i = 0; i < mData.size(); ++i) {
                mData.valueAt(i).clear();
            }
        }

        /** Removes all the data for the user, if there was any. */
        public void delete(int userId) {
            mData.delete(userId);
        }

        /** Removes the data for the user and package, if there was any. */
        public void delete(int userId, @NonNull String packageName) {
            ArrayMap<String, T> data = mData.get(userId);
            if (data != null) {
                data.remove(packageName);
            }
        }

        @Nullable
        public T get(int userId, @NonNull String packageName) {
            ArrayMap<String, T> data = mData.get(userId);
            if (data != null) {
                return data.get(packageName);
            }
            return null;
        }

        /** @see SparseArray#indexOfKey */
        public int indexOfKey(int userId) {
            return mData.indexOfKey(userId);
        }

        /** Returns the userId at the given index. */
        public int keyAt(int index) {
            return mData.keyAt(index);
        }

        /** Returns the package name at the given index. */
        @NonNull
        public String keyAt(int userIndex, int packageIndex) {
            return mData.valueAt(userIndex).keyAt(packageIndex);
        }

        /** Returns the size of the outer (userId) array. */
        public int numUsers() {
            return mData.size();
        }

        public int numPackagesForUser(int userId) {
            ArrayMap<String, T> data = mData.get(userId);
            return data == null ? 0 : data.size();
        }

        /** Returns the value T at the given user and index. */
        @Nullable
        public T valueAt(int userIndex, int packageIndex) {
            return mData.valueAt(userIndex).valueAt(packageIndex);
        }

        public void forEach(Consumer<T> consumer) {
            for (int i = numUsers() - 1; i >= 0; --i) {
                ArrayMap<String, T> data = mData.valueAt(i);
                for (int j = data.size() - 1; j >= 0; --j) {
                    consumer.accept(data.valueAt(j));
                }
            }
        }
    }

    /**
     * Standardize the output of userId-packageName combo.
     */
    private static String string(int userId, String packageName) {
        return "<" + userId + ">" + packageName;
    }

    private static final class Package {
        public final String packageName;
        public final int userId;

        Package(int userId, String packageName) {
            this.userId = userId;
            this.packageName = packageName;
        }

        @Override
        public String toString() {
            return string(userId, packageName);
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(StateControllerProto.QuotaController.Package.USER_ID, userId);
            proto.write(StateControllerProto.QuotaController.Package.NAME, packageName);

            proto.end(token);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Package) {
                Package other = (Package) obj;
                return userId == other.userId && Objects.equals(packageName, other.packageName);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return packageName.hashCode() + userId;
        }
    }

    private static int hashLong(long val) {
        return (int) (val ^ (val >>> 32));
    }

    @VisibleForTesting
    static class ExecutionStats {
        /**
         * The time after which this record should be considered invalid (out of date), in the
         * elapsed realtime timebase.
         */
        public long expirationTimeElapsed;

        public long windowSizeMs;

        /** The total amount of time the app ran in its respective bucket window size. */
        public long executionTimeInWindowMs;
        public int bgJobCountInWindow;

        /** The total amount of time the app ran in the last {@link #MAX_PERIOD_MS}. */
        public long executionTimeInMaxPeriodMs;
        public int bgJobCountInMaxPeriod;

        /**
         * The time after which the sum of all the app's sessions plus {@link #mQuotaBufferMs}
         * equals the quota. This is only valid if
         * executionTimeInWindowMs >= {@link #mAllowedTimePerPeriodMs} or
         * executionTimeInMaxPeriodMs >= {@link #mMaxExecutionTimeMs}.
         */
        public long quotaCutoffTimeElapsed;

        /**
         * The time after which {@link #jobCountInAllowedTime} should be considered invalid, in the
         * elapsed realtime timebase.
         */
        public long jobCountExpirationTimeElapsed;

        /**
         * The number of jobs that ran in at least the last {@link #mAllowedTimePerPeriodMs}.
         * It may contain a few stale entries since cleanup won't happen exactly every
         * {@link #mAllowedTimePerPeriodMs}.
         */
        public int jobCountInAllowedTime;

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("expirationTime=").append(expirationTimeElapsed).append(", ")
                    .append("windowSize=").append(windowSizeMs).append(", ")
                    .append("executionTimeInWindow=").append(executionTimeInWindowMs).append(", ")
                    .append("bgJobCountInWindow=").append(bgJobCountInWindow).append(", ")
                    .append("executionTimeInMaxPeriod=").append(executionTimeInMaxPeriodMs)
                    .append(", ")
                    .append("bgJobCountInMaxPeriod=").append(bgJobCountInMaxPeriod).append(", ")
                    .append("quotaCutoffTime=").append(quotaCutoffTimeElapsed).append(", ")
                    .append("jobCountExpirationTime").append(jobCountExpirationTimeElapsed)
                    .append(", ")
                    .append("jobCountInAllowedTime").append(jobCountInAllowedTime)
                    .toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ExecutionStats) {
                ExecutionStats other = (ExecutionStats) obj;
                return this.expirationTimeElapsed == other.expirationTimeElapsed
                        && this.windowSizeMs == other.windowSizeMs
                        && this.executionTimeInWindowMs == other.executionTimeInWindowMs
                        && this.bgJobCountInWindow == other.bgJobCountInWindow
                        && this.executionTimeInMaxPeriodMs == other.executionTimeInMaxPeriodMs
                        && this.bgJobCountInMaxPeriod == other.bgJobCountInMaxPeriod
                        && this.quotaCutoffTimeElapsed == other.quotaCutoffTimeElapsed
                        && this.jobCountExpirationTimeElapsed == other.jobCountExpirationTimeElapsed
                        && this.jobCountInAllowedTime == other.jobCountInAllowedTime;
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            int result = 0;
            result = 31 * result + hashLong(expirationTimeElapsed);
            result = 31 * result + hashLong(windowSizeMs);
            result = 31 * result + hashLong(executionTimeInWindowMs);
            result = 31 * result + bgJobCountInWindow;
            result = 31 * result + hashLong(executionTimeInMaxPeriodMs);
            result = 31 * result + bgJobCountInMaxPeriod;
            result = 31 * result + hashLong(quotaCutoffTimeElapsed);
            result = 31 * result + hashLong(jobCountExpirationTimeElapsed);
            result = 31 * result + jobCountInAllowedTime;
            return result;
        }
    }

    /** List of all tracked jobs keyed by source package-userId combo. */
    private final UserPackageMap<ArraySet<JobStatus>> mTrackedJobs = new UserPackageMap<>();

    /** Timer for each package-userId combo. */
    private final UserPackageMap<Timer> mPkgTimers = new UserPackageMap<>();

    /** List of all timing sessions for a package-userId combo, in chronological order. */
    private final UserPackageMap<List<TimingSession>> mTimingSessions = new UserPackageMap<>();

    /**
     * List of alarm listeners for each package that listen for when each package comes back within
     * quota.
     */
    private final UserPackageMap<QcAlarmListener> mInQuotaAlarmListeners = new UserPackageMap<>();

    /** Cached calculation results for each app, with the standby buckets as the array indices. */
    private final UserPackageMap<ExecutionStats[]> mExecutionStatsCache = new UserPackageMap<>();

    /** List of UIDs currently in the foreground. */
    private final SparseBooleanArray mForegroundUids = new SparseBooleanArray();

    /**
     * List of jobs that started while the UID was in the TOP state. There will be no more than
     * 16 ({@link JobSchedulerService#MAX_JOB_CONTEXTS_COUNT}) running at once, so an ArraySet is
     * fine.
     */
    private final ArraySet<JobStatus> mTopStartedJobs = new ArraySet<>();

    private final ActivityManagerInternal mActivityManagerInternal;
    private final AlarmManager mAlarmManager;
    private final ChargingTracker mChargeTracker;
    private final Handler mHandler;

    private volatile boolean mInParole;

    /**
     * If the QuotaController should throttle apps based on their standby bucket and job activity.
     * If false, all jobs will have their CONSTRAINT_WITHIN_QUOTA bit set to true immediately and
     * indefinitely.
     */
    private boolean mShouldThrottle;

    /** How much time each app will have to run jobs within their standby bucket window. */
    private long mAllowedTimePerPeriodMs = 10 * MINUTE_IN_MILLIS;

    /**
     * The maximum amount of time an app can have its jobs running within a {@link #MAX_PERIOD_MS}
     * window.
     */
    private long mMaxExecutionTimeMs = 4 * 60 * MINUTE_IN_MILLIS;

    /**
     * How much time the app should have before transitioning from out-of-quota to in-quota.
     * This should not affect processing if the app is already in-quota.
     */
    private long mQuotaBufferMs = 30 * 1000L; // 30 seconds

    /**
     * {@link #mAllowedTimePerPeriodMs} - {@link #mQuotaBufferMs}. This can be used to determine
     * when an app will have enough quota to transition from out-of-quota to in-quota.
     */
    private long mAllowedTimeIntoQuotaMs = mAllowedTimePerPeriodMs - mQuotaBufferMs;

    /**
     * {@link #mMaxExecutionTimeMs} - {@link #mQuotaBufferMs}. This can be used to determine when an
     * app will have enough quota to transition from out-of-quota to in-quota.
     */
    private long mMaxExecutionTimeIntoQuotaMs = mMaxExecutionTimeMs - mQuotaBufferMs;

    /** The maximum number of jobs that can run within the past {@link #mAllowedTimePerPeriodMs}. */
    private int mMaxJobCountPerAllowedTime = 20;

    private long mNextCleanupTimeElapsed = 0;
    private final AlarmManager.OnAlarmListener mSessionCleanupAlarmListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    mHandler.obtainMessage(MSG_CLEAN_UP_SESSIONS).sendToTarget();
                }
            };

    private final IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
            mHandler.obtainMessage(MSG_UID_PROCESS_STATE_CHANGED, uid, procState).sendToTarget();
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
        }

        @Override
        public void onUidActive(int uid) {
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    /**
     * The rolling window size for each standby bucket. Within each window, an app will have 10
     * minutes to run its jobs.
     */
    private final long[] mBucketPeriodsMs = new long[] {
            10 * MINUTE_IN_MILLIS, // 10 minutes for ACTIVE -- ACTIVE apps can run jobs at any time
            2 * 60 * MINUTE_IN_MILLIS, // 2 hours for WORKING
            8 * 60 * MINUTE_IN_MILLIS, // 8 hours for FREQUENT
            24 * 60 * MINUTE_IN_MILLIS // 24 hours for RARE
    };

    /** The maximum period any bucket can have. */
    private static final long MAX_PERIOD_MS = 24 * 60 * MINUTE_IN_MILLIS;

    /**
     * The maximum number of jobs based on its standby bucket. For each max value count in the
     * array, the app will not be allowed to run more than that many number of jobs within the
     * latest time interval of its rolling window size.
     *
     * @see #mBucketPeriodsMs
     */
    private final int[] mMaxBucketJobCounts = new int[] {
            200,  // ACTIVE   -- 1200/hr
            1200, // WORKING  -- 600/hr
            1800, // FREQUENT -- 225/hr
            2400  // RARE     -- 100/hr
    };

    /** The minimum number of jobs that any bucket will be allowed to run. */
    private static final int MIN_BUCKET_JOB_COUNT = 100;

    /** An app has reached its quota. The message should contain a {@link Package} object. */
    private static final int MSG_REACHED_QUOTA = 0;
    /** Drop any old timing sessions. */
    private static final int MSG_CLEAN_UP_SESSIONS = 1;
    /** Check if a package is now within its quota. */
    private static final int MSG_CHECK_PACKAGE = 2;
    /** Process state for a UID has changed. */
    private static final int MSG_UID_PROCESS_STATE_CHANGED = 3;

    public QuotaController(JobSchedulerService service) {
        super(service);
        mHandler = new QcHandler(mContext.getMainLooper());
        mChargeTracker = new ChargingTracker();
        mChargeTracker.startTracking();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        // Set up the app standby bucketing tracker
        UsageStatsManagerInternal usageStats = LocalServices.getService(
                UsageStatsManagerInternal.class);
        usageStats.addAppIdleStateChangeListener(new StandbyTracker());

        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE,
                    ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }

        onConstantsUpdatedLocked();
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        // Still need to track jobs even if mShouldThrottle is false in case it's set to true at
        // some point.
        ArraySet<JobStatus> jobs = mTrackedJobs.get(jobStatus.getSourceUserId(),
                jobStatus.getSourcePackageName());
        if (jobs == null) {
            jobs = new ArraySet<>();
            mTrackedJobs.add(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(), jobs);
        }
        jobs.add(jobStatus);
        jobStatus.setTrackingController(JobStatus.TRACKING_QUOTA);
        jobStatus.setQuotaConstraintSatisfied(!mShouldThrottle || isWithinQuotaLocked(jobStatus));
    }

    @Override
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        if (DEBUG) Slog.d(TAG, "Prepping for " + jobStatus.toShortString());

        final int uid = jobStatus.getSourceUid();
        if (mActivityManagerInternal.getUidProcessState(uid) <= ActivityManager.PROCESS_STATE_TOP) {
            mTopStartedJobs.add(jobStatus);
            // Top jobs won't count towards quota so there's no need to involve the Timer.
            return;
        }

        final int userId = jobStatus.getSourceUserId();
        final String packageName = jobStatus.getSourcePackageName();
        Timer timer = mPkgTimers.get(userId, packageName);
        if (timer == null) {
            timer = new Timer(uid, userId, packageName);
            mPkgTimers.add(userId, packageName, timer);
        }
        timer.startTrackingJob(jobStatus);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (jobStatus.clearTrackingController(JobStatus.TRACKING_QUOTA)) {
            Timer timer = mPkgTimers.get(jobStatus.getSourceUserId(),
                    jobStatus.getSourcePackageName());
            if (timer != null) {
                timer.stopTrackingJob(jobStatus);
            }
            ArraySet<JobStatus> jobs = mTrackedJobs.get(jobStatus.getSourceUserId(),
                    jobStatus.getSourcePackageName());
            if (jobs != null) {
                jobs.remove(jobStatus);
            }
            mTopStartedJobs.remove(jobStatus);
        }
    }

    @Override
    public void onConstantsUpdatedLocked() {
        boolean changed = false;
        if (mShouldThrottle == mConstants.USE_HEARTBEATS) {
            mShouldThrottle = !mConstants.USE_HEARTBEATS;
            changed = true;
        }
        long newAllowedTimeMs = Math.min(MAX_PERIOD_MS,
                Math.max(MINUTE_IN_MILLIS, mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS));
        if (mAllowedTimePerPeriodMs != newAllowedTimeMs) {
            mAllowedTimePerPeriodMs = newAllowedTimeMs;
            mAllowedTimeIntoQuotaMs = mAllowedTimePerPeriodMs - mQuotaBufferMs;
            changed = true;
        }
        long newQuotaBufferMs = Math.max(0,
                Math.min(5 * MINUTE_IN_MILLIS, mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS));
        if (mQuotaBufferMs != newQuotaBufferMs) {
            mQuotaBufferMs = newQuotaBufferMs;
            mAllowedTimeIntoQuotaMs = mAllowedTimePerPeriodMs - mQuotaBufferMs;
            mMaxExecutionTimeIntoQuotaMs = mMaxExecutionTimeMs - mQuotaBufferMs;
            changed = true;
        }
        long newActivePeriodMs = Math.max(mAllowedTimePerPeriodMs,
                Math.min(MAX_PERIOD_MS, mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_ACTIVE_MS));
        if (mBucketPeriodsMs[ACTIVE_INDEX] != newActivePeriodMs) {
            mBucketPeriodsMs[ACTIVE_INDEX] = newActivePeriodMs;
            changed = true;
        }
        long newWorkingPeriodMs = Math.max(mAllowedTimePerPeriodMs,
                Math.min(MAX_PERIOD_MS, mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_WORKING_MS));
        if (mBucketPeriodsMs[WORKING_INDEX] != newWorkingPeriodMs) {
            mBucketPeriodsMs[WORKING_INDEX] = newWorkingPeriodMs;
            changed = true;
        }
        long newFrequentPeriodMs = Math.max(mAllowedTimePerPeriodMs,
                Math.min(MAX_PERIOD_MS, mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS));
        if (mBucketPeriodsMs[FREQUENT_INDEX] != newFrequentPeriodMs) {
            mBucketPeriodsMs[FREQUENT_INDEX] = newFrequentPeriodMs;
            changed = true;
        }
        long newRarePeriodMs = Math.max(mAllowedTimePerPeriodMs,
                Math.min(MAX_PERIOD_MS, mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_RARE_MS));
        if (mBucketPeriodsMs[RARE_INDEX] != newRarePeriodMs) {
            mBucketPeriodsMs[RARE_INDEX] = newRarePeriodMs;
            changed = true;
        }
        long newMaxExecutionTimeMs = Math.max(60 * MINUTE_IN_MILLIS,
                Math.min(MAX_PERIOD_MS, mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS));
        if (mMaxExecutionTimeMs != newMaxExecutionTimeMs) {
            mMaxExecutionTimeMs = newMaxExecutionTimeMs;
            mMaxExecutionTimeIntoQuotaMs = mMaxExecutionTimeMs - mQuotaBufferMs;
            changed = true;
        }
        int newMaxCountPerAllowedPeriod = Math.max(10,
                mConstants.QUOTA_CONTROLLER_MAX_JOB_COUNT_PER_ALLOWED_TIME);
        if (mMaxJobCountPerAllowedTime != newMaxCountPerAllowedPeriod) {
            mMaxJobCountPerAllowedTime = newMaxCountPerAllowedPeriod;
            changed = true;
        }
        int newActiveMaxJobCount = Math.max(mMaxJobCountPerAllowedTime,
                Math.max(MIN_BUCKET_JOB_COUNT, mConstants.QUOTA_CONTROLLER_MAX_JOB_COUNT_ACTIVE));
        if (mMaxBucketJobCounts[ACTIVE_INDEX] != newActiveMaxJobCount) {
            mMaxBucketJobCounts[ACTIVE_INDEX] = newActiveMaxJobCount;
            changed = true;
        }
        int newWorkingMaxJobCount = Math.max(mMaxJobCountPerAllowedTime,
                Math.max(MIN_BUCKET_JOB_COUNT, mConstants.QUOTA_CONTROLLER_MAX_JOB_COUNT_WORKING));
        if (mMaxBucketJobCounts[WORKING_INDEX] != newWorkingMaxJobCount) {
            mMaxBucketJobCounts[WORKING_INDEX] = newWorkingMaxJobCount;
            changed = true;
        }
        int newFrequentMaxJobCount = Math.max(mMaxJobCountPerAllowedTime,
                Math.max(MIN_BUCKET_JOB_COUNT, mConstants.QUOTA_CONTROLLER_MAX_JOB_COUNT_FREQUENT));
        if (mMaxBucketJobCounts[FREQUENT_INDEX] != newFrequentMaxJobCount) {
            mMaxBucketJobCounts[FREQUENT_INDEX] = newFrequentMaxJobCount;
            changed = true;
        }
        int newRareMaxJobCount = Math.max(mMaxJobCountPerAllowedTime,
                Math.max(MIN_BUCKET_JOB_COUNT, mConstants.QUOTA_CONTROLLER_MAX_JOB_COUNT_RARE));
        if (mMaxBucketJobCounts[RARE_INDEX] != newRareMaxJobCount) {
            mMaxBucketJobCounts[RARE_INDEX] = newRareMaxJobCount;
            changed = true;
        }

        if (changed) {
            // Update job bookkeeping out of band.
            BackgroundThread.getHandler().post(() -> {
                synchronized (mLock) {
                    maybeUpdateAllConstraintsLocked();
                }
            });
        }
    }

    @Override
    public void onAppRemovedLocked(String packageName, int uid) {
        if (packageName == null) {
            Slog.wtf(TAG, "Told app removed but given null package name.");
            return;
        }
        final int userId = UserHandle.getUserId(uid);
        mTrackedJobs.delete(userId, packageName);
        Timer timer = mPkgTimers.get(userId, packageName);
        if (timer != null) {
            if (timer.isActive()) {
                Slog.wtf(TAG, "onAppRemovedLocked called before Timer turned off.");
                timer.dropEverything();
            }
            mPkgTimers.delete(userId, packageName);
        }
        mTimingSessions.delete(userId, packageName);
        QcAlarmListener alarmListener = mInQuotaAlarmListeners.get(userId, packageName);
        if (alarmListener != null) {
            mAlarmManager.cancel(alarmListener);
            mInQuotaAlarmListeners.delete(userId, packageName);
        }
        mExecutionStatsCache.delete(userId, packageName);
        mForegroundUids.delete(uid);
    }

    @Override
    public void onUserRemovedLocked(int userId) {
        mTrackedJobs.delete(userId);
        mPkgTimers.delete(userId);
        mTimingSessions.delete(userId);
        mInQuotaAlarmListeners.delete(userId);
        mExecutionStatsCache.delete(userId);
    }

    private boolean isUidInForeground(int uid) {
        if (UserHandle.isCore(uid)) {
            return true;
        }
        synchronized (mLock) {
            return mForegroundUids.get(uid);
        }
    }

    /** @return true if the job was started while the app was in the TOP state. */
    private boolean isTopStartedJob(@NonNull final JobStatus jobStatus) {
        return mTopStartedJobs.contains(jobStatus);
    }

    /**
     * Returns an appropriate standby bucket for the job, taking into account any standby
     * exemptions.
     */
    private int getEffectiveStandbyBucket(@NonNull final JobStatus jobStatus) {
        if (jobStatus.uidActive || jobStatus.getJob().isExemptedFromAppStandby()) {
            // Treat these cases as if they're in the ACTIVE bucket so that they get throttled
            // like other ACTIVE apps.
            return ACTIVE_INDEX;
        }
        return jobStatus.getStandbyBucket();
    }

    private boolean isWithinQuotaLocked(@NonNull final JobStatus jobStatus) {
        final int standbyBucket = getEffectiveStandbyBucket(jobStatus);
        Timer timer = mPkgTimers.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        // A job is within quota if one of the following is true:
        //   1. it was started while the app was in the TOP state
        //   2. the app is currently in the foreground
        //   3. the app overall is within its quota
        return isTopStartedJob(jobStatus)
                || isUidInForeground(jobStatus.getSourceUid())
                || isWithinQuotaLocked(
                jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(), standbyBucket);
    }

    @VisibleForTesting
    boolean isWithinQuotaLocked(final int userId, @NonNull final String packageName,
            final int standbyBucket) {
        if (standbyBucket == NEVER_INDEX) return false;
        // This check is needed in case the flag is toggled after a job has been registered.
        if (!mShouldThrottle) return true;

        // Quota constraint is not enforced while charging or when parole is on.
        if (mChargeTracker.isCharging() || mInParole) {
            return true;
        }

        return getRemainingExecutionTimeLocked(userId, packageName, standbyBucket) > 0
                && isUnderJobCountQuotaLocked(userId, packageName, standbyBucket);
    }

    private boolean isUnderJobCountQuotaLocked(final int userId, @NonNull final String packageName,
            final int standbyBucket) {
        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket, false);
        return isUnderJobCountQuotaLocked(stats, standbyBucket);
    }

    private boolean isUnderJobCountQuotaLocked(@NonNull ExecutionStats stats,
            final int standbyBucket) {
        final long now = sElapsedRealtimeClock.millis();
        final boolean isUnderAllowedTimeQuota =
                (stats.jobCountExpirationTimeElapsed <= now
                        || stats.jobCountInAllowedTime < mMaxJobCountPerAllowedTime);
        return isUnderAllowedTimeQuota
                && (stats.bgJobCountInWindow < mMaxBucketJobCounts[standbyBucket]);
    }

    @VisibleForTesting
    long getRemainingExecutionTimeLocked(@NonNull final JobStatus jobStatus) {
        return getRemainingExecutionTimeLocked(jobStatus.getSourceUserId(),
                jobStatus.getSourcePackageName(),
                getEffectiveStandbyBucket(jobStatus));
    }

    @VisibleForTesting
    long getRemainingExecutionTimeLocked(final int userId, @NonNull final String packageName) {
        final int standbyBucket = JobSchedulerService.standbyBucketForPackage(packageName,
                userId, sElapsedRealtimeClock.millis());
        return getRemainingExecutionTimeLocked(userId, packageName, standbyBucket);
    }

    /**
     * Returns the amount of time, in milliseconds, that this job has remaining to run based on its
     * current standby bucket. Time remaining could be negative if the app was moved from a less
     * restricted to a more restricted bucket.
     */
    private long getRemainingExecutionTimeLocked(final int userId,
            @NonNull final String packageName, final int standbyBucket) {
        if (standbyBucket == NEVER_INDEX) {
            return 0;
        }
        final ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        return Math.min(mAllowedTimePerPeriodMs - stats.executionTimeInWindowMs,
                mMaxExecutionTimeMs - stats.executionTimeInMaxPeriodMs);
    }

    /** Returns the execution stats of the app in the most recent window. */
    @VisibleForTesting
    @NonNull
    ExecutionStats getExecutionStatsLocked(final int userId, @NonNull final String packageName,
            final int standbyBucket) {
        return getExecutionStatsLocked(userId, packageName, standbyBucket, true);
    }

    @NonNull
    private ExecutionStats getExecutionStatsLocked(final int userId,
            @NonNull final String packageName, final int standbyBucket,
            final boolean refreshStatsIfOld) {
        if (standbyBucket == NEVER_INDEX) {
            Slog.wtf(TAG, "getExecutionStatsLocked called for a NEVER app.");
            return new ExecutionStats();
        }
        ExecutionStats[] appStats = mExecutionStatsCache.get(userId, packageName);
        if (appStats == null) {
            appStats = new ExecutionStats[mBucketPeriodsMs.length];
            mExecutionStatsCache.add(userId, packageName, appStats);
        }
        ExecutionStats stats = appStats[standbyBucket];
        if (stats == null) {
            stats = new ExecutionStats();
            appStats[standbyBucket] = stats;
        }
        if (refreshStatsIfOld) {
            final long bucketWindowSizeMs = mBucketPeriodsMs[standbyBucket];
            Timer timer = mPkgTimers.get(userId, packageName);
            if ((timer != null && timer.isActive())
                    || stats.expirationTimeElapsed <= sElapsedRealtimeClock.millis()
                    || stats.windowSizeMs != bucketWindowSizeMs) {
                // The stats are no longer valid.
                stats.windowSizeMs = bucketWindowSizeMs;
                updateExecutionStatsLocked(userId, packageName, stats);
            }
        }

        return stats;
    }

    @VisibleForTesting
    void updateExecutionStatsLocked(final int userId, @NonNull final String packageName,
            @NonNull ExecutionStats stats) {
        stats.executionTimeInWindowMs = 0;
        stats.bgJobCountInWindow = 0;
        stats.executionTimeInMaxPeriodMs = 0;
        stats.bgJobCountInMaxPeriod = 0;
        stats.quotaCutoffTimeElapsed = 0;

        Timer timer = mPkgTimers.get(userId, packageName);
        final long nowElapsed = sElapsedRealtimeClock.millis();
        stats.expirationTimeElapsed = nowElapsed + MAX_PERIOD_MS;
        if (timer != null && timer.isActive()) {
            stats.executionTimeInWindowMs =
                    stats.executionTimeInMaxPeriodMs = timer.getCurrentDuration(nowElapsed);
            stats.bgJobCountInWindow = stats.bgJobCountInMaxPeriod = timer.getBgJobCount();
            // If the timer is active, the value will be stale at the next method call, so
            // invalidate now.
            stats.expirationTimeElapsed = nowElapsed;
            if (stats.executionTimeInWindowMs >= mAllowedTimeIntoQuotaMs) {
                stats.quotaCutoffTimeElapsed = Math.max(stats.quotaCutoffTimeElapsed,
                        nowElapsed - mAllowedTimeIntoQuotaMs);
            }
            if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeIntoQuotaMs) {
                stats.quotaCutoffTimeElapsed = Math.max(stats.quotaCutoffTimeElapsed,
                        nowElapsed - mMaxExecutionTimeIntoQuotaMs);
            }
        }

        List<TimingSession> sessions = mTimingSessions.get(userId, packageName);
        if (sessions == null || sessions.size() == 0) {
            return;
        }

        final long startWindowElapsed = nowElapsed - stats.windowSizeMs;
        final long startMaxElapsed = nowElapsed - MAX_PERIOD_MS;
        // The minimum time between the start time and the beginning of the sessions that were
        // looked at --> how much time the stats will be valid for.
        long emptyTimeMs = Long.MAX_VALUE;
        // Sessions are non-overlapping and in order of occurrence, so iterating backwards will get
        // the most recent ones.
        for (int i = sessions.size() - 1; i >= 0; --i) {
            TimingSession session = sessions.get(i);

            // Window management.
            if (startWindowElapsed < session.startTimeElapsed) {
                stats.executionTimeInWindowMs += session.endTimeElapsed - session.startTimeElapsed;
                stats.bgJobCountInWindow += session.bgJobCount;
                emptyTimeMs = Math.min(emptyTimeMs, session.startTimeElapsed - startWindowElapsed);
                if (stats.executionTimeInWindowMs >= mAllowedTimeIntoQuotaMs) {
                    stats.quotaCutoffTimeElapsed = Math.max(stats.quotaCutoffTimeElapsed,
                            session.startTimeElapsed + stats.executionTimeInWindowMs
                                    - mAllowedTimeIntoQuotaMs);
                }
            } else if (startWindowElapsed < session.endTimeElapsed) {
                // The session started before the window but ended within the window. Only include
                // the portion that was within the window.
                stats.executionTimeInWindowMs += session.endTimeElapsed - startWindowElapsed;
                stats.bgJobCountInWindow += session.bgJobCount;
                emptyTimeMs = 0;
                if (stats.executionTimeInWindowMs >= mAllowedTimeIntoQuotaMs) {
                    stats.quotaCutoffTimeElapsed = Math.max(stats.quotaCutoffTimeElapsed,
                            startWindowElapsed + stats.executionTimeInWindowMs
                                    - mAllowedTimeIntoQuotaMs);
                }
            }

            // Max period check.
            if (startMaxElapsed < session.startTimeElapsed) {
                stats.executionTimeInMaxPeriodMs +=
                        session.endTimeElapsed - session.startTimeElapsed;
                stats.bgJobCountInMaxPeriod += session.bgJobCount;
                emptyTimeMs = Math.min(emptyTimeMs, session.startTimeElapsed - startMaxElapsed);
                if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeIntoQuotaMs) {
                    stats.quotaCutoffTimeElapsed = Math.max(stats.quotaCutoffTimeElapsed,
                            session.startTimeElapsed + stats.executionTimeInMaxPeriodMs
                                    - mMaxExecutionTimeIntoQuotaMs);
                }
            } else if (startMaxElapsed < session.endTimeElapsed) {
                // The session started before the window but ended within the window. Only include
                // the portion that was within the window.
                stats.executionTimeInMaxPeriodMs += session.endTimeElapsed - startMaxElapsed;
                stats.bgJobCountInMaxPeriod += session.bgJobCount;
                emptyTimeMs = 0;
                if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeIntoQuotaMs) {
                    stats.quotaCutoffTimeElapsed = Math.max(stats.quotaCutoffTimeElapsed,
                            startMaxElapsed + stats.executionTimeInMaxPeriodMs
                                    - mMaxExecutionTimeIntoQuotaMs);
                }
            } else {
                // This session ended before the window. No point in going any further.
                break;
            }
        }
        stats.expirationTimeElapsed = nowElapsed + emptyTimeMs;
    }

    private void invalidateAllExecutionStatsLocked(final int userId,
            @NonNull final String packageName) {
        ExecutionStats[] appStats = mExecutionStatsCache.get(userId, packageName);
        if (appStats != null) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            for (int i = 0; i < appStats.length; ++i) {
                ExecutionStats stats = appStats[i];
                if (stats != null) {
                    stats.expirationTimeElapsed = nowElapsed;
                }
            }
        }
    }

    @VisibleForTesting
    void incrementJobCount(final int userId, @NonNull final String packageName, int count) {
        final long now = sElapsedRealtimeClock.millis();
        ExecutionStats[] appStats = mExecutionStatsCache.get(userId, packageName);
        if (appStats == null) {
            appStats = new ExecutionStats[mBucketPeriodsMs.length];
            mExecutionStatsCache.add(userId, packageName, appStats);
        }
        for (int i = 0; i < appStats.length; ++i) {
            ExecutionStats stats = appStats[i];
            if (stats == null) {
                stats = new ExecutionStats();
                appStats[i] = stats;
            }
            if (stats.jobCountExpirationTimeElapsed <= now) {
                stats.jobCountExpirationTimeElapsed = now + mAllowedTimePerPeriodMs;
                stats.jobCountInAllowedTime = 0;
            }
            stats.jobCountInAllowedTime += count;
        }
    }

    @VisibleForTesting
    void saveTimingSession(final int userId, @NonNull final String packageName,
            @NonNull final TimingSession session) {
        synchronized (mLock) {
            List<TimingSession> sessions = mTimingSessions.get(userId, packageName);
            if (sessions == null) {
                sessions = new ArrayList<>();
                mTimingSessions.add(userId, packageName, sessions);
            }
            sessions.add(session);
            // Adding a new session means that the current stats are now incorrect.
            invalidateAllExecutionStatsLocked(userId, packageName);

            maybeScheduleCleanupAlarmLocked();
        }
    }

    private final class EarliestEndTimeFunctor implements Consumer<List<TimingSession>> {
        public long earliestEndElapsed = Long.MAX_VALUE;

        @Override
        public void accept(List<TimingSession> sessions) {
            if (sessions != null && sessions.size() > 0) {
                earliestEndElapsed = Math.min(earliestEndElapsed, sessions.get(0).endTimeElapsed);
            }
        }

        void reset() {
            earliestEndElapsed = Long.MAX_VALUE;
        }
    }

    private final EarliestEndTimeFunctor mEarliestEndTimeFunctor = new EarliestEndTimeFunctor();

    /** Schedule a cleanup alarm if necessary and there isn't already one scheduled. */
    @VisibleForTesting
    void maybeScheduleCleanupAlarmLocked() {
        if (mNextCleanupTimeElapsed > sElapsedRealtimeClock.millis()) {
            // There's already an alarm scheduled. Just stick with that one. There's no way we'll
            // end up scheduling an earlier alarm.
            if (DEBUG) {
                Slog.v(TAG, "Not scheduling cleanup since there's already one at "
                        + mNextCleanupTimeElapsed + " (in " + (mNextCleanupTimeElapsed
                        - sElapsedRealtimeClock.millis()) + "ms)");
            }
            return;
        }
        mEarliestEndTimeFunctor.reset();
        mTimingSessions.forEach(mEarliestEndTimeFunctor);
        final long earliestEndElapsed = mEarliestEndTimeFunctor.earliestEndElapsed;
        if (earliestEndElapsed == Long.MAX_VALUE) {
            // Couldn't find a good time to clean up. Maybe this was called after we deleted all
            // timing sessions.
            if (DEBUG) Slog.d(TAG, "Didn't find a time to schedule cleanup");
            return;
        }
        // Need to keep sessions for all apps up to the max period, regardless of their current
        // standby bucket.
        long nextCleanupElapsed = earliestEndElapsed + MAX_PERIOD_MS;
        if (nextCleanupElapsed - mNextCleanupTimeElapsed <= 10 * MINUTE_IN_MILLIS) {
            // No need to clean up too often. Delay the alarm if the next cleanup would be too soon
            // after it.
            nextCleanupElapsed += 10 * MINUTE_IN_MILLIS;
        }
        mNextCleanupTimeElapsed = nextCleanupElapsed;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, nextCleanupElapsed, ALARM_TAG_CLEANUP,
                mSessionCleanupAlarmListener, mHandler);
        if (DEBUG) Slog.d(TAG, "Scheduled next cleanup for " + mNextCleanupTimeElapsed);
    }

    private void handleNewChargingStateLocked() {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final boolean isCharging = mChargeTracker.isCharging();
        if (DEBUG) Slog.d(TAG, "handleNewChargingStateLocked: " + isCharging);
        // Deal with Timers first.
        mPkgTimers.forEach((t) -> t.onStateChanged(nowElapsed, isCharging));
        // Now update jobs.
        maybeUpdateAllConstraintsLocked();
    }

    private void maybeUpdateAllConstraintsLocked() {
        boolean changed = false;
        for (int u = 0; u < mTrackedJobs.numUsers(); ++u) {
            final int userId = mTrackedJobs.keyAt(u);
            for (int p = 0; p < mTrackedJobs.numPackagesForUser(userId); ++p) {
                final String packageName = mTrackedJobs.keyAt(u, p);
                changed |= maybeUpdateConstraintForPkgLocked(userId, packageName);
            }
        }
        if (changed) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    /**
     * Update the CONSTRAINT_WITHIN_QUOTA bit for all of the Jobs for a given package.
     *
     * @return true if at least one job had its bit changed
     */
    private boolean maybeUpdateConstraintForPkgLocked(final int userId,
            @NonNull final String packageName) {
        ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, packageName);
        if (jobs == null || jobs.size() == 0) {
            return false;
        }

        // Quota is the same for all jobs within a package.
        final int realStandbyBucket = jobs.valueAt(0).getStandbyBucket();
        final boolean realInQuota = isWithinQuotaLocked(userId, packageName, realStandbyBucket);
        boolean changed = false;
        for (int i = jobs.size() - 1; i >= 0; --i) {
            final JobStatus js = jobs.valueAt(i);
            if (isTopStartedJob(js)) {
                // Job was started while the app was in the TOP state so we should allow it to
                // finish.
                changed |= js.setQuotaConstraintSatisfied(true);
            } else if (realStandbyBucket != ACTIVE_INDEX
                    && realStandbyBucket == getEffectiveStandbyBucket(js)) {
                // An app in the ACTIVE bucket may be out of quota while the job could be in quota
                // for some reason. Therefore, avoid setting the real value here and check each job
                // individually.
                changed |= js.setQuotaConstraintSatisfied(realInQuota);
            } else {
                // This job is somehow exempted. Need to determine its own quota status.
                changed |= js.setQuotaConstraintSatisfied(isWithinQuotaLocked(js));
            }
        }
        if (!realInQuota) {
            // Don't want to use the effective standby bucket here since that bump the bucket to
            // ACTIVE for one of the jobs, which doesn't help with other jobs that aren't
            // exempted.
            maybeScheduleStartAlarmLocked(userId, packageName, realStandbyBucket);
        } else {
            QcAlarmListener alarmListener = mInQuotaAlarmListeners.get(userId, packageName);
            if (alarmListener != null && alarmListener.isWaiting()) {
                mAlarmManager.cancel(alarmListener);
                // Set the trigger time to 0 so that the alarm doesn't think it's still waiting.
                alarmListener.setTriggerTime(0);
            }
        }
        return changed;
    }

    private class UidConstraintUpdater implements Consumer<JobStatus> {
        private final UserPackageMap<Integer> mToScheduleStartAlarms = new UserPackageMap<>();
        public boolean wasJobChanged;

        @Override
        public void accept(JobStatus jobStatus) {
            wasJobChanged |= jobStatus.setQuotaConstraintSatisfied(isWithinQuotaLocked(jobStatus));
            final int userId = jobStatus.getSourceUserId();
            final String packageName = jobStatus.getSourcePackageName();
            final int realStandbyBucket = jobStatus.getStandbyBucket();
            if (isWithinQuotaLocked(userId, packageName, realStandbyBucket)) {
                QcAlarmListener alarmListener = mInQuotaAlarmListeners.get(userId, packageName);
                if (alarmListener != null && alarmListener.isWaiting()) {
                    mAlarmManager.cancel(alarmListener);
                    // Set the trigger time to 0 so that the alarm doesn't think it's still waiting.
                    alarmListener.setTriggerTime(0);
                }
            } else {
                mToScheduleStartAlarms.add(userId, packageName, realStandbyBucket);
            }
        }

        void postProcess() {
            for (int u = 0; u < mToScheduleStartAlarms.numUsers(); ++u) {
                final int userId = mToScheduleStartAlarms.keyAt(u);
                for (int p = 0; p < mToScheduleStartAlarms.numPackagesForUser(userId); ++p) {
                    final String packageName = mToScheduleStartAlarms.keyAt(u, p);
                    final int standbyBucket = mToScheduleStartAlarms.get(userId, packageName);
                    maybeScheduleStartAlarmLocked(userId, packageName, standbyBucket);
                }
            }
        }

        void reset() {
            wasJobChanged = false;
            mToScheduleStartAlarms.clear();
        }
    }

    private final UidConstraintUpdater mUpdateUidConstraints = new UidConstraintUpdater();

    private boolean maybeUpdateConstraintForUidLocked(final int uid) {
        mService.getJobStore().forEachJobForSourceUid(uid, mUpdateUidConstraints);

        mUpdateUidConstraints.postProcess();
        boolean changed = mUpdateUidConstraints.wasJobChanged;
        mUpdateUidConstraints.reset();
        return changed;
    }

    /**
     * Maybe schedule a non-wakeup alarm for the next time this package will have quota to run
     * again. This should only be called if the package is already out of quota.
     */
    @VisibleForTesting
    void maybeScheduleStartAlarmLocked(final int userId, @NonNull final String packageName,
            final int standbyBucket) {
        if (standbyBucket == NEVER_INDEX) {
            return;
        }

        final String pkgString = string(userId, packageName);
        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        final boolean isUnderJobCountQuota = isUnderJobCountQuotaLocked(stats, standbyBucket);

        QcAlarmListener alarmListener = mInQuotaAlarmListeners.get(userId, packageName);
        if (stats.executionTimeInWindowMs < mAllowedTimePerPeriodMs
                && stats.executionTimeInMaxPeriodMs < mMaxExecutionTimeMs
                && isUnderJobCountQuota) {
            // Already in quota. Why was this method called?
            if (DEBUG) {
                Slog.e(TAG, "maybeScheduleStartAlarmLocked called for " + pkgString
                        + " even though it already has "
                        + getRemainingExecutionTimeLocked(userId, packageName, standbyBucket)
                        + "ms in its quota.");
            }
            if (alarmListener != null) {
                // Cancel any pending alarm.
                mAlarmManager.cancel(alarmListener);
                // Set the trigger time to 0 so that the alarm doesn't think it's still waiting.
                alarmListener.setTriggerTime(0);
            }
            mHandler.obtainMessage(MSG_CHECK_PACKAGE, userId, 0, packageName).sendToTarget();
            return;
        }

        if (alarmListener == null) {
            alarmListener = new QcAlarmListener(userId, packageName);
            mInQuotaAlarmListeners.add(userId, packageName, alarmListener);
        }

        // The time this app will have quota again.
        long inQuotaTimeElapsed = stats.quotaCutoffTimeElapsed + stats.windowSizeMs;
        if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeMs) {
            inQuotaTimeElapsed = Math.max(inQuotaTimeElapsed,
                    stats.quotaCutoffTimeElapsed + MAX_PERIOD_MS);
        }
        if (!isUnderJobCountQuota) {
            inQuotaTimeElapsed = Math.max(inQuotaTimeElapsed,
                    stats.jobCountExpirationTimeElapsed + mAllowedTimePerPeriodMs);
        }
        // Only schedule the alarm if:
        // 1. There isn't one currently scheduled
        // 2. The new alarm is significantly earlier than the previous alarm (which could be the
        // case if the package moves into a higher standby bucket). If it's earlier but not
        // significantly so, then we essentially delay the job a few extra minutes.
        // 3. The alarm is after the current alarm by more than the quota buffer.
        // TODO: this might be overengineering. Simplify if proven safe.
        if (!alarmListener.isWaiting()
                || inQuotaTimeElapsed < alarmListener.getTriggerTimeElapsed() - 3 * MINUTE_IN_MILLIS
                || alarmListener.getTriggerTimeElapsed() < inQuotaTimeElapsed) {
            if (DEBUG) Slog.d(TAG, "Scheduling start alarm for " + pkgString);
            // If the next time this app will have quota is at least 3 minutes before the
            // alarm is supposed to go off, reschedule the alarm.
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, inQuotaTimeElapsed,
                    ALARM_TAG_QUOTA_CHECK, alarmListener, mHandler);
            alarmListener.setTriggerTime(inQuotaTimeElapsed);
        } else if (DEBUG) {
            Slog.d(TAG, "No need to scheduling start alarm for " + pkgString);
        }
    }

    private final class ChargingTracker extends BroadcastReceiver {
        /**
         * Track whether we're charging. This has a slightly different definition than that of
         * BatteryController.
         */
        private boolean mCharging;

        ChargingTracker() {
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();

            // Charging/not charging.
            filter.addAction(BatteryManager.ACTION_CHARGING);
            filter.addAction(BatteryManager.ACTION_DISCHARGING);
            mContext.registerReceiver(this, filter);

            // Initialise tracker state.
            BatteryManagerInternal batteryManagerInternal =
                    LocalServices.getService(BatteryManagerInternal.class);
            mCharging = batteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
        }

        public boolean isCharging() {
            return mCharging;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                final String action = intent.getAction();
                if (BatteryManager.ACTION_CHARGING.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Received charging intent, fired @ "
                                + sElapsedRealtimeClock.millis());
                    }
                    mCharging = true;
                    handleNewChargingStateLocked();
                } else if (BatteryManager.ACTION_DISCHARGING.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Disconnected from power.");
                    }
                    mCharging = false;
                    handleNewChargingStateLocked();
                }
            }
        }
    }

    @VisibleForTesting
    static final class TimingSession {
        // Start timestamp in elapsed realtime timebase.
        public final long startTimeElapsed;
        // End timestamp in elapsed realtime timebase.
        public final long endTimeElapsed;
        // How many background jobs ran during this session.
        public final int bgJobCount;

        private final int mHashCode;

        TimingSession(long startElapsed, long endElapsed, int bgJobCount) {
            this.startTimeElapsed = startElapsed;
            this.endTimeElapsed = endElapsed;
            this.bgJobCount = bgJobCount;

            int hashCode = 0;
            hashCode = 31 * hashCode + hashLong(startTimeElapsed);
            hashCode = 31 * hashCode + hashLong(endTimeElapsed);
            hashCode = 31 * hashCode + bgJobCount;
            mHashCode = hashCode;
        }

        @Override
        public String toString() {
            return "TimingSession{" + startTimeElapsed + "->" + endTimeElapsed + ", " + bgJobCount
                    + "}";
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof TimingSession) {
                TimingSession other = (TimingSession) obj;
                return startTimeElapsed == other.startTimeElapsed
                        && endTimeElapsed == other.endTimeElapsed
                        && bgJobCount == other.bgJobCount;
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }

        public void dump(IndentingPrintWriter pw) {
            pw.print(startTimeElapsed);
            pw.print(" -> ");
            pw.print(endTimeElapsed);
            pw.print(" (");
            pw.print(endTimeElapsed - startTimeElapsed);
            pw.print("), ");
            pw.print(bgJobCount);
            pw.print(" bg jobs.");
            pw.println();
        }

        public void dump(@NonNull ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(StateControllerProto.QuotaController.TimingSession.START_TIME_ELAPSED,
                    startTimeElapsed);
            proto.write(StateControllerProto.QuotaController.TimingSession.END_TIME_ELAPSED,
                    endTimeElapsed);
            proto.write(StateControllerProto.QuotaController.TimingSession.BG_JOB_COUNT,
                    bgJobCount);

            proto.end(token);
        }
    }

    private final class Timer {
        private final Package mPkg;
        private final int mUid;

        // List of jobs currently running for this app that started when the app wasn't in the
        // foreground.
        private final ArraySet<JobStatus> mRunningBgJobs = new ArraySet<>();
        private long mStartTimeElapsed;
        private int mBgJobCount;

        Timer(int uid, int userId, String packageName) {
            mPkg = new Package(userId, packageName);
            mUid = uid;
        }

        void startTrackingJob(@NonNull JobStatus jobStatus) {
            if (isTopStartedJob(jobStatus)) {
                // We intentionally don't pay attention to fg state changes after a TOP job has
                // started.
                if (DEBUG) {
                    Slog.v(TAG,
                            "Timer ignoring " + jobStatus.toShortString() + " because isTop");
                }
                return;
            }
            if (DEBUG) Slog.v(TAG, "Starting to track " + jobStatus.toShortString());
            synchronized (mLock) {
                // Always track jobs, even when charging.
                mRunningBgJobs.add(jobStatus);
                if (shouldTrackLocked()) {
                    mBgJobCount++;
                    incrementJobCount(mPkg.userId, mPkg.packageName, 1);
                    if (mRunningBgJobs.size() == 1) {
                        // Started tracking the first job.
                        mStartTimeElapsed = sElapsedRealtimeClock.millis();
                        // Starting the timer means that all cached execution stats are now
                        // incorrect.
                        invalidateAllExecutionStatsLocked(mPkg.userId, mPkg.packageName);
                        scheduleCutoff();
                    }
                }
            }
        }

        void stopTrackingJob(@NonNull JobStatus jobStatus) {
            if (DEBUG) Slog.v(TAG, "Stopping tracking of " + jobStatus.toShortString());
            synchronized (mLock) {
                if (mRunningBgJobs.size() == 0) {
                    // maybeStopTrackingJobLocked can be called when an app cancels a job, so a
                    // timer may not be running when it's asked to stop tracking a job.
                    if (DEBUG) {
                        Slog.d(TAG, "Timer isn't tracking any jobs but still told to stop");
                    }
                    return;
                }
                if (mRunningBgJobs.remove(jobStatus)
                        && !mChargeTracker.isCharging() && mRunningBgJobs.size() == 0) {
                    emitSessionLocked(sElapsedRealtimeClock.millis());
                    cancelCutoff();
                }
            }
        }

        /**
         * Stops tracking all jobs and cancels any pending alarms. This should only be called if
         * the Timer is not going to be used anymore.
         */
        void dropEverything() {
            mRunningBgJobs.clear();
            cancelCutoff();
        }

        private void emitSessionLocked(long nowElapsed) {
            if (mBgJobCount <= 0) {
                // Nothing to emit.
                return;
            }
            TimingSession ts = new TimingSession(mStartTimeElapsed, nowElapsed, mBgJobCount);
            saveTimingSession(mPkg.userId, mPkg.packageName, ts);
            mBgJobCount = 0;
            // Don't reset the tracked jobs list as we need to keep tracking the current number
            // of jobs.
            // However, cancel the currently scheduled cutoff since it's not currently useful.
            cancelCutoff();
        }

        /**
         * Returns true if the Timer is actively tracking, as opposed to passively ref counting
         * during charging.
         */
        public boolean isActive() {
            synchronized (mLock) {
                return mBgJobCount > 0;
            }
        }

        boolean isRunning(JobStatus jobStatus) {
            return mRunningBgJobs.contains(jobStatus);
        }

        long getCurrentDuration(long nowElapsed) {
            synchronized (mLock) {
                return !isActive() ? 0 : nowElapsed - mStartTimeElapsed;
            }
        }

        int getBgJobCount() {
            synchronized (mLock) {
                return mBgJobCount;
            }
        }

        private boolean shouldTrackLocked() {
            return !mChargeTracker.isCharging() && !mForegroundUids.get(mUid);
        }

        void onStateChanged(long nowElapsed, boolean isQuotaFree) {
            synchronized (mLock) {
                if (isQuotaFree) {
                    emitSessionLocked(nowElapsed);
                } else if (shouldTrackLocked()) {
                    // Start timing from unplug.
                    if (mRunningBgJobs.size() > 0) {
                        mStartTimeElapsed = nowElapsed;
                        // NOTE: this does have the unfortunate consequence that if the device is
                        // repeatedly plugged in and unplugged, or an app changes foreground state
                        // very frequently, the job count for a package may be artificially high.
                        mBgJobCount = mRunningBgJobs.size();
                        incrementJobCount(mPkg.userId, mPkg.packageName, mBgJobCount);
                        // Starting the timer means that all cached execution stats are now
                        // incorrect.
                        invalidateAllExecutionStatsLocked(mPkg.userId, mPkg.packageName);
                        // Schedule cutoff since we're now actively tracking for quotas again.
                        scheduleCutoff();
                    }
                }
            }
        }

        void rescheduleCutoff() {
            cancelCutoff();
            scheduleCutoff();
        }

        private void scheduleCutoff() {
            // Each package can only be in one standby bucket, so we only need to have one
            // message per timer. We only need to reschedule when restarting timer or when
            // standby bucket changes.
            synchronized (mLock) {
                if (!isActive()) {
                    return;
                }
                Message msg = mHandler.obtainMessage(MSG_REACHED_QUOTA, mPkg);
                final long timeRemainingMs = getRemainingExecutionTimeLocked(mPkg.userId,
                        mPkg.packageName);
                if (DEBUG) {
                    Slog.i(TAG, "Job for " + mPkg + " has " + timeRemainingMs + "ms left.");
                }
                // If the job was running the entire time, then the system would be up, so it's
                // fine to use uptime millis for these messages.
                mHandler.sendMessageDelayed(msg, timeRemainingMs);
            }
        }

        private void cancelCutoff() {
            mHandler.removeMessages(MSG_REACHED_QUOTA, mPkg);
        }

        public void dump(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
            pw.print("Timer{");
            pw.print(mPkg);
            pw.print("} ");
            if (isActive()) {
                pw.print("started at ");
                pw.print(mStartTimeElapsed);
            } else {
                pw.print("NOT active");
            }
            pw.print(", ");
            pw.print(mBgJobCount);
            pw.print(" running bg jobs");
            pw.println();
            pw.increaseIndent();
            for (int i = 0; i < mRunningBgJobs.size(); i++) {
                JobStatus js = mRunningBgJobs.valueAt(i);
                if (predicate.test(js)) {
                    pw.println(js.toShortString());
                }
            }

            pw.decreaseIndent();
        }

        public void dump(ProtoOutputStream proto, long fieldId, Predicate<JobStatus> predicate) {
            final long token = proto.start(fieldId);

            mPkg.writeToProto(proto, StateControllerProto.QuotaController.Timer.PKG);
            proto.write(StateControllerProto.QuotaController.Timer.IS_ACTIVE, isActive());
            proto.write(StateControllerProto.QuotaController.Timer.START_TIME_ELAPSED,
                    mStartTimeElapsed);
            proto.write(StateControllerProto.QuotaController.Timer.BG_JOB_COUNT, mBgJobCount);
            for (int i = 0; i < mRunningBgJobs.size(); i++) {
                JobStatus js = mRunningBgJobs.valueAt(i);
                if (predicate.test(js)) {
                    js.writeToShortProto(proto,
                            StateControllerProto.QuotaController.Timer.RUNNING_JOBS);
                }
            }

            proto.end(token);
        }
    }

    /**
     * Tracking of app assignments to standby buckets
     */
    final class StandbyTracker extends AppIdleStateChangeListener {

        @Override
        public void onAppIdleStateChanged(final String packageName, final @UserIdInt int userId,
                boolean idle, int bucket, int reason) {
            // Update job bookkeeping out of band.
            BackgroundThread.getHandler().post(() -> {
                final int bucketIndex = JobSchedulerService.standbyBucketToBucketIndex(bucket);
                if (DEBUG) {
                    Slog.i(TAG, "Moving pkg " + string(userId, packageName) + " to bucketIndex "
                            + bucketIndex);
                }
                synchronized (mLock) {
                    ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, packageName);
                    if (jobs == null || jobs.size() == 0) {
                        return;
                    }
                    for (int i = jobs.size() - 1; i >= 0; i--) {
                        JobStatus js = jobs.valueAt(i);
                        js.setStandbyBucket(bucketIndex);
                    }
                    Timer timer = mPkgTimers.get(userId, packageName);
                    if (timer != null && timer.isActive()) {
                        timer.rescheduleCutoff();
                    }
                    if (!mShouldThrottle || maybeUpdateConstraintForPkgLocked(userId,
                            packageName)) {
                        mStateChangedListener.onControllerStateChanged();
                    }
                }
            });
        }

        @Override
        public void onParoleStateChanged(final boolean isParoleOn) {
            mInParole = isParoleOn;
            if (DEBUG) Slog.i(TAG, "Global parole state now " + (isParoleOn ? "ON" : "OFF"));
            // Update job bookkeeping out of band.
            BackgroundThread.getHandler().post(() -> {
                synchronized (mLock) {
                    maybeUpdateAllConstraintsLocked();
                }
            });
        }
    }

    private final class DeleteTimingSessionsFunctor implements Consumer<List<TimingSession>> {
        private final Predicate<TimingSession> mTooOld = new Predicate<TimingSession>() {
            public boolean test(TimingSession ts) {
                return ts.endTimeElapsed <= sElapsedRealtimeClock.millis() - MAX_PERIOD_MS;
            }
        };

        @Override
        public void accept(List<TimingSession> sessions) {
            if (sessions != null) {
                // Remove everything older than MAX_PERIOD_MS time ago.
                sessions.removeIf(mTooOld);
            }
        }
    }

    private final DeleteTimingSessionsFunctor mDeleteOldSessionsFunctor =
            new DeleteTimingSessionsFunctor();

    @VisibleForTesting
    void deleteObsoleteSessionsLocked() {
        mTimingSessions.forEach(mDeleteOldSessionsFunctor);
    }

    private class QcHandler extends Handler {
        QcHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                switch (msg.what) {
                    case MSG_REACHED_QUOTA: {
                        Package pkg = (Package) msg.obj;
                        if (DEBUG) Slog.d(TAG, "Checking if " + pkg + " has reached its quota.");

                        long timeRemainingMs = getRemainingExecutionTimeLocked(pkg.userId,
                                pkg.packageName);
                        if (timeRemainingMs <= 50) {
                            // Less than 50 milliseconds left. Start process of shutting down jobs.
                            if (DEBUG) Slog.d(TAG, pkg + " has reached its quota.");
                            if (maybeUpdateConstraintForPkgLocked(pkg.userId, pkg.packageName)) {
                                mStateChangedListener.onControllerStateChanged();
                            }
                        } else {
                            // This could potentially happen if an old session phases out while a
                            // job is currently running.
                            // Reschedule message
                            Message rescheduleMsg = obtainMessage(MSG_REACHED_QUOTA, pkg);
                            if (DEBUG) {
                                Slog.d(TAG, pkg + " has " + timeRemainingMs + "ms left.");
                            }
                            sendMessageDelayed(rescheduleMsg, timeRemainingMs);
                        }
                        break;
                    }
                    case MSG_CLEAN_UP_SESSIONS:
                        if (DEBUG) Slog.d(TAG, "Cleaning up timing sessions.");
                        deleteObsoleteSessionsLocked();
                        maybeScheduleCleanupAlarmLocked();

                        break;
                    case MSG_CHECK_PACKAGE: {
                        String packageName = (String) msg.obj;
                        int userId = msg.arg1;
                        if (DEBUG) Slog.d(TAG, "Checking pkg " + string(userId, packageName));
                        if (maybeUpdateConstraintForPkgLocked(userId, packageName)) {
                            mStateChangedListener.onControllerStateChanged();
                        }
                        break;
                    }
                    case MSG_UID_PROCESS_STATE_CHANGED: {
                        final int uid = msg.arg1;
                        final int procState = msg.arg2;
                        final int userId = UserHandle.getUserId(uid);
                        final long nowElapsed = sElapsedRealtimeClock.millis();

                        synchronized (mLock) {
                            boolean isQuotaFree;
                            if (procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                                mForegroundUids.put(uid, true);
                                isQuotaFree = true;
                            } else {
                                mForegroundUids.delete(uid);
                                isQuotaFree = false;
                            }
                            // Update Timers first.
                            final int userIndex = mPkgTimers.indexOfKey(userId);
                            if (userIndex != -1) {
                                final int numPkgs = mPkgTimers.numPackagesForUser(userId);
                                for (int p = 0; p < numPkgs; ++p) {
                                    Timer t = mPkgTimers.valueAt(userIndex, p);
                                    if (t != null) {
                                        t.onStateChanged(nowElapsed, isQuotaFree);
                                    }
                                }
                            }
                            if (maybeUpdateConstraintForUidLocked(uid)) {
                                mStateChangedListener.onControllerStateChanged();
                            }
                        }
                        break;
                    }
                }
            }
        }
    }

    private class QcAlarmListener implements AlarmManager.OnAlarmListener {
        private final int mUserId;
        private final String mPackageName;
        private volatile long mTriggerTimeElapsed;

        QcAlarmListener(int userId, String packageName) {
            mUserId = userId;
            mPackageName = packageName;
        }

        boolean isWaiting() {
            return mTriggerTimeElapsed > 0;
        }

        void setTriggerTime(long timeElapsed) {
            mTriggerTimeElapsed = timeElapsed;
        }

        long getTriggerTimeElapsed() {
            return mTriggerTimeElapsed;
        }

        @Override
        public void onAlarm() {
            mHandler.obtainMessage(MSG_CHECK_PACKAGE, mUserId, 0, mPackageName).sendToTarget();
            mTriggerTimeElapsed = 0;
        }
    }

    //////////////////////// TESTING HELPERS /////////////////////////////

    @VisibleForTesting
    long getAllowedTimePerPeriodMs() {
        return mAllowedTimePerPeriodMs;
    }

    @VisibleForTesting
    @NonNull
    int[] getBucketMaxJobCounts() {
        return mMaxBucketJobCounts;
    }

    @VisibleForTesting
    @NonNull
    long[] getBucketWindowSizes() {
        return mBucketPeriodsMs;
    }

    @VisibleForTesting
    @NonNull
    SparseBooleanArray getForegroundUids() {
        return mForegroundUids;
    }

    @VisibleForTesting
    @NonNull
    Handler getHandler() {
        return mHandler;
    }

    @VisibleForTesting
    long getInQuotaBufferMs() {
        return mQuotaBufferMs;
    }

    @VisibleForTesting
    long getMaxExecutionTimeMs() {
        return mMaxExecutionTimeMs;
    }

    @VisibleForTesting
    int getMaxJobCountPerAllowedTime() {
        return mMaxJobCountPerAllowedTime;
    }

    @VisibleForTesting
    @Nullable
    List<TimingSession> getTimingSessions(int userId, String packageName) {
        return mTimingSessions.get(userId, packageName);
    }

    //////////////////////////// DATA DUMP //////////////////////////////

    @Override
    public void dumpControllerStateLocked(final IndentingPrintWriter pw,
            final Predicate<JobStatus> predicate) {
        pw.println("Is throttling: " + mShouldThrottle);
        pw.println("Is charging: " + mChargeTracker.isCharging());
        pw.println("In parole: " + mInParole);
        pw.println();

        pw.print("Foreground UIDs: ");
        pw.println(mForegroundUids.toString());
        pw.println();

        mTrackedJobs.forEach((jobs) -> {
            for (int j = 0; j < jobs.size(); j++) {
                final JobStatus js = jobs.valueAt(j);
                if (!predicate.test(js)) {
                    continue;
                }
                pw.print("#");
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                if (mTopStartedJobs.contains(js)) {
                    pw.print(" (TOP)");
                }
                pw.println();

                pw.increaseIndent();
                pw.print(JobStatus.bucketName(getEffectiveStandbyBucket(js)));
                pw.print(", ");
                if (js.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA)) {
                    pw.print("within quota");
                } else {
                    pw.print("not within quota");
                }
                pw.print(", ");
                pw.print(getRemainingExecutionTimeLocked(js));
                pw.print("ms remaining in quota");
                pw.decreaseIndent();
                pw.println();
            }
        });

        pw.println();
        for (int u = 0; u < mPkgTimers.numUsers(); ++u) {
            final int userId = mPkgTimers.keyAt(u);
            for (int p = 0; p < mPkgTimers.numPackagesForUser(userId); ++p) {
                final String pkgName = mPkgTimers.keyAt(u, p);
                mPkgTimers.valueAt(u, p).dump(pw, predicate);
                pw.println();
                List<TimingSession> sessions = mTimingSessions.get(userId, pkgName);
                if (sessions != null) {
                    pw.increaseIndent();
                    pw.println("Saved sessions:");
                    pw.increaseIndent();
                    for (int j = sessions.size() - 1; j >= 0; j--) {
                        TimingSession session = sessions.get(j);
                        session.dump(pw);
                    }
                    pw.decreaseIndent();
                    pw.decreaseIndent();
                    pw.println();
                }
            }
        }
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.QUOTA);

        proto.write(StateControllerProto.QuotaController.IS_CHARGING, mChargeTracker.isCharging());
        proto.write(StateControllerProto.QuotaController.IS_IN_PAROLE, mInParole);

        for (int i = 0; i < mForegroundUids.size(); ++i) {
            proto.write(StateControllerProto.QuotaController.FOREGROUND_UIDS,
                    mForegroundUids.keyAt(i));
        }

        mTrackedJobs.forEach((jobs) -> {
            for (int j = 0; j < jobs.size(); j++) {
                final JobStatus js = jobs.valueAt(j);
                if (!predicate.test(js)) {
                    continue;
                }
                final long jsToken = proto.start(
                        StateControllerProto.QuotaController.TRACKED_JOBS);
                js.writeToShortProto(proto,
                        StateControllerProto.QuotaController.TrackedJob.INFO);
                proto.write(StateControllerProto.QuotaController.TrackedJob.SOURCE_UID,
                        js.getSourceUid());
                proto.write(
                        StateControllerProto.QuotaController.TrackedJob.EFFECTIVE_STANDBY_BUCKET,
                        getEffectiveStandbyBucket(js));
                proto.write(StateControllerProto.QuotaController.TrackedJob.IS_TOP_STARTED_JOB,
                        mTopStartedJobs.contains(js));
                proto.write(StateControllerProto.QuotaController.TrackedJob.HAS_QUOTA,
                        js.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
                proto.write(StateControllerProto.QuotaController.TrackedJob.REMAINING_QUOTA_MS,
                        getRemainingExecutionTimeLocked(js));
                proto.end(jsToken);
            }
        });

        for (int u = 0; u < mPkgTimers.numUsers(); ++u) {
            final int userId = mPkgTimers.keyAt(u);
            for (int p = 0; p < mPkgTimers.numPackagesForUser(userId); ++p) {
                final String pkgName = mPkgTimers.keyAt(u, p);
                final long psToken = proto.start(
                        StateControllerProto.QuotaController.PACKAGE_STATS);
                mPkgTimers.valueAt(u, p).dump(proto,
                        StateControllerProto.QuotaController.PackageStats.TIMER, predicate);

                List<TimingSession> sessions = mTimingSessions.get(userId, pkgName);
                if (sessions != null) {
                    for (int j = sessions.size() - 1; j >= 0; j--) {
                        TimingSession session = sessions.get(j);
                        session.dump(proto,
                                StateControllerProto.QuotaController.PackageStats.SAVED_SESSIONS);
                    }
                }

                proto.end(psToken);
            }
        }

        proto.end(mToken);
        proto.end(token);
    }
}
