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

import static android.app.job.JobInfo.PRIORITY_DEFAULT;
import static android.app.job.JobInfo.PRIORITY_HIGH;
import static android.app.job.JobInfo.PRIORITY_LOW;
import static android.app.job.JobInfo.PRIORITY_MAX;
import static android.app.job.JobInfo.PRIORITY_MIN;
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseArrayMap;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
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
    /**
     * The default deadline that all flexible constraints should be dropped by if a job lacks
     * a deadline, keyed by job priority.
     */
    private SparseLongArray mFallbackFlexibilityDeadlines =
            FcConfig.DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES;
    /**
     * The scores to use for each job, keyed by job priority.
     */
    private SparseIntArray mFallbackFlexibilityDeadlineScores =
            FcConfig.DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES;
    /**
     * The amount of time to add (scaled by job run score) to the fallback flexibility deadline,
     * keyed by job priority.
     */
    private SparseLongArray mFallbackFlexibilityAdditionalScoreTimeFactors =
            FcConfig.DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS;

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
     * mPercentsToDropConstraints[i] denotes that at x% of a Jobs lifecycle,
     * the controller should have i+1 constraints dropped. Keyed by job priority.
     */
    private SparseArray<int[]> mPercentsToDropConstraints;

    /**
     * Keeps track of what flexible constraints are satisfied at the moment.
     * Is updated by the other controllers.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    int mSatisfiedFlexibleConstraints;

    @GuardedBy("mLock")
    private final SparseLongArray mLastSeenConstraintTimesElapsed = new SparseLongArray();

    private DeviceIdleInternal mDeviceIdleInternal;
    private final ArraySet<String> mPowerAllowlistedApps = new ArraySet<>();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED:
                    mHandler.post(FlexibilityController.this::updatePowerAllowlistCache);
                    break;
            }
        }
    };
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

    /** Helper object to track job run score for each app. */
    private static class JobScoreTracker {
        private static class JobScoreBucket {
            @ElapsedRealtimeLong
            public long startTimeElapsed;
            public int score;

            private void reset() {
                startTimeElapsed = 0;
                score = 0;
            }
        }

        private static final int NUM_SCORE_BUCKETS = 24;
        private static final long MAX_TIME_WINDOW_MS = 24 * HOUR_IN_MILLIS;
        private final JobScoreBucket[] mScoreBuckets = new JobScoreBucket[NUM_SCORE_BUCKETS];
        private int mScoreBucketIndex = 0;

        public void addScore(int add, long nowElapsed) {
            JobScoreBucket bucket = mScoreBuckets[mScoreBucketIndex];
            if (bucket == null) {
                bucket = new JobScoreBucket();
                bucket.startTimeElapsed = nowElapsed;
                mScoreBuckets[mScoreBucketIndex] = bucket;
            } else if (bucket.startTimeElapsed < nowElapsed - MAX_TIME_WINDOW_MS) {
                // The bucket is too old.
                bucket.reset();
                bucket.startTimeElapsed = nowElapsed;
            } else if (bucket.startTimeElapsed
                    < nowElapsed - MAX_TIME_WINDOW_MS / NUM_SCORE_BUCKETS) {
                // The current bucket's duration has completed. Move on to the next bucket.
                mScoreBucketIndex = (mScoreBucketIndex + 1) % NUM_SCORE_BUCKETS;
                addScore(add, nowElapsed);
                return;
            }

            bucket.score += add;
        }

        public int getScore(long nowElapsed) {
            int score = 0;
            final long earliestElapsed = nowElapsed - MAX_TIME_WINDOW_MS;
            for (JobScoreBucket bucket : mScoreBuckets) {
                if (bucket != null && bucket.startTimeElapsed >= earliestElapsed) {
                    score += bucket.score;
                }
            }
            return score;
        }

        public void dump(@NonNull IndentingPrintWriter pw, long nowElapsed) {
            pw.print("{");

            boolean printed = false;
            for (int x = 0; x < mScoreBuckets.length; ++x) {
                final int idx = (mScoreBucketIndex + 1 + x) % mScoreBuckets.length;
                final JobScoreBucket jsb = mScoreBuckets[idx];
                if (jsb == null || jsb.startTimeElapsed == 0) {
                    continue;
                }
                if (printed) {
                    pw.print(", ");
                }
                TimeUtils.formatDuration(jsb.startTimeElapsed, nowElapsed, pw);
                pw.print("=");
                pw.print(jsb.score);
                printed = true;
            }

            pw.print("}");
        }
    }

    /**
     * Set of {@link JobScoreTracker JobScoreTrackers} for each app.
     * Keyed by source UID -> source package.
     **/
    private final SparseArrayMap<String, JobScoreTracker> mJobScoreTrackers =
            new SparseArrayMap<>();

    private static final int MSG_CHECK_ALL_JOBS = 0;
    /** Check the jobs in {@link #mJobsToCheck} */
    private static final int MSG_CHECK_JOBS = 1;
    /** Check the jobs of packages in {@link #mPackagesToCheck} */
    private static final int MSG_CHECK_PACKAGES = 2;

    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mJobsToCheck = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<String> mPackagesToCheck = new ArraySet<>();

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
        mPercentsToDropConstraints =
                FcConfig.DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS;
        mPrefetchController = prefetchController;

        if (mFlexibilityEnabled) {
            registerBroadcastReceiver();
        }
    }

    @Override
    public void onSystemServicesReady() {
        mDeviceIdleInternal = LocalServices.getService(DeviceIdleInternal.class);
        mHandler.post(FlexibilityController.this::updatePowerAllowlistCache);
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
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        // Use the job's requested priority to determine its score since that is what the developer
        // selected and it will be stable across job runs.
        final int score = mFallbackFlexibilityDeadlineScores
                .get(jobStatus.getJob().getPriority(), jobStatus.getJob().getPriority() / 100);
        JobScoreTracker jobScoreTracker =
                mJobScoreTrackers.get(jobStatus.getSourceUid(), jobStatus.getSourcePackageName());
        if (jobScoreTracker == null) {
            jobScoreTracker = new JobScoreTracker();
            mJobScoreTrackers.add(jobStatus.getSourceUid(), jobStatus.getSourcePackageName(),
                    jobScoreTracker);
        }
        jobScoreTracker.addScore(score, sElapsedRealtimeClock.millis());
    }

    @Override
    public void unprepareFromExecutionLocked(JobStatus jobStatus) {
        // The job didn't actually start. Undo the score increase.
        JobScoreTracker jobScoreTracker =
                mJobScoreTrackers.get(jobStatus.getSourceUid(), jobStatus.getSourcePackageName());
        if (jobScoreTracker == null) {
            Slog.e(TAG, "Unprepared a job that didn't result in a score change");
            return;
        }
        final int score = mFallbackFlexibilityDeadlineScores
                .get(jobStatus.getJob().getPriority(), jobStatus.getJob().getPriority() / 100);
        jobScoreTracker.addScore(-score, sElapsedRealtimeClock.millis());
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus js, JobStatus incomingJob) {
        if (js.clearTrackingController(JobStatus.TRACKING_FLEXIBILITY)) {
            mFlexibilityAlarmQueue.removeAlarmForKey(js);
            mFlexibilityTracker.remove(js);
        }
        mJobsToCheck.remove(js);
    }

    @Override
    @GuardedBy("mLock")
    public void onAppRemovedLocked(String packageName, int uid) {
        final int userId = UserHandle.getUserId(uid);
        mPrefetchLifeCycleStart.delete(userId, packageName);
        mJobScoreTrackers.delete(uid, packageName);
        for (int i = mJobsToCheck.size() - 1; i >= 0; --i) {
            final JobStatus js = mJobsToCheck.valueAt(i);
            if ((js.getSourceUid() == uid && js.getSourcePackageName().equals(packageName))
                    || (js.getUid() == uid && js.getCallingPackageName().equals(packageName))) {
                mJobsToCheck.removeAt(i);
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onUserRemovedLocked(int userId) {
        mPrefetchLifeCycleStart.delete(userId);
        for (int u = mJobScoreTrackers.numMaps() - 1; u >= 0; --u) {
            final int uid = mJobScoreTrackers.keyAt(u);
            if (UserHandle.getUserId(uid) == userId) {
                mJobScoreTrackers.deleteAt(u);
            }
        }
        for (int i = mJobsToCheck.size() - 1; i >= 0; --i) {
            final JobStatus js = mJobsToCheck.valueAt(i);
            if (UserHandle.getUserId(js.getSourceUid()) == userId
                    || UserHandle.getUserId(js.getUid()) == userId) {
                mJobsToCheck.removeAt(i);
            }
        }
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
                // Exclude all jobs of the TOP app
                || mService.getUidBias(js.getSourceUid()) == JobInfo.BIAS_TOP_APP
                // Only exclude DEFAULT+ priority jobs for BFGS+ apps
                || (mService.getUidBias(js.getSourceUid()) >= JobInfo.BIAS_BOUND_FOREGROUND_SERVICE
                        && js.getEffectivePriority() >= PRIORITY_DEFAULT)
                // For apps in the power allowlist, automatically exclude DEFAULT+ priority jobs.
                || (js.getEffectivePriority() >= PRIORITY_DEFAULT
                        && mPowerAllowlistedApps.contains(js.getSourcePackageName()))
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
                mHandler.obtainMessage(MSG_CHECK_ALL_JOBS).sendToTarget();
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
    int getScoreLocked(int uid, @NonNull String pkgName, long nowElapsed) {
        final JobScoreTracker scoreTracker = mJobScoreTrackers.get(uid, pkgName);
        return scoreTracker == null ? 0 : scoreTracker.getScore(nowElapsed);
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    long getLifeCycleEndElapsedLocked(JobStatus js, long nowElapsed, long earliest) {
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
        if (js.getLatestRunTimeElapsed() == JobStatus.NO_LATEST_RUNTIME) {
            // Intentionally use the effective priority here. If a job's priority was effectively
            // lowered, it will be less likely to run quickly given other policies in JobScheduler.
            // Thus, there's no need to further delay the job based on flex policy.
            final int jobPriority = js.getEffectivePriority();
            final int jobScore =
                    getScoreLocked(js.getSourceUid(), js.getSourcePackageName(), nowElapsed);
            // Set an upper limit on the fallback deadline so that the delay doesn't become extreme.
            final long fallbackDeadlineMs = Math.min(3 * mFallbackFlexibilityDeadlineMs,
                    mFallbackFlexibilityDeadlines.get(jobPriority, mFallbackFlexibilityDeadlineMs)
                            + mFallbackFlexibilityAdditionalScoreTimeFactors
                                    .get(jobPriority, MINUTE_IN_MILLIS) * jobScore);
            return earliest + fallbackDeadlineMs;
        }
        return js.getLatestRunTimeElapsed();
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getCurPercentOfLifecycleLocked(JobStatus js, long nowElapsed) {
        final long earliest = getLifeCycleBeginningElapsedLocked(js);
        final long latest = getLifeCycleEndElapsedLocked(js, nowElapsed, earliest);
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
        final long latest =
                getLifeCycleEndElapsedLocked(js, sElapsedRealtimeClock.millis(), earliest);
        return getNextConstraintDropTimeElapsedLocked(js, earliest, latest);
    }

    /** The elapsed time that marks when the next constraint should be dropped. */
    @ElapsedRealtimeLong
    @GuardedBy("mLock")
    long getNextConstraintDropTimeElapsedLocked(JobStatus js, long earliest, long latest) {
        final int[] percentsToDropConstraints =
                getPercentsToDropConstraints(js.getEffectivePriority());
        if (latest == NO_LIFECYCLE_END
                || js.getNumDroppedFlexibleConstraints() == percentsToDropConstraints.length) {
            return NO_LIFECYCLE_END;
        }
        final int percent = percentsToDropConstraints[js.getNumDroppedFlexibleConstraints()];
        final long percentInTime = ((latest - earliest) * percent) / 100;
        return earliest + percentInTime;
    }

    @NonNull
    private int[] getPercentsToDropConstraints(int priority) {
        int[] percentsToDropConstraints = mPercentsToDropConstraints.get(priority);
        if (percentsToDropConstraints == null) {
            Slog.wtf(TAG, "No %-to-drop for priority " + JobInfo.getPriorityString(priority));
            return new int[]{50, 60, 70, 80};
        }
        return percentsToDropConstraints;
    }

    @Override
    @GuardedBy("mLock")
    public void onUidBiasChangedLocked(int uid, int prevBias, int newBias) {
        if (prevBias < JobInfo.BIAS_BOUND_FOREGROUND_SERVICE
                && newBias < JobInfo.BIAS_BOUND_FOREGROUND_SERVICE) {
            // All changes are below BFGS. There's no significant change to care about.
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

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    private void unregisterBroadcastReceiver() {
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    private void updatePowerAllowlistCache() {
        if (mDeviceIdleInternal == null) {
            return;
        }

        // Don't call out to DeviceIdleController with the lock held.
        final String[] allowlistedPkgs = mDeviceIdleInternal.getFullPowerWhitelistExceptIdle();
        final ArraySet<String> changedPkgs = new ArraySet<>();
        synchronized (mLock) {
            changedPkgs.addAll(mPowerAllowlistedApps);
            mPowerAllowlistedApps.clear();
            for (final String pkgName : allowlistedPkgs) {
                mPowerAllowlistedApps.add(pkgName);
                if (changedPkgs.contains(pkgName)) {
                    changedPkgs.remove(pkgName);
                } else {
                    changedPkgs.add(pkgName);
                }
            }
            mPackagesToCheck.addAll(changedPkgs);
            mHandler.sendEmptyMessage(MSG_CHECK_PACKAGES);
        }
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

            final int[] percentsToDropConstraints =
                    getPercentsToDropConstraints(js.getEffectivePriority());
            final int curPercent = getCurPercentOfLifecycleLocked(js, nowElapsed);
            int toDrop = 0;
            for (int i = 0; i < numAppliedConstraints; i++) {
                if (curPercent >= percentsToDropConstraints[i]) {
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
            final int[] percentsToDropConstraints =
                    getPercentsToDropConstraints(js.getEffectivePriority());
            for (int i = 0; i < jsMaxFlexibleConstraints; i++) {
                if (curPercent >= percentsToDropConstraints[i]) {
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

        public void dump(IndentingPrintWriter pw, Predicate<JobStatus> predicate, long nowElapsed) {
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
                    pw.print("-> Num Required Constraints: ");
                    pw.print(js.getNumRequiredFlexibleConstraints());

                    pw.print(", lifecycle=[");
                    final long earliest = getLifeCycleBeginningElapsedLocked(js);
                    pw.print(earliest);
                    pw.print(", (");
                    pw.print(getCurPercentOfLifecycleLocked(js, nowElapsed));
                    pw.print("%), ");
                    pw.print(getLifeCycleEndElapsedLocked(js, nowElapsed, earliest));
                    pw.print("]");

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
                final long latest = getLifeCycleEndElapsedLocked(js, nowElapsed, earliest);
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
                    mJobsToCheck.add(js);
                    mHandler.sendEmptyMessage(MSG_CHECK_JOBS);
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
                case MSG_CHECK_ALL_JOBS:
                    removeMessages(MSG_CHECK_ALL_JOBS);

                    synchronized (mLock) {
                        mJobsToCheck.clear();
                        mPackagesToCheck.clear();
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

                case MSG_CHECK_JOBS:
                    synchronized (mLock) {
                        final long nowElapsed = sElapsedRealtimeClock.millis();
                        ArraySet<JobStatus> changedJobs = new ArraySet<>();

                        for (int i = mJobsToCheck.size() - 1; i >= 0; --i) {
                            final JobStatus js = mJobsToCheck.valueAt(i);
                            if (DEBUG) {
                                Slog.d(TAG, "Checking on " + js.toShortString());
                            }
                            if (js.setFlexibilityConstraintSatisfied(
                                    nowElapsed, isFlexibilitySatisfiedLocked(js))) {
                                changedJobs.add(js);
                            }
                        }

                        mJobsToCheck.clear();
                        if (changedJobs.size() > 0) {
                            mStateChangedListener.onControllerStateChanged(changedJobs);
                        }
                    }
                    break;

                case MSG_CHECK_PACKAGES:
                    synchronized (mLock) {
                        final long nowElapsed = sElapsedRealtimeClock.millis();
                        final ArraySet<JobStatus> changedJobs = new ArraySet<>();

                        mService.getJobStore().forEachJob(
                                (js) -> mPackagesToCheck.contains(js.getSourcePackageName())
                                        || mPackagesToCheck.contains(js.getCallingPackageName()),
                                (js) -> {
                                    if (DEBUG) {
                                        Slog.d(TAG, "Checking on " + js.toShortString());
                                    }
                                    if (js.setFlexibilityConstraintSatisfied(
                                            nowElapsed, isFlexibilitySatisfiedLocked(js))) {
                                        changedJobs.add(js);
                                    }
                                });

                        mPackagesToCheck.clear();
                        if (changedJobs.size() > 0) {
                            mStateChangedListener.onControllerStateChanged(changedJobs);
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
        static final String KEY_FALLBACK_FLEXIBILITY_DEADLINES =
                FC_CONFIG_PREFIX + "fallback_flexibility_deadlines";
        static final String KEY_FALLBACK_FLEXIBILITY_DEADLINE_SCORES =
                FC_CONFIG_PREFIX + "fallback_flexibility_deadline_scores";
        static final String KEY_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS =
                FC_CONFIG_PREFIX + "fallback_flexibility_deadline_additional_score_time_factors";
        static final String KEY_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS =
                FC_CONFIG_PREFIX + "min_time_between_flexibility_alarms_ms";
        static final String KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS =
                FC_CONFIG_PREFIX + "percents_to_drop_flexible_constraints";
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
        static final long DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_MS = 24 * HOUR_IN_MILLIS;
        static final SparseLongArray DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES = new SparseLongArray();
        static final SparseIntArray DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES =
                new SparseIntArray();
        static final SparseLongArray
                DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS =
                new SparseLongArray();
        @VisibleForTesting
        static final SparseArray<int[]> DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS =
                new SparseArray<>();

        static {
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.put(PRIORITY_MAX, HOUR_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.put(PRIORITY_HIGH, 6 * HOUR_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.put(PRIORITY_DEFAULT, 12 * HOUR_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.put(PRIORITY_LOW, 24 * HOUR_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.put(PRIORITY_MIN, 48 * HOUR_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.put(PRIORITY_MAX, 5);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.put(PRIORITY_HIGH, 4);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.put(PRIORITY_DEFAULT, 3);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.put(PRIORITY_LOW, 2);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.put(PRIORITY_MIN, 1);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS
                    .put(PRIORITY_MAX, 0);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS
                    .put(PRIORITY_HIGH, 4 * MINUTE_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS
                    .put(PRIORITY_DEFAULT, 3 * MINUTE_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS
                    .put(PRIORITY_LOW, 2 * MINUTE_IN_MILLIS);
            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS
                    .put(PRIORITY_MIN, 1 * MINUTE_IN_MILLIS);
            DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                    .put(PRIORITY_MAX, new int[]{1, 2, 3, 4});
            DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                    .put(PRIORITY_HIGH, new int[]{33, 50, 60, 75});
            DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                    .put(PRIORITY_DEFAULT, new int[]{50, 60, 70, 80});
            DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                    .put(PRIORITY_LOW, new int[]{50, 60, 70, 80});
            DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS
                    .put(PRIORITY_MIN, new int[]{55, 65, 75, 85});
        }

        private static final long DEFAULT_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS = MINUTE_IN_MILLIS;
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
        /**
         * The percentages of a jobs' lifecycle to drop the number of required constraints.
         * Keyed by job priority.
         */
        public SparseArray<int[]> PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS = new SparseArray<>();
        /** Initial fallback flexible deadline for rescheduled jobs. */
        public long RESCHEDULED_JOB_DEADLINE_MS = DEFAULT_RESCHEDULED_JOB_DEADLINE_MS;
        /** The max deadline for rescheduled jobs. */
        public long MAX_RESCHEDULED_DEADLINE_MS = DEFAULT_MAX_RESCHEDULED_DEADLINE_MS;
        /**
         * How long to wait after last seeing a constraint combination before no longer waiting for
         * it in order to run jobs.
         */
        public long UNSEEN_CONSTRAINT_GRACE_PERIOD_MS = DEFAULT_UNSEEN_CONSTRAINT_GRACE_PERIOD_MS;
        /**
         * The base fallback deadlines to use if a job doesn't have its own deadline. Values are in
         * milliseconds and keyed by job priority.
         */
        public final SparseLongArray FALLBACK_FLEXIBILITY_DEADLINES = new SparseLongArray();
        /**
         * The score to ascribe to each job, keyed by job priority.
         */
        public final SparseIntArray FALLBACK_FLEXIBILITY_DEADLINE_SCORES = new SparseIntArray();
        /**
         * How much additional time to increase the fallback deadline by based on the app's current
         * job run score. Values are in
         * milliseconds and keyed by job priority.
         */
        public final SparseLongArray FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS =
                new SparseLongArray();

        FcConfig() {
            // Copy the values from the DEFAULT_* data structures to avoid accidentally modifying
            // the DEFAULT_* data structures in other parts of the code.
            for (int i = 0; i < DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.size(); ++i) {
                FALLBACK_FLEXIBILITY_DEADLINES.put(
                        DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.keyAt(i),
                        DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES.valueAt(i));
            }
            for (int i = 0; i < DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.size(); ++i) {
                FALLBACK_FLEXIBILITY_DEADLINE_SCORES.put(
                        DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.keyAt(i),
                        DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES.valueAt(i));
            }
            for (int i = 0;
                    i < DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS.size();
                    ++i) {
                FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS.put(
                        DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS
                                .keyAt(i),
                        DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS
                                .valueAt(i));
            }
            for (int i = 0; i < DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS.size(); ++i) {
                PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS.put(
                        DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS.keyAt(i),
                        DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS.valueAt(i));
            }
        }

        @GuardedBy("mLock")
        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            // TODO(257322915): add appropriate minimums and maximums to constants when parsing
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
                            registerBroadcastReceiver();
                        } else {
                            mFlexibilityEnabled = false;
                            mPrefetchController
                                    .unRegisterPrefetchChangedListener(mPrefetchChangedListener);
                            unregisterBroadcastReceiver();
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
                    }
                    break;
                case KEY_FALLBACK_FLEXIBILITY_DEADLINES:
                    if (parsePriorityToLongKeyValueString(
                            properties.getString(key, null),
                            FALLBACK_FLEXIBILITY_DEADLINES,
                            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINES)) {
                        mFallbackFlexibilityDeadlines = FALLBACK_FLEXIBILITY_DEADLINES;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_FALLBACK_FLEXIBILITY_DEADLINE_SCORES:
                    if (parsePriorityToIntKeyValueString(
                            properties.getString(key, null),
                            FALLBACK_FLEXIBILITY_DEADLINE_SCORES,
                            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_SCORES)) {
                        mFallbackFlexibilityDeadlineScores = FALLBACK_FLEXIBILITY_DEADLINE_SCORES;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
                case KEY_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS:
                    if (parsePriorityToLongKeyValueString(
                            properties.getString(key, null),
                            FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS,
                            DEFAULT_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS)) {
                        mFallbackFlexibilityAdditionalScoreTimeFactors =
                                FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS;
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
                case KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS:
                    if (parsePercentToDropKeyValueString(
                            properties.getString(key, null),
                            PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                            DEFAULT_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS)) {
                        mPercentsToDropConstraints = PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS;
                        mShouldReevaluateConstraints = true;
                    }
                    break;
            }
        }

        private boolean parsePercentToDropKeyValueString(@Nullable String s,
                SparseArray<int[]> into, SparseArray<int[]> defaults) {
            final KeyValueListParser priorityParser = new KeyValueListParser(',');
            try {
                priorityParser.setString(s);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad percent to drop key value string given", e);
                // Clear the string and continue with the defaults.
                priorityParser.setString(null);
            }

            final int[] oldMax = into.get(PRIORITY_MAX);
            final int[] oldHigh = into.get(PRIORITY_HIGH);
            final int[] oldDefault = into.get(PRIORITY_DEFAULT);
            final int[] oldLow = into.get(PRIORITY_LOW);
            final int[] oldMin = into.get(PRIORITY_MIN);

            final int[] newMax = parsePercentToDropString(priorityParser.getString(
                    String.valueOf(PRIORITY_MAX), null));
            final int[] newHigh = parsePercentToDropString(priorityParser.getString(
                    String.valueOf(PRIORITY_HIGH), null));
            final int[] newDefault = parsePercentToDropString(priorityParser.getString(
                    String.valueOf(PRIORITY_DEFAULT), null));
            final int[] newLow = parsePercentToDropString(priorityParser.getString(
                    String.valueOf(PRIORITY_LOW), null));
            final int[] newMin = parsePercentToDropString(priorityParser.getString(
                    String.valueOf(PRIORITY_MIN), null));

            into.put(PRIORITY_MAX, newMax == null ? defaults.get(PRIORITY_MAX) : newMax);
            into.put(PRIORITY_HIGH, newHigh == null ? defaults.get(PRIORITY_HIGH) : newHigh);
            into.put(PRIORITY_DEFAULT,
                    newDefault == null ? defaults.get(PRIORITY_DEFAULT) : newDefault);
            into.put(PRIORITY_LOW, newLow == null ? defaults.get(PRIORITY_LOW) : newLow);
            into.put(PRIORITY_MIN, newMin == null ? defaults.get(PRIORITY_MIN) : newMin);

            return !Arrays.equals(oldMax, into.get(PRIORITY_MAX))
                    || !Arrays.equals(oldHigh, into.get(PRIORITY_HIGH))
                    || !Arrays.equals(oldDefault, into.get(PRIORITY_DEFAULT))
                    || !Arrays.equals(oldLow, into.get(PRIORITY_LOW))
                    || !Arrays.equals(oldMin, into.get(PRIORITY_MIN));
        }

        @Nullable
        private int[] parsePercentToDropString(@Nullable String s) {
            if (s == null || s.isEmpty()) {
                return null;
            }
            final String[] dropPercentString = s.split("\\|");
            int[] dropPercentInt = new int[Integer.bitCount(FLEXIBLE_CONSTRAINTS)];
            if (dropPercentInt.length != dropPercentString.length) {
                return null;
            }
            int prevPercent = 0;
            for (int i = 0; i < dropPercentString.length; i++) {
                try {
                    dropPercentInt[i] =
                            Integer.parseInt(dropPercentString[i]);
                } catch (NumberFormatException ex) {
                    Slog.e(TAG, "Provided string was improperly formatted.", ex);
                    return null;
                }
                if (dropPercentInt[i] < prevPercent) {
                    Slog.wtf(TAG, "Percents to drop constraints were not in increasing order.");
                    return null;
                }
                if (dropPercentInt[i] > 100) {
                    Slog.e(TAG, "Found % over 100");
                    return null;
                }
                prevPercent = dropPercentInt[i];
            }

            return dropPercentInt;
        }

        /**
         * Parses the input string, expecting it to a key-value string where the keys are job
         * priorities, and replaces everything in {@code into} with the values from the string,
         * or the default values if the string contains none.
         *
         * Returns true if any values changed.
         */
        private boolean parsePriorityToIntKeyValueString(@Nullable String s,
                SparseIntArray into, SparseIntArray defaults) {
            final KeyValueListParser parser = new KeyValueListParser(',');
            try {
                parser.setString(s);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad string given", e);
                // Clear the string and continue with the defaults.
                parser.setString(null);
            }

            final int oldMax = into.get(PRIORITY_MAX);
            final int oldHigh = into.get(PRIORITY_HIGH);
            final int oldDefault = into.get(PRIORITY_DEFAULT);
            final int oldLow = into.get(PRIORITY_LOW);
            final int oldMin = into.get(PRIORITY_MIN);

            final int newMax = parser.getInt(String.valueOf(PRIORITY_MAX),
                    defaults.get(PRIORITY_MAX));
            final int newHigh = parser.getInt(String.valueOf(PRIORITY_HIGH),
                    defaults.get(PRIORITY_HIGH));
            final int newDefault = parser.getInt(String.valueOf(PRIORITY_DEFAULT),
                    defaults.get(PRIORITY_DEFAULT));
            final int newLow = parser.getInt(String.valueOf(PRIORITY_LOW),
                    defaults.get(PRIORITY_LOW));
            final int newMin = parser.getInt(String.valueOf(PRIORITY_MIN),
                    defaults.get(PRIORITY_MIN));

            into.put(PRIORITY_MAX, newMax);
            into.put(PRIORITY_HIGH, newHigh);
            into.put(PRIORITY_DEFAULT, newDefault);
            into.put(PRIORITY_LOW, newLow);
            into.put(PRIORITY_MIN, newMin);

            return oldMax != newMax
                    || oldHigh != newHigh
                    || oldDefault != newDefault
                    || oldLow != newLow
                    || oldMin != newMin;
        }

        /**
         * Parses the input string, expecting it to a key-value string where the keys are job
         * priorities, and replaces everything in {@code into} with the values from the string,
         * or the default values if the string contains none.
         *
         * Returns true if any values changed.
         */
        private boolean parsePriorityToLongKeyValueString(@Nullable String s,
                SparseLongArray into, SparseLongArray defaults) {
            final KeyValueListParser parser = new KeyValueListParser(',');
            try {
                parser.setString(s);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad string given", e);
                // Clear the string and continue with the defaults.
                parser.setString(null);
            }

            final long oldMax = into.get(PRIORITY_MAX);
            final long oldHigh = into.get(PRIORITY_HIGH);
            final long oldDefault = into.get(PRIORITY_DEFAULT);
            final long oldLow = into.get(PRIORITY_LOW);
            final long oldMin = into.get(PRIORITY_MIN);

            final long newMax = parser.getLong(String.valueOf(PRIORITY_MAX),
                    defaults.get(PRIORITY_MAX));
            final long newHigh = parser.getLong(String.valueOf(PRIORITY_HIGH),
                    defaults.get(PRIORITY_HIGH));
            final long newDefault = parser.getLong(String.valueOf(PRIORITY_DEFAULT),
                    defaults.get(PRIORITY_DEFAULT));
            final long newLow = parser.getLong(String.valueOf(PRIORITY_LOW),
                    defaults.get(PRIORITY_LOW));
            final long newMin = parser.getLong(String.valueOf(PRIORITY_MIN),
                    defaults.get(PRIORITY_MIN));

            into.put(PRIORITY_MAX, newMax);
            into.put(PRIORITY_HIGH, newHigh);
            into.put(PRIORITY_DEFAULT, newDefault);
            into.put(PRIORITY_LOW, newLow);
            into.put(PRIORITY_MIN, newMin);

            return oldMax != newMax
                    || oldHigh != newHigh
                    || oldDefault != newDefault
                    || oldLow != newLow
                    || oldMin != newMin;
        }

        private void dump(IndentingPrintWriter pw) {
            pw.println();
            pw.print(FlexibilityController.class.getSimpleName());
            pw.println(":");
            pw.increaseIndent();

            pw.print(KEY_APPLIED_CONSTRAINTS, APPLIED_CONSTRAINTS);
            pw.print("(");
            if (APPLIED_CONSTRAINTS != 0) {
                JobStatus.dumpConstraints(pw, APPLIED_CONSTRAINTS);
            } else {
                pw.print("nothing");
            }
            pw.println(")");
            pw.print(KEY_DEADLINE_PROXIMITY_LIMIT, DEADLINE_PROXIMITY_LIMIT_MS).println();
            pw.print(KEY_FALLBACK_FLEXIBILITY_DEADLINE, FALLBACK_FLEXIBILITY_DEADLINE_MS).println();
            pw.print(KEY_FALLBACK_FLEXIBILITY_DEADLINES, FALLBACK_FLEXIBILITY_DEADLINES).println();
            pw.print(KEY_FALLBACK_FLEXIBILITY_DEADLINE_SCORES,
                    FALLBACK_FLEXIBILITY_DEADLINE_SCORES).println();
            pw.print(KEY_FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS,
                    FALLBACK_FLEXIBILITY_DEADLINE_ADDITIONAL_SCORE_TIME_FACTORS).println();
            pw.print(KEY_MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS,
                    MIN_TIME_BETWEEN_FLEXIBILITY_ALARMS_MS).println();
            pw.print(KEY_PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS,
                    PERCENTS_TO_DROP_FLEXIBLE_CONSTRAINTS).println();
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
        pw.print("Power allowlisted packages: ");
        pw.println(mPowerAllowlistedApps);

        pw.println();
        mFlexibilityTracker.dump(pw, predicate, nowElapsed);

        pw.println();
        pw.println("Job scores:");
        pw.increaseIndent();
        mJobScoreTrackers.forEach((uid, pkgName, jobScoreTracker) -> {
            pw.print(uid);
            pw.print("/");
            pw.print(pkgName);
            pw.print(": ");
            jobScoreTracker.dump(pw, nowElapsed);
            pw.println();
        });
        pw.decreaseIndent();

        pw.println();
        mFlexibilityAlarmQueue.dump(pw);
    }
}
