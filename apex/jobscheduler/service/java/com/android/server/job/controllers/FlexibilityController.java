/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_BATTERY_NOT_LOW;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CHARGING;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_CONNECTIVITY;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_FLEXIBLE;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_IDLE;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.job.JobSchedulerService;
import com.android.server.utils.AlarmQueue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Controller that tracks the number of flexible constraints being actively satisfied.
 * Drops constraint for TOP apps and lowers number of required constraints with time.
 */
public final class FlexibilityController extends StateController {
    private static final String TAG = "JobScheduler.Flexibility";

    /** List of all system-wide flexible constraints whose satisfaction is independent of job. */
    static final int SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS = CONSTRAINT_BATTERY_NOT_LOW
            | CONSTRAINT_CHARGING
            | CONSTRAINT_IDLE;

    /** List of all job flexible constraints whose satisfaction is job specific. */
    private static final int JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS = CONSTRAINT_CONNECTIVITY;

    /** List of all flexible constraints. */
    private static final int FLEXIBLE_CONSTRAINTS =
            JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS | SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS;

    private static final int NUM_JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS =
            Integer.bitCount(JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS);

    static final int NUM_SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS =
            Integer.bitCount(SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS);

    static final int NUM_FLEXIBLE_CONSTRAINTS = Integer.bitCount(FLEXIBLE_CONSTRAINTS);

    private static final long NO_LIFECYCLE_END = Long.MAX_VALUE;

    /**
     * Keeps track of what flexible constraints are satisfied at the moment.
     * Is updated by the other controllers.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    int mSatisfiedFlexibleConstraints;

    /** Hard cutoff to remove flexible constraints. */
    private long mDeadlineProximityLimitMs =
            FcConfig.DEFAULT_DEADLINE_PROXIMITY_LIMIT_MS;

    /**
     * The default deadline that all flexible constraints should be dropped by if a job lacks
     * a deadline.
     */
    private long mFallbackFlexibilityDeadlineMs =
            FcConfig.DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS;

    @GuardedBy("mLock")
    @VisibleForTesting
    boolean mFlexibilityEnabled = FcConfig.DEFAULT_FLEXIBILITY_ENABLED;

    private long mMinTimeBetweenFlexibilityAlarmsMs =
            FcConfig.DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS;

    /**
     * The percent of a job's lifecycle to drop number of required constraints.
     * mPercentToDropConstraints[i] denotes that at x% of a Jobs lifecycle,
     * the controller should have i+1 constraints dropped.
     */
    private int[] mPercentToDropConstraints;

    @VisibleForTesting
    @GuardedBy("mLock")
    final FlexibilityTracker mFlexibilityTracker;
    @VisibleForTesting
    @GuardedBy("mLock")
    final FlexibilityAlarmQueue mFlexibilityAlarmQueue;
    @VisibleForTesting
    final FcConfig mFcConfig;

    @VisibleForTesting
    final PrefetchController mPrefetchController;

