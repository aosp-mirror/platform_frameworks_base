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

import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.StatLogger;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.JobSchedulerService.MaxJobCountsPerMemoryTrimLevel;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;

import java.util.Iterator;
import java.util.List;

/**
 * This class decides, given the various configuration and the system status, how many more jobs
 * can start.
 */
class JobConcurrencyManager {
    private static final String TAG = JobSchedulerService.TAG;
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    private final Object mLock;
    private final JobSchedulerService mService;
    private final JobSchedulerService.Constants mConstants;
    private final Context mContext;
    private final Handler mHandler;

    private PowerManager mPowerManager;

    private boolean mCurrentInteractiveState;
    private boolean mEffectiveInteractiveState;

    private long mLastScreenOnRealtime;
    private long mLastScreenOffRealtime;

    private static final int MAX_JOB_CONTEXTS_COUNT = JobSchedulerService.MAX_JOB_CONTEXTS_COUNT;

    /**
     * This array essentially stores the state of mActiveServices array.
     * The ith index stores the job present on the ith JobServiceContext.
     * We manipulate this array until we arrive at what jobs should be running on
     * what JobServiceContext.
     */
    JobStatus[] mRecycledAssignContextIdToJobMap = new JobStatus[MAX_JOB_CONTEXTS_COUNT];

    boolean[] mRecycledSlotChanged = new boolean[MAX_JOB_CONTEXTS_COUNT];

    int[] mRecycledPreferredUidForContext = new int[MAX_JOB_CONTEXTS_COUNT];

    /** Max job counts according to the current system state. */
    private JobSchedulerService.MaxJobCounts mMaxJobCounts;

    private final JobCountTracker mJobCountTracker = new JobCountTracker();

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
        mConstants = service.mConstants;
        mContext = service.getContext();

