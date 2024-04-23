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

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.EXEMPTED_INDEX;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.NEVER_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.UidObserver;
import android.app.job.JobInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.UsageEventListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserPackage;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseArrayMap;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.util.SparseSetArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.LocalServices;
import com.android.server.PowerAllowlistInternal;
import com.android.server.job.ConstantsProto;
import com.android.server.job.Flags;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;
import com.android.server.utils.AlarmQueue;

import dalvik.annotation.optimization.NeverCompile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Controller that tracks whether an app has exceeded its standby bucket quota.
 *
 * With initial defaults, each app in each bucket is given 10 minutes to run within its respective
 * time window. Active jobs can run indefinitely, working set jobs can run for 10 minutes within a
 * 2 hour window, frequent jobs get to run 10 minutes in an 8 hour window, and rare jobs get to run
 * 10 minutes in a 24 hour window. The windows are rolling, so as soon as a job would have some
 * quota based on its bucket, it will be eligible to run. When a job's bucket changes, its new
 * quota is immediately applied to it.
 *
 * Job and session count limits are included to prevent abuse/spam. Each bucket has its own limit on
 * the number of jobs or sessions that can run within the window. Regardless of bucket, apps will
 * not be allowed to run more than 20 jobs within the past 10 minutes.
 *
 * Jobs are throttled while an app is not in a foreground state. All jobs are allowed to run
 * freely when an app enters the foreground state and are restricted when the app leaves the
 * foreground state. However, jobs that are started while the app is in the TOP state do not count
 * towards any quota and are not restricted regardless of the app's state change.
 *
 * Jobs will not be throttled when the device is charging. The device is considered to be charging
 * once the {@link BatteryManager#ACTION_CHARGING} intent has been broadcast.
 *
 * Note: all limits are enforced per bucket window unless explicitly stated otherwise.
 * All stated values are configurable and subject to change. See {@link QcConstants} for current
 * defaults.
 *
 * Test: atest com.android.server.job.controllers.QuotaControllerTest
 */
public final class QuotaController extends StateController {
    private static final String TAG = "JobScheduler.Quota";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private static final String ALARM_TAG_CLEANUP = "*job.cleanup*";
    private static final String ALARM_TAG_QUOTA_CHECK = "*job.quota_check*";

    private static final int SYSTEM_APP_CHECK_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.GET_PERMISSIONS | PackageManager.MATCH_KNOWN_PACKAGES;

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

        public long allowedTimePerPeriodMs;
        public long windowSizeMs;
        public int jobCountLimit;
        public int sessionCountLimit;

        /** The total amount of time the app ran in its respective bucket window size. */
        public long executionTimeInWindowMs;
        public int bgJobCountInWindow;

        /** The total amount of time the app ran in the last {@link #MAX_PERIOD_MS}. */
        public long executionTimeInMaxPeriodMs;
        public int bgJobCountInMaxPeriod;

        /**
         * The number of {@link TimingSession TimingSessions} within the bucket window size.
         * This will include sessions that started before the window as long as they end within
         * the window.
         */
        public int sessionCountInWindow;

        /**
         * The time after which the app will be under the bucket quota and can start running jobs
         * again. This is only valid if
         * {@link #executionTimeInWindowMs} >= {@link #mAllowedTimePerPeriodMs},
         * {@link #executionTimeInMaxPeriodMs} >= {@link #mMaxExecutionTimeMs},
         * {@link #bgJobCountInWindow} >= {@link #jobCountLimit}, or
         * {@link #sessionCountInWindow} >= {@link #sessionCountLimit}.
         */
        public long inQuotaTimeElapsed;

        /**
         * The time after which {@link #jobCountInRateLimitingWindow} should be considered invalid,
         * in the elapsed realtime timebase.
         */
        public long jobRateLimitExpirationTimeElapsed;

        /**
         * The number of jobs that ran in at least the last {@link #mRateLimitingWindowMs}.
         * It may contain a few stale entries since cleanup won't happen exactly every
         * {@link #mRateLimitingWindowMs}.
         */
        public int jobCountInRateLimitingWindow;

        /**
         * The time after which {@link #sessionCountInRateLimitingWindow} should be considered
         * invalid, in the elapsed realtime timebase.
         */
        public long sessionRateLimitExpirationTimeElapsed;

        /**
         * The number of {@link TimingSession TimingSessions} that ran in at least the last
         * {@link #mRateLimitingWindowMs}. It may contain a few stale entries since cleanup won't
         * happen exactly every {@link #mRateLimitingWindowMs}. This should only be considered
         * valid before elapsed realtime has reached {@link #sessionRateLimitExpirationTimeElapsed}.
         */
        public int sessionCountInRateLimitingWindow;

        @Override
        public String toString() {
            return "expirationTime=" + expirationTimeElapsed + ", "
                    + "allowedTimePerPeriodMs=" + allowedTimePerPeriodMs + ", "
                    + "windowSizeMs=" + windowSizeMs + ", "
                    + "jobCountLimit=" + jobCountLimit + ", "
                    + "sessionCountLimit=" + sessionCountLimit + ", "
                    + "executionTimeInWindow=" + executionTimeInWindowMs + ", "
                    + "bgJobCountInWindow=" + bgJobCountInWindow + ", "
                    + "executionTimeInMaxPeriod=" + executionTimeInMaxPeriodMs + ", "
                    + "bgJobCountInMaxPeriod=" + bgJobCountInMaxPeriod + ", "
                    + "sessionCountInWindow=" + sessionCountInWindow + ", "
                    + "inQuotaTime=" + inQuotaTimeElapsed + ", "
                    + "rateLimitJobCountExpirationTime=" + jobRateLimitExpirationTimeElapsed + ", "
                    + "rateLimitJobCountWindow=" + jobCountInRateLimitingWindow + ", "
                    + "rateLimitSessionCountExpirationTime="
                    + sessionRateLimitExpirationTimeElapsed + ", "
                    + "rateLimitSessionCountWindow=" + sessionCountInRateLimitingWindow;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ExecutionStats) {
                ExecutionStats other = (ExecutionStats) obj;
                return this.expirationTimeElapsed == other.expirationTimeElapsed
                        && this.allowedTimePerPeriodMs == other.allowedTimePerPeriodMs
                        && this.windowSizeMs == other.windowSizeMs
                        && this.jobCountLimit == other.jobCountLimit
                        && this.sessionCountLimit == other.sessionCountLimit
                        && this.executionTimeInWindowMs == other.executionTimeInWindowMs
                        && this.bgJobCountInWindow == other.bgJobCountInWindow
                        && this.executionTimeInMaxPeriodMs == other.executionTimeInMaxPeriodMs
                        && this.sessionCountInWindow == other.sessionCountInWindow
                        && this.bgJobCountInMaxPeriod == other.bgJobCountInMaxPeriod
                        && this.inQuotaTimeElapsed == other.inQuotaTimeElapsed
                        && this.jobRateLimitExpirationTimeElapsed
                                == other.jobRateLimitExpirationTimeElapsed
                        && this.jobCountInRateLimitingWindow == other.jobCountInRateLimitingWindow
                        && this.sessionRateLimitExpirationTimeElapsed
                                == other.sessionRateLimitExpirationTimeElapsed
                        && this.sessionCountInRateLimitingWindow
                                == other.sessionCountInRateLimitingWindow;
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            int result = 0;
            result = 31 * result + hashLong(expirationTimeElapsed);
            result = 31 * result + hashLong(allowedTimePerPeriodMs);
            result = 31 * result + hashLong(windowSizeMs);
            result = 31 * result + hashLong(jobCountLimit);
            result = 31 * result + hashLong(sessionCountLimit);
            result = 31 * result + hashLong(executionTimeInWindowMs);
            result = 31 * result + bgJobCountInWindow;
            result = 31 * result + hashLong(executionTimeInMaxPeriodMs);
            result = 31 * result + bgJobCountInMaxPeriod;
            result = 31 * result + sessionCountInWindow;
            result = 31 * result + hashLong(inQuotaTimeElapsed);
            result = 31 * result + hashLong(jobRateLimitExpirationTimeElapsed);
            result = 31 * result + jobCountInRateLimitingWindow;
            result = 31 * result + hashLong(sessionRateLimitExpirationTimeElapsed);
            result = 31 * result + sessionCountInRateLimitingWindow;
            return result;
        }
    }

    /** List of all tracked jobs keyed by source package-userId combo. */
    private final SparseArrayMap<String, ArraySet<JobStatus>> mTrackedJobs = new SparseArrayMap<>();

    /** Timer for each package-userId combo. */
    private final SparseArrayMap<String, Timer> mPkgTimers = new SparseArrayMap<>();

    /** Timer for expedited jobs for each package-userId combo. */
    private final SparseArrayMap<String, Timer> mEJPkgTimers = new SparseArrayMap<>();

    /** List of all regular timing sessions for a package-userId combo, in chronological order. */
    private final SparseArrayMap<String, List<TimedEvent>> mTimingEvents = new SparseArrayMap<>();

    /**
     * List of all expedited job timing sessions for a package-userId combo, in chronological order.
     */
    private final SparseArrayMap<String, List<TimedEvent>> mEJTimingSessions =
            new SparseArrayMap<>();

    /**
     * Queue to track and manage when each package comes back within quota.
     */
    @GuardedBy("mLock")
    private final InQuotaAlarmQueue mInQuotaAlarmQueue;

    /** Cached calculation results for each app, with the standby buckets as the array indices. */
    private final SparseArrayMap<String, ExecutionStats[]> mExecutionStatsCache =
            new SparseArrayMap<>();

    private final SparseArrayMap<String, ShrinkableDebits> mEJStats = new SparseArrayMap<>();

    private final SparseArrayMap<String, TopAppTimer> mTopAppTrackers = new SparseArrayMap<>();

    /** List of UIDs currently in the foreground. */
    private final SparseBooleanArray mForegroundUids = new SparseBooleanArray();

    /**
     * List of jobs that started while the UID was in the TOP state. There will usually be no more
     * than {@value JobConcurrencyManager#MAX_STANDARD_JOB_CONCURRENCY} running at once, so an
     * ArraySet is fine.
     */
    private final ArraySet<JobStatus> mTopStartedJobs = new ArraySet<>();

    /** Current set of UIDs on the temp allowlist. */
    private final SparseBooleanArray mTempAllowlistCache = new SparseBooleanArray();

    /**
     * Mapping of UIDs to when their temp allowlist grace period ends (in the elapsed
     * realtime timebase).
     */
    private final SparseLongArray mTempAllowlistGraceCache = new SparseLongArray();

    /** Current set of UIDs in the {@link ActivityManager#PROCESS_STATE_TOP} state. */
    private final SparseBooleanArray mTopAppCache = new SparseBooleanArray();

    /**
     * Mapping of UIDs to the when their top app grace period ends (in the elapsed realtime
     * timebase).
     */
    private final SparseLongArray mTopAppGraceCache = new SparseLongArray();

    private final AlarmManager mAlarmManager;
    private final QcHandler mHandler;
    private final QcConstants mQcConstants;

    private final BackgroundJobsController mBackgroundJobsController;
    private final ConnectivityController mConnectivityController;

    /** How much time each app will have to run jobs within their standby bucket window. */
    private final long[] mAllowedTimePerPeriodMs = new long[]{
            QcConstants.DEFAULT_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS,
            QcConstants.DEFAULT_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
            QcConstants.DEFAULT_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS,
            QcConstants.DEFAULT_ALLOWED_TIME_PER_PERIOD_RARE_MS,
            0, // NEVER
            QcConstants.DEFAULT_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
            QcConstants.DEFAULT_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS
    };

    /**
     * The maximum amount of time an app can have its jobs running within a {@link #MAX_PERIOD_MS}
     * window.
     */
    private long mMaxExecutionTimeMs = QcConstants.DEFAULT_MAX_EXECUTION_TIME_MS;

    /**
     * How much time the app should have before transitioning from out-of-quota to in-quota.
     * This should not affect processing if the app is already in-quota.
     */
    private long mQuotaBufferMs = QcConstants.DEFAULT_IN_QUOTA_BUFFER_MS;

    /**
     * {@link #mMaxExecutionTimeMs} - {@link #mQuotaBufferMs}. This can be used to determine when an
     * app will have enough quota to transition from out-of-quota to in-quota.
     */
    private long mMaxExecutionTimeIntoQuotaMs = mMaxExecutionTimeMs - mQuotaBufferMs;

    /** The period of time used to rate limit recently run jobs. */
    private long mRateLimitingWindowMs = QcConstants.DEFAULT_RATE_LIMITING_WINDOW_MS;

    /** The maximum number of jobs that can run within the past {@link #mRateLimitingWindowMs}. */
    private int mMaxJobCountPerRateLimitingWindow =
            QcConstants.DEFAULT_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW;

    /**
     * The maximum number of {@link TimingSession TimingSessions} that can run within the past
     * {@link #mRateLimitingWindowMs}.
     */
    private int mMaxSessionCountPerRateLimitingWindow =
            QcConstants.DEFAULT_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW;

    private long mNextCleanupTimeElapsed = 0;
    private final AlarmManager.OnAlarmListener mSessionCleanupAlarmListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    mHandler.obtainMessage(MSG_CLEAN_UP_SESSIONS).sendToTarget();
                }
            };

    private class QcUidObserver extends UidObserver {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mHandler.obtainMessage(MSG_UID_PROCESS_STATE_CHANGED, uid, procState).sendToTarget();
        }
    }

    /**
     * The rolling window size for each standby bucket. Within each window, an app will have 10
     * minutes to run its jobs.
     */
    private final long[] mBucketPeriodsMs = new long[]{
            QcConstants.DEFAULT_WINDOW_SIZE_ACTIVE_MS,
            QcConstants.DEFAULT_WINDOW_SIZE_WORKING_MS,
            QcConstants.DEFAULT_WINDOW_SIZE_FREQUENT_MS,
            QcConstants.DEFAULT_WINDOW_SIZE_RARE_MS,
            0, // NEVER
            QcConstants.DEFAULT_WINDOW_SIZE_RESTRICTED_MS,
            QcConstants.DEFAULT_WINDOW_SIZE_EXEMPTED_MS
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
    private final int[] mMaxBucketJobCounts = new int[]{
            QcConstants.DEFAULT_MAX_JOB_COUNT_ACTIVE,
            QcConstants.DEFAULT_MAX_JOB_COUNT_WORKING,
            QcConstants.DEFAULT_MAX_JOB_COUNT_FREQUENT,
            QcConstants.DEFAULT_MAX_JOB_COUNT_RARE,
            0, // NEVER
            QcConstants.DEFAULT_MAX_JOB_COUNT_RESTRICTED,
            QcConstants.DEFAULT_MAX_JOB_COUNT_EXEMPTED
    };

    /**
     * The maximum number of {@link TimingSession TimingSessions} based on its standby bucket.
     * For each max value count in the array, the app will not be allowed to have more than that
     * many number of {@link TimingSession TimingSessions} within the latest time interval of its
     * rolling window size.
     *
     * @see #mBucketPeriodsMs
     */
    private final int[] mMaxBucketSessionCounts = new int[]{
            QcConstants.DEFAULT_MAX_SESSION_COUNT_ACTIVE,
            QcConstants.DEFAULT_MAX_SESSION_COUNT_WORKING,
            QcConstants.DEFAULT_MAX_SESSION_COUNT_FREQUENT,
            QcConstants.DEFAULT_MAX_SESSION_COUNT_RARE,
            0, // NEVER
            QcConstants.DEFAULT_MAX_SESSION_COUNT_RESTRICTED,
            QcConstants.DEFAULT_MAX_SESSION_COUNT_EXEMPTED,
    };

    /**
     * Treat two distinct {@link TimingSession TimingSessions} as the same if they start and end
     * within this amount of time of each other.
     */
    private long mTimingSessionCoalescingDurationMs =
            QcConstants.DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS;

    /**
     * The rolling window size for each standby bucket. Within each window, an app will have 10
     * minutes to run its jobs.
     */
    private final long[] mEJLimitsMs = new long[]{
            QcConstants.DEFAULT_EJ_LIMIT_ACTIVE_MS,
            QcConstants.DEFAULT_EJ_LIMIT_WORKING_MS,
            QcConstants.DEFAULT_EJ_LIMIT_FREQUENT_MS,
            QcConstants.DEFAULT_EJ_LIMIT_RARE_MS,
            0, // NEVER
            QcConstants.DEFAULT_EJ_LIMIT_RESTRICTED_MS,
            QcConstants.DEFAULT_EJ_LIMIT_EXEMPTED_MS
    };

    private long mEjLimitAdditionInstallerMs = QcConstants.DEFAULT_EJ_LIMIT_ADDITION_INSTALLER_MS;

    private long mEjLimitAdditionSpecialMs = QcConstants.DEFAULT_EJ_LIMIT_ADDITION_SPECIAL_MS;

    /**
     * The period of time used to calculate expedited job sessions. Apps can only have expedited job
     * sessions totalling {@link #mEJLimitsMs}[bucket within this period of time (without factoring
     * in any rewards or free EJs).
     */
    private long mEJLimitWindowSizeMs = QcConstants.DEFAULT_EJ_WINDOW_SIZE_MS;

    /**
     * Length of time used to split an app's top time into chunks.
     */
    private long mEJTopAppTimeChunkSizeMs = QcConstants.DEFAULT_EJ_TOP_APP_TIME_CHUNK_SIZE_MS;

    /**
     * How much EJ quota to give back to an app based on the number of top app time chunks it had.
     */
    private long mEJRewardTopAppMs = QcConstants.DEFAULT_EJ_REWARD_TOP_APP_MS;

    /**
     * How much EJ quota to give back to an app based on each non-top user interaction.
     */
    private long mEJRewardInteractionMs = QcConstants.DEFAULT_EJ_REWARD_INTERACTION_MS;

    /**
     * How much EJ quota to give back to an app based on each notification seen event.
     */
    private long mEJRewardNotificationSeenMs = QcConstants.DEFAULT_EJ_REWARD_NOTIFICATION_SEEN_MS;

    private long mEJGracePeriodTempAllowlistMs =
            QcConstants.DEFAULT_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS;

    private long mEJGracePeriodTopAppMs = QcConstants.DEFAULT_EJ_GRACE_PERIOD_TOP_APP_MS;

    private long mQuotaBumpAdditionalDurationMs =
            QcConstants.DEFAULT_QUOTA_BUMP_ADDITIONAL_DURATION_MS;
    private int mQuotaBumpAdditionalJobCount = QcConstants.DEFAULT_QUOTA_BUMP_ADDITIONAL_JOB_COUNT;
    private int mQuotaBumpAdditionalSessionCount =
            QcConstants.DEFAULT_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT;
    private long mQuotaBumpWindowSizeMs = QcConstants.DEFAULT_QUOTA_BUMP_WINDOW_SIZE_MS;
    private int mQuotaBumpLimit = QcConstants.DEFAULT_QUOTA_BUMP_LIMIT;

    /**
     * List of system apps with the {@link android.Manifest.permission#INSTALL_PACKAGES} permission
     * granted for each user.
     */
    private final SparseSetArray<String> mSystemInstallers = new SparseSetArray<>();

    /** An app has reached its quota. The message should contain a {@link UserPackage} object. */
    @VisibleForTesting
    static final int MSG_REACHED_TIME_QUOTA = 0;
    /** Drop any old timing sessions. */
    private static final int MSG_CLEAN_UP_SESSIONS = 1;
    /** Check if a package is now within its quota. */
    private static final int MSG_CHECK_PACKAGE = 2;
    /** Process state for a UID has changed. */
    private static final int MSG_UID_PROCESS_STATE_CHANGED = 3;
    /**
     * An app has reached its expedited job quota. The message should contain a {@link UserPackage}
     * object.
     */
    @VisibleForTesting
    static final int MSG_REACHED_EJ_TIME_QUOTA = 4;
    /**
     * Process a new {@link UsageEvents.Event}. The event will be the message's object and the
     * userId will the first arg.
     */
    private static final int MSG_PROCESS_USAGE_EVENT = 5;
    /** A UID's free quota grace period has ended. */
    @VisibleForTesting
    static final int MSG_END_GRACE_PERIOD = 6;
    /**
     * An app has reached its job count quota. The message should contain a {@link UserPackage}
     * object.
     */
    static final int MSG_REACHED_COUNT_QUOTA = 7;

    public QuotaController(@NonNull JobSchedulerService service,
            @NonNull BackgroundJobsController backgroundJobsController,
            @NonNull ConnectivityController connectivityController) {
        super(service);
        mHandler = new QcHandler(AppSchedulingModuleThread.get().getLooper());
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mQcConstants = new QcConstants();
        mBackgroundJobsController = backgroundJobsController;
        mConnectivityController = connectivityController;
        mInQuotaAlarmQueue =
                new InQuotaAlarmQueue(mContext, AppSchedulingModuleThread.get().getLooper());

        // Set up the app standby bucketing tracker
        AppStandbyInternal appStandby = LocalServices.getService(AppStandbyInternal.class);
        appStandby.addListener(new StandbyTracker());

        UsageStatsManagerInternal usmi = LocalServices.getService(UsageStatsManagerInternal.class);
        usmi.registerListener(new UsageEventTracker());

        PowerAllowlistInternal pai = LocalServices.getService(PowerAllowlistInternal.class);
        pai.registerTempAllowlistChangeListener(new TempAllowlistTracker());

        try {
            ActivityManager.getService().registerUidObserver(new QcUidObserver(),
                    ActivityManager.UID_OBSERVER_PROCSTATE,
                    ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, null);
            ActivityManager.getService().registerUidObserver(new QcUidObserver(),
                    ActivityManager.UID_OBSERVER_PROCSTATE,
                    ActivityManager.PROCESS_STATE_TOP, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }
    }

    @Override
    public void onSystemServicesReady() {
        synchronized (mLock) {
            cacheInstallerPackagesLocked(UserHandle.USER_SYSTEM);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, pkgName);
        if (jobs == null) {
            jobs = new ArraySet<>();
            mTrackedJobs.add(userId, pkgName, jobs);
        }
        jobs.add(jobStatus);
        jobStatus.setTrackingController(JobStatus.TRACKING_QUOTA);
        final boolean isWithinQuota = isWithinQuotaLocked(jobStatus);
        final boolean isWithinEJQuota =
                jobStatus.isRequestedExpeditedJob() && isWithinEJQuotaLocked(jobStatus);
        setConstraintSatisfied(jobStatus, nowElapsed, isWithinQuota, isWithinEJQuota);
        final boolean outOfEJQuota;
        if (jobStatus.isRequestedExpeditedJob()) {
            setExpeditedQuotaApproved(jobStatus, nowElapsed, isWithinEJQuota);
            outOfEJQuota = !isWithinEJQuota;
        } else {
            outOfEJQuota = false;
        }
        if (!isWithinQuota || outOfEJQuota) {
            maybeScheduleStartAlarmLocked(userId, pkgName, jobStatus.getEffectiveStandbyBucket());
        }
    }

    @Override
    @GuardedBy("mLock")
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        if (DEBUG) {
            Slog.d(TAG, "Prepping for " + jobStatus.toShortString());
        }

        final int uid = jobStatus.getSourceUid();
        if (mTopAppCache.get(uid)) {
            if (DEBUG) {
                Slog.d(TAG, jobStatus.toShortString() + " is top started job");
            }
            mTopStartedJobs.add(jobStatus);
            // Top jobs won't count towards quota so there's no need to involve the Timer.
            return;
        } else if (jobStatus.shouldTreatAsUserInitiatedJob()) {
            // User-initiated jobs won't count towards quota.
            return;
        }

        final int userId = jobStatus.getSourceUserId();
        final String packageName = jobStatus.getSourcePackageName();
        final SparseArrayMap<String, Timer> timerMap =
                jobStatus.shouldTreatAsExpeditedJob() ? mEJPkgTimers : mPkgTimers;
        Timer timer = timerMap.get(userId, packageName);
        if (timer == null) {
            timer = new Timer(uid, userId, packageName, !jobStatus.shouldTreatAsExpeditedJob());
            timerMap.add(userId, packageName, timer);
        }
        timer.startTrackingJobLocked(jobStatus);
    }

    @Override
    @GuardedBy("mLock")
    public void unprepareFromExecutionLocked(JobStatus jobStatus) {
        Timer timer = mPkgTimers.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        if (timer != null) {
            timer.stopTrackingJob(jobStatus);
        }
        if (jobStatus.isRequestedExpeditedJob()) {
            timer = mEJPkgTimers.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
            if (timer != null) {
                timer.stopTrackingJob(jobStatus);
            }
        }
        mTopStartedJobs.remove(jobStatus);
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob) {
        if (jobStatus.clearTrackingController(JobStatus.TRACKING_QUOTA)) {
            unprepareFromExecutionLocked(jobStatus);
            final int userId = jobStatus.getSourceUserId();
            final String pkgName = jobStatus.getSourcePackageName();
            ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, pkgName);
            if (jobs != null && jobs.remove(jobStatus) && jobs.size() == 0) {
                mInQuotaAlarmQueue.removeAlarmForKey(UserPackage.of(userId, pkgName));
            }
        }
    }

    @Override
    public void onAppRemovedLocked(String packageName, int uid) {
        if (packageName == null) {
            Slog.wtf(TAG, "Told app removed but given null package name.");
            return;
        }
        clearAppStatsLocked(UserHandle.getUserId(uid), packageName);
        if (mService.getPackagesForUidLocked(uid) == null) {
            // All packages in the UID have been removed. It's safe to remove things based on
            // UID alone.
            mForegroundUids.delete(uid);
            mTempAllowlistCache.delete(uid);
            mTempAllowlistGraceCache.delete(uid);
            mTopAppCache.delete(uid);
            mTopAppGraceCache.delete(uid);
        }
    }

    @Override
    public void onUserAddedLocked(int userId) {
        cacheInstallerPackagesLocked(userId);
    }

    @Override
    public void onUserRemovedLocked(int userId) {
        mTrackedJobs.delete(userId);
        mPkgTimers.delete(userId);
        mEJPkgTimers.delete(userId);
        mTimingEvents.delete(userId);
        mEJTimingSessions.delete(userId);
        mInQuotaAlarmQueue.removeAlarmsForUserId(userId);
        mExecutionStatsCache.delete(userId);
        mEJStats.delete(userId);
        mSystemInstallers.remove(userId);
        mTopAppTrackers.delete(userId);
    }

    @Override
    public void onBatteryStateChangedLocked() {
        handleNewChargingStateLocked();
    }

    /** Drop all historical stats and stop tracking any active sessions for the specified app. */
    public void clearAppStatsLocked(int userId, @NonNull String packageName) {
        mTrackedJobs.delete(userId, packageName);
        Timer timer = mPkgTimers.delete(userId, packageName);
        if (timer != null) {
            if (timer.isActive()) {
                Slog.e(TAG, "clearAppStats called before Timer turned off.");
                timer.dropEverythingLocked();
            }
        }
        timer = mEJPkgTimers.delete(userId, packageName);
        if (timer != null) {
            if (timer.isActive()) {
                Slog.e(TAG, "clearAppStats called before EJ Timer turned off.");
                timer.dropEverythingLocked();
            }
        }
        mTimingEvents.delete(userId, packageName);
        mEJTimingSessions.delete(userId, packageName);
        mInQuotaAlarmQueue.removeAlarmForKey(UserPackage.of(userId, packageName));
        mExecutionStatsCache.delete(userId, packageName);
        mEJStats.delete(userId, packageName);
        mTopAppTrackers.delete(userId, packageName);
    }

    private void cacheInstallerPackagesLocked(int userId) {
        final List<PackageInfo> packages = mContext.getPackageManager()
                .getInstalledPackagesAsUser(SYSTEM_APP_CHECK_FLAGS, userId);
        for (int i = packages.size() - 1; i >= 0; --i) {
            final PackageInfo pi = packages.get(i);
            final ApplicationInfo ai = pi.applicationInfo;
            final int idx = ArrayUtils.indexOf(
                    pi.requestedPermissions, Manifest.permission.INSTALL_PACKAGES);

            if (idx >= 0 && ai != null && PackageManager.PERMISSION_GRANTED
                    == mContext.checkPermission(Manifest.permission.INSTALL_PACKAGES, -1, ai.uid)) {
                mSystemInstallers.add(UserHandle.getUserId(ai.uid), pi.packageName);
            }
        }
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
    private boolean isTopStartedJobLocked(@NonNull final JobStatus jobStatus) {
        return mTopStartedJobs.contains(jobStatus);
    }

    /** Returns the maximum amount of time this job could run for. */
    @GuardedBy("mLock")
    public long getMaxJobExecutionTimeMsLocked(@NonNull final JobStatus jobStatus) {
        if (!jobStatus.shouldTreatAsExpeditedJob()) {
            // If quota is currently "free", then the job can run for the full amount of time,
            // regardless of bucket (hence using charging instead of isQuotaFreeLocked()).
            if (mService.isBatteryCharging()) {
                return mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;
            }
            // The top and foreground cases here were added because apps in those states
            // aren't really restricted and the work could be something the user is
            // waiting for. Now that user-initiated jobs are a defined concept, we may
            // not need these exemptions as much. However, UIJs are currently limited
            // (as of UDC) to data transfer work. There may be other work that could
            // rely on this exception. Once we add more UIJ types, we can re-evaluate
            // the need for these exceptions.
            // TODO: re-evaluate the need for these exceptions
            final boolean isInPrivilegedState = mTopAppCache.get(jobStatus.getSourceUid())
                    || isTopStartedJobLocked(jobStatus)
                    || isUidInForeground(jobStatus.getSourceUid());
            final boolean isJobImportant = jobStatus.getEffectivePriority() >= JobInfo.PRIORITY_HIGH
                    || (jobStatus.getFlags() & JobInfo.FLAG_IMPORTANT_WHILE_FOREGROUND) != 0;
            if (isInPrivilegedState && isJobImportant) {
                return mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;
            }
            return getTimeUntilQuotaConsumedLocked(
                    jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        }

        // Expedited job.
        if (mService.isBatteryCharging()) {
            return mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;
        }
        if (jobStatus.getEffectiveStandbyBucket() == EXEMPTED_INDEX) {
            return Math.max(mEJLimitsMs[EXEMPTED_INDEX] / 2,
                    getTimeUntilEJQuotaConsumedLocked(
                            jobStatus.getSourceUserId(), jobStatus.getSourcePackageName()));
        }
        if (mTopAppCache.get(jobStatus.getSourceUid()) || isTopStartedJobLocked(jobStatus)) {
            return Math.max(mEJLimitsMs[ACTIVE_INDEX] / 2,
                    getTimeUntilEJQuotaConsumedLocked(
                            jobStatus.getSourceUserId(), jobStatus.getSourcePackageName()));
        }
        if (isUidInForeground(jobStatus.getSourceUid())) {
            return Math.max(mEJLimitsMs[WORKING_INDEX] / 2,
                    getTimeUntilEJQuotaConsumedLocked(
                            jobStatus.getSourceUserId(), jobStatus.getSourcePackageName()));
        }
        return getTimeUntilEJQuotaConsumedLocked(
                jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
    }

    private boolean hasTempAllowlistExemptionLocked(int sourceUid, int standbyBucket,
            long nowElapsed) {
        if (standbyBucket == RESTRICTED_INDEX || standbyBucket == NEVER_INDEX) {
            // Don't let RESTRICTED apps get free quota from the temp allowlist.
            // TODO: consider granting the exemption to RESTRICTED apps if the temp allowlist allows
            // them to start FGS
            return false;
        }
        final long tempAllowlistGracePeriodEndElapsed = mTempAllowlistGraceCache.get(sourceUid);
        return mTempAllowlistCache.get(sourceUid)
                || nowElapsed < tempAllowlistGracePeriodEndElapsed;
    }

    /** @return true if the job is within expedited job quota. */
    @GuardedBy("mLock")
    public boolean isWithinEJQuotaLocked(@NonNull final JobStatus jobStatus) {
        if (isQuotaFreeLocked(jobStatus.getEffectiveStandbyBucket())) {
            return true;
        }
        // A job is within quota if one of the following is true:
        //   1. the app is currently in the foreground
        //   2. the app overall is within its quota
        //   3. It's on the temp allowlist (or within the grace period)
        if (isTopStartedJobLocked(jobStatus) || isUidInForeground(jobStatus.getSourceUid())) {
            return true;
        }

        final long nowElapsed = sElapsedRealtimeClock.millis();
        if (hasTempAllowlistExemptionLocked(jobStatus.getSourceUid(),
                jobStatus.getEffectiveStandbyBucket(), nowElapsed)) {
            return true;
        }

        final long topAppGracePeriodEndElapsed = mTopAppGraceCache.get(jobStatus.getSourceUid());
        final boolean hasTopAppExemption = mTopAppCache.get(jobStatus.getSourceUid())
                || nowElapsed < topAppGracePeriodEndElapsed;
        if (hasTopAppExemption) {
            return true;
        }

        return 0 < getRemainingEJExecutionTimeLocked(
                jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
    }

    @NonNull
    @VisibleForTesting
    ShrinkableDebits getEJDebitsLocked(final int userId, @NonNull final String packageName) {
        ShrinkableDebits debits = mEJStats.get(userId, packageName);
        if (debits == null) {
            debits = new ShrinkableDebits(
                    JobSchedulerService.standbyBucketForPackage(
                            packageName, userId, sElapsedRealtimeClock.millis())
            );
            mEJStats.add(userId, packageName, debits);
        }
        return debits;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    boolean isWithinQuotaLocked(@NonNull final JobStatus jobStatus) {
        final int standbyBucket = jobStatus.getEffectiveStandbyBucket();
        // A job is within quota if one of the following is true:
        //   1. it was started while the app was in the TOP state
        //   2. the app is currently in the foreground
        //   3. the app overall is within its quota
        if (!Flags.countQuotaFix()) {
            return jobStatus.shouldTreatAsUserInitiatedJob()
                    || isTopStartedJobLocked(jobStatus)
                    || isUidInForeground(jobStatus.getSourceUid())
                    || isWithinQuotaLocked(
                    jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(), standbyBucket);
        }

        if (jobStatus.shouldTreatAsUserInitiatedJob()
                || isTopStartedJobLocked(jobStatus)
                || isUidInForeground(jobStatus.getSourceUid())) {
            return true;
        }

        if (standbyBucket == NEVER_INDEX) return false;

        if (isQuotaFreeLocked(standbyBucket)) return true;

        final ExecutionStats stats = getExecutionStatsLocked(jobStatus.getSourceUserId(),
                jobStatus.getSourcePackageName(), standbyBucket);
        if (!(getRemainingExecutionTimeLocked(stats) > 0)) {
            // Out of execution time quota.
            return false;
        }

        if (standbyBucket != RESTRICTED_INDEX && mService.isCurrentlyRunningLocked(jobStatus)) {
            // Running job is considered as within quota except for the restricted one, which
            // requires additional constraints.
            return true;
        }

        // Check if the app is within job count quota.
        return isUnderJobCountQuotaLocked(stats) && isUnderSessionCountQuotaLocked(stats);
    }

    @GuardedBy("mLock")
    private boolean isQuotaFreeLocked(final int standbyBucket) {
        // Quota constraint is not enforced while charging.
        if (mService.isBatteryCharging()) {
            // Restricted jobs require additional constraints when charging, so don't immediately
            // mark quota as free when charging.
            return standbyBucket != RESTRICTED_INDEX;
        }
        return false;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    boolean isWithinQuotaLocked(final int userId, @NonNull final String packageName,
            final int standbyBucket) {
        if (standbyBucket == NEVER_INDEX) return false;

        if (isQuotaFreeLocked(standbyBucket)) return true;

        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        // TODO: use a higher minimum remaining time for jobs with MINIMUM priority
        return getRemainingExecutionTimeLocked(stats) > 0
                && isUnderJobCountQuotaLocked(stats)
                && isUnderSessionCountQuotaLocked(stats);
    }

    private boolean isUnderJobCountQuotaLocked(@NonNull ExecutionStats stats) {
        final long now = sElapsedRealtimeClock.millis();
        final boolean isUnderAllowedTimeQuota =
                (stats.jobRateLimitExpirationTimeElapsed <= now
                        || stats.jobCountInRateLimitingWindow < mMaxJobCountPerRateLimitingWindow);
        return isUnderAllowedTimeQuota
                && stats.bgJobCountInWindow < stats.jobCountLimit;
    }

    private boolean isUnderSessionCountQuotaLocked(@NonNull ExecutionStats stats) {
        final long now = sElapsedRealtimeClock.millis();
        final boolean isUnderAllowedTimeQuota = (stats.sessionRateLimitExpirationTimeElapsed <= now
                || stats.sessionCountInRateLimitingWindow < mMaxSessionCountPerRateLimitingWindow);
        return isUnderAllowedTimeQuota
                && stats.sessionCountInWindow < stats.sessionCountLimit;
    }

    @VisibleForTesting
    long getRemainingExecutionTimeLocked(@NonNull final JobStatus jobStatus) {
        return getRemainingExecutionTimeLocked(jobStatus.getSourceUserId(),
                jobStatus.getSourcePackageName(),
                jobStatus.getEffectiveStandbyBucket());
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
        return getRemainingExecutionTimeLocked(
                getExecutionStatsLocked(userId, packageName, standbyBucket));
    }

    private long getRemainingExecutionTimeLocked(@NonNull ExecutionStats stats) {
        return Math.min(stats.allowedTimePerPeriodMs - stats.executionTimeInWindowMs,
                mMaxExecutionTimeMs - stats.executionTimeInMaxPeriodMs);
    }

    @VisibleForTesting
    long getRemainingEJExecutionTimeLocked(final int userId, @NonNull final String packageName) {
        ShrinkableDebits quota = getEJDebitsLocked(userId, packageName);
        if (quota.getStandbyBucketLocked() == NEVER_INDEX) {
            return 0;
        }
        final long limitMs =
                getEJLimitMsLocked(userId, packageName, quota.getStandbyBucketLocked());
        long remainingMs = limitMs - quota.getTallyLocked();

        // Stale sessions may still be factored into tally. Make sure they're removed.
        List<TimedEvent> timingSessions = mEJTimingSessions.get(userId, packageName);
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final long windowStartTimeElapsed = nowElapsed - mEJLimitWindowSizeMs;
        if (timingSessions != null) {
            while (timingSessions.size() > 0) {
                TimingSession ts = (TimingSession) timingSessions.get(0);
                if (ts.endTimeElapsed < windowStartTimeElapsed) {
                    final long duration = ts.endTimeElapsed - ts.startTimeElapsed;
                    remainingMs += duration;
                    quota.transactLocked(-duration);
                    timingSessions.remove(0);
                } else if (ts.startTimeElapsed < windowStartTimeElapsed) {
                    remainingMs += windowStartTimeElapsed - ts.startTimeElapsed;
                    break;
                } else {
                    // Fully within the window.
                    break;
                }
            }
        }

        TopAppTimer topAppTimer = mTopAppTrackers.get(userId, packageName);
        if (topAppTimer != null && topAppTimer.isActive()) {
            remainingMs += topAppTimer.getPendingReward(nowElapsed);
        }

        Timer timer = mEJPkgTimers.get(userId, packageName);
        if (timer == null) {
            return remainingMs;
        }

        return remainingMs - timer.getCurrentDuration(sElapsedRealtimeClock.millis());
    }

    private long getEJLimitMsLocked(final int userId, @NonNull final String packageName,
            final int standbyBucket) {
        final long baseLimitMs = mEJLimitsMs[standbyBucket];
        if (mSystemInstallers.contains(userId, packageName)) {
            return baseLimitMs + mEjLimitAdditionInstallerMs;
        }
        return baseLimitMs;
    }

    /**
     * Returns the amount of time, in milliseconds, until the package would have reached its
     * duration quota, assuming it has a job counting towards its quota the entire time. This takes
     * into account any {@link TimingSession TimingSessions} that may roll out of the window as the
     * job is running.
     */
    @VisibleForTesting
    long getTimeUntilQuotaConsumedLocked(final int userId, @NonNull final String packageName) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final int standbyBucket = JobSchedulerService.standbyBucketForPackage(
                packageName, userId, nowElapsed);
        if (standbyBucket == NEVER_INDEX) {
            return 0;
        }

        List<TimedEvent> events = mTimingEvents.get(userId, packageName);
        final ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        if (events == null || events.size() == 0) {
            // Regular ACTIVE case. Since the bucket size equals the allowed time, the app jobs can
            // essentially run until they reach the maximum limit.
            if (stats.windowSizeMs == mAllowedTimePerPeriodMs[standbyBucket]) {
                return mMaxExecutionTimeMs;
            }
            return mAllowedTimePerPeriodMs[standbyBucket];
        }

        final long startWindowElapsed = nowElapsed - stats.windowSizeMs;
        final long startMaxElapsed = nowElapsed - MAX_PERIOD_MS;
        final long allowedTimePerPeriodMs = mAllowedTimePerPeriodMs[standbyBucket];
        final long allowedTimeRemainingMs = allowedTimePerPeriodMs - stats.executionTimeInWindowMs;
        final long maxExecutionTimeRemainingMs =
                mMaxExecutionTimeMs - stats.executionTimeInMaxPeriodMs;

        // Regular ACTIVE case. Since the bucket size equals the allowed time, the app jobs can
        // essentially run until they reach the maximum limit.
        if (stats.windowSizeMs == mAllowedTimePerPeriodMs[standbyBucket]) {
            return calculateTimeUntilQuotaConsumedLocked(
                    events, startMaxElapsed, maxExecutionTimeRemainingMs, false);
        }

        // Need to check both max time and period time in case one is less than the other.
        // For example, max time remaining could be less than bucket time remaining, but sessions
        // contributing to the max time remaining could phase out enough that we'd want to use the
        // bucket value.
        return Math.min(
                calculateTimeUntilQuotaConsumedLocked(
                        events, startMaxElapsed, maxExecutionTimeRemainingMs, false),
                calculateTimeUntilQuotaConsumedLocked(
                        events, startWindowElapsed, allowedTimeRemainingMs, true));
    }

    /**
     * Calculates how much time it will take, in milliseconds, until the quota is fully consumed.
     *
     * @param windowStartElapsed The start of the window, in the elapsed realtime timebase.
     * @param deadSpaceMs        How much time can be allowed to count towards the quota
     */
    private long calculateTimeUntilQuotaConsumedLocked(@NonNull List<TimedEvent> sessions,
            final long windowStartElapsed, long deadSpaceMs, boolean allowQuotaBumps) {
        long timeUntilQuotaConsumedMs = 0;
        long start = windowStartElapsed;
        int numQuotaBumps = 0;
        final long quotaBumpWindowStartElapsed =
                sElapsedRealtimeClock.millis() - mQuotaBumpWindowSizeMs;
        final int numSessions = sessions.size();
        if (allowQuotaBumps) {
            for (int i = numSessions - 1; i >= 0; --i) {
                TimedEvent event = sessions.get(i);

                if (event instanceof QuotaBump) {
                    if (event.getEndTimeElapsed() >= quotaBumpWindowStartElapsed
                            && numQuotaBumps++ < mQuotaBumpLimit) {
                        deadSpaceMs += mQuotaBumpAdditionalDurationMs;
                    } else {
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < numSessions; ++i) {
            TimedEvent event = sessions.get(i);

            if (event instanceof QuotaBump) {
                continue;
            }

            TimingSession session = (TimingSession) event;

            if (session.endTimeElapsed < windowStartElapsed) {
                // Outside of window. Ignore.
                continue;
            } else if (session.startTimeElapsed <= windowStartElapsed) {
                // Overlapping session. Can extend time by portion of session in window.
                timeUntilQuotaConsumedMs += session.endTimeElapsed - windowStartElapsed;
                start = session.endTimeElapsed;
            } else {
                // Completely within the window. Can only consider if there's enough dead space
                // to get to the start of the session.
                long diff = session.startTimeElapsed - start;
                if (diff > deadSpaceMs) {
                    break;
                }
                timeUntilQuotaConsumedMs += diff
                        + (session.endTimeElapsed - session.startTimeElapsed);
                deadSpaceMs -= diff;
                start = session.endTimeElapsed;
            }
        }
        // Will be non-zero if the loop didn't look at any sessions.
        timeUntilQuotaConsumedMs += deadSpaceMs;
        if (timeUntilQuotaConsumedMs > mMaxExecutionTimeMs) {
            Slog.wtf(TAG, "Calculated quota consumed time too high: " + timeUntilQuotaConsumedMs);
        }
        return timeUntilQuotaConsumedMs;
    }

    /**
     * Returns the amount of time, in milliseconds, until the package would have reached its
     * expedited job quota, assuming it has a job counting towards the quota the entire time and
     * the quota isn't replenished at all in that time.
     */
    @VisibleForTesting
    long getTimeUntilEJQuotaConsumedLocked(final int userId, @NonNull final String packageName) {
        final long remainingExecutionTimeMs =
                getRemainingEJExecutionTimeLocked(userId, packageName);

        List<TimedEvent> sessions = mEJTimingSessions.get(userId, packageName);
        if (sessions == null || sessions.size() == 0) {
            return remainingExecutionTimeMs;
        }

        final long nowElapsed = sElapsedRealtimeClock.millis();
        ShrinkableDebits quota = getEJDebitsLocked(userId, packageName);
        final long limitMs =
                getEJLimitMsLocked(userId, packageName, quota.getStandbyBucketLocked());
        final long startWindowElapsed = Math.max(0, nowElapsed - mEJLimitWindowSizeMs);
        long remainingDeadSpaceMs = remainingExecutionTimeMs;
        // Total time looked at where a session wouldn't be phasing out.
        long deadSpaceMs = 0;
        // Time regained from sessions phasing out
        long phasedOutSessionTimeMs = 0;

        for (int i = 0; i < sessions.size(); ++i) {
            TimingSession session = (TimingSession) sessions.get(i);
            if (session.endTimeElapsed < startWindowElapsed) {
                // Edge case where a session became stale in the time between the call to
                // getRemainingEJExecutionTimeLocked and this line.
                remainingDeadSpaceMs += session.endTimeElapsed - session.startTimeElapsed;
                sessions.remove(i);
                i--;
            } else if (session.startTimeElapsed < startWindowElapsed) {
                // Session straddles start of window
                phasedOutSessionTimeMs = session.endTimeElapsed - startWindowElapsed;
            } else {
                // Session fully inside window
                final long timeBetweenSessions = session.startTimeElapsed
                        - (i == 0 ? startWindowElapsed : sessions.get(i - 1).getEndTimeElapsed());
                final long usedDeadSpaceMs = Math.min(remainingDeadSpaceMs, timeBetweenSessions);
                deadSpaceMs += usedDeadSpaceMs;
                if (usedDeadSpaceMs == timeBetweenSessions) {
                    phasedOutSessionTimeMs += session.endTimeElapsed - session.startTimeElapsed;
                }
                remainingDeadSpaceMs -= usedDeadSpaceMs;
                if (remainingDeadSpaceMs <= 0) {
                    break;
                }
            }
        }

        return Math.min(limitMs, deadSpaceMs + phasedOutSessionTimeMs + remainingDeadSpaceMs);
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
            final long bucketAllowedTimeMs = mAllowedTimePerPeriodMs[standbyBucket];
            final long bucketWindowSizeMs = mBucketPeriodsMs[standbyBucket];
            final int jobCountLimit = mMaxBucketJobCounts[standbyBucket];
            final int sessionCountLimit = mMaxBucketSessionCounts[standbyBucket];
            Timer timer = mPkgTimers.get(userId, packageName);
            if ((timer != null && timer.isActive())
                    || stats.expirationTimeElapsed <= sElapsedRealtimeClock.millis()
                    || stats.allowedTimePerPeriodMs != bucketAllowedTimeMs
                    || stats.windowSizeMs != bucketWindowSizeMs
                    || stats.jobCountLimit != jobCountLimit
                    || stats.sessionCountLimit != sessionCountLimit) {
                // The stats are no longer valid.
                stats.allowedTimePerPeriodMs = bucketAllowedTimeMs;
                stats.windowSizeMs = bucketWindowSizeMs;
                stats.jobCountLimit = jobCountLimit;
                stats.sessionCountLimit = sessionCountLimit;
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
        stats.sessionCountInWindow = 0;
        if (stats.jobCountLimit == 0 || stats.sessionCountLimit == 0) {
            // App won't be in quota until configuration changes.
            stats.inQuotaTimeElapsed = Long.MAX_VALUE;
        } else {
            stats.inQuotaTimeElapsed = 0;
        }
        final long allowedTimeIntoQuotaMs = stats.allowedTimePerPeriodMs - mQuotaBufferMs;

        Timer timer = mPkgTimers.get(userId, packageName);
        final long nowElapsed = sElapsedRealtimeClock.millis();
        stats.expirationTimeElapsed = nowElapsed + MAX_PERIOD_MS;
        if (timer != null && timer.isActive()) {
            // Exclude active sessions from the session count so that new jobs aren't prevented
            // from starting due to an app hitting the session limit.
            stats.executionTimeInWindowMs =
                    stats.executionTimeInMaxPeriodMs = timer.getCurrentDuration(nowElapsed);
            stats.bgJobCountInWindow = stats.bgJobCountInMaxPeriod = timer.getBgJobCount();
            // If the timer is active, the value will be stale at the next method call, so
            // invalidate now.
            stats.expirationTimeElapsed = nowElapsed;
            if (stats.executionTimeInWindowMs >= allowedTimeIntoQuotaMs) {
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                        nowElapsed - allowedTimeIntoQuotaMs + stats.windowSizeMs);
            }
            if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeIntoQuotaMs) {
                final long inQuotaTime = nowElapsed - mMaxExecutionTimeIntoQuotaMs + MAX_PERIOD_MS;
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, inQuotaTime);
            }
            if (stats.bgJobCountInWindow >= stats.jobCountLimit) {
                final long inQuotaTime = nowElapsed + stats.windowSizeMs;
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, inQuotaTime);
            }
        }

        List<TimedEvent> events = mTimingEvents.get(userId, packageName);
        if (events == null || events.size() == 0) {
            return;
        }

        final long startWindowElapsed = nowElapsed - stats.windowSizeMs;
        final long startMaxElapsed = nowElapsed - MAX_PERIOD_MS;
        int sessionCountInWindow = 0;
        int numQuotaBumps = 0;
        final long quotaBumpWindowStartElapsed = nowElapsed - mQuotaBumpWindowSizeMs;
        // The minimum time between the start time and the beginning of the events that were
        // looked at --> how much time the stats will be valid for.
        long emptyTimeMs = Long.MAX_VALUE;
        // Sessions are non-overlapping and in order of occurrence, so iterating backwards will get
        // the most recent ones.
        final int loopStart = events.size() - 1;
        // Process QuotaBumps first to ensure the limits are properly adjusted.
        for (int i = loopStart; i >= 0; --i) {
            TimedEvent event = events.get(i);

            if (event.getEndTimeElapsed() < quotaBumpWindowStartElapsed
                    || numQuotaBumps >= mQuotaBumpLimit) {
                break;
            }

            if (event instanceof QuotaBump) {
                stats.allowedTimePerPeriodMs += mQuotaBumpAdditionalDurationMs;
                stats.jobCountLimit += mQuotaBumpAdditionalJobCount;
                stats.sessionCountLimit += mQuotaBumpAdditionalSessionCount;
                emptyTimeMs = Math.min(emptyTimeMs,
                        event.getEndTimeElapsed() - quotaBumpWindowStartElapsed);
                numQuotaBumps++;
            }
        }
        TimingSession lastSeenTimingSession = null;
        for (int i = loopStart; i >= 0; --i) {
            TimedEvent event = events.get(i);

            if (event instanceof QuotaBump) {
                continue;
            }

            TimingSession session = (TimingSession) event;

            // Window management.
            if (startWindowElapsed < session.endTimeElapsed) {
                final long start;
                if (startWindowElapsed < session.startTimeElapsed) {
                    start = session.startTimeElapsed;
                    emptyTimeMs =
                            Math.min(emptyTimeMs, session.startTimeElapsed - startWindowElapsed);
                } else {
                    // The session started before the window but ended within the window. Only
                    // include the portion that was within the window.
                    start = startWindowElapsed;
                    emptyTimeMs = 0;
                }

                stats.executionTimeInWindowMs += session.endTimeElapsed - start;
                stats.bgJobCountInWindow += session.bgJobCount;
                if (stats.executionTimeInWindowMs >= allowedTimeIntoQuotaMs) {
                    stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                            start + stats.executionTimeInWindowMs - allowedTimeIntoQuotaMs
                                    + stats.windowSizeMs);
                }
                if (stats.bgJobCountInWindow >= stats.jobCountLimit) {
                    final long inQuotaTime = session.endTimeElapsed + stats.windowSizeMs;
                    stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, inQuotaTime);
                }
                // Coalesce sessions if they are very close to each other in time
                boolean shouldCoalesce = lastSeenTimingSession != null
                        && lastSeenTimingSession.startTimeElapsed - session.endTimeElapsed
                        <= mTimingSessionCoalescingDurationMs;
                if (!shouldCoalesce) {
                    sessionCountInWindow++;

                    if (sessionCountInWindow >= stats.sessionCountLimit) {
                        final long inQuotaTime = session.endTimeElapsed + stats.windowSizeMs;
                        stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, inQuotaTime);
                    }
                }
            }

            // Max period check.
            if (startMaxElapsed < session.startTimeElapsed) {
                stats.executionTimeInMaxPeriodMs +=
                        session.endTimeElapsed - session.startTimeElapsed;
                stats.bgJobCountInMaxPeriod += session.bgJobCount;
                emptyTimeMs = Math.min(emptyTimeMs, session.startTimeElapsed - startMaxElapsed);
                if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeIntoQuotaMs) {
                    stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                            session.startTimeElapsed + stats.executionTimeInMaxPeriodMs
                                    - mMaxExecutionTimeIntoQuotaMs + MAX_PERIOD_MS);
                }
            } else if (startMaxElapsed < session.endTimeElapsed) {
                // The session started before the window but ended within the window. Only include
                // the portion that was within the window.
                stats.executionTimeInMaxPeriodMs += session.endTimeElapsed - startMaxElapsed;
                stats.bgJobCountInMaxPeriod += session.bgJobCount;
                emptyTimeMs = 0;
                if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeIntoQuotaMs) {
                    stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                            startMaxElapsed + stats.executionTimeInMaxPeriodMs
                                    - mMaxExecutionTimeIntoQuotaMs + MAX_PERIOD_MS);
                }
            } else {
                // This session ended before the window. No point in going any further.
                break;
            }

            lastSeenTimingSession = session;
        }
        stats.expirationTimeElapsed = nowElapsed + emptyTimeMs;
        stats.sessionCountInWindow = sessionCountInWindow;
    }

    /** Invalidate ExecutionStats for all apps. */
    @VisibleForTesting
    void invalidateAllExecutionStatsLocked() {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        mExecutionStatsCache.forEach((appStats) -> {
            if (appStats != null) {
                for (int i = 0; i < appStats.length; ++i) {
                    ExecutionStats stats = appStats[i];
                    if (stats != null) {
                        stats.expirationTimeElapsed = nowElapsed;
                    }
                }
            }
        });
    }

    @VisibleForTesting
    void invalidateAllExecutionStatsLocked(final int userId,
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
    void incrementJobCountLocked(final int userId, @NonNull final String packageName, int count) {
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
            if (stats.jobRateLimitExpirationTimeElapsed <= now) {
                stats.jobRateLimitExpirationTimeElapsed = now + mRateLimitingWindowMs;
                stats.jobCountInRateLimitingWindow = 0;
            }
            stats.jobCountInRateLimitingWindow += count;
            if (Flags.countQuotaFix()) {
                stats.bgJobCountInWindow += count;
            }
        }
    }

    private void incrementTimingSessionCountLocked(final int userId,
            @NonNull final String packageName) {
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
            if (stats.sessionRateLimitExpirationTimeElapsed <= now) {
                stats.sessionRateLimitExpirationTimeElapsed = now + mRateLimitingWindowMs;
                stats.sessionCountInRateLimitingWindow = 0;
            }
            stats.sessionCountInRateLimitingWindow++;
        }
    }

    @VisibleForTesting
    void saveTimingSession(final int userId, @NonNull final String packageName,
            @NonNull final TimingSession session, boolean isExpedited) {
        saveTimingSession(userId, packageName, session, isExpedited, 0);
    }

    private void saveTimingSession(final int userId, @NonNull final String packageName,
            @NonNull final TimingSession session, boolean isExpedited, long debitAdjustment) {
        synchronized (mLock) {
            final SparseArrayMap<String, List<TimedEvent>> sessionMap =
                    isExpedited ? mEJTimingSessions : mTimingEvents;
            List<TimedEvent> sessions = sessionMap.get(userId, packageName);
            if (sessions == null) {
                sessions = new ArrayList<>();
                sessionMap.add(userId, packageName, sessions);
            }
            sessions.add(session);
            if (isExpedited) {
                final ShrinkableDebits quota = getEJDebitsLocked(userId, packageName);
                quota.transactLocked(session.endTimeElapsed - session.startTimeElapsed
                        + debitAdjustment);
            } else {
                // Adding a new session means that the current stats are now incorrect.
                invalidateAllExecutionStatsLocked(userId, packageName);

                maybeScheduleCleanupAlarmLocked();
            }
        }
    }

    private void grantRewardForInstantEvent(
            final int userId, @NonNull final String packageName, final long credit) {
        if (credit == 0) {
            return;
        }
        synchronized (mLock) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            final ShrinkableDebits quota = getEJDebitsLocked(userId, packageName);
            if (transactQuotaLocked(userId, packageName, nowElapsed, quota, credit)) {
                mStateChangedListener.onControllerStateChanged(
                        maybeUpdateConstraintForPkgLocked(nowElapsed, userId, packageName));
            }
        }
    }

    private boolean transactQuotaLocked(final int userId, @NonNull final String packageName,
            final long nowElapsed, @NonNull ShrinkableDebits debits, final long credit) {
        final long oldTally = debits.getTallyLocked();
        final long leftover = debits.transactLocked(-credit);
        if (DEBUG) {
            Slog.d(TAG, "debits overflowed by " + leftover);
        }
        boolean changed = oldTally != debits.getTallyLocked();
        if (leftover != 0) {
            // Only adjust timer if its active.
            final Timer ejTimer = mEJPkgTimers.get(userId, packageName);
            if (ejTimer != null && ejTimer.isActive()) {
                ejTimer.updateDebitAdjustment(nowElapsed, leftover);
                changed = true;
            }
        }
        return changed;
    }

    private final class EarliestEndTimeFunctor implements Consumer<List<TimedEvent>> {
        public long earliestEndElapsed = Long.MAX_VALUE;

        @Override
        public void accept(List<TimedEvent> events) {
            if (events != null && events.size() > 0) {
                earliestEndElapsed =
                        Math.min(earliestEndElapsed, events.get(0).getEndTimeElapsed());
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
        final long nowElapsed = sElapsedRealtimeClock.millis();
        if (mNextCleanupTimeElapsed > nowElapsed) {
            // There's already an alarm scheduled. Just stick with that one. There's no way we'll
            // end up scheduling an earlier alarm.
            if (DEBUG) {
                Slog.v(TAG, "Not scheduling cleanup since there's already one at "
                        + mNextCleanupTimeElapsed
                        + " (in " + (mNextCleanupTimeElapsed - nowElapsed) + "ms)");
            }
            return;
        }
        mEarliestEndTimeFunctor.reset();
        mTimingEvents.forEach(mEarliestEndTimeFunctor);
        mEJTimingSessions.forEach(mEarliestEndTimeFunctor);
        final long earliestEndElapsed = mEarliestEndTimeFunctor.earliestEndElapsed;
        if (earliestEndElapsed == Long.MAX_VALUE) {
            // Couldn't find a good time to clean up. Maybe this was called after we deleted all
            // timing sessions.
            if (DEBUG) {
                Slog.d(TAG, "Didn't find a time to schedule cleanup");
            }
            return;
        }
        // Need to keep sessions for all apps up to the max period, regardless of their current
        // standby bucket.
        long nextCleanupElapsed = earliestEndElapsed + MAX_PERIOD_MS;
        if (nextCleanupElapsed - mNextCleanupTimeElapsed <= 10 * MINUTE_IN_MILLIS) {
            // No need to clean up too often. Delay the alarm if the next cleanup would be too soon
            // after it.
            nextCleanupElapsed = mNextCleanupTimeElapsed + 10 * MINUTE_IN_MILLIS;
        }
        mNextCleanupTimeElapsed = nextCleanupElapsed;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, nextCleanupElapsed, ALARM_TAG_CLEANUP,
                mSessionCleanupAlarmListener, mHandler);
        if (DEBUG) {
            Slog.d(TAG, "Scheduled next cleanup for " + mNextCleanupTimeElapsed);
        }
    }

    private class TimerChargingUpdateFunctor implements Consumer<Timer> {
        private long mNowElapsed;
        private boolean mIsCharging;

        private void setStatus(long nowElapsed, boolean isCharging) {
            mNowElapsed = nowElapsed;
            mIsCharging = isCharging;
        }

        @Override
        public void accept(Timer timer) {
            if (JobSchedulerService.standbyBucketForPackage(timer.mPkg.packageName,
                    timer.mPkg.userId, mNowElapsed) != RESTRICTED_INDEX) {
                // Restricted jobs need additional constraints even when charging, so don't
                // immediately say that quota is free.
                timer.onStateChangedLocked(mNowElapsed, mIsCharging);
            }
        }
    }

    private final TimerChargingUpdateFunctor
            mTimerChargingUpdateFunctor = new TimerChargingUpdateFunctor();

    private void handleNewChargingStateLocked() {
        mTimerChargingUpdateFunctor.setStatus(sElapsedRealtimeClock.millis(),
                mService.isBatteryCharging());
        if (DEBUG) {
            Slog.d(TAG, "handleNewChargingStateLocked: " + mService.isBatteryCharging());
        }
        // Deal with Timers first.
        mEJPkgTimers.forEach(mTimerChargingUpdateFunctor);
        mPkgTimers.forEach(mTimerChargingUpdateFunctor);
        // Now update jobs out of band so broadcast processing can proceed.
        AppSchedulingModuleThread.getHandler().post(() -> {
            synchronized (mLock) {
                maybeUpdateAllConstraintsLocked();
            }
        });
    }

    private void maybeUpdateAllConstraintsLocked() {
        final ArraySet<JobStatus> changedJobs = new ArraySet<>();
        final long nowElapsed = sElapsedRealtimeClock.millis();
        for (int u = 0; u < mTrackedJobs.numMaps(); ++u) {
            final int userId = mTrackedJobs.keyAt(u);
            for (int p = 0; p < mTrackedJobs.numElementsForKey(userId); ++p) {
                final String packageName = mTrackedJobs.keyAt(u, p);
                changedJobs.addAll(
                        maybeUpdateConstraintForPkgLocked(nowElapsed, userId, packageName));
            }
        }
        if (changedJobs.size() > 0) {
            mStateChangedListener.onControllerStateChanged(changedJobs);
        }
    }

    /**
     * Update the CONSTRAINT_WITHIN_QUOTA bit for all of the Jobs for a given package.
     *
     * @return the set of jobs whose status changed
     */
    @NonNull
    private ArraySet<JobStatus> maybeUpdateConstraintForPkgLocked(final long nowElapsed,
            final int userId, @NonNull final String packageName) {
        ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, packageName);
        final ArraySet<JobStatus> changedJobs = new ArraySet<>();
        if (jobs == null || jobs.size() == 0) {
            return changedJobs;
        }

        // Quota is the same for all jobs within a package.
        final int realStandbyBucket = jobs.valueAt(0).getStandbyBucket();
        final boolean realInQuota = isWithinQuotaLocked(userId, packageName, realStandbyBucket);
        boolean outOfEJQuota = false;
        for (int i = jobs.size() - 1; i >= 0; --i) {
            final JobStatus js = jobs.valueAt(i);
            final boolean isWithinEJQuota =
                    js.isRequestedExpeditedJob() && isWithinEJQuotaLocked(js);
            if (isTopStartedJobLocked(js)) {
                // Job was started while the app was in the TOP state so we should allow it to
                // finish.
                if (js.setQuotaConstraintSatisfied(nowElapsed, true)) {
                    changedJobs.add(js);
                }
            } else if (realStandbyBucket != EXEMPTED_INDEX && realStandbyBucket != ACTIVE_INDEX
                    && realStandbyBucket == js.getEffectiveStandbyBucket()
                    && !(Flags.countQuotaFix() && mService.isCurrentlyRunningLocked(js))) {
                // An app in the ACTIVE bucket may be out of quota while the job could be in quota
                // for some reason. Therefore, avoid setting the real value here and check each job
                // individually. Running job need to determine its own quota status as well.
                if (setConstraintSatisfied(js, nowElapsed, realInQuota, isWithinEJQuota)) {
                    changedJobs.add(js);
                }
            } else {
                // This job is somehow exempted. Need to determine its own quota status.
                if (setConstraintSatisfied(js, nowElapsed,
                        isWithinQuotaLocked(js), isWithinEJQuota)) {
                    changedJobs.add(js);
                }
            }

            if (js.isRequestedExpeditedJob()) {
                if (setExpeditedQuotaApproved(js, nowElapsed, isWithinEJQuota)) {
                    changedJobs.add(js);
                }
                outOfEJQuota |= !isWithinEJQuota;
            }
        }
        if (!realInQuota || outOfEJQuota) {
            // Don't want to use the effective standby bucket here since that bump the bucket to
            // ACTIVE for one of the jobs, which doesn't help with other jobs that aren't
            // exempted.
            maybeScheduleStartAlarmLocked(userId, packageName, realStandbyBucket);
        } else {
            mInQuotaAlarmQueue.removeAlarmForKey(UserPackage.of(userId, packageName));
        }
        return changedJobs;
    }

    private class UidConstraintUpdater implements Consumer<JobStatus> {
        private final SparseArrayMap<String, Integer> mToScheduleStartAlarms =
                new SparseArrayMap<>();
        public final ArraySet<JobStatus> changedJobs = new ArraySet<>();
        long mUpdateTimeElapsed = 0;

        void prepare() {
            mUpdateTimeElapsed = sElapsedRealtimeClock.millis();
            changedJobs.clear();
        }

        @Override
        public void accept(JobStatus jobStatus) {
            final boolean isWithinEJQuota;
            if (jobStatus.isRequestedExpeditedJob()) {
                isWithinEJQuota = isWithinEJQuotaLocked(jobStatus);
            } else {
                isWithinEJQuota = false;
            }
            if (setConstraintSatisfied(jobStatus, mUpdateTimeElapsed,
                    isWithinQuotaLocked(jobStatus), isWithinEJQuota)) {
                changedJobs.add(jobStatus);
            }
            if (setExpeditedQuotaApproved(jobStatus, mUpdateTimeElapsed, isWithinEJQuota)) {
                changedJobs.add(jobStatus);
            }

            final int userId = jobStatus.getSourceUserId();
            final String packageName = jobStatus.getSourcePackageName();
            final int realStandbyBucket = jobStatus.getStandbyBucket();
            if (isWithinEJQuota
                    && isWithinQuotaLocked(userId, packageName, realStandbyBucket)) {
                // TODO(141645789): we probably shouldn't cancel the alarm until we've verified
                // that all jobs for the userId-package are within quota.
                mInQuotaAlarmQueue.removeAlarmForKey(UserPackage.of(userId, packageName));
            } else {
                mToScheduleStartAlarms.add(userId, packageName, realStandbyBucket);
            }
        }

        void postProcess() {
            for (int u = 0; u < mToScheduleStartAlarms.numMaps(); ++u) {
                final int userId = mToScheduleStartAlarms.keyAt(u);
                for (int p = 0; p < mToScheduleStartAlarms.numElementsForKey(userId); ++p) {
                    final String packageName = mToScheduleStartAlarms.keyAt(u, p);
                    final int standbyBucket = mToScheduleStartAlarms.get(userId, packageName);
                    maybeScheduleStartAlarmLocked(userId, packageName, standbyBucket);
                }
            }
        }

        void reset() {
            mToScheduleStartAlarms.clear();
        }
    }

    private final UidConstraintUpdater mUpdateUidConstraints = new UidConstraintUpdater();

    @GuardedBy("mLock")
    @NonNull
    private ArraySet<JobStatus> maybeUpdateConstraintForUidLocked(final int uid) {
        mUpdateUidConstraints.prepare();
        mService.getJobStore().forEachJobForSourceUid(uid, mUpdateUidConstraints);

        mUpdateUidConstraints.postProcess();
        mUpdateUidConstraints.reset();
        return mUpdateUidConstraints.changedJobs;
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

        ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, packageName);
        if (jobs == null || jobs.size() == 0) {
            Slog.e(TAG, "maybeScheduleStartAlarmLocked called for "
                    + packageToString(userId, packageName) + " that has no jobs");
            mInQuotaAlarmQueue.removeAlarmForKey(UserPackage.of(userId, packageName));
            return;
        }

        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        final boolean isUnderJobCountQuota = isUnderJobCountQuotaLocked(stats);
        final boolean isUnderTimingSessionCountQuota = isUnderSessionCountQuotaLocked(stats);
        final long remainingEJQuota = getRemainingEJExecutionTimeLocked(userId, packageName);

        final boolean inRegularQuota =
                stats.executionTimeInWindowMs < mAllowedTimePerPeriodMs[standbyBucket]
                        && stats.executionTimeInMaxPeriodMs < mMaxExecutionTimeMs
                        && isUnderJobCountQuota
                        && isUnderTimingSessionCountQuota;
        if (inRegularQuota && remainingEJQuota > 0) {
            // Already in quota. Why was this method called?
            if (DEBUG) {
                Slog.e(TAG, "maybeScheduleStartAlarmLocked called for "
                        + packageToString(userId, packageName)
                        + " even though it already has "
                        + getRemainingExecutionTimeLocked(userId, packageName, standbyBucket)
                        + "ms in its quota.");
            }
            mInQuotaAlarmQueue.removeAlarmForKey(UserPackage.of(userId, packageName));
            mHandler.obtainMessage(MSG_CHECK_PACKAGE, userId, 0, packageName).sendToTarget();
            return;
        }

        long inRegularQuotaTimeElapsed = Long.MAX_VALUE;
        long inEJQuotaTimeElapsed = Long.MAX_VALUE;
        if (!inRegularQuota) {
            // The time this app will have quota again.
            long inQuotaTimeElapsed = stats.inQuotaTimeElapsed;
            if (!isUnderJobCountQuota && stats.bgJobCountInWindow < stats.jobCountLimit) {
                // App hit the rate limit.
                inQuotaTimeElapsed =
                        Math.max(inQuotaTimeElapsed, stats.jobRateLimitExpirationTimeElapsed);
            }
            if (!isUnderTimingSessionCountQuota
                    && stats.sessionCountInWindow < stats.sessionCountLimit) {
                // App hit the rate limit.
                inQuotaTimeElapsed =
                        Math.max(inQuotaTimeElapsed, stats.sessionRateLimitExpirationTimeElapsed);
            }
            inRegularQuotaTimeElapsed = inQuotaTimeElapsed;
        }
        if (remainingEJQuota <= 0) {
            final long limitMs =
                    getEJLimitMsLocked(userId, packageName, standbyBucket) - mQuotaBufferMs;
            long sumMs = 0;
            final Timer ejTimer = mEJPkgTimers.get(userId, packageName);
            if (ejTimer != null && ejTimer.isActive()) {
                final long nowElapsed = sElapsedRealtimeClock.millis();
                sumMs += ejTimer.getCurrentDuration(nowElapsed);
                if (sumMs >= limitMs) {
                    inEJQuotaTimeElapsed = (nowElapsed - limitMs) + mEJLimitWindowSizeMs;
                }
            }
            List<TimedEvent> timingSessions = mEJTimingSessions.get(userId, packageName);
            if (timingSessions != null) {
                for (int i = timingSessions.size() - 1; i >= 0; --i) {
                    TimingSession ts = (TimingSession) timingSessions.get(i);
                    final long durationMs = ts.endTimeElapsed - ts.startTimeElapsed;
                    sumMs += durationMs;
                    if (sumMs >= limitMs) {
                        inEJQuotaTimeElapsed =
                                ts.startTimeElapsed + (sumMs - limitMs) + mEJLimitWindowSizeMs;
                        break;
                    }
                }
            } else if ((ejTimer == null || !ejTimer.isActive()) && inRegularQuota) {
                // In some strange cases, an app may end be in the NEVER bucket but could have run
                // some regular jobs. This results in no EJ timing sessions and QC having a bad
                // time.
                Slog.wtf(TAG, packageToString(userId, packageName)
                        + " has 0 EJ quota without running anything");
                return;
            }
        }
        long inQuotaTimeElapsed = Math.min(inRegularQuotaTimeElapsed, inEJQuotaTimeElapsed);

        if (inQuotaTimeElapsed <= sElapsedRealtimeClock.millis()) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            Slog.wtf(TAG,
                    "In quota time is " + (nowElapsed - inQuotaTimeElapsed) + "ms old. Now="
                            + nowElapsed + ", inQuotaTime=" + inQuotaTimeElapsed + ": " + stats);
            inQuotaTimeElapsed = nowElapsed + 5 * MINUTE_IN_MILLIS;
        }
        mInQuotaAlarmQueue.addAlarm(UserPackage.of(userId, packageName), inQuotaTimeElapsed);
    }

    private boolean setConstraintSatisfied(@NonNull JobStatus jobStatus, long nowElapsed,
            boolean isWithinQuota, boolean isWithinEjQuota) {
        final boolean isSatisfied;
        if (jobStatus.startedAsExpeditedJob) {
            // If the job started as an EJ, then we should only consider EJ quota for the constraint
            // satisfaction.
            isSatisfied = isWithinEjQuota;
        } else if (mService.isCurrentlyRunningLocked(jobStatus)) {
            // Job is running but didn't start as an EJ, so only the regular quota should be
            // considered.
            isSatisfied = isWithinQuota;
        } else {
            isSatisfied = isWithinEjQuota || isWithinQuota;
        }
        if (!isSatisfied && jobStatus.getWhenStandbyDeferred() == 0) {
            // Mark that the job is being deferred due to buckets.
            jobStatus.setWhenStandbyDeferred(nowElapsed);
        }
        return jobStatus.setQuotaConstraintSatisfied(nowElapsed, isSatisfied);
    }

    /**
     * If the satisfaction changes, this will tell connectivity & background jobs controller to
     * also re-evaluate their state.
     */
    private boolean setExpeditedQuotaApproved(@NonNull JobStatus jobStatus, long nowElapsed,
            boolean isWithinQuota) {
        if (jobStatus.setExpeditedJobQuotaApproved(nowElapsed, isWithinQuota)) {
            mBackgroundJobsController.evaluateStateLocked(jobStatus);
            mConnectivityController.evaluateStateLocked(jobStatus);
            if (isWithinQuota && jobStatus.isReady()) {
                mStateChangedListener.onRunJobNow(jobStatus);
            }
            return true;
        }
        return false;
    }

    @VisibleForTesting
    interface TimedEvent {
        long getEndTimeElapsed();

        void dump(IndentingPrintWriter pw);
    }

    @VisibleForTesting
    static final class TimingSession implements TimedEvent {
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
        public long getEndTimeElapsed() {
            return endTimeElapsed;
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

        @Override
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

    @VisibleForTesting
    static final class QuotaBump implements TimedEvent {
        // Event timestamp in elapsed realtime timebase.
        public final long eventTimeElapsed;

        QuotaBump(long eventElapsed) {
            this.eventTimeElapsed = eventElapsed;
        }

        @Override
        public long getEndTimeElapsed() {
            return eventTimeElapsed;
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            pw.print("Quota bump @ ");
            pw.print(eventTimeElapsed);
            pw.println();
        }
    }

    @VisibleForTesting
    static final class ShrinkableDebits {
        /** The amount of quota remaining. Can be negative if limit changes. */
        private long mDebitTally;
        private int mStandbyBucket;

        ShrinkableDebits(int standbyBucket) {
            mDebitTally = 0;
            mStandbyBucket = standbyBucket;
        }

        long getTallyLocked() {
            return mDebitTally;
        }

        /**
         * Negative if the tally should decrease (therefore increasing available quota);
         * or positive if the tally should increase (therefore decreasing available quota).
         */
        long transactLocked(final long amount) {
            final long leftover = amount < 0 && Math.abs(amount) > mDebitTally
                    ? mDebitTally + amount : 0;
            mDebitTally = Math.max(0, mDebitTally + amount);
            return leftover;
        }

        void setStandbyBucketLocked(int standbyBucket) {
            mStandbyBucket = standbyBucket;
        }

        int getStandbyBucketLocked() {
            return mStandbyBucket;
        }

        @Override
        public String toString() {
            return "ShrinkableDebits { debit tally: "
                    + mDebitTally + ", bucket: " + mStandbyBucket
                    + " }";
        }

        void dumpLocked(IndentingPrintWriter pw) {
            pw.println(toString());
        }
    }

    private final class Timer {
        private final UserPackage mPkg;
        private final int mUid;
        private final boolean mRegularJobTimer;

        // List of jobs currently running for this app that started when the app wasn't in the
        // foreground.
        private final ArraySet<JobStatus> mRunningBgJobs = new ArraySet<>();
        private long mStartTimeElapsed;
        private int mBgJobCount;
        private long mDebitAdjustment;

        Timer(int uid, int userId, String packageName, boolean regularJobTimer) {
            mPkg = UserPackage.of(userId, packageName);
            mUid = uid;
            mRegularJobTimer = regularJobTimer;
        }

        void startTrackingJobLocked(@NonNull JobStatus jobStatus) {
            if (jobStatus.shouldTreatAsUserInitiatedJob()) {
                if (DEBUG) {
                    Slog.v(TAG, "Timer ignoring " + jobStatus.toShortString()
                            + " because it's user-initiated");
                }
                return;
            }
            if (isTopStartedJobLocked(jobStatus)) {
                // We intentionally don't pay attention to fg state changes after a TOP job has
                // started.
                if (DEBUG) {
                    Slog.v(TAG,
                            "Timer ignoring " + jobStatus.toShortString() + " because isTop");
                }
                return;
            }
            if (DEBUG) {
                Slog.v(TAG, "Starting to track " + jobStatus.toShortString());
            }
            // Always maintain list of running jobs, even when quota is free.
            if (mRunningBgJobs.add(jobStatus) && shouldTrackLocked()) {
                mBgJobCount++;
                if (mRegularJobTimer) {
                    incrementJobCountLocked(mPkg.userId, mPkg.packageName, 1);
                    if (Flags.countQuotaFix()) {
                        final ExecutionStats stats = getExecutionStatsLocked(mPkg.userId,
                                mPkg.packageName, jobStatus.getEffectiveStandbyBucket(), false);
                        if (!isUnderJobCountQuotaLocked(stats)) {
                            mHandler.obtainMessage(MSG_REACHED_COUNT_QUOTA, mPkg).sendToTarget();
                        }
                    }
                }
                if (mRunningBgJobs.size() == 1) {
                    // Started tracking the first job.
                    mStartTimeElapsed = sElapsedRealtimeClock.millis();
                    mDebitAdjustment = 0;
                    if (mRegularJobTimer) {
                        // Starting the timer means that all cached execution stats are now
                        // incorrect.
                        invalidateAllExecutionStatsLocked(mPkg.userId, mPkg.packageName);
                    }
                    scheduleCutoff();
                }
            }
        }

        void stopTrackingJob(@NonNull JobStatus jobStatus) {
            if (DEBUG) {
                Slog.v(TAG, "Stopping tracking of " + jobStatus.toShortString());
            }
            synchronized (mLock) {
                if (mRunningBgJobs.size() == 0) {
                    // maybeStopTrackingJobLocked can be called when an app cancels a job, so a
                    // timer may not be running when it's asked to stop tracking a job.
                    if (DEBUG) {
                        Slog.d(TAG, "Timer isn't tracking any jobs but still told to stop");
                    }
                    return;
                }
                final long nowElapsed = sElapsedRealtimeClock.millis();
                final int standbyBucket = JobSchedulerService.standbyBucketForPackage(
                        mPkg.packageName, mPkg.userId, nowElapsed);
                if (mRunningBgJobs.remove(jobStatus) && mRunningBgJobs.size() == 0
                        && !isQuotaFreeLocked(standbyBucket)) {
                    emitSessionLocked(nowElapsed);
                    cancelCutoff();
                }
            }
        }

        void updateDebitAdjustment(long nowElapsed, long debit) {
            // Make sure we don't have a credit larger than the expected session.
            mDebitAdjustment = Math.max(mDebitAdjustment + debit, mStartTimeElapsed - nowElapsed);
        }

        /**
         * Stops tracking all jobs and cancels any pending alarms. This should only be called if
         * the Timer is not going to be used anymore.
         */
        void dropEverythingLocked() {
            mRunningBgJobs.clear();
            cancelCutoff();
        }

        @GuardedBy("mLock")
        private void emitSessionLocked(long nowElapsed) {
            if (mBgJobCount <= 0) {
                // Nothing to emit.
                return;
            }
            TimingSession ts = new TimingSession(mStartTimeElapsed, nowElapsed, mBgJobCount);
            saveTimingSession(mPkg.userId, mPkg.packageName, ts, !mRegularJobTimer,
                    mDebitAdjustment);
            mBgJobCount = 0;
            // Don't reset the tracked jobs list as we need to keep tracking the current number
            // of jobs.
            // However, cancel the currently scheduled cutoff since it's not currently useful.
            cancelCutoff();
            if (mRegularJobTimer) {
                incrementTimingSessionCountLocked(mPkg.userId, mPkg.packageName);
            }
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
                return !isActive() ? 0 : nowElapsed - mStartTimeElapsed + mDebitAdjustment;
            }
        }

        int getBgJobCount() {
            synchronized (mLock) {
                return mBgJobCount;
            }
        }

        @GuardedBy("mLock")
        private boolean shouldTrackLocked() {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            final int standbyBucket = JobSchedulerService.standbyBucketForPackage(mPkg.packageName,
                    mPkg.userId, nowElapsed);
            final boolean hasTempAllowlistExemption = !mRegularJobTimer
                    && hasTempAllowlistExemptionLocked(mUid, standbyBucket, nowElapsed);
            final long topAppGracePeriodEndElapsed = mTopAppGraceCache.get(mUid);
            final boolean hasTopAppExemption = !mRegularJobTimer
                    && (mTopAppCache.get(mUid) || nowElapsed < topAppGracePeriodEndElapsed);
            if (DEBUG) {
                Slog.d(TAG, "quotaFree=" + isQuotaFreeLocked(standbyBucket)
                        + " isFG=" + mForegroundUids.get(mUid)
                        + " tempEx=" + hasTempAllowlistExemption
                        + " topEx=" + hasTopAppExemption);
            }
            return !isQuotaFreeLocked(standbyBucket)
                    && !mForegroundUids.get(mUid) && !hasTempAllowlistExemption
                    && !hasTopAppExemption;
        }

        void onStateChangedLocked(long nowElapsed, boolean isQuotaFree) {
            if (isQuotaFree) {
                emitSessionLocked(nowElapsed);
            } else if (!isActive() && shouldTrackLocked()) {
                // Start timing from unplug.
                if (mRunningBgJobs.size() > 0) {
                    mStartTimeElapsed = nowElapsed;
                    mDebitAdjustment = 0;
                    // NOTE: this does have the unfortunate consequence that if the device is
                    // repeatedly plugged in and unplugged, or an app changes foreground state
                    // very frequently, the job count for a package may be artificially high.
                    mBgJobCount = mRunningBgJobs.size();
                    if (mRegularJobTimer) {
                        incrementJobCountLocked(mPkg.userId, mPkg.packageName, mBgJobCount);
                        // Starting the timer means that all cached execution stats are now
                        // incorrect.
                        invalidateAllExecutionStatsLocked(mPkg.userId, mPkg.packageName);
                    }
                    // Schedule cutoff since we're now actively tracking for quotas again.
                    scheduleCutoff();
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
                Message msg = mHandler.obtainMessage(
                        mRegularJobTimer ? MSG_REACHED_TIME_QUOTA : MSG_REACHED_EJ_TIME_QUOTA,
                        mPkg);
                final long timeRemainingMs = mRegularJobTimer
                        ? getTimeUntilQuotaConsumedLocked(mPkg.userId, mPkg.packageName)
                        : getTimeUntilEJQuotaConsumedLocked(mPkg.userId, mPkg.packageName);
                if (DEBUG) {
                    Slog.i(TAG,
                            (mRegularJobTimer ? "Regular job" : "EJ") + " for " + mPkg + " has "
                                    + timeRemainingMs + "ms left.");
                }
                // If the job was running the entire time, then the system would be up, so it's
                // fine to use uptime millis for these messages.
                mHandler.sendMessageDelayed(msg, timeRemainingMs);
            }
        }

        private void cancelCutoff() {
            mHandler.removeMessages(
                    mRegularJobTimer ? MSG_REACHED_TIME_QUOTA : MSG_REACHED_EJ_TIME_QUOTA, mPkg);
        }

        public void dump(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
            pw.print("Timer<");
            pw.print(mRegularJobTimer ? "REG" : "EJ");
            pw.print(">{");
            pw.print(mPkg);
            pw.print("} ");
            if (isActive()) {
                pw.print("started at ");
                pw.print(mStartTimeElapsed);
                pw.print(" (");
                pw.print(sElapsedRealtimeClock.millis() - mStartTimeElapsed);
                pw.print("ms ago)");
            } else {
                pw.print("NOT active");
            }
            pw.print(", ");
            pw.print(mBgJobCount);
            pw.print(" running bg jobs");
            if (!mRegularJobTimer) {
                pw.print(" (debit adj=");
                pw.print(mDebitAdjustment);
                pw.print(")");
            }
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

    private final class TopAppTimer {
        private final UserPackage mPkg;

        // List of jobs currently running for this app that started when the app wasn't in the
        // foreground.
        private final SparseArray<UsageEvents.Event> mActivities = new SparseArray<>();
        private long mStartTimeElapsed;

        TopAppTimer(int userId, String packageName) {
            mPkg = UserPackage.of(userId, packageName);
        }

        private int calculateTimeChunks(final long nowElapsed) {
            final long totalTopTimeMs = nowElapsed - mStartTimeElapsed;
            int numTimeChunks = (int) (totalTopTimeMs / mEJTopAppTimeChunkSizeMs);
            final long remainderMs = totalTopTimeMs % mEJTopAppTimeChunkSizeMs;
            if (remainderMs >= SECOND_IN_MILLIS) {
                // "Round up"
                numTimeChunks++;
            }
            return numTimeChunks;
        }

        long getPendingReward(final long nowElapsed) {
            return mEJRewardTopAppMs * calculateTimeChunks(nowElapsed);
        }

        void processEventLocked(@NonNull UsageEvents.Event event) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            switch (event.getEventType()) {
                case UsageEvents.Event.ACTIVITY_RESUMED:
                    if (mActivities.size() == 0) {
                        mStartTimeElapsed = nowElapsed;
                    }
                    mActivities.put(event.mInstanceId, event);
                    break;
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED:
                case UsageEvents.Event.ACTIVITY_DESTROYED:
                    final UsageEvents.Event existingEvent =
                            mActivities.removeReturnOld(event.mInstanceId);
                    if (existingEvent != null && mActivities.size() == 0) {
                        final long pendingReward = getPendingReward(nowElapsed);
                        if (DEBUG) {
                            Slog.d(TAG, "Crediting " + mPkg + " " + pendingReward + "ms"
                                    + " for " + calculateTimeChunks(nowElapsed) + " time chunks");
                        }
                        final ShrinkableDebits debits =
                                getEJDebitsLocked(mPkg.userId, mPkg.packageName);
                        if (transactQuotaLocked(mPkg.userId, mPkg.packageName,
                                nowElapsed, debits, pendingReward)) {
                            mStateChangedListener.onControllerStateChanged(
                                    maybeUpdateConstraintForPkgLocked(nowElapsed,
                                            mPkg.userId, mPkg.packageName));
                        }
                    }
                    break;
            }
        }

        boolean isActive() {
            synchronized (mLock) {
                return mActivities.size() > 0;
            }
        }

        public void dump(IndentingPrintWriter pw) {
            pw.print("TopAppTimer{");
            pw.print(mPkg);
            pw.print("} ");
            if (isActive()) {
                pw.print("started at ");
                pw.print(mStartTimeElapsed);
                pw.print(" (");
                pw.print(sElapsedRealtimeClock.millis() - mStartTimeElapsed);
                pw.print("ms ago)");
            } else {
                pw.print("NOT active");
            }
            pw.println();
            pw.increaseIndent();
            for (int i = 0; i < mActivities.size(); i++) {
                UsageEvents.Event event = mActivities.valueAt(i);
                pw.println(event.getClassName());
            }
            pw.decreaseIndent();
        }

        public void dump(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(StateControllerProto.QuotaController.TopAppTimer.IS_ACTIVE, isActive());
            proto.write(StateControllerProto.QuotaController.TopAppTimer.START_TIME_ELAPSED,
                    mStartTimeElapsed);
            proto.write(StateControllerProto.QuotaController.TopAppTimer.ACTIVITY_COUNT,
                    mActivities.size());
            // TODO: maybe dump activities/events

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
            AppSchedulingModuleThread.getHandler().post(() -> {
                final int bucketIndex = JobSchedulerService.standbyBucketToBucketIndex(bucket);
                updateStandbyBucket(userId, packageName, bucketIndex);
            });
        }

        @Override
        public void triggerTemporaryQuotaBump(String packageName, @UserIdInt int userId) {
            synchronized (mLock) {
                List<TimedEvent> events = mTimingEvents.get(userId, packageName);
                if (events == null || events.size() == 0) {
                    // If the app hasn't run any jobs, there's no point giving it a quota bump.
                    return;
                }
                events.add(new QuotaBump(sElapsedRealtimeClock.millis()));
                invalidateAllExecutionStatsLocked(userId, packageName);
            }
            // Update jobs out of band.
            mHandler.obtainMessage(MSG_CHECK_PACKAGE, userId, 0, packageName).sendToTarget();
        }
    }

    @VisibleForTesting
    void updateStandbyBucket(
            final int userId, final @NonNull String packageName, final int bucketIndex) {
        if (DEBUG) {
            Slog.i(TAG, "Moving pkg " + packageToString(userId, packageName)
                    + " to bucketIndex " + bucketIndex);
        }
        List<JobStatus> restrictedChanges = new ArrayList<>();
        synchronized (mLock) {
            ShrinkableDebits debits = mEJStats.get(userId, packageName);
            if (debits != null) {
                debits.setStandbyBucketLocked(bucketIndex);
            }

            ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, packageName);
            if (jobs == null || jobs.size() == 0) {
                // Nothing further to do.
                return;
            }
            for (int i = jobs.size() - 1; i >= 0; i--) {
                JobStatus js = jobs.valueAt(i);
                // Effective standby bucket can change after this in some situations so
                // use the real bucket so that the job is tracked by the controllers.
                if ((bucketIndex == RESTRICTED_INDEX || js.getStandbyBucket() == RESTRICTED_INDEX)
                        && bucketIndex != js.getStandbyBucket()) {
                    restrictedChanges.add(js);
                }
                js.setStandbyBucket(bucketIndex);
            }
            Timer timer = mPkgTimers.get(userId, packageName);
            if (timer != null && timer.isActive()) {
                timer.rescheduleCutoff();
            }
            timer = mEJPkgTimers.get(userId, packageName);
            if (timer != null && timer.isActive()) {
                timer.rescheduleCutoff();
            }
            mStateChangedListener.onControllerStateChanged(
                    maybeUpdateConstraintForPkgLocked(
                            sElapsedRealtimeClock.millis(), userId, packageName));
        }
        if (restrictedChanges.size() > 0) {
            mStateChangedListener.onRestrictedBucketChanged(restrictedChanges);
        }
    }

    final class UsageEventTracker implements UsageEventListener {
        /**
         * Callback to inform listeners of a new event.
         */
        @Override
        public void onUsageEvent(int userId, @NonNull UsageEvents.Event event) {
            // Skip posting a message to the handler for events we don't care about.
            switch (event.getEventType()) {
                case UsageEvents.Event.ACTIVITY_RESUMED:
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED:
                case UsageEvents.Event.ACTIVITY_DESTROYED:
                case UsageEvents.Event.USER_INTERACTION:
                case UsageEvents.Event.CHOOSER_ACTION:
                case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                case UsageEvents.Event.NOTIFICATION_SEEN:
                    mHandler.obtainMessage(MSG_PROCESS_USAGE_EVENT, userId, 0, event)
                            .sendToTarget();
                    break;
                default:
                    if (DEBUG) {
                        Slog.d(TAG, "Dropping usage event " + event.getEventType());
                    }
                    break;
            }
        }
    }

    final class TempAllowlistTracker implements PowerAllowlistInternal.TempAllowlistChangeListener {

        @Override
        public void onAppAdded(int uid) {
            synchronized (mLock) {
                final long nowElapsed = sElapsedRealtimeClock.millis();
                mTempAllowlistCache.put(uid, true);
                final ArraySet<String> packages = mService.getPackagesForUidLocked(uid);
                if (packages != null) {
                    final int userId = UserHandle.getUserId(uid);
                    for (int i = packages.size() - 1; i >= 0; --i) {
                        Timer t = mEJPkgTimers.get(userId, packages.valueAt(i));
                        if (t != null) {
                            t.onStateChangedLocked(nowElapsed, true);
                        }
                    }
                    final ArraySet<JobStatus> changedJobs = maybeUpdateConstraintForUidLocked(uid);
                    if (changedJobs.size() > 0) {
                        mStateChangedListener.onControllerStateChanged(changedJobs);
                    }
                }
            }
        }

        @Override
        public void onAppRemoved(int uid) {
            synchronized (mLock) {
                final long nowElapsed = sElapsedRealtimeClock.millis();
                final long endElapsed = nowElapsed + mEJGracePeriodTempAllowlistMs;
                mTempAllowlistCache.delete(uid);
                mTempAllowlistGraceCache.put(uid, endElapsed);
                Message msg = mHandler.obtainMessage(MSG_END_GRACE_PERIOD, uid, 0);
                mHandler.sendMessageDelayed(msg, mEJGracePeriodTempAllowlistMs);
            }
        }
    }

    private static final class TimedEventTooOldPredicate implements Predicate<TimedEvent> {
        private long mNowElapsed;

        private void updateNow() {
            mNowElapsed = sElapsedRealtimeClock.millis();
        }

        @Override
        public boolean test(TimedEvent ts) {
            return ts.getEndTimeElapsed() <= mNowElapsed - MAX_PERIOD_MS;
        }
    }

    private final TimedEventTooOldPredicate mTimedEventTooOld = new TimedEventTooOldPredicate();

    private final Consumer<List<TimedEvent>> mDeleteOldEventsFunctor = events -> {
        if (events != null) {
            // Remove everything older than MAX_PERIOD_MS time ago.
            events.removeIf(mTimedEventTooOld);
        }
    };

    @VisibleForTesting
    void deleteObsoleteSessionsLocked() {
        mTimedEventTooOld.updateNow();

        // Regular sessions
        mTimingEvents.forEach(mDeleteOldEventsFunctor);

        // EJ sessions
        for (int uIdx = 0; uIdx < mEJTimingSessions.numMaps(); ++uIdx) {
            final int userId = mEJTimingSessions.keyAt(uIdx);
            for (int pIdx = 0; pIdx < mEJTimingSessions.numElementsForKey(userId); ++pIdx) {
                final String packageName = mEJTimingSessions.keyAt(uIdx, pIdx);
                final ShrinkableDebits debits = getEJDebitsLocked(userId, packageName);
                final List<TimedEvent> sessions = mEJTimingSessions.get(userId, packageName);
                if (sessions == null) {
                    continue;
                }

                while (sessions.size() > 0) {
                    final TimingSession ts = (TimingSession) sessions.get(0);
                    if (mTimedEventTooOld.test(ts)) {
                        // Stale sessions may still be factored into tally. Remove them.
                        final long duration = ts.endTimeElapsed - ts.startTimeElapsed;
                        debits.transactLocked(-duration);
                        sessions.remove(0);
                    } else {
                        break;
                    }
                }
            }
        }
    }

    private class QcHandler extends Handler {

        QcHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                switch (msg.what) {
                    case MSG_REACHED_TIME_QUOTA: {
                        UserPackage pkg = (UserPackage) msg.obj;
                        if (DEBUG) {
                            Slog.d(TAG, "Checking if " + pkg + " has reached its quota.");
                        }

                        long timeRemainingMs = getRemainingExecutionTimeLocked(pkg.userId,
                                pkg.packageName);
                        if (timeRemainingMs <= 50) {
                            // Less than 50 milliseconds left. Start process of shutting down jobs.
                            if (DEBUG) Slog.d(TAG, pkg + " has reached its quota.");
                            mStateChangedListener.onControllerStateChanged(
                                    maybeUpdateConstraintForPkgLocked(
                                            sElapsedRealtimeClock.millis(),
                                            pkg.userId, pkg.packageName));
                        } else {
                            // This could potentially happen if an old session phases out while a
                            // job is currently running.
                            // Reschedule message
                            Message rescheduleMsg = obtainMessage(MSG_REACHED_TIME_QUOTA, pkg);
                            timeRemainingMs = getTimeUntilQuotaConsumedLocked(pkg.userId,
                                    pkg.packageName);
                            if (DEBUG) {
                                Slog.d(TAG, pkg + " has " + timeRemainingMs + "ms left.");
                            }
                            sendMessageDelayed(rescheduleMsg, timeRemainingMs);
                        }
                        break;
                    }
                    case MSG_REACHED_EJ_TIME_QUOTA: {
                        UserPackage pkg = (UserPackage) msg.obj;
                        if (DEBUG) {
                            Slog.d(TAG, "Checking if " + pkg + " has reached its EJ quota.");
                        }

                        long timeRemainingMs = getRemainingEJExecutionTimeLocked(
                                pkg.userId, pkg.packageName);
                        if (timeRemainingMs <= 0) {
                            if (DEBUG) Slog.d(TAG, pkg + " has reached its EJ quota.");
                            mStateChangedListener.onControllerStateChanged(
                                    maybeUpdateConstraintForPkgLocked(
                                            sElapsedRealtimeClock.millis(),
                                            pkg.userId, pkg.packageName));
                        } else {
                            // This could potentially happen if an old session phases out while a
                            // job is currently running.
                            // Reschedule message
                            Message rescheduleMsg = obtainMessage(MSG_REACHED_EJ_TIME_QUOTA, pkg);
                            timeRemainingMs = getTimeUntilEJQuotaConsumedLocked(
                                    pkg.userId, pkg.packageName);
                            if (DEBUG) {
                                Slog.d(TAG, pkg + " has " + timeRemainingMs + "ms left for EJ");
                            }
                            sendMessageDelayed(rescheduleMsg, timeRemainingMs);
                        }
                        break;
                    }
                    case MSG_REACHED_COUNT_QUOTA: {
                        UserPackage pkg = (UserPackage) msg.obj;
                        if (DEBUG) {
                            Slog.d(TAG, pkg + " has reached its count quota.");
                        }

                        mStateChangedListener.onControllerStateChanged(
                                maybeUpdateConstraintForPkgLocked(
                                        sElapsedRealtimeClock.millis(),
                                        pkg.userId, pkg.packageName));
                        break;
                    }
                    case MSG_CLEAN_UP_SESSIONS:
                        if (DEBUG) {
                            Slog.d(TAG, "Cleaning up timing sessions.");
                        }
                        deleteObsoleteSessionsLocked();
                        maybeScheduleCleanupAlarmLocked();

                        break;
                    case MSG_CHECK_PACKAGE: {
                        String packageName = (String) msg.obj;
                        int userId = msg.arg1;
                        if (DEBUG) {
                            Slog.d(TAG, "Checking pkg " + packageToString(userId, packageName));
                        }
                        mStateChangedListener.onControllerStateChanged(
                                maybeUpdateConstraintForPkgLocked(sElapsedRealtimeClock.millis(),
                                        userId, packageName));
                        break;
                    }
                    case MSG_UID_PROCESS_STATE_CHANGED: {
                        final int uid = msg.arg1;
                        final int procState = msg.arg2;
                        final int userId = UserHandle.getUserId(uid);
                        final long nowElapsed = sElapsedRealtimeClock.millis();

                        synchronized (mLock) {
                            boolean isQuotaFree;
                            if (procState <= ActivityManager.PROCESS_STATE_TOP) {
                                mTopAppCache.put(uid, true);
                                mTopAppGraceCache.delete(uid);
                                if (mForegroundUids.get(uid)) {
                                    // Went from FGS to TOP. We don't need to reprocess timers or
                                    // jobs.
                                    break;
                                }
                                mForegroundUids.put(uid, true);
                                isQuotaFree = true;
                            } else {
                                final boolean reprocess;
                                if (procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                                    reprocess = !mForegroundUids.get(uid);
                                    mForegroundUids.put(uid, true);
                                    isQuotaFree = true;
                                } else {
                                    reprocess = true;
                                    mForegroundUids.delete(uid);
                                    isQuotaFree = false;
                                }
                                if (mTopAppCache.get(uid)) {
                                    final long endElapsed = nowElapsed + mEJGracePeriodTopAppMs;
                                    mTopAppCache.delete(uid);
                                    mTopAppGraceCache.put(uid, endElapsed);
                                    sendMessageDelayed(obtainMessage(MSG_END_GRACE_PERIOD, uid, 0),
                                            mEJGracePeriodTopAppMs);
                                }
                                if (!reprocess) {
                                    break;
                                }
                            }
                            // Update Timers first.
                            if (mPkgTimers.indexOfKey(userId) >= 0
                                    || mEJPkgTimers.indexOfKey(userId) >= 0) {
                                final ArraySet<String> packages =
                                        mService.getPackagesForUidLocked(uid);
                                if (packages != null) {
                                    for (int i = packages.size() - 1; i >= 0; --i) {
                                        Timer t = mEJPkgTimers.get(userId, packages.valueAt(i));
                                        if (t != null) {
                                            t.onStateChangedLocked(nowElapsed, isQuotaFree);
                                        }
                                        t = mPkgTimers.get(userId, packages.valueAt(i));
                                        if (t != null) {
                                            t.onStateChangedLocked(nowElapsed, isQuotaFree);
                                        }
                                    }
                                }
                            }
                            final ArraySet<JobStatus> changedJobs =
                                    maybeUpdateConstraintForUidLocked(uid);
                            if (changedJobs.size() > 0) {
                                mStateChangedListener.onControllerStateChanged(changedJobs);
                            }
                        }
                        break;
                    }
                    case MSG_PROCESS_USAGE_EVENT: {
                        final int userId = msg.arg1;
                        final UsageEvents.Event event = (UsageEvents.Event) msg.obj;
                        final String pkgName = event.getPackageName();
                        if (DEBUG) {
                            Slog.d(TAG, "Processing event " + event.getEventType()
                                    + " for " + packageToString(userId, pkgName));
                        }
                        switch (event.getEventType()) {
                            case UsageEvents.Event.ACTIVITY_RESUMED:
                            case UsageEvents.Event.ACTIVITY_PAUSED:
                            case UsageEvents.Event.ACTIVITY_STOPPED:
                            case UsageEvents.Event.ACTIVITY_DESTROYED:
                                synchronized (mLock) {
                                    TopAppTimer timer = mTopAppTrackers.get(userId, pkgName);
                                    if (timer == null) {
                                        timer = new TopAppTimer(userId, pkgName);
                                        mTopAppTrackers.add(userId, pkgName, timer);
                                    }
                                    timer.processEventLocked(event);
                                }
                                break;
                            case UsageEvents.Event.USER_INTERACTION:
                            case UsageEvents.Event.CHOOSER_ACTION:
                            case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                                // Don't need to include SHORTCUT_INVOCATION. The app will be
                                // launched through it (if it's not already on top).
                                grantRewardForInstantEvent(
                                        userId, pkgName, mEJRewardInteractionMs);
                                break;
                            case UsageEvents.Event.NOTIFICATION_SEEN:
                                // Intentionally don't give too much for notification seen.
                                // Interactions will award more.
                                grantRewardForInstantEvent(
                                        userId, pkgName, mEJRewardNotificationSeenMs);
                                break;
                        }

                        break;
                    }
                    case MSG_END_GRACE_PERIOD: {
                        final int uid = msg.arg1;
                        synchronized (mLock) {
                            if (mTempAllowlistCache.get(uid) || mTopAppCache.get(uid)) {
                                // App added back to the temp allowlist or became top again
                                // during the grace period.
                                if (DEBUG) {
                                    Slog.d(TAG, uid + " is still allowed");
                                }
                                break;
                            }
                            final long nowElapsed = sElapsedRealtimeClock.millis();
                            if (nowElapsed < mTempAllowlistGraceCache.get(uid)
                                    || nowElapsed < mTopAppGraceCache.get(uid)) {
                                // One of the grace periods is still in effect.
                                if (DEBUG) {
                                    Slog.d(TAG, uid + " is still in grace period");
                                }
                                break;
                            }
                            if (DEBUG) {
                                Slog.d(TAG, uid + " is now out of grace period");
                            }
                            mTempAllowlistGraceCache.delete(uid);
                            mTopAppGraceCache.delete(uid);
                            final ArraySet<String> packages = mService.getPackagesForUidLocked(uid);
                            if (packages != null) {
                                final int userId = UserHandle.getUserId(uid);
                                for (int i = packages.size() - 1; i >= 0; --i) {
                                    Timer t = mEJPkgTimers.get(userId, packages.valueAt(i));
                                    if (t != null) {
                                        t.onStateChangedLocked(nowElapsed, false);
                                    }
                                }
                                final ArraySet<JobStatus> changedJobs =
                                        maybeUpdateConstraintForUidLocked(uid);
                                if (changedJobs.size() > 0) {
                                    mStateChangedListener.onControllerStateChanged(changedJobs);
                                }
                            }
                        }

                        break;
                    }
                }
            }
        }
    }

    /** Track when UPTCs are expected to come back into quota. */
    private class InQuotaAlarmQueue extends AlarmQueue<UserPackage> {
        private InQuotaAlarmQueue(Context context, Looper looper) {
            super(context, looper, ALARM_TAG_QUOTA_CHECK, "In quota", false,
                    QcConstants.DEFAULT_MIN_QUOTA_CHECK_DELAY_MS);
        }

        @Override
        protected boolean isForUser(@NonNull UserPackage key, int userId) {
            return key.userId == userId;
        }

        @Override
        protected void processExpiredAlarms(@NonNull ArraySet<UserPackage> expired) {
            for (int i = 0; i < expired.size(); ++i) {
                UserPackage p = expired.valueAt(i);
                mHandler.obtainMessage(MSG_CHECK_PACKAGE, p.userId, 0, p.packageName)
                        .sendToTarget();
            }
        }
    }

    @Override
    public void prepareForUpdatedConstantsLocked() {
        mQcConstants.mShouldReevaluateConstraints = false;
        mQcConstants.mRateLimitingConstantsUpdated = false;
        mQcConstants.mExecutionPeriodConstantsUpdated = false;
        mQcConstants.mEJLimitConstantsUpdated = false;
        mQcConstants.mQuotaBumpConstantsUpdated = false;
    }

    @Override
    public void processConstantLocked(DeviceConfig.Properties properties, String key) {
        mQcConstants.processConstantLocked(properties, key);
    }

    @Override
    public void onConstantsUpdatedLocked() {
        if (mQcConstants.mShouldReevaluateConstraints) {
            // Update job bookkeeping out of band.
            AppSchedulingModuleThread.getHandler().post(() -> {
                synchronized (mLock) {
                    invalidateAllExecutionStatsLocked();
                    maybeUpdateAllConstraintsLocked();
                }
            });
        }
    }

    @VisibleForTesting
    class QcConstants {
        private boolean mShouldReevaluateConstraints = false;
        private boolean mRateLimitingConstantsUpdated = false;
        private boolean mExecutionPeriodConstantsUpdated = false;
        private boolean mEJLimitConstantsUpdated = false;
        private boolean mQuotaBumpConstantsUpdated = false;

        /** Prefix to use with all constant keys in order to "sub-namespace" the keys. */
        private static final String QC_CONSTANT_PREFIX = "qc_";

        /**
         * Previously used keys:
         *   * allowed_time_per_period_ms -- No longer used after splitting by bucket
         */

        @VisibleForTesting
        static final String KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS =
                QC_CONSTANT_PREFIX + "allowed_time_per_period_exempted_ms";
        @VisibleForTesting
        static final String KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS =
                QC_CONSTANT_PREFIX + "allowed_time_per_period_active_ms";
        @VisibleForTesting
        static final String KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS =
                QC_CONSTANT_PREFIX + "allowed_time_per_period_working_ms";
        @VisibleForTesting
        static final String KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS =
                QC_CONSTANT_PREFIX + "allowed_time_per_period_frequent_ms";
        @VisibleForTesting
        static final String KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS =
                QC_CONSTANT_PREFIX + "allowed_time_per_period_rare_ms";
        @VisibleForTesting
        static final String KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS =
                QC_CONSTANT_PREFIX + "allowed_time_per_period_restricted_ms";
        @VisibleForTesting
        static final String KEY_IN_QUOTA_BUFFER_MS =
                QC_CONSTANT_PREFIX + "in_quota_buffer_ms";
        @VisibleForTesting
        static final String KEY_WINDOW_SIZE_EXEMPTED_MS =
                QC_CONSTANT_PREFIX + "window_size_exempted_ms";
        @VisibleForTesting
        static final String KEY_WINDOW_SIZE_ACTIVE_MS =
                QC_CONSTANT_PREFIX + "window_size_active_ms";
        @VisibleForTesting
        static final String KEY_WINDOW_SIZE_WORKING_MS =
                QC_CONSTANT_PREFIX + "window_size_working_ms";
        @VisibleForTesting
        static final String KEY_WINDOW_SIZE_FREQUENT_MS =
                QC_CONSTANT_PREFIX + "window_size_frequent_ms";
        @VisibleForTesting
        static final String KEY_WINDOW_SIZE_RARE_MS =
                QC_CONSTANT_PREFIX + "window_size_rare_ms";
        @VisibleForTesting
        static final String KEY_WINDOW_SIZE_RESTRICTED_MS =
                QC_CONSTANT_PREFIX + "window_size_restricted_ms";
        @VisibleForTesting
        static final String KEY_MAX_EXECUTION_TIME_MS =
                QC_CONSTANT_PREFIX + "max_execution_time_ms";
        @VisibleForTesting
        static final String KEY_MAX_JOB_COUNT_EXEMPTED =
                QC_CONSTANT_PREFIX + "max_job_count_exempted";
        @VisibleForTesting
        static final String KEY_MAX_JOB_COUNT_ACTIVE =
                QC_CONSTANT_PREFIX + "max_job_count_active";
        @VisibleForTesting
        static final String KEY_MAX_JOB_COUNT_WORKING =
                QC_CONSTANT_PREFIX + "max_job_count_working";
        @VisibleForTesting
        static final String KEY_MAX_JOB_COUNT_FREQUENT =
                QC_CONSTANT_PREFIX + "max_job_count_frequent";
        @VisibleForTesting
        static final String KEY_MAX_JOB_COUNT_RARE =
                QC_CONSTANT_PREFIX + "max_job_count_rare";
        @VisibleForTesting
        static final String KEY_MAX_JOB_COUNT_RESTRICTED =
                QC_CONSTANT_PREFIX + "max_job_count_restricted";
        @VisibleForTesting
        static final String KEY_RATE_LIMITING_WINDOW_MS =
                QC_CONSTANT_PREFIX + "rate_limiting_window_ms";
        @VisibleForTesting
        static final String KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW =
                QC_CONSTANT_PREFIX + "max_job_count_per_rate_limiting_window";
        @VisibleForTesting
        static final String KEY_MAX_SESSION_COUNT_EXEMPTED =
                QC_CONSTANT_PREFIX + "max_session_count_exempted";
        @VisibleForTesting
        static final String KEY_MAX_SESSION_COUNT_ACTIVE =
                QC_CONSTANT_PREFIX + "max_session_count_active";
        @VisibleForTesting
        static final String KEY_MAX_SESSION_COUNT_WORKING =
                QC_CONSTANT_PREFIX + "max_session_count_working";
        @VisibleForTesting
        static final String KEY_MAX_SESSION_COUNT_FREQUENT =
                QC_CONSTANT_PREFIX + "max_session_count_frequent";
        @VisibleForTesting
        static final String KEY_MAX_SESSION_COUNT_RARE =
                QC_CONSTANT_PREFIX + "max_session_count_rare";
        @VisibleForTesting
        static final String KEY_MAX_SESSION_COUNT_RESTRICTED =
                QC_CONSTANT_PREFIX + "max_session_count_restricted";
        @VisibleForTesting
        static final String KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW =
                QC_CONSTANT_PREFIX + "max_session_count_per_rate_limiting_window";
        @VisibleForTesting
        static final String KEY_TIMING_SESSION_COALESCING_DURATION_MS =
                QC_CONSTANT_PREFIX + "timing_session_coalescing_duration_ms";
        @VisibleForTesting
        static final String KEY_MIN_QUOTA_CHECK_DELAY_MS =
                QC_CONSTANT_PREFIX + "min_quota_check_delay_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_EXEMPTED_MS =
                QC_CONSTANT_PREFIX + "ej_limit_exempted_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_ACTIVE_MS =
                QC_CONSTANT_PREFIX + "ej_limit_active_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_WORKING_MS =
                QC_CONSTANT_PREFIX + "ej_limit_working_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_FREQUENT_MS =
                QC_CONSTANT_PREFIX + "ej_limit_frequent_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_RARE_MS =
                QC_CONSTANT_PREFIX + "ej_limit_rare_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_RESTRICTED_MS =
                QC_CONSTANT_PREFIX + "ej_limit_restricted_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_ADDITION_SPECIAL_MS =
                QC_CONSTANT_PREFIX + "ej_limit_addition_special_ms";
        @VisibleForTesting
        static final String KEY_EJ_LIMIT_ADDITION_INSTALLER_MS =
                QC_CONSTANT_PREFIX + "ej_limit_addition_installer_ms";
        @VisibleForTesting
        static final String KEY_EJ_WINDOW_SIZE_MS =
                QC_CONSTANT_PREFIX + "ej_window_size_ms";
        @VisibleForTesting
        static final String KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS =
                QC_CONSTANT_PREFIX + "ej_top_app_time_chunk_size_ms";
        @VisibleForTesting
        static final String KEY_EJ_REWARD_TOP_APP_MS =
                QC_CONSTANT_PREFIX + "ej_reward_top_app_ms";
        @VisibleForTesting
        static final String KEY_EJ_REWARD_INTERACTION_MS =
                QC_CONSTANT_PREFIX + "ej_reward_interaction_ms";
        @VisibleForTesting
        static final String KEY_EJ_REWARD_NOTIFICATION_SEEN_MS =
                QC_CONSTANT_PREFIX + "ej_reward_notification_seen_ms";
        @VisibleForTesting
        static final String KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS =
                QC_CONSTANT_PREFIX + "ej_grace_period_temp_allowlist_ms";
        @VisibleForTesting
        static final String KEY_EJ_GRACE_PERIOD_TOP_APP_MS =
                QC_CONSTANT_PREFIX + "ej_grace_period_top_app_ms";
        @VisibleForTesting
        static final String KEY_QUOTA_BUMP_ADDITIONAL_DURATION_MS =
                QC_CONSTANT_PREFIX + "quota_bump_additional_duration_ms";
        @VisibleForTesting
        static final String KEY_QUOTA_BUMP_ADDITIONAL_JOB_COUNT =
                QC_CONSTANT_PREFIX + "quota_bump_additional_job_count";
        @VisibleForTesting
        static final String KEY_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT =
                QC_CONSTANT_PREFIX + "quota_bump_additional_session_count";
        @VisibleForTesting
        static final String KEY_QUOTA_BUMP_WINDOW_SIZE_MS =
                QC_CONSTANT_PREFIX + "quota_bump_window_size_ms";
        @VisibleForTesting
        static final String KEY_QUOTA_BUMP_LIMIT =
                QC_CONSTANT_PREFIX + "quota_bump_limit";

        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS =
                10 * 60 * 1000L; // 10 minutes
        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS =
                10 * 60 * 1000L; // 10 minutes
        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_WORKING_MS =
                10 * 60 * 1000L; // 10 minutes
        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS =
                10 * 60 * 1000L; // 10 minutes
        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_RARE_MS =
                10 * 60 * 1000L; // 10 minutes
        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS =
                10 * 60 * 1000L; // 10 minutes
        private static final long DEFAULT_IN_QUOTA_BUFFER_MS =
                30 * 1000L; // 30 seconds
        private static final long DEFAULT_WINDOW_SIZE_EXEMPTED_MS =
                DEFAULT_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS; // EXEMPT apps can run jobs at any time
        private static final long DEFAULT_WINDOW_SIZE_ACTIVE_MS =
                DEFAULT_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS; // ACTIVE apps can run jobs at any time
        private static final long DEFAULT_WINDOW_SIZE_WORKING_MS =
                2 * 60 * 60 * 1000L; // 2 hours
        private static final long DEFAULT_WINDOW_SIZE_FREQUENT_MS =
                8 * 60 * 60 * 1000L; // 8 hours
        private static final long DEFAULT_WINDOW_SIZE_RARE_MS =
                24 * 60 * 60 * 1000L; // 24 hours
        private static final long DEFAULT_WINDOW_SIZE_RESTRICTED_MS =
                24 * 60 * 60 * 1000L; // 24 hours
        private static final long DEFAULT_MAX_EXECUTION_TIME_MS =
                4 * HOUR_IN_MILLIS;
        private static final long DEFAULT_RATE_LIMITING_WINDOW_MS =
                MINUTE_IN_MILLIS;
        private static final int DEFAULT_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW = 20;
        private static final int DEFAULT_MAX_JOB_COUNT_EXEMPTED =
                75; // 75/window = 450/hr = 1/session
        private static final int DEFAULT_MAX_JOB_COUNT_ACTIVE = DEFAULT_MAX_JOB_COUNT_EXEMPTED;
        private static final int DEFAULT_MAX_JOB_COUNT_WORKING = // 120/window = 60/hr = 12/session
                (int) (60.0 * DEFAULT_WINDOW_SIZE_WORKING_MS / HOUR_IN_MILLIS);
        private static final int DEFAULT_MAX_JOB_COUNT_FREQUENT = // 200/window = 25/hr = 25/session
                (int) (25.0 * DEFAULT_WINDOW_SIZE_FREQUENT_MS / HOUR_IN_MILLIS);
        private static final int DEFAULT_MAX_JOB_COUNT_RARE = // 48/window = 2/hr = 16/session
                (int) (2.0 * DEFAULT_WINDOW_SIZE_RARE_MS / HOUR_IN_MILLIS);
        private static final int DEFAULT_MAX_JOB_COUNT_RESTRICTED = 10;
        private static final int DEFAULT_MAX_SESSION_COUNT_EXEMPTED =
                75; // 450/hr
        private static final int DEFAULT_MAX_SESSION_COUNT_ACTIVE =
                DEFAULT_MAX_SESSION_COUNT_EXEMPTED;
        private static final int DEFAULT_MAX_SESSION_COUNT_WORKING =
                10; // 5/hr
        private static final int DEFAULT_MAX_SESSION_COUNT_FREQUENT =
                8; // 1/hr
        private static final int DEFAULT_MAX_SESSION_COUNT_RARE =
                3; // .125/hr
        private static final int DEFAULT_MAX_SESSION_COUNT_RESTRICTED = 1; // 1/day
        private static final int DEFAULT_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW = 20;
        private static final long DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS = 5000; // 5 seconds
        private static final long DEFAULT_MIN_QUOTA_CHECK_DELAY_MS = MINUTE_IN_MILLIS;
        // TODO(267949143): set a different limit for headless system apps
        private static final long DEFAULT_EJ_LIMIT_EXEMPTED_MS = 60 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_ACTIVE_MS = 30 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_WORKING_MS = DEFAULT_EJ_LIMIT_ACTIVE_MS;
        private static final long DEFAULT_EJ_LIMIT_FREQUENT_MS = 10 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_RARE_MS = DEFAULT_EJ_LIMIT_FREQUENT_MS;
        private static final long DEFAULT_EJ_LIMIT_RESTRICTED_MS = 5 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_ADDITION_SPECIAL_MS = 15 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_ADDITION_INSTALLER_MS = 30 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_WINDOW_SIZE_MS = 24 * HOUR_IN_MILLIS;
        private static final long DEFAULT_EJ_TOP_APP_TIME_CHUNK_SIZE_MS = 30 * SECOND_IN_MILLIS;
        private static final long DEFAULT_EJ_REWARD_TOP_APP_MS = 10 * SECOND_IN_MILLIS;
        private static final long DEFAULT_EJ_REWARD_INTERACTION_MS = 15 * SECOND_IN_MILLIS;
        private static final long DEFAULT_EJ_REWARD_NOTIFICATION_SEEN_MS = 0;
        private static final long DEFAULT_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS = 3 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_GRACE_PERIOD_TOP_APP_MS = 1 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_QUOTA_BUMP_ADDITIONAL_DURATION_MS = 1 * MINUTE_IN_MILLIS;
        private static final int DEFAULT_QUOTA_BUMP_ADDITIONAL_JOB_COUNT = 2;
        private static final int DEFAULT_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT = 1;
        private static final long DEFAULT_QUOTA_BUMP_WINDOW_SIZE_MS = 8 * HOUR_IN_MILLIS;
        private static final int DEFAULT_QUOTA_BUMP_LIMIT = 8;

        /**
         * How much time each app in the exempted bucket will have to run jobs within their standby
         * bucket window.
         */
        public long ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS =
                DEFAULT_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS;
        /**
         * How much time each app in the active bucket will have to run jobs within their standby
         * bucket window.
         */
        public long ALLOWED_TIME_PER_PERIOD_ACTIVE_MS = DEFAULT_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS;
        /**
         * How much time each app in the working set bucket will have to run jobs within their
         * standby bucket window.
         */
        public long ALLOWED_TIME_PER_PERIOD_WORKING_MS = DEFAULT_ALLOWED_TIME_PER_PERIOD_WORKING_MS;
        /**
         * How much time each app in the frequent bucket will have to run jobs within their standby
         * bucket window.
         */
        public long ALLOWED_TIME_PER_PERIOD_FREQUENT_MS =
                DEFAULT_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS;
        /**
         * How much time each app in the rare bucket will have to run jobs within their standby
         * bucket window.
         */
        public long ALLOWED_TIME_PER_PERIOD_RARE_MS = DEFAULT_ALLOWED_TIME_PER_PERIOD_RARE_MS;
        /**
         * How much time each app in the restricted bucket will have to run jobs within their
         * standby bucket window.
         */
        public long ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS =
                DEFAULT_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS;

        /**
         * How much time the package should have before transitioning from out-of-quota to in-quota.
         * This should not affect processing if the package is already in-quota.
         */
        public long IN_QUOTA_BUFFER_MS = DEFAULT_IN_QUOTA_BUFFER_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_EXEMPTED_MS = DEFAULT_WINDOW_SIZE_EXEMPTED_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_ACTIVE_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_ACTIVE_MS = DEFAULT_WINDOW_SIZE_ACTIVE_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_WORKING_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_WORKING_MS = DEFAULT_WINDOW_SIZE_WORKING_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_FREQUENT_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_FREQUENT_MS = DEFAULT_WINDOW_SIZE_FREQUENT_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_RARE_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_RARE_MS = DEFAULT_WINDOW_SIZE_RARE_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_RESTRICTED_MS = DEFAULT_WINDOW_SIZE_RESTRICTED_MS;

        /**
         * The maximum amount of time an app can have its jobs running within a 24 hour window.
         */
        public long MAX_EXECUTION_TIME_MS = DEFAULT_MAX_EXECUTION_TIME_MS;

        /**
         * The maximum number of jobs an app can run within this particular standby bucket's
         * window size.
         */
        public int MAX_JOB_COUNT_EXEMPTED = DEFAULT_MAX_JOB_COUNT_EXEMPTED;

        /**
         * The maximum number of jobs an app can run within this particular standby bucket's
         * window size.
         */
        public int MAX_JOB_COUNT_ACTIVE = DEFAULT_MAX_JOB_COUNT_ACTIVE;

        /**
         * The maximum number of jobs an app can run within this particular standby bucket's
         * window size.
         */
        public int MAX_JOB_COUNT_WORKING = DEFAULT_MAX_JOB_COUNT_WORKING;

        /**
         * The maximum number of jobs an app can run within this particular standby bucket's
         * window size.
         */
        public int MAX_JOB_COUNT_FREQUENT = DEFAULT_MAX_JOB_COUNT_FREQUENT;

        /**
         * The maximum number of jobs an app can run within this particular standby bucket's
         * window size.
         */
        public int MAX_JOB_COUNT_RARE = DEFAULT_MAX_JOB_COUNT_RARE;

        /**
         * The maximum number of jobs an app can run within this particular standby bucket's
         * window size.
         */
        public int MAX_JOB_COUNT_RESTRICTED = DEFAULT_MAX_JOB_COUNT_RESTRICTED;

        /** The period of time used to rate limit recently run jobs. */
        public long RATE_LIMITING_WINDOW_MS = DEFAULT_RATE_LIMITING_WINDOW_MS;

        /**
         * The maximum number of jobs that can run within the past {@link #RATE_LIMITING_WINDOW_MS}.
         */
        public int MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW =
                DEFAULT_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW;

        /**
         * The maximum number of {@link TimingSession TimingSessions} an app can run within this
         * particular standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_EXEMPTED = DEFAULT_MAX_SESSION_COUNT_EXEMPTED;

        /**
         * The maximum number of {@link TimingSession TimingSessions} an app can run within this
         * particular standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_ACTIVE = DEFAULT_MAX_SESSION_COUNT_ACTIVE;

        /**
         * The maximum number of {@link TimingSession TimingSessions} an app can run within this
         * particular standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_WORKING = DEFAULT_MAX_SESSION_COUNT_WORKING;

        /**
         * The maximum number of {@link TimingSession TimingSessions} an app can run within this
         * particular standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_FREQUENT = DEFAULT_MAX_SESSION_COUNT_FREQUENT;

        /**
         * The maximum number of {@link TimingSession TimingSessions} an app can run within this
         * particular standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_RARE = DEFAULT_MAX_SESSION_COUNT_RARE;

        /**
         * The maximum number of {@link TimingSession TimingSessions} an app can run within this
         * particular standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_RESTRICTED = DEFAULT_MAX_SESSION_COUNT_RESTRICTED;

        /**
         * The maximum number of {@link TimingSession TimingSessions} that can run within the past
         * {@link #RATE_LIMITING_WINDOW_MS}.
         */
        public int MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW =
                DEFAULT_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW;

        /**
         * Treat two distinct {@link TimingSession TimingSessions} as the same if they start and
         * end within this amount of time of each other.
         */
        public long TIMING_SESSION_COALESCING_DURATION_MS =
                DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS;

        /** The minimum amount of time between quota check alarms. */
        public long MIN_QUOTA_CHECK_DELAY_MS = DEFAULT_MIN_QUOTA_CHECK_DELAY_MS;

        // Safeguards

        /** The minimum number of jobs that any bucket will be allowed to run within its window. */
        private static final int MIN_BUCKET_JOB_COUNT = 10;

        /**
         * The minimum number of {@link TimingSession TimingSessions} that any bucket will be
         * allowed to run within its window.
         */
        private static final int MIN_BUCKET_SESSION_COUNT = 1;

        /** The minimum value that {@link #MAX_EXECUTION_TIME_MS} can have. */
        private static final long MIN_MAX_EXECUTION_TIME_MS = 60 * MINUTE_IN_MILLIS;

        /** The minimum value that {@link #MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW} can have. */
        private static final int MIN_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW = 10;

        /** The minimum value that {@link #MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW} can have. */
        private static final int MIN_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW = 10;

        /** The minimum value that {@link #RATE_LIMITING_WINDOW_MS} can have. */
        private static final long MIN_RATE_LIMITING_WINDOW_MS = 30 * SECOND_IN_MILLIS;

        /**
         * The total expedited job session limit of the particular standby bucket. Apps in this
         * standby bucket can only have expedited job sessions totalling EJ_LIMIT (without factoring
         * in any rewards or free EJs).
         */
        public long EJ_LIMIT_EXEMPTED_MS = DEFAULT_EJ_LIMIT_EXEMPTED_MS;

        /**
         * The total expedited job session limit of the particular standby bucket. Apps in this
         * standby bucket can only have expedited job sessions totalling EJ_LIMIT (without factoring
         * in any rewards or free EJs).
         */
        public long EJ_LIMIT_ACTIVE_MS = DEFAULT_EJ_LIMIT_ACTIVE_MS;

        /**
         * The total expedited job session limit of the particular standby bucket. Apps in this
         * standby bucket can only have expedited job sessions totalling EJ_LIMIT (without factoring
         * in any rewards or free EJs).
         */
        public long EJ_LIMIT_WORKING_MS = DEFAULT_EJ_LIMIT_WORKING_MS;

        /**
         * The total expedited job session limit of the particular standby bucket. Apps in this
         * standby bucket can only have expedited job sessions totalling EJ_LIMIT (without factoring
         * in any rewards or free EJs).
         */
        public long EJ_LIMIT_FREQUENT_MS = DEFAULT_EJ_LIMIT_FREQUENT_MS;

        /**
         * The total expedited job session limit of the particular standby bucket. Apps in this
         * standby bucket can only have expedited job sessions totalling EJ_LIMIT (without factoring
         * in any rewards or free EJs).
         */
        public long EJ_LIMIT_RARE_MS = DEFAULT_EJ_LIMIT_RARE_MS;

        /**
         * The total expedited job session limit of the particular standby bucket. Apps in this
         * standby bucket can only have expedited job sessions totalling EJ_LIMIT (without factoring
         * in any rewards or free EJs).
         */
        public long EJ_LIMIT_RESTRICTED_MS = DEFAULT_EJ_LIMIT_RESTRICTED_MS;

        /**
         * How much additional EJ quota special, critical apps should get.
         */
        public long EJ_LIMIT_ADDITION_SPECIAL_MS = DEFAULT_EJ_LIMIT_ADDITION_SPECIAL_MS;

        /**
         * How much additional EJ quota system installers (with the INSTALL_PACKAGES permission)
         * should get.
         */
        public long EJ_LIMIT_ADDITION_INSTALLER_MS = DEFAULT_EJ_LIMIT_ADDITION_INSTALLER_MS;

        /**
         * The period of time used to calculate expedited job sessions. Apps can only have expedited
         * job sessions totalling EJ_LIMIT_<bucket>_MS within this period of time (without factoring
         * in any rewards or free EJs).
         */
        public long EJ_WINDOW_SIZE_MS = DEFAULT_EJ_WINDOW_SIZE_MS;

        /**
         * Length of time used to split an app's top time into chunks.
         */
        public long EJ_TOP_APP_TIME_CHUNK_SIZE_MS = DEFAULT_EJ_TOP_APP_TIME_CHUNK_SIZE_MS;

        /**
         * How much EJ quota to give back to an app based on the number of top app time chunks it
         * had.
         */
        public long EJ_REWARD_TOP_APP_MS = DEFAULT_EJ_REWARD_TOP_APP_MS;

        /**
         * How much EJ quota to give back to an app based on each non-top user interaction.
         */
        public long EJ_REWARD_INTERACTION_MS = DEFAULT_EJ_REWARD_INTERACTION_MS;

        /**
         * How much EJ quota to give back to an app based on each notification seen event.
         */
        public long EJ_REWARD_NOTIFICATION_SEEN_MS = DEFAULT_EJ_REWARD_NOTIFICATION_SEEN_MS;

        /**
         * How much additional grace period to add to the end of an app's temp allowlist
         * duration.
         */
        public long EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS = DEFAULT_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS;

        /**
         * How much additional grace period to give an app when it leaves the TOP state.
         */
        public long EJ_GRACE_PERIOD_TOP_APP_MS = DEFAULT_EJ_GRACE_PERIOD_TOP_APP_MS;

        /**
         * How much additional session duration to give an app for each accepted quota bump.
         */
        public long QUOTA_BUMP_ADDITIONAL_DURATION_MS = DEFAULT_QUOTA_BUMP_ADDITIONAL_DURATION_MS;

        /**
         * How many additional regular jobs to give an app for each accepted quota bump.
         */
        public int QUOTA_BUMP_ADDITIONAL_JOB_COUNT = DEFAULT_QUOTA_BUMP_ADDITIONAL_JOB_COUNT;

        /**
         * How many additional sessions to give an app for each accepted quota bump.
         */
        public int QUOTA_BUMP_ADDITIONAL_SESSION_COUNT =
                DEFAULT_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT;

        /**
         * The rolling window size within which to accept and apply quota bump events.
         */
        public long QUOTA_BUMP_WINDOW_SIZE_MS = DEFAULT_QUOTA_BUMP_WINDOW_SIZE_MS;

        /**
         * The maximum number of quota bumps to accept and apply within the
         * {@link #QUOTA_BUMP_WINDOW_SIZE_MS window}.
         */
        public int QUOTA_BUMP_LIMIT = DEFAULT_QUOTA_BUMP_LIMIT;

        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            switch (key) {
                case KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS:
                case KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS:
                case KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS:
                case KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS:
                case KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS:
                case KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS:
                case KEY_IN_QUOTA_BUFFER_MS:
                case KEY_MAX_EXECUTION_TIME_MS:
                case KEY_WINDOW_SIZE_ACTIVE_MS:
                case KEY_WINDOW_SIZE_WORKING_MS:
                case KEY_WINDOW_SIZE_FREQUENT_MS:
                case KEY_WINDOW_SIZE_RARE_MS:
                case KEY_WINDOW_SIZE_RESTRICTED_MS:
                    updateExecutionPeriodConstantsLocked();
                    break;

                case KEY_RATE_LIMITING_WINDOW_MS:
                case KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW:
                case KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW:
                    updateRateLimitingConstantsLocked();
                    break;

                case KEY_EJ_LIMIT_ACTIVE_MS:
                case KEY_EJ_LIMIT_WORKING_MS:
                case KEY_EJ_LIMIT_FREQUENT_MS:
                case KEY_EJ_LIMIT_RARE_MS:
                case KEY_EJ_LIMIT_RESTRICTED_MS:
                case KEY_EJ_LIMIT_ADDITION_SPECIAL_MS:
                case KEY_EJ_LIMIT_ADDITION_INSTALLER_MS:
                case KEY_EJ_WINDOW_SIZE_MS:
                    updateEJLimitConstantsLocked();
                    break;

                case KEY_QUOTA_BUMP_ADDITIONAL_DURATION_MS:
                case KEY_QUOTA_BUMP_ADDITIONAL_JOB_COUNT:
                case KEY_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT:
                case KEY_QUOTA_BUMP_WINDOW_SIZE_MS:
                case KEY_QUOTA_BUMP_LIMIT:
                    updateQuotaBumpConstantsLocked();
                    break;

                case KEY_MAX_JOB_COUNT_EXEMPTED:
                    MAX_JOB_COUNT_EXEMPTED = properties.getInt(key, DEFAULT_MAX_JOB_COUNT_EXEMPTED);
                    int newExemptedMaxJobCount =
                            Math.max(MIN_BUCKET_JOB_COUNT, MAX_JOB_COUNT_EXEMPTED);
                    if (mMaxBucketJobCounts[EXEMPTED_INDEX] != newExemptedMaxJobCount) {
                        mMaxBucketJobCounts[EXEMPTED_INDEX] = newExemptedMaxJobCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_JOB_COUNT_ACTIVE:
                    MAX_JOB_COUNT_ACTIVE = properties.getInt(key, DEFAULT_MAX_JOB_COUNT_ACTIVE);
                    int newActiveMaxJobCount = Math.max(MIN_BUCKET_JOB_COUNT, MAX_JOB_COUNT_ACTIVE);
                    if (mMaxBucketJobCounts[ACTIVE_INDEX] != newActiveMaxJobCount) {
                        mMaxBucketJobCounts[ACTIVE_INDEX] = newActiveMaxJobCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_JOB_COUNT_WORKING:
                    MAX_JOB_COUNT_WORKING = properties.getInt(key, DEFAULT_MAX_JOB_COUNT_WORKING);
                    int newWorkingMaxJobCount = Math.max(MIN_BUCKET_JOB_COUNT,
                            MAX_JOB_COUNT_WORKING);
                    if (mMaxBucketJobCounts[WORKING_INDEX] != newWorkingMaxJobCount) {
                        mMaxBucketJobCounts[WORKING_INDEX] = newWorkingMaxJobCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_JOB_COUNT_FREQUENT:
                    MAX_JOB_COUNT_FREQUENT = properties.getInt(key, DEFAULT_MAX_JOB_COUNT_FREQUENT);
                    int newFrequentMaxJobCount = Math.max(MIN_BUCKET_JOB_COUNT,
                            MAX_JOB_COUNT_FREQUENT);
                    if (mMaxBucketJobCounts[FREQUENT_INDEX] != newFrequentMaxJobCount) {
                        mMaxBucketJobCounts[FREQUENT_INDEX] = newFrequentMaxJobCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_JOB_COUNT_RARE:
                    MAX_JOB_COUNT_RARE = properties.getInt(key, DEFAULT_MAX_JOB_COUNT_RARE);
                    int newRareMaxJobCount = Math.max(MIN_BUCKET_JOB_COUNT, MAX_JOB_COUNT_RARE);
                    if (mMaxBucketJobCounts[RARE_INDEX] != newRareMaxJobCount) {
                        mMaxBucketJobCounts[RARE_INDEX] = newRareMaxJobCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_JOB_COUNT_RESTRICTED:
                    MAX_JOB_COUNT_RESTRICTED =
                            properties.getInt(key, DEFAULT_MAX_JOB_COUNT_RESTRICTED);
                    int newRestrictedMaxJobCount =
                            Math.max(MIN_BUCKET_JOB_COUNT, MAX_JOB_COUNT_RESTRICTED);
                    if (mMaxBucketJobCounts[RESTRICTED_INDEX] != newRestrictedMaxJobCount) {
                        mMaxBucketJobCounts[RESTRICTED_INDEX] = newRestrictedMaxJobCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_SESSION_COUNT_EXEMPTED:
                    MAX_SESSION_COUNT_EXEMPTED =
                            properties.getInt(key, DEFAULT_MAX_SESSION_COUNT_EXEMPTED);
                    int newExemptedMaxSessionCount =
                            Math.max(MIN_BUCKET_SESSION_COUNT, MAX_SESSION_COUNT_EXEMPTED);
                    if (mMaxBucketSessionCounts[EXEMPTED_INDEX] != newExemptedMaxSessionCount) {
                        mMaxBucketSessionCounts[EXEMPTED_INDEX] = newExemptedMaxSessionCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_SESSION_COUNT_ACTIVE:
                    MAX_SESSION_COUNT_ACTIVE =
                            properties.getInt(key, DEFAULT_MAX_SESSION_COUNT_ACTIVE);
                    int newActiveMaxSessionCount =
                            Math.max(MIN_BUCKET_SESSION_COUNT, MAX_SESSION_COUNT_ACTIVE);
                    if (mMaxBucketSessionCounts[ACTIVE_INDEX] != newActiveMaxSessionCount) {
                        mMaxBucketSessionCounts[ACTIVE_INDEX] = newActiveMaxSessionCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_SESSION_COUNT_WORKING:
                    MAX_SESSION_COUNT_WORKING =
                            properties.getInt(key, DEFAULT_MAX_SESSION_COUNT_WORKING);
                    int newWorkingMaxSessionCount =
                            Math.max(MIN_BUCKET_SESSION_COUNT, MAX_SESSION_COUNT_WORKING);
                    if (mMaxBucketSessionCounts[WORKING_INDEX] != newWorkingMaxSessionCount) {
                        mMaxBucketSessionCounts[WORKING_INDEX] = newWorkingMaxSessionCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_SESSION_COUNT_FREQUENT:
                    MAX_SESSION_COUNT_FREQUENT =
                            properties.getInt(key, DEFAULT_MAX_SESSION_COUNT_FREQUENT);
                    int newFrequentMaxSessionCount =
                            Math.max(MIN_BUCKET_SESSION_COUNT, MAX_SESSION_COUNT_FREQUENT);
                    if (mMaxBucketSessionCounts[FREQUENT_INDEX] != newFrequentMaxSessionCount) {
                        mMaxBucketSessionCounts[FREQUENT_INDEX] = newFrequentMaxSessionCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_SESSION_COUNT_RARE:
                    MAX_SESSION_COUNT_RARE = properties.getInt(key, DEFAULT_MAX_SESSION_COUNT_RARE);
                    int newRareMaxSessionCount =
                            Math.max(MIN_BUCKET_SESSION_COUNT, MAX_SESSION_COUNT_RARE);
                    if (mMaxBucketSessionCounts[RARE_INDEX] != newRareMaxSessionCount) {
                        mMaxBucketSessionCounts[RARE_INDEX] = newRareMaxSessionCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_SESSION_COUNT_RESTRICTED:
                    MAX_SESSION_COUNT_RESTRICTED =
                            properties.getInt(key, DEFAULT_MAX_SESSION_COUNT_RESTRICTED);
                    int newRestrictedMaxSessionCount = Math.max(0, MAX_SESSION_COUNT_RESTRICTED);
                    if (mMaxBucketSessionCounts[RESTRICTED_INDEX] != newRestrictedMaxSessionCount) {
                        mMaxBucketSessionCounts[RESTRICTED_INDEX] = newRestrictedMaxSessionCount;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_TIMING_SESSION_COALESCING_DURATION_MS:
                    TIMING_SESSION_COALESCING_DURATION_MS =
                            properties.getLong(key, DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS);
                    long newSessionCoalescingDurationMs = Math.min(15 * MINUTE_IN_MILLIS,
                            Math.max(0, TIMING_SESSION_COALESCING_DURATION_MS));
                    if (mTimingSessionCoalescingDurationMs != newSessionCoalescingDurationMs) {
                        mTimingSessionCoalescingDurationMs = newSessionCoalescingDurationMs;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MIN_QUOTA_CHECK_DELAY_MS:
                    MIN_QUOTA_CHECK_DELAY_MS =
                            properties.getLong(key, DEFAULT_MIN_QUOTA_CHECK_DELAY_MS);
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    // Limit the delay to the range [0, 15] minutes.
                    mInQuotaAlarmQueue.setMinTimeBetweenAlarmsMs(
                            Math.min(15 * MINUTE_IN_MILLIS, Math.max(0, MIN_QUOTA_CHECK_DELAY_MS)));
                    break;
                case KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS:
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    EJ_TOP_APP_TIME_CHUNK_SIZE_MS =
                            properties.getLong(key, DEFAULT_EJ_TOP_APP_TIME_CHUNK_SIZE_MS);
                    // Limit chunking to be in the range [1 millisecond, 15 minutes] per event.
                    long newChunkSizeMs = Math.min(15 * MINUTE_IN_MILLIS,
                            Math.max(1, EJ_TOP_APP_TIME_CHUNK_SIZE_MS));
                    if (mEJTopAppTimeChunkSizeMs != newChunkSizeMs) {
                        mEJTopAppTimeChunkSizeMs = newChunkSizeMs;
                        if (mEJTopAppTimeChunkSizeMs < mEJRewardTopAppMs) {
                            // Not making chunk sizes and top rewards to be the upper/lower
                            // limits of the other to allow trying different policies. Just log
                            // the discrepancy.
                            Slog.w(TAG, "EJ top app time chunk less than reward: "
                                    + mEJTopAppTimeChunkSizeMs + " vs " + mEJRewardTopAppMs);
                        }
                    }
                    break;
                case KEY_EJ_REWARD_TOP_APP_MS:
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    EJ_REWARD_TOP_APP_MS =
                            properties.getLong(key, DEFAULT_EJ_REWARD_TOP_APP_MS);
                    // Limit top reward to be in the range [10 seconds, 15 minutes] per event.
                    long newTopReward = Math.min(15 * MINUTE_IN_MILLIS,
                            Math.max(10 * SECOND_IN_MILLIS, EJ_REWARD_TOP_APP_MS));
                    if (mEJRewardTopAppMs != newTopReward) {
                        mEJRewardTopAppMs = newTopReward;
                        if (mEJTopAppTimeChunkSizeMs < mEJRewardTopAppMs) {
                            // Not making chunk sizes and top rewards to be the upper/lower
                            // limits of the other to allow trying different policies. Just log
                            // the discrepancy.
                            Slog.w(TAG, "EJ top app time chunk less than reward: "
                                    + mEJTopAppTimeChunkSizeMs + " vs " + mEJRewardTopAppMs);
                        }
                    }
                    break;
                case KEY_EJ_REWARD_INTERACTION_MS:
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    EJ_REWARD_INTERACTION_MS =
                            properties.getLong(key, DEFAULT_EJ_REWARD_INTERACTION_MS);
                    // Limit interaction reward to be in the range [5 seconds, 15 minutes] per
                    // event.
                    mEJRewardInteractionMs = Math.min(15 * MINUTE_IN_MILLIS,
                            Math.max(5 * SECOND_IN_MILLIS, EJ_REWARD_INTERACTION_MS));
                    break;
                case KEY_EJ_REWARD_NOTIFICATION_SEEN_MS:
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    EJ_REWARD_NOTIFICATION_SEEN_MS =
                            properties.getLong(key, DEFAULT_EJ_REWARD_NOTIFICATION_SEEN_MS);
                    // Limit notification seen reward to be in the range [0, 5] minutes per event.
                    mEJRewardNotificationSeenMs = Math.min(5 * MINUTE_IN_MILLIS,
                            Math.max(0, EJ_REWARD_NOTIFICATION_SEEN_MS));
                    break;
                case KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS:
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS =
                            properties.getLong(key, DEFAULT_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS);
                    // Limit grace period to be in the range [0 minutes, 1 hour].
                    mEJGracePeriodTempAllowlistMs = Math.min(HOUR_IN_MILLIS,
                            Math.max(0, EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS));
                    break;
                case KEY_EJ_GRACE_PERIOD_TOP_APP_MS:
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    EJ_GRACE_PERIOD_TOP_APP_MS =
                            properties.getLong(key, DEFAULT_EJ_GRACE_PERIOD_TOP_APP_MS);
                    // Limit grace period to be in the range [0 minutes, 1 hour].
                    mEJGracePeriodTopAppMs = Math.min(HOUR_IN_MILLIS,
                            Math.max(0, EJ_GRACE_PERIOD_TOP_APP_MS));
                    break;
            }
        }

        private void updateExecutionPeriodConstantsLocked() {
            if (mExecutionPeriodConstantsUpdated) {
                return;
            }
            mExecutionPeriodConstantsUpdated = true;

            // Query the values as an atomic set.
            final DeviceConfig.Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS, KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS,
                    KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS, KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS,
                    KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS, KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
                    KEY_IN_QUOTA_BUFFER_MS,
                    KEY_MAX_EXECUTION_TIME_MS,
                    KEY_WINDOW_SIZE_EXEMPTED_MS, KEY_WINDOW_SIZE_ACTIVE_MS,
                    KEY_WINDOW_SIZE_WORKING_MS,
                    KEY_WINDOW_SIZE_FREQUENT_MS, KEY_WINDOW_SIZE_RARE_MS,
                    KEY_WINDOW_SIZE_RESTRICTED_MS);
            ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS =
                    properties.getLong(KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS,
                            DEFAULT_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS);
            ALLOWED_TIME_PER_PERIOD_ACTIVE_MS =
                    properties.getLong(KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS,
                            DEFAULT_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS);
            ALLOWED_TIME_PER_PERIOD_WORKING_MS =
                    properties.getLong(KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
                            DEFAULT_ALLOWED_TIME_PER_PERIOD_WORKING_MS);
            ALLOWED_TIME_PER_PERIOD_FREQUENT_MS =
                    properties.getLong(KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS,
                            DEFAULT_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS);
            ALLOWED_TIME_PER_PERIOD_RARE_MS =
                    properties.getLong(KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS,
                            DEFAULT_ALLOWED_TIME_PER_PERIOD_RARE_MS);
            ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS =
                    properties.getLong(KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
                            DEFAULT_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS);
            IN_QUOTA_BUFFER_MS = properties.getLong(KEY_IN_QUOTA_BUFFER_MS,
                    DEFAULT_IN_QUOTA_BUFFER_MS);
            MAX_EXECUTION_TIME_MS = properties.getLong(KEY_MAX_EXECUTION_TIME_MS,
                    DEFAULT_MAX_EXECUTION_TIME_MS);
            WINDOW_SIZE_EXEMPTED_MS = properties.getLong(KEY_WINDOW_SIZE_EXEMPTED_MS,
                    DEFAULT_WINDOW_SIZE_EXEMPTED_MS);
            WINDOW_SIZE_ACTIVE_MS = properties.getLong(KEY_WINDOW_SIZE_ACTIVE_MS,
                    DEFAULT_WINDOW_SIZE_ACTIVE_MS);
            WINDOW_SIZE_WORKING_MS =
                    properties.getLong(KEY_WINDOW_SIZE_WORKING_MS, DEFAULT_WINDOW_SIZE_WORKING_MS);
            WINDOW_SIZE_FREQUENT_MS =
                    properties.getLong(KEY_WINDOW_SIZE_FREQUENT_MS,
                            DEFAULT_WINDOW_SIZE_FREQUENT_MS);
            WINDOW_SIZE_RARE_MS = properties.getLong(KEY_WINDOW_SIZE_RARE_MS,
                    DEFAULT_WINDOW_SIZE_RARE_MS);
            WINDOW_SIZE_RESTRICTED_MS =
                    properties.getLong(KEY_WINDOW_SIZE_RESTRICTED_MS,
                            DEFAULT_WINDOW_SIZE_RESTRICTED_MS);

            long newMaxExecutionTimeMs = Math.max(MIN_MAX_EXECUTION_TIME_MS,
                    Math.min(MAX_PERIOD_MS, MAX_EXECUTION_TIME_MS));
            if (mMaxExecutionTimeMs != newMaxExecutionTimeMs) {
                mMaxExecutionTimeMs = newMaxExecutionTimeMs;
                mMaxExecutionTimeIntoQuotaMs = mMaxExecutionTimeMs - mQuotaBufferMs;
                mShouldReevaluateConstraints = true;
            }
            long minAllowedTimeMs = Long.MAX_VALUE;
            long newAllowedTimeExemptedMs = Math.min(mMaxExecutionTimeMs,
                    Math.max(MINUTE_IN_MILLIS, ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS));
            minAllowedTimeMs = Math.min(minAllowedTimeMs, newAllowedTimeExemptedMs);
            if (mAllowedTimePerPeriodMs[EXEMPTED_INDEX] != newAllowedTimeExemptedMs) {
                mAllowedTimePerPeriodMs[EXEMPTED_INDEX] = newAllowedTimeExemptedMs;
                mShouldReevaluateConstraints = true;
            }
            long newAllowedTimeActiveMs = Math.min(mMaxExecutionTimeMs,
                    Math.max(MINUTE_IN_MILLIS, ALLOWED_TIME_PER_PERIOD_ACTIVE_MS));
            minAllowedTimeMs = Math.min(minAllowedTimeMs, newAllowedTimeActiveMs);
            if (mAllowedTimePerPeriodMs[ACTIVE_INDEX] != newAllowedTimeActiveMs) {
                mAllowedTimePerPeriodMs[ACTIVE_INDEX] = newAllowedTimeActiveMs;
                mShouldReevaluateConstraints = true;
            }
            long newAllowedTimeWorkingMs = Math.min(mMaxExecutionTimeMs,
                    Math.max(MINUTE_IN_MILLIS, ALLOWED_TIME_PER_PERIOD_WORKING_MS));
            minAllowedTimeMs = Math.min(minAllowedTimeMs, newAllowedTimeWorkingMs);
            if (mAllowedTimePerPeriodMs[WORKING_INDEX] != newAllowedTimeWorkingMs) {
                mAllowedTimePerPeriodMs[WORKING_INDEX] = newAllowedTimeWorkingMs;
                mShouldReevaluateConstraints = true;
            }
            long newAllowedTimeFrequentMs = Math.min(mMaxExecutionTimeMs,
                    Math.max(MINUTE_IN_MILLIS, ALLOWED_TIME_PER_PERIOD_FREQUENT_MS));
            minAllowedTimeMs = Math.min(minAllowedTimeMs, newAllowedTimeFrequentMs);
            if (mAllowedTimePerPeriodMs[FREQUENT_INDEX] != newAllowedTimeFrequentMs) {
                mAllowedTimePerPeriodMs[FREQUENT_INDEX] = newAllowedTimeFrequentMs;
                mShouldReevaluateConstraints = true;
            }
            long newAllowedTimeRareMs = Math.min(mMaxExecutionTimeMs,
                    Math.max(MINUTE_IN_MILLIS, ALLOWED_TIME_PER_PERIOD_RARE_MS));
            minAllowedTimeMs = Math.min(minAllowedTimeMs, newAllowedTimeRareMs);
            if (mAllowedTimePerPeriodMs[RARE_INDEX] != newAllowedTimeRareMs) {
                mAllowedTimePerPeriodMs[RARE_INDEX] = newAllowedTimeRareMs;
                mShouldReevaluateConstraints = true;
            }
            long newAllowedTimeRestrictedMs = Math.min(mMaxExecutionTimeMs,
                    Math.max(MINUTE_IN_MILLIS, ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS));
            minAllowedTimeMs = Math.min(minAllowedTimeMs, newAllowedTimeRestrictedMs);
            if (mAllowedTimePerPeriodMs[RESTRICTED_INDEX] != newAllowedTimeRestrictedMs) {
                mAllowedTimePerPeriodMs[RESTRICTED_INDEX] = newAllowedTimeRestrictedMs;
                mShouldReevaluateConstraints = true;
            }
            // Make sure quota buffer is non-negative, not greater than allowed time per period,
            // and no more than 5 minutes.
            long newQuotaBufferMs = Math.max(0, Math.min(minAllowedTimeMs,
                    Math.min(5 * MINUTE_IN_MILLIS, IN_QUOTA_BUFFER_MS)));
            if (mQuotaBufferMs != newQuotaBufferMs) {
                mQuotaBufferMs = newQuotaBufferMs;
                mMaxExecutionTimeIntoQuotaMs = mMaxExecutionTimeMs - mQuotaBufferMs;
                mShouldReevaluateConstraints = true;
            }
            long newExemptedPeriodMs = Math.max(mAllowedTimePerPeriodMs[EXEMPTED_INDEX],
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_EXEMPTED_MS));
            if (mBucketPeriodsMs[EXEMPTED_INDEX] != newExemptedPeriodMs) {
                mBucketPeriodsMs[EXEMPTED_INDEX] = newExemptedPeriodMs;
                mShouldReevaluateConstraints = true;
            }
            long newActivePeriodMs = Math.max(mAllowedTimePerPeriodMs[ACTIVE_INDEX],
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_ACTIVE_MS));
            if (mBucketPeriodsMs[ACTIVE_INDEX] != newActivePeriodMs) {
                mBucketPeriodsMs[ACTIVE_INDEX] = newActivePeriodMs;
                mShouldReevaluateConstraints = true;
            }
            long newWorkingPeriodMs = Math.max(mAllowedTimePerPeriodMs[WORKING_INDEX],
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_WORKING_MS));
            if (mBucketPeriodsMs[WORKING_INDEX] != newWorkingPeriodMs) {
                mBucketPeriodsMs[WORKING_INDEX] = newWorkingPeriodMs;
                mShouldReevaluateConstraints = true;
            }
            long newFrequentPeriodMs = Math.max(mAllowedTimePerPeriodMs[FREQUENT_INDEX],
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_FREQUENT_MS));
            if (mBucketPeriodsMs[FREQUENT_INDEX] != newFrequentPeriodMs) {
                mBucketPeriodsMs[FREQUENT_INDEX] = newFrequentPeriodMs;
                mShouldReevaluateConstraints = true;
            }
            long newRarePeriodMs = Math.max(mAllowedTimePerPeriodMs[RARE_INDEX],
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_RARE_MS));
            if (mBucketPeriodsMs[RARE_INDEX] != newRarePeriodMs) {
                mBucketPeriodsMs[RARE_INDEX] = newRarePeriodMs;
                mShouldReevaluateConstraints = true;
            }
            // Fit in the range [allowed time (10 mins), 1 week].
            long newRestrictedPeriodMs = Math.max(mAllowedTimePerPeriodMs[RESTRICTED_INDEX],
                    Math.min(7 * 24 * 60 * MINUTE_IN_MILLIS, WINDOW_SIZE_RESTRICTED_MS));
            if (mBucketPeriodsMs[RESTRICTED_INDEX] != newRestrictedPeriodMs) {
                mBucketPeriodsMs[RESTRICTED_INDEX] = newRestrictedPeriodMs;
                mShouldReevaluateConstraints = true;
            }
        }

        private void updateRateLimitingConstantsLocked() {
            if (mRateLimitingConstantsUpdated) {
                return;
            }
            mRateLimitingConstantsUpdated = true;

            // Query the values as an atomic set.
            final DeviceConfig.Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_RATE_LIMITING_WINDOW_MS, KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW,
                    KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW);

            RATE_LIMITING_WINDOW_MS =
                    properties.getLong(KEY_RATE_LIMITING_WINDOW_MS,
                            DEFAULT_RATE_LIMITING_WINDOW_MS);

            MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW =
                    properties.getInt(KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW,
                            DEFAULT_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW);

            MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW =
                    properties.getInt(KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW,
                            DEFAULT_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW);

            long newRateLimitingWindowMs = Math.min(MAX_PERIOD_MS,
                    Math.max(MIN_RATE_LIMITING_WINDOW_MS, RATE_LIMITING_WINDOW_MS));
            if (mRateLimitingWindowMs != newRateLimitingWindowMs) {
                mRateLimitingWindowMs = newRateLimitingWindowMs;
                mShouldReevaluateConstraints = true;
            }
            int newMaxJobCountPerRateLimitingWindow = Math.max(
                    MIN_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW,
                    MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW);
            if (mMaxJobCountPerRateLimitingWindow != newMaxJobCountPerRateLimitingWindow) {
                mMaxJobCountPerRateLimitingWindow = newMaxJobCountPerRateLimitingWindow;
                mShouldReevaluateConstraints = true;
            }
            int newMaxSessionCountPerRateLimitPeriod = Math.max(
                    MIN_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW,
                    MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW);
            if (mMaxSessionCountPerRateLimitingWindow != newMaxSessionCountPerRateLimitPeriod) {
                mMaxSessionCountPerRateLimitingWindow = newMaxSessionCountPerRateLimitPeriod;
                mShouldReevaluateConstraints = true;
            }
        }

        private void updateEJLimitConstantsLocked() {
            if (mEJLimitConstantsUpdated) {
                return;
            }
            mEJLimitConstantsUpdated = true;

            // Query the values as an atomic set.
            final DeviceConfig.Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_EJ_LIMIT_EXEMPTED_MS,
                    KEY_EJ_LIMIT_ACTIVE_MS, KEY_EJ_LIMIT_WORKING_MS,
                    KEY_EJ_LIMIT_FREQUENT_MS, KEY_EJ_LIMIT_RARE_MS,
                    KEY_EJ_LIMIT_RESTRICTED_MS, KEY_EJ_LIMIT_ADDITION_SPECIAL_MS,
                    KEY_EJ_LIMIT_ADDITION_INSTALLER_MS,
                    KEY_EJ_WINDOW_SIZE_MS);
            EJ_LIMIT_EXEMPTED_MS = properties.getLong(
                    KEY_EJ_LIMIT_EXEMPTED_MS, DEFAULT_EJ_LIMIT_EXEMPTED_MS);
            EJ_LIMIT_ACTIVE_MS = properties.getLong(
                    KEY_EJ_LIMIT_ACTIVE_MS, DEFAULT_EJ_LIMIT_ACTIVE_MS);
            EJ_LIMIT_WORKING_MS = properties.getLong(
                    KEY_EJ_LIMIT_WORKING_MS, DEFAULT_EJ_LIMIT_WORKING_MS);
            EJ_LIMIT_FREQUENT_MS = properties.getLong(
                    KEY_EJ_LIMIT_FREQUENT_MS, DEFAULT_EJ_LIMIT_FREQUENT_MS);
            EJ_LIMIT_RARE_MS = properties.getLong(
                    KEY_EJ_LIMIT_RARE_MS, DEFAULT_EJ_LIMIT_RARE_MS);
            EJ_LIMIT_RESTRICTED_MS = properties.getLong(
                    KEY_EJ_LIMIT_RESTRICTED_MS, DEFAULT_EJ_LIMIT_RESTRICTED_MS);
            EJ_LIMIT_ADDITION_INSTALLER_MS = properties.getLong(
                    KEY_EJ_LIMIT_ADDITION_INSTALLER_MS, DEFAULT_EJ_LIMIT_ADDITION_INSTALLER_MS);
            EJ_LIMIT_ADDITION_SPECIAL_MS = properties.getLong(
                    KEY_EJ_LIMIT_ADDITION_SPECIAL_MS, DEFAULT_EJ_LIMIT_ADDITION_SPECIAL_MS);
            EJ_WINDOW_SIZE_MS = properties.getLong(
                    KEY_EJ_WINDOW_SIZE_MS, DEFAULT_EJ_WINDOW_SIZE_MS);

            // The window must be in the range [1 hour, 24 hours].
            long newWindowSizeMs = Math.max(HOUR_IN_MILLIS,
                    Math.min(MAX_PERIOD_MS, EJ_WINDOW_SIZE_MS));
            if (mEJLimitWindowSizeMs != newWindowSizeMs) {
                mEJLimitWindowSizeMs = newWindowSizeMs;
                mShouldReevaluateConstraints = true;
            }
            // The limit must be in the range [15 minutes, window size].
            long newExemptLimitMs = Math.max(15 * MINUTE_IN_MILLIS,
                    Math.min(newWindowSizeMs, EJ_LIMIT_EXEMPTED_MS));
            if (mEJLimitsMs[EXEMPTED_INDEX] != newExemptLimitMs) {
                mEJLimitsMs[EXEMPTED_INDEX] = newExemptLimitMs;
                mShouldReevaluateConstraints = true;
            }
            // The limit must be in the range [15 minutes, exempted limit].
            long newActiveLimitMs = Math.max(15 * MINUTE_IN_MILLIS,
                    Math.min(newExemptLimitMs, EJ_LIMIT_ACTIVE_MS));
            if (mEJLimitsMs[ACTIVE_INDEX] != newActiveLimitMs) {
                mEJLimitsMs[ACTIVE_INDEX] = newActiveLimitMs;
                mShouldReevaluateConstraints = true;
            }
            // The limit must be in the range [15 minutes, active limit].
            long newWorkingLimitMs = Math.max(15 * MINUTE_IN_MILLIS,
                    Math.min(newActiveLimitMs, EJ_LIMIT_WORKING_MS));
            if (mEJLimitsMs[WORKING_INDEX] != newWorkingLimitMs) {
                mEJLimitsMs[WORKING_INDEX] = newWorkingLimitMs;
                mShouldReevaluateConstraints = true;
            }
            // The limit must be in the range [10 minutes, working limit].
            long newFrequentLimitMs = Math.max(10 * MINUTE_IN_MILLIS,
                    Math.min(newWorkingLimitMs, EJ_LIMIT_FREQUENT_MS));
            if (mEJLimitsMs[FREQUENT_INDEX] != newFrequentLimitMs) {
                mEJLimitsMs[FREQUENT_INDEX] = newFrequentLimitMs;
                mShouldReevaluateConstraints = true;
            }
            // The limit must be in the range [10 minutes, frequent limit].
            long newRareLimitMs = Math.max(10 * MINUTE_IN_MILLIS,
                    Math.min(newFrequentLimitMs, EJ_LIMIT_RARE_MS));
            if (mEJLimitsMs[RARE_INDEX] != newRareLimitMs) {
                mEJLimitsMs[RARE_INDEX] = newRareLimitMs;
                mShouldReevaluateConstraints = true;
            }
            // The limit must be in the range [5 minutes, rare limit].
            long newRestrictedLimitMs = Math.max(5 * MINUTE_IN_MILLIS,
                    Math.min(newRareLimitMs, EJ_LIMIT_RESTRICTED_MS));
            if (mEJLimitsMs[RESTRICTED_INDEX] != newRestrictedLimitMs) {
                mEJLimitsMs[RESTRICTED_INDEX] = newRestrictedLimitMs;
                mShouldReevaluateConstraints = true;
            }
            // The additions must be in the range [0 minutes, window size - active limit].
            long newAdditionInstallerMs = Math.max(0,
                    Math.min(newWindowSizeMs - newActiveLimitMs, EJ_LIMIT_ADDITION_INSTALLER_MS));
            if (mEjLimitAdditionInstallerMs != newAdditionInstallerMs) {
                mEjLimitAdditionInstallerMs = newAdditionInstallerMs;
                mShouldReevaluateConstraints = true;
            }
            long newAdditionSpecialMs = Math.max(0,
                    Math.min(newWindowSizeMs - newActiveLimitMs, EJ_LIMIT_ADDITION_SPECIAL_MS));
            if (mEjLimitAdditionSpecialMs != newAdditionSpecialMs) {
                mEjLimitAdditionSpecialMs = newAdditionSpecialMs;
                mShouldReevaluateConstraints = true;
            }
        }

        private void updateQuotaBumpConstantsLocked() {
            if (mQuotaBumpConstantsUpdated) {
                return;
            }
            mQuotaBumpConstantsUpdated = true;

            // Query the values as an atomic set.
            final DeviceConfig.Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_QUOTA_BUMP_ADDITIONAL_DURATION_MS,
                    KEY_QUOTA_BUMP_ADDITIONAL_JOB_COUNT, KEY_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT,
                    KEY_QUOTA_BUMP_WINDOW_SIZE_MS, KEY_QUOTA_BUMP_LIMIT);
            QUOTA_BUMP_ADDITIONAL_DURATION_MS = properties.getLong(
                    KEY_QUOTA_BUMP_ADDITIONAL_DURATION_MS,
                    DEFAULT_QUOTA_BUMP_ADDITIONAL_DURATION_MS);
            QUOTA_BUMP_ADDITIONAL_JOB_COUNT = properties.getInt(
                    KEY_QUOTA_BUMP_ADDITIONAL_JOB_COUNT, DEFAULT_QUOTA_BUMP_ADDITIONAL_JOB_COUNT);
            QUOTA_BUMP_ADDITIONAL_SESSION_COUNT = properties.getInt(
                    KEY_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT,
                    DEFAULT_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT);
            QUOTA_BUMP_WINDOW_SIZE_MS = properties.getLong(
                    KEY_QUOTA_BUMP_WINDOW_SIZE_MS, DEFAULT_QUOTA_BUMP_WINDOW_SIZE_MS);
            QUOTA_BUMP_LIMIT = properties.getInt(
                    KEY_QUOTA_BUMP_LIMIT, DEFAULT_QUOTA_BUMP_LIMIT);

            // The window must be in the range [1 hour, 24 hours].
            long newWindowSizeMs = Math.max(HOUR_IN_MILLIS,
                    Math.min(MAX_PERIOD_MS, QUOTA_BUMP_WINDOW_SIZE_MS));
            if (mQuotaBumpWindowSizeMs != newWindowSizeMs) {
                mQuotaBumpWindowSizeMs = newWindowSizeMs;
                mShouldReevaluateConstraints = true;
            }
            // The limit must be nonnegative.
            int newLimit = Math.max(0, QUOTA_BUMP_LIMIT);
            if (mQuotaBumpLimit != newLimit) {
                mQuotaBumpLimit = newLimit;
                mShouldReevaluateConstraints = true;
            }
            // The job count must be nonnegative.
            int newJobAddition = Math.max(0, QUOTA_BUMP_ADDITIONAL_JOB_COUNT);
            if (mQuotaBumpAdditionalJobCount != newJobAddition) {
                mQuotaBumpAdditionalJobCount = newJobAddition;
                mShouldReevaluateConstraints = true;
            }
            // The session count must be nonnegative.
            int newSessionAddition = Math.max(0, QUOTA_BUMP_ADDITIONAL_SESSION_COUNT);
            if (mQuotaBumpAdditionalSessionCount != newSessionAddition) {
                mQuotaBumpAdditionalSessionCount = newSessionAddition;
                mShouldReevaluateConstraints = true;
            }
            // The additional duration must be in the range [0, 10 minutes].
            long newAdditionalDuration = Math.max(0,
                    Math.min(10 * MINUTE_IN_MILLIS, QUOTA_BUMP_ADDITIONAL_DURATION_MS));
            if (mQuotaBumpAdditionalDurationMs != newAdditionalDuration) {
                mQuotaBumpAdditionalDurationMs = newAdditionalDuration;
                mShouldReevaluateConstraints = true;
            }
        }

        private void dump(IndentingPrintWriter pw) {
            pw.println();
            pw.println("QuotaController:");
            pw.increaseIndent();
            pw.print(KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS, ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS)
                    .println();
            pw.print(KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS, ALLOWED_TIME_PER_PERIOD_ACTIVE_MS)
                    .println();
            pw.print(KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS, ALLOWED_TIME_PER_PERIOD_WORKING_MS)
                    .println();
            pw.print(KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS, ALLOWED_TIME_PER_PERIOD_FREQUENT_MS)
                    .println();
            pw.print(KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS, ALLOWED_TIME_PER_PERIOD_RARE_MS)
                    .println();
            pw.print(KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
                    ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS).println();
            pw.print(KEY_IN_QUOTA_BUFFER_MS, IN_QUOTA_BUFFER_MS).println();
            pw.print(KEY_WINDOW_SIZE_EXEMPTED_MS, WINDOW_SIZE_EXEMPTED_MS).println();
            pw.print(KEY_WINDOW_SIZE_ACTIVE_MS, WINDOW_SIZE_ACTIVE_MS).println();
            pw.print(KEY_WINDOW_SIZE_WORKING_MS, WINDOW_SIZE_WORKING_MS).println();
            pw.print(KEY_WINDOW_SIZE_FREQUENT_MS, WINDOW_SIZE_FREQUENT_MS).println();
            pw.print(KEY_WINDOW_SIZE_RARE_MS, WINDOW_SIZE_RARE_MS).println();
            pw.print(KEY_WINDOW_SIZE_RESTRICTED_MS, WINDOW_SIZE_RESTRICTED_MS).println();
            pw.print(KEY_MAX_EXECUTION_TIME_MS, MAX_EXECUTION_TIME_MS).println();
            pw.print(KEY_MAX_JOB_COUNT_EXEMPTED, MAX_JOB_COUNT_EXEMPTED).println();
            pw.print(KEY_MAX_JOB_COUNT_ACTIVE, MAX_JOB_COUNT_ACTIVE).println();
            pw.print(KEY_MAX_JOB_COUNT_WORKING, MAX_JOB_COUNT_WORKING).println();
            pw.print(KEY_MAX_JOB_COUNT_FREQUENT, MAX_JOB_COUNT_FREQUENT).println();
            pw.print(KEY_MAX_JOB_COUNT_RARE, MAX_JOB_COUNT_RARE).println();
            pw.print(KEY_MAX_JOB_COUNT_RESTRICTED, MAX_JOB_COUNT_RESTRICTED).println();
            pw.print(KEY_RATE_LIMITING_WINDOW_MS, RATE_LIMITING_WINDOW_MS).println();
            pw.print(KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW,
                    MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW).println();
            pw.print(KEY_MAX_SESSION_COUNT_EXEMPTED, MAX_SESSION_COUNT_EXEMPTED).println();
            pw.print(KEY_MAX_SESSION_COUNT_ACTIVE, MAX_SESSION_COUNT_ACTIVE).println();
            pw.print(KEY_MAX_SESSION_COUNT_WORKING, MAX_SESSION_COUNT_WORKING).println();
            pw.print(KEY_MAX_SESSION_COUNT_FREQUENT, MAX_SESSION_COUNT_FREQUENT).println();
            pw.print(KEY_MAX_SESSION_COUNT_RARE, MAX_SESSION_COUNT_RARE).println();
            pw.print(KEY_MAX_SESSION_COUNT_RESTRICTED, MAX_SESSION_COUNT_RESTRICTED).println();
            pw.print(KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW,
                    MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW).println();
            pw.print(KEY_TIMING_SESSION_COALESCING_DURATION_MS,
                    TIMING_SESSION_COALESCING_DURATION_MS).println();
            pw.print(KEY_MIN_QUOTA_CHECK_DELAY_MS, MIN_QUOTA_CHECK_DELAY_MS).println();

            pw.print(KEY_EJ_LIMIT_EXEMPTED_MS, EJ_LIMIT_EXEMPTED_MS).println();
            pw.print(KEY_EJ_LIMIT_ACTIVE_MS, EJ_LIMIT_ACTIVE_MS).println();
            pw.print(KEY_EJ_LIMIT_WORKING_MS, EJ_LIMIT_WORKING_MS).println();
            pw.print(KEY_EJ_LIMIT_FREQUENT_MS, EJ_LIMIT_FREQUENT_MS).println();
            pw.print(KEY_EJ_LIMIT_RARE_MS, EJ_LIMIT_RARE_MS).println();
            pw.print(KEY_EJ_LIMIT_RESTRICTED_MS, EJ_LIMIT_RESTRICTED_MS).println();
            pw.print(KEY_EJ_LIMIT_ADDITION_INSTALLER_MS, EJ_LIMIT_ADDITION_INSTALLER_MS).println();
            pw.print(KEY_EJ_LIMIT_ADDITION_SPECIAL_MS, EJ_LIMIT_ADDITION_SPECIAL_MS).println();
            pw.print(KEY_EJ_WINDOW_SIZE_MS, EJ_WINDOW_SIZE_MS).println();
            pw.print(KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS, EJ_TOP_APP_TIME_CHUNK_SIZE_MS).println();
            pw.print(KEY_EJ_REWARD_TOP_APP_MS, EJ_REWARD_TOP_APP_MS).println();
            pw.print(KEY_EJ_REWARD_INTERACTION_MS, EJ_REWARD_INTERACTION_MS).println();
            pw.print(KEY_EJ_REWARD_NOTIFICATION_SEEN_MS, EJ_REWARD_NOTIFICATION_SEEN_MS).println();
            pw.print(KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS,
                    EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS).println();
            pw.print(KEY_EJ_GRACE_PERIOD_TOP_APP_MS, EJ_GRACE_PERIOD_TOP_APP_MS).println();

            pw.print(KEY_QUOTA_BUMP_ADDITIONAL_DURATION_MS,
                    QUOTA_BUMP_ADDITIONAL_DURATION_MS).println();
            pw.print(KEY_QUOTA_BUMP_ADDITIONAL_JOB_COUNT,
                    QUOTA_BUMP_ADDITIONAL_JOB_COUNT).println();
            pw.print(KEY_QUOTA_BUMP_ADDITIONAL_SESSION_COUNT,
                    QUOTA_BUMP_ADDITIONAL_SESSION_COUNT).println();
            pw.print(KEY_QUOTA_BUMP_WINDOW_SIZE_MS, QUOTA_BUMP_WINDOW_SIZE_MS).println();
            pw.print(KEY_QUOTA_BUMP_LIMIT, QUOTA_BUMP_LIMIT).println();

            pw.decreaseIndent();
        }

        private void dump(ProtoOutputStream proto) {
            final long qcToken = proto.start(ConstantsProto.QUOTA_CONTROLLER);
            proto.write(ConstantsProto.QuotaController.IN_QUOTA_BUFFER_MS, IN_QUOTA_BUFFER_MS);
            proto.write(ConstantsProto.QuotaController.ACTIVE_WINDOW_SIZE_MS,
                    WINDOW_SIZE_ACTIVE_MS);
            proto.write(ConstantsProto.QuotaController.WORKING_WINDOW_SIZE_MS,
                    WINDOW_SIZE_WORKING_MS);
            proto.write(ConstantsProto.QuotaController.FREQUENT_WINDOW_SIZE_MS,
                    WINDOW_SIZE_FREQUENT_MS);
            proto.write(ConstantsProto.QuotaController.RARE_WINDOW_SIZE_MS, WINDOW_SIZE_RARE_MS);
            proto.write(ConstantsProto.QuotaController.RESTRICTED_WINDOW_SIZE_MS,
                    WINDOW_SIZE_RESTRICTED_MS);
            proto.write(ConstantsProto.QuotaController.MAX_EXECUTION_TIME_MS,
                    MAX_EXECUTION_TIME_MS);
            proto.write(ConstantsProto.QuotaController.MAX_JOB_COUNT_ACTIVE, MAX_JOB_COUNT_ACTIVE);
            proto.write(ConstantsProto.QuotaController.MAX_JOB_COUNT_WORKING,
                    MAX_JOB_COUNT_WORKING);
            proto.write(ConstantsProto.QuotaController.MAX_JOB_COUNT_FREQUENT,
                    MAX_JOB_COUNT_FREQUENT);
            proto.write(ConstantsProto.QuotaController.MAX_JOB_COUNT_RARE, MAX_JOB_COUNT_RARE);
            proto.write(ConstantsProto.QuotaController.MAX_JOB_COUNT_RESTRICTED,
                    MAX_JOB_COUNT_RESTRICTED);
            proto.write(ConstantsProto.QuotaController.RATE_LIMITING_WINDOW_MS,
                    RATE_LIMITING_WINDOW_MS);
            proto.write(ConstantsProto.QuotaController.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW,
                    MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW);
            proto.write(ConstantsProto.QuotaController.MAX_SESSION_COUNT_ACTIVE,
                    MAX_SESSION_COUNT_ACTIVE);
            proto.write(ConstantsProto.QuotaController.MAX_SESSION_COUNT_WORKING,
                    MAX_SESSION_COUNT_WORKING);
            proto.write(ConstantsProto.QuotaController.MAX_SESSION_COUNT_FREQUENT,
                    MAX_SESSION_COUNT_FREQUENT);
            proto.write(ConstantsProto.QuotaController.MAX_SESSION_COUNT_RARE,
                    MAX_SESSION_COUNT_RARE);
            proto.write(ConstantsProto.QuotaController.MAX_SESSION_COUNT_RESTRICTED,
                    MAX_SESSION_COUNT_RESTRICTED);
            proto.write(ConstantsProto.QuotaController.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW,
                    MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW);
            proto.write(ConstantsProto.QuotaController.TIMING_SESSION_COALESCING_DURATION_MS,
                    TIMING_SESSION_COALESCING_DURATION_MS);
            proto.write(ConstantsProto.QuotaController.MIN_QUOTA_CHECK_DELAY_MS,
                    MIN_QUOTA_CHECK_DELAY_MS);

            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_LIMIT_ACTIVE_MS,
                    EJ_LIMIT_ACTIVE_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_LIMIT_WORKING_MS,
                    EJ_LIMIT_WORKING_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_LIMIT_FREQUENT_MS,
                    EJ_LIMIT_FREQUENT_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_LIMIT_RARE_MS,
                    EJ_LIMIT_RARE_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_LIMIT_RESTRICTED_MS,
                    EJ_LIMIT_RESTRICTED_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_WINDOW_SIZE_MS,
                    EJ_WINDOW_SIZE_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_TOP_APP_TIME_CHUNK_SIZE_MS,
                    EJ_TOP_APP_TIME_CHUNK_SIZE_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_REWARD_TOP_APP_MS,
                    EJ_REWARD_TOP_APP_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_REWARD_INTERACTION_MS,
                    EJ_REWARD_INTERACTION_MS);
            proto.write(ConstantsProto.QuotaController.EXPEDITED_JOB_REWARD_NOTIFICATION_SEEN_MS,
                    EJ_REWARD_NOTIFICATION_SEEN_MS);

            proto.end(qcToken);
        }
    }

    //////////////////////// TESTING HELPERS /////////////////////////////

    @VisibleForTesting
    long[] getAllowedTimePerPeriodMs() {
        return mAllowedTimePerPeriodMs;
    }

    @VisibleForTesting
    @NonNull
    int[] getBucketMaxJobCounts() {
        return mMaxBucketJobCounts;
    }

    @VisibleForTesting
    @NonNull
    int[] getBucketMaxSessionCounts() {
        return mMaxBucketSessionCounts;
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
    long getEJGracePeriodTempAllowlistMs() {
        return mEJGracePeriodTempAllowlistMs;
    }

    @VisibleForTesting
    long getEJGracePeriodTopAppMs() {
        return mEJGracePeriodTopAppMs;
    }

    @VisibleForTesting
    @NonNull
    long[] getEJLimitsMs() {
        return mEJLimitsMs;
    }

    @VisibleForTesting
    long getEjLimitAdditionInstallerMs() {
        return mEjLimitAdditionInstallerMs;
    }

    @VisibleForTesting
    long getEjLimitAdditionSpecialMs() {
        return mEjLimitAdditionSpecialMs;
    }

    @VisibleForTesting
    @NonNull
    long getEJLimitWindowSizeMs() {
        return mEJLimitWindowSizeMs;
    }

    @VisibleForTesting
    @NonNull
    long getEJRewardInteractionMs() {
        return mEJRewardInteractionMs;
    }

    @VisibleForTesting
    @NonNull
    long getEJRewardNotificationSeenMs() {
        return mEJRewardNotificationSeenMs;
    }

    @VisibleForTesting
    @NonNull
    long getEJRewardTopAppMs() {
        return mEJRewardTopAppMs;
    }

    @VisibleForTesting
    @Nullable
    List<TimedEvent> getEJTimingSessions(int userId, String packageName) {
        return mEJTimingSessions.get(userId, packageName);
    }

    @VisibleForTesting
    @NonNull
    long getEJTopAppTimeChunkSizeMs() {
        return mEJTopAppTimeChunkSizeMs;
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
    int getMaxJobCountPerRateLimitingWindow() {
        return mMaxJobCountPerRateLimitingWindow;
    }

    @VisibleForTesting
    int getMaxSessionCountPerRateLimitingWindow() {
        return mMaxSessionCountPerRateLimitingWindow;
    }

    @VisibleForTesting
    long getMinQuotaCheckDelayMs() {
        return mInQuotaAlarmQueue.getMinTimeBetweenAlarmsMs();
    }

    @VisibleForTesting
    long getRateLimitingWindowMs() {
        return mRateLimitingWindowMs;
    }

    @VisibleForTesting
    long getTimingSessionCoalescingDurationMs() {
        return mTimingSessionCoalescingDurationMs;
    }

    @VisibleForTesting
    @Nullable
    List<TimedEvent> getTimingSessions(int userId, String packageName) {
        return mTimingEvents.get(userId, packageName);
    }

    @VisibleForTesting
    @NonNull
    QcConstants getQcConstants() {
        return mQcConstants;
    }

    @VisibleForTesting
    long getQuotaBumpAdditionDurationMs() {
        return mQuotaBumpAdditionalDurationMs;
    }

    @VisibleForTesting
    int getQuotaBumpAdditionJobCount() {
        return mQuotaBumpAdditionalJobCount;
    }

    @VisibleForTesting
    int getQuotaBumpAdditionSessionCount() {
        return mQuotaBumpAdditionalSessionCount;
    }

    @VisibleForTesting
    int getQuotaBumpLimit() {
        return mQuotaBumpLimit;
    }

    @VisibleForTesting
    long getQuotaBumpWindowSizeMs() {
        return mQuotaBumpWindowSizeMs;
    }

    //////////////////////////// DATA DUMP //////////////////////////////

    @NeverCompile // Avoid size overhead of debugging code.
    @Override
    public void dumpControllerStateLocked(final IndentingPrintWriter pw,
            final Predicate<JobStatus> predicate) {
        pw.println("Current elapsed time: " + sElapsedRealtimeClock.millis());
        pw.println();

        pw.print("Foreground UIDs: ");
        pw.println(mForegroundUids.toString());
        pw.println();

        pw.print("Cached top apps: ");
        pw.println(mTopAppCache.toString());
        pw.print("Cached top app grace period: ");
        pw.println(mTopAppGraceCache.toString());

        pw.print("Cached temp allowlist: ");
        pw.println(mTempAllowlistCache.toString());
        pw.print("Cached temp allowlist grace period: ");
        pw.println(mTempAllowlistGraceCache.toString());
        pw.println();

        pw.println("Special apps:");
        pw.increaseIndent();
        pw.print("System installers={");
        for (int si = 0; si < mSystemInstallers.size(); ++si) {
            if (si > 0) {
                pw.print(", ");
            }
            pw.print(mSystemInstallers.keyAt(si));
            pw.print("->");
            pw.print(mSystemInstallers.get(si));
        }
        pw.println("}");
        pw.decreaseIndent();

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
                pw.print(JobStatus.bucketName(js.getEffectiveStandbyBucket()));
                pw.print(", ");
                if (js.shouldTreatAsExpeditedJob()) {
                    pw.print("within EJ quota");
                } else if (js.startedAsExpeditedJob) {
                    pw.print("out of EJ quota");
                } else if (js.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA)) {
                    pw.print("within regular quota");
                } else {
                    pw.print("not within quota");
                }
                pw.print(", ");
                if (js.shouldTreatAsExpeditedJob()) {
                    pw.print(getRemainingEJExecutionTimeLocked(
                            js.getSourceUserId(), js.getSourcePackageName()));
                    pw.print("ms remaining in EJ quota");
                } else if (js.startedAsExpeditedJob) {
                    pw.print("should be stopped after min execution time");
                } else {
                    pw.print(getRemainingExecutionTimeLocked(js));
                    pw.print("ms remaining in quota");
                }
                pw.println();
                pw.decreaseIndent();
            }
        });

        pw.println();
        for (int u = 0; u < mPkgTimers.numMaps(); ++u) {
            final int userId = mPkgTimers.keyAt(u);
            for (int p = 0; p < mPkgTimers.numElementsForKey(userId); ++p) {
                final String pkgName = mPkgTimers.keyAt(u, p);
                mPkgTimers.valueAt(u, p).dump(pw, predicate);
                pw.println();
                List<TimedEvent> events = mTimingEvents.get(userId, pkgName);
                if (events != null) {
                    pw.increaseIndent();
                    pw.println("Saved events:");
                    pw.increaseIndent();
                    for (int j = events.size() - 1; j >= 0; j--) {
                        TimedEvent event = events.get(j);
                        event.dump(pw);
                    }
                    pw.decreaseIndent();
                    pw.decreaseIndent();
                    pw.println();
                }
            }
        }

        pw.println();
        for (int u = 0; u < mEJPkgTimers.numMaps(); ++u) {
            final int userId = mEJPkgTimers.keyAt(u);
            for (int p = 0; p < mEJPkgTimers.numElementsForKey(userId); ++p) {
                final String pkgName = mEJPkgTimers.keyAt(u, p);
                mEJPkgTimers.valueAt(u, p).dump(pw, predicate);
                pw.println();
                List<TimedEvent> sessions = mEJTimingSessions.get(userId, pkgName);
                if (sessions != null) {
                    pw.increaseIndent();
                    pw.println("Saved sessions:");
                    pw.increaseIndent();
                    for (int j = sessions.size() - 1; j >= 0; j--) {
                        TimedEvent session = sessions.get(j);
                        session.dump(pw);
                    }
                    pw.decreaseIndent();
                    pw.decreaseIndent();
                    pw.println();
                }
            }
        }

        pw.println();
        mTopAppTrackers.forEach((timer) -> timer.dump(pw));

        pw.println();
        pw.println("Cached execution stats:");
        pw.increaseIndent();
        for (int u = 0; u < mExecutionStatsCache.numMaps(); ++u) {
            final int userId = mExecutionStatsCache.keyAt(u);
            for (int p = 0; p < mExecutionStatsCache.numElementsForKey(userId); ++p) {
                final String pkgName = mExecutionStatsCache.keyAt(u, p);
                ExecutionStats[] stats = mExecutionStatsCache.valueAt(u, p);

                pw.println(packageToString(userId, pkgName));
                pw.increaseIndent();
                for (int i = 0; i < stats.length; ++i) {
                    ExecutionStats executionStats = stats[i];
                    if (executionStats != null) {
                        pw.print(JobStatus.bucketName(i));
                        pw.print(": ");
                        pw.println(executionStats);
                    }
                }
                pw.decreaseIndent();
            }
        }
        pw.decreaseIndent();

        pw.println();
        pw.println("EJ debits:");
        pw.increaseIndent();
        for (int u = 0; u < mEJStats.numMaps(); ++u) {
            final int userId = mEJStats.keyAt(u);
            for (int p = 0; p < mEJStats.numElementsForKey(userId); ++p) {
                final String pkgName = mEJStats.keyAt(u, p);
                ShrinkableDebits debits = mEJStats.valueAt(u, p);

                pw.print(packageToString(userId, pkgName));
                pw.print(": ");
                debits.dumpLocked(pw);
            }
        }
        pw.decreaseIndent();

        pw.println();
        mInQuotaAlarmQueue.dump(pw);
        pw.decreaseIndent();
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.QUOTA);

        proto.write(StateControllerProto.QuotaController.IS_CHARGING,
                mService.isBatteryCharging());
        proto.write(StateControllerProto.QuotaController.ELAPSED_REALTIME,
                sElapsedRealtimeClock.millis());

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
                final long jsToken = proto.start(StateControllerProto.QuotaController.TRACKED_JOBS);
                js.writeToShortProto(proto, StateControllerProto.QuotaController.TrackedJob.INFO);
                proto.write(StateControllerProto.QuotaController.TrackedJob.SOURCE_UID,
                        js.getSourceUid());
                proto.write(
                        StateControllerProto.QuotaController.TrackedJob.EFFECTIVE_STANDBY_BUCKET,
                        js.getEffectiveStandbyBucket());
                proto.write(StateControllerProto.QuotaController.TrackedJob.IS_TOP_STARTED_JOB,
                        mTopStartedJobs.contains(js));
                proto.write(StateControllerProto.QuotaController.TrackedJob.HAS_QUOTA,
                        js.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
                proto.write(StateControllerProto.QuotaController.TrackedJob.REMAINING_QUOTA_MS,
                        getRemainingExecutionTimeLocked(js));
                proto.write(
                        StateControllerProto.QuotaController.TrackedJob.IS_REQUESTED_FOREGROUND_JOB,
                        js.isRequestedExpeditedJob());
                proto.write(
                        StateControllerProto.QuotaController.TrackedJob.IS_WITHIN_FG_JOB_QUOTA,
                        js.isExpeditedQuotaApproved());
                proto.end(jsToken);
            }
        });

        for (int u = 0; u < mPkgTimers.numMaps(); ++u) {
            final int userId = mPkgTimers.keyAt(u);
            for (int p = 0; p < mPkgTimers.numElementsForKey(userId); ++p) {
                final String pkgName = mPkgTimers.keyAt(u, p);
                final long psToken = proto.start(
                        StateControllerProto.QuotaController.PACKAGE_STATS);

                mPkgTimers.valueAt(u, p).dump(proto,
                        StateControllerProto.QuotaController.PackageStats.TIMER, predicate);
                final Timer ejTimer = mEJPkgTimers.get(userId, pkgName);
                if (ejTimer != null) {
                    ejTimer.dump(proto,
                            StateControllerProto.QuotaController.PackageStats.FG_JOB_TIMER,
                            predicate);
                }

                List<TimedEvent> events = mTimingEvents.get(userId, pkgName);
                if (events != null) {
                    for (int j = events.size() - 1; j >= 0; j--) {
                        TimedEvent event = events.get(j);
                        if (!(event instanceof TimingSession)) {
                            continue;
                        }
                        TimingSession session = (TimingSession) event;
                        session.dump(proto,
                                StateControllerProto.QuotaController.PackageStats.SAVED_SESSIONS);
                    }
                }

                ExecutionStats[] stats = mExecutionStatsCache.get(userId, pkgName);
                if (stats != null) {
                    for (int bucketIndex = 0; bucketIndex < stats.length; ++bucketIndex) {
                        ExecutionStats es = stats[bucketIndex];
                        if (es == null) {
                            continue;
                        }
                        final long esToken = proto.start(
                                StateControllerProto.QuotaController.PackageStats.EXECUTION_STATS);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.STANDBY_BUCKET,
                                bucketIndex);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.EXPIRATION_TIME_ELAPSED,
                                es.expirationTimeElapsed);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.WINDOW_SIZE_MS,
                                es.windowSizeMs);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.JOB_COUNT_LIMIT,
                                es.jobCountLimit);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.SESSION_COUNT_LIMIT,
                                es.sessionCountLimit);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.EXECUTION_TIME_IN_WINDOW_MS,
                                es.executionTimeInWindowMs);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.BG_JOB_COUNT_IN_WINDOW,
                                es.bgJobCountInWindow);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.EXECUTION_TIME_IN_MAX_PERIOD_MS,
                                es.executionTimeInMaxPeriodMs);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.BG_JOB_COUNT_IN_MAX_PERIOD,
                                es.bgJobCountInMaxPeriod);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.SESSION_COUNT_IN_WINDOW,
                                es.sessionCountInWindow);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.IN_QUOTA_TIME_ELAPSED,
                                es.inQuotaTimeElapsed);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.JOB_COUNT_EXPIRATION_TIME_ELAPSED,
                                es.jobRateLimitExpirationTimeElapsed);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.JOB_COUNT_IN_RATE_LIMITING_WINDOW,
                                es.jobCountInRateLimitingWindow);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.SESSION_COUNT_EXPIRATION_TIME_ELAPSED,
                                es.sessionRateLimitExpirationTimeElapsed);
                        proto.write(
                                StateControllerProto.QuotaController.ExecutionStats.SESSION_COUNT_IN_RATE_LIMITING_WINDOW,
                                es.sessionCountInRateLimitingWindow);
                        proto.end(esToken);
                    }
                }

                proto.end(psToken);
            }
        }

        proto.end(mToken);
        proto.end(token);
    }

    @Override
    public void dumpConstants(IndentingPrintWriter pw) {
        mQcConstants.dump(pw);
    }

    @Override
    public void dumpConstants(ProtoOutputStream proto) {
        mQcConstants.dump(proto);
    }
}
