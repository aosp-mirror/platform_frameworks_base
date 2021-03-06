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

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.UserSwitchObserver;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.util.StatLogger;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.LocalServices;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;
import com.android.server.pm.UserManagerInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Iterator;
import java.util.List;

/**
 * This class decides, given the various configuration and the system status, which jobs can start
 * and which {@link JobServiceContext} to run each job on.
 */
class JobConcurrencyManager {
    private static final String TAG = JobSchedulerService.TAG + ".Concurrency";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    static final String CONFIG_KEY_PREFIX_CONCURRENCY = "concurrency_";
    private static final String KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS =
            CONFIG_KEY_PREFIX_CONCURRENCY + "screen_off_adjustment_delay_ms";
    private static final long DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS = 30_000;

    /**
     * Set of possible execution types that a job can have. The actual type(s) of a job are based
     * on the {@link JobStatus#lastEvaluatedPriority}, which is typically evaluated right before
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
    /** The job is allowed to run as an expedited job for a currently active user. */
    static final int WORK_TYPE_EJ = 1 << 2;
    /**
     * The job does not satisfy any of the conditions for {@link #WORK_TYPE_TOP},
     * {@link #WORK_TYPE_FGS}, or {@link #WORK_TYPE_EJ}, but is for a currently active user, so
     * can run as a background job.
     */
    static final int WORK_TYPE_BG = 1 << 3;
    /**
     * The job does not satisfy any of the conditions for {@link #WORK_TYPE_TOP},
     * {@link #WORK_TYPE_FGS}, or {@link #WORK_TYPE_EJ}, but is for a completely background user,
     * so can run as a background user job.
     */
    static final int WORK_TYPE_BGUSER = 1 << 4;
    @VisibleForTesting
    static final int NUM_WORK_TYPES = 5;
    private static final int ALL_WORK_TYPES = (1 << NUM_WORK_TYPES) - 1;

    @IntDef(prefix = {"WORK_TYPE_"}, flag = true, value = {
            WORK_TYPE_NONE,
            WORK_TYPE_TOP,
            WORK_TYPE_FGS,
            WORK_TYPE_EJ,
            WORK_TYPE_BG,
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
            case WORK_TYPE_EJ:
                return "EJ";
            case WORK_TYPE_BG:
                return "BG";
            case WORK_TYPE_BGUSER:
                return "BGUSER";
            default:
                return "WORK(" + workType + ")";
        }
    }

    private final Object mLock;
    private final JobSchedulerService mService;
    private final Context mContext;
    private final Handler mHandler;

    private PowerManager mPowerManager;

    private boolean mCurrentInteractiveState;
    private boolean mEffectiveInteractiveState;

    private long mLastScreenOnRealtime;
    private long mLastScreenOffRealtime;

    private static final int MAX_JOB_CONTEXTS_COUNT = JobSchedulerService.MAX_JOB_CONTEXTS_COUNT;