        mHandler = BackgroundThread.getHandler();
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
                mHandler.postDelayed(mRampUpForScreenOff,
                        mConstants.SCREEN_OFF_JOB_CONCURRENCY_INCREASE_DELAY_MS.getValue());
            }
        }
    }

    private final Runnable mRampUpForScreenOff = this::rampUpForScreenOff;

    /**
     * Called in {@link Constants#SCREEN_OFF_JOB_CONCURRENCY_INCREASE_DELAY_MS} after
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
            if ((mLastScreenOffRealtime
                    + mConstants.SCREEN_OFF_JOB_CONCURRENCY_INCREASE_DELAY_MS.getValue())
                    > now) {
                return;
            }

            mEffectiveInteractiveState = false;

            if (DEBUG) {
                Slog.d(TAG, "Ramping up concurrency");
            }

            mService.maybeRunPendingJobsLocked();
        }
    }

    private boolean isFgJob(JobStatus job) {
        return job.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP;
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
    private void updateMaxCountsLocked() {
        refreshSystemStateLocked();

        final MaxJobCountsPerMemoryTrimLevel jobCounts = mEffectiveInteractiveState
                ? mConstants.MAX_JOB_COUNTS_SCREEN_ON
                : mConstants.MAX_JOB_COUNTS_SCREEN_OFF;


        switch (mLastMemoryTrimLevel) {
            case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                mMaxJobCounts = jobCounts.moderate;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_LOW:
                mMaxJobCounts = jobCounts.low;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                mMaxJobCounts = jobCounts.critical;
                break;
            default:
                mMaxJobCounts = jobCounts.normal;
                break;
        }
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

        final JobPackageTracker tracker = mService.mJobPackageTracker;
        final List<JobStatus> pendingJobs = mService.mPendingJobs;
        final List<JobServiceContext> activeServices = mService.mActiveServices;
        final List<StateController> controllers = mService.mControllers;

        updateMaxCountsLocked();

        // To avoid GC churn, we recycle the arrays.
        JobStatus[] contextIdToJobMap = mRecycledAssignContextIdToJobMap;
        boolean[] slotChanged = mRecycledSlotChanged;
        int[] preferredUidForContext = mRecycledPreferredUidForContext;


        // Initialize the work variables and also count running jobs.
        mJobCountTracker.reset(
                mMaxJobCounts.getMaxTotal(),
                mMaxJobCounts.getMaxBg(),
                mMaxJobCounts.getMinBg());

        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
            final JobServiceContext js = mService.mActiveServices.get(i);
            final JobStatus status = js.getRunningJobLocked();

            if ((contextIdToJobMap[i] = status) != null) {
                mJobCountTracker.incrementRunningJobCount(isFgJob(status));
            }

            slotChanged[i] = false;
            preferredUidForContext[i] = js.getPreferredUid();
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs initial"));
        }

        // Next, update the job priorities, and also count the pending FG / BG jobs.
        for (int i = 0; i < pendingJobs.size(); i++) {
            final JobStatus pending = pendingJobs.get(i);

            // If job is already running, go to next job.
            int jobRunningContext = findJobContextIdFromMap(pending, contextIdToJobMap);
            if (jobRunningContext != -1) {
                continue;
            }

            final int priority = mService.evaluateJobPriorityLocked(pending);
            pending.lastEvaluatedPriority = priority;

            mJobCountTracker.incrementPendingJobCount(isFgJob(pending));
        }

        mJobCountTracker.onCountDone();

        for (int i = 0; i < pendingJobs.size(); i++) {
            final JobStatus nextPending = pendingJobs.get(i);

            // Unfortunately we need to repeat this relatively expensive check.
            int jobRunningContext = findJobContextIdFromMap(nextPending, contextIdToJobMap);
            if (jobRunningContext != -1) {
                continue;
            }

            final boolean isPendingFg = isFgJob(nextPending);

            // Find an available slot for nextPending. The context should be available OR
            // it should have lowest priority among all running jobs
            // (sharing the same Uid as nextPending)
            int minPriorityForPreemption = Integer.MAX_VALUE;
            int selectedContextId = -1;
            boolean startingJob = false;
            for (int j=0; j<MAX_JOB_CONTEXTS_COUNT; j++) {
                JobStatus job = contextIdToJobMap[j];
                int preferredUid = preferredUidForContext[j];
                if (job == null) {
                    final boolean preferredUidOkay = (preferredUid == nextPending.getUid())
                            || (preferredUid == JobServiceContext.NO_PREFERRED_UID);

                    if (preferredUidOkay && mJobCountTracker.canJobStart(isPendingFg)) {
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
                mJobCountTracker.onStartingNewJob(isPendingFg);
            }
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs final"));
        }

        mJobCountTracker.logStatus();

        tracker.noteConcurrency(mJobCountTracker.getTotalRunningJobCountToNote(),
                mJobCountTracker.getFgRunningJobCountToNote());

        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
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
                    for (int ic=0; ic<controllers.size(); ic++) {
                        controllers.get(ic).prepareForExecutionLocked(pendingJob);
                    }
                    if (!activeServices.get(i).executeRunnableJob(pendingJob)) {
                        Slog.d(TAG, "Error executing " + pendingJob);
                    }
                    if (pendingJobs.remove(pendingJob)) {
                        tracker.noteNonpending(pendingJob);
                    }
                }
            }
            if (!preservePreferredUid) {
                activeServices.get(i).clearPreferredUid();
            }
        }
    }

    private static int findJobContextIdFromMap(JobStatus jobStatus, JobStatus[] map) {
        for (int i=0; i<map.length; i++) {
            if (map[i] != null && map[i].matches(jobStatus.getUid(), jobStatus.getJobId())) {
                return i;
            }
        }
        return -1;
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


    public void dumpLocked(IndentingPrintWriter pw, long now, long nowRealtime) {
        pw.println("Concurrency:");

        pw.increaseIndent();
        try {
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
            pw.println(mJobCountTracker);

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

        mJobCountTracker.dumpProto(proto, JobConcurrencyManagerProto.JOB_COUNT_TRACKER);

        proto.write(JobConcurrencyManagerProto.MEMORY_TRIM_LEVEL, mLastMemoryTrimLevel);

        mStatLogger.dumpProto(proto, JobConcurrencyManagerProto.STATS);

        proto.end(token);
    }

    /**
     * This class decides, taking into account {@link #mMaxJobCounts} and how mny jos are running /
     * pending, how many more job can start.
     *
     * Extracted for testing and logging.
     */
    @VisibleForTesting
    static class JobCountTracker {
        private int mConfigNumMaxTotalJobs;
        private int mConfigNumMaxBgJobs;
        private int mConfigNumMinBgJobs;

        private int mNumRunningFgJobs;
        private int mNumRunningBgJobs;

        private int mNumPendingFgJobs;
        private int mNumPendingBgJobs;

        private int mNumStartingFgJobs;
        private int mNumStartingBgJobs;

        private int mNumReservedForBg;
        private int mNumActualMaxFgJobs;
        private int mNumActualMaxBgJobs;

        void reset(int numTotalMaxJobs, int numMaxBgJobs, int numMinBgJobs) {
            mConfigNumMaxTotalJobs = numTotalMaxJobs;
            mConfigNumMaxBgJobs = numMaxBgJobs;
            mConfigNumMinBgJobs = numMinBgJobs;

            mNumRunningFgJobs = 0;
            mNumRunningBgJobs = 0;

            mNumPendingFgJobs = 0;
            mNumPendingBgJobs = 0;

            mNumStartingFgJobs = 0;
            mNumStartingBgJobs = 0;

            mNumReservedForBg = 0;
            mNumActualMaxFgJobs = 0;
            mNumActualMaxBgJobs = 0;
        }

        void incrementRunningJobCount(boolean isFg) {
            if (isFg) {
                mNumRunningFgJobs++;
            } else {
                mNumRunningBgJobs++;
            }
        }

        void incrementPendingJobCount(boolean isFg) {
            if (isFg) {
                mNumPendingFgJobs++;
            } else {
                mNumPendingBgJobs++;
            }
        }

        void onStartingNewJob(boolean isFg) {
            if (isFg) {
                mNumStartingFgJobs++;
            } else {
                mNumStartingBgJobs++;
            }
        }

        void onCountDone() {
            // Note some variables are used only here but are made class members in order to have
            // them on logcat / dumpsys.

            // How many slots should we allocate to BG jobs at least?
            // That's basically "getMinBg()", but if there are less jobs, decrease it.
            // (e.g. even if min-bg is 2, if there's only 1 running+pending job, this has to be 1.)
            final int reservedForBg = Math.min(
                    mConfigNumMinBgJobs,
                    mNumRunningBgJobs + mNumPendingBgJobs);

            // However, if there are FG jobs already running, we have to adjust it.
            mNumReservedForBg = Math.min(reservedForBg,
                    mConfigNumMaxTotalJobs - mNumRunningFgJobs);

            // Max FG is [total - [number needed for BG jobs]]
            // [number needed for BG jobs] is the bigger one of [running BG] or [reserved BG]
            final int maxFg =
                    mConfigNumMaxTotalJobs - Math.max(mNumRunningBgJobs, mNumReservedForBg);

            // The above maxFg is the theoretical max. If there are less FG jobs, the actual
            // max FG will be lower accordingly.
            mNumActualMaxFgJobs = Math.min(
                    maxFg,
                    mNumRunningFgJobs + mNumPendingFgJobs);

            // Max BG is [total - actual max FG], but cap at [config max BG].
            final int maxBg = Math.min(
                    mConfigNumMaxBgJobs,
                    mConfigNumMaxTotalJobs - mNumActualMaxFgJobs);

            // If there are less BG jobs than maxBg, then reduce the actual max BG accordingly.
            // This isn't needed for the logic to work, but this will give consistent output
            // on logcat and dumpsys.
            mNumActualMaxBgJobs = Math.min(
                    maxBg,
                    mNumRunningBgJobs + mNumPendingBgJobs);
        }

        boolean canJobStart(boolean isFg) {
            if (isFg) {
                return mNumRunningFgJobs + mNumStartingFgJobs < mNumActualMaxFgJobs;
            } else {
                return mNumRunningBgJobs + mNumStartingBgJobs < mNumActualMaxBgJobs;
            }
        }

        public int getNumStartingFgJobs() {
            return mNumStartingFgJobs;
        }

        public int getNumStartingBgJobs() {
            return mNumStartingBgJobs;
        }

        int getTotalRunningJobCountToNote() {
            return mNumRunningFgJobs + mNumRunningBgJobs
                    + mNumStartingFgJobs + mNumStartingBgJobs;
        }

        int getFgRunningJobCountToNote() {
            return mNumRunningFgJobs + mNumStartingFgJobs;
        }

        void logStatus() {
            if (DEBUG) {
                Slog.d(TAG, "assignJobsToContexts: " + this);
            }
        }

        public String toString() {
            final int totalFg = mNumRunningFgJobs + mNumStartingFgJobs;
            final int totalBg = mNumRunningBgJobs + mNumStartingBgJobs;
            return String.format(
                    "Config={tot=%d bg min/max=%d/%d}"
                            + " Running[FG/BG (total)]: %d / %d (%d)"
                            + " Pending: %d / %d (%d)"
                            + " Actual max: %d%s / %d%s (%d%s)"
                            + " Res BG: %d"
                            + " Starting: %d / %d (%d)"
                            + " Total: %d%s / %d%s (%d%s)",
                    mConfigNumMaxTotalJobs, mConfigNumMinBgJobs, mConfigNumMaxBgJobs,

                    mNumRunningFgJobs, mNumRunningBgJobs, mNumRunningFgJobs + mNumRunningBgJobs,

                    mNumPendingFgJobs, mNumPendingBgJobs, mNumPendingFgJobs + mNumPendingBgJobs,

                    mNumActualMaxFgJobs, (totalFg <= mConfigNumMaxTotalJobs) ? "" : "*",
                    mNumActualMaxBgJobs, (totalBg <= mConfigNumMaxBgJobs) ? "" : "*",
                    mNumActualMaxFgJobs + mNumActualMaxBgJobs,
                    (mNumActualMaxFgJobs + mNumActualMaxBgJobs <= mConfigNumMaxTotalJobs)
                            ? "" : "*",

                    mNumReservedForBg,

                    mNumStartingFgJobs, mNumStartingBgJobs, mNumStartingFgJobs + mNumStartingBgJobs,

                    totalFg, (totalFg <= mNumActualMaxFgJobs) ? "" : "*",
                    totalBg, (totalBg <= mNumActualMaxBgJobs) ? "" : "*",
                    totalFg + totalBg, (totalFg + totalBg <= mConfigNumMaxTotalJobs) ? "" : "*"
            );
        }

        public void dumpProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(JobCountTrackerProto.CONFIG_NUM_MAX_TOTAL_JOBS, mConfigNumMaxTotalJobs);
            proto.write(JobCountTrackerProto.CONFIG_NUM_MAX_BG_JOBS, mConfigNumMaxBgJobs);
            proto.write(JobCountTrackerProto.CONFIG_NUM_MIN_BG_JOBS, mConfigNumMinBgJobs);

            proto.write(JobCountTrackerProto.NUM_RUNNING_FG_JOBS, mNumRunningFgJobs);
            proto.write(JobCountTrackerProto.NUM_RUNNING_BG_JOBS, mNumRunningBgJobs);

            proto.write(JobCountTrackerProto.NUM_PENDING_FG_JOBS, mNumPendingFgJobs);
            proto.write(JobCountTrackerProto.NUM_PENDING_BG_JOBS, mNumPendingBgJobs);

            proto.write(JobCountTrackerProto.NUM_ACTUAL_MAX_FG_JOBS, mNumActualMaxFgJobs);
            proto.write(JobCountTrackerProto.NUM_ACTUAL_MAX_BG_JOBS, mNumActualMaxBgJobs);

            proto.write(JobCountTrackerProto.NUM_RESERVED_FOR_BG, mNumReservedForBg);

            proto.write(JobCountTrackerProto.NUM_STARTING_FG_JOBS, mNumStartingFgJobs);
            proto.write(JobCountTrackerProto.NUM_STARTING_BG_JOBS, mNumStartingBgJobs);

            proto.end(token);
        }
    }
}
