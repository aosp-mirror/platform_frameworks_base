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

package com.android.server.job;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.util.DataUnit.GIGABYTES;

import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.BackgroundStartPrivileges;
import android.app.UserSwitchObserver;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Pools;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.StatLogger;
import com.android.modules.expresslog.Histogram;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.LocalServices;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;
import com.android.server.job.restrictions.JobRestriction;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * This class decides, given the various configuration and the system status, which jobs can start
 * and which {@link JobServiceContext} to run each job on.
 */
class JobConcurrencyManager {
    private static final String TAG = JobSchedulerService.TAG + ".Concurrency";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    /** The maximum number of concurrent jobs we'll aim to run at one time. */
    @VisibleForTesting
    static final int MAX_CONCURRENCY_LIMIT = 64;
    /** The maximum number of objects we should retain in memory when not in use. */
    private static final int MAX_RETAINED_OBJECTS = (int) (1.5 * MAX_CONCURRENCY_LIMIT);

    static final String CONFIG_KEY_PREFIX_CONCURRENCY = "concurrency_";
    private static final String KEY_CONCURRENCY_LIMIT = CONFIG_KEY_PREFIX_CONCURRENCY + "limit";
    @VisibleForTesting
    static final int DEFAULT_CONCURRENCY_LIMIT;

    static {
        if (ActivityManager.isLowRamDeviceStatic()) {
            DEFAULT_CONCURRENCY_LIMIT = 8;
        } else {
            final long ramBytes = new MemInfoReader().getTotalSize();
            if (ramBytes <= GIGABYTES.toBytes(6)) {
                DEFAULT_CONCURRENCY_LIMIT = 16;
            } else if (ramBytes <= GIGABYTES.toBytes(8)) {
                DEFAULT_CONCURRENCY_LIMIT = 20;
            } else if (ramBytes <= GIGABYTES.toBytes(12)) {
                DEFAULT_CONCURRENCY_LIMIT = 32;
            } else {
                DEFAULT_CONCURRENCY_LIMIT = 40;
            }
        }
    }

    private static final String KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS =
            CONFIG_KEY_PREFIX_CONCURRENCY + "screen_off_adjustment_delay_ms";
    private static final long DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS = 30_000;
    @VisibleForTesting
    static final String KEY_PKG_CONCURRENCY_LIMIT_EJ =
            CONFIG_KEY_PREFIX_CONCURRENCY + "pkg_concurrency_limit_ej";
    private static final int DEFAULT_PKG_CONCURRENCY_LIMIT_EJ = 3;
    @VisibleForTesting
    static final String KEY_PKG_CONCURRENCY_LIMIT_REGULAR =
            CONFIG_KEY_PREFIX_CONCURRENCY + "pkg_concurrency_limit_regular";
    private static final int DEFAULT_PKG_CONCURRENCY_LIMIT_REGULAR = DEFAULT_CONCURRENCY_LIMIT / 2;
    @VisibleForTesting
    static final String KEY_ENABLE_MAX_WAIT_TIME_BYPASS =
            CONFIG_KEY_PREFIX_CONCURRENCY + "enable_max_wait_time_bypass";
    private static final boolean DEFAULT_ENABLE_MAX_WAIT_TIME_BYPASS = true;
    @VisibleForTesting
    static final String KEY_MAX_WAIT_UI_MS = CONFIG_KEY_PREFIX_CONCURRENCY + "max_wait_ui_ms";
    @VisibleForTesting
    static final long DEFAULT_MAX_WAIT_UI_MS = 5 * MINUTE_IN_MILLIS;
    private static final String KEY_MAX_WAIT_EJ_MS =
            CONFIG_KEY_PREFIX_CONCURRENCY + "max_wait_ej_ms";
    @VisibleForTesting
    static final long DEFAULT_MAX_WAIT_EJ_MS = 5 * MINUTE_IN_MILLIS;
    private static final String KEY_MAX_WAIT_REGULAR_MS =
            CONFIG_KEY_PREFIX_CONCURRENCY + "max_wait_regular_ms";
    @VisibleForTesting
    static final long DEFAULT_MAX_WAIT_REGULAR_MS = 30 * MINUTE_IN_MILLIS;

    /**
     * Set of possible execution types that a job can have. The actual type(s) of a job are based
     * on the {@link JobStatus#lastEvaluatedBias}, which is typically evaluated right before
     * execution (when we're trying to determine which jobs to run next) and won't change after the
     * job has started executing.
     *
     * Try to give higher priority types lower values.
     *
     * @see #getJobWorkTypes(JobStatus)
     */

    /** Job shouldn't run or qualify as any other work type. */
    static final int WORK_TYPE_NONE = 0;
    /** The job is for an app in the TOP state for a currently active user. */
    static final int WORK_TYPE_TOP = 1 << 0;
    /**
     * The job is for an app in a {@link ActivityManager#PROCESS_STATE_FOREGROUND_SERVICE} or higher
     * state (excluding {@link ActivityManager#PROCESS_STATE_TOP} for a currently active user.
     */
    static final int WORK_TYPE_FGS = 1 << 1;
    /** The job is allowed to run as a user-initiated job for a currently active user. */
    static final int WORK_TYPE_UI = 1 << 2;
    /** The job is allowed to run as an expedited job for a currently active user. */
    static final int WORK_TYPE_EJ = 1 << 3;
    /**
     * The job does not satisfy any of the conditions for {@link #WORK_TYPE_TOP},
     * {@link #WORK_TYPE_FGS}, or {@link #WORK_TYPE_EJ}, but is for a currently active user, so
     * can run as a background job.
     */
    static final int WORK_TYPE_BG = 1 << 4;
    /**
     * The job is for an app in a {@link ActivityManager#PROCESS_STATE_FOREGROUND_SERVICE} or higher
     * state, or is allowed to run as an expedited or user-initiated job,
     * but is for a completely background user.
     */
    static final int WORK_TYPE_BGUSER_IMPORTANT = 1 << 5;
    /**
     * The job does not satisfy any of the conditions for {@link #WORK_TYPE_TOP},
     * {@link #WORK_TYPE_FGS}, or {@link #WORK_TYPE_EJ}, but is for a completely background user,
     * so can run as a background user job.
     */
    static final int WORK_TYPE_BGUSER = 1 << 6;
    @VisibleForTesting
    static final int NUM_WORK_TYPES = 7;
    private static final int ALL_WORK_TYPES = (1 << NUM_WORK_TYPES) - 1;