    private static final WorkConfigLimitsPerMemoryTrimLevel CONFIG_LIMITS_SCREEN_ON =
            new WorkConfigLimitsPerMemoryTrimLevel(
                    new WorkTypeConfig("screen_on_normal", 11,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 6), Pair.create(WORK_TYPE_BGUSER, 4))
                    ),
                    new WorkTypeConfig("screen_on_moderate", 9,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2))
                    ),
                    new WorkTypeConfig("screen_on_low", 6,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1))
                    ),
                    new WorkTypeConfig("screen_on_critical", 6,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1))
                    )
            );
    private static final WorkConfigLimitsPerMemoryTrimLevel CONFIG_LIMITS_SCREEN_OFF =
            new WorkConfigLimitsPerMemoryTrimLevel(
                    new WorkTypeConfig("screen_off_normal", 15,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 2),
                                    Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 6), Pair.create(WORK_TYPE_BGUSER, 4))
                    ),
                    new WorkTypeConfig("screen_off_moderate", 15,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_FGS, 2),
                                    Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2))
                    ),
                    new WorkTypeConfig("screen_off_low", 9,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1))
                    ),
                    new WorkTypeConfig("screen_off_critical", 6,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1))
                    )
            );

    /**
     * This array essentially stores the state of mActiveServices array.
     * The ith index stores the job present on the ith JobServiceContext.
     * We manipulate this array until we arrive at what jobs should be running on
     * what JobServiceContext.
     */
    JobStatus[] mRecycledAssignContextIdToJobMap = new JobStatus[MAX_JOB_CONTEXTS_COUNT];

    boolean[] mRecycledSlotChanged = new boolean[MAX_JOB_CONTEXTS_COUNT];

    int[] mRecycledPreferredUidForContext = new int[MAX_JOB_CONTEXTS_COUNT];

    int[] mRecycledWorkTypeForContext = new int[MAX_JOB_CONTEXTS_COUNT];

    String[] mRecycledPreemptReasonForContext = new String[MAX_JOB_CONTEXTS_COUNT];

    String[] mRecycledShouldStopJobReason = new String[MAX_JOB_CONTEXTS_COUNT];

    private final ArraySet<JobStatus> mRunningJobs = new ArraySet<>();

    private final WorkCountTracker mWorkCountTracker = new WorkCountTracker();

    private WorkTypeConfig mWorkTypeConfig = CONFIG_LIMITS_SCREEN_OFF.normal;

    /** Wait for this long after screen off before adjusting the job concurrency. */
    private long mScreenOffAdjustmentDelayMs = DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS;

    /** Current memory trim level. */
    private int mLastMemoryTrimLevel;

    /** Used to throttle heavy API calls. */
    private long mNextSystemStateRefreshTime;
    private static final int SYSTEM_STATE_REFRESH_MIN_INTERVAL = 1000;

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
        mService = service;
        mLock = mService.mLock;
        mContext = service.getTestableContext();

        mHandler = JobSchedulerBackgroundThread.getHandler();

        mGracePeriodObserver = new GracePeriodObserver(mContext);
        mShouldRestrictBgUser = mContext.getResources().getBoolean(
                R.bool.config_jobSchedulerRestrictBackgroundUser);
    }

    public void onSystemReady() {
        mPowerManager = mContext.getSystemService(PowerManager.class);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mReceiver, filter);
        try {
            ActivityManager.getService().registerUserSwitchObserver(mGracePeriodObserver, TAG);
        } catch (RemoteException e) {
        }

        onInteractiveStateChanged(mPowerManager.isInteractive());
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
     * If no contexts are available, preempts lower priority jobs to
     * run higher priority ones.
     * Lock on mJobs before calling this function.
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

        final List<JobStatus> pendingJobs = mService.mPendingJobs;
        final List<JobServiceContext> activeServices = mService.mActiveServices;

        // To avoid GC churn, we recycle the arrays.
        JobStatus[] contextIdToJobMap = mRecycledAssignContextIdToJobMap;
        boolean[] slotChanged = mRecycledSlotChanged;
        int[] preferredUidForContext = mRecycledPreferredUidForContext;
        int[] workTypeForContext = mRecycledWorkTypeForContext;
        String[] preemptReasonForContext = mRecycledPreemptReasonForContext;
        String[] shouldStopJobReason = mRecycledShouldStopJobReason;

        updateCounterConfigLocked();
        // Reset everything since we'll re-evaluate the current state.
        mWorkCountTracker.resetCounts();

        // Update the priorities of jobs that aren't running, and also count the pending work types.
        // Do this before the following loop to hopefully reduce the cost of
        // shouldStopRunningJobLocked().
        updateNonRunningPrioritiesLocked(pendingJobs, true);

        for (int i = 0; i < MAX_JOB_CONTEXTS_COUNT; i++) {
            final JobServiceContext js = activeServices.get(i);
            final JobStatus status = js.getRunningJobLocked();

            if ((contextIdToJobMap[i] = status) != null) {
                mWorkCountTracker.incrementRunningJobCount(js.getRunningJobWorkType());
                workTypeForContext[i] = js.getRunningJobWorkType();
            }

            slotChanged[i] = false;
            preferredUidForContext[i] = js.getPreferredUid();
            preemptReasonForContext[i] = null;
            shouldStopJobReason[i] = shouldStopRunningJobLocked(js);
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs initial"));
        }

        mWorkCountTracker.onCountDone();

        for (int i = 0; i < pendingJobs.size(); i++) {
            final JobStatus nextPending = pendingJobs.get(i);

            if (mRunningJobs.contains(nextPending)) {
                continue;
            }

            // Find an available slot for nextPending. The context should be available OR
            // it should have lowest priority among all running jobs
            // (sharing the same Uid as nextPending)
            int minPriorityForPreemption = Integer.MAX_VALUE;
            int selectedContextId = -1;
            int allWorkTypes = getJobWorkTypes(nextPending);
            int workType = mWorkCountTracker.canJobStart(allWorkTypes);
            boolean startingJob = false;
            String preemptReason = null;
            // TODO(141645789): rewrite this to look at empty contexts first so we don't
            // unnecessarily preempt
            for (int j = 0; j < MAX_JOB_CONTEXTS_COUNT; j++) {
                JobStatus job = contextIdToJobMap[j];
                int preferredUid = preferredUidForContext[j];
                if (job == null) {
                    final boolean preferredUidOkay = (preferredUid == nextPending.getUid())
                            || (preferredUid == JobServiceContext.NO_PREFERRED_UID);

                    if (preferredUidOkay && workType != WORK_TYPE_NONE) {
                        // This slot is free, and we haven't yet hit the limit on
                        // concurrent jobs...  we can just throw the job in to here.
                        selectedContextId = j;
                        startingJob = true;
                        break;
                    }
                    // No job on this context, but nextPending can't run here because
                    // the context has a preferred Uid or we have reached the limit on
                    // concurrent jobs.
                    continue;
                }
                if (job.getUid() != nextPending.getUid()) {
                    // Maybe stop the job if it has had its day in the sun.
                    final String reason = shouldStopJobReason[j];
                    if (reason != null && mWorkCountTracker.canJobStart(allWorkTypes,
                            activeServices.get(j).getRunningJobWorkType()) != WORK_TYPE_NONE) {
                        // Right now, the way the code is set up, we don't need to explicitly
                        // assign the new job to this context since we'll reassign when the
                        // preempted job finally stops.
                        preemptReason = reason;
                    }
                    continue;
                }

                final int jobPriority = mService.evaluateJobPriorityLocked(job);
                if (jobPriority >= nextPending.lastEvaluatedPriority) {
                    continue;
                }

                if (minPriorityForPreemption > jobPriority) {
                    // Step down the preemption threshold - wind up replacing
                    // the lowest-priority running job
                    minPriorityForPreemption = jobPriority;
                    selectedContextId = j;
                    preemptReason = "higher priority job found";
                    // In this case, we're just going to preempt a low priority job, we're not
                    // actually starting a job, so don't set startingJob.
                }
            }
            if (selectedContextId != -1) {
                contextIdToJobMap[selectedContextId] = nextPending;
                slotChanged[selectedContextId] = true;
                preemptReasonForContext[selectedContextId] = preemptReason;
            }
            if (startingJob) {
                // Increase the counters when we're going to start a job.
                workTypeForContext[selectedContextId] = workType;
                mWorkCountTracker.stageJob(workType, allWorkTypes);
            }
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs final"));
        }

        if (DEBUG) {
            Slog.d(TAG, "assignJobsToContexts: " + mWorkCountTracker.toString());
        }

        for (int i = 0; i < MAX_JOB_CONTEXTS_COUNT; i++) {
            boolean preservePreferredUid = false;
            if (slotChanged[i]) {
                JobStatus js = activeServices.get(i).getRunningJobLocked();
                if (js != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "preempting job: "
                                + activeServices.get(i).getRunningJobLocked());
                    }
                    // preferredUid will be set to uid of currently running job.
                    activeServices.get(i).cancelExecutingJobLocked(
                            JobParameters.REASON_PREEMPT, preemptReasonForContext[i]);
                    preservePreferredUid = true;
                } else {
                    final JobStatus pendingJob = contextIdToJobMap[i];
                    if (DEBUG) {
                        Slog.d(TAG, "About to run job on context "
                                + i + ", job: " + pendingJob);
                    }
                    startJobLocked(activeServices.get(i), pendingJob, workTypeForContext[i]);
                }
            }
            if (!preservePreferredUid) {
                activeServices.get(i).clearPreferredUid();
            }
        }
        mWorkCountTracker.resetStagingCount();
        noteConcurrency();
    }

    private void noteConcurrency() {
        mService.mJobPackageTracker.noteConcurrency(mRunningJobs.size(),
                // TODO: log per type instead of only TOP
                mWorkCountTracker.getRunningJobCount(WORK_TYPE_TOP));
    }

    @GuardedBy("mLock")
    private void updateNonRunningPrioritiesLocked(@NonNull final List<JobStatus> pendingJobs,
            boolean updateCounter) {
        for (int i = 0; i < pendingJobs.size(); i++) {
            final JobStatus pending = pendingJobs.get(i);

            // If job is already running, go to next job.
            if (mRunningJobs.contains(pending)) {
                continue;
            }

            pending.lastEvaluatedPriority = mService.evaluateJobPriorityLocked(pending);

            if (updateCounter) {
                mWorkCountTracker.incrementPendingJobCount(getJobWorkTypes(pending));
            }
        }
    }

    @GuardedBy("mLock")
    private void startJobLocked(@NonNull JobServiceContext worker, @NonNull JobStatus jobStatus,
            @WorkType final int workType) {
        final List<StateController> controllers = mService.mControllers;
        for (int ic = 0; ic < controllers.size(); ic++) {
            controllers.get(ic).prepareForExecutionLocked(jobStatus);
        }
        if (!worker.executeRunnableJob(jobStatus, workType)) {
            Slog.e(TAG, "Error executing " + jobStatus);
            mWorkCountTracker.onStagedJobFailed(workType);
        } else {
            mRunningJobs.add(jobStatus);
            mWorkCountTracker.onJobStarted(workType);
        }
        final List<JobStatus> pendingJobs = mService.mPendingJobs;
        if (pendingJobs.remove(jobStatus)) {
            mService.mJobPackageTracker.noteNonpending(jobStatus);
        }
    }

    @GuardedBy("mLock")
    void onJobCompletedLocked(@NonNull JobServiceContext worker, @NonNull JobStatus jobStatus,
            @WorkType final int workType) {
        mWorkCountTracker.onJobFinished(workType);
        mRunningJobs.remove(jobStatus);
        final List<JobStatus> pendingJobs = mService.mPendingJobs;
        if (worker.getPreferredUid() != JobServiceContext.NO_PREFERRED_UID) {
            updateCounterConfigLocked();
            // Preemption case needs special care.
            updateNonRunningPrioritiesLocked(pendingJobs, false);

            JobStatus highestPriorityJob = null;
            int highPriWorkType = workType;
            int highPriAllWorkTypes = workType;
            JobStatus backupJob = null;
            int backupWorkType = WORK_TYPE_NONE;
            int backupAllWorkTypes = WORK_TYPE_NONE;
            for (int i = 0; i < pendingJobs.size(); i++) {
                final JobStatus nextPending = pendingJobs.get(i);

                if (mRunningJobs.contains(nextPending)) {
                    continue;
                }

                if (worker.getPreferredUid() != nextPending.getUid()) {
                    if (backupJob == null) {
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

                if (highestPriorityJob == null
                        || highestPriorityJob.lastEvaluatedPriority
                        < nextPending.lastEvaluatedPriority) {
                    highestPriorityJob = nextPending;
                } else {
                    continue;
                }

                // In this path, we pre-empted an existing job. We don't fully care about the
                // reserved slots. We should just run the highest priority job we can find,
                // though it would be ideal to use an available WorkType slot instead of
                // overloading slots.
                highPriAllWorkTypes = getJobWorkTypes(nextPending);
                final int workAsType = mWorkCountTracker.canJobStart(highPriAllWorkTypes);
                if (workAsType == WORK_TYPE_NONE) {
                    // Just use the preempted job's work type since this new one is technically
                    // replacing it anyway.
                    highPriWorkType = workType;
                } else {
                    highPriWorkType = workAsType;
                }
            }
            if (highestPriorityJob != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Running job " + jobStatus + " as preemption");
                }
                mWorkCountTracker.stageJob(highPriWorkType, highPriAllWorkTypes);
                startJobLocked(worker, highestPriorityJob, highPriWorkType);
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Couldn't find preemption job for uid " + worker.getPreferredUid());
                }
                worker.clearPreferredUid();
                if (backupJob != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Running job " + jobStatus + " instead");
                    }
                    mWorkCountTracker.stageJob(backupWorkType, backupAllWorkTypes);
                    startJobLocked(worker, backupJob, backupWorkType);
                }
            }
        } else if (pendingJobs.size() > 0) {
            updateCounterConfigLocked();
            updateNonRunningPrioritiesLocked(pendingJobs, false);

            // This slot is now free and we have pending jobs. Start the highest priority job we
            // find.
            JobStatus highestPriorityJob = null;
            int highPriWorkType = workType;
            int highPriAllWorkTypes = workType;
            for (int i = 0; i < pendingJobs.size(); i++) {
                final JobStatus nextPending = pendingJobs.get(i);

                if (mRunningJobs.contains(nextPending)) {
                    continue;
                }

                final int allWorkTypes = getJobWorkTypes(nextPending);
                final int workAsType = mWorkCountTracker.canJobStart(allWorkTypes);
                if (workAsType == WORK_TYPE_NONE) {
                    continue;
                }
                if (highestPriorityJob == null
                        || highestPriorityJob.lastEvaluatedPriority
                        < nextPending.lastEvaluatedPriority) {
                    highestPriorityJob = nextPending;
                    highPriWorkType = workAsType;
                    highPriAllWorkTypes = allWorkTypes;
                }
            }

            if (highestPriorityJob != null) {
                // This slot is free, and we haven't yet hit the limit on
                // concurrent jobs...  we can just throw the job in to here.
                if (DEBUG) {
                    Slog.d(TAG, "About to run job: " + jobStatus);
                }
                mWorkCountTracker.stageJob(highPriWorkType, highPriAllWorkTypes);
                startJobLocked(worker, highestPriorityJob, highPriWorkType);
            }
        }

        noteConcurrency();
    }

    /**
     * Returns {@code null} if the job can continue running and a non-null String if the job should
     * be stopped. The non-null String details the reason for stopping the job. A job will generally
     * be stopped if there similar job types waiting to be run and stopping this job would allow
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

        // Update config in case memory usage has changed significantly.
        updateCounterConfigLocked();

        @WorkType final int workType = context.getRunningJobWorkType();

        if (mRunningJobs.size() > mWorkTypeConfig.getMaxTotal()
                || mWorkCountTracker.isOverTypeLimit(workType)) {
            return "too many jobs running";
        }

        final List<JobStatus> pendingJobs = mService.mPendingJobs;
        final int numPending = pendingJobs.size();
        if (numPending == 0) {
            // All quiet. We can let this job run to completion.
            return null;
        }

        // Only expedited jobs can replace expedited jobs.
        if (js.shouldTreatAsExpeditedJob()) {
            // Keep fg/bg user distinction.
            if (workType == WORK_TYPE_BGUSER) {
                // For now, let any bg user job replace a bg user expedited job.
                // TODO: limit to ej once we have dedicated bg user ej slots.
                if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_BGUSER) > 0) {
                    return "blocking " + workTypeToString(workType) + " queue";
                }
            } else {
                if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_EJ) > 0) {
                    return "blocking " + workTypeToString(workType) + " queue";
                }
            }
        }

        // Easy check. If there are pending jobs of the same work type, then we know that
        // something will replace this.
        if (mWorkCountTracker.getPendingJobCount(workType) > 0) {
            return "blocking " + workTypeToString(workType) + " queue";
        }

        // Harder check. We need to see if a different work type can replace this job.
        int remainingWorkTypes = ALL_WORK_TYPES;
        for (int i = 0; i < numPending; ++i) {
            final JobStatus pending = pendingJobs.get(i);
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
    private String printPendingQueueLocked() {
        StringBuilder s = new StringBuilder("Pending queue: ");
        Iterator<JobStatus> it = mService.mPendingJobs.iterator();
        while (it.hasNext()) {
            JobStatus js = it.next();
            s.append("(")
                    .append(js.getJob().getId())
                    .append(", ")
                    .append(js.getUid())
                    .append(") ");
        }
        return s.toString();
    }

    private static String printContextIdToJobMap(JobStatus[] map, String initial) {
        StringBuilder s = new StringBuilder(initial + ": ");
        for (int i=0; i<map.length; i++) {
            s.append("(")
                    .append(map[i] == null? -1: map[i].getJobId())
                    .append(map[i] == null? -1: map[i].getUid())
                    .append(")" );
        }
        return s.toString();
    }

    @GuardedBy("mLock")
    void updateConfigLocked() {
        DeviceConfig.Properties properties =
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_JOB_SCHEDULER);

        mScreenOffAdjustmentDelayMs = properties.getLong(
                KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS, DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS);

        CONFIG_LIMITS_SCREEN_ON.normal.update(properties);
        CONFIG_LIMITS_SCREEN_ON.moderate.update(properties);
        CONFIG_LIMITS_SCREEN_ON.low.update(properties);
        CONFIG_LIMITS_SCREEN_ON.critical.update(properties);

        CONFIG_LIMITS_SCREEN_OFF.normal.update(properties);
        CONFIG_LIMITS_SCREEN_OFF.moderate.update(properties);
        CONFIG_LIMITS_SCREEN_OFF.low.update(properties);
        CONFIG_LIMITS_SCREEN_OFF.critical.update(properties);
    }

    public void dumpLocked(IndentingPrintWriter pw, long now, long nowRealtime) {
        pw.println("Concurrency:");

        pw.increaseIndent();
        try {
            pw.println("Configuration:");
            pw.increaseIndent();
            pw.print(KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS, mScreenOffAdjustmentDelayMs).println();
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

            pw.print("User Grace Period: ");
            pw.println(mGracePeriodObserver.mGracePeriodExpiration);
            pw.println();

            mStatLogger.dump(pw);
        } finally {
            pw.decreaseIndent();
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
            if (js.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
                classification |= WORK_TYPE_TOP;
            } else if (js.lastEvaluatedPriority >= JobInfo.PRIORITY_FOREGROUND_SERVICE) {
                classification |= WORK_TYPE_FGS;
            } else {
                classification |= WORK_TYPE_BG;
            }

            if (js.shouldTreatAsExpeditedJob()) {
                classification |= WORK_TYPE_EJ;
            }
        } else {
            // TODO(171305774): create dedicated slots for EJs of bg user
            classification |= WORK_TYPE_BGUSER;
        }

        return classification;
    }

    @VisibleForTesting
    static class WorkTypeConfig {
        private static final String KEY_PREFIX_MAX_TOTAL =
                CONFIG_KEY_PREFIX_CONCURRENCY + "max_total_";
        private static final String KEY_PREFIX_MAX_TOP = CONFIG_KEY_PREFIX_CONCURRENCY + "max_top_";
        private static final String KEY_PREFIX_MAX_FGS = CONFIG_KEY_PREFIX_CONCURRENCY + "max_fgs_";
        private static final String KEY_PREFIX_MAX_EJ = CONFIG_KEY_PREFIX_CONCURRENCY + "max_ej_";
        private static final String KEY_PREFIX_MAX_BG = CONFIG_KEY_PREFIX_CONCURRENCY + "max_bg_";
        private static final String KEY_PREFIX_MAX_BGUSER =
                CONFIG_KEY_PREFIX_CONCURRENCY + "max_bguser_";
        private static final String KEY_PREFIX_MIN_TOP = CONFIG_KEY_PREFIX_CONCURRENCY + "min_top_";
        private static final String KEY_PREFIX_MIN_FGS = CONFIG_KEY_PREFIX_CONCURRENCY + "min_fgs_";
        private static final String KEY_PREFIX_MIN_EJ = CONFIG_KEY_PREFIX_CONCURRENCY + "min_ej_";
        private static final String KEY_PREFIX_MIN_BG = CONFIG_KEY_PREFIX_CONCURRENCY + "min_bg_";
        private static final String KEY_PREFIX_MIN_BGUSER =
                CONFIG_KEY_PREFIX_CONCURRENCY + "min_bguser_";
        private final String mConfigIdentifier;

        private int mMaxTotal;
        private final SparseIntArray mMinReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mMaxAllowedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final int mDefaultMaxTotal;
        private final SparseIntArray mDefaultMinReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mDefaultMaxAllowedSlots = new SparseIntArray(NUM_WORK_TYPES);

        WorkTypeConfig(@NonNull String configIdentifier, int defaultMaxTotal,
                List<Pair<Integer, Integer>> defaultMin, List<Pair<Integer, Integer>> defaultMax) {
            mConfigIdentifier = configIdentifier;
            mDefaultMaxTotal = mMaxTotal = Math.min(defaultMaxTotal, MAX_JOB_CONTEXTS_COUNT);
            int numReserved = 0;
            for (int i = defaultMin.size() - 1; i >= 0; --i) {
                mDefaultMinReservedSlots.put(defaultMin.get(i).first, defaultMin.get(i).second);
                numReserved += defaultMin.get(i).second;
            }
            if (mDefaultMaxTotal < 0 || numReserved > mDefaultMaxTotal) {
                // We only create new configs on boot, so this should trigger during development
                // (before the code gets checked in), so this makes sure the hard-coded defaults
                // make sense. DeviceConfig values will be handled gracefully in update().
                throw new IllegalArgumentException("Invalid default config: t=" + defaultMaxTotal
                        + " min=" + defaultMin + " max=" + defaultMax);
            }
            for (int i = defaultMax.size() - 1; i >= 0; --i) {
                mDefaultMaxAllowedSlots.put(defaultMax.get(i).first, defaultMax.get(i).second);
            }
            update(new DeviceConfig.Properties.Builder(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER).build());
        }

        void update(@NonNull DeviceConfig.Properties properties) {
            // Ensure total in the range [1, MAX_JOB_CONTEXTS_COUNT].
            mMaxTotal = Math.max(1, Math.min(MAX_JOB_CONTEXTS_COUNT,
                    properties.getInt(KEY_PREFIX_MAX_TOTAL + mConfigIdentifier, mDefaultMaxTotal)));

            mMaxAllowedSlots.clear();
            // Ensure they're in the range [1, total].
            final int maxTop = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_TOP + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_TOP, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_TOP, maxTop);
            final int maxFgs = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_FGS + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_FGS, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_FGS, maxFgs);
            final int maxEj = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_EJ + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_EJ, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_EJ, maxEj);
            final int maxBg = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_BG + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_BG, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_BG, maxBg);
            final int maxBgUser = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_BGUSER + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_BGUSER, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_BGUSER, maxBgUser);

            int remaining = mMaxTotal;
            mMinReservedSlots.clear();
            // Ensure top is in the range [1, min(maxTop, total)]
            final int minTop = Math.max(1, Math.min(Math.min(maxTop, mMaxTotal),
                    properties.getInt(KEY_PREFIX_MIN_TOP + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_TOP))));
            mMinReservedSlots.put(WORK_TYPE_TOP, minTop);
            remaining -= minTop;
            // Ensure fgs is in the range [0, min(maxFgs, remaining)]
            final int minFgs = Math.max(0, Math.min(Math.min(maxFgs, remaining),
                    properties.getInt(KEY_PREFIX_MIN_FGS + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_FGS))));
            mMinReservedSlots.put(WORK_TYPE_FGS, minFgs);
            remaining -= minFgs;
            // Ensure ej is in the range [0, min(maxEj, remaining)]
            final int minEj = Math.max(0, Math.min(Math.min(maxEj, remaining),
                    properties.getInt(KEY_PREFIX_MIN_EJ + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_EJ))));
            mMinReservedSlots.put(WORK_TYPE_EJ, minEj);
            remaining -= minEj;
            // Ensure bg is in the range [0, min(maxBg, remaining)]
            final int minBg = Math.max(0, Math.min(Math.min(maxBg, remaining),
                    properties.getInt(KEY_PREFIX_MIN_BG + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_BG))));
            mMinReservedSlots.put(WORK_TYPE_BG, minBg);
            remaining -= minBg;
            // Ensure bg user is in the range [0, min(maxBgUser, remaining)]
            final int minBgUser = Math.max(0, Math.min(Math.min(maxBgUser, remaining),
                    properties.getInt(KEY_PREFIX_MIN_BGUSER + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_BGUSER, 0))));
            mMinReservedSlots.put(WORK_TYPE_BGUSER, minBgUser);
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
            pw.print(KEY_PREFIX_MIN_TOP + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_TOP))
                    .println();
            pw.print(KEY_PREFIX_MAX_TOP + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_TOP))
                    .println();
            pw.print(KEY_PREFIX_MIN_FGS + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_FGS))
                    .println();
            pw.print(KEY_PREFIX_MAX_FGS + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_FGS))
                    .println();
            pw.print(KEY_PREFIX_MIN_EJ + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_EJ))
                    .println();
            pw.print(KEY_PREFIX_MAX_EJ + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_EJ))
                    .println();
            pw.print(KEY_PREFIX_MIN_BG + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_BG))
                    .println();
            pw.print(KEY_PREFIX_MAX_BG + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_BG))
                    .println();
            pw.print(KEY_PREFIX_MIN_BGUSER + mConfigIdentifier,
                    mMinReservedSlots.get(WORK_TYPE_BGUSER)).println();
            pw.print(KEY_PREFIX_MAX_BGUSER + mConfigIdentifier,
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
            mConfigNumReservedSlots.put(WORK_TYPE_TOP,
                    workTypeConfig.getMinReserved(WORK_TYPE_TOP));
            mConfigNumReservedSlots.put(WORK_TYPE_FGS,
                    workTypeConfig.getMinReserved(WORK_TYPE_FGS));
            mConfigNumReservedSlots.put(WORK_TYPE_EJ, workTypeConfig.getMinReserved(WORK_TYPE_EJ));
            mConfigNumReservedSlots.put(WORK_TYPE_BG, workTypeConfig.getMinReserved(WORK_TYPE_BG));
            mConfigNumReservedSlots.put(WORK_TYPE_BGUSER,
                    workTypeConfig.getMinReserved(WORK_TYPE_BGUSER));
            mConfigAbsoluteMaxSlots.put(WORK_TYPE_TOP, workTypeConfig.getMax(WORK_TYPE_TOP));
            mConfigAbsoluteMaxSlots.put(WORK_TYPE_FGS, workTypeConfig.getMax(WORK_TYPE_FGS));
            mConfigAbsoluteMaxSlots.put(WORK_TYPE_EJ, workTypeConfig.getMax(WORK_TYPE_EJ));
            mConfigAbsoluteMaxSlots.put(WORK_TYPE_BG, workTypeConfig.getMax(WORK_TYPE_BG));
            mConfigAbsoluteMaxSlots.put(WORK_TYPE_BGUSER, workTypeConfig.getMax(WORK_TYPE_BGUSER));

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
                        // Try to give this slot to the highest priority one within its limits.
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
}
