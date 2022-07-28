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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.job.JobSchedulerService;
import com.android.server.utils.AlarmQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Controller that tracks the number of flexible constraints being actively satisfied.
 * Drops constraint for TOP apps and lowers number of required constraints with time.
 *
 * TODO(b/238887951): handle prefetch
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

    @VisibleForTesting
    static final int NUM_JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS =
            Integer.bitCount(JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS);

    static final int NUM_SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS =
            Integer.bitCount(SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS);

    @VisibleForTesting
    static final int NUM_FLEXIBLE_CONSTRAINTS = Integer.bitCount(FLEXIBLE_CONSTRAINTS);

    /** Hard cutoff to remove flexible constraints. */
    private static final long DEADLINE_PROXIMITY_LIMIT_MS = 15 * MINUTE_IN_MILLIS;

    /**
     * The default deadline that all flexible constraints should be dropped by if a job lacks
     * a deadline.
     */
    private static final long DEFAULT_FLEXIBILITY_DEADLINE = 72 * HOUR_IN_MILLIS;

    /**
     * Keeps track of what flexible constraints are satisfied at the moment.
     * Is updated by the other controllers.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    int mSatisfiedFlexibleConstraints;
    @GuardedBy("mLock")
    private boolean mFlexibilityEnabled = FcConstants.DEFAULT_FLEXIBILITY_ENABLED;

    @VisibleForTesting
    @GuardedBy("mLock")
    final FlexibilityTracker mFlexibilityTracker;
    private final FcConstants mFcConstants;

    private final FlexibilityAlarmQueue mFlexibilityAlarmQueue;
    private static final long MIN_TIME_BETWEEN_ALARMS_MS = MINUTE_IN_MILLIS;

    /**
     * The percent of a Jobs lifecycle to drop number of required constraints.
     * PERCENT_TO_DROP_CONSTRAINTS[i] denotes that at x% of a Jobs lifecycle,
     * the controller should have i+1 constraints dropped.
     */
    private static final int[] PERCENT_TO_DROP_CONSTRAINTS = {50, 60, 70, 80};

    public FlexibilityController(JobSchedulerService service) {
        super(service);
        mFlexibilityTracker = new FlexibilityTracker(NUM_FLEXIBLE_CONSTRAINTS);
        mFcConstants = new FcConstants();
        mFlexibilityAlarmQueue = new FlexibilityAlarmQueue(
                mContext, JobSchedulerBackgroundThread.get().getLooper());
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
            mFlexibilityAlarmQueue.addAlarm(js, getNextConstraintDropTimeElapsed(js));
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
    void setConstraintSatisfied(int constraint, boolean state) {
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

            final long nowElapsed = sElapsedRealtimeClock.millis();

            // In order to get the range of all potentially satisfied jobs, we start at the number
            // of satisfied system-wide constraints and iterate to the max number of potentially
            // satisfied constraints, determined by how many job-specific constraints exist.
            for (int j = 0; j <= NUM_JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS; j++) {
                final ArraySet<JobStatus> jobs = mFlexibilityTracker
                        .getJobsByNumRequiredConstraints(numConstraintsToUpdate + j);

                if (jobs == null) {
                    // If there are no more jobs to iterate through we can just return.
                    return;
                }

                for (int i = 0; i < jobs.size(); i++) {
                    JobStatus js = jobs.valueAt(i);
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

    /** The elapsed time that marks when the next constraint should be dropped. */
    @VisibleForTesting
    @ElapsedRealtimeLong
    long getNextConstraintDropTimeElapsed(JobStatus js) {
        final long earliest = js.getEarliestRunTime() == JobStatus.NO_EARLIEST_RUNTIME
                ? js.enqueueTime : js.getEarliestRunTime();
        final long latest = js.getLatestRunTimeElapsed() == JobStatus.NO_LATEST_RUNTIME
                ? earliest + DEFAULT_FLEXIBILITY_DEADLINE
                : js.getLatestRunTimeElapsed();
        final int percent = PERCENT_TO_DROP_CONSTRAINTS[js.getNumDroppedFlexibleConstraints()];
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
        for (int i = 0; i < jobsByUid.size(); i++) {
            JobStatus js = jobsByUid.get(i);
            if (js.hasFlexibilityConstraint()) {
                js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onConstantsUpdatedLocked() {
        if (mFcConstants.mShouldReevaluateConstraints) {
            // Update job bookkeeping out of band.
            JobSchedulerBackgroundThread.getHandler().post(() -> {
                final ArraySet<JobStatus> changedJobs = new ArraySet<>();
                synchronized (mLock) {
                    final long nowElapsed = sElapsedRealtimeClock.millis();
                    for (int j = 1; j <= mFlexibilityTracker.size(); j++) {
                        final ArraySet<JobStatus> jobs = mFlexibilityTracker
                                .getJobsByNumRequiredConstraints(j);
                        for (int i = 0; i < jobs.size(); i++) {
                            JobStatus js = jobs.valueAt(i);
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
        mFcConstants.mShouldReevaluateConstraints = false;
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
            return mTrackedJobs.get(numRequired - 1);
        }

        /** adds a JobStatus object based on number of required flexible constraints. */
        public void add(JobStatus js) {
            if (js.getNumRequiredFlexibleConstraints() <= 0) {
                return;
            }
            mTrackedJobs.get(js.getNumRequiredFlexibleConstraints() - 1).add(js);
        }

        /** Removes a JobStatus object. */
        public void remove(JobStatus js) {
            if (js.getNumRequiredFlexibleConstraints() == 0) {
                return;
            }
            mTrackedJobs.get(js.getNumRequiredFlexibleConstraints() - 1).remove(js);
        }

        /** Returns all tracked jobs. */
        public ArrayList<ArraySet<JobStatus>> getArrayList() {
            return mTrackedJobs;
        }

        /**
         * Adjusts number of required flexible constraints and sorts it into the tracker.
         * Returns false if the job status's number of flexible constraints is now 0.
         * Jobs with 0 required flexible constraints are removed from the tracker.
         */
        public boolean adjustJobsRequiredConstraints(JobStatus js, int n) {
            remove(js);
            js.adjustNumRequiredFlexibleConstraints(n);
            final long nowElapsed = sElapsedRealtimeClock.millis();
            js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
            if (js.getNumRequiredFlexibleConstraints() <= 0) {
                maybeStopTrackingJobLocked(js, null, false);
                return false;
            }
            add(js);
            return true;
        }

        public int size() {
            return mTrackedJobs.size();
        }

        public void dump(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
            for (int i = 0; i < mTrackedJobs.size(); i++) {
                ArraySet<JobStatus> jobs = mTrackedJobs.get(i);
                for (int j = 0; j < mTrackedJobs.size(); j++) {
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

    private class FlexibilityAlarmQueue extends AlarmQueue<JobStatus> {
        private FlexibilityAlarmQueue(Context context, Looper looper) {
            super(context, looper, "*job.flexibility_check*",
                    "Flexible Constraint Check", false,
                    MIN_TIME_BETWEEN_ALARMS_MS);
        }

        @Override
        protected boolean isForUser(@NonNull JobStatus js, int userId) {
            return js.getSourceUserId() == userId;
        }

        @Override
        protected void processExpiredAlarms(@NonNull ArraySet<JobStatus> expired) {
            synchronized (mLock) {
                JobStatus js;
                for (int i = 0; i < expired.size(); i++) {
                    js = expired.valueAt(i);
                    long time = getNextConstraintDropTimeElapsed(js);
                    int toDecrease =
                            js.getLatestRunTimeElapsed() - time < DEADLINE_PROXIMITY_LIMIT_MS
                            ? -js.getNumRequiredFlexibleConstraints() : -1;
                    if (mFlexibilityTracker.adjustJobsRequiredConstraints(js, toDecrease)) {
                        mFlexibilityAlarmQueue.addAlarm(js, time);
                    }
                }
            }
        }
    }

    @VisibleForTesting
    class FcConstants {
        private boolean mShouldReevaluateConstraints = false;

        private static final boolean DEFAULT_FLEXIBILITY_ENABLED = false;

        public boolean FLEXIBILITY_ENABLED = DEFAULT_FLEXIBILITY_ENABLED;

        /** Prefix to use with all constant keys in order to "sub-namespace" the keys. */
        private static final String FC_CONSTANT_PREFIX = "fc_";

        static final String KEY_FLEXIBILITY_ENABLED = FC_CONSTANT_PREFIX + "enable_flexibility";

        // TODO(b/239925946): properly handle DeviceConfig and changing variables
        @GuardedBy("mLock")
        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            switch (key) {
                case KEY_FLEXIBILITY_ENABLED:
                    FLEXIBILITY_ENABLED = properties.getBoolean(key, DEFAULT_FLEXIBILITY_ENABLED);
                    if (mFlexibilityEnabled != FLEXIBILITY_ENABLED) {
                        mFlexibilityEnabled = FLEXIBILITY_ENABLED;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
            }
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
    FcConstants getFcConstants() {
        return mFcConstants;
    }

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        pw.println("# Constraints Satisfied: " + Integer.bitCount(mSatisfiedFlexibleConstraints));
        pw.println();

        mFlexibilityTracker.dump(pw, predicate);
        mFcConstants.dump(pw);
    }
}