    @IntDef(prefix = {"WORK_TYPE_"}, flag = true, value = {
            WORK_TYPE_NONE,
            WORK_TYPE_TOP,
            WORK_TYPE_FGS,
            WORK_TYPE_UI,
            WORK_TYPE_EJ,
            WORK_TYPE_BG,
            WORK_TYPE_BGUSER_IMPORTANT,
            WORK_TYPE_BGUSER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WorkType {
    }

    @VisibleForTesting
    static String workTypeToString(@WorkType int workType) {
        switch (workType) {
            case WORK_TYPE_NONE:
                return "NONE";
            case WORK_TYPE_TOP:
                return "TOP";
            case WORK_TYPE_FGS:
                return "FGS";
            case WORK_TYPE_UI:
                return "UI";
            case WORK_TYPE_EJ:
                return "EJ";
            case WORK_TYPE_BG:
                return "BG";
            case WORK_TYPE_BGUSER:
                return "BGUSER";
            case WORK_TYPE_BGUSER_IMPORTANT:
                return "BGUSER_IMPORTANT";
            default:
                return "WORK(" + workType + ")";
        }
    }

    private final Object mLock;
    private final JobNotificationCoordinator mNotificationCoordinator;
    private final JobSchedulerService mService;
    private final Context mContext;
    private final Handler mHandler;
    private final Injector mInjector;

    private PowerManager mPowerManager;

    private boolean mCurrentInteractiveState;
    private boolean mEffectiveInteractiveState;

    private long mLastScreenOnRealtime;
    private long mLastScreenOffRealtime;

    private static final WorkConfigLimitsPerMemoryTrimLevel CONFIG_LIMITS_SCREEN_ON =
            new WorkConfigLimitsPerMemoryTrimLevel(
                    new WorkTypeConfig("screen_on_normal", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT * 3 / 4,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .4f),
                                    Pair.create(WORK_TYPE_FGS, .2f),
                                    Pair.create(WORK_TYPE_UI, .1f),
                                    Pair.create(WORK_TYPE_EJ, .1f), Pair.create(WORK_TYPE_BG, .05f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .05f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, .5f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .25f),
                                    Pair.create(WORK_TYPE_BGUSER, .2f))
                    ),
                    new WorkTypeConfig("screen_on_moderate", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT / 2,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .4f),
                                    Pair.create(WORK_TYPE_FGS, .1f),
                                    Pair.create(WORK_TYPE_UI, .1f),
                                    Pair.create(WORK_TYPE_EJ, .1f), Pair.create(WORK_TYPE_BG, .1f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .1f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, .4f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .1f),
                                    Pair.create(WORK_TYPE_BGUSER, .1f))
                    ),
                    new WorkTypeConfig("screen_on_low", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT * 4 / 10,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .6f),
                                    Pair.create(WORK_TYPE_FGS, .1f),
                                    Pair.create(WORK_TYPE_UI, .1f),
                                    Pair.create(WORK_TYPE_EJ, .1f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1.0f / 3),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1.0f / 6),
                                    Pair.create(WORK_TYPE_BGUSER, 1.0f / 6))
                    ),
                    new WorkTypeConfig("screen_on_critical", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT * 4 / 10,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .7f),
                                    Pair.create(WORK_TYPE_FGS, .1f),
                                    Pair.create(WORK_TYPE_UI, .1f),
                                    Pair.create(WORK_TYPE_EJ, .05f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1.0f / 6),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1.0f / 6),
                                    Pair.create(WORK_TYPE_BGUSER, 1.0f / 6))
                    )
            );
    private static final WorkConfigLimitsPerMemoryTrimLevel CONFIG_LIMITS_SCREEN_OFF =
            new WorkConfigLimitsPerMemoryTrimLevel(
                    new WorkTypeConfig("screen_off_normal", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .3f),
                                    Pair.create(WORK_TYPE_FGS, .2f),
                                    Pair.create(WORK_TYPE_UI, .2f),
                                    Pair.create(WORK_TYPE_EJ, .15f), Pair.create(WORK_TYPE_BG, .1f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .05f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, .6f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .2f),
                                    Pair.create(WORK_TYPE_BGUSER, .2f))
                    ),
                    new WorkTypeConfig("screen_off_moderate", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT * 9 / 10,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .3f),
                                    Pair.create(WORK_TYPE_FGS, .2f),
                                    Pair.create(WORK_TYPE_UI, .2f),
                                    Pair.create(WORK_TYPE_EJ, .15f), Pair.create(WORK_TYPE_BG, .1f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .05f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, .5f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .1f),
                                    Pair.create(WORK_TYPE_BGUSER, .1f))
                    ),
                    new WorkTypeConfig("screen_off_low", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT * 6 / 10,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .3f),
                                    Pair.create(WORK_TYPE_FGS, .15f),
                                    Pair.create(WORK_TYPE_UI, .15f),
                                    Pair.create(WORK_TYPE_EJ, .1f), Pair.create(WORK_TYPE_BG, .05f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .05f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, .25f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .1f),
                                    Pair.create(WORK_TYPE_BGUSER, .1f))
                    ),
                    new WorkTypeConfig("screen_off_critical", DEFAULT_CONCURRENCY_LIMIT,
                            /* defaultMaxTotal */  DEFAULT_CONCURRENCY_LIMIT * 4 / 10,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, .3f),
                                    Pair.create(WORK_TYPE_FGS, .1f),
                                    Pair.create(WORK_TYPE_UI, .1f),
                                    Pair.create(WORK_TYPE_EJ, .05f)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, .1f),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, .1f),
                                    Pair.create(WORK_TYPE_BGUSER, .1f))
                    )
            );

    /**
     * Comparator to sort the determination lists, putting the ContextAssignments that we most
     * prefer to use at the end of the list.
     */
    private static final Comparator<ContextAssignment> sDeterminationComparator = (ca1, ca2) -> {
        if (ca1 == ca2) {
            return 0;
        }
        final JobStatus js1 = ca1.context.getRunningJobLocked();
        final JobStatus js2 = ca2.context.getRunningJobLocked();
        // Prefer using an empty context over one with a running job.
        if (js1 == null) {
            if (js2 == null) {
                return 0;
            }
            return 1;
        } else if (js2 == null) {
            return -1;
        }
        // We would prefer to replace bg jobs over TOP jobs.
        if (js1.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
            if (js2.lastEvaluatedBias != JobInfo.BIAS_TOP_APP) {
                return -1;
            }
        } else if (js2.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
            return 1;
        }
        // Prefer replacing the job that has been running the longest.
        return Long.compare(
                ca2.context.getExecutionStartTimeElapsed(),
                ca1.context.getExecutionStartTimeElapsed());
    };

    // We reuse the lists to avoid GC churn.
    private final ArraySet<ContextAssignment> mRecycledChanged = new ArraySet<>();
    private final ArraySet<ContextAssignment> mRecycledIdle = new ArraySet<>();
    private final ArrayList<ContextAssignment> mRecycledPreferredUidOnly = new ArrayList<>();
    private final ArrayList<ContextAssignment> mRecycledStoppable = new ArrayList<>();
    private final AssignmentInfo mRecycledAssignmentInfo = new AssignmentInfo();
    private final SparseIntArray mRecycledPrivilegedState = new SparseIntArray();

    private static final int PRIVILEGED_STATE_UNDEFINED = 0;
    private static final int PRIVILEGED_STATE_NONE = 1;
    private static final int PRIVILEGED_STATE_BAL = 2;
    private static final int PRIVILEGED_STATE_TOP = 3;

    private final Pools.Pool<ContextAssignment> mContextAssignmentPool =
            new Pools.SimplePool<>(MAX_RETAINED_OBJECTS);

    /**
     * Set of JobServiceContexts that are actively running jobs.
     */
    final List<JobServiceContext> mActiveServices = new ArrayList<>();

    /** Set of JobServiceContexts that aren't currently running any jobs. */
    private final ArraySet<JobServiceContext> mIdleContexts = new ArraySet<>();

    private int mNumDroppedContexts = 0;

    private final ArraySet<JobStatus> mRunningJobs = new ArraySet<>();

    private final WorkCountTracker mWorkCountTracker = new WorkCountTracker();

    private final Pools.Pool<PackageStats> mPkgStatsPool =
            new Pools.SimplePool<>(MAX_RETAINED_OBJECTS);

    private final SparseArrayMap<String, PackageStats> mActivePkgStats = new SparseArrayMap<>();

    private WorkTypeConfig mWorkTypeConfig = CONFIG_LIMITS_SCREEN_OFF.normal;

    /** Wait for this long after screen off before adjusting the job concurrency. */
    private long mScreenOffAdjustmentDelayMs = DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS;

    /**
     * The maximum number of jobs we'll attempt to have running at one time. This may occasionally
     * be exceeded based on other factors.
     */
    private int mSteadyStateConcurrencyLimit = DEFAULT_CONCURRENCY_LIMIT;

    /**
     * The maximum number of expedited jobs a single userId-package can have running simultaneously.
     * TOP apps are not limited.
     */
    private int mPkgConcurrencyLimitEj = DEFAULT_PKG_CONCURRENCY_LIMIT_EJ;

    /**
     * The maximum number of regular jobs a single userId-package can have running simultaneously.
     * TOP apps are not limited.
     */
    private int mPkgConcurrencyLimitRegular = DEFAULT_PKG_CONCURRENCY_LIMIT_REGULAR;

    private boolean mMaxWaitTimeBypassEnabled = DEFAULT_ENABLE_MAX_WAIT_TIME_BYPASS;

    /**
     * The maximum time a user-initiated job would have to be potentially waiting for an available
     * slot before we would consider creating a new slot for it.
     */
    private long mMaxWaitUIMs = DEFAULT_MAX_WAIT_UI_MS;

    /**
     * The maximum time an expedited job would have to be potentially waiting for an available
     * slot before we would consider creating a new slot for it.
     */
    private long mMaxWaitEjMs = DEFAULT_MAX_WAIT_EJ_MS;

    /**
     * The maximum time a regular job would have to be potentially waiting for an available
     * slot before we would consider creating a new slot for it.
     */
    private long mMaxWaitRegularMs = DEFAULT_MAX_WAIT_REGULAR_MS;

    /** Current memory trim level. */
    private int mLastMemoryTrimLevel;

    /** Used to throttle heavy API calls. */
    private long mNextSystemStateRefreshTime;
    private static final int SYSTEM_STATE_REFRESH_MIN_INTERVAL = 1000;

    private final Consumer<PackageStats> mPackageStatsStagingCountClearer =
            PackageStats::resetStagedCount;

    private static final Histogram sConcurrencyHistogramLogger = new Histogram(
            "job_scheduler.value_hist_job_concurrency",
            // Create a histogram that expects values in the range [0, 99].
            // Include more buckets than MAX_CONCURRENCY_LIMIT to account for/identify the cases
            // where we may create additional slots for TOP-started EJs and UIJs
            new Histogram.UniformOptions(100, 0, 99));

    private final StatLogger mStatLogger = new StatLogger(new String[]{
            "assignJobsToContexts",
            "refreshSystemState",
    });
    @VisibleForTesting
    GracePeriodObserver mGracePeriodObserver;
    @VisibleForTesting
    boolean mShouldRestrictBgUser;

    interface Stats {
        int ASSIGN_JOBS_TO_CONTEXTS = 0;
        int REFRESH_SYSTEM_STATE = 1;

        int COUNT = REFRESH_SYSTEM_STATE + 1;
    }

    JobConcurrencyManager(JobSchedulerService service) {
        this(service, new Injector());
    }

    @VisibleForTesting
    JobConcurrencyManager(JobSchedulerService service, Injector injector) {
        mService = service;
        mLock = mService.getLock();
        mContext = service.getTestableContext();
        mInjector = injector;
        mNotificationCoordinator = new JobNotificationCoordinator();

        mHandler = AppSchedulingModuleThread.getHandler();

        mGracePeriodObserver = new GracePeriodObserver(mContext);
        mShouldRestrictBgUser = mContext.getResources().getBoolean(
                R.bool.config_jobSchedulerRestrictBackgroundUser);
    }

    public void onSystemReady() {
        mPowerManager = mContext.getSystemService(PowerManager.class);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        try {
            ActivityManager.getService().registerUserSwitchObserver(mGracePeriodObserver, TAG);
        } catch (RemoteException e) {
        }

        onInteractiveStateChanged(mPowerManager.isInteractive());
    }

    /**
     * Called when the boot phase reaches
     * {@link com.android.server.SystemService#PHASE_THIRD_PARTY_APPS_CAN_START}.
     */
    void onThirdPartyAppsCanStart() {
        final IBatteryStats batteryStats = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
        for (int i = 0; i < mSteadyStateConcurrencyLimit; ++i) {
            mIdleContexts.add(
                    mInjector.createJobServiceContext(mService, this,
                            mNotificationCoordinator, batteryStats,
                            mService.mJobPackageTracker,
                            AppSchedulingModuleThread.get().getLooper()));
        }
    }

    @GuardedBy("mLock")
    void onAppRemovedLocked(String pkgName, int uid) {
        final PackageStats packageStats = mActivePkgStats.get(UserHandle.getUserId(uid), pkgName);
        if (packageStats != null) {
            if (packageStats.numRunningEj > 0 || packageStats.numRunningRegular > 0) {
                // Don't delete the object just yet. We'll remove it in onJobCompleted() when the
                // jobs officially stop running.
                Slog.w(TAG,
                        pkgName + "(" + uid + ") marked as removed before jobs stopped running");
            } else {
                mActivePkgStats.delete(UserHandle.getUserId(uid), pkgName);
            }
        }
    }

    void onUserRemoved(int userId) {
        mGracePeriodObserver.onUserRemoved(userId);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON:
                    onInteractiveStateChanged(true);
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    onInteractiveStateChanged(false);
                    break;
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    if (mPowerManager != null && mPowerManager.isDeviceIdleMode()) {
                        synchronized (mLock) {
                            stopUnexemptedJobsForDoze();
                            stopOvertimeJobsLocked("deep doze");
                        }
                    }
                    break;
                case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                    if (mPowerManager != null && mPowerManager.isPowerSaveMode()) {
                        synchronized (mLock) {
                            stopOvertimeJobsLocked("battery saver");
                        }
                    }
                    break;
            }
        }
    };

    /**
     * Called when the screen turns on / off.
     */
    private void onInteractiveStateChanged(boolean interactive) {
        synchronized (mLock) {
            if (mCurrentInteractiveState == interactive) {
                return;
            }
            mCurrentInteractiveState = interactive;
            if (DEBUG) {
                Slog.d(TAG, "Interactive: " + interactive);
            }

            final long nowRealtime = sElapsedRealtimeClock.millis();
            if (interactive) {
                mLastScreenOnRealtime = nowRealtime;
                mEffectiveInteractiveState = true;

                mHandler.removeCallbacks(mRampUpForScreenOff);
            } else {
                mLastScreenOffRealtime = nowRealtime;

                // Set mEffectiveInteractiveState to false after the delay, when we may increase
                // the concurrency.
                // We don't need a wakeup alarm here. When there's a pending job, there should
                // also be jobs running too, meaning the device should be awake.

                // Note: we can't directly do postDelayed(this::rampUpForScreenOn), because
                // we need the exact same instance for removeCallbacks().
                mHandler.postDelayed(mRampUpForScreenOff, mScreenOffAdjustmentDelayMs);
            }
        }
    }

    private final Runnable mRampUpForScreenOff = this::rampUpForScreenOff;

    /**
     * Called in {@link #mScreenOffAdjustmentDelayMs} after
     * the screen turns off, in order to increase concurrency.
     */
    private void rampUpForScreenOff() {
        synchronized (mLock) {
            // Make sure the screen has really been off for the configured duration.
            // (There could be a race.)
            if (!mEffectiveInteractiveState) {
                return;
            }
            if (mLastScreenOnRealtime > mLastScreenOffRealtime) {
                return;
            }
            final long now = sElapsedRealtimeClock.millis();
            if ((mLastScreenOffRealtime + mScreenOffAdjustmentDelayMs) > now) {
                return;
            }

            mEffectiveInteractiveState = false;

            if (DEBUG) {
                Slog.d(TAG, "Ramping up concurrency");
            }

            mService.maybeRunPendingJobsLocked();
        }
    }

    @GuardedBy("mLock")
    ArraySet<JobStatus> getRunningJobsLocked() {
        return mRunningJobs;
    }

    @GuardedBy("mLock")
    boolean isJobRunningLocked(JobStatus job) {
        return mRunningJobs.contains(job);
    }

    /**
     * Return {@code true} if the specified job has been executing for longer than the minimum
     * execution guarantee.
     */
    @GuardedBy("mLock")
    boolean isJobInOvertimeLocked(@NonNull JobStatus job) {
        if (!mRunningJobs.contains(job)) {
            return false;
        }

        for (int i = mActiveServices.size() - 1; i >= 0; --i) {
            final JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus jobStatus = jsc.getRunningJobLocked();

            if (jobStatus == job) {
                return !jsc.isWithinExecutionGuaranteeTime();
            }
        }

        Slog.wtf(TAG, "Couldn't find long running job on a context");
        mRunningJobs.remove(job);
        return false;
    }

    /**
     * Returns true if a job that is "similar" to the provided job is currently running.
     * "Similar" in this context means any job that the {@link JobStore} would consider equivalent
     * and replace one with the other.
     */
    @GuardedBy("mLock")
    private boolean isSimilarJobRunningLocked(JobStatus job) {
        for (int i = mRunningJobs.size() - 1; i >= 0; --i) {
            JobStatus js = mRunningJobs.valueAt(i);
            if (job.matches(js.getUid(), js.getNamespace(), js.getJobId())) {
                return true;
            }
        }
        return false;
    }

    /** Return {@code true} if the state was updated. */
    @GuardedBy("mLock")
    private boolean refreshSystemStateLocked() {
        final long nowUptime = JobSchedulerService.sUptimeMillisClock.millis();

        // Only refresh the information every so often.
        if (nowUptime < mNextSystemStateRefreshTime) {
            return false;
        }

        final long start = mStatLogger.getTime();
        mNextSystemStateRefreshTime = nowUptime + SYSTEM_STATE_REFRESH_MIN_INTERVAL;

        mLastMemoryTrimLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        try {
            mLastMemoryTrimLevel = ActivityManager.getService().getMemoryTrimLevel();
        } catch (RemoteException e) {
        }

        mStatLogger.logDurationStat(Stats.REFRESH_SYSTEM_STATE, start);
        return true;
    }

    @GuardedBy("mLock")
    private void updateCounterConfigLocked() {
        if (!refreshSystemStateLocked()) {
            return;
        }

        final WorkConfigLimitsPerMemoryTrimLevel workConfigs = mEffectiveInteractiveState
                ? CONFIG_LIMITS_SCREEN_ON : CONFIG_LIMITS_SCREEN_OFF;

        switch (mLastMemoryTrimLevel) {
            case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                mWorkTypeConfig = workConfigs.moderate;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_LOW:
                mWorkTypeConfig = workConfigs.low;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                mWorkTypeConfig = workConfigs.critical;
                break;
            default:
                mWorkTypeConfig = workConfigs.normal;
                break;
        }

        mWorkCountTracker.setConfig(mWorkTypeConfig);
    }

    /**
     * Takes jobs from pending queue and runs them on available contexts.
     * If no contexts are available, preempts lower bias jobs to run higher bias ones.
     * Lock on mLock before calling this function.
     */
    @GuardedBy("mLock")
    void assignJobsToContextsLocked() {
        final long start = mStatLogger.getTime();

        assignJobsToContextsInternalLocked();

        mStatLogger.logDurationStat(Stats.ASSIGN_JOBS_TO_CONTEXTS, start);
    }

    @GuardedBy("mLock")
    private void assignJobsToContextsInternalLocked() {
        if (DEBUG) {
            Slog.d(TAG, printPendingQueueLocked());
        }

        if (mService.getPendingJobQueue().size() == 0) {
            // Nothing to do.
            return;
        }

        prepareForAssignmentDeterminationLocked(
                mRecycledIdle, mRecycledPreferredUidOnly, mRecycledStoppable,
                mRecycledAssignmentInfo);

        if (DEBUG) {
            Slog.d(TAG, printAssignments("running jobs initial",
                    mRecycledStoppable, mRecycledPreferredUidOnly));
        }

        determineAssignmentsLocked(
                mRecycledChanged, mRecycledIdle, mRecycledPreferredUidOnly, mRecycledStoppable,
                mRecycledAssignmentInfo);

        if (DEBUG) {
            Slog.d(TAG, printAssignments("running jobs final",
                    mRecycledStoppable, mRecycledPreferredUidOnly, mRecycledChanged));

            Slog.d(TAG, "work count results: " + mWorkCountTracker);
        }

        carryOutAssignmentChangesLocked(mRecycledChanged);

        cleanUpAfterAssignmentChangesLocked(
                mRecycledChanged, mRecycledIdle, mRecycledPreferredUidOnly, mRecycledStoppable,
                mRecycledAssignmentInfo, mRecycledPrivilegedState);

        noteConcurrency();
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void prepareForAssignmentDeterminationLocked(final ArraySet<ContextAssignment> idle,
            final List<ContextAssignment> preferredUidOnly,
            final List<ContextAssignment> stoppable,
            final AssignmentInfo info) {
        final PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        final List<JobServiceContext> activeServices = mActiveServices;

        updateCounterConfigLocked();
        // Reset everything since we'll re-evaluate the current state.
        mWorkCountTracker.resetCounts();

        // Update the priorities of jobs that aren't running, and also count the pending work types.
        // Do this before the following loop to hopefully reduce the cost of
        // shouldStopRunningJobLocked().
        updateNonRunningPrioritiesLocked(pendingJobQueue, true);

        final int numRunningJobs = activeServices.size();
        final long nowElapsed = sElapsedRealtimeClock.millis();
        long minPreferredUidOnlyWaitingTimeMs = Long.MAX_VALUE;
        for (int i = 0; i < numRunningJobs; ++i) {
            final JobServiceContext jsc = activeServices.get(i);
            final JobStatus js = jsc.getRunningJobLocked();

            ContextAssignment assignment = mContextAssignmentPool.acquire();
            if (assignment == null) {
                assignment = new ContextAssignment();
            }

            assignment.context = jsc;

            if (js != null) {
                mWorkCountTracker.incrementRunningJobCount(jsc.getRunningJobWorkType());
                assignment.workType = jsc.getRunningJobWorkType();
                if (js.startedWithImmediacyPrivilege) {
                    info.numRunningImmediacyPrivileged++;
                }
                if (js.shouldTreatAsUserInitiatedJob()) {
                    info.numRunningUi++;
                } else if (js.startedAsExpeditedJob) {
                    info.numRunningEj++;
                } else {
                    info.numRunningReg++;
                }
            }

            assignment.preferredUid = jsc.getPreferredUid();
            if ((assignment.shouldStopJobReason = shouldStopRunningJobLocked(jsc)) != null) {
                stoppable.add(assignment);
            } else {
                assignment.timeUntilStoppableMs = jsc.getRemainingGuaranteedTimeMs(nowElapsed);
                minPreferredUidOnlyWaitingTimeMs =
                        Math.min(minPreferredUidOnlyWaitingTimeMs, assignment.timeUntilStoppableMs);
                preferredUidOnly.add(assignment);
            }
        }
        preferredUidOnly.sort(sDeterminationComparator);
        stoppable.sort(sDeterminationComparator);
        for (int i = numRunningJobs; i < mSteadyStateConcurrencyLimit; ++i) {
            final JobServiceContext jsc;
            final int numIdleContexts = mIdleContexts.size();
            if (numIdleContexts > 0) {
                jsc = mIdleContexts.removeAt(numIdleContexts - 1);
            } else {
                // This could happen if the config is changed at runtime.
                Slog.w(TAG, "Had fewer than " + mSteadyStateConcurrencyLimit + " in existence");
                jsc = createNewJobServiceContext();
            }

            ContextAssignment assignment = mContextAssignmentPool.acquire();
            if (assignment == null) {
                assignment = new ContextAssignment();
            }

            assignment.context = jsc;
            idle.add(assignment);
        }

        mWorkCountTracker.onCountDone();
        // Set 0 if there were no preferred UID only contexts to indicate no waiting time due
        // to such jobs.
        info.minPreferredUidOnlyWaitingTimeMs =
                minPreferredUidOnlyWaitingTimeMs == Long.MAX_VALUE
                        ? 0 : minPreferredUidOnlyWaitingTimeMs;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void determineAssignmentsLocked(final ArraySet<ContextAssignment> changed,
            final ArraySet<ContextAssignment> idle,
            final List<ContextAssignment> preferredUidOnly,
            final List<ContextAssignment> stoppable,
            @NonNull AssignmentInfo info) {
        final PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        final List<JobServiceContext> activeServices = mActiveServices;
        pendingJobQueue.resetIterator();
        JobStatus nextPending;
        int projectedRunningCount = activeServices.size();
        long minChangedWaitingTimeMs = Long.MAX_VALUE;
        // Only allow the Context creation bypass for each type if one of that type isn't already
        // running. That way, we don't run into issues (creating too many additional contexts)
        // if new jobs become ready to run in rapid succession and we end up going through this
        // loop many times before running jobs have had a decent chance to finish.
        boolean allowMaxWaitContextBypassUi = info.numRunningUi == 0;
        boolean allowMaxWaitContextBypassEj = info.numRunningEj == 0;
        boolean allowMaxWaitContextBypassOthers = info.numRunningReg == 0;
        while ((nextPending = pendingJobQueue.next()) != null) {
            if (mRunningJobs.contains(nextPending)) {
                // Should never happen.
                Slog.wtf(TAG, "Pending queue contained a running job");
                if (DEBUG) {
                    Slog.e(TAG, "Pending+running job: " + nextPending);
                }
                pendingJobQueue.remove(nextPending);
                continue;
            }

            final boolean hasImmediacyPrivilege =
                    hasImmediacyPrivilegeLocked(nextPending, mRecycledPrivilegedState);
            if (DEBUG && isSimilarJobRunningLocked(nextPending)) {
                Slog.w(TAG, "Already running similar job to: " + nextPending);
            }

            // Factoring minChangedWaitingTimeMs into the min waiting time effectively limits
            // the number of additional contexts that are created due to long waiting times.
            // By factoring it in, we imply that the new slot will be available for other
            // pending jobs that could be designated as waiting too long, and those other jobs
            // would only have to wait for the new slots to become available.
            final long minWaitingTimeMs =
                    Math.min(info.minPreferredUidOnlyWaitingTimeMs, minChangedWaitingTimeMs);

            // Find an available slot for nextPending. The context should be one of the following:
            // 1. Unused
            // 2. Its job should have used up its minimum execution guarantee so it
            // 3. Its job should have the lowest bias among all running jobs (sharing the same UID
            //    as nextPending)
            ContextAssignment selectedContext = null;
            final int allWorkTypes = getJobWorkTypes(nextPending);
            final boolean pkgConcurrencyOkay = !isPkgConcurrencyLimitedLocked(nextPending);
            final boolean isInOverage = projectedRunningCount > mSteadyStateConcurrencyLimit;
            boolean startingJob = false;
            if (idle.size() > 0) {
                final int idx = idle.size() - 1;
                final ContextAssignment assignment = idle.valueAt(idx);
                final boolean preferredUidOkay = (assignment.preferredUid == nextPending.getUid())
                        || (assignment.preferredUid == JobServiceContext.NO_PREFERRED_UID);
                int workType = mWorkCountTracker.canJobStart(allWorkTypes);
                if (preferredUidOkay && pkgConcurrencyOkay && workType != WORK_TYPE_NONE) {
                    // This slot is free, and we haven't yet hit the limit on
                    // concurrent jobs...  we can just throw the job in to here.
                    selectedContext = assignment;
                    startingJob = true;
                    idle.removeAt(idx);
                    assignment.newJob = nextPending;
                    assignment.newWorkType = workType;
                }
            }
            if (selectedContext == null && stoppable.size() > 0) {
                for (int s = stoppable.size() - 1; s >= 0; --s) {
                    final ContextAssignment assignment = stoppable.get(s);
                    final JobStatus runningJob = assignment.context.getRunningJobLocked();
                    // Maybe stop the job if it has had its day in the sun. Only allow replacing
                    // for one of the following conditions:
                    // 1. We're putting in a job that has the privilege of running immediately
                    // 2. There aren't too many jobs running AND the current job started when the
                    //    app was in the background
                    // 3. There aren't too many jobs running AND the current job started when the
                    //    app was on TOP, but the app has since left TOP
                    // 4. There aren't too many jobs running AND the current job started when the
                    //    app was on TOP, the app is still TOP, but there are too many
                    //    immediacy-privileged jobs
                    //    running (because we don't want them to starve out other apps and the
                    //    current job has already run for the minimum guaranteed time).
                    // 5. This new job could be waiting for too long for a slot to open up
                    boolean canReplace = hasImmediacyPrivilege; // Case 1
                    if (!canReplace && !isInOverage) {
                        final int currentJobBias = mService.evaluateJobBiasLocked(runningJob);
                        canReplace = runningJob.lastEvaluatedBias < JobInfo.BIAS_TOP_APP // Case 2
                                || currentJobBias < JobInfo.BIAS_TOP_APP // Case 3
                                // Case 4
                                || info.numRunningImmediacyPrivileged
                                        > (mWorkTypeConfig.getMaxTotal() / 2);
                    }
                    if (!canReplace && mMaxWaitTimeBypassEnabled) { // Case 5
                        if (nextPending.shouldTreatAsUserInitiatedJob()) {
                            canReplace = minWaitingTimeMs >= mMaxWaitUIMs;
                        } else if (nextPending.shouldTreatAsExpeditedJob()) {
                            canReplace = minWaitingTimeMs >= mMaxWaitEjMs;
                        } else {
                            canReplace = minWaitingTimeMs >= mMaxWaitRegularMs;
                        }
                    }
                    if (canReplace) {
                        int replaceWorkType = mWorkCountTracker.canJobStart(allWorkTypes,
                                assignment.context.getRunningJobWorkType());
                        if (replaceWorkType != WORK_TYPE_NONE) {
                            // Right now, the way the code is set up, we don't need to explicitly
                            // assign the new job to this context since we'll reassign when the
                            // preempted job finally stops.
                            assignment.preemptReason = assignment.shouldStopJobReason;
                            assignment.preemptReasonCode = JobParameters.STOP_REASON_DEVICE_STATE;
                            selectedContext = assignment;
                            stoppable.remove(s);
                            assignment.newJob = nextPending;
                            assignment.newWorkType = replaceWorkType;
                            break;
                        }
                    }
                }
            }
            if (selectedContext == null && (!isInOverage || hasImmediacyPrivilege)) {
                int lowestBiasSeen = Integer.MAX_VALUE;
                long newMinPreferredUidOnlyWaitingTimeMs = Long.MAX_VALUE;
                for (int p = preferredUidOnly.size() - 1; p >= 0; --p) {
                    final ContextAssignment assignment = preferredUidOnly.get(p);
                    final JobStatus runningJob = assignment.context.getRunningJobLocked();
                    if (runningJob.getUid() != nextPending.getUid()) {
                        continue;
                    }
                    final int jobBias = mService.evaluateJobBiasLocked(runningJob);
                    if (jobBias >= nextPending.lastEvaluatedBias) {
                        continue;
                    }

                    if (selectedContext == null || lowestBiasSeen > jobBias) {
                        if (selectedContext != null) {
                            // We're no longer using the previous context, so factor it into the
                            // calculation.
                            newMinPreferredUidOnlyWaitingTimeMs = Math.min(
                                    newMinPreferredUidOnlyWaitingTimeMs,
                                    selectedContext.timeUntilStoppableMs);
                        }
                        // Step down the preemption threshold - wind up replacing
                        // the lowest-bias running job
                        lowestBiasSeen = jobBias;
                        selectedContext = assignment;
                        assignment.preemptReason = "higher bias job found";
                        assignment.preemptReasonCode = JobParameters.STOP_REASON_PREEMPT;
                        // In this case, we're just going to preempt a low bias job, we're not
                        // actually starting a job, so don't set startingJob to true.
                    } else {
                        // We're not going to use this context, so factor it into the calculation.
                        newMinPreferredUidOnlyWaitingTimeMs = Math.min(
                                newMinPreferredUidOnlyWaitingTimeMs,
                                assignment.timeUntilStoppableMs);
                    }
                }
                if (selectedContext != null) {
                    selectedContext.newJob = nextPending;
                    preferredUidOnly.remove(selectedContext);
                    info.minPreferredUidOnlyWaitingTimeMs = newMinPreferredUidOnlyWaitingTimeMs;
                }
            }
            // Make sure to run jobs with special privilege immediately.
            if (hasImmediacyPrivilege) {
                if (selectedContext != null
                        && selectedContext.context.getRunningJobLocked() != null) {
                    // We're "replacing" a currently running job, but we want immediacy-privileged
                    // jobs to start immediately, so we'll start the privileged jobs on a fresh
                    // available context and
                    // stop this currently running job to replace in two steps.
                    changed.add(selectedContext);
                    projectedRunningCount--;
                    selectedContext.newJob = null;
                    selectedContext.newWorkType = WORK_TYPE_NONE;
                    selectedContext = null;
                }
                if (selectedContext == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Allowing additional context because EJ would wait too long");
                    }
                    selectedContext = mContextAssignmentPool.acquire();
                    if (selectedContext == null) {
                        selectedContext = new ContextAssignment();
                    }
                    selectedContext.context = mIdleContexts.size() > 0
                            ? mIdleContexts.removeAt(mIdleContexts.size() - 1)
                            : createNewJobServiceContext();
                    selectedContext.newJob = nextPending;
                    final int workType = mWorkCountTracker.canJobStart(allWorkTypes);
                    selectedContext.newWorkType =
                            (workType != WORK_TYPE_NONE) ? workType : WORK_TYPE_TOP;
                }
            } else if (selectedContext == null && mMaxWaitTimeBypassEnabled) {
                final boolean wouldBeWaitingTooLong;
                if (nextPending.shouldTreatAsUserInitiatedJob() && allowMaxWaitContextBypassUi) {
                    wouldBeWaitingTooLong = minWaitingTimeMs >= mMaxWaitUIMs;
                    // We want to create at most one additional context for each type.
                    allowMaxWaitContextBypassUi = !wouldBeWaitingTooLong;
                } else if (nextPending.shouldTreatAsExpeditedJob() && allowMaxWaitContextBypassEj) {
                    wouldBeWaitingTooLong = minWaitingTimeMs >= mMaxWaitEjMs;
                    // We want to create at most one additional context for each type.
                    allowMaxWaitContextBypassEj = !wouldBeWaitingTooLong;
                } else if (allowMaxWaitContextBypassOthers) {
                    // The way things are set up a UIJ or EJ could end up here and create a 2nd
                    // context as if it were a "regular" job. That's fine for now since they would
                    // still be subject to the higher waiting time threshold here.
                    wouldBeWaitingTooLong = minWaitingTimeMs >= mMaxWaitRegularMs;
                    // We want to create at most one additional context for each type.
                    allowMaxWaitContextBypassOthers = !wouldBeWaitingTooLong;
                } else {
                    wouldBeWaitingTooLong = false;
                }
                if (wouldBeWaitingTooLong) {
                    if (DEBUG) {
                        Slog.d(TAG, "Allowing additional context because job would wait too long");
                    }
                    selectedContext = mContextAssignmentPool.acquire();
                    if (selectedContext == null) {
                        selectedContext = new ContextAssignment();
                    }
                    selectedContext.context = mIdleContexts.size() > 0
                            ? mIdleContexts.removeAt(mIdleContexts.size() - 1)
                            : createNewJobServiceContext();
                    selectedContext.newJob = nextPending;
                    final int workType = mWorkCountTracker.canJobStart(allWorkTypes);
                    if (workType != WORK_TYPE_NONE) {
                        selectedContext.newWorkType = workType;
                    } else {
                        // Use the strongest work type possible for this job.
                        for (int type = 1; type <= ALL_WORK_TYPES; type = type << 1) {
                            if ((type & allWorkTypes) != 0) {
                                selectedContext.newWorkType = type;
                                break;
                            }
                        }
                    }
                }
            }
            final PackageStats packageStats = getPkgStatsLocked(
                    nextPending.getSourceUserId(), nextPending.getSourcePackageName());
            if (selectedContext != null) {
                changed.add(selectedContext);
                if (selectedContext.context.getRunningJobLocked() != null) {
                    projectedRunningCount--;
                }
                if (selectedContext.newJob != null) {
                    selectedContext.newJob.startedWithImmediacyPrivilege = hasImmediacyPrivilege;
                    projectedRunningCount++;
                    minChangedWaitingTimeMs = Math.min(minChangedWaitingTimeMs,
                            mService.getMinJobExecutionGuaranteeMs(selectedContext.newJob));
                }
                packageStats.adjustStagedCount(true, nextPending.shouldTreatAsExpeditedJob());
            }
            if (startingJob) {
                // Increase the counters when we're going to start a job.
                mWorkCountTracker.stageJob(selectedContext.newWorkType, allWorkTypes);
                mActivePkgStats.add(
                        nextPending.getSourceUserId(), nextPending.getSourcePackageName(),
                        packageStats);
            }
        }
    }

    @GuardedBy("mLock")
    private void carryOutAssignmentChangesLocked(final ArraySet<ContextAssignment> changed) {
        for (int c = changed.size() - 1; c >= 0; --c) {
            final ContextAssignment assignment = changed.valueAt(c);
            final JobStatus js = assignment.context.getRunningJobLocked();
            if (js != null) {
                if (DEBUG) {
                    Slog.d(TAG, "preempting job: " + js);
                }
                // preferredUid will be set to uid of currently running job, if appropriate.
                assignment.context.cancelExecutingJobLocked(
                        assignment.preemptReasonCode,
                        JobParameters.INTERNAL_STOP_REASON_PREEMPT, assignment.preemptReason);
            } else {
                final JobStatus pendingJob = assignment.newJob;
                if (DEBUG) {
                    Slog.d(TAG, "About to run job on context "
                            + assignment.context.getId() + ", job: " + pendingJob);
                }
                startJobLocked(assignment.context, pendingJob, assignment.newWorkType);
            }

            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
    }

    @GuardedBy("mLock")
    private void cleanUpAfterAssignmentChangesLocked(final ArraySet<ContextAssignment> changed,
            final ArraySet<ContextAssignment> idle,
            final List<ContextAssignment> preferredUidOnly,
            final List<ContextAssignment> stoppable,
            final AssignmentInfo assignmentInfo,
            final SparseIntArray privilegedState) {
        for (int s = stoppable.size() - 1; s >= 0; --s) {
            final ContextAssignment assignment = stoppable.get(s);
            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
        for (int p = preferredUidOnly.size() - 1; p >= 0; --p) {
            final ContextAssignment assignment = preferredUidOnly.get(p);
            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
        for (int i = idle.size() - 1; i >= 0; --i) {
            final ContextAssignment assignment = idle.valueAt(i);
            mIdleContexts.add(assignment.context);
            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
        changed.clear();
        idle.clear();
        stoppable.clear();
        preferredUidOnly.clear();
        assignmentInfo.clear();
        privilegedState.clear();
        mWorkCountTracker.resetStagingCount();
        mActivePkgStats.forEach(mPackageStatsStagingCountClearer);
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    boolean hasImmediacyPrivilegeLocked(@NonNull JobStatus job,
            @NonNull SparseIntArray cachedPrivilegedState) {
        if (!job.shouldTreatAsExpeditedJob() && !job.shouldTreatAsUserInitiatedJob()) {
            return false;
        }
        // EJs & user-initiated jobs for the TOP app should run immediately.
        // However, even for user-initiated jobs, if the app has not recently been in TOP or BAL
        // state, we don't give the immediacy privilege so that we can try and maintain
        // reasonably concurrency behavior.
        if (job.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
            return true;
        }
        final int uid = job.getSourceUid();
        final int privilegedState = cachedPrivilegedState.get(uid, PRIVILEGED_STATE_UNDEFINED);
        switch (privilegedState) {
            case PRIVILEGED_STATE_TOP:
                return true;
            case PRIVILEGED_STATE_BAL:
                return job.shouldTreatAsUserInitiatedJob();
            case PRIVILEGED_STATE_NONE:
                return false;
            case PRIVILEGED_STATE_UNDEFINED:
            default:
                final ActivityManagerInternal activityManagerInternal =
                        LocalServices.getService(ActivityManagerInternal.class);
                final int procState = activityManagerInternal.getUidProcessState(uid);
                if (procState == ActivityManager.PROCESS_STATE_TOP) {
                    cachedPrivilegedState.put(uid, PRIVILEGED_STATE_TOP);
                    return true;
                }
                if (job.shouldTreatAsExpeditedJob()) {
                    // EJs only get the TOP privilege.
                    return false;
                }

                final BackgroundStartPrivileges bsp =
                        activityManagerInternal.getBackgroundStartPrivileges(uid);
                if (DEBUG) {
                    Slog.d(TAG, "Job " + job.toShortString() + " bsp state: " + bsp);
                }
                // Intentionally use the background activity start BSP here instead of
                // the full BAL check since the former is transient and better indicates that the
                // user recently interacted with the app, while the latter includes
                // permanent exceptions that don't warrant bypassing normal concurrency policy.
                final boolean balAllowed = bsp.allowsBackgroundActivityStarts();
                cachedPrivilegedState.put(uid,
                        balAllowed ? PRIVILEGED_STATE_BAL : PRIVILEGED_STATE_NONE);
                return balAllowed;
        }
    }

    @GuardedBy("mLock")
    void onUidBiasChangedLocked(int prevBias, int newBias) {
        if (prevBias != JobInfo.BIAS_TOP_APP && newBias != JobInfo.BIAS_TOP_APP) {
            // TOP app didn't change. Nothing to do.
            return;
        }
        if (mService.getPendingJobQueue().size() == 0) {
            // Nothing waiting for the top app to leave. Nothing to do.
            return;
        }
        // Don't stop the TOP jobs directly. Instead, see if they would be replaced by some
        // pending job (there may not always be something to replace them).
        assignJobsToContextsLocked();
    }

    @Nullable
    @GuardedBy("mLock")
    JobServiceContext getRunningJobServiceContextLocked(JobStatus job) {
        if (!mRunningJobs.contains(job)) {
            return null;
        }

        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus executing = jsc.getRunningJobLocked();
            if (executing == job) {
                return jsc;
            }
        }
        Slog.wtf(TAG, "Couldn't find running job on a context");
        mRunningJobs.remove(job);
        return null;
    }

    @GuardedBy("mLock")
    boolean stopJobOnServiceContextLocked(JobStatus job,
            @JobParameters.StopReason int reason, int internalReasonCode, String debugReason) {
        if (!mRunningJobs.contains(job)) {
            return false;
        }

        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus executing = jsc.getRunningJobLocked();
            if (executing == job) {
                jsc.cancelExecutingJobLocked(reason, internalReasonCode, debugReason);
                return true;
            }
        }
        Slog.wtf(TAG, "Couldn't find running job on a context");
        mRunningJobs.remove(job);
        return false;
    }

    @GuardedBy("mLock")
    private void stopUnexemptedJobsForDoze() {
        // When becoming idle, make sure no jobs are actively running,
        // except those using the idle exemption flag.
        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus executing = jsc.getRunningJobLocked();
            if (executing != null && !executing.canRunInDoze()) {
                jsc.cancelExecutingJobLocked(JobParameters.STOP_REASON_DEVICE_STATE,
                        JobParameters.INTERNAL_STOP_REASON_DEVICE_IDLE,
                        "cancelled due to doze");
            }
        }
    }

    @GuardedBy("mLock")
    private void stopOvertimeJobsLocked(@NonNull String debugReason) {
        for (int i = 0; i < mActiveServices.size(); ++i) {
            final JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus jobStatus = jsc.getRunningJobLocked();

            if (jobStatus != null && !jsc.isWithinExecutionGuaranteeTime()) {
                jsc.cancelExecutingJobLocked(JobParameters.STOP_REASON_DEVICE_STATE,
                        JobParameters.INTERNAL_STOP_REASON_TIMEOUT, debugReason);
            }
        }
    }

    /**
     * Stops any jobs that have run for more than their minimum execution guarantee and are
     * restricted by the given {@link JobRestriction}.
     */
    @GuardedBy("mLock")
    void maybeStopOvertimeJobsLocked(@NonNull JobRestriction restriction) {
        for (int i = mActiveServices.size() - 1; i >= 0; --i) {
            final JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus jobStatus = jsc.getRunningJobLocked();

            if (jobStatus != null && !jsc.isWithinExecutionGuaranteeTime()
                    && restriction.isJobRestricted(jobStatus)) {
                jsc.cancelExecutingJobLocked(restriction.getStopReason(),
                        restriction.getInternalReason(),
                        JobParameters.getInternalReasonCodeDescription(
                                restriction.getInternalReason()));
            }
        }
    }

    @GuardedBy("mLock")
    void markJobsForUserStopLocked(int userId, @NonNull String packageName,
            @Nullable String debugReason) {
        for (int i = mActiveServices.size() - 1; i >= 0; --i) {
            final JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus jobStatus = jsc.getRunningJobLocked();

            // Normally, we handle jobs primarily using the source package and userId,
            // however, user-visible jobs are shown as coming from the calling app, so we
            // need to operate on the jobs from that perspective here.
            if (jobStatus != null && userId == jobStatus.getUserId()
                    && jobStatus.getServiceComponent().getPackageName().equals(packageName)) {
                jsc.markForProcessDeathLocked(JobParameters.STOP_REASON_USER,
                        JobParameters.INTERNAL_STOP_REASON_USER_UI_STOP,
                        debugReason);
            }
        }
    }

    @GuardedBy("mLock")
    void stopNonReadyActiveJobsLocked() {
        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext serviceContext = mActiveServices.get(i);
            final JobStatus running = serviceContext.getRunningJobLocked();
            if (running == null) {
                continue;
            }
            if (!running.isReady()) {
                if (running.getEffectiveStandbyBucket() == RESTRICTED_INDEX
                        && running.getStopReason() == JobParameters.STOP_REASON_APP_STANDBY) {
                    serviceContext.cancelExecutingJobLocked(
                            running.getStopReason(),
                            JobParameters.INTERNAL_STOP_REASON_RESTRICTED_BUCKET,
                            "cancelled due to restricted bucket");
                } else {
                    serviceContext.cancelExecutingJobLocked(
                            running.getStopReason(),
                            JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED,
                            "cancelled due to unsatisfied constraints");
                }
            } else {
                final JobRestriction restriction = mService.checkIfRestricted(running);
                if (restriction != null) {
                    final int internalReasonCode = restriction.getInternalReason();
                    serviceContext.cancelExecutingJobLocked(restriction.getStopReason(),
                            internalReasonCode,
                            "restricted due to "
                                    + JobParameters.getInternalReasonCodeDescription(
                                    internalReasonCode));
                }
            }
        }
    }

    private void noteConcurrency() {
        mService.mJobPackageTracker.noteConcurrency(mRunningJobs.size(),
                // TODO: log per type instead of only TOP
                mWorkCountTracker.getRunningJobCount(WORK_TYPE_TOP));
        sConcurrencyHistogramLogger.logSample(mActiveServices.size());
    }

    @GuardedBy("mLock")
    private void updateNonRunningPrioritiesLocked(@NonNull final PendingJobQueue jobQueue,
            boolean updateCounter) {
        JobStatus pending;
        jobQueue.resetIterator();
        while ((pending = jobQueue.next()) != null) {

            // If job is already running, go to next job.
            if (mRunningJobs.contains(pending)) {
                continue;
            }

            pending.lastEvaluatedBias = mService.evaluateJobBiasLocked(pending);

            if (updateCounter) {
                mWorkCountTracker.incrementPendingJobCount(getJobWorkTypes(pending));
            }
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private PackageStats getPkgStatsLocked(int userId, @NonNull String packageName) {
        PackageStats packageStats = mActivePkgStats.get(userId, packageName);
        if (packageStats == null) {
            packageStats = mPkgStatsPool.acquire();
            if (packageStats == null) {
                packageStats = new PackageStats();
            }
            packageStats.setPackage(userId, packageName);
        }
        return packageStats;
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    boolean isPkgConcurrencyLimitedLocked(@NonNull JobStatus jobStatus) {
        if (jobStatus.lastEvaluatedBias >= JobInfo.BIAS_TOP_APP) {
            // Don't restrict top apps' concurrency. The work type limits will make sure
            // background jobs have slots to run if the system has resources.
            return false;
        }
        // Use < instead of <= as that gives us a little wiggle room in case a new job comes
        // along very shortly.
        if (mService.getPendingJobQueue().size() + mRunningJobs.size()
                < mWorkTypeConfig.getMaxTotal()) {
            // Don't artificially limit a single package if we don't even have enough jobs to use
            // the maximum number of slots. We'll preempt the job later if we need the slot.
            return false;
        }
        final PackageStats packageStats =
                mActivePkgStats.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        if (packageStats == null) {
            // No currently running jobs.
            return false;
        }
        if (jobStatus.shouldTreatAsExpeditedJob()) {
            return packageStats.numRunningEj + packageStats.numStagedEj >= mPkgConcurrencyLimitEj;
        } else {
            return packageStats.numRunningRegular + packageStats.numStagedRegular
                    >= mPkgConcurrencyLimitRegular;
        }
    }

    @GuardedBy("mLock")
    private void startJobLocked(@NonNull JobServiceContext worker, @NonNull JobStatus jobStatus,
            @WorkType final int workType) {
        final List<StateController> controllers = mService.mControllers;
        final int numControllers = controllers.size();
        final PowerManager.WakeLock wl =
                mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, jobStatus.getTag());
        wl.setWorkSource(mService.deriveWorkSource(
                jobStatus.getSourceUid(), jobStatus.getSourcePackageName()));
        wl.setReferenceCounted(false);
        // Since the quota controller will start counting from the time prepareForExecutionLocked()
        // is called, hold a wakelock to make sure the CPU doesn't suspend between that call and
        // when the service actually starts.
        wl.acquire();
        try {
            for (int ic = 0; ic < numControllers; ic++) {
                controllers.get(ic).prepareForExecutionLocked(jobStatus);
            }
            final PackageStats packageStats = getPkgStatsLocked(
                    jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
            packageStats.adjustStagedCount(false, jobStatus.shouldTreatAsExpeditedJob());
            if (!worker.executeRunnableJob(jobStatus, workType)) {
                Slog.e(TAG, "Error executing " + jobStatus);
                mWorkCountTracker.onStagedJobFailed(workType);
                for (int ic = 0; ic < numControllers; ic++) {
                    controllers.get(ic).unprepareFromExecutionLocked(jobStatus);
                }
            } else {
                mRunningJobs.add(jobStatus);
                mActiveServices.add(worker);
                mIdleContexts.remove(worker);
                mWorkCountTracker.onJobStarted(workType);
                packageStats.adjustRunningCount(true, jobStatus.shouldTreatAsExpeditedJob());
                mActivePkgStats.add(
                        jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(),
                        packageStats);
                mService.resetPendingJobReasonCache(jobStatus);
            }
            if (mService.getPendingJobQueue().remove(jobStatus)) {
                mService.mJobPackageTracker.noteNonpending(jobStatus);
            }
        } finally {
            wl.release();
        }
    }

    @GuardedBy("mLock")
    void onJobCompletedLocked(@NonNull JobServiceContext worker, @NonNull JobStatus jobStatus,
            @WorkType final int workType) {
        mWorkCountTracker.onJobFinished(workType);
        mRunningJobs.remove(jobStatus);
        mActiveServices.remove(worker);
        if (mIdleContexts.size() < MAX_RETAINED_OBJECTS) {
            // Don't need to save all new contexts, but keep some extra around in case we need
            // extras for another immediacy privileged overage.
            mIdleContexts.add(worker);
        } else {
            mNumDroppedContexts++;
        }
        final PackageStats packageStats =
                mActivePkgStats.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        if (packageStats == null) {
            Slog.wtf(TAG, "Running job didn't have an active PackageStats object");
        } else {
            packageStats.adjustRunningCount(false, jobStatus.startedAsExpeditedJob);
            if (packageStats.numRunningEj <= 0 && packageStats.numRunningRegular <= 0) {
                mActivePkgStats.delete(packageStats.userId, packageStats.packageName);
                mPkgStatsPool.release(packageStats);
            }
        }

        final PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        if (pendingJobQueue.size() == 0) {
            worker.clearPreferredUid();
            noteConcurrency();
            return;
        }
        if (mActiveServices.size() >= mSteadyStateConcurrencyLimit) {
            final boolean respectConcurrencyLimit;
            if (!mMaxWaitTimeBypassEnabled) {
                respectConcurrencyLimit = true;
            } else {
                long minWaitingTimeMs = Long.MAX_VALUE;
                final long nowElapsed = sElapsedRealtimeClock.millis();
                for (int i = mActiveServices.size() - 1; i >= 0; --i) {
                    minWaitingTimeMs = Math.min(minWaitingTimeMs,
                            mActiveServices.get(i).getRemainingGuaranteedTimeMs(nowElapsed));
                }
                final boolean wouldBeWaitingTooLong;
                if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_UI) > 0) {
                    wouldBeWaitingTooLong = minWaitingTimeMs >= mMaxWaitUIMs;
                } else if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_EJ) > 0) {
                    wouldBeWaitingTooLong = minWaitingTimeMs >= mMaxWaitEjMs;
                } else {
                    wouldBeWaitingTooLong = minWaitingTimeMs >= mMaxWaitRegularMs;
                }
                respectConcurrencyLimit = !wouldBeWaitingTooLong;
            }
            if (respectConcurrencyLimit) {
                worker.clearPreferredUid();
                // We're over the limit (because there were a lot of immediacy-privileged jobs
                // scheduled), but we should
                // be able to stop the other jobs soon so don't start running anything new until we
                // get back below the limit.
                noteConcurrency();
                return;
            }
        }

        if (worker.getPreferredUid() != JobServiceContext.NO_PREFERRED_UID) {
            updateCounterConfigLocked();
            // Preemption case needs special care.
            updateNonRunningPrioritiesLocked(pendingJobQueue, false);

            JobStatus highestBiasJob = null;
            int highBiasWorkType = workType;
            int highBiasAllWorkTypes = workType;
            JobStatus backupJob = null;
            int backupWorkType = WORK_TYPE_NONE;
            int backupAllWorkTypes = WORK_TYPE_NONE;

            JobStatus nextPending;
            pendingJobQueue.resetIterator();
            while ((nextPending = pendingJobQueue.next()) != null) {
                if (mRunningJobs.contains(nextPending)) {
                    // Should never happen.
                    Slog.wtf(TAG, "Pending queue contained a running job");
                    if (DEBUG) {
                        Slog.e(TAG, "Pending+running job: " + nextPending);
                    }
                    pendingJobQueue.remove(nextPending);
                    continue;
                }

                if (DEBUG && isSimilarJobRunningLocked(nextPending)) {
                    Slog.w(TAG, "Already running similar job to: " + nextPending);
                }

                if (worker.getPreferredUid() != nextPending.getUid()) {
                    if (backupJob == null && !isPkgConcurrencyLimitedLocked(nextPending)) {
                        int allWorkTypes = getJobWorkTypes(nextPending);
                        int workAsType = mWorkCountTracker.canJobStart(allWorkTypes);
                        if (workAsType != WORK_TYPE_NONE) {
                            backupJob = nextPending;
                            backupWorkType = workAsType;
                            backupAllWorkTypes = allWorkTypes;
                        }
                    }
                    continue;
                }

                // Only bypass the concurrent limit if we had preempted the job due to a higher
                // bias job.
                if (nextPending.lastEvaluatedBias <= jobStatus.lastEvaluatedBias
                        && isPkgConcurrencyLimitedLocked(nextPending)) {
                    continue;
                }

                if (highestBiasJob == null
                        || highestBiasJob.lastEvaluatedBias < nextPending.lastEvaluatedBias) {
                    highestBiasJob = nextPending;
                } else {
                    continue;
                }

                // In this path, we pre-empted an existing job. We don't fully care about the
                // reserved slots. We should just run the highest bias job we can find,
                // though it would be ideal to use an available WorkType slot instead of
                // overloading slots.
                highBiasAllWorkTypes = getJobWorkTypes(nextPending);
                final int workAsType = mWorkCountTracker.canJobStart(highBiasAllWorkTypes);
                if (workAsType == WORK_TYPE_NONE) {
                    // Just use the preempted job's work type since this new one is technically
                    // replacing it anyway.
                    highBiasWorkType = workType;
                } else {
                    highBiasWorkType = workAsType;
                }
            }
            if (highestBiasJob != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Running job " + highestBiasJob + " as preemption");
                }
                mWorkCountTracker.stageJob(highBiasWorkType, highBiasAllWorkTypes);
                startJobLocked(worker, highestBiasJob, highBiasWorkType);
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Couldn't find preemption job for uid " + worker.getPreferredUid());
                }
                worker.clearPreferredUid();
                if (backupJob != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Running job " + backupJob + " instead");
                    }
                    mWorkCountTracker.stageJob(backupWorkType, backupAllWorkTypes);
                    startJobLocked(worker, backupJob, backupWorkType);
                }
            }
        } else if (pendingJobQueue.size() > 0) {
            updateCounterConfigLocked();
            updateNonRunningPrioritiesLocked(pendingJobQueue, false);

            // This slot is now free and we have pending jobs. Start the highest bias job we find.
            JobStatus highestBiasJob = null;
            int highBiasWorkType = workType;
            int highBiasAllWorkTypes = workType;

            JobStatus nextPending;
            pendingJobQueue.resetIterator();
            while ((nextPending = pendingJobQueue.next()) != null) {

                if (mRunningJobs.contains(nextPending)) {
                    // Should never happen.
                    Slog.wtf(TAG, "Pending queue contained a running job");
                    if (DEBUG) {
                        Slog.e(TAG, "Pending+running job: " + nextPending);
                    }
                    pendingJobQueue.remove(nextPending);
                    continue;
                }

                if (DEBUG && isSimilarJobRunningLocked(nextPending)) {
                    Slog.w(TAG, "Already running similar job to: " + nextPending);
                }

                if (isPkgConcurrencyLimitedLocked(nextPending)) {
                    continue;
                }

                final int allWorkTypes = getJobWorkTypes(nextPending);
                final int workAsType = mWorkCountTracker.canJobStart(allWorkTypes);
                if (workAsType == WORK_TYPE_NONE) {
                    continue;
                }
                if (highestBiasJob == null
                        || highestBiasJob.lastEvaluatedBias < nextPending.lastEvaluatedBias) {
                    highestBiasJob = nextPending;
                    highBiasWorkType = workAsType;
                    highBiasAllWorkTypes = allWorkTypes;
                }
            }

            if (highestBiasJob != null) {
                // This slot is free, and we haven't yet hit the limit on
                // concurrent jobs...  we can just throw the job in to here.
                if (DEBUG) {
                    Slog.d(TAG, "About to run job: " + highestBiasJob);
                }
                mWorkCountTracker.stageJob(highBiasWorkType, highBiasAllWorkTypes);
                startJobLocked(worker, highestBiasJob, highBiasWorkType);
            }
        }

        noteConcurrency();
    }

    /**
     * Returns {@code null} if the job can continue running and a non-null String if the job should
     * be stopped. The non-null String details the reason for stopping the job. A job will generally
     * be stopped if there are similar job types waiting to be run and stopping this job would allow
     * another job to run, or if system state suggests the job should stop.
     */
    @Nullable
    @GuardedBy("mLock")
    String shouldStopRunningJobLocked(@NonNull JobServiceContext context) {
        final JobStatus js = context.getRunningJobLocked();
        if (js == null) {
            // This can happen when we try to assign newly found pending jobs to contexts.
            return null;
        }

        if (context.isWithinExecutionGuaranteeTime()) {
            return null;
        }

        // We're over the minimum guaranteed runtime. Stop the job if we're over config limits,
        // there are pending jobs that could replace this one, or the device state is not conducive
        // to long runs.

        if (mPowerManager.isPowerSaveMode()) {
            return "battery saver";
        }
        if (mPowerManager.isDeviceIdleMode()) {
            return "deep doze";
        }
        final JobRestriction jobRestriction;
        if ((jobRestriction = mService.checkIfRestricted(js)) != null) {
            return "restriction:"
                    + JobParameters.getInternalReasonCodeDescription(
                            jobRestriction.getInternalReason());
        }

        // Update config in case memory usage has changed significantly.
        updateCounterConfigLocked();

        @WorkType final int workType = context.getRunningJobWorkType();

        if (mRunningJobs.size() > mWorkTypeConfig.getMaxTotal()
                || mWorkCountTracker.isOverTypeLimit(workType)) {
            return "too many jobs running";
        }

        final PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        final int numPending = pendingJobQueue.size();
        if (numPending == 0) {
            // All quiet. We can let this job run to completion.
            return null;
        }

        // Only expedited jobs can replace expedited jobs.
        if (js.shouldTreatAsExpeditedJob() || js.startedAsExpeditedJob) {
            // Keep fg/bg user distinction.
            if (workType == WORK_TYPE_BGUSER_IMPORTANT || workType == WORK_TYPE_BGUSER) {
                // Let any important bg user job replace a bg user expedited job.
                if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_BGUSER_IMPORTANT) > 0) {
                    return "blocking " + workTypeToString(WORK_TYPE_BGUSER_IMPORTANT) + " queue";
                }
                // Let a fg user EJ preempt a bg user EJ (if able), but not the other way around.
                if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_EJ) > 0
                        && mWorkCountTracker.canJobStart(WORK_TYPE_EJ, workType)
                        != WORK_TYPE_NONE) {
                    return "blocking " + workTypeToString(WORK_TYPE_EJ) + " queue";
                }
            } else if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_EJ) > 0) {
                return "blocking " + workTypeToString(WORK_TYPE_EJ) + " queue";
            } else if (js.startedWithImmediacyPrivilege) {
                // Try not to let jobs with immediacy privilege starve out other apps.
                int immediacyPrivilegeCount = 0;
                for (int r = mRunningJobs.size() - 1; r >= 0; --r) {
                    JobStatus j = mRunningJobs.valueAt(r);
                    if (j.startedWithImmediacyPrivilege) {
                        immediacyPrivilegeCount++;
                    }
                }
                if (immediacyPrivilegeCount > mWorkTypeConfig.getMaxTotal() / 2) {
                    return "prevent immediacy privilege dominance";
                }
            }
            // No other pending EJs. Return null so we don't let regular jobs preempt an EJ.
            return null;
        }

        // Easy check. If there are pending jobs of the same work type, then we know that
        // something will replace this.
        if (mWorkCountTracker.getPendingJobCount(workType) > 0) {
            return "blocking " + workTypeToString(workType) + " queue";
        }

        // Harder check. We need to see if a different work type can replace this job.
        int remainingWorkTypes = ALL_WORK_TYPES;
        JobStatus pending;
        pendingJobQueue.resetIterator();
        while ((pending = pendingJobQueue.next()) != null) {
            final int workTypes = getJobWorkTypes(pending);
            if ((workTypes & remainingWorkTypes) > 0
                    && mWorkCountTracker.canJobStart(workTypes, workType) != WORK_TYPE_NONE) {
                return "blocking other pending jobs";
            }

            remainingWorkTypes = remainingWorkTypes & ~workTypes;
            if (remainingWorkTypes == 0) {
                break;
            }
        }

        return null;
    }

    @GuardedBy("mLock")
    boolean executeStopCommandLocked(PrintWriter pw, String pkgName, int userId,
            @Nullable String namespace, boolean matchJobId, int jobId,
            int stopReason, int internalStopReason) {
        boolean foundSome = false;
        for (int i = 0; i < mActiveServices.size(); i++) {
            final JobServiceContext jc = mActiveServices.get(i);
            final JobStatus js = jc.getRunningJobLocked();
            if (jc.stopIfExecutingLocked(pkgName, userId, namespace, matchJobId, jobId,
                    stopReason, internalStopReason)) {
                foundSome = true;
                pw.print("Stopping job: ");
                js.printUniqueId(pw);
                pw.print(" ");
                pw.println(js.getServiceComponent().flattenToShortString());
            }
        }
        return foundSome;
    }

    /**
     * Returns the estimated network bytes if the job is running. Returns {@code null} if the job
     * isn't running.
     */
    @Nullable
    @GuardedBy("mLock")
    Pair<Long, Long> getEstimatedNetworkBytesLocked(String pkgName, int uid,
            String namespace, int jobId) {
        for (int i = 0; i < mActiveServices.size(); i++) {
            final JobServiceContext jc = mActiveServices.get(i);
            final JobStatus js = jc.getRunningJobLocked();
            if (js != null && js.matches(uid, namespace, jobId)
                    && js.getSourcePackageName().equals(pkgName)) {
                return jc.getEstimatedNetworkBytes();
            }
        }
        return null;
    }

    /**
     * Returns the transferred network bytes if the job is running. Returns {@code null} if the job
     * isn't running.
     */
    @Nullable
    @GuardedBy("mLock")
    Pair<Long, Long> getTransferredNetworkBytesLocked(String pkgName, int uid,
            String namespace, int jobId) {
        for (int i = 0; i < mActiveServices.size(); i++) {
            final JobServiceContext jc = mActiveServices.get(i);
            final JobStatus js = jc.getRunningJobLocked();
            if (js != null && js.matches(uid, namespace, jobId)
                    && js.getSourcePackageName().equals(pkgName)) {
                return jc.getTransferredNetworkBytes();
            }
        }
        return null;
    }

    boolean isNotificationAssociatedWithAnyUserInitiatedJobs(int notificationId, int userId,
            @NonNull String packageName) {
        return mNotificationCoordinator.isNotificationAssociatedWithAnyUserInitiatedJobs(
                notificationId, userId, packageName);
    }

    boolean isNotificationChannelAssociatedWithAnyUserInitiatedJobs(
            @NonNull String notificationChannel, int userId, @NonNull String packageName) {
        return mNotificationCoordinator.isNotificationChannelAssociatedWithAnyUserInitiatedJobs(
                notificationChannel, userId, packageName);
    }

    @NonNull
    private JobServiceContext createNewJobServiceContext() {
        return mInjector.createJobServiceContext(mService, this, mNotificationCoordinator,
                IBatteryStats.Stub.asInterface(
                        ServiceManager.getService(BatteryStats.SERVICE_NAME)),
                mService.mJobPackageTracker, AppSchedulingModuleThread.get().getLooper());
    }

    @GuardedBy("mLock")
    private String printPendingQueueLocked() {
        StringBuilder s = new StringBuilder("Pending queue: ");
        PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        JobStatus js;
        pendingJobQueue.resetIterator();
        while ((js = pendingJobQueue.next()) != null) {
            s.append("(")
                    .append("{")
                    .append(js.getNamespace())
                    .append("} ")
                    .append(js.getJob().getId())
                    .append(", ")
                    .append(js.getUid())
                    .append(") ");
        }
        return s.toString();
    }

    private static String printAssignments(String header, Collection<ContextAssignment>... list) {
        final StringBuilder s = new StringBuilder(header + ": ");
        for (int l = 0; l < list.length; ++l) {
            final Collection<ContextAssignment> assignments = list[l];
            int c = 0;
            for (final ContextAssignment assignment : assignments) {
                final JobStatus job = assignment.newJob == null
                        ? assignment.context.getRunningJobLocked() : assignment.newJob;

                if (l > 0 || c > 0) {
                    s.append(" ");
                }
                s.append("(").append(assignment.context.getId()).append("=");
                if (job == null) {
                    s.append("nothing");
                } else {
                    if (job.getNamespace() != null) {
                        s.append(job.getNamespace()).append(":");
                    }
                    s.append(job.getJobId()).append("/").append(job.getUid());
                }
                s.append(")");
                c++;
            }
        }
        return s.toString();
    }

    @GuardedBy("mLock")
    void updateConfigLocked() {
        DeviceConfig.Properties properties =
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_JOB_SCHEDULER);

        // Concurrency limit should be in the range [8, MAX_CONCURRENCY_LIMIT].
        mSteadyStateConcurrencyLimit = Math.max(8, Math.min(MAX_CONCURRENCY_LIMIT,
                properties.getInt(KEY_CONCURRENCY_LIMIT, DEFAULT_CONCURRENCY_LIMIT)));

        mScreenOffAdjustmentDelayMs = properties.getLong(
                KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS, DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS);

        CONFIG_LIMITS_SCREEN_ON.normal.update(properties, mSteadyStateConcurrencyLimit);
        CONFIG_LIMITS_SCREEN_ON.moderate.update(properties, mSteadyStateConcurrencyLimit);
        CONFIG_LIMITS_SCREEN_ON.low.update(properties, mSteadyStateConcurrencyLimit);
        CONFIG_LIMITS_SCREEN_ON.critical.update(properties, mSteadyStateConcurrencyLimit);

        CONFIG_LIMITS_SCREEN_OFF.normal.update(properties, mSteadyStateConcurrencyLimit);
        CONFIG_LIMITS_SCREEN_OFF.moderate.update(properties, mSteadyStateConcurrencyLimit);
        CONFIG_LIMITS_SCREEN_OFF.low.update(properties, mSteadyStateConcurrencyLimit);
        CONFIG_LIMITS_SCREEN_OFF.critical.update(properties, mSteadyStateConcurrencyLimit);

        // Package concurrency limits must in the range [1, mSteadyStateConcurrencyLimit].
        mPkgConcurrencyLimitEj = Math.max(1, Math.min(mSteadyStateConcurrencyLimit,
                properties.getInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, DEFAULT_PKG_CONCURRENCY_LIMIT_EJ)));
        mPkgConcurrencyLimitRegular = Math.max(1, Math.min(mSteadyStateConcurrencyLimit,
                properties.getInt(
                        KEY_PKG_CONCURRENCY_LIMIT_REGULAR, DEFAULT_PKG_CONCURRENCY_LIMIT_REGULAR)));

        mMaxWaitTimeBypassEnabled = properties.getBoolean(
                KEY_ENABLE_MAX_WAIT_TIME_BYPASS, DEFAULT_ENABLE_MAX_WAIT_TIME_BYPASS);
        // UI max wait must be in the range [0, infinity).
        mMaxWaitUIMs = Math.max(0, properties.getLong(KEY_MAX_WAIT_UI_MS, DEFAULT_MAX_WAIT_UI_MS));
        // EJ max wait must be in the range [UI max wait, infinity).
        mMaxWaitEjMs = Math.max(mMaxWaitUIMs,
                properties.getLong(KEY_MAX_WAIT_EJ_MS, DEFAULT_MAX_WAIT_EJ_MS));
        // Regular max wait must be in the range [EJ max wait, infinity).
        mMaxWaitRegularMs = Math.max(mMaxWaitEjMs,
                properties.getLong(KEY_MAX_WAIT_REGULAR_MS, DEFAULT_MAX_WAIT_REGULAR_MS));
    }

    @GuardedBy("mLock")
    public void dumpLocked(IndentingPrintWriter pw, long now, long nowRealtime) {
        pw.println("Concurrency:");

        pw.increaseIndent();
        try {
            pw.println("Configuration:");
            pw.increaseIndent();
            pw.print(KEY_CONCURRENCY_LIMIT, mSteadyStateConcurrencyLimit).println();
            pw.print(KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS, mScreenOffAdjustmentDelayMs).println();
            pw.print(KEY_PKG_CONCURRENCY_LIMIT_EJ, mPkgConcurrencyLimitEj).println();
            pw.print(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, mPkgConcurrencyLimitRegular).println();
            pw.print(KEY_ENABLE_MAX_WAIT_TIME_BYPASS, mMaxWaitTimeBypassEnabled).println();
            pw.print(KEY_MAX_WAIT_UI_MS, mMaxWaitUIMs).println();
            pw.print(KEY_MAX_WAIT_EJ_MS, mMaxWaitEjMs).println();
            pw.print(KEY_MAX_WAIT_REGULAR_MS, mMaxWaitRegularMs).println();
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.normal.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.moderate.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.low.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.critical.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.normal.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.moderate.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.low.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.critical.dump(pw);
            pw.println();
            pw.decreaseIndent();

            pw.print("Screen state: current ");
            pw.print(mCurrentInteractiveState ? "ON" : "OFF");
            pw.print("  effective ");
            pw.print(mEffectiveInteractiveState ? "ON" : "OFF");
            pw.println();

            pw.print("Last screen ON: ");
            TimeUtils.dumpTimeWithDelta(pw, now - nowRealtime + mLastScreenOnRealtime, now);
            pw.println();

            pw.print("Last screen OFF: ");
            TimeUtils.dumpTimeWithDelta(pw, now - nowRealtime + mLastScreenOffRealtime, now);
            pw.println();

            pw.println();

            pw.print("Current work counts: ");
            pw.println(mWorkCountTracker);

            pw.println();

            pw.print("mLastMemoryTrimLevel: ");
            pw.println(mLastMemoryTrimLevel);
            pw.println();

            pw.println("Active Package stats:");
            pw.increaseIndent();
            mActivePkgStats.forEach(pkgStats -> pkgStats.dumpLocked(pw));
            pw.decreaseIndent();
            pw.println();

            pw.print("User Grace Period: ");
            pw.println(mGracePeriodObserver.mGracePeriodExpiration);
            pw.println();

            mStatLogger.dump(pw);
        } finally {
            pw.decreaseIndent();
        }
    }

    @GuardedBy("mLock")
    void dumpContextInfoLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate,
            long nowElapsed, long nowUptime) {
        pw.println("Active jobs:");
        pw.increaseIndent();
        if (mActiveServices.size() == 0) {
            pw.println("N/A");
        }
        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus job = jsc.getRunningJobLocked();

            if (job != null && !predicate.test(job)) {
                continue;
            }

            pw.print("Slot #"); pw.print(i);
            pw.print("(ID="); pw.print(jsc.getId()); pw.print("): ");
            jsc.dumpLocked(pw, nowElapsed);

            if (job != null) {
                pw.increaseIndent();

                pw.increaseIndent();
                job.dump(pw, false, nowElapsed);
                pw.decreaseIndent();

                pw.print("Evaluated bias: ");
                pw.println(JobInfo.getBiasString(job.lastEvaluatedBias));

                pw.print("Active at ");
                TimeUtils.formatDuration(job.madeActive - nowUptime, pw);
                pw.print(", pending for ");
                TimeUtils.formatDuration(job.madeActive - job.madePending, pw);
                pw.decreaseIndent();
                pw.println();
            }
        }
        pw.decreaseIndent();

        pw.println();
        pw.print("Idle contexts (");
        pw.print(mIdleContexts.size());
        pw.println("):");
        pw.increaseIndent();
        for (int i = 0; i < mIdleContexts.size(); i++) {
            JobServiceContext jsc = mIdleContexts.valueAt(i);

            pw.print("ID="); pw.print(jsc.getId()); pw.print(": ");
            jsc.dumpLocked(pw, nowElapsed);
        }
        pw.decreaseIndent();

        if (mNumDroppedContexts > 0) {
            pw.println();
            pw.print("Dropped ");
            pw.print(mNumDroppedContexts);
            pw.println(" contexts");
        }
    }

    public void dumpProtoLocked(ProtoOutputStream proto, long tag, long now, long nowRealtime) {
        final long token = proto.start(tag);

        proto.write(JobConcurrencyManagerProto.CURRENT_INTERACTIVE_STATE, mCurrentInteractiveState);
        proto.write(JobConcurrencyManagerProto.EFFECTIVE_INTERACTIVE_STATE,
                mEffectiveInteractiveState);

        proto.write(JobConcurrencyManagerProto.TIME_SINCE_LAST_SCREEN_ON_MS,
                nowRealtime - mLastScreenOnRealtime);
        proto.write(JobConcurrencyManagerProto.TIME_SINCE_LAST_SCREEN_OFF_MS,
                nowRealtime - mLastScreenOffRealtime);

        proto.write(JobConcurrencyManagerProto.MEMORY_TRIM_LEVEL, mLastMemoryTrimLevel);

        mStatLogger.dumpProto(proto, JobConcurrencyManagerProto.STATS);

        proto.end(token);
    }

    /**
     * Decides whether a job is from the current foreground user or the equivalent.
     */
    @VisibleForTesting
    boolean shouldRunAsFgUserJob(JobStatus job) {
        if (!mShouldRestrictBgUser) return true;
        int userId = job.getSourceUserId();
        UserManagerInternal um = LocalServices.getService(UserManagerInternal.class);
        UserInfo userInfo = um.getUserInfo(userId);

        // If the user has a parent user (e.g. a work profile of another user), the user should be
        // treated equivalent as its parent user.
        if (userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                && userInfo.profileGroupId != userId) {
            userId = userInfo.profileGroupId;
            userInfo = um.getUserInfo(userId);
        }

        int currentUser = LocalServices.getService(ActivityManagerInternal.class)
                .getCurrentUserId();
        // A user is treated as foreground user if any of the followings is true:
        // 1. The user is current user
        // 2. The user is primary user
        // 3. The user's grace period has not expired
        return currentUser == userId || userInfo.isPrimary()
                || mGracePeriodObserver.isWithinGracePeriodForUser(userId);
    }

    int getJobWorkTypes(@NonNull JobStatus js) {
        int classification = 0;

        if (shouldRunAsFgUserJob(js)) {
            if (js.lastEvaluatedBias >= JobInfo.BIAS_TOP_APP) {
                classification |= WORK_TYPE_TOP;
            } else if (js.lastEvaluatedBias >= JobInfo.BIAS_FOREGROUND_SERVICE) {
                classification |= WORK_TYPE_FGS;
            } else {
                classification |= WORK_TYPE_BG;
            }

            if (js.shouldTreatAsExpeditedJob()) {
                classification |= WORK_TYPE_EJ;
            } else if (js.shouldTreatAsUserInitiatedJob()) {
                classification |= WORK_TYPE_UI;
            }
        } else {
            if (js.lastEvaluatedBias >= JobInfo.BIAS_FOREGROUND_SERVICE
                    || js.shouldTreatAsExpeditedJob() || js.shouldTreatAsUserInitiatedJob()) {
                classification |= WORK_TYPE_BGUSER_IMPORTANT;
            }
            // BGUSER_IMPORTANT jobs can also run as BGUSER jobs, so not an 'else' here.
            classification |= WORK_TYPE_BGUSER;
        }

        return classification;
    }

    @VisibleForTesting
    static class WorkTypeConfig {
        private static final String KEY_PREFIX_MAX = CONFIG_KEY_PREFIX_CONCURRENCY + "max_";
        private static final String KEY_PREFIX_MIN = CONFIG_KEY_PREFIX_CONCURRENCY + "min_";
        @VisibleForTesting
        static final String KEY_PREFIX_MAX_TOTAL = CONFIG_KEY_PREFIX_CONCURRENCY + "max_total_";
        @VisibleForTesting
        static final String KEY_PREFIX_MAX_RATIO = KEY_PREFIX_MAX + "ratio_";
        private static final String KEY_PREFIX_MAX_RATIO_TOP = KEY_PREFIX_MAX_RATIO + "top_";
        private static final String KEY_PREFIX_MAX_RATIO_FGS = KEY_PREFIX_MAX_RATIO + "fgs_";
        private static final String KEY_PREFIX_MAX_RATIO_UI = KEY_PREFIX_MAX_RATIO + "ui_";
        private static final String KEY_PREFIX_MAX_RATIO_EJ = KEY_PREFIX_MAX_RATIO + "ej_";
        private static final String KEY_PREFIX_MAX_RATIO_BG = KEY_PREFIX_MAX_RATIO + "bg_";
        private static final String KEY_PREFIX_MAX_RATIO_BGUSER = KEY_PREFIX_MAX_RATIO + "bguser_";
        private static final String KEY_PREFIX_MAX_RATIO_BGUSER_IMPORTANT =
                KEY_PREFIX_MAX_RATIO + "bguser_important_";
        @VisibleForTesting
        static final String KEY_PREFIX_MIN_RATIO = KEY_PREFIX_MIN + "ratio_";
        private static final String KEY_PREFIX_MIN_RATIO_TOP = KEY_PREFIX_MIN_RATIO + "top_";
        private static final String KEY_PREFIX_MIN_RATIO_FGS = KEY_PREFIX_MIN_RATIO + "fgs_";
        private static final String KEY_PREFIX_MIN_RATIO_UI = KEY_PREFIX_MIN_RATIO + "ui_";
        private static final String KEY_PREFIX_MIN_RATIO_EJ = KEY_PREFIX_MIN_RATIO + "ej_";
        private static final String KEY_PREFIX_MIN_RATIO_BG = KEY_PREFIX_MIN_RATIO + "bg_";
        private static final String KEY_PREFIX_MIN_RATIO_BGUSER = KEY_PREFIX_MIN_RATIO + "bguser_";
        private static final String KEY_PREFIX_MIN_RATIO_BGUSER_IMPORTANT =
                KEY_PREFIX_MIN_RATIO + "bguser_important_";
        private final String mConfigIdentifier;

        private int mMaxTotal;
        private final SparseIntArray mMinReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mMaxAllowedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final int mDefaultMaxTotal;
        // We use SparseIntArrays to store floats because there is currently no SparseFloatArray
        // available, and it doesn't seem worth it to add such a data structure just for this
        // use case. We don't use SparseDoubleArrays because DeviceConfig only supports floats and
        // converting between floats and ints is more straightforward than floats and doubles.
        private final SparseIntArray mDefaultMinReservedSlotsRatio =
                new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mDefaultMaxAllowedSlotsRatio =
                new SparseIntArray(NUM_WORK_TYPES);

        WorkTypeConfig(@NonNull String configIdentifier,
                int steadyStateConcurrencyLimit, int defaultMaxTotal,
                List<Pair<Integer, Float>> defaultMinRatio,
                List<Pair<Integer, Float>> defaultMaxRatio) {
            mConfigIdentifier = configIdentifier;
            mDefaultMaxTotal = mMaxTotal = Math.min(defaultMaxTotal, steadyStateConcurrencyLimit);
            int numReserved = 0;
            for (int i = defaultMinRatio.size() - 1; i >= 0; --i) {
                final float ratio = defaultMinRatio.get(i).second;
                final int wt = defaultMinRatio.get(i).first;
                if (ratio < 0 || 1 <= ratio) {
                    // 1 means to reserve everything. This shouldn't be allowed.
                    // We only create new configs on boot, so this should trigger during development
                    // (before the code gets checked in), so this makes sure the hard-coded defaults
                    // make sense. DeviceConfig values will be handled gracefully in update().
                    throw new IllegalArgumentException("Invalid default min ratio: wt=" + wt
                            + " minRatio=" + ratio);
                }
                mDefaultMinReservedSlotsRatio.put(wt, Float.floatToRawIntBits(ratio));
                numReserved += mMaxTotal * ratio;
            }
            if (mDefaultMaxTotal < 0 || numReserved > mDefaultMaxTotal) {
                // We only create new configs on boot, so this should trigger during development
                // (before the code gets checked in), so this makes sure the hard-coded defaults
                // make sense. DeviceConfig values will be handled gracefully in update().
                throw new IllegalArgumentException("Invalid default config: t=" + defaultMaxTotal
                        + " min=" + defaultMinRatio + " max=" + defaultMaxRatio);
            }
            for (int i = defaultMaxRatio.size() - 1; i >= 0; --i) {
                final float ratio = defaultMaxRatio.get(i).second;
                final int wt = defaultMaxRatio.get(i).first;
                final float minRatio =
                        Float.intBitsToFloat(mDefaultMinReservedSlotsRatio.get(wt, 0));
                if (ratio < minRatio || ratio <= 0) {
                    // Max ratio shouldn't be <= 0 or less than minRatio.
                    throw new IllegalArgumentException("Invalid default config:"
                            + " t=" + defaultMaxTotal
                            + " min=" + defaultMinRatio + " max=" + defaultMaxRatio);
                }
                mDefaultMaxAllowedSlotsRatio.put(wt, Float.floatToRawIntBits(ratio));
            }
            update(new DeviceConfig.Properties.Builder(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER).build(), steadyStateConcurrencyLimit);
        }

        void update(@NonNull DeviceConfig.Properties properties, int steadyStateConcurrencyLimit) {
            // Ensure total in the range [1, mSteadyStateConcurrencyLimit].
            mMaxTotal = Math.max(1, Math.min(steadyStateConcurrencyLimit,
                    properties.getInt(KEY_PREFIX_MAX_TOTAL + mConfigIdentifier, mDefaultMaxTotal)));

            final int oneIntBits = Float.floatToIntBits(1);

            mMaxAllowedSlots.clear();
            // Ensure they're in the range [1, total].
            final int maxTop = getMaxValue(properties,
                    KEY_PREFIX_MAX_RATIO_TOP + mConfigIdentifier, WORK_TYPE_TOP, oneIntBits);
            mMaxAllowedSlots.put(WORK_TYPE_TOP, maxTop);
            final int maxFgs = getMaxValue(properties,
                    KEY_PREFIX_MAX_RATIO_FGS + mConfigIdentifier, WORK_TYPE_FGS, oneIntBits);
            mMaxAllowedSlots.put(WORK_TYPE_FGS, maxFgs);
            final int maxUi = getMaxValue(properties,
                    KEY_PREFIX_MAX_RATIO_UI + mConfigIdentifier, WORK_TYPE_UI, oneIntBits);
            mMaxAllowedSlots.put(WORK_TYPE_UI, maxUi);
            final int maxEj = getMaxValue(properties,
                    KEY_PREFIX_MAX_RATIO_EJ + mConfigIdentifier, WORK_TYPE_EJ, oneIntBits);
            mMaxAllowedSlots.put(WORK_TYPE_EJ, maxEj);
            final int maxBg = getMaxValue(properties,
                    KEY_PREFIX_MAX_RATIO_BG + mConfigIdentifier, WORK_TYPE_BG, oneIntBits);
            mMaxAllowedSlots.put(WORK_TYPE_BG, maxBg);
            final int maxBgUserImp = getMaxValue(properties,
                    KEY_PREFIX_MAX_RATIO_BGUSER_IMPORTANT + mConfigIdentifier,
                    WORK_TYPE_BGUSER_IMPORTANT, oneIntBits);
            mMaxAllowedSlots.put(WORK_TYPE_BGUSER_IMPORTANT, maxBgUserImp);
            final int maxBgUser = getMaxValue(properties,
                    KEY_PREFIX_MAX_RATIO_BGUSER + mConfigIdentifier, WORK_TYPE_BGUSER, oneIntBits);
            mMaxAllowedSlots.put(WORK_TYPE_BGUSER, maxBgUser);

            int remaining = mMaxTotal;
            mMinReservedSlots.clear();
            // Ensure top is in the range [1, min(maxTop, total)]
            final int minTop = getMinValue(properties,
                    KEY_PREFIX_MIN_RATIO_TOP + mConfigIdentifier, WORK_TYPE_TOP,
                    1, Math.min(maxTop, mMaxTotal));
            mMinReservedSlots.put(WORK_TYPE_TOP, minTop);
            remaining -= minTop;
            // Ensure fgs is in the range [0, min(maxFgs, remaining)]
            final int minFgs = getMinValue(properties,
                    KEY_PREFIX_MIN_RATIO_FGS + mConfigIdentifier, WORK_TYPE_FGS,
                    0, Math.min(maxFgs, remaining));
            mMinReservedSlots.put(WORK_TYPE_FGS, minFgs);
            remaining -= minFgs;
            // Ensure ui is in the range [0, min(maxUi, remaining)]
            final int minUi = getMinValue(properties,
                    KEY_PREFIX_MIN_RATIO_UI + mConfigIdentifier, WORK_TYPE_UI,
                    0, Math.min(maxUi, remaining));
            mMinReservedSlots.put(WORK_TYPE_UI, minUi);
            remaining -= minUi;
            // Ensure ej is in the range [0, min(maxEj, remaining)]
            final int minEj = getMinValue(properties,
                    KEY_PREFIX_MIN_RATIO_EJ + mConfigIdentifier, WORK_TYPE_EJ,
                    0, Math.min(maxEj, remaining));
            mMinReservedSlots.put(WORK_TYPE_EJ, minEj);
            remaining -= minEj;
            // Ensure bg is in the range [0, min(maxBg, remaining)]
            final int minBg = getMinValue(properties,
                    KEY_PREFIX_MIN_RATIO_BG + mConfigIdentifier, WORK_TYPE_BG,
                    0, Math.min(maxBg, remaining));
            mMinReservedSlots.put(WORK_TYPE_BG, minBg);
            remaining -= minBg;
            // Ensure bg user imp is in the range [0, min(maxBgUserImp, remaining)]
            final int minBgUserImp = getMinValue(properties,
                    KEY_PREFIX_MIN_RATIO_BGUSER_IMPORTANT + mConfigIdentifier,
                    WORK_TYPE_BGUSER_IMPORTANT, 0, Math.min(maxBgUserImp, remaining));
            mMinReservedSlots.put(WORK_TYPE_BGUSER_IMPORTANT, minBgUserImp);
            remaining -= minBgUserImp;
            // Ensure bg user is in the range [0, min(maxBgUser, remaining)]
            final int minBgUser = getMinValue(properties,
                    KEY_PREFIX_MIN_RATIO_BGUSER + mConfigIdentifier, WORK_TYPE_BGUSER,
                    0, Math.min(maxBgUser, remaining));
            mMinReservedSlots.put(WORK_TYPE_BGUSER, minBgUser);
        }

        /**
         * Return the calculated max value for the work type.
         * @param defaultFloatInIntBits A {@code float} value in int bits representation (using
         *                              {@link Float#floatToIntBits(float)}.
         */
        private int getMaxValue(@NonNull DeviceConfig.Properties properties, @NonNull String key,
                int workType, int defaultFloatInIntBits) {
            final float maxRatio = Math.min(1, properties.getFloat(key,
                    Float.intBitsToFloat(
                            mDefaultMaxAllowedSlotsRatio.get(workType, defaultFloatInIntBits))));
            // Max values should be in  the range [1, total].
            return Math.max(1, (int) (mMaxTotal * maxRatio));
        }

        /**
         * Return the calculated min value for the work type.
         */
        private int getMinValue(@NonNull DeviceConfig.Properties properties, @NonNull String key,
                int workType, int lowerLimit, int upperLimit) {
            final float minRatio = Math.min(1,
                    properties.getFloat(key,
                            Float.intBitsToFloat(mDefaultMinReservedSlotsRatio.get(workType))));
            return Math.max(lowerLimit, Math.min(upperLimit, (int) (mMaxTotal * minRatio)));
        }

        int getMaxTotal() {
            return mMaxTotal;
        }

        int getMax(@WorkType int workType) {
            return mMaxAllowedSlots.get(workType, mMaxTotal);
        }

        int getMinReserved(@WorkType int workType) {
            return mMinReservedSlots.get(workType);
        }

        void dump(IndentingPrintWriter pw) {
            pw.print(KEY_PREFIX_MAX_TOTAL + mConfigIdentifier, mMaxTotal).println();
            pw.print(KEY_PREFIX_MIN_RATIO_TOP + mConfigIdentifier,
                            mMinReservedSlots.get(WORK_TYPE_TOP))
                    .println();
            pw.print(KEY_PREFIX_MAX_RATIO_TOP + mConfigIdentifier,
                            mMaxAllowedSlots.get(WORK_TYPE_TOP))
                    .println();
            pw.print(KEY_PREFIX_MIN_RATIO_FGS + mConfigIdentifier,
                            mMinReservedSlots.get(WORK_TYPE_FGS))
                    .println();
            pw.print(KEY_PREFIX_MAX_RATIO_FGS + mConfigIdentifier,
                            mMaxAllowedSlots.get(WORK_TYPE_FGS))
                    .println();
            pw.print(KEY_PREFIX_MIN_RATIO_UI + mConfigIdentifier,
                            mMinReservedSlots.get(WORK_TYPE_UI))
                    .println();
            pw.print(KEY_PREFIX_MAX_RATIO_UI + mConfigIdentifier,
                            mMaxAllowedSlots.get(WORK_TYPE_UI))
                    .println();
            pw.print(KEY_PREFIX_MIN_RATIO_EJ + mConfigIdentifier,
                            mMinReservedSlots.get(WORK_TYPE_EJ))
                    .println();
            pw.print(KEY_PREFIX_MAX_RATIO_EJ + mConfigIdentifier,
                            mMaxAllowedSlots.get(WORK_TYPE_EJ))
                    .println();
            pw.print(KEY_PREFIX_MIN_RATIO_BG + mConfigIdentifier,
                            mMinReservedSlots.get(WORK_TYPE_BG))
                    .println();
            pw.print(KEY_PREFIX_MAX_RATIO_BG + mConfigIdentifier,
                            mMaxAllowedSlots.get(WORK_TYPE_BG))
                    .println();
            pw.print(KEY_PREFIX_MIN_RATIO_BGUSER + mConfigIdentifier,
                    mMinReservedSlots.get(WORK_TYPE_BGUSER_IMPORTANT)).println();
            pw.print(KEY_PREFIX_MAX_RATIO_BGUSER + mConfigIdentifier,
                    mMaxAllowedSlots.get(WORK_TYPE_BGUSER_IMPORTANT)).println();
            pw.print(KEY_PREFIX_MIN_RATIO_BGUSER + mConfigIdentifier,
                    mMinReservedSlots.get(WORK_TYPE_BGUSER)).println();
            pw.print(KEY_PREFIX_MAX_RATIO_BGUSER + mConfigIdentifier,
                    mMaxAllowedSlots.get(WORK_TYPE_BGUSER)).println();
        }
    }

    /** {@link WorkTypeConfig} for each memory trim level. */
    static class WorkConfigLimitsPerMemoryTrimLevel {
        public final WorkTypeConfig normal;
        public final WorkTypeConfig moderate;
        public final WorkTypeConfig low;
        public final WorkTypeConfig critical;

        WorkConfigLimitsPerMemoryTrimLevel(WorkTypeConfig normal, WorkTypeConfig moderate,
                WorkTypeConfig low, WorkTypeConfig critical) {
            this.normal = normal;
            this.moderate = moderate;
            this.low = low;
            this.critical = critical;
        }
    }

    /**
     * This class keeps the track of when a user's grace period expires.
     */
    @VisibleForTesting
    static class GracePeriodObserver extends UserSwitchObserver {
        // Key is UserId and Value is the time when grace period expires
        @VisibleForTesting
        final SparseLongArray mGracePeriodExpiration = new SparseLongArray();
        private int mCurrentUserId;
        @VisibleForTesting
        int mGracePeriod;
        private final UserManagerInternal mUserManagerInternal;
        final Object mLock = new Object();


        GracePeriodObserver(Context context) {
            mCurrentUserId = LocalServices.getService(ActivityManagerInternal.class)
                    .getCurrentUserId();
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            mGracePeriod = Math.max(0, context.getResources().getInteger(
                    R.integer.config_jobSchedulerUserGracePeriod));
        }

        @Override
        public void onUserSwitchComplete(int newUserId) {
            final long expiration = sElapsedRealtimeClock.millis() + mGracePeriod;
            synchronized (mLock) {
                if (mCurrentUserId != UserHandle.USER_NULL
                        && mUserManagerInternal.exists(mCurrentUserId)) {
                    mGracePeriodExpiration.append(mCurrentUserId, expiration);
                }
                mGracePeriodExpiration.delete(newUserId);
                mCurrentUserId = newUserId;
            }
        }

        void onUserRemoved(int userId) {
            synchronized (mLock) {
                mGracePeriodExpiration.delete(userId);
            }
        }

        @VisibleForTesting
        public boolean isWithinGracePeriodForUser(int userId) {
            synchronized (mLock) {
                return userId == mCurrentUserId
                        || sElapsedRealtimeClock.millis()
                        < mGracePeriodExpiration.get(userId, Long.MAX_VALUE);
            }
        }
    }

    /**
     * This class decides, taking into account the current {@link WorkTypeConfig} and how many jobs
     * are running/pending, how many more job can start.
     *
     * Extracted for testing and logging.
     */
    @VisibleForTesting
    static class WorkCountTracker {
        private int mConfigMaxTotal;
        private final SparseIntArray mConfigNumReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mConfigAbsoluteMaxSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mRecycledReserved = new SparseIntArray(NUM_WORK_TYPES);

        /**
         * Numbers may be lower in this than in {@link #mConfigNumReservedSlots} if there aren't
         * enough ready jobs of a type to take up all of the desired reserved slots.
         */
        private final SparseIntArray mNumActuallyReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumPendingJobs = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumRunningJobs = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumStartingJobs = new SparseIntArray(NUM_WORK_TYPES);
        private int mNumUnspecializedRemaining = 0;

        void setConfig(@NonNull WorkTypeConfig workTypeConfig) {
            mConfigMaxTotal = workTypeConfig.getMaxTotal();
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                mConfigNumReservedSlots.put(workType, workTypeConfig.getMinReserved(workType));
                mConfigAbsoluteMaxSlots.put(workType, workTypeConfig.getMax(workType));
            }

            mNumUnspecializedRemaining = mConfigMaxTotal;
            for (int i = mNumRunningJobs.size() - 1; i >= 0; --i) {
                mNumUnspecializedRemaining -= Math.max(mNumRunningJobs.valueAt(i),
                        mConfigNumReservedSlots.get(mNumRunningJobs.keyAt(i)));
            }
        }

        void resetCounts() {
            mNumActuallyReservedSlots.clear();
            mNumPendingJobs.clear();
            mNumRunningJobs.clear();
            resetStagingCount();
        }

        void resetStagingCount() {
            mNumStartingJobs.clear();
        }

        void incrementRunningJobCount(@WorkType int workType) {
            mNumRunningJobs.put(workType, mNumRunningJobs.get(workType) + 1);
        }

        void incrementPendingJobCount(int workTypes) {
            adjustPendingJobCount(workTypes, true);
        }

        void decrementPendingJobCount(int workTypes) {
            if (adjustPendingJobCount(workTypes, false) > 1) {
                // We don't need to adjust reservations if only one work type was modified
                // because that work type is the one we're using.

                for (int workType = 1; workType <= workTypes; workType <<= 1) {
                    if ((workType & workTypes) == workType) {
                        maybeAdjustReservations(workType);
                    }
                }
            }
        }

        /** Returns the number of WorkTypes that were modified. */
        private int adjustPendingJobCount(int workTypes, boolean add) {
            final int adj = add ? 1 : -1;

            int numAdj = 0;
            // We don't know which type we'll classify the job as when we run it yet, so make sure
            // we have space in all applicable slots.
            for (int workType = 1; workType <= workTypes; workType <<= 1) {
                if ((workTypes & workType) == workType) {
                    mNumPendingJobs.put(workType, mNumPendingJobs.get(workType) + adj);
                    numAdj++;
                }
            }

            return numAdj;
        }

        void stageJob(@WorkType int workType, int allWorkTypes) {
            final int newNumStartingJobs = mNumStartingJobs.get(workType) + 1;
            mNumStartingJobs.put(workType, newNumStartingJobs);
            decrementPendingJobCount(allWorkTypes);
            if (newNumStartingJobs + mNumRunningJobs.get(workType)
                    > mNumActuallyReservedSlots.get(workType)) {
                mNumUnspecializedRemaining--;
            }
        }

        void onStagedJobFailed(@WorkType int workType) {
            final int oldNumStartingJobs = mNumStartingJobs.get(workType);
            if (oldNumStartingJobs == 0) {
                Slog.e(TAG, "# staged jobs for " + workType + " went negative.");
                // We are in a bad state. We will eventually recover when the pending list is
                // regenerated.
                return;
            }
            mNumStartingJobs.put(workType, oldNumStartingJobs - 1);
            maybeAdjustReservations(workType);
        }

        private void maybeAdjustReservations(@WorkType int workType) {
            // Always make sure we reserve the minimum number of slots in case new jobs become ready
            // soon.
            final int numRemainingForType = Math.max(mConfigNumReservedSlots.get(workType),
                    mNumRunningJobs.get(workType) + mNumStartingJobs.get(workType)
                            + mNumPendingJobs.get(workType));
            if (numRemainingForType < mNumActuallyReservedSlots.get(workType)) {
                // We've run all jobs for this type. Let another type use it now.
                mNumActuallyReservedSlots.put(workType, numRemainingForType);
                int assignWorkType = WORK_TYPE_NONE;
                for (int i = 0; i < mNumActuallyReservedSlots.size(); ++i) {
                    int wt = mNumActuallyReservedSlots.keyAt(i);
                    if (assignWorkType == WORK_TYPE_NONE || wt < assignWorkType) {
                        // Try to give this slot to the highest bias one within its limits.
                        int total = mNumRunningJobs.get(wt) + mNumStartingJobs.get(wt)
                                + mNumPendingJobs.get(wt);
                        if (mNumActuallyReservedSlots.valueAt(i) < mConfigAbsoluteMaxSlots.get(wt)
                                && total > mNumActuallyReservedSlots.valueAt(i)) {
                            assignWorkType = wt;
                        }
                    }
                }
                if (assignWorkType != WORK_TYPE_NONE) {
                    mNumActuallyReservedSlots.put(assignWorkType,
                            mNumActuallyReservedSlots.get(assignWorkType) + 1);
                } else {
                    mNumUnspecializedRemaining++;
                }
            }
        }

        void onJobStarted(@WorkType int workType) {
            mNumRunningJobs.put(workType, mNumRunningJobs.get(workType) + 1);
            final int oldNumStartingJobs = mNumStartingJobs.get(workType);
            if (oldNumStartingJobs == 0) {
                Slog.e(TAG, "# stated jobs for " + workType + " went negative.");
                // We are in a bad state. We will eventually recover when the pending list is
                // regenerated. For now, only modify the running count.
            } else {
                mNumStartingJobs.put(workType, oldNumStartingJobs - 1);
            }
        }

        void onJobFinished(@WorkType int workType) {
            final int newNumRunningJobs = mNumRunningJobs.get(workType) - 1;
            if (newNumRunningJobs < 0) {
                // We are in a bad state. We will eventually recover when the pending list is
                // regenerated.
                Slog.e(TAG, "# running jobs for " + workType + " went negative.");
                return;
            }
            mNumRunningJobs.put(workType, newNumRunningJobs);
            maybeAdjustReservations(workType);
        }

        void onCountDone() {
            // Calculate how many slots to reserve for each work type. "Unspecialized" slots will
            // be reserved for higher importance types first (ie. top before ej before bg).
            // Steps:
            //   1. Account for slots for already running jobs
            //   2. Use remaining unaccounted slots to try and ensure minimum reserved slots
            //   3. Allocate remaining up to max, based on importance

            mNumUnspecializedRemaining = mConfigMaxTotal;

            // Step 1
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                int run = mNumRunningJobs.get(workType);
                mRecycledReserved.put(workType, run);
                mNumUnspecializedRemaining -= run;
            }

            // Step 2
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                int num = mNumRunningJobs.get(workType) + mNumPendingJobs.get(workType);
                int res = mRecycledReserved.get(workType);
                int fillUp = Math.max(0, Math.min(mNumUnspecializedRemaining,
                        Math.min(num, mConfigNumReservedSlots.get(workType) - res)));
                res += fillUp;
                mRecycledReserved.put(workType, res);
                mNumUnspecializedRemaining -= fillUp;
            }

            // Step 3
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                int num = mNumRunningJobs.get(workType) + mNumPendingJobs.get(workType);
                int res = mRecycledReserved.get(workType);
                int unspecializedAssigned = Math.max(0,
                        Math.min(mNumUnspecializedRemaining,
                                Math.min(mConfigAbsoluteMaxSlots.get(workType), num) - res));
                mNumActuallyReservedSlots.put(workType, res + unspecializedAssigned);
                mNumUnspecializedRemaining -= unspecializedAssigned;
            }
        }

        int canJobStart(int workTypes) {
            for (int workType = 1; workType <= workTypes; workType <<= 1) {
                if ((workTypes & workType) == workType) {
                    final int maxAllowed = Math.min(
                            mConfigAbsoluteMaxSlots.get(workType),
                            mNumActuallyReservedSlots.get(workType) + mNumUnspecializedRemaining);
                    if (mNumRunningJobs.get(workType) + mNumStartingJobs.get(workType)
                            < maxAllowed) {
                        return workType;
                    }
                }
            }
            return WORK_TYPE_NONE;
        }

        int canJobStart(int workTypes, @WorkType int replacingWorkType) {
            final boolean changedNums;
            int oldNumRunning = mNumRunningJobs.get(replacingWorkType);
            if (replacingWorkType != WORK_TYPE_NONE && oldNumRunning > 0) {
                mNumRunningJobs.put(replacingWorkType, oldNumRunning - 1);
                // Lazy implementation to avoid lots of processing. Best way would be to go
                // through the whole process of adjusting reservations, but the processing cost
                // is likely not worth it.
                mNumUnspecializedRemaining++;
                changedNums = true;
            } else {
                changedNums = false;
            }

            final int ret = canJobStart(workTypes);
            if (changedNums) {
                mNumRunningJobs.put(replacingWorkType, oldNumRunning);
                mNumUnspecializedRemaining--;
            }
            return ret;
        }

        int getPendingJobCount(@WorkType final int workType) {
            return mNumPendingJobs.get(workType, 0);
        }

        int getRunningJobCount(@WorkType final int workType) {
            return mNumRunningJobs.get(workType, 0);
        }

        boolean isOverTypeLimit(@WorkType final int workType) {
            return getRunningJobCount(workType) > mConfigAbsoluteMaxSlots.get(workType);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();

            sb.append("Config={");
            sb.append("tot=").append(mConfigMaxTotal);
            sb.append(" mins=");
            sb.append(mConfigNumReservedSlots);
            sb.append(" maxs=");
            sb.append(mConfigAbsoluteMaxSlots);
            sb.append("}");

            sb.append(", act res=").append(mNumActuallyReservedSlots);
            sb.append(", Pending=").append(mNumPendingJobs);
            sb.append(", Running=").append(mNumRunningJobs);
            sb.append(", Staged=").append(mNumStartingJobs);
            sb.append(", # unspecialized remaining=").append(mNumUnspecializedRemaining);

            return sb.toString();
        }
    }

    @VisibleForTesting
    static class PackageStats {
        public int userId;
        public String packageName;
        public int numRunningEj;
        public int numRunningRegular;
        public int numStagedEj;
        public int numStagedRegular;

        private void setPackage(int userId, @NonNull String packageName) {
            this.userId = userId;
            this.packageName = packageName;
            numRunningEj = numRunningRegular = 0;
            resetStagedCount();
        }

        private void resetStagedCount() {
            numStagedEj = numStagedRegular = 0;
        }

        private void adjustRunningCount(boolean add, boolean forEj) {
            if (forEj) {
                numRunningEj = Math.max(0, numRunningEj + (add ? 1 : -1));
            } else {
                numRunningRegular = Math.max(0, numRunningRegular + (add ? 1 : -1));
            }
        }

        private void adjustStagedCount(boolean add, boolean forEj) {
            if (forEj) {
                numStagedEj = Math.max(0, numStagedEj + (add ? 1 : -1));
            } else {
                numStagedRegular = Math.max(0, numStagedRegular + (add ? 1 : -1));
            }
        }

        @GuardedBy("mLock")
        private void dumpLocked(IndentingPrintWriter pw) {
            pw.print("PackageStats{");
            pw.print(userId);
            pw.print("-");
            pw.print(packageName);
            pw.print("#runEJ", numRunningEj);
            pw.print("#runReg", numRunningRegular);
            pw.print("#stagedEJ", numStagedEj);
            pw.print("#stagedReg", numStagedRegular);
            pw.println("}");
        }
    }

    @VisibleForTesting
    static final class ContextAssignment {
        public JobServiceContext context;
        public int preferredUid = JobServiceContext.NO_PREFERRED_UID;
        public int workType = WORK_TYPE_NONE;
        public String preemptReason;
        public int preemptReasonCode = JobParameters.STOP_REASON_UNDEFINED;
        public long timeUntilStoppableMs;
        public String shouldStopJobReason;
        public JobStatus newJob;
        public int newWorkType = WORK_TYPE_NONE;

        void clear() {
            context = null;
            preferredUid = JobServiceContext.NO_PREFERRED_UID;
            workType = WORK_TYPE_NONE;
            preemptReason = null;
            preemptReasonCode = JobParameters.STOP_REASON_UNDEFINED;
            timeUntilStoppableMs = 0;
            shouldStopJobReason = null;
            newJob = null;
            newWorkType = WORK_TYPE_NONE;
        }
    }

    @VisibleForTesting
    static final class AssignmentInfo {
        public long minPreferredUidOnlyWaitingTimeMs;
        public int numRunningImmediacyPrivileged;
        public int numRunningUi;
        public int numRunningEj;
        public int numRunningReg;

        void clear() {
            minPreferredUidOnlyWaitingTimeMs = 0;
            numRunningImmediacyPrivileged = 0;
            numRunningUi = 0;
            numRunningEj = 0;
            numRunningReg = 0;
        }
    }

    // TESTING HELPERS

    @VisibleForTesting
    void addRunningJobForTesting(@NonNull JobStatus job) {
        mRunningJobs.add(job);
        final PackageStats packageStats =
                getPackageStatsForTesting(job.getSourceUserId(), job.getSourcePackageName());
        packageStats.adjustRunningCount(true, job.shouldTreatAsExpeditedJob());

        final JobServiceContext context;
        if (mIdleContexts.size() > 0) {
            context = mIdleContexts.removeAt(mIdleContexts.size() - 1);
        } else {
            context = createNewJobServiceContext();
        }
        context.executeRunnableJob(job, mWorkCountTracker.canJobStart(getJobWorkTypes(job)));
        mActiveServices.add(context);
    }

    @VisibleForTesting
    int getPackageConcurrencyLimitEj() {
        return mPkgConcurrencyLimitEj;
    }

    int getPackageConcurrencyLimitRegular() {
        return mPkgConcurrencyLimitRegular;
    }

    /** Gets the {@link PackageStats} object for the app and saves it for testing use. */
    @NonNull
    @VisibleForTesting
    PackageStats getPackageStatsForTesting(int userId, @NonNull String packageName) {
        final PackageStats packageStats = getPkgStatsLocked(userId, packageName);
        mActivePkgStats.add(userId, packageName, packageStats);
        return packageStats;
    }

    @VisibleForTesting
    static class Injector {
        @NonNull
        JobServiceContext createJobServiceContext(JobSchedulerService service,
                JobConcurrencyManager concurrencyManager,
                JobNotificationCoordinator notificationCoordinator, IBatteryStats batteryStats,
                JobPackageTracker tracker, Looper looper) {
            return new JobServiceContext(service, concurrencyManager, notificationCoordinator,
                    batteryStats, tracker, looper);
        }
    }
}
