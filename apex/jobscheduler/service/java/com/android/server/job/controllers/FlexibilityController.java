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

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.app.job.JobInfo;
import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;

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
 * TODO: Plug in to other controllers (b/239047584), handle prefetch (b/238887951)
 */
public final class FlexibilityController extends StateController {
    /**
     * List of all potential flexible constraints
     */
    @VisibleForTesting
    static final int FLEXIBLE_CONSTRAINTS = JobStatus.CONSTRAINT_BATTERY_NOT_LOW
            | JobStatus.CONSTRAINT_CHARGING
            | JobStatus.CONSTRAINT_CONNECTIVITY
            | JobStatus.CONSTRAINT_IDLE;

    /** Hard cutoff to remove flexible constraints */
    private static final long DEADLINE_PROXIMITY_LIMIT_MS = 15 * MINUTE_IN_MILLIS;

    /**
     * Keeps track of what flexible constraints are satisfied at the moment.
     * Is updated by the other controllers.
     */
    private int mSatisfiedFlexibleConstraints;

    @VisibleForTesting
    @GuardedBy("mLock")
    final FlexibilityTracker mFlexibilityTracker;

    private final FlexibilityAlarmQueue mFlexibilityAlarmQueue;
    private final long mMinTimeBetweenAlarmsMs = MINUTE_IN_MILLIS;

    /**
     * The percent of a Jobs lifecycle to drop number of required constraints.
     * mPercentToDropConstraints[i] denotes that at x% of a Jobs lifecycle,
     * the controller should have i+1 constraints dropped.
     */
    private final int[] mPercentToDropConstraints = {50, 60, 70, 80};

    /** The default deadline that all flexible constraints should be dropped by. */
    private final long mDefaultFlexibleDeadline = 72 * HOUR_IN_MILLIS;

    public FlexibilityController(JobSchedulerService service) {
        super(service);
        mFlexibilityTracker = new FlexibilityTracker(FLEXIBLE_CONSTRAINTS);
        mFlexibilityAlarmQueue = new FlexibilityAlarmQueue(
                mContext, JobSchedulerBackgroundThread.get().getLooper());
    }

    /**
     * StateController interface
     */
    @Override
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
    public void maybeStopTrackingJobLocked(JobStatus js, JobStatus incomingJob, boolean forUpdate) {
        if (js.clearTrackingController(JobStatus.TRACKING_FLEXIBILITY)) {
            mFlexibilityAlarmQueue.removeAlarmForKey(js);
            mFlexibilityTracker.remove(js);
        }
    }

    /** Checks if the flexibility constraint is actively satisfied for a given job. */
    @VisibleForTesting
    boolean isFlexibilitySatisfiedLocked(JobStatus js) {
        synchronized (mLock) {
            return mService.getUidBias(js.getUid()) == JobInfo.BIAS_TOP_APP
                    || mService.isCurrentlyRunningLocked(js)
                    || getNumSatisfiedRequiredConstraintsLocked(js)
                    >= js.getNumRequiredFlexibleConstraints();
        }
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getNumSatisfiedRequiredConstraintsLocked(JobStatus js) {
        return Integer.bitCount(js.getFlexibleConstraints() & mSatisfiedFlexibleConstraints);
    }

    /**
     * Sets the controller's constraint to a given state.
     * Changes flexibility constraint satisfaction for affected jobs.
     */
    @VisibleForTesting
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

            final ArraySet<JobStatus> jobs = mFlexibilityTracker.getJobsByNumRequiredConstraints(
                    numConstraintsToUpdate);
            final long nowElapsed = sElapsedRealtimeClock.millis();

            for (int i = 0; i < jobs.size(); i++) {
                JobStatus js = jobs.valueAt(i);
                js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
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
                ? earliest + mDefaultFlexibleDeadline
                : js.getLatestRunTimeElapsed();
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
        for (int i = 0; i < jobsByUid.size(); i++) {
            JobStatus js = jobsByUid.get(i);
            if (js.hasFlexibilityConstraint()) {
                js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
            }
        }
    }

    @VisibleForTesting
    class FlexibilityTracker {
        final ArrayList<ArraySet<JobStatus>> mTrackedJobs;

        FlexibilityTracker(int flexibleConstraints) {
            mTrackedJobs = new ArrayList<>();
            int numFlexibleConstraints = Integer.bitCount(flexibleConstraints);
            for (int i = 0; i <= numFlexibleConstraints; i++) {
                mTrackedJobs.add(new ArraySet<JobStatus>());
            }
        }

        /** Gets every tracked job with a given number of required constraints. */
        public ArraySet<JobStatus> getJobsByNumRequiredConstraints(int numRequired) {
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
                    "Flexible Constraint Check", false, mMinTimeBetweenAlarmsMs);
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
                    if (js.getLatestRunTimeElapsed() - time < DEADLINE_PROXIMITY_LIMIT_MS) {
                        mFlexibilityTracker.adjustJobsRequiredConstraints(js,
                                -js.getNumRequiredFlexibleConstraints());
                        continue;
                    }
                    if (mFlexibilityTracker.adjustJobsRequiredConstraints(js, -1)) {
                        mFlexibilityAlarmQueue.addAlarm(js, time);
                    }
                }
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        pw.println("# Constraints Satisfied: " + Integer.bitCount(mSatisfiedFlexibleConstraints));
        pw.println();

        mFlexibilityTracker.dump(pw, predicate);
    }
}
