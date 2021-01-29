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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.util.StatLogger;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Iterator;
import java.util.List;

/**
 * This class decides, given the various configuration and the system status, which jobs can start
 * and which {@link JobServiceContext} to run each job on.
 */
class JobConcurrencyManager {
    private static final String TAG = JobSchedulerService.TAG;
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    static final String CONFIG_KEY_PREFIX_CONCURRENCY = "concurrency_";
    private static final String KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS =
            CONFIG_KEY_PREFIX_CONCURRENCY + "screen_off_adjustment_delay_ms";
    private static final long DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS = 30_000;

    static final int WORK_TYPE_NONE = 0;
    static final int WORK_TYPE_TOP = 1 << 0;
    static final int WORK_TYPE_BG = 1 << 1;
    private static final int NUM_WORK_TYPES = 2;

    @IntDef(prefix = {"WORK_TYPE_"}, flag = true, value = {
            WORK_TYPE_NONE,
            WORK_TYPE_TOP,
            WORK_TYPE_BG
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WorkType {
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
                    new WorkTypeConfig("screen_on_normal", 8,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 6))),
                    new WorkTypeConfig("screen_on_moderate", 8,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 4))),
                    new WorkTypeConfig("screen_on_low", 5,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1))),
                    new WorkTypeConfig("screen_on_critical", 5,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1)))
            );
    private static final WorkConfigLimitsPerMemoryTrimLevel CONFIG_LIMITS_SCREEN_OFF =
            new WorkConfigLimitsPerMemoryTrimLevel(
                    new WorkTypeConfig("screen_off_normal", 10,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 6))),
                    new WorkTypeConfig("screen_off_moderate", 10,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 2)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 4))),
                    new WorkTypeConfig("screen_off_low", 5,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1))),
                    new WorkTypeConfig("screen_off_critical", 5,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1)))
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

    private final ArraySet<JobStatus> mRunningJobs = new ArraySet<>();

    private final WorkCountTracker mWorkCountTracker = new WorkCountTracker();

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

    interface Stats {
        int ASSIGN_JOBS_TO_CONTEXTS = 0;
        int REFRESH_SYSTEM_STATE = 1;

        int COUNT = REFRESH_SYSTEM_STATE + 1;
    }

    JobConcurrencyManager(JobSchedulerService service) {
        mService = service;
        mLock = mService.mLock;
        mContext = service.getContext();

        mHandler = JobSchedulerBackgroundThread.getHandler();
    }

    public void onSystemReady() {
        mPowerManager = mContext.getSystemService(PowerManager.class);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mReceiver, filter);

        onInteractiveStateChanged(mPowerManager.isInteractive());
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

            final long nowRealtime = JobSchedulerService.sElapsedRealtimeClock.millis();
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
            final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
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
    private void refreshSystemStateLocked() {
        final long nowUptime = JobSchedulerService.sUptimeMillisClock.millis();

        // Only refresh the information every so often.
        if (nowUptime < mNextSystemStateRefreshTime) {
            return;
        }

        final long start = mStatLogger.getTime();
        mNextSystemStateRefreshTime = nowUptime + SYSTEM_STATE_REFRESH_MIN_INTERVAL;

        mLastMemoryTrimLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        try {
            mLastMemoryTrimLevel = ActivityManager.getService().getMemoryTrimLevel();
        } catch (RemoteException e) {
        }

        mStatLogger.logDurationStat(Stats.REFRESH_SYSTEM_STATE, start);
    }

    @GuardedBy("mLock")
    private void updateCounterConfigLocked() {
        refreshSystemStateLocked();

        final WorkConfigLimitsPerMemoryTrimLevel workConfigs = mEffectiveInteractiveState
                ? CONFIG_LIMITS_SCREEN_ON : CONFIG_LIMITS_SCREEN_OFF;

        WorkTypeConfig workTypeConfig;
        switch (mLastMemoryTrimLevel) {
            case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                workTypeConfig = workConfigs.moderate;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_LOW:
                workTypeConfig = workConfigs.low;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                workTypeConfig = workConfigs.critical;
                break;
            default:
                workTypeConfig = workConfigs.normal;
                break;
        }

        mWorkCountTracker.setConfig(workTypeConfig);
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

        updateCounterConfigLocked();
        // Reset everything since we'll re-evaluate the current state.
        mWorkCountTracker.resetCounts();

        for (int i = 0; i < MAX_JOB_CONTEXTS_COUNT; i++) {
            final JobServiceContext js = mService.mActiveServices.get(i);
            final JobStatus status = js.getRunningJobLocked();

            if ((contextIdToJobMap[i] = status) != null) {
                mWorkCountTracker.incrementRunningJobCount(js.getRunningJobWorkType());
                workTypeForContext[i] = js.getRunningJobWorkType();
            }

            slotChanged[i] = false;
            preferredUidForContext[i] = js.getPreferredUid();
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs initial"));
        }

        // Next, update the job priorities, and also count the pending FG / BG jobs.
        updateNonRunningPriorities(pendingJobs, true);

        mWorkCountTracker.onCountDone();

        for (int i = 0; i < pendingJobs.size(); i++) {
            final JobStatus nextPending = pendingJobs.get(i);

            if (mRunningJobs.contains(nextPending)) {
                continue;
            }

            // TODO(171305774): make sure HPJs aren't pre-empted and add dedicated contexts for them

            // Find an available slot for nextPending. The context should be available OR
            // it should have lowest priority among all running jobs
            // (sharing the same Uid as nextPending)
            int minPriorityForPreemption = Integer.MAX_VALUE;
            int selectedContextId = -1;
            int workType = mWorkCountTracker.canJobStart(getJobWorkTypes(nextPending));
            boolean startingJob = false;
            for (int j=0; j<MAX_JOB_CONTEXTS_COUNT; j++) {
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
                    // In this case, we're just going to preempt a low priority job, we're not
                    // actually starting a job, so don't set startingJob.
                }
            }
            if (selectedContextId != -1) {
                contextIdToJobMap[selectedContextId] = nextPending;
                slotChanged[selectedContextId] = true;
            }
            if (startingJob) {
                // Increase the counters when we're going to start a job.
                workTypeForContext[selectedContextId] = workType;
                mWorkCountTracker.stageJob(workType);
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
                    activeServices.get(i).preemptExecutingJobLocked();
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

    private void updateNonRunningPriorities(@NonNull final List<JobStatus> pendingJobs,
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
            pw.print("Configuration:");
            pw.increaseIndent();
            pw.print(KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS, mScreenOffAdjustmentDelayMs).println();
            CONFIG_LIMITS_SCREEN_ON.normal.dump(pw);
            CONFIG_LIMITS_SCREEN_ON.moderate.dump(pw);
            CONFIG_LIMITS_SCREEN_ON.low.dump(pw);
            CONFIG_LIMITS_SCREEN_ON.critical.dump(pw);
            CONFIG_LIMITS_SCREEN_OFF.normal.dump(pw);
            CONFIG_LIMITS_SCREEN_OFF.moderate.dump(pw);
            CONFIG_LIMITS_SCREEN_OFF.low.dump(pw);
            CONFIG_LIMITS_SCREEN_OFF.critical.dump(pw);
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

            pw.println("Current max jobs:");
            pw.println("  ");
            pw.println(mWorkCountTracker);

            pw.println();

            pw.print("mLastMemoryTrimLevel: ");
            pw.print(mLastMemoryTrimLevel);
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

    int getJobWorkTypes(@NonNull JobStatus js) {
        int classification = 0;
        // TODO(171305774): create dedicated work type for EJ and FGS
        if (js.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP
                || js.shouldTreatAsExpeditedJob()) {
            classification |= WORK_TYPE_TOP;
        } else {
            classification |= WORK_TYPE_BG;
        }
        return classification;
    }

    @VisibleForTesting
    static class WorkTypeConfig {
        private static final String KEY_PREFIX_MAX_TOTAL =
                CONFIG_KEY_PREFIX_CONCURRENCY + "max_total_";
        private static final String KEY_PREFIX_MAX_TOP = CONFIG_KEY_PREFIX_CONCURRENCY + "max_top_";
        private static final String KEY_PREFIX_MAX_BG = CONFIG_KEY_PREFIX_CONCURRENCY + "max_bg_";
        private static final String KEY_PREFIX_MIN_TOP = CONFIG_KEY_PREFIX_CONCURRENCY + "min_top_";
        private static final String KEY_PREFIX_MIN_BG = CONFIG_KEY_PREFIX_CONCURRENCY + "min_bg_";
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
            mDefaultMaxTotal = mMaxTotal = defaultMaxTotal;
            for (int i = defaultMin.size() - 1; i >= 0; --i) {
                mDefaultMinReservedSlots.put(defaultMin.get(i).first, defaultMin.get(i).second);
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
            final int maxBg = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_BG + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_BG, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_BG, maxBg);

            int remaining = mMaxTotal;
            mMinReservedSlots.clear();
            // Ensure top is in the range [1, min(maxTop, total)]
            final int minTop = Math.max(1, Math.min(Math.min(maxTop, mMaxTotal),
                    properties.getInt(KEY_PREFIX_MIN_TOP + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_TOP))));
            mMinReservedSlots.put(WORK_TYPE_TOP, minTop);
            remaining -= minTop;
            // Ensure bg is in the range [0, min(maxBg, remaining)]
            final int minBg = Math.max(0, Math.min(Math.min(maxBg, remaining),
                    properties.getInt(KEY_PREFIX_MIN_BG + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_BG))));
            mMinReservedSlots.put(WORK_TYPE_BG, minBg);
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
            pw.print(KEY_PREFIX_MIN_BG + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_BG))
                    .println();
            pw.print(KEY_PREFIX_MAX_BG + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_BG))
                    .println();
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

        /**
         * Numbers may be lower in this than in {@link #mConfigNumReservedSlots} if there aren't
         * enough ready jobs of a type to take up all of the desired reserved slots.
         */
        private final SparseIntArray mNumActuallyReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumPendingJobs = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumRunningJobs = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumStartingJobs = new SparseIntArray(NUM_WORK_TYPES);
        private int mNumUnspecialized = 0;
        private int mNumUnspecializedRemaining = 0;

        void setConfig(@NonNull WorkTypeConfig workTypeConfig) {
            mConfigMaxTotal = workTypeConfig.getMaxTotal();
            mConfigNumReservedSlots.put(WORK_TYPE_TOP,
                    workTypeConfig.getMinReserved(WORK_TYPE_TOP));
            mConfigNumReservedSlots.put(WORK_TYPE_BG, workTypeConfig.getMinReserved(WORK_TYPE_BG));
            mConfigAbsoluteMaxSlots.put(WORK_TYPE_TOP, workTypeConfig.getMax(WORK_TYPE_TOP));
            mConfigAbsoluteMaxSlots.put(WORK_TYPE_BG, workTypeConfig.getMax(WORK_TYPE_BG));

            mNumUnspecialized = mConfigMaxTotal;
            mNumUnspecialized -= mConfigNumReservedSlots.get(WORK_TYPE_TOP);
            mNumUnspecialized -= mConfigNumReservedSlots.get(WORK_TYPE_BG);
            mNumUnspecialized -= mConfigAbsoluteMaxSlots.get(WORK_TYPE_TOP);
            mNumUnspecialized -= mConfigAbsoluteMaxSlots.get(WORK_TYPE_BG);
            calculateUnspecializedRemaining();
        }

        private void calculateUnspecializedRemaining() {
            mNumUnspecializedRemaining = mNumUnspecialized;
            for (int i = mNumRunningJobs.size() - 1; i >= 0; --i) {
                mNumUnspecializedRemaining -= mNumRunningJobs.valueAt(i);
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
            // We don't know which type we'll classify the job as when we run it yet, so make sure
            // we have space in all applicable slots.
            if ((workTypes & WORK_TYPE_TOP) == WORK_TYPE_TOP) {
                mNumPendingJobs.put(WORK_TYPE_TOP, mNumPendingJobs.get(WORK_TYPE_TOP) + 1);
            }
            if ((workTypes & WORK_TYPE_BG) == WORK_TYPE_BG) {
                mNumPendingJobs.put(WORK_TYPE_BG, mNumPendingJobs.get(WORK_TYPE_BG) + 1);
            }
        }

        void stageJob(@WorkType int workType) {
            final int newNumStartingJobs = mNumStartingJobs.get(workType) + 1;
            mNumStartingJobs.put(workType, newNumStartingJobs);
            mNumPendingJobs.put(workType, Math.max(0, mNumPendingJobs.get(workType) - 1));
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
                mNumUnspecializedRemaining++;
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

        void onCountDone() {
            // Calculate how many slots to reserve for each work type. "Unspecialized" slots will
            // be reserved for higher importance types first (ie. top before bg).
            mNumUnspecialized = mConfigMaxTotal;
            final int numTop = mNumRunningJobs.get(WORK_TYPE_TOP)
                    + mNumPendingJobs.get(WORK_TYPE_TOP);
            final int resTop = Math.min(mConfigNumReservedSlots.get(WORK_TYPE_TOP), numTop);
            mNumActuallyReservedSlots.put(WORK_TYPE_TOP, resTop);
            mNumUnspecialized -= resTop;
            final int numBg = mNumRunningJobs.get(WORK_TYPE_BG) + mNumPendingJobs.get(WORK_TYPE_BG);
            final int resBg = Math.min(mConfigNumReservedSlots.get(WORK_TYPE_BG), numBg);
            mNumActuallyReservedSlots.put(WORK_TYPE_BG, resBg);
            mNumUnspecialized -= resBg;
            calculateUnspecializedRemaining();

            // Assign remaining unspecialized based on ranking.
            int unspecializedAssigned = Math.max(0,
                    Math.min(mConfigAbsoluteMaxSlots.get(WORK_TYPE_TOP),
                            Math.min(mNumUnspecializedRemaining, numTop - resTop)));
            mNumActuallyReservedSlots.put(WORK_TYPE_TOP, resTop + unspecializedAssigned);
            mNumUnspecializedRemaining -= unspecializedAssigned;
            unspecializedAssigned = Math.max(0,
                    Math.min(mConfigAbsoluteMaxSlots.get(WORK_TYPE_BG),
                            Math.min(mNumUnspecializedRemaining, numBg - resBg)));
            mNumActuallyReservedSlots.put(WORK_TYPE_BG, resBg + unspecializedAssigned);
            mNumUnspecializedRemaining -= unspecializedAssigned;
        }

        int canJobStart(int workTypes) {
            if ((workTypes & WORK_TYPE_TOP) == WORK_TYPE_TOP) {
                final int maxAllowed = Math.min(
                        mConfigAbsoluteMaxSlots.get(WORK_TYPE_TOP),
                        mNumActuallyReservedSlots.get(WORK_TYPE_TOP) + mNumUnspecializedRemaining);
                if (mNumRunningJobs.get(WORK_TYPE_TOP) + mNumStartingJobs.get(WORK_TYPE_TOP)
                        < maxAllowed) {
                    return WORK_TYPE_TOP;
                }
            }
            if ((workTypes & WORK_TYPE_BG) == WORK_TYPE_BG) {
                final int maxAllowed = Math.min(
                        mConfigAbsoluteMaxSlots.get(WORK_TYPE_BG),
                        mNumActuallyReservedSlots.get(WORK_TYPE_BG) + mNumUnspecializedRemaining);
                if (mNumRunningJobs.get(WORK_TYPE_BG) + mNumStartingJobs.get(WORK_TYPE_BG)
                        < maxAllowed) {
                    return WORK_TYPE_BG;
                }
            }
            return WORK_TYPE_NONE;
        }

        int getRunningJobCount(@WorkType final int workType) {
            return mNumRunningJobs.get(workType, 0);
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