    /**
     * Stores the beginning of prefetch jobs lifecycle per app as a maximum of
     * the last time the app was used and the last time the launch time was updated.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    final SparseArrayMap<String, Long> mPrefetchLifeCycleStart = new SparseArrayMap<>();

    @VisibleForTesting
    final PrefetchController.PrefetchChangedListener mPrefetchChangedListener =
            new PrefetchController.PrefetchChangedListener() {
                @Override
                public void onPrefetchCacheUpdated(ArraySet<JobStatus> jobs, int userId,
                        String pkgName, long prevEstimatedLaunchTime,
                        long newEstimatedLaunchTime, long nowElapsed) {
                    synchronized (mLock) {
                        final long prefetchThreshold =
                                mPrefetchController.getLaunchTimeThresholdMs();
                        boolean jobWasInPrefetchWindow  = prevEstimatedLaunchTime
                                - prefetchThreshold < nowElapsed;
                        boolean jobIsInPrefetchWindow  = newEstimatedLaunchTime
                                - prefetchThreshold < nowElapsed;
                        if (jobIsInPrefetchWindow != jobWasInPrefetchWindow) {
                            // If the job was in the window previously then changing the start
                            // of the lifecycle to the current moment without a large change in the
                            // end would squeeze the window too tight fail to drop constraints.
                            mPrefetchLifeCycleStart.add(userId, pkgName, Math.max(nowElapsed,
                                    mPrefetchLifeCycleStart.getOrDefault(userId, pkgName, 0L)));
                        }
                        for (int i = 0; i < jobs.size(); i++) {
                            JobStatus js = jobs.valueAt(i);
                            if (!js.hasFlexibilityConstraint()) {
                                continue;
                            }
                            mFlexibilityTracker.resetJobNumDroppedConstraints(js, nowElapsed);
                            mFlexibilityAlarmQueue.scheduleDropNumConstraintsAlarm(js, nowElapsed);
                        }
                    }
                }
            };

    public FlexibilityController(
            JobSchedulerService service, PrefetchController prefetchController) {
        super(service);
        mFlexibilityTracker = new FlexibilityTracker(NUM_FLEXIBLE_CONSTRAINTS);
        mFcConfig = new FcConfig();
        mFlexibilityAlarmQueue = new FlexibilityAlarmQueue(
                mContext, JobSchedulerBackgroundThread.get().getLooper());
        mPercentToDropConstraints =
                mFcConfig.DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS;
        mPrefetchController = prefetchController;
        if (mFlexibilityEnabled) {
            mPrefetchController.registerPrefetchChangedListener(mPrefetchChangedListener);
        }
    }

    /**
     * StateController interface.
     */
    @Override
    @GuardedBy("mLock")
    public void maybeStartTrackingJobLocked(JobStatus js, JobStatus lastJob) {
        if (js.hasFlexibilityConstraint()) {
            mFlexibilityTracker.add(js);
            js.setTrackingController(JobStatus.TRACKING_FLEXIBILITY);
            final long nowElapsed = sElapsedRealtimeClock.millis();
            js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
            mFlexibilityAlarmQueue.scheduleDropNumConstraintsAlarm(js, nowElapsed);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus js, JobStatus incomingJob, boolean forUpdate) {
        if (js.clearTrackingController(JobStatus.TRACKING_FLEXIBILITY)) {
            mFlexibilityAlarmQueue.removeAlarmForKey(js);
            mFlexibilityTracker.remove(js);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onAppRemovedLocked(String packageName, int uid) {
        final int userId = UserHandle.getUserId(uid);
        mPrefetchLifeCycleStart.delete(userId, packageName);
    }

    @Override
    @GuardedBy("mLock")
    public void onUserRemovedLocked(int userId) {
        mPrefetchLifeCycleStart.delete(userId);
    }

    /** Checks if the flexibility constraint is actively satisfied for a given job. */
    @GuardedBy("mLock")
    boolean isFlexibilitySatisfiedLocked(JobStatus js) {
        return !mFlexibilityEnabled
                || mService.getUidBias(js.getUid()) == JobInfo.BIAS_TOP_APP
                || mService.isCurrentlyRunningLocked(js)
                || getNumSatisfiedRequiredConstraintsLocked(js)
                >= js.getNumRequiredFlexibleConstraints();
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getNumSatisfiedRequiredConstraintsLocked(JobStatus js) {
        return Integer.bitCount(mSatisfiedFlexibleConstraints)
                + (js.getHasAccessToUnmetered() ? 1 : 0);
    }

    /**
     * Sets the controller's constraint to a given state.
     * Changes flexibility constraint satisfaction for affected jobs.
     */
    @VisibleForTesting
    void setConstraintSatisfied(int constraint, boolean state, long nowElapsed) {
        synchronized (mLock) {
            final boolean old = (mSatisfiedFlexibleConstraints & constraint) != 0;
            if (old == state) {
                return;
            }

            final int prevSatisfied = Integer.bitCount(mSatisfiedFlexibleConstraints);
            mSatisfiedFlexibleConstraints =
                    (mSatisfiedFlexibleConstraints & ~constraint) | (state ? constraint : 0);
            final int curSatisfied = Integer.bitCount(mSatisfiedFlexibleConstraints);

            // Only the max of the number of required flexible constraints will need to be updated
            // The rest did not have a change in state and are still satisfied or unsatisfied.
            final int numConstraintsToUpdate = Math.max(curSatisfied, prevSatisfied);

            // In order to get the range of all potentially satisfied jobs, we start at the number
            // of satisfied system-wide constraints and iterate to the max number of potentially
            // satisfied constraints, determined by how many job-specific constraints exist.
            for (int j = 0; j <= NUM_JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS; j++) {
                final ArraySet<JobStatus> jobsByNumConstraints = mFlexibilityTracker
                        .getJobsByNumRequiredConstraints(numConstraintsToUpdate + j);

                if (jobsByNumConstraints == null) {
                    // If there are no more jobs to iterate through we can just return.
                    return;
                }

                for (int i = 0; i < jobsByNumConstraints.size(); i++) {
                    JobStatus js = jobsByNumConstraints.valueAt(i);
                    js.setFlexibilityConstraintSatisfied(
                            nowElapsed, isFlexibilitySatisfiedLocked(js));
                }
            }
        }
    }

    /** Checks if the given constraint is satisfied in the flexibility controller. */
    @VisibleForTesting
    boolean isConstraintSatisfied(int constraint) {
        return (mSatisfiedFlexibleConstraints & constraint) != 0;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    long getLifeCycleBeginningElapsedLocked(JobStatus js) {
        if (js.getJob().isPrefetch()) {
            final long earliestRuntime = Math.max(js.enqueueTime, js.getEarliestRunTime());
            final long estimatedLaunchTime =
                    mPrefetchController.getNextEstimatedLaunchTimeLocked(js);
            long prefetchWindowStart = mPrefetchLifeCycleStart.getOrDefault(
                    js.getSourceUserId(), js.getSourcePackageName(), 0L);
            if (estimatedLaunchTime != Long.MAX_VALUE) {
                prefetchWindowStart = Math.max(prefetchWindowStart,
                        estimatedLaunchTime - mPrefetchController.getLaunchTimeThresholdMs());
            }
            return Math.max(prefetchWindowStart, earliestRuntime);
        }
        return js.getEarliestRunTime() == JobStatus.NO_EARLIEST_RUNTIME
                ? js.enqueueTime : js.getEarliestRunTime();
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    long getLifeCycleEndElapsedLocked(JobStatus js, long earliest) {
        if (js.getJob().isPrefetch()) {
            final long estimatedLaunchTime =
                    mPrefetchController.getNextEstimatedLaunchTimeLocked(js);
            // Prefetch jobs aren't supposed to have deadlines after T.
            // But some legacy apps might still schedule them with deadlines.
            if (js.getLatestRunTimeElapsed() != JobStatus.NO_LATEST_RUNTIME) {
                // If there is a deadline, the earliest time is the end of the lifecycle.
                return Math.min(
                        estimatedLaunchTime - mConstants.PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS,
                        js.getLatestRunTimeElapsed());
            }
            if (estimatedLaunchTime != Long.MAX_VALUE) {
                return estimatedLaunchTime - mConstants.PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS;
            }
            // There is no deadline and no estimated launch time.
            return NO_LIFECYCLE_END;
        }
        return js.getLatestRunTimeElapsed() == JobStatus.NO_LATEST_RUNTIME
                ? earliest + mFallbackFlexibilityDeadlineMs : js.getLatestRunTimeElapsed();
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getCurPercentOfLifecycleLocked(JobStatus js, long nowElapsed) {
        final long earliest = getLifeCycleBeginningElapsedLocked(js);
        final long latest = getLifeCycleEndElapsedLocked(js, earliest);
        if (latest == NO_LIFECYCLE_END || earliest >= nowElapsed) {
            return 0;
        }
        if (nowElapsed > latest || latest == earliest) {
            return 100;
        }
        final int percentInTime = (int) ((nowElapsed - earliest) * 100 / (latest - earliest));
        return percentInTime;
    }

    /** The elapsed time that marks when the next constraint should be dropped. */
    @VisibleForTesting
    @ElapsedRealtimeLong
    @GuardedBy("mLock")
    long getNextConstraintDropTimeElapsedLocked(JobStatus js) {
        final long earliest = getLifeCycleBeginningElapsedLocked(js);
        final long latest = getLifeCycleEndElapsedLocked(js, earliest);
        return getNextConstraintDropTimeElapsedLocked(js, earliest, latest);
    }

    /** The elapsed time that marks when the next constraint should be dropped. */
    @VisibleForTesting
    @ElapsedRealtimeLong
    @GuardedBy("mLock")
    long getNextConstraintDropTimeElapsedLocked(JobStatus js, long earliest, long latest) {
        if (latest == NO_LIFECYCLE_END
                || js.getNumDroppedFlexibleConstraints() == mPercentToDropConstraints.length) {
            return NO_LIFECYCLE_END;
        }
        final int percent = mPercentToDropConstraints[js.getNumDroppedFlexibleConstraints()];
        final long percentInTime = ((latest - earliest) * percent) / 100;
        return earliest + percentInTime;
    }

    @Override
    @GuardedBy("mLock")
    public void onUidBiasChangedLocked(int uid, int prevBias, int newBias) {
        if (prevBias != JobInfo.BIAS_TOP_APP && newBias != JobInfo.BIAS_TOP_APP) {
            return;
        }
        final long nowElapsed = sElapsedRealtimeClock.millis();
        List<JobStatus> jobsByUid = mService.getJobStore().getJobsByUid(uid);
        boolean hasPrefetch = false;
        for (int i = 0; i < jobsByUid.size(); i++) {
            JobStatus js = jobsByUid.get(i);
            if (js.hasFlexibilityConstraint()) {
                js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
                hasPrefetch |= js.getJob().isPrefetch();
            }
        }

        // Prefetch jobs can't run when the app is TOP, so it should not be included in their
        // lifecycle, and marks the beginning of a new lifecycle.
        if (hasPrefetch && prevBias == JobInfo.BIAS_TOP_APP) {
            final int userId = UserHandle.getUserId(uid);
            final ArraySet<String> pkgs = mService.getPackagesForUidLocked(uid);
            if (pkgs == null) {
                return;
            }
            for (int i = 0; i < pkgs.size(); i++) {
                String pkg = pkgs.valueAt(i);
                mPrefetchLifeCycleStart.add(userId, pkg,
                        Math.max(mPrefetchLifeCycleStart.getOrDefault(userId, pkg, 0L),
                                nowElapsed));
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onConstantsUpdatedLocked() {
        if (mFcConfig.mShouldReevaluateConstraints) {
            JobSchedulerBackgroundThread.getHandler().post(() -> {
                final ArraySet<JobStatus> changedJobs = new ArraySet<>();
                synchronized (mLock) {
                    final long nowElapsed = sElapsedRealtimeClock.millis();
                    for (int j = 0; j < mFlexibilityTracker.size(); j++) {
                        final ArraySet<JobStatus> jobs = mFlexibilityTracker
                                .getJobsByNumRequiredConstraints(j);
                        for (int i = 0; i < jobs.size(); i++) {
                            JobStatus js = jobs.valueAt(i);
                            mFlexibilityTracker.resetJobNumDroppedConstraints(js, nowElapsed);
                            mFlexibilityAlarmQueue.scheduleDropNumConstraintsAlarm(js, nowElapsed);
                            if (js.setFlexibilityConstraintSatisfied(
                                    nowElapsed, isFlexibilitySatisfiedLocked(js))) {
                                changedJobs.add(js);
                            }
                        }
                    }
                }
                if (changedJobs.size() > 0) {
                    mStateChangedListener.onControllerStateChanged(changedJobs);
                }
            });
        }
    }

    @Override
    @GuardedBy("mLock")
    public void prepareForUpdatedConstantsLocked() {
        mFcConfig.mShouldReevaluateConstraints = false;
    }

    @Override
    @GuardedBy("mLock")
    public void processConstantLocked(DeviceConfig.Properties properties, String key) {
        mFcConfig.processConstantLocked(properties, key);
    }

    @VisibleForTesting
    class FlexibilityTracker {
        final ArrayList<ArraySet<JobStatus>> mTrackedJobs;

        FlexibilityTracker(int numFlexibleConstraints) {
            mTrackedJobs = new ArrayList<>();
            for (int i = 0; i <= numFlexibleConstraints; i++) {
                mTrackedJobs.add(new ArraySet<JobStatus>());
            }
        }

        /** Gets every tracked job with a given number of required constraints. */
        @Nullable
        public ArraySet<JobStatus> getJobsByNumRequiredConstraints(int numRequired) {
            if (numRequired > mTrackedJobs.size()) {
                Slog.wtfStack(TAG, "Asked for a larger number of constraints than exists.");
                return null;
            }
            return mTrackedJobs.get(numRequired);
        }

        /** adds a JobStatus object based on number of required flexible constraints. */
        public void add(JobStatus js) {
            if (js.getNumRequiredFlexibleConstraints() < 0) {
                return;
            }
            mTrackedJobs.get(js.getNumRequiredFlexibleConstraints()).add(js);
        }

        /** Removes a JobStatus object. */
        public void remove(JobStatus js) {
            if (js.getNumRequiredFlexibleConstraints() == 0) {
                return;
            }
            mTrackedJobs.get(js.getNumRequiredFlexibleConstraints()).remove(js);
        }

        public void resetJobNumDroppedConstraints(JobStatus js, long nowElapsed) {
            final int curPercent = getCurPercentOfLifecycleLocked(js, nowElapsed);
            int toDrop = 0;
            final int jsMaxFlexibleConstraints = NUM_SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS
                    + (js.getPreferUnmetered() ? 1 : 0);
            for (int i = 0; i < jsMaxFlexibleConstraints; i++) {
                if (curPercent >= mPercentToDropConstraints[i]) {
                    toDrop++;
                }
            }
            adjustJobsRequiredConstraints(
                    js, js.getNumDroppedFlexibleConstraints() - toDrop, nowElapsed);
        }

        /** Returns all tracked jobs. */
        public ArrayList<ArraySet<JobStatus>> getArrayList() {
            return mTrackedJobs;
        }

        /**
         * Adjusts number of required flexible constraints and sorts it into the tracker.
         * Returns false if the job status's number of flexible constraints is now 0.
         */
        public boolean adjustJobsRequiredConstraints(JobStatus js, int adjustBy, long nowElapsed) {
            if (adjustBy != 0) {
                remove(js);
                js.adjustNumRequiredFlexibleConstraints(adjustBy);
                js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
                add(js);
            }
            return js.getNumRequiredFlexibleConstraints() > 0;
        }

        public int size() {
            return mTrackedJobs.size();
        }

        public void dump(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
            for (int i = 0; i < mTrackedJobs.size(); i++) {
                ArraySet<JobStatus> jobs = mTrackedJobs.get(i);
                for (int j = 0; j < jobs.size(); j++) {
                    final JobStatus js = jobs.valueAt(j);
                    if (!predicate.test(js)) {
                        continue;
                    }
                    pw.print("#");
                    js.printUniqueId(pw);
                    pw.print(" from ");
                    UserHandle.formatUid(pw, js.getSourceUid());
                    pw.println();
                }
            }
        }
    }

    @VisibleForTesting
    class FlexibilityAlarmQueue extends AlarmQueue<JobStatus> {
        private FlexibilityAlarmQueue(Context context, Looper looper) {
            super(context, looper, "*job.flexibility_check*",
                    "Flexible Constraint Check", false,
                    mMinTimeBetweenFlexibilityAlarmsMs);
        }

        @Override
        protected boolean isForUser(@NonNull JobStatus js, int userId) {
            return js.getSourceUserId() == userId;
        }

        public void scheduleDropNumConstraintsAlarm(JobStatus js, long nowElapsed) {
            long nextTimeElapsed;
            synchronized (mLock) {
                final long earliest = getLifeCycleBeginningElapsedLocked(js);
                final long latest = getLifeCycleEndElapsedLocked(js, earliest);
                nextTimeElapsed = getNextConstraintDropTimeElapsedLocked(js, earliest, latest);
                if (nextTimeElapsed == NO_LIFECYCLE_END) {
                    // There is no known or estimated next time to drop a constraint.
                    removeAlarmForKey(js);
                    return;
                }

                if (latest - nextTimeElapsed < mDeadlineProximityLimitMs) {
                    mFlexibilityTracker.adjustJobsRequiredConstraints(
                            js, -js.getNumRequiredFlexibleConstraints(), nowElapsed);
                    return;
                }
                addAlarm(js, nextTimeElapsed);
            }
        }

        @Override
        protected void processExpiredAlarms(@NonNull ArraySet<JobStatus> expired) {
            synchronized (mLock) {
                ArraySet<JobStatus> changedJobs = new ArraySet<>();
                final long nowElapsed = sElapsedRealtimeClock.millis();
                for (int i = 0; i < expired.size(); i++) {
                    JobStatus js = expired.valueAt(i);
                    boolean wasFlexibilitySatisfied = js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE);

                    final long earliest = getLifeCycleBeginningElapsedLocked(js);
                    final long latest = getLifeCycleEndElapsedLocked(js, earliest);

                    if (latest - nowElapsed < mDeadlineProximityLimitMs) {
                        mFlexibilityTracker.adjustJobsRequiredConstraints(js,
                                -js.getNumRequiredFlexibleConstraints(), nowElapsed);
                    } else {
                        long nextTimeElapsed =
                                getNextConstraintDropTimeElapsedLocked(js, earliest, latest);
                        if (mFlexibilityTracker.adjustJobsRequiredConstraints(js, -1,  nowElapsed)
                                && nextTimeElapsed != NO_LIFECYCLE_END) {
                            mFlexibilityAlarmQueue.addAlarm(js, nextTimeElapsed);
                        }
                    }
                    if (wasFlexibilitySatisfied != js.isConstraintSatisfied(CONSTRAINT_FLEXIBLE)) {
                        changedJobs.add(js);
                    }
                }
                mStateChangedListener.onControllerStateChanged(changedJobs);
            }
        }
    }

    @VisibleForTesting
    class FcConfig {
        private boolean mShouldReevaluateConstraints = false;

        /** Prefix to use with all constant keys in order to "sub-namespace" the keys. */
        private static final String FC_CONFIG_PREFIX = "fc_";

        static final String KEY_FLEXIBILITY_ENABLED = FC_CONFIG_PREFIX + "enable_flexibility";
        static final String KEY_DEADLINE_PROXIMITY_LIMIT =
                FC_CONFIG_PREFIX + "flexibility_deadline_proximity_limit_ms";
        static final String KEY_FALLBACK_FLEXIBILITY_DEADLINE =
                FC_CONFIG_PREFIX + "fallback_flexibility_deadline_ms";
        static final String KEY_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS =
                FC_CONFIG_PREFIX + "min_alarm_time_flexibility_ms";
        static final String KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS =
                FC_CONFIG_PREFIX + "percents_to_drop_num_flexible_constraints";

        private static final boolean DEFAULT_FLEXIBILITY_ENABLED = false;
        @VisibleForTesting
        static final long DEFAULT_DEADLINE_PROXIMITY_LIMIT_MS = 15 * MINUTE_IN_MILLIS;
        @VisibleForTesting
        static final long DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS = 72 * HOUR_IN_MILLIS;
        private static final long DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS = MINUTE_IN_MILLIS;
        @VisibleForTesting
        final int[] DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS = {50, 60, 70, 80};

        /**
         * If false the controller will not track new jobs
         * and the flexibility constraint will always be satisfied.
         */
        public boolean FLEXIBILITY_ENABLED = DEFAULT_FLEXIBILITY_ENABLED;
        /** How close to a jobs' deadline all flexible constraints will be dropped. */
        public long DEADLINE_PROXIMITY_LIMIT_MS = DEFAULT_DEADLINE_PROXIMITY_LIMIT_MS;
        /** For jobs that lack a deadline, the time that will be used to drop all constraints by. */
        public long FALLBACK_FLEXIBILITY_DEADLINE_MS = DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS;
        public long MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS =
                DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS;
        /** The percentages of a jobs' lifecycle to drop the number of required constraints. */
        public int[] PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS =
                DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS;

        @GuardedBy("mLock")
        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            switch (key) {
                case KEY_FLEXIBILITY_ENABLED:
                    FLEXIBILITY_ENABLED = properties.getBoolean(key, DEFAULT_FLEXIBILITY_ENABLED);
                    if (mFlexibilityEnabled != FLEXIBILITY_ENABLED) {
                        mFlexibilityEnabled = FLEXIBILITY_ENABLED;
                        mShouldReevaluateConstraints = true;
                        if (mFlexibilityEnabled) {
                            mPrefetchController
                                    .registerPrefetchChangedListener(mPrefetchChangedListener);
                        } else {
                            mPrefetchController
                                    .unRegisterPrefetchChangedListener(mPrefetchChangedListener);
                        }
                    }
                    break;
                case KEY_DEADLINE_PROXIMITY_LIMIT:
                    DEADLINE_PROXIMITY_LIMIT_MS =
                            properties.getLong(key, DEFAULT_DEADLINE_PROXIMITY_LIMIT_MS);
                    if (mDeadlineProximityLimitMs != DEADLINE_PROXIMITY_LIMIT_MS) {
                        mDeadlineProximityLimitMs = DEADLINE_PROXIMITY_LIMIT_MS;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_FALLBACK_FLEXIBILITY_DEADLINE:
                    FALLBACK_FLEXIBILITY_DEADLINE_MS =
                            properties.getLong(key, DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS);
                    if (mFallbackFlexibilityDeadlineMs != FALLBACK_FLEXIBILITY_DEADLINE_MS) {
                        mFallbackFlexibilityDeadlineMs = FALLBACK_FLEXIBILITY_DEADLINE_MS;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS:
                    MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS =
                            properties.getLong(key, DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS);
                    if (mMinTimeBetweenFlexibilityAlarmsMs
                            != MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS) {
                        mMinTimeBetweenFlexibilityAlarmsMs = MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS:
                    String dropPercentString = properties.getString(key, "");
                    PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS =
                            parsePercentToDropString(dropPercentString);
                    if (PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS != null
                            && !Arrays.equals(mPercentToDropConstraints,
                            PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS)) {
                        mPercentToDropConstraints = PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
            }
        }

        private int[] parsePercentToDropString(String s) {
            String[] dropPercentString = s.split(",");
            int[] dropPercentInt = new int[NUM_FLEXIBLE_CONSTRAINTS];
            if (dropPercentInt.length != dropPercentString.length) {
                return DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS;
            }
            int prevPercent = 0;
            for (int i = 0; i < dropPercentString.length; i++) {
                try {
                    dropPercentInt[i] =
                            Integer.parseInt(dropPercentString[i]);
                } catch (NumberFormatException ex) {
                    Slog.e(TAG, "Provided string was improperly formatted.", ex);
                    return DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS;
                }
                if (dropPercentInt[i] < prevPercent) {
                    Slog.wtf(TAG, "Percents to drop constraints were not in increasing order.");
                    return DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS;
                }
                prevPercent = dropPercentInt[i];
            }

            return dropPercentInt;
        }

        private void dump(IndentingPrintWriter pw) {
            pw.println();
            pw.print(FlexibilityController.class.getSimpleName());
            pw.println(":");
            pw.increaseIndent();

            pw.print(KEY_FLEXIBILITY_ENABLED, FLEXIBILITY_ENABLED).println();

            pw.decreaseIndent();
        }
    }

    @VisibleForTesting
    @NonNull
    FcConfig getFcConfig() {
        return mFcConfig;
    }

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        pw.println("# Constraints Satisfied: " + Integer.bitCount(mSatisfiedFlexibleConstraints));
        pw.println();

        mFlexibilityTracker.dump(pw, predicate);
        mFcConfig.dump(pw);
    }
}
