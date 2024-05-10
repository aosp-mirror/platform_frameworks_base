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

import static android.text.format.DateUtils.DAY_IN_MILLIS;
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
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseLongArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.job.JobSchedulerService;
import com.android.server.utils.AlarmQueue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Controller that tracks the number of flexible constraints being actively satisfied.
 * Drops constraint for TOP apps and lowers number of required constraints with time.
 */
public final class FlexibilityController extends StateController {
    private static final String TAG = "JobScheduler.Flex";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    /** List of all system-wide flexible constraints whose satisfaction is independent of job. */
    static final int SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS = CONSTRAINT_BATTERY_NOT_LOW
            | CONSTRAINT_CHARGING
            | CONSTRAINT_IDLE;

    /** List of all job flexible constraints whose satisfaction is job specific. */
    private static final int JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS = CONSTRAINT_CONNECTIVITY;

    /** List of all flexible constraints. */
    @VisibleForTesting
    static final int FLEXIBLE_CONSTRAINTS =
            JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS | SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS;

    private static final long NO_LIFECYCLE_END = Long.MAX_VALUE;

    /**
     * The default deadline that all flexible constraints should be dropped by if a job lacks
     * a deadline.
     */
    private long mFallbackFlexibilityDeadlineMs =
            FcConfig.DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS;

    private long mRescheduledJobDeadline = FcConfig.DEFAULT_RESCHEDULED_JOB_DEADLINE_MS;
    private long mMaxRescheduledDeadline = FcConfig.DEFAULT_MAX_RESCHEDULED_DEADLINE_MS;

    private long mUnseenConstraintGracePeriodMs =
            FcConfig.DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS;

    /** Set of constraints supported on this device for flex scheduling. */
    private final int mSupportedFlexConstraints;

    @GuardedBy("mLock")
    private boolean mFlexibilityEnabled;

    /** Set of constraints that will be used in the flex policy. */
    @GuardedBy("mLock")
    private int mAppliedConstraints = FcConfig.DEFAULT_APPLIED_CONSTRAINTS;

    private long mMinTimeBetweenFlexibilityAlarmsMs =
            FcConfig.DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS;

    /** Hard cutoff to remove flexible constraints. */
    private long mDeadlineProximityLimitMs =
            FcConfig.DEFAULT_DEADLINE_PROXIMITY_LIMIT_MS;

    /**
     * The percent of a job's lifecycle to drop number of required constraints.
     * mPercentToDropConstraints[i] denotes that at x% of a Jobs lifecycle,
     * the controller should have i+1 constraints dropped.
     */
    private int[] mPercentToDropConstraints;

