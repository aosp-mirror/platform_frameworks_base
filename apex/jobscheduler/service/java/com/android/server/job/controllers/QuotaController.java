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
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.NEVER_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.UsageEventListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManagerInternal;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
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
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.LocalServices;
import com.android.server.PowerAllowlistInternal;
import com.android.server.job.ConstantsProto;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
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

        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
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
        public int jobCountLimit;
        public int sessionCountLimit;

        /** The total amount of time the app ran in its respective bucket window size. */
        public long executionTimeInWindowMs;
        public int bgJobCountInWindow;

        /** The total amount of time the app ran in the last {@link #MAX_PERIOD_MS}. */
        public long executionTimeInMaxPeriodMs;
        public int bgJobCountInMaxPeriod;

        /**
         * The number of {@link TimingSession}s within the bucket window size. This will include
         * sessions that started before the window as long as they end within the window.
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
         * The number of {@link TimingSession}s that ran in at least the last
         * {@link #mRateLimitingWindowMs}. It may contain a few stale entries since cleanup won't
         * happen exactly every {@link #mRateLimitingWindowMs}. This should only be considered
         * valid before elapsed realtime has reached {@link #sessionRateLimitExpirationTimeElapsed}.
         */
        public int sessionCountInRateLimitingWindow;

        @Override
        public String toString() {
            return "expirationTime=" + expirationTimeElapsed + ", "
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
    private final SparseArrayMap<String, List<TimingSession>> mTimingSessions =
            new SparseArrayMap<>();

    /**
     * List of all expedited job timing sessions for a package-userId combo, in chronological order.
     */
    private final SparseArrayMap<String, List<TimingSession>> mEJTimingSessions =
            new SparseArrayMap<>();

    /**
     * Listener to track and manage when each package comes back within quota.
     */
    @GuardedBy("mLock")
    private final InQuotaAlarmListener mInQuotaAlarmListener = new InQuotaAlarmListener();

    /** Cached calculation results for each app, with the standby buckets as the array indices. */
    private final SparseArrayMap<String, ExecutionStats[]> mExecutionStatsCache =
            new SparseArrayMap<>();

    private final SparseArrayMap<String, ShrinkableDebits> mEJStats = new SparseArrayMap<>();

    private final SparseArrayMap<String, TopAppTimer> mTopAppTrackers = new SparseArrayMap<>();

    /** List of UIDs currently in the foreground. */
    private final SparseBooleanArray mForegroundUids = new SparseBooleanArray();

    /** Cached mapping of UIDs (for all users) to a list of packages in the UID. */
    private final SparseSetArray<String> mUidToPackageCache = new SparseSetArray<>();

    /**
     * List of jobs that started while the UID was in the TOP state. There will be no more than
     * 16 ({@link JobSchedulerService#MAX_JOB_CONTEXTS_COUNT}) running at once, so an ArraySet is
     * fine.
     */
    private final ArraySet<JobStatus> mTopStartedJobs = new ArraySet<>();

    /** Current set of UIDs on the temp allowlist. */
    private final SparseBooleanArray mTempAllowlistCache = new SparseBooleanArray();

    /**
     * Mapping of app IDs to the when their temp allowlist grace period ends (in the elapsed
     * realtime timebase).
     */
    private final SparseLongArray mTempAllowlistGraceCache = new SparseLongArray();

    private final ActivityManagerInternal mActivityManagerInternal;
    private final AlarmManager mAlarmManager;
    private final ChargingTracker mChargeTracker;
    private final QcHandler mHandler;
    private final QcConstants mQcConstants;

    private final BackgroundJobsController mBackgroundJobsController;
    private final ConnectivityController mConnectivityController;

    /** How much time each app will have to run jobs within their standby bucket window. */
    private long mAllowedTimePerPeriodMs = QcConstants.DEFAULT_ALLOWED_TIME_PER_PERIOD_MS;

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
     * {@link #mAllowedTimePerPeriodMs} - {@link #mQuotaBufferMs}. This can be used to determine
     * when an app will have enough quota to transition from out-of-quota to in-quota.
     */
    private long mAllowedTimeIntoQuotaMs = mAllowedTimePerPeriodMs - mQuotaBufferMs;

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
     * The maximum number of {@link TimingSession}s that can run within the past {@link
     * #mRateLimitingWindowMs}.
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

    private final IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
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

    private final BroadcastReceiver mPackageAddedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return;
            }
            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            synchronized (mLock) {
                mUidToPackageCache.remove(uid);
            }
        }
    };

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
            QcConstants.DEFAULT_WINDOW_SIZE_RESTRICTED_MS
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
            QcConstants.DEFAULT_MAX_JOB_COUNT_RESTRICTED
    };

    /**
     * The maximum number of {@link TimingSession}s based on its standby bucket. For each max value
     * count in the array, the app will not be allowed to have more than that many number of
     * {@link TimingSession}s within the latest time interval of its rolling window size.
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
    };

    /**
     * Treat two distinct {@link TimingSession}s as the same if they start and end within this
     * amount of time of each other.
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
            QcConstants.DEFAULT_EJ_LIMIT_RESTRICTED_MS
    };

    private long mEjLimitSpecialAdditionMs = QcConstants.DEFAULT_EJ_LIMIT_SPECIAL_ADDITION_MS;

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

    private long mEJTempAllowlistGracePeriodMs =
            QcConstants.DEFAULT_EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS;

    /** The package verifier app. */
    @Nullable
    private String mPackageVerifier;

    /** An app has reached its quota. The message should contain a {@link Package} object. */
    @VisibleForTesting
    static final int MSG_REACHED_QUOTA = 0;
    /** Drop any old timing sessions. */
    private static final int MSG_CLEAN_UP_SESSIONS = 1;
    /** Check if a package is now within its quota. */
    private static final int MSG_CHECK_PACKAGE = 2;
    /** Process state for a UID has changed. */
    private static final int MSG_UID_PROCESS_STATE_CHANGED = 3;
    /**
     * An app has reached its expedited job quota. The message should contain a {@link Package}
     * object.
     */
    @VisibleForTesting
    static final int MSG_REACHED_EJ_QUOTA = 4;
    /**
     * Process a new {@link UsageEvents.Event}. The event will be the message's object and the
     * userId will the first arg.
     */
    private static final int MSG_PROCESS_USAGE_EVENT = 5;
    /** A UID's free quota grace period has ended. */
    @VisibleForTesting
    static final int MSG_END_GRACE_PERIOD = 6;

    public QuotaController(@NonNull JobSchedulerService service,
            @NonNull BackgroundJobsController backgroundJobsController,
            @NonNull ConnectivityController connectivityController) {
        super(service);
        mHandler = new QcHandler(mContext.getMainLooper());
        mChargeTracker = new ChargingTracker();
        mChargeTracker.startTracking();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mQcConstants = new QcConstants();
        mBackgroundJobsController = backgroundJobsController;
        mConnectivityController = connectivityController;

        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        mContext.registerReceiverAsUser(mPackageAddedReceiver, UserHandle.ALL, filter, null, null);

        // Set up the app standby bucketing tracker
        AppStandbyInternal appStandby = LocalServices.getService(AppStandbyInternal.class);
        appStandby.addListener(new StandbyTracker());

        UsageStatsManagerInternal usmi = LocalServices.getService(UsageStatsManagerInternal.class);
        usmi.registerListener(new UsageEventTracker());

        PowerAllowlistInternal pai = LocalServices.getService(PowerAllowlistInternal.class);
        pai.registerTempAllowlistChangeListener(new TempAllowlistTracker());

        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE,
                    ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }
    }

    @Override
    public void onSystemServicesReady() {
        String[] pkgNames = LocalServices.getService(PackageManagerInternal.class)
                .getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_VERIFIER, UserHandle.USER_SYSTEM);
        synchronized (mLock) {
            mPackageVerifier = ArrayUtils.firstOrNull(pkgNames);
        }
    }

    @Override
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
        setConstraintSatisfied(jobStatus, nowElapsed, isWithinQuota);
        final boolean outOfEJQuota;
        if (jobStatus.isRequestedExpeditedJob()) {
            final boolean isWithinEJQuota = isWithinEJQuotaLocked(jobStatus);
            setExpeditedConstraintSatisfied(jobStatus, nowElapsed, isWithinEJQuota);
            outOfEJQuota = !isWithinEJQuota;
        } else {
            outOfEJQuota = false;
        }
        if (!isWithinQuota || outOfEJQuota) {
            maybeScheduleStartAlarmLocked(userId, pkgName, jobStatus.getEffectiveStandbyBucket());
        }
    }

    @Override
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        if (DEBUG) {
            Slog.d(TAG, "Prepping for " + jobStatus.toShortString());
        }

        final int uid = jobStatus.getSourceUid();
        if (mActivityManagerInternal.getUidProcessState(uid) <= ActivityManager.PROCESS_STATE_TOP) {
            if (DEBUG) {
                Slog.d(TAG, jobStatus.toShortString() + " is top started job");
            }
            mTopStartedJobs.add(jobStatus);
            // Top jobs won't count towards quota so there's no need to involve the Timer.
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
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (jobStatus.clearTrackingController(JobStatus.TRACKING_QUOTA)) {
            Timer timer = mPkgTimers.get(jobStatus.getSourceUserId(),
                    jobStatus.getSourcePackageName());
            if (timer != null) {
                timer.stopTrackingJob(jobStatus);
            }
            if (jobStatus.isRequestedExpeditedJob()) {
                timer = mEJPkgTimers.get(jobStatus.getSourceUserId(),
                        jobStatus.getSourcePackageName());
                if (timer != null) {
                    timer.stopTrackingJob(jobStatus);
                }
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
    public void onAppRemovedLocked(String packageName, int uid) {
        if (packageName == null) {
            Slog.wtf(TAG, "Told app removed but given null package name.");
            return;
        }
        clearAppStatsLocked(UserHandle.getUserId(uid), packageName);
        mForegroundUids.delete(uid);
        mUidToPackageCache.remove(uid);
        mTempAllowlistCache.delete(uid);
        mTempAllowlistGraceCache.delete(uid);
    }

    @Override
    public void onUserRemovedLocked(int userId) {
        mTrackedJobs.delete(userId);
        mPkgTimers.delete(userId);
        mEJPkgTimers.delete(userId);
        mTimingSessions.delete(userId);
        mEJTimingSessions.delete(userId);
        mInQuotaAlarmListener.removeAlarmsLocked(userId);
        mExecutionStatsCache.delete(userId);
        mEJStats.delete(userId);
        mUidToPackageCache.clear();
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
        mTimingSessions.delete(userId, packageName);
        mEJTimingSessions.delete(userId, packageName);
        mInQuotaAlarmListener.removeAlarmLocked(userId, packageName);
        mExecutionStatsCache.delete(userId, packageName);
        mEJStats.delete(userId, packageName);
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
    public long getMaxJobExecutionTimeMsLocked(@NonNull final JobStatus jobStatus) {
        // Need to look at current proc state as well in the case where the job hasn't started yet.
        final boolean isTop = mActivityManagerInternal
                .getUidProcessState(jobStatus.getSourceUid()) <= ActivityManager.PROCESS_STATE_TOP;

        if (!jobStatus.shouldTreatAsExpeditedJob()) {
            // If quota is currently "free", then the job can run for the full amount of time.
            if (mChargeTracker.isCharging()
                    || isTop
                    || isTopStartedJobLocked(jobStatus)
                    || isUidInForeground(jobStatus.getSourceUid())) {
                return mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;
            }
            return getTimeUntilQuotaConsumedLocked(
                    jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        }

        // Expedited job.
        if (mChargeTracker.isCharging()) {
            return mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;
        }
        if (isTop || isTopStartedJobLocked(jobStatus)) {
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

    /** @return true if the job is within expedited job quota. */
    public boolean isWithinEJQuotaLocked(@NonNull final JobStatus jobStatus) {
        if (isQuotaFree(jobStatus.getEffectiveStandbyBucket())) {
            return true;
        }
        // A job is within quota if one of the following is true:
        //   1. it's already running (already executing expedited jobs should be allowed to finish)
        //   2. the app is currently in the foreground
        //   3. the app overall is within its quota
        if (isTopStartedJobLocked(jobStatus) || isUidInForeground(jobStatus.getSourceUid())) {
            return true;
        }
        Timer ejTimer = mEJPkgTimers.get(jobStatus.getSourceUserId(),
                jobStatus.getSourcePackageName());
        // Any already executing expedited jbos should be allowed to finish.
        if (ejTimer != null && ejTimer.isRunning(jobStatus)) {
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
    boolean isWithinQuotaLocked(@NonNull final JobStatus jobStatus) {
        final int standbyBucket = jobStatus.getEffectiveStandbyBucket();
        // A job is within quota if one of the following is true:
        //   1. it was started while the app was in the TOP state
        //   2. the app is currently in the foreground
        //   3. the app overall is within its quota
        return isTopStartedJobLocked(jobStatus)
                || isUidInForeground(jobStatus.getSourceUid())
                || isWithinQuotaLocked(
                jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(), standbyBucket);
    }

    private boolean isQuotaFree(final int standbyBucket) {
        // Quota constraint is not enforced while charging.
        if (mChargeTracker.isCharging()) {
            // Restricted jobs require additional constraints when charging, so don't immediately
            // mark quota as free when charging.
            return standbyBucket != RESTRICTED_INDEX;
        }
        return false;
    }

    @VisibleForTesting
    boolean isWithinQuotaLocked(final int userId, @NonNull final String packageName,
            final int standbyBucket) {
        if (standbyBucket == NEVER_INDEX) return false;

        if (isQuotaFree(standbyBucket)) return true;

        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        return getRemainingExecutionTimeLocked(stats) > 0
                && isUnderJobCountQuotaLocked(stats, standbyBucket)
                && isUnderSessionCountQuotaLocked(stats, standbyBucket);
    }

    private boolean isUnderJobCountQuotaLocked(@NonNull ExecutionStats stats,
            final int standbyBucket) {
        final long now = sElapsedRealtimeClock.millis();
        final boolean isUnderAllowedTimeQuota =
                (stats.jobRateLimitExpirationTimeElapsed <= now
                        || stats.jobCountInRateLimitingWindow < mMaxJobCountPerRateLimitingWindow);
        return isUnderAllowedTimeQuota
                && (stats.bgJobCountInWindow < mMaxBucketJobCounts[standbyBucket]);
    }

    private boolean isUnderSessionCountQuotaLocked(@NonNull ExecutionStats stats,
            final int standbyBucket) {
        final long now = sElapsedRealtimeClock.millis();
        final boolean isUnderAllowedTimeQuota = (stats.sessionRateLimitExpirationTimeElapsed <= now
                || stats.sessionCountInRateLimitingWindow < mMaxSessionCountPerRateLimitingWindow);
        return isUnderAllowedTimeQuota
                && stats.sessionCountInWindow < mMaxBucketSessionCounts[standbyBucket];
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
        return Math.min(mAllowedTimePerPeriodMs - stats.executionTimeInWindowMs,
                mMaxExecutionTimeMs - stats.executionTimeInMaxPeriodMs);
    }

    @VisibleForTesting
    long getRemainingEJExecutionTimeLocked(final int userId, @NonNull final String packageName) {
        ShrinkableDebits quota = getEJDebitsLocked(userId, packageName);
        if (quota.getStandbyBucketLocked() == NEVER_INDEX) {
            return 0;
        }
        final long limitMs = getEJLimitMsLocked(packageName, quota.getStandbyBucketLocked());
        long remainingMs = limitMs - quota.getTallyLocked();

        // Stale sessions may still be factored into tally. Make sure they're removed.
        List<TimingSession> timingSessions = mEJTimingSessions.get(userId, packageName);
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final long windowStartTimeElapsed = nowElapsed - mEJLimitWindowSizeMs;
        if (timingSessions != null) {
            while (timingSessions.size() > 0) {
                TimingSession ts = timingSessions.get(0);
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

    private long getEJLimitMsLocked(@NonNull final String packageName, final int standbyBucket) {
        final long baseLimitMs = mEJLimitsMs[standbyBucket];
        if (packageName.equals(mPackageVerifier)) {
            return baseLimitMs + mEjLimitSpecialAdditionMs;
        }
        return baseLimitMs;
    }

    /**
     * Returns the amount of time, in milliseconds, until the package would have reached its
     * duration quota, assuming it has a job counting towards its quota the entire time. This takes
     * into account any {@link TimingSession}s that may roll out of the window as the job is
     * running.
     */
    @VisibleForTesting
    long getTimeUntilQuotaConsumedLocked(final int userId, @NonNull final String packageName) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final int standbyBucket = JobSchedulerService.standbyBucketForPackage(
                packageName, userId, nowElapsed);
        if (standbyBucket == NEVER_INDEX) {
            return 0;
        }

        List<TimingSession> sessions = mTimingSessions.get(userId, packageName);
        final ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        if (sessions == null || sessions.size() == 0) {
            // Regular ACTIVE case. Since the bucket size equals the allowed time, the app jobs can
            // essentially run until they reach the maximum limit.
            if (stats.windowSizeMs == mAllowedTimePerPeriodMs) {
                return mMaxExecutionTimeMs;
            }
            return mAllowedTimePerPeriodMs;
        }

        final long startWindowElapsed = nowElapsed - stats.windowSizeMs;
        final long startMaxElapsed = nowElapsed - MAX_PERIOD_MS;
        final long allowedTimeRemainingMs = mAllowedTimePerPeriodMs - stats.executionTimeInWindowMs;
        final long maxExecutionTimeRemainingMs =
                mMaxExecutionTimeMs - stats.executionTimeInMaxPeriodMs;

        // Regular ACTIVE case. Since the bucket size equals the allowed time, the app jobs can
        // essentially run until they reach the maximum limit.
        if (stats.windowSizeMs == mAllowedTimePerPeriodMs) {
            return calculateTimeUntilQuotaConsumedLocked(
                    sessions, startMaxElapsed, maxExecutionTimeRemainingMs);
        }

        // Need to check both max time and period time in case one is less than the other.
        // For example, max time remaining could be less than bucket time remaining, but sessions
        // contributing to the max time remaining could phase out enough that we'd want to use the
        // bucket value.
        return Math.min(
                calculateTimeUntilQuotaConsumedLocked(
                        sessions, startMaxElapsed, maxExecutionTimeRemainingMs),
                calculateTimeUntilQuotaConsumedLocked(
                        sessions, startWindowElapsed, allowedTimeRemainingMs));
    }

    /**
     * Calculates how much time it will take, in milliseconds, until the quota is fully consumed.
     *
     * @param windowStartElapsed The start of the window, in the elapsed realtime timebase.
     * @param deadSpaceMs        How much time can be allowed to count towards the quota
     */
    private long calculateTimeUntilQuotaConsumedLocked(@NonNull List<TimingSession> sessions,
            final long windowStartElapsed, long deadSpaceMs) {
        long timeUntilQuotaConsumedMs = 0;
        long start = windowStartElapsed;
        for (int i = 0; i < sessions.size(); ++i) {
            TimingSession session = sessions.get(i);

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

        List<TimingSession> sessions = mEJTimingSessions.get(userId, packageName);
        if (sessions == null || sessions.size() == 0) {
            return remainingExecutionTimeMs;
        }

        final long nowElapsed = sElapsedRealtimeClock.millis();
        ShrinkableDebits quota = getEJDebitsLocked(userId, packageName);
        final long limitMs = getEJLimitMsLocked(packageName, quota.getStandbyBucketLocked());
        final long startWindowElapsed = Math.max(0, nowElapsed - mEJLimitWindowSizeMs);
        long remainingDeadSpaceMs = remainingExecutionTimeMs;
        // Total time looked at where a session wouldn't be phasing out.
        long deadSpaceMs = 0;
        // Time regained from sessions phasing out
        long phasedOutSessionTimeMs = 0;

        for (int i = 0; i < sessions.size(); ++i) {
            TimingSession session = sessions.get(i);
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
                        - (i == 0 ? startWindowElapsed : sessions.get(i - 1).endTimeElapsed);
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
            final long bucketWindowSizeMs = mBucketPeriodsMs[standbyBucket];
            final int jobCountLimit = mMaxBucketJobCounts[standbyBucket];
            final int sessionCountLimit = mMaxBucketSessionCounts[standbyBucket];
            Timer timer = mPkgTimers.get(userId, packageName);
            if ((timer != null && timer.isActive())
                    || stats.expirationTimeElapsed <= sElapsedRealtimeClock.millis()
                    || stats.windowSizeMs != bucketWindowSizeMs
                    || stats.jobCountLimit != jobCountLimit
                    || stats.sessionCountLimit != sessionCountLimit) {
                // The stats are no longer valid.
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
            if (stats.executionTimeInWindowMs >= mAllowedTimeIntoQuotaMs) {
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                        nowElapsed - mAllowedTimeIntoQuotaMs + stats.windowSizeMs);
            }
            if (stats.executionTimeInMaxPeriodMs >= mMaxExecutionTimeIntoQuotaMs) {
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                        nowElapsed - mMaxExecutionTimeIntoQuotaMs + MAX_PERIOD_MS);
            }
            if (stats.bgJobCountInWindow >= stats.jobCountLimit) {
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                        nowElapsed + stats.windowSizeMs);
            }
        }

        List<TimingSession> sessions = mTimingSessions.get(userId, packageName);
        if (sessions == null || sessions.size() == 0) {
            return;
        }

        final long startWindowElapsed = nowElapsed - stats.windowSizeMs;
        final long startMaxElapsed = nowElapsed - MAX_PERIOD_MS;
        int sessionCountInWindow = 0;
        // The minimum time between the start time and the beginning of the sessions that were
        // looked at --> how much time the stats will be valid for.
        long emptyTimeMs = Long.MAX_VALUE;
        // Sessions are non-overlapping and in order of occurrence, so iterating backwards will get
        // the most recent ones.
        final int loopStart = sessions.size() - 1;
        for (int i = loopStart; i >= 0; --i) {
            TimingSession session = sessions.get(i);

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
                if (stats.executionTimeInWindowMs >= mAllowedTimeIntoQuotaMs) {
                    stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                            start + stats.executionTimeInWindowMs - mAllowedTimeIntoQuotaMs
                                    + stats.windowSizeMs);
                }
                if (stats.bgJobCountInWindow >= stats.jobCountLimit) {
                    stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                            session.endTimeElapsed + stats.windowSizeMs);
                }
                if (i == loopStart
                        || (sessions.get(i + 1).startTimeElapsed - session.endTimeElapsed)
                                > mTimingSessionCoalescingDurationMs) {
                    // Coalesce sessions if they are very close to each other in time
                    sessionCountInWindow++;

                    if (sessionCountInWindow >= stats.sessionCountLimit) {
                        stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                                session.endTimeElapsed + stats.windowSizeMs);
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
            final SparseArrayMap<String, List<TimingSession>> sessionMap =
                    isExpedited ? mEJTimingSessions : mTimingSessions;
            List<TimingSession> sessions = sessionMap.get(userId, packageName);
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
        synchronized (mLock) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            final ShrinkableDebits quota = getEJDebitsLocked(userId, packageName);
            if (transactQuotaLocked(userId, packageName, nowElapsed, quota, credit)
                    && maybeUpdateConstraintForPkgLocked(nowElapsed, userId, packageName)) {
                mStateChangedListener.onControllerStateChanged();
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
            nextCleanupElapsed += 10 * MINUTE_IN_MILLIS;
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
                mChargeTracker.isCharging());
        if (DEBUG) {
            Slog.d(TAG, "handleNewChargingStateLocked: " + mChargeTracker.isCharging());
        }
        // Deal with Timers first.
        mEJPkgTimers.forEach(mTimerChargingUpdateFunctor);
        mPkgTimers.forEach(mTimerChargingUpdateFunctor);
        // Now update jobs.
        maybeUpdateAllConstraintsLocked();
    }

    private void maybeUpdateAllConstraintsLocked() {
        boolean changed = false;
        final long nowElapsed = sElapsedRealtimeClock.millis();
        for (int u = 0; u < mTrackedJobs.numMaps(); ++u) {
            final int userId = mTrackedJobs.keyAt(u);
            for (int p = 0; p < mTrackedJobs.numElementsForKey(userId); ++p) {
                final String packageName = mTrackedJobs.keyAt(u, p);
                changed |= maybeUpdateConstraintForPkgLocked(nowElapsed, userId, packageName);
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
    private boolean maybeUpdateConstraintForPkgLocked(final long nowElapsed, final int userId,
            @NonNull final String packageName) {
        ArraySet<JobStatus> jobs = mTrackedJobs.get(userId, packageName);
        if (jobs == null || jobs.size() == 0) {
            return false;
        }

        // Quota is the same for all jobs within a package.
        final int realStandbyBucket = jobs.valueAt(0).getStandbyBucket();
        final boolean realInQuota = isWithinQuotaLocked(userId, packageName, realStandbyBucket);
        boolean outOfEJQuota = false;
        boolean changed = false;
        for (int i = jobs.size() - 1; i >= 0; --i) {
            final JobStatus js = jobs.valueAt(i);
            if (isTopStartedJobLocked(js)) {
                // Job was started while the app was in the TOP state so we should allow it to
                // finish.
                changed |= js.setQuotaConstraintSatisfied(nowElapsed, true);
            } else if (realStandbyBucket != ACTIVE_INDEX
                    && realStandbyBucket == js.getEffectiveStandbyBucket()) {
                // An app in the ACTIVE bucket may be out of quota while the job could be in quota
                // for some reason. Therefore, avoid setting the real value here and check each job
                // individually.
                changed |= setConstraintSatisfied(js, nowElapsed, realInQuota);
            } else {
                // This job is somehow exempted. Need to determine its own quota status.
                changed |= setConstraintSatisfied(js, nowElapsed, isWithinQuotaLocked(js));
            }

            if (js.isRequestedExpeditedJob()) {
                boolean isWithinEJQuota = isWithinEJQuotaLocked(js);
                changed |= setExpeditedConstraintSatisfied(js, nowElapsed, isWithinEJQuota);
                outOfEJQuota |= !isWithinEJQuota;
            }
        }
        if (!realInQuota || outOfEJQuota) {
            // Don't want to use the effective standby bucket here since that bump the bucket to
            // ACTIVE for one of the jobs, which doesn't help with other jobs that aren't
            // exempted.
            maybeScheduleStartAlarmLocked(userId, packageName, realStandbyBucket);
        } else {
            mInQuotaAlarmListener.removeAlarmLocked(userId, packageName);
        }
        return changed;
    }

    private class UidConstraintUpdater implements Consumer<JobStatus> {
        private final SparseArrayMap<String, Integer> mToScheduleStartAlarms =
                new SparseArrayMap<>();
        public boolean wasJobChanged;
        long mUpdateTimeElapsed = 0;

        void prepare() {
            mUpdateTimeElapsed = sElapsedRealtimeClock.millis();
        }

        @Override
        public void accept(JobStatus jobStatus) {
            wasJobChanged |= setConstraintSatisfied(
                    jobStatus, mUpdateTimeElapsed, isWithinQuotaLocked(jobStatus));
            final boolean outOfEJQuota;
            if (jobStatus.isRequestedExpeditedJob()) {
                final boolean isWithinEJQuota = isWithinEJQuotaLocked(jobStatus);
                wasJobChanged |= setExpeditedConstraintSatisfied(
                        jobStatus, mUpdateTimeElapsed, isWithinEJQuota);
                outOfEJQuota = !isWithinEJQuota;
            } else {
                outOfEJQuota = false;
            }

            final int userId = jobStatus.getSourceUserId();
            final String packageName = jobStatus.getSourcePackageName();
            final int realStandbyBucket = jobStatus.getStandbyBucket();
            if (isWithinQuotaLocked(userId, packageName, realStandbyBucket) && !outOfEJQuota) {
                // TODO(141645789): we probably shouldn't cancel the alarm until we've verified
                // that all jobs for the userId-package are within quota.
                mInQuotaAlarmListener.removeAlarmLocked(userId, packageName);
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
            wasJobChanged = false;
            mToScheduleStartAlarms.clear();
        }
    }

    private final UidConstraintUpdater mUpdateUidConstraints = new UidConstraintUpdater();

    private boolean maybeUpdateConstraintForUidLocked(final int uid) {
        mUpdateUidConstraints.prepare();
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
        final boolean isUnderTimingSessionCountQuota = isUnderSessionCountQuotaLocked(stats,
                standbyBucket);
        final long remainingEJQuota = getRemainingEJExecutionTimeLocked(userId, packageName);

        final boolean inRegularQuota = stats.executionTimeInWindowMs < mAllowedTimePerPeriodMs
                && stats.executionTimeInMaxPeriodMs < mMaxExecutionTimeMs
                && isUnderJobCountQuota
                && isUnderTimingSessionCountQuota;
        if (inRegularQuota && remainingEJQuota > 0) {
            // Already in quota. Why was this method called?
            if (DEBUG) {
                Slog.e(TAG, "maybeScheduleStartAlarmLocked called for " + pkgString
                        + " even though it already has "
                        + getRemainingExecutionTimeLocked(userId, packageName, standbyBucket)
                        + "ms in its quota.");
            }
            mInQuotaAlarmListener.removeAlarmLocked(userId, packageName);
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
            final long limitMs = getEJLimitMsLocked(packageName, standbyBucket) - mQuotaBufferMs;
            long sumMs = 0;
            final Timer ejTimer = mEJPkgTimers.get(userId, packageName);
            if (ejTimer != null && ejTimer.isActive()) {
                final long nowElapsed = sElapsedRealtimeClock.millis();
                sumMs += ejTimer.getCurrentDuration(nowElapsed);
                if (sumMs >= limitMs) {
                    inEJQuotaTimeElapsed = (nowElapsed - limitMs) + mEJLimitWindowSizeMs;
                }
            }
            List<TimingSession> timingSessions = mEJTimingSessions.get(userId, packageName);
            if (timingSessions != null) {
                for (int i = timingSessions.size() - 1; i >= 0; --i) {
                    TimingSession ts = timingSessions.get(i);
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
                Slog.wtf(TAG,
                        string(userId, packageName) + " has 0 EJ quota without running anything");
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
        mInQuotaAlarmListener.addAlarmLocked(userId, packageName, inQuotaTimeElapsed);
    }

    private boolean setConstraintSatisfied(@NonNull JobStatus jobStatus, long nowElapsed,
            boolean isWithinQuota) {
        if (!isWithinQuota && jobStatus.getWhenStandbyDeferred() == 0) {
            // Mark that the job is being deferred due to buckets.
            jobStatus.setWhenStandbyDeferred(nowElapsed);
        }
        return jobStatus.setQuotaConstraintSatisfied(nowElapsed, isWithinQuota);
    }

    /**
     * If the satisfaction changes, this will tell connectivity & background jobs controller to
     * also re-evaluate their state.
     */
    private boolean setExpeditedConstraintSatisfied(@NonNull JobStatus jobStatus, long nowElapsed,
            boolean isWithinQuota) {
        if (jobStatus.setExpeditedJobQuotaConstraintSatisfied(nowElapsed, isWithinQuota)) {
            mBackgroundJobsController.evaluateStateLocked(jobStatus);
            mConnectivityController.evaluateStateLocked(jobStatus);
            if (isWithinQuota && jobStatus.isReady()) {
                mStateChangedListener.onRunJobNow(jobStatus);
            }
            return true;
        }
        return false;
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
        private final Package mPkg;
        private final int mUid;
        private final boolean mRegularJobTimer;

        // List of jobs currently running for this app that started when the app wasn't in the
        // foreground.
        private final ArraySet<JobStatus> mRunningBgJobs = new ArraySet<>();
        private long mStartTimeElapsed;
        private int mBgJobCount;
        private long mDebitAdjustment;

        Timer(int uid, int userId, String packageName, boolean regularJobTimer) {
            mPkg = new Package(userId, packageName);
            mUid = uid;
            mRegularJobTimer = regularJobTimer;
        }

        void startTrackingJobLocked(@NonNull JobStatus jobStatus) {
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
            // Always track jobs, even when charging.
            mRunningBgJobs.add(jobStatus);
            if (shouldTrackLocked()) {
                mBgJobCount++;
                if (mRegularJobTimer) {
                    incrementJobCountLocked(mPkg.userId, mPkg.packageName, 1);
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
                if (mRunningBgJobs.remove(jobStatus)
                        && !mChargeTracker.isCharging() && mRunningBgJobs.size() == 0) {
                    emitSessionLocked(sElapsedRealtimeClock.millis());
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

        private boolean shouldTrackLocked() {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            final int standbyBucket = JobSchedulerService.standbyBucketForPackage(mPkg.packageName,
                    mPkg.userId, nowElapsed);
            final long tempAllowlistGracePeriodEndElapsed = mTempAllowlistGraceCache.get(mUid);
            final boolean hasTempAllowlistExemption = !mRegularJobTimer
                    && (mTempAllowlistCache.get(mUid)
                    || nowElapsed < tempAllowlistGracePeriodEndElapsed);
            return (standbyBucket == RESTRICTED_INDEX || !mChargeTracker.isCharging())
                    && !mForegroundUids.get(mUid) && !hasTempAllowlistExemption;
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
                        mRegularJobTimer ? MSG_REACHED_QUOTA : MSG_REACHED_EJ_QUOTA, mPkg);
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
                    mRegularJobTimer ? MSG_REACHED_QUOTA : MSG_REACHED_EJ_QUOTA, mPkg);
        }

        public void dump(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
            pw.print("Timer<");
            pw.print(mRegularJobTimer ? "REG" : " EJ");
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

            mPkg.dumpDebug(proto, StateControllerProto.QuotaController.Timer.PKG);
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
        private final Package mPkg;

        // List of jobs currently running for this app that started when the app wasn't in the
        // foreground.
        private final SparseArray<UsageEvents.Event> mActivities = new SparseArray<>();
        private long mStartTimeElapsed;

        TopAppTimer(int userId, String packageName) {
            mPkg = new Package(userId, packageName);
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
                                nowElapsed, debits, pendingReward)
                                && maybeUpdateConstraintForPkgLocked(nowElapsed,
                                mPkg.userId, mPkg.packageName)) {
                            mStateChangedListener.onControllerStateChanged();
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

            mPkg.dumpDebug(proto, StateControllerProto.QuotaController.TopAppTimer.PKG);
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
            JobSchedulerBackgroundThread.getHandler().post(() -> {
                final int bucketIndex = JobSchedulerService.standbyBucketToBucketIndex(bucket);
                updateStandbyBucket(userId, packageName, bucketIndex);
            });
        }
    }

    @VisibleForTesting
    void updateStandbyBucket(
            final int userId, final @NonNull String packageName, final int bucketIndex) {
        if (DEBUG) {
            Slog.i(TAG, "Moving pkg " + string(userId, packageName)
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
            if (maybeUpdateConstraintForPkgLocked(sElapsedRealtimeClock.millis(),
                    userId, packageName)) {
                mStateChangedListener.onControllerStateChanged();
            }
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
            mHandler.obtainMessage(MSG_PROCESS_USAGE_EVENT, userId, 0, event).sendToTarget();
        }
    }

    final class TempAllowlistTracker implements PowerAllowlistInternal.TempAllowlistChangeListener {

        @Override
        public void onAppAdded(int uid) {
            synchronized (mLock) {
                final long nowElapsed = sElapsedRealtimeClock.millis();
                mTempAllowlistCache.put(uid, true);
                final ArraySet<String> packages = getPackagesForUidLocked(uid);
                if (packages != null) {
                    final int userId = UserHandle.getUserId(uid);
                    for (int i = packages.size() - 1; i >= 0; --i) {
                        Timer t = mEJPkgTimers.get(userId, packages.valueAt(i));
                        if (t != null) {
                            t.onStateChangedLocked(nowElapsed, true);
                        }
                    }
                    if (maybeUpdateConstraintForUidLocked(uid)) {
                        mStateChangedListener.onControllerStateChanged();
                    }
                }
            }
        }

        @Override
        public void onAppRemoved(int uid) {
            synchronized (mLock) {
                final long nowElapsed = sElapsedRealtimeClock.millis();
                final long endElapsed = nowElapsed + mEJTempAllowlistGracePeriodMs;
                mTempAllowlistCache.delete(uid);
                mTempAllowlistGraceCache.put(uid, endElapsed);
                Message msg = mHandler.obtainMessage(MSG_END_GRACE_PERIOD, uid, 0);
                mHandler.sendMessageDelayed(msg, mEJTempAllowlistGracePeriodMs);
            }
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
        // Don't delete EJ timing sessions here. They'll be removed in
        // getRemainingEJExecutionTimeLocked().
    }

    @Nullable
    private ArraySet<String> getPackagesForUidLocked(final int uid) {
        ArraySet<String> packages = mUidToPackageCache.get(uid);
        if (packages == null) {
            try {
                String[] pkgs = AppGlobals.getPackageManager()
                        .getPackagesForUid(uid);
                if (pkgs != null) {
                    for (String pkg : pkgs) {
                        mUidToPackageCache.add(uid, pkg);
                    }
                    packages = mUidToPackageCache.get(uid);
                }
            } catch (RemoteException e) {
                // Shouldn't happen.
            }
        }
        return packages;
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
                        if (DEBUG) {
                            Slog.d(TAG, "Checking if " + pkg + " has reached its quota.");
                        }

                        long timeRemainingMs = getRemainingExecutionTimeLocked(pkg.userId,
                                pkg.packageName);
                        if (timeRemainingMs <= 50) {
                            // Less than 50 milliseconds left. Start process of shutting down jobs.
                            if (DEBUG) Slog.d(TAG, pkg + " has reached its quota.");
                            if (maybeUpdateConstraintForPkgLocked(sElapsedRealtimeClock.millis(),
                                    pkg.userId, pkg.packageName)) {
                                mStateChangedListener.onControllerStateChanged();
                            }
                        } else {
                            // This could potentially happen if an old session phases out while a
                            // job is currently running.
                            // Reschedule message
                            Message rescheduleMsg = obtainMessage(MSG_REACHED_QUOTA, pkg);
                            timeRemainingMs = getTimeUntilQuotaConsumedLocked(pkg.userId,
                                    pkg.packageName);
                            if (DEBUG) {
                                Slog.d(TAG, pkg + " has " + timeRemainingMs + "ms left.");
                            }
                            sendMessageDelayed(rescheduleMsg, timeRemainingMs);
                        }
                        break;
                    }
                    case MSG_REACHED_EJ_QUOTA: {
                        Package pkg = (Package) msg.obj;
                        if (DEBUG) {
                            Slog.d(TAG, "Checking if " + pkg + " has reached its EJ quota.");
                        }

                        long timeRemainingMs = getRemainingEJExecutionTimeLocked(
                                pkg.userId, pkg.packageName);
                        if (timeRemainingMs <= 0) {
                            if (DEBUG) Slog.d(TAG, pkg + " has reached its EJ quota.");
                            if (maybeUpdateConstraintForPkgLocked(sElapsedRealtimeClock.millis(),
                                    pkg.userId, pkg.packageName)) {
                                mStateChangedListener.onControllerStateChanged();
                            }
                        } else {
                            // This could potentially happen if an old session phases out while a
                            // job is currently running.
                            // Reschedule message
                            Message rescheduleMsg = obtainMessage(MSG_REACHED_EJ_QUOTA, pkg);
                            timeRemainingMs = getTimeUntilEJQuotaConsumedLocked(
                                    pkg.userId, pkg.packageName);
                            if (DEBUG) {
                                Slog.d(TAG, pkg + " has " + timeRemainingMs + "ms left for EJ");
                            }
                            sendMessageDelayed(rescheduleMsg, timeRemainingMs);
                        }
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
                            Slog.d(TAG, "Checking pkg " + string(userId, packageName));
                        }
                        if (maybeUpdateConstraintForPkgLocked(sElapsedRealtimeClock.millis(),
                                userId, packageName)) {
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
                            if (mPkgTimers.indexOfKey(userId) >= 0
                                    || mEJPkgTimers.indexOfKey(userId) >= 0) {
                                final ArraySet<String> packages = getPackagesForUidLocked(uid);
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
                            if (maybeUpdateConstraintForUidLocked(uid)) {
                                mStateChangedListener.onControllerStateChanged();
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
                                    + " for " + string(userId, pkgName));
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
                            if (mTempAllowlistCache.get(uid)) {
                                // App added back to the temp allowlist during the grace period.
                                if (DEBUG) {
                                    Slog.d(TAG, uid + " is still allowed");
                                }
                                break;
                            }
                            if (DEBUG) {
                                Slog.d(TAG, uid + " is now out of grace period");
                            }
                            final ArraySet<String> packages = getPackagesForUidLocked(uid);
                            if (packages != null) {
                                final int userId = UserHandle.getUserId(uid);
                                final long nowElapsed = sElapsedRealtimeClock.millis();
                                for (int i = packages.size() - 1; i >= 0; --i) {
                                    Timer t = mEJPkgTimers.get(userId, packages.valueAt(i));
                                    if (t != null) {
                                        t.onStateChangedLocked(nowElapsed, false);
                                    }
                                }
                                if (maybeUpdateConstraintForUidLocked(uid)) {
                                    mStateChangedListener.onControllerStateChanged();
                                }
                            }
                        }

                        break;
                    }
                }
            }
        }
    }

    static class AlarmQueue extends PriorityQueue<Pair<Package, Long>> {
        AlarmQueue() {
            super(1, (o1, o2) -> (int) (o1.second - o2.second));
        }

        /**
         * Remove any instances of the Package from the queue.
         *
         * @return true if an instance was removed, false otherwise.
         */
        boolean remove(@NonNull Package pkg) {
            boolean removed = false;
            Pair[] alarms = toArray(new Pair[size()]);
            for (int i = alarms.length - 1; i >= 0; --i) {
                if (pkg.equals(alarms[i].first)) {
                    remove(alarms[i]);
                    removed = true;
                }
            }
            return removed;
        }
    }

    /** Track when UPTCs are expected to come back into quota. */
    private class InQuotaAlarmListener implements AlarmManager.OnAlarmListener {
        @GuardedBy("mLock")
        private final AlarmQueue mAlarmQueue = new AlarmQueue();
        /** The next time the alarm is set to go off, in the elapsed realtime timebase. */
        @GuardedBy("mLock")
        private long mTriggerTimeElapsed = 0;
        /** The minimum amount of time between quota check alarms. */
        @GuardedBy("mLock")
        private long mMinQuotaCheckDelayMs = QcConstants.DEFAULT_MIN_QUOTA_CHECK_DELAY_MS;

        @GuardedBy("mLock")
        void addAlarmLocked(int userId, @NonNull String pkgName, long inQuotaTimeElapsed) {
            final Package pkg = new Package(userId, pkgName);
            mAlarmQueue.remove(pkg);
            mAlarmQueue.offer(new Pair<>(pkg, inQuotaTimeElapsed));
            setNextAlarmLocked();
        }

        @GuardedBy("mLock")
        void setMinQuotaCheckDelayMs(long minDelayMs) {
            mMinQuotaCheckDelayMs = minDelayMs;
        }

        @GuardedBy("mLock")
        void removeAlarmLocked(@NonNull Package pkg) {
            if (mAlarmQueue.remove(pkg)) {
                setNextAlarmLocked();
            }
        }

        @GuardedBy("mLock")
        void removeAlarmLocked(int userId, @NonNull String packageName) {
            removeAlarmLocked(new Package(userId, packageName));
        }

        @GuardedBy("mLock")
        void removeAlarmsLocked(int userId) {
            boolean removed = false;
            Pair[] alarms = mAlarmQueue.toArray(new Pair[mAlarmQueue.size()]);
            for (int i = alarms.length - 1; i >= 0; --i) {
                final Package pkg = (Package) alarms[i].first;
                if (userId == pkg.userId) {
                    mAlarmQueue.remove(alarms[i]);
                    removed = true;
                }
            }
            if (removed) {
                setNextAlarmLocked();
            }
        }

        @GuardedBy("mLock")
        private void setNextAlarmLocked() {
            setNextAlarmLocked(sElapsedRealtimeClock.millis());
        }

        @GuardedBy("mLock")
        private void setNextAlarmLocked(long earliestTriggerElapsed) {
            if (mAlarmQueue.size() > 0) {
                final Pair<Package, Long> alarm = mAlarmQueue.peek();
                final long nextTriggerTimeElapsed = Math.max(earliestTriggerElapsed, alarm.second);
                // Only schedule the alarm if one of the following is true:
                // 1. There isn't one currently scheduled
                // 2. The new alarm is significantly earlier than the previous alarm. If it's
                // earlier but not significantly so, then we essentially delay the job a few extra
                // minutes.
                // 3. The alarm is after the current alarm.
                if (mTriggerTimeElapsed == 0
                        || nextTriggerTimeElapsed < mTriggerTimeElapsed - 3 * MINUTE_IN_MILLIS
                        || mTriggerTimeElapsed < nextTriggerTimeElapsed) {
                    if (DEBUG) {
                        Slog.d(TAG, "Scheduling start alarm at " + nextTriggerTimeElapsed
                                + " for app " + alarm.first);
                    }
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, nextTriggerTimeElapsed,
                            ALARM_TAG_QUOTA_CHECK, this, mHandler);
                    mTriggerTimeElapsed = nextTriggerTimeElapsed;
                }
            } else {
                mAlarmManager.cancel(this);
                mTriggerTimeElapsed = 0;
            }
        }

        @Override
        public void onAlarm() {
            synchronized (mLock) {
                while (mAlarmQueue.size() > 0) {
                    final Pair<Package, Long> alarm = mAlarmQueue.peek();
                    if (alarm.second <= sElapsedRealtimeClock.millis()) {
                        mHandler.obtainMessage(MSG_CHECK_PACKAGE, alarm.first.userId, 0,
                                alarm.first.packageName).sendToTarget();
                        mAlarmQueue.remove(alarm);
                    } else {
                        break;
                    }
                }
                setNextAlarmLocked(sElapsedRealtimeClock.millis() + mMinQuotaCheckDelayMs);
            }
        }

        @GuardedBy("mLock")
        void dumpLocked(IndentingPrintWriter pw) {
            pw.println("In quota alarms:");
            pw.increaseIndent();

            if (mAlarmQueue.size() == 0) {
                pw.println("NOT WAITING");
            } else {
                Pair[] alarms = mAlarmQueue.toArray(new Pair[mAlarmQueue.size()]);
                for (int i = 0; i < alarms.length; ++i) {
                    final Package pkg = (Package) alarms[i].first;
                    pw.print(pkg);
                    pw.print(": ");
                    pw.print(alarms[i].second);
                    pw.println();
                }
            }

            pw.decreaseIndent();
        }

        @GuardedBy("mLock")
        void dumpLocked(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(
                    StateControllerProto.QuotaController.InQuotaAlarmListener.TRIGGER_TIME_ELAPSED,
                    mTriggerTimeElapsed);

            Pair[] alarms = mAlarmQueue.toArray(new Pair[mAlarmQueue.size()]);
            for (int i = 0; i < alarms.length; ++i) {
                final long aToken = proto.start(
                        StateControllerProto.QuotaController.InQuotaAlarmListener.ALARMS);

                final Package pkg = (Package) alarms[i].first;
                pkg.dumpDebug(proto,
                        StateControllerProto.QuotaController.InQuotaAlarmListener.Alarm.PKG);
                proto.write(
                        StateControllerProto.QuotaController.InQuotaAlarmListener.Alarm.IN_QUOTA_TIME_ELAPSED,
                        (Long) alarms[i].second);

                proto.end(aToken);
            }

            proto.end(token);
        }
    }

    @Override
    public void prepareForUpdatedConstantsLocked() {
        mQcConstants.mShouldReevaluateConstraints = false;
        mQcConstants.mRateLimitingConstantsUpdated = false;
        mQcConstants.mExecutionPeriodConstantsUpdated = false;
        mQcConstants.mEJLimitConstantsUpdated = false;
    }

    @Override
    public void processConstantLocked(DeviceConfig.Properties properties, String key) {
        mQcConstants.processConstantLocked(properties, key);
    }

    @Override
    public void onConstantsUpdatedLocked() {
        if (mQcConstants.mShouldReevaluateConstraints) {
            // Update job bookkeeping out of band.
            JobSchedulerBackgroundThread.getHandler().post(() -> {
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

        /** Prefix to use with all constant keys in order to "sub-namespace" the keys. */
        private static final String QC_CONSTANT_PREFIX = "qc_";

        @VisibleForTesting
        static final String KEY_ALLOWED_TIME_PER_PERIOD_MS =
                QC_CONSTANT_PREFIX + "allowed_time_per_period_ms";
        @VisibleForTesting
        static final String KEY_IN_QUOTA_BUFFER_MS =
                QC_CONSTANT_PREFIX + "in_quota_buffer_ms";
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
        static final String KEY_EJ_LIMIT_SPECIAL_ADDITION_MS =
                QC_CONSTANT_PREFIX + "ej_limit_special_addition_ms";
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
        static final String KEY_EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS =
                QC_CONSTANT_PREFIX + "ej_temp_allowlist_grace_period_ms";

        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_MS =
                10 * 60 * 1000L; // 10 minutes
        private static final long DEFAULT_IN_QUOTA_BUFFER_MS =
                30 * 1000L; // 30 seconds
        private static final long DEFAULT_WINDOW_SIZE_ACTIVE_MS =
                DEFAULT_ALLOWED_TIME_PER_PERIOD_MS; // ACTIVE apps can run jobs at any time
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
        private static final int DEFAULT_MAX_JOB_COUNT_ACTIVE =
                75; // 75/window = 450/hr = 1/session
        private static final int DEFAULT_MAX_JOB_COUNT_WORKING = // 120/window = 60/hr = 12/session
                (int) (60.0 * DEFAULT_WINDOW_SIZE_WORKING_MS / HOUR_IN_MILLIS);
        private static final int DEFAULT_MAX_JOB_COUNT_FREQUENT = // 200/window = 25/hr = 25/session
                (int) (25.0 * DEFAULT_WINDOW_SIZE_FREQUENT_MS / HOUR_IN_MILLIS);
        private static final int DEFAULT_MAX_JOB_COUNT_RARE = // 48/window = 2/hr = 16/session
                (int) (2.0 * DEFAULT_WINDOW_SIZE_RARE_MS / HOUR_IN_MILLIS);
        private static final int DEFAULT_MAX_JOB_COUNT_RESTRICTED = 10;
        private static final int DEFAULT_MAX_SESSION_COUNT_ACTIVE =
                75; // 450/hr
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
        private static final long DEFAULT_EJ_LIMIT_ACTIVE_MS = 30 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_WORKING_MS = DEFAULT_EJ_LIMIT_ACTIVE_MS;
        private static final long DEFAULT_EJ_LIMIT_FREQUENT_MS = 10 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_RARE_MS = DEFAULT_EJ_LIMIT_FREQUENT_MS;
        private static final long DEFAULT_EJ_LIMIT_RESTRICTED_MS = 5 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_LIMIT_SPECIAL_ADDITION_MS = 30 * MINUTE_IN_MILLIS;
        private static final long DEFAULT_EJ_WINDOW_SIZE_MS = 24 * HOUR_IN_MILLIS;
        private static final long DEFAULT_EJ_TOP_APP_TIME_CHUNK_SIZE_MS = 30 * SECOND_IN_MILLIS;
        private static final long DEFAULT_EJ_REWARD_TOP_APP_MS = 10 * SECOND_IN_MILLIS;
        private static final long DEFAULT_EJ_REWARD_INTERACTION_MS = 15 * SECOND_IN_MILLIS;
        private static final long DEFAULT_EJ_REWARD_NOTIFICATION_SEEN_MS = 0;
        private static final long DEFAULT_EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS = 3 * MINUTE_IN_MILLIS;

        /** How much time each app will have to run jobs within their standby bucket window. */
        public long ALLOWED_TIME_PER_PERIOD_MS = DEFAULT_ALLOWED_TIME_PER_PERIOD_MS;

        /**
         * How much time the package should have before transitioning from out-of-quota to in-quota.
         * This should not affect processing if the package is already in-quota.
         */
        public long IN_QUOTA_BUFFER_MS = DEFAULT_IN_QUOTA_BUFFER_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_ACTIVE_MS = DEFAULT_WINDOW_SIZE_ACTIVE_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_WORKING_MS = DEFAULT_WINDOW_SIZE_WORKING_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_FREQUENT_MS = DEFAULT_WINDOW_SIZE_FREQUENT_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_MS} within the past
         * WINDOW_SIZE_MS.
         */
        public long WINDOW_SIZE_RARE_MS = DEFAULT_WINDOW_SIZE_RARE_MS;

        /**
         * The quota window size of the particular standby bucket. Apps in this standby bucket are
         * expected to run only {@link #ALLOWED_TIME_PER_PERIOD_MS} within the past
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
         * The maximum number of {@link TimingSession}s an app can run within this particular
         * standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_ACTIVE = DEFAULT_MAX_SESSION_COUNT_ACTIVE;

        /**
         * The maximum number of {@link TimingSession}s an app can run within this particular
         * standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_WORKING = DEFAULT_MAX_SESSION_COUNT_WORKING;

        /**
         * The maximum number of {@link TimingSession}s an app can run within this particular
         * standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_FREQUENT = DEFAULT_MAX_SESSION_COUNT_FREQUENT;

        /**
         * The maximum number of {@link TimingSession}s an app can run within this particular
         * standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_RARE = DEFAULT_MAX_SESSION_COUNT_RARE;

        /**
         * The maximum number of {@link TimingSession}s an app can run within this particular
         * standby bucket's window size.
         */
        public int MAX_SESSION_COUNT_RESTRICTED = DEFAULT_MAX_SESSION_COUNT_RESTRICTED;

        /**
         * The maximum number of {@link TimingSession}s that can run within the past
         * {@link #ALLOWED_TIME_PER_PERIOD_MS}.
         */
        public int MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW =
                DEFAULT_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW;

        /**
         * Treat two distinct {@link TimingSession}s as the same if they start and end within this
         * amount of time of each other.
         */
        public long TIMING_SESSION_COALESCING_DURATION_MS =
                DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS;

        /** The minimum amount of time between quota check alarms. */
        public long MIN_QUOTA_CHECK_DELAY_MS = DEFAULT_MIN_QUOTA_CHECK_DELAY_MS;

        // Safeguards

        /** The minimum number of jobs that any bucket will be allowed to run within its window. */
        private static final int MIN_BUCKET_JOB_COUNT = 10;

        /**
         * The minimum number of {@link TimingSession}s that any bucket will be allowed to run
         * within its window.
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
        public long EJ_LIMIT_SPECIAL_ADDITION_MS = DEFAULT_EJ_LIMIT_SPECIAL_ADDITION_MS;

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
        public long EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS = DEFAULT_EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS;

        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            switch (key) {
                case KEY_ALLOWED_TIME_PER_PERIOD_MS:
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
                case KEY_EJ_LIMIT_SPECIAL_ADDITION_MS:
                case KEY_EJ_WINDOW_SIZE_MS:
                    updateEJLimitConstantsLocked();
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
                    mInQuotaAlarmListener.setMinQuotaCheckDelayMs(
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
                case KEY_EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS:
                    // We don't need to re-evaluate execution stats or constraint status for this.
                    EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS =
                            properties.getLong(key, DEFAULT_EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS);
                    // Limit grace period to be in the range [0 minutes, 1 hour].
                    mEJTempAllowlistGracePeriodMs = Math.min(HOUR_IN_MILLIS,
                            Math.max(0, EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS));
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
                    KEY_ALLOWED_TIME_PER_PERIOD_MS, KEY_IN_QUOTA_BUFFER_MS,
                    KEY_MAX_EXECUTION_TIME_MS, KEY_WINDOW_SIZE_ACTIVE_MS,
                    KEY_WINDOW_SIZE_WORKING_MS,
                    KEY_WINDOW_SIZE_FREQUENT_MS, KEY_WINDOW_SIZE_RARE_MS,
                    KEY_WINDOW_SIZE_RESTRICTED_MS);
            ALLOWED_TIME_PER_PERIOD_MS =
                    properties.getLong(KEY_ALLOWED_TIME_PER_PERIOD_MS,
                            DEFAULT_ALLOWED_TIME_PER_PERIOD_MS);
            IN_QUOTA_BUFFER_MS = properties.getLong(KEY_IN_QUOTA_BUFFER_MS,
                    DEFAULT_IN_QUOTA_BUFFER_MS);
            MAX_EXECUTION_TIME_MS = properties.getLong(KEY_MAX_EXECUTION_TIME_MS,
                    DEFAULT_MAX_EXECUTION_TIME_MS);
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
            long newAllowedTimeMs = Math.min(mMaxExecutionTimeMs,
                    Math.max(MINUTE_IN_MILLIS, ALLOWED_TIME_PER_PERIOD_MS));
            if (mAllowedTimePerPeriodMs != newAllowedTimeMs) {
                mAllowedTimePerPeriodMs = newAllowedTimeMs;
                mAllowedTimeIntoQuotaMs = mAllowedTimePerPeriodMs - mQuotaBufferMs;
                mShouldReevaluateConstraints = true;
            }
            // Make sure quota buffer is non-negative, not greater than allowed time per period,
            // and no more than 5 minutes.
            long newQuotaBufferMs = Math.max(0, Math.min(mAllowedTimePerPeriodMs,
                    Math.min(5 * MINUTE_IN_MILLIS, IN_QUOTA_BUFFER_MS)));
            if (mQuotaBufferMs != newQuotaBufferMs) {
                mQuotaBufferMs = newQuotaBufferMs;
                mAllowedTimeIntoQuotaMs = mAllowedTimePerPeriodMs - mQuotaBufferMs;
                mMaxExecutionTimeIntoQuotaMs = mMaxExecutionTimeMs - mQuotaBufferMs;
                mShouldReevaluateConstraints = true;
            }
            long newActivePeriodMs = Math.max(mAllowedTimePerPeriodMs,
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_ACTIVE_MS));
            if (mBucketPeriodsMs[ACTIVE_INDEX] != newActivePeriodMs) {
                mBucketPeriodsMs[ACTIVE_INDEX] = newActivePeriodMs;
                mShouldReevaluateConstraints = true;
            }
            long newWorkingPeriodMs = Math.max(mAllowedTimePerPeriodMs,
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_WORKING_MS));
            if (mBucketPeriodsMs[WORKING_INDEX] != newWorkingPeriodMs) {
                mBucketPeriodsMs[WORKING_INDEX] = newWorkingPeriodMs;
                mShouldReevaluateConstraints = true;
            }
            long newFrequentPeriodMs = Math.max(mAllowedTimePerPeriodMs,
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_FREQUENT_MS));
            if (mBucketPeriodsMs[FREQUENT_INDEX] != newFrequentPeriodMs) {
                mBucketPeriodsMs[FREQUENT_INDEX] = newFrequentPeriodMs;
                mShouldReevaluateConstraints = true;
            }
            long newRarePeriodMs = Math.max(mAllowedTimePerPeriodMs,
                    Math.min(MAX_PERIOD_MS, WINDOW_SIZE_RARE_MS));
            if (mBucketPeriodsMs[RARE_INDEX] != newRarePeriodMs) {
                mBucketPeriodsMs[RARE_INDEX] = newRarePeriodMs;
                mShouldReevaluateConstraints = true;
            }
            // Fit in the range [allowed time (10 mins), 1 week].
            long newRestrictedPeriodMs = Math.max(mAllowedTimePerPeriodMs,
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
                    KEY_EJ_LIMIT_ACTIVE_MS, KEY_EJ_LIMIT_WORKING_MS,
                    KEY_EJ_LIMIT_FREQUENT_MS, KEY_EJ_LIMIT_RARE_MS,
                    KEY_EJ_LIMIT_RESTRICTED_MS, KEY_EJ_LIMIT_SPECIAL_ADDITION_MS,
                    KEY_EJ_WINDOW_SIZE_MS);
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
            EJ_LIMIT_SPECIAL_ADDITION_MS = properties.getLong(
                    KEY_EJ_LIMIT_SPECIAL_ADDITION_MS, DEFAULT_EJ_LIMIT_SPECIAL_ADDITION_MS);
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
            long newActiveLimitMs = Math.max(15 * MINUTE_IN_MILLIS,
                    Math.min(newWindowSizeMs, EJ_LIMIT_ACTIVE_MS));
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
            // The addition must be in the range [0 minutes, window size - active limit].
            long newSpecialAdditionMs = Math.max(0,
                    Math.min(newWindowSizeMs - newActiveLimitMs, EJ_LIMIT_SPECIAL_ADDITION_MS));
            if (mEjLimitSpecialAdditionMs != newSpecialAdditionMs) {
                mEjLimitSpecialAdditionMs = newSpecialAdditionMs;
                mShouldReevaluateConstraints = true;
            }
        }

        private void dump(IndentingPrintWriter pw) {
            pw.println();
            pw.println("QuotaController:");
            pw.increaseIndent();
            pw.print(KEY_ALLOWED_TIME_PER_PERIOD_MS, ALLOWED_TIME_PER_PERIOD_MS).println();
            pw.print(KEY_IN_QUOTA_BUFFER_MS, IN_QUOTA_BUFFER_MS).println();
            pw.print(KEY_WINDOW_SIZE_ACTIVE_MS, WINDOW_SIZE_ACTIVE_MS).println();
            pw.print(KEY_WINDOW_SIZE_WORKING_MS, WINDOW_SIZE_WORKING_MS).println();
            pw.print(KEY_WINDOW_SIZE_FREQUENT_MS, WINDOW_SIZE_FREQUENT_MS).println();
            pw.print(KEY_WINDOW_SIZE_RARE_MS, WINDOW_SIZE_RARE_MS).println();
            pw.print(KEY_WINDOW_SIZE_RESTRICTED_MS, WINDOW_SIZE_RESTRICTED_MS).println();
            pw.print(KEY_MAX_EXECUTION_TIME_MS, MAX_EXECUTION_TIME_MS).println();
            pw.print(KEY_MAX_JOB_COUNT_ACTIVE, MAX_JOB_COUNT_ACTIVE).println();
            pw.print(KEY_MAX_JOB_COUNT_WORKING, MAX_JOB_COUNT_WORKING).println();
            pw.print(KEY_MAX_JOB_COUNT_FREQUENT, MAX_JOB_COUNT_FREQUENT).println();
            pw.print(KEY_MAX_JOB_COUNT_RARE, MAX_JOB_COUNT_RARE).println();
            pw.print(KEY_MAX_JOB_COUNT_RESTRICTED, MAX_JOB_COUNT_RESTRICTED).println();
            pw.print(KEY_RATE_LIMITING_WINDOW_MS, RATE_LIMITING_WINDOW_MS).println();
            pw.print(KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW,
                    MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW).println();
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

            pw.print(KEY_EJ_LIMIT_ACTIVE_MS, EJ_LIMIT_ACTIVE_MS).println();
            pw.print(KEY_EJ_LIMIT_WORKING_MS, EJ_LIMIT_WORKING_MS).println();
            pw.print(KEY_EJ_LIMIT_FREQUENT_MS, EJ_LIMIT_FREQUENT_MS).println();
            pw.print(KEY_EJ_LIMIT_RARE_MS, EJ_LIMIT_RARE_MS).println();
            pw.print(KEY_EJ_LIMIT_RESTRICTED_MS, EJ_LIMIT_RESTRICTED_MS).println();
            pw.print(KEY_EJ_LIMIT_SPECIAL_ADDITION_MS, EJ_LIMIT_SPECIAL_ADDITION_MS).println();
            pw.print(KEY_EJ_WINDOW_SIZE_MS, EJ_WINDOW_SIZE_MS).println();
            pw.print(KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS, EJ_TOP_APP_TIME_CHUNK_SIZE_MS).println();
            pw.print(KEY_EJ_REWARD_TOP_APP_MS, EJ_REWARD_TOP_APP_MS).println();
            pw.print(KEY_EJ_REWARD_INTERACTION_MS, EJ_REWARD_INTERACTION_MS).println();
            pw.print(KEY_EJ_REWARD_NOTIFICATION_SEEN_MS, EJ_REWARD_NOTIFICATION_SEEN_MS).println();
            pw.print(KEY_EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS,
                    EJ_TEMP_ALLOWLIST_GRACE_PERIOD_MS).println();

            pw.decreaseIndent();
        }

        private void dump(ProtoOutputStream proto) {
            final long qcToken = proto.start(ConstantsProto.QUOTA_CONTROLLER);
            proto.write(ConstantsProto.QuotaController.ALLOWED_TIME_PER_PERIOD_MS,
                    ALLOWED_TIME_PER_PERIOD_MS);
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
    @NonNull
    long[] getEJLimitsMs() {
        return mEJLimitsMs;
    }

    @VisibleForTesting
    long getEjLimitSpecialAdditionMs() {
        return mEjLimitSpecialAdditionMs;
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
    long getEJTempAllowlistGracePeriodMs() {
        return mEJTempAllowlistGracePeriodMs;
    }

    @VisibleForTesting
    @Nullable
    List<TimingSession> getEJTimingSessions(int userId, String packageName) {
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
        return mInQuotaAlarmListener.mMinQuotaCheckDelayMs;
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
    List<TimingSession> getTimingSessions(int userId, String packageName) {
        return mTimingSessions.get(userId, packageName);
    }

    @VisibleForTesting
    @NonNull
    QcConstants getQcConstants() {
        return mQcConstants;
    }

    //////////////////////////// DATA DUMP //////////////////////////////

    @Override
    public void dumpControllerStateLocked(final IndentingPrintWriter pw,
            final Predicate<JobStatus> predicate) {
        pw.println("Is charging: " + mChargeTracker.isCharging());
        pw.println("Current elapsed time: " + sElapsedRealtimeClock.millis());
        pw.println();

        pw.print("Foreground UIDs: ");
        pw.println(mForegroundUids.toString());
        pw.println();

        pw.println("Cached UID->package map:");
        pw.increaseIndent();
        for (int i = 0; i < mUidToPackageCache.size(); ++i) {
            final int uid = mUidToPackageCache.keyAt(i);
            pw.print(uid);
            pw.print(": ");
            pw.println(mUidToPackageCache.get(uid));
        }
        pw.decreaseIndent();
        pw.println();

        pw.print("Cached temp allowlist: ");
        pw.println(mTempAllowlistCache.toString());
        pw.print("Cached temp allowlist grace period: ");
        pw.println(mTempAllowlistGraceCache.toString());

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

        pw.println();
        for (int u = 0; u < mEJPkgTimers.numMaps(); ++u) {
            final int userId = mEJPkgTimers.keyAt(u);
            for (int p = 0; p < mEJPkgTimers.numElementsForKey(userId); ++p) {
                final String pkgName = mEJPkgTimers.keyAt(u, p);
                mEJPkgTimers.valueAt(u, p).dump(pw, predicate);
                pw.println();
                List<TimingSession> sessions = mEJTimingSessions.get(userId, pkgName);
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

                pw.println(string(userId, pkgName));
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

                pw.print(string(userId, pkgName));
                pw.print(": ");
                debits.dumpLocked(pw);
            }
        }
        pw.decreaseIndent();

        pw.println();
        mInQuotaAlarmListener.dumpLocked(pw);
        pw.decreaseIndent();
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.QUOTA);

        proto.write(StateControllerProto.QuotaController.IS_CHARGING, mChargeTracker.isCharging());
        proto.write(StateControllerProto.QuotaController.ELAPSED_REALTIME,
                sElapsedRealtimeClock.millis());

        for (int i = 0; i < mForegroundUids.size(); ++i) {
            proto.write(StateControllerProto.QuotaController.FOREGROUND_UIDS,
                    mForegroundUids.keyAt(i));
        }

        for (int i = 0; i < mUidToPackageCache.size(); ++i) {
            final long upToken = proto.start(
                    StateControllerProto.QuotaController.UID_TO_PACKAGE_CACHE);

            final int uid = mUidToPackageCache.keyAt(i);
            ArraySet<String> packages = mUidToPackageCache.get(uid);

            proto.write(StateControllerProto.QuotaController.UidPackageMapping.UID, uid);
            for (int j = 0; j < packages.size(); ++j) {
                proto.write(StateControllerProto.QuotaController.UidPackageMapping.PACKAGE_NAMES,
                        packages.valueAt(j));
            }

            proto.end(upToken);
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
                        js.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_EXPEDITED_QUOTA));
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

                List<TimingSession> sessions = mTimingSessions.get(userId, pkgName);
                if (sessions != null) {
                    for (int j = sessions.size() - 1; j >= 0; j--) {
                        TimingSession session = sessions.get(j);
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

        mInQuotaAlarmListener.dumpLocked(proto,
                StateControllerProto.QuotaController.IN_QUOTA_ALARM_LISTENER);

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