    /**
     * Keeps track of what flexible constraints are satisfied at the moment.
     * Is updated by the other controllers.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    int mSatisfiedFlexibleConstraints;

    @GuardedBy("mLock")
    private final SparseLongArray mLastSeenConstraintTimesElapsed = new SparseLongArray();

    @VisibleForTesting
    @GuardedBy("mLock")
    final FlexibilityTracker mFlexibilityTracker;
    @VisibleForTesting
    @GuardedBy("mLock")
    final FlexibilityAlarmQueue mFlexibilityAlarmQueue;
    @VisibleForTesting
    final FcConfig mFcConfig;
    private final FcHandler mHandler;
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
                            mFlexibilityTracker.calculateNumDroppedConstraints(js, nowElapsed);
                            mFlexibilityAlarmQueue.scheduleDropNumConstraintsAlarm(js, nowElapsed);
                        }
                    }
                }
            };

    private static final int MSG_UPDATE_JOBS = 0;
    private static final int MSG_UPDATE_JOB = 1;

    public FlexibilityController(
            JobSchedulerService service, PrefetchController prefetchController) {
        super(service);
        mHandler = new FcHandler(AppSchedulingModuleThread.get().getLooper());
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                || mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_EMBEDDED)) {
            // Embedded devices have no user-installable apps. Assume all jobs are critical
            // and can't be flexed.
            mSupportedFlexConstraints = 0;
        } else {
            // TODO(236261941): handle devices without a battery
            mSupportedFlexConstraints = FLEXIBLE_CONSTRAINTS;
        }
        mFlexibilityEnabled = (mAppliedConstraints & mSupportedFlexConstraints) != 0;
        mFlexibilityTracker = new FlexibilityTracker(Integer.bitCount(mSupportedFlexConstraints));
        mFcConfig = new FcConfig();
        mFlexibilityAlarmQueue = new FlexibilityAlarmQueue(
                mContext, AppSchedulingModuleThread.get().getLooper());
        mPercentToDropConstraints =
                mFcConfig.DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS;
        mPrefetchController = prefetchController;
    }

    @Override
    public void startTrackingLocked() {
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
            final long nowElapsed = sElapsedRealtimeClock.millis();
            if (mSupportedFlexConstraints == 0) {
                js.setFlexibilityConstraintSatisfied(nowElapsed, true);
                return;
            }
            js.setNumAppliedFlexibleConstraints(
                    Integer.bitCount(getRelevantAppliedConstraintsLocked(js)));
            js.setFlexibilityConstraintSatisfied(nowElapsed, isFlexibilitySatisfiedLocked(js));
            mFlexibilityTracker.add(js);
            js.setTrackingController(JobStatus.TRACKING_FLEXIBILITY);
            mFlexibilityAlarmQueue.scheduleDropNumConstraintsAlarm(js, nowElapsed);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus js, JobStatus incomingJob) {
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

    boolean isEnabled() {
        synchronized (mLock) {
            return mFlexibilityEnabled;
        }
    }

    /** Checks if the flexibility constraint is actively satisfied for a given job. */
    @GuardedBy("mLock")
    boolean isFlexibilitySatisfiedLocked(JobStatus js) {
        return !mFlexibilityEnabled
                || mService.getUidBias(js.getSourceUid()) == JobInfo.BIAS_TOP_APP
                || hasEnoughSatisfiedConstraintsLocked(js)
                || mService.isCurrentlyRunningLocked(js);
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getRelevantAppliedConstraintsLocked(@NonNull JobStatus js) {
        final int relevantConstraints = SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS
                | (js.canApplyTransportAffinities() ? CONSTRAINT_CONNECTIVITY : 0);
        return mAppliedConstraints & relevantConstraints;
    }

    /**
     * Returns whether there are enough constraints satisfied to allow running the job from flex's
     * perspective. This takes into account unseen constraint combinations and expectations around
     * whether additional constraints can ever be satisfied.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    boolean hasEnoughSatisfiedConstraintsLocked(@NonNull JobStatus js) {
        final int satisfiedConstraints = mSatisfiedFlexibleConstraints & mAppliedConstraints
                & (SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS
                        | (js.areTransportAffinitiesSatisfied() ? CONSTRAINT_CONNECTIVITY : 0));
        final int numSatisfied = Integer.bitCount(satisfiedConstraints);
        if (numSatisfied >= js.getNumRequiredFlexibleConstraints()) {
            return true;
        }
        // We don't yet have the full number of required flex constraints. See if we should expect
        // to be able to reach it. If not, then there's no point waiting anymore.
        final long nowElapsed = sElapsedRealtimeClock.millis();
        if (nowElapsed < mUnseenConstraintGracePeriodMs) {
            // Too soon after boot. Not enough time to start predicting. Wait longer.
            return false;
        }

        // The intention is to not force jobs to wait for constraint combinations that have never
        // been seen together in a while. The job may still be allowed to wait for other constraint
        // combinations. Thus, the logic is:
        // If all the constraint combinations that have a count higher than the current satisfied
        // count have not been seen recently enough, then assume they won't be seen anytime soon,
        // so don't force the job to wait longer. If any combinations with a higher count have been
        // seen recently, then the job can potentially wait for those combinations.
        final int irrelevantConstraints = ~getRelevantAppliedConstraintsLocked(js);
        for (int i = mLastSeenConstraintTimesElapsed.size() - 1; i >= 0; --i) {
            final int constraints = mLastSeenConstraintTimesElapsed.keyAt(i);
            if ((constraints & irrelevantConstraints) != 0) {
                // Ignore combinations that couldn't satisfy this job's needs.
                continue;
            }
            final long lastSeenElapsed = mLastSeenConstraintTimesElapsed.valueAt(i);
            final boolean seenRecently =
                    nowElapsed - lastSeenElapsed <= mUnseenConstraintGracePeriodMs;
            if (Integer.bitCount(constraints) > numSatisfied && seenRecently) {
                // We've seen a set of constraints with a higher count than what is currently
                // satisfied recently enough, which means we can expect to see it again at some
                // point. Keep waiting for now.
                return false;
            }
        }

        // We haven't seen any constraint set with more satisfied than the current satisfied count.
        // There's no reason to expect additional constraints to be satisfied. Let the job run.
        return true;
    }

    /**
     * Sets the controller's constraint to a given state.
     * Changes flexibility constraint satisfaction for affected jobs.
     */
    void setConstraintSatisfied(int constraint, boolean state, long nowElapsed) {
        synchronized (mLock) {
            final boolean old = (mSatisfiedFlexibleConstraints & constraint) != 0;
            if (old == state) {
                return;
            }

            if (DEBUG) {
                Slog.d(TAG, "setConstraintSatisfied: "
                        + " constraint: " + constraint + " state: " + state);
            }

            // Mark now as the last time we saw this set of constraints.
            mLastSeenConstraintTimesElapsed.put(mSatisfiedFlexibleConstraints, nowElapsed);
            if (!state) {
                // Mark now as the last time we saw this particular constraint.
                // (Good for logging/dump purposes).
                mLastSeenConstraintTimesElapsed.put(constraint, nowElapsed);
            }

            mSatisfiedFlexibleConstraints =
                    (mSatisfiedFlexibleConstraints & ~constraint) | (state ? constraint : 0);

            if ((JOB_SPECIFIC_FLEXIBLE_CONSTRAINTS & constraint) != 0) {
                // Job-specific constraint --> don't need to proceed with logic below that
                // works with system-wide constraints.
                return;
            }

            if (mFlexibilityEnabled) {
                // Only attempt to update jobs if the flex logic is enabled. Otherwise, the status
                // of the jobs won't change, so all the work will be a waste.

                // Push the job update to the handler to avoid blocking other controllers and
                // potentially batch back-to-back controller state updates together.
                mHandler.obtainMessage(MSG_UPDATE_JOBS).sendToTarget();
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
        long earliestRuntime = js.getEarliestRunTime() == JobStatus.NO_EARLIEST_RUNTIME
                ? js.enqueueTime : js.getEarliestRunTime();
        if (js.getJob().isPeriodic() && js.getNumPreviousAttempts() == 0) {
            // Rescheduling periodic jobs (after a successful execution) may result in the job's
            // start time being a little after the "true" periodic start time (to avoid jobs
            // running back to back). See JobSchedulerService#getRescheduleJobForPeriodic for more
            // details. Since rescheduled periodic jobs may already be delayed slightly by this
            // policy, don't penalize them further by then enforcing the full set of applied
            // flex constraints at the beginning of the newly determined start time. Let the flex
            // constraint requirement start closer to the true periodic start time.
            final long truePeriodicStartTimeElapsed =
                    js.getLatestRunTimeElapsed() - js.getJob().getFlexMillis();
            // For now, treat the lifecycle beginning as the midpoint between the true periodic
            // start time and the adjusted start time.
            earliestRuntime = (earliestRuntime + truePeriodicStartTimeElapsed) / 2;
        }
        if (js.getJob().isPrefetch()) {
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
        return earliestRuntime;
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
        // Increase the flex deadline for jobs rescheduled more than once.
        if (js.getNumPreviousAttempts() > 1) {
            return earliest + Math.min(
                    (long) Math.scalb(mRescheduledJobDeadline, js.getNumPreviousAttempts() - 2),
                    mMaxRescheduledDeadline);
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

    @VisibleForTesting
    @ElapsedRealtimeLong
    @GuardedBy("mLock")
    long getNextConstraintDropTimeElapsedLocked(JobStatus js) {
        final long earliest = getLifeCycleBeginningElapsedLocked(js);
        final long latest = getLifeCycleEndElapsedLocked(js, earliest);
        return getNextConstraintDropTimeElapsedLocked(js, earliest, latest);
    }

    /** The elapsed time that marks when the next constraint should be dropped. */
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
        ArraySet<JobStatus> jobsByUid = mService.getJobStore().getJobsBySourceUid(uid);
        boolean hasPrefetch = false;
        for (int i = 0; i < jobsByUid.size(); i++) {
            JobStatus js = jobsByUid.valueAt(i);
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
            AppSchedulingModuleThread.getHandler().post(() -> {
                final ArraySet<JobStatus> changedJobs = new ArraySet<>();
                synchronized (mLock) {
                    final long nowElapsed = sElapsedRealtimeClock.millis();
                    for (int j = 0; j < mFlexibilityTracker.size(); j++) {
                        final ArraySet<JobStatus> jobs = mFlexibilityTracker
                                .getJobsByNumRequiredConstraints(j);
                        for (int i = jobs.size() - 1; i >= 0; --i) {
                            JobStatus js = jobs.valueAt(i);
                            mFlexibilityTracker.updateFlexibleConstraints(js, nowElapsed);
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
            mTrackedJobs.get(js.getNumRequiredFlexibleConstraints()).remove(js);
        }

        /**
         * Updates applied and dropped constraints for the job.
         */
        public void updateFlexibleConstraints(JobStatus js, long nowElapsed) {
            final int prevNumRequired = js.getNumRequiredFlexibleConstraints();

            final int numAppliedConstraints =
                    Integer.bitCount(getRelevantAppliedConstraintsLocked(js));
            js.setNumAppliedFlexibleConstraints(numAppliedConstraints);

            final int curPercent = getCurPercentOfLifecycleLocked(js, nowElapsed);
            int toDrop = 0;
            for (int i = 0; i < numAppliedConstraints; i++) {
                if (curPercent >= mPercentToDropConstraints[i]) {
                    toDrop++;
                }
            }
            js.setNumDroppedFlexibleConstraints(toDrop);

            if (prevNumRequired == js.getNumRequiredFlexibleConstraints()) {
                return;
            }
            mTrackedJobs.get(prevNumRequired).remove(js);
            add(js);
        }

        /**
         * Calculates the number of constraints that should be dropped for the job, based on how
         * far along the job is into its lifecycle.
         */
        public void calculateNumDroppedConstraints(JobStatus js, long nowElapsed) {
            final int curPercent = getCurPercentOfLifecycleLocked(js, nowElapsed);
            int toDrop = 0;
            final int jsMaxFlexibleConstraints = js.getNumAppliedFlexibleConstraints();
            for (int i = 0; i < jsMaxFlexibleConstraints; i++) {
                if (curPercent >= mPercentToDropConstraints[i]) {
                    toDrop++;
                }
            }
            setNumDroppedFlexibleConstraints(js, toDrop);
        }

        /** Returns all tracked jobs. */
        public ArrayList<ArraySet<JobStatus>> getArrayList() {
            return mTrackedJobs;
        }

        /**
         * Updates the number of dropped flexible constraints and sorts it into the tracker.
         */
        public void setNumDroppedFlexibleConstraints(JobStatus js, int numDropped) {
            if (numDropped != js.getNumDroppedFlexibleConstraints()) {
                remove(js);
                js.setNumDroppedFlexibleConstraints(numDropped);
                add(js);
            }
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
                    js.printUniqueId(pw);
                    pw.print(" from ");
                    UserHandle.formatUid(pw, js.getSourceUid());
                    pw.print(" Num Required Constraints: ");
                    pw.print(js.getNumRequiredFlexibleConstraints());
                    pw.println();
                }
            }
        }
    }

    @VisibleForTesting
    class FlexibilityAlarmQueue extends AlarmQueue<JobStatus> {
        private FlexibilityAlarmQueue(Context context, Looper looper) {
            super(context, looper, "*job.flexibility_check*",
                    "Flexible Constraint Check", true,
                    mMinTimeBetweenFlexibilityAlarmsMs);
        }

        @Override
        protected boolean isForUser(@NonNull JobStatus js, int userId) {
            return js.getSourceUserId() == userId;
        }

        public void scheduleDropNumConstraintsAlarm(JobStatus js, long nowElapsed) {
            synchronized (mLock) {
                final long earliest = getLifeCycleBeginningElapsedLocked(js);
                final long latest = getLifeCycleEndElapsedLocked(js, earliest);
                final long nextTimeElapsed =
                        getNextConstraintDropTimeElapsedLocked(js, earliest, latest);

                if (DEBUG) {
                    Slog.d(TAG, "scheduleDropNumConstraintsAlarm: "
                            + js.getSourcePackageName() + " " + js.getSourceUserId()
                            + " numApplied: " + js.getNumAppliedFlexibleConstraints()
                            + " numRequired: " + js.getNumRequiredFlexibleConstraints()
                            + " numSatisfied: " + Integer.bitCount(
                            mSatisfiedFlexibleConstraints & getRelevantAppliedConstraintsLocked(js))
                            + " curTime: " + nowElapsed
                            + " earliest: " + earliest
                            + " latest: " + latest
                            + " nextTime: " + nextTimeElapsed);
                }
                if (latest - nowElapsed < mDeadlineProximityLimitMs) {
                    if (DEBUG) {
                        Slog.d(TAG, "deadline proximity met: " + js);
                    }
                    mFlexibilityTracker.setNumDroppedFlexibleConstraints(js,
                            js.getNumAppliedFlexibleConstraints());
                    mHandler.obtainMessage(MSG_UPDATE_JOB, js).sendToTarget();
                    return;
                }
                if (nextTimeElapsed == NO_LIFECYCLE_END) {
                    // There is no known or estimated next time to drop a constraint.
                    removeAlarmForKey(js);
                    return;
                }
                if (latest - nextTimeElapsed <= mDeadlineProximityLimitMs) {
                    if (DEBUG) {
                        Slog.d(TAG, "last alarm set: " + js);
                    }
                    addAlarm(js, latest - mDeadlineProximityLimitMs);
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
                    if (DEBUG) {
                        Slog.d(TAG, "Alarm fired for " + js.toShortString());
                    }
                    mFlexibilityTracker.calculateNumDroppedConstraints(js, nowElapsed);
                    if (js.getNumRequiredFlexibleConstraints() > 0) {
                        scheduleDropNumConstraintsAlarm(js, nowElapsed);
                    }
                    if (js.setFlexibilityConstraintSatisfied(nowElapsed,
                            isFlexibilitySatisfiedLocked(js))) {
                        changedJobs.add(js);
                    }
                }
                mStateChangedListener.onControllerStateChanged(changedJobs);
            }
        }
    }

    private class FcHandler extends Handler {
        FcHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_JOBS:
                    removeMessages(MSG_UPDATE_JOBS);

                    synchronized (mLock) {
                        final long nowElapsed = sElapsedRealtimeClock.millis();
                        final ArraySet<JobStatus> changedJobs = new ArraySet<>();

                        final int numAppliedSystemWideConstraints = Integer.bitCount(
                                mAppliedConstraints & SYSTEM_WIDE_FLEXIBLE_CONSTRAINTS);
                        for (int o = 0; o <= numAppliedSystemWideConstraints; ++o) {
                            final ArraySet<JobStatus> jobsByNumConstraints = mFlexibilityTracker
                                    .getJobsByNumRequiredConstraints(o);

                            if (jobsByNumConstraints != null) {
                                for (int i = 0; i < jobsByNumConstraints.size(); i++) {
                                    final JobStatus js = jobsByNumConstraints.valueAt(i);
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
                    }
                    break;

                case MSG_UPDATE_JOB:
                    synchronized (mLock) {
                        final JobStatus js = (JobStatus) msg.obj;
                        if (DEBUG) {
                            Slog.d("blah", "Checking on " + js.toShortString());
                        }
                        final long nowElapsed = sElapsedRealtimeClock.millis();
                        if (js.setFlexibilityConstraintSatisfied(
                                nowElapsed, isFlexibilitySatisfiedLocked(js))) {
                            // TODO(141645789): add method that will take a single job
                            ArraySet<JobStatus> changedJob = new ArraySet<>();
                            changedJob.add(js);
                            mStateChangedListener.onControllerStateChanged(changedJob);
                        }
                    }
                    break;
            }
        }
    }

    class FcConfig {
        private boolean mShouldReevaluateConstraints = false;

        /** Prefix to use with all constant keys in order to "sub-namespace" the keys. */
        private static final String FC_CONFIG_PREFIX = "fc_";

        @VisibleForTesting
        static final String KEY_APPLIED_CONSTRAINTS = FC_CONFIG_PREFIX + "applied_constraints";
        static final String KEY_DEADLINE_PROXIMITY_LIMIT =
                FC_CONFIG_PREFIX + "flexibility_deadline_proximity_limit_ms";
        static final String KEY_FALLBACK_FLEXIBILITY_DEADLINE =
                FC_CONFIG_PREFIX + "fallback_flexibility_deadline_ms";
        static final String KEY_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS =
                FC_CONFIG_PREFIX + "min_time_between_flexibility_alarms_ms";
        static final String KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS =
                FC_CONFIG_PREFIX + "percents_to_drop_num_flexible_constraints";
        static final String KEY_MAX_RESCHEDULED_DEADLINE_MS =
                FC_CONFIG_PREFIX + "max_rescheduled_deadline_ms";
        static final String KEY_RESCHEDULED_JOB_DEADLINE_MS =
                FC_CONFIG_PREFIX + "rescheduled_job_deadline_ms";
        static final String KEY_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS =
                FC_CONFIG_PREFIX + "unseen_constraint_grace_period_ms";

        static final int DEFAULT_APPLIED_CONSTRAINTS = 0;
        @VisibleForTesting
        static final long DEFAULT_DEADLINE_PROXIMITY_LIMIT_MS = 15 * MINUTE_IN_MILLIS;
        @VisibleForTesting
        static final long DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS = 72 * HOUR_IN_MILLIS;
        private static final long DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS = MINUTE_IN_MILLIS;
        @VisibleForTesting
        final int[] DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS = {50, 60, 70, 80};
        private static final long DEFAULT_RESCHEDULED_JOB_DEADLINE_MS = HOUR_IN_MILLIS;
        private static final long DEFAULT_MAX_RESCHEDULED_DEADLINE_MS = 5 * DAY_IN_MILLIS;
        @VisibleForTesting
        static final long DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS = 3 * DAY_IN_MILLIS;

        /** Which constraints to apply/consider in flex policy. */
        public int APPLIED_CONSTRAINTS = DEFAULT_APPLIED_CONSTRAINTS;
        /** How close to a jobs' deadline all flexible constraints will be dropped. */
        public long DEADLINE_PROXIMITY_LIMIT_MS = DEFAULT_DEADLINE_PROXIMITY_LIMIT_MS;
        /** For jobs that lack a deadline, the time that will be used to drop all constraints by. */
        public long FALLBACK_FLEXIBILITY_DEADLINE_MS = DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS;
        public long MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS =
                DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS;
        /** The percentages of a jobs' lifecycle to drop the number of required constraints. */
        public int[] PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS =
                DEFAULT_PERCENT_TO_DROP_FLEXIBLE_CONSTRAINTS;
        /** Initial fallback flexible deadline for rescheduled jobs. */
        public long RESCHEDULED_JOB_DEADLINE_MS = DEFAULT_RESCHEDULED_JOB_DEADLINE_MS;
        /** The max deadline for rescheduled jobs. */
        public long MAX_RESCHEDULED_DEADLINE_MS = DEFAULT_MAX_RESCHEDULED_DEADLINE_MS;
        /**
         * How long to wait after last seeing a constraint combination before no longer waiting for
         * it in order to run jobs.
         */
        public long UNSEEN_CONSTRAINT_GRACE_PERIOD_MS = DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS;

        @GuardedBy("mLock")
        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            switch (key) {
                case KEY_APPLIED_CONSTRAINTS:
                    APPLIED_CONSTRAINTS =
                            properties.getInt(key, DEFAULT_APPLIED_CONSTRAINTS)
                                    & mSupportedFlexConstraints;
                    if (mAppliedConstraints != APPLIED_CONSTRAINTS) {
                        mAppliedConstraints = APPLIED_CONSTRAINTS;
                        mShouldReevaluateConstraints = true;
                        if (mAppliedConstraints != 0) {
                            mFlexibilityEnabled = true;
                            mPrefetchController
                                    .registerPrefetchChangedListener(mPrefetchChangedListener);
                        } else {
                            mFlexibilityEnabled = false;
                            mPrefetchController
                                    .unRegisterPrefetchChangedListener(mPrefetchChangedListener);
                        }
                    }
                    break;
                case KEY_RESCHEDULED_JOB_DEADLINE_MS:
                    RESCHEDULED_JOB_DEADLINE_MS =
                            properties.getLong(key, DEFAULT_RESCHEDULED_JOB_DEADLINE_MS);
                    if (mRescheduledJobDeadline != RESCHEDULED_JOB_DEADLINE_MS) {
                        mRescheduledJobDeadline = RESCHEDULED_JOB_DEADLINE_MS;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_MAX_RESCHEDULED_DEADLINE_MS:
                    MAX_RESCHEDULED_DEADLINE_MS =
                            properties.getLong(key, DEFAULT_MAX_RESCHEDULED_DEADLINE_MS);
                    if (mMaxRescheduledDeadline != MAX_RESCHEDULED_DEADLINE_MS) {
                        mMaxRescheduledDeadline = MAX_RESCHEDULED_DEADLINE_MS;
                        mShouldReevaluateConstraints = true;
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
                        mFlexibilityAlarmQueue
                                .setMinTimeBetweenAlarmsMs(MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS);
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS:
                    UNSEEN_CONSTRAINT_GRACE_PERIOD_MS =
                            properties.getLong(key, DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS);
                    if (mUnseenConstraintGracePeriodMs != UNSEEN_CONSTRAINT_GRACE_PERIOD_MS) {
                        mUnseenConstraintGracePeriodMs = UNSEEN_CONSTRAINT_GRACE_PERIOD_MS;
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
            int[] dropPercentInt = new int[Integer.bitCount(FLEXIBLE_CONSTRAINTS)];
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

            pw.print(KEY_APPLIED_CONSTRAINTS, APPLIED_CONSTRAINTS).println();
            pw.print(KEY_DEADLINE_PROXIMITY_LIMIT, DEADLINE_PROXIMITY_LIMIT_MS).println();
            pw.print(KEY_FALLBACK_FLEXIBILITY_DEADLINE, FALLBACK_FLEXIBILITY_DEADLINE_MS).println();
            pw.print(KEY_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS,
                    MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS).println();
            pw.print(KEY_PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS,
                    PERCENTS_TO_DROP_NUM_FLEXIBLE_CONSTRAINTS).println();
            pw.print(KEY_RESCHEDULED_JOB_DEADLINE_MS, RESCHEDULED_JOB_DEADLINE_MS).println();
            pw.print(KEY_MAX_RESCHEDULED_DEADLINE_MS, MAX_RESCHEDULED_DEADLINE_MS).println();
            pw.print(KEY_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS, UNSEEN_CONSTRAINT_GRACE_PERIOD_MS)
                    .println();

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
    public void dumpConstants(IndentingPrintWriter pw) {
        mFcConfig.dump(pw);
    }

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        pw.print("Satisfied Flexible Constraints:");
        JobStatus.dumpConstraints(pw, mSatisfiedFlexibleConstraints);
        pw.println();
        pw.println();

        final long nowElapsed = sElapsedRealtimeClock.millis();
        pw.println("Time since constraint combos last seen:");
        pw.increaseIndent();
        for (int i = 0; i < mLastSeenConstraintTimesElapsed.size(); ++i) {
            final int constraints = mLastSeenConstraintTimesElapsed.keyAt(i);
            if (constraints == mSatisfiedFlexibleConstraints) {
                pw.print("0ms");
            } else {
                TimeUtils.formatDuration(
                        mLastSeenConstraintTimesElapsed.valueAt(i), nowElapsed, pw);
            }
            pw.print(":");
            if (constraints != 0) {
                // dumpConstraints prepends with a space, so no need to add a space after the :
                JobStatus.dumpConstraints(pw, constraints);
            } else {
                pw.print(" none");
            }
            pw.println();
        }
        pw.decreaseIndent();

        pw.println();
        mFlexibilityTracker.dump(pw, predicate);
        pw.println();
        mFlexibilityAlarmQueue.dump(pw);
    }
}
