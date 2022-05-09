/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.job.JobInfo.getPriorityString;

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.NonNull;
import android.app.job.JobInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.tare.EconomicPolicy;
import com.android.server.tare.EconomyManagerInternal;
import com.android.server.tare.EconomyManagerInternal.ActionBill;
import com.android.server.tare.JobSchedulerEconomicPolicy;

import java.util.List;
import java.util.function.Predicate;

/**
 * Controller that interfaces with Tare ({@link EconomyManagerInternal} and manages each job's
 * ability to run per TARE policies.
 *
 * @see JobSchedulerEconomicPolicy
 */
public class TareController extends StateController {
    private static final String TAG = "JobScheduler.TARE";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Bill to use while we're waiting to start a min priority job. If a job isn't running yet,
     * don't consider it eligible to run unless it can pay for a job start and at least some
     * period of execution time. We don't want min priority jobs to use up all available credits,
     * so we make sure to only run them while there are enough credits to run higher priority jobs.
     */
    private static final ActionBill BILL_JOB_START_MIN =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, 0, 120_000L)
            ));

    /**
     * Bill to use when a min priority job is currently running. We don't want min priority jobs
     * to use up remaining credits, so we make sure to only run them while there are enough
     * credits to run higher priority jobs. We stop the job when the app's credits get too low.
     */
    private static final ActionBill BILL_JOB_RUNNING_MIN =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, 0, 60_000L)
            ));

    /**
     * Bill to use while we're waiting to start a low priority job. If a job isn't running yet,
     * don't consider it eligible to run unless it can pay for a job start and at least some
     * period of execution time. We don't want low priority jobs to use up all available credits,
     * so we make sure to only run them while there are enough credits to run higher priority jobs.
     */
    private static final ActionBill BILL_JOB_START_LOW =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, 0, 60_000L)
            ));

    /**
     * Bill to use when a low priority job is currently running. We don't want low priority jobs
     * to use up all available credits, so we make sure to only run them while there are enough
     * credits to run higher priority jobs. We stop the job when the app's credits get too low.
     */
    private static final ActionBill BILL_JOB_RUNNING_LOW =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, 0, 30_000L)
            ));

    /**
     * Bill to use while we're waiting to start a job. If a job isn't running yet, don't consider it
     * eligible to run unless it can pay for a job start and at least some period of execution time.
     */
    private static final ActionBill BILL_JOB_START_DEFAULT =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, 0, 30_000L)
            ));

    /**
     * Bill to use when a default priority job is currently running. We want to track and make
     * sure the app can continue to pay for 1 more second of execution time. We stop the job when
     * the app can no longer pay for that time.
     */
    private static final ActionBill BILL_JOB_RUNNING_DEFAULT =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, 0, 1_000L)
            ));

    /**
     * Bill to use while we're waiting to start a job. If a job isn't running yet, don't consider it
     * eligible to run unless it can pay for a job start and at least some period of execution time.
     */
    private static final ActionBill BILL_JOB_START_HIGH =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, 0, 30_000L)
            ));

    /**
     * Bill to use when a high priority job is currently running. We want to track and make sure
     * the app can continue to pay for 1 more second of execution time. We stop the job when the
     * app can no longer pay for that time.
     */
    private static final ActionBill BILL_JOB_RUNNING_HIGH =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, 0, 1_000L)
            ));


    /**
     * Bill to use while we're waiting to start a max priority job. This should only be used for
     * requested-EJs that aren't allowed to run as EJs. If a job isn't running yet, don't consider
     * it eligible to run unless it can pay for a job start and at least some period of execution
     * time.
     */
    private static final ActionBill BILL_JOB_START_MAX =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 0, 30_000L)
            ));

    /**
     * Bill to use when a max priority job is currently running. This should only be used for
     * requested-EJs that aren't allowed to run as EJs. We want to track and make sure
     * the app can continue to pay for 1 more second of execution time. We stop the job when the
     * app can no longer pay for that time.
     */
    private static final ActionBill BILL_JOB_RUNNING_MAX =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 0, 1_000L)
            ));

    /**
     * Bill to use while we're waiting to start a job. If a job isn't running yet, don't consider it
     * eligible to run unless it can pay for a job start and at least some period of execution time.
     */
    private static final ActionBill BILL_JOB_START_MAX_EXPEDITED =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 0, 30_000L)
            ));

    /**
     * Bill to use when a max priority EJ is currently running (as an EJ). We want to track and
     * make sure the app can continue to pay for 1 more second of execution time. We stop the job
     * when the app can no longer pay for that time.
     */
    private static final ActionBill BILL_JOB_RUNNING_MAX_EXPEDITED =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 0, 1_000L)
            ));

    /**
     * Bill to use while we're waiting to start a job. If a job isn't running yet, don't consider it
     * eligible to run unless it can pay for a job start and at least some period of execution time.
     */
    private static final ActionBill BILL_JOB_START_HIGH_EXPEDITED =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, 0, 30_000L)
            ));

    /**
     * Bill to use when a high priority EJ is currently running (as an EJ). We want to track and
     * make sure the app can continue to pay for 1 more second of execution time. We stop the job
     * when the app can no longer pay for that time.
     */
    private static final ActionBill BILL_JOB_RUNNING_HIGH_EXPEDITED =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, 0, 1_000L)
            ));

    private final EconomyManagerInternal mEconomyManagerInternal;

    private final BackgroundJobsController mBackgroundJobsController;
    private final ConnectivityController mConnectivityController;

    /**
     * Local cache of the ability of each userId-pkg to afford the various bills we're tracking for
     * them.
     */
    @GuardedBy("mLock")
    private final SparseArrayMap<String, ArrayMap<ActionBill, Boolean>> mAffordabilityCache =
            new SparseArrayMap<>();

    /**
     * List of all tracked jobs. Out SparseArrayMap is userId-sourcePkg. The inner mapping is the
     * anticipated actions and all the jobs that are applicable to them.
     */
    @GuardedBy("mLock")
    private final SparseArrayMap<String, ArrayMap<ActionBill, ArraySet<JobStatus>>>
            mRegisteredBillsAndJobs = new SparseArrayMap<>();

    private final EconomyManagerInternal.AffordabilityChangeListener mAffordabilityChangeListener =
            (userId, pkgName, bill, canAfford) -> {
                final long nowElapsed = sElapsedRealtimeClock.millis();
                if (DEBUG) {
                    Slog.d(TAG,
                            userId + ":" + pkgName + " affordability for " + getBillName(bill)
                                    + " changed to " + canAfford);
                }
                synchronized (mLock) {
                    ArrayMap<ActionBill, Boolean> actionAffordability =
                            mAffordabilityCache.get(userId, pkgName);
                    if (actionAffordability == null) {
                        actionAffordability = new ArrayMap<>();
                        mAffordabilityCache.add(userId, pkgName, actionAffordability);
                    }
                    actionAffordability.put(bill, canAfford);

                    final ArrayMap<ActionBill, ArraySet<JobStatus>> billToJobMap =
                            mRegisteredBillsAndJobs.get(userId, pkgName);
                    if (billToJobMap != null) {
                        final ArraySet<JobStatus> jobs = billToJobMap.get(bill);
                        if (jobs != null) {
                            final ArraySet<JobStatus> changedJobs = new ArraySet<>();
                            for (int i = 0; i < jobs.size(); ++i) {
                                final JobStatus job = jobs.valueAt(i);
                                // Use hasEnoughWealth if canAfford is false in case the job has
                                // other bills it can depend on (eg. EJs being demoted to
                                // regular jobs).
                                if (job.setTareWealthConstraintSatisfied(nowElapsed,
                                        canAfford || hasEnoughWealthLocked(job))) {
                                    changedJobs.add(job);
                                }
                                if (job.isRequestedExpeditedJob()
                                        && setExpeditedTareApproved(job, nowElapsed,
                                        canAffordExpeditedBillLocked(job))) {
                                    changedJobs.add(job);
                                }
                            }
                            if (changedJobs.size() > 0) {
                                mStateChangedListener.onControllerStateChanged(changedJobs);
                            }
                        }
                    }
                }
            };

    /**
     * List of jobs that started while the UID was in the TOP state. There will usually be no more
     * than {@value JobConcurrencyManager#MAX_STANDARD_JOB_CONCURRENCY} running at once, so an
     * ArraySet is fine.
     */
    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mTopStartedJobs = new ArraySet<>();

    @GuardedBy("mLock")
    private boolean mIsEnabled;

    public TareController(JobSchedulerService service,
            @NonNull BackgroundJobsController backgroundJobsController,
            @NonNull ConnectivityController connectivityController) {
        super(service);
        mBackgroundJobsController = backgroundJobsController;
        mConnectivityController = connectivityController;
        mEconomyManagerInternal = LocalServices.getService(EconomyManagerInternal.class);
        mIsEnabled = mConstants.USE_TARE_POLICY;
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        jobStatus.setTareWealthConstraintSatisfied(nowElapsed, hasEnoughWealthLocked(jobStatus));
        setExpeditedTareApproved(jobStatus, nowElapsed,
                jobStatus.isRequestedExpeditedJob() && canAffordExpeditedBillLocked(jobStatus));

        final ArraySet<ActionBill> bills = getPossibleStartBills(jobStatus);
        for (int i = 0; i < bills.size(); ++i) {
            addJobToBillList(jobStatus, bills.valueAt(i));
        }
    }

    @Override
    @GuardedBy("mLock")
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        ArrayMap<ActionBill, ArraySet<JobStatus>> billToJobMap =
                mRegisteredBillsAndJobs.get(userId, pkgName);
        if (billToJobMap == null) {
            Slog.e(TAG, "Job is being prepared but doesn't have a pre-existing billToJobMap");
        } else {
            for (int i = 0; i < billToJobMap.size(); ++i) {
                removeJobFromBillList(jobStatus, billToJobMap.keyAt(i));
            }
        }
        addJobToBillList(jobStatus, getRunningBill(jobStatus));

        final int uid = jobStatus.getSourceUid();
        if (mService.getUidBias(uid) == JobInfo.BIAS_TOP_APP) {
            if (DEBUG) {
                Slog.d(TAG, jobStatus.toShortString() + " is top started job");
            }
            mTopStartedJobs.add(jobStatus);
            // Top jobs won't count towards quota so there's no need to involve the EconomyManager.
        } else {
            mEconomyManagerInternal.noteOngoingEventStarted(userId, pkgName,
                    getRunningActionId(jobStatus), String.valueOf(jobStatus.getJobId()));
        }
    }

    @Override
    @GuardedBy("mLock")
    public void unprepareFromExecutionLocked(JobStatus jobStatus) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        mEconomyManagerInternal.noteOngoingEventStopped(userId, pkgName,
                getRunningActionId(jobStatus), String.valueOf(jobStatus.getJobId()));
        mTopStartedJobs.remove(jobStatus);

        final ArraySet<ActionBill> bills = getPossibleStartBills(jobStatus);
        ArrayMap<ActionBill, ArraySet<JobStatus>> billToJobMap =
                mRegisteredBillsAndJobs.get(userId, pkgName);
        if (billToJobMap == null) {
            Slog.e(TAG, "Job was just unprepared but didn't have a pre-existing billToJobMap");
        } else {
            for (int i = 0; i < billToJobMap.size(); ++i) {
                removeJobFromBillList(jobStatus, billToJobMap.keyAt(i));
            }
        }
        for (int i = 0; i < bills.size(); ++i) {
            addJobToBillList(jobStatus, bills.valueAt(i));
        }
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        mEconomyManagerInternal.noteOngoingEventStopped(userId, pkgName,
                getRunningActionId(jobStatus), String.valueOf(jobStatus.getJobId()));
        mTopStartedJobs.remove(jobStatus);
        ArrayMap<ActionBill, ArraySet<JobStatus>> billToJobMap =
                mRegisteredBillsAndJobs.get(userId, pkgName);
        if (billToJobMap != null) {
            for (int i = 0; i < billToJobMap.size(); ++i) {
                removeJobFromBillList(jobStatus, billToJobMap.keyAt(i));
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onConstantsUpdatedLocked() {
        if (mIsEnabled != mConstants.USE_TARE_POLICY) {
            mIsEnabled = mConstants.USE_TARE_POLICY;
            // Update job bookkeeping out of band.
            JobSchedulerBackgroundThread.getHandler().post(() -> {
                synchronized (mLock) {
                    final long nowElapsed = sElapsedRealtimeClock.millis();
                    mService.getJobStore().forEachJob((jobStatus) -> {
                        if (!mIsEnabled) {
                            jobStatus.setTareWealthConstraintSatisfied(nowElapsed, true);
                            setExpeditedTareApproved(jobStatus, nowElapsed, true);
                        } else {
                            jobStatus.setTareWealthConstraintSatisfied(
                                    nowElapsed, hasEnoughWealthLocked(jobStatus));
                            setExpeditedTareApproved(jobStatus, nowElapsed,
                                    jobStatus.isRequestedExpeditedJob()
                                            && canAffordExpeditedBillLocked(jobStatus));
                        }
                    });
                }
            });
        }
    }

    @GuardedBy("mLock")
    public boolean canScheduleEJ(@NonNull JobStatus jobStatus) {
        if (!mIsEnabled) {
            return true;
        }
        if (jobStatus.getEffectivePriority() == JobInfo.PRIORITY_MAX) {
            return canAffordBillLocked(jobStatus, BILL_JOB_START_MAX_EXPEDITED);
        }
        return canAffordBillLocked(jobStatus, BILL_JOB_START_HIGH_EXPEDITED);
    }

    /** @return true if the job was started while the app was in the TOP state. */
    @GuardedBy("mLock")
    private boolean isTopStartedJobLocked(@NonNull final JobStatus jobStatus) {
        return mTopStartedJobs.contains(jobStatus);
    }

    @GuardedBy("mLock")
    public long getMaxJobExecutionTimeMsLocked(@NonNull JobStatus jobStatus) {
        if (!mIsEnabled) {
            return mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;
        }
        return mEconomyManagerInternal.getMaxDurationMs(
                jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(),
                getRunningBill(jobStatus));
    }

    @GuardedBy("mLock")
    private void addJobToBillList(@NonNull JobStatus jobStatus, @NonNull ActionBill bill) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        ArrayMap<ActionBill, ArraySet<JobStatus>> billToJobMap =
                mRegisteredBillsAndJobs.get(userId, pkgName);
        if (billToJobMap == null) {
            billToJobMap = new ArrayMap<>();
            mRegisteredBillsAndJobs.add(userId, pkgName, billToJobMap);
        }
        ArraySet<JobStatus> jobs = billToJobMap.get(bill);
        if (jobs == null) {
            jobs = new ArraySet<>();
            billToJobMap.put(bill, jobs);
        }
        if (jobs.add(jobStatus)) {
            mEconomyManagerInternal.registerAffordabilityChangeListener(userId, pkgName,
                    mAffordabilityChangeListener, bill);
        }
    }

    @GuardedBy("mLock")
    private void removeJobFromBillList(@NonNull JobStatus jobStatus, @NonNull ActionBill bill) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        final ArrayMap<ActionBill, ArraySet<JobStatus>> billToJobMap =
                mRegisteredBillsAndJobs.get(userId, pkgName);
        if (billToJobMap != null) {
            final ArraySet<JobStatus> jobs = billToJobMap.get(bill);
            if (jobs == null || (jobs.remove(jobStatus) && jobs.size() == 0)) {
                mEconomyManagerInternal.unregisterAffordabilityChangeListener(
                        userId, pkgName, mAffordabilityChangeListener, bill);
                // Remove the cached value so we don't accidentally use it when the app
                // schedules a new job.
                final ArrayMap<ActionBill, Boolean> actionAffordability =
                        mAffordabilityCache.get(userId, pkgName);
                if (actionAffordability != null) {
                    actionAffordability.remove(bill);
                }
            }
        }
    }

    @NonNull
    private ArraySet<ActionBill> getPossibleStartBills(JobStatus jobStatus) {
        // TODO: factor in network cost when available
        final ArraySet<ActionBill> bills = new ArraySet<>();
        if (jobStatus.isRequestedExpeditedJob()) {
            if (jobStatus.getEffectivePriority() == JobInfo.PRIORITY_MAX) {
                bills.add(BILL_JOB_START_MAX_EXPEDITED);
            } else {
                bills.add(BILL_JOB_START_HIGH_EXPEDITED);
            }
        }
        switch (jobStatus.getEffectivePriority()) {
            case JobInfo.PRIORITY_MAX:
                bills.add(BILL_JOB_START_MAX);
                break;
            case JobInfo.PRIORITY_HIGH:
                bills.add(BILL_JOB_START_HIGH);
                break;
            case JobInfo.PRIORITY_DEFAULT:
                bills.add(BILL_JOB_START_DEFAULT);
                break;
            case JobInfo.PRIORITY_LOW:
                bills.add(BILL_JOB_START_LOW);
                break;
            case JobInfo.PRIORITY_MIN:
                bills.add(BILL_JOB_START_MIN);
                break;
            default:
                Slog.wtf(TAG, "Unexpected priority: "
                        + JobInfo.getPriorityString(jobStatus.getEffectivePriority()));
                break;
        }
        return bills;
    }

    @NonNull
    private ActionBill getRunningBill(JobStatus jobStatus) {
        // TODO: factor in network cost when available
        if (jobStatus.shouldTreatAsExpeditedJob() || jobStatus.startedAsExpeditedJob) {
            if (jobStatus.getEffectivePriority() == JobInfo.PRIORITY_MAX) {
                return BILL_JOB_RUNNING_MAX_EXPEDITED;
            } else {
                return BILL_JOB_RUNNING_HIGH_EXPEDITED;
            }
        }
        switch (jobStatus.getEffectivePriority()) {
            case JobInfo.PRIORITY_MAX:
                return BILL_JOB_RUNNING_MAX;
            case JobInfo.PRIORITY_HIGH:
                return BILL_JOB_RUNNING_HIGH;
            case JobInfo.PRIORITY_LOW:
                return BILL_JOB_RUNNING_LOW;
            case JobInfo.PRIORITY_MIN:
                return BILL_JOB_RUNNING_MIN;
            default:
                Slog.wtf(TAG, "Got unexpected priority: " + jobStatus.getEffectivePriority());
                // Intentional fallthrough
            case JobInfo.PRIORITY_DEFAULT:
                return BILL_JOB_RUNNING_DEFAULT;
        }
    }

    @EconomicPolicy.AppAction
    private static int getRunningActionId(@NonNull JobStatus job) {
        switch (job.getEffectivePriority()) {
            case JobInfo.PRIORITY_MAX:
                return JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING;
            case JobInfo.PRIORITY_HIGH:
                return JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING;
            case JobInfo.PRIORITY_LOW:
                return JobSchedulerEconomicPolicy.ACTION_JOB_LOW_RUNNING;
            case JobInfo.PRIORITY_MIN:
                return JobSchedulerEconomicPolicy.ACTION_JOB_MIN_RUNNING;
            default:
                Slog.wtf(TAG, "Unknown priority: " + getPriorityString(job.getEffectivePriority()));
                // Intentional fallthrough
            case JobInfo.PRIORITY_DEFAULT:
                return JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING;
        }
    }

    @GuardedBy("mLock")
    private boolean canAffordBillLocked(@NonNull JobStatus jobStatus, @NonNull ActionBill bill) {
        if (!mIsEnabled) {
            return true;
        }
        if (mService.getUidBias(jobStatus.getSourceUid()) == JobInfo.BIAS_TOP_APP
                || isTopStartedJobLocked(jobStatus)) {
            // Jobs for the top app should always be allowed to run, and any jobs started while
            // the app is on top shouldn't consume any credits.
            return true;
        }
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
        ArrayMap<ActionBill, Boolean> actionAffordability =
                mAffordabilityCache.get(userId, pkgName);
        if (actionAffordability == null) {
            actionAffordability = new ArrayMap<>();
            mAffordabilityCache.add(userId, pkgName, actionAffordability);
        }

        if (actionAffordability.containsKey(bill)) {
            return actionAffordability.get(bill);
        }

        final boolean canAfford = mEconomyManagerInternal.canPayFor(userId, pkgName, bill);
        actionAffordability.put(bill, canAfford);
        return canAfford;
    }

    @GuardedBy("mLock")
    private boolean canAffordExpeditedBillLocked(@NonNull JobStatus jobStatus) {
        if (!mIsEnabled) {
            return true;
        }
        if (!jobStatus.isRequestedExpeditedJob()) {
            return false;
        }
        if (mService.getUidBias(jobStatus.getSourceUid()) == JobInfo.BIAS_TOP_APP
                || isTopStartedJobLocked(jobStatus)) {
            // Jobs for the top app should always be allowed to run, and any jobs started while
            // the app is on top shouldn't consume any credits.
            return true;
        }
        if (mService.isCurrentlyRunningLocked(jobStatus)) {
            return canAffordBillLocked(jobStatus, getRunningBill(jobStatus));
        }

        if (jobStatus.getEffectivePriority() == JobInfo.PRIORITY_MAX) {
            return canAffordBillLocked(jobStatus, BILL_JOB_START_MAX_EXPEDITED);
        }
        return canAffordBillLocked(jobStatus, BILL_JOB_START_HIGH_EXPEDITED);
    }

    @GuardedBy("mLock")
    private boolean hasEnoughWealthLocked(@NonNull JobStatus jobStatus) {
        if (!mIsEnabled) {
            return true;
        }
        if (mService.getUidBias(jobStatus.getSourceUid()) == JobInfo.BIAS_TOP_APP
                || isTopStartedJobLocked(jobStatus)) {
            // Jobs for the top app should always be allowed to run, and any jobs started while
            // the app is on top shouldn't consume any credits.
            return true;
        }
        if (mService.isCurrentlyRunningLocked(jobStatus)) {
            return canAffordBillLocked(jobStatus, getRunningBill(jobStatus));
        }

        final ArraySet<ActionBill> bills = getPossibleStartBills(jobStatus);
        for (int i = 0; i < bills.size(); ++i) {
            ActionBill bill = bills.valueAt(i);
            if (canAffordBillLocked(jobStatus, bill)) {
                return true;
            }
        }
        return false;
    }

    /**
     * If the satisfaction changes, this will tell connectivity & background jobs controller to
     * also re-evaluate their state.
     */
    private boolean setExpeditedTareApproved(@NonNull JobStatus jobStatus, long nowElapsed,
            boolean isApproved) {
        if (jobStatus.setExpeditedJobTareApproved(nowElapsed, isApproved)) {
            mBackgroundJobsController.evaluateStateLocked(jobStatus);
            mConnectivityController.evaluateStateLocked(jobStatus);
            if (isApproved && jobStatus.isReady()) {
                mStateChangedListener.onRunJobNow(jobStatus);
            }
            return true;
        }
        return false;
    }

    @NonNull
    private String getBillName(@NonNull ActionBill bill) {
        if (bill.equals(BILL_JOB_START_MAX_EXPEDITED)) {
            return "EJ_MAX_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_MAX_EXPEDITED)) {
            return "EJ_MAX_RUNNING_BILL";
        }
        if (bill.equals(BILL_JOB_START_HIGH_EXPEDITED)) {
            return "EJ_HIGH_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_HIGH_EXPEDITED)) {
            return "EJ_HIGH_RUNNING_BILL";
        }
        if (bill.equals(BILL_JOB_START_HIGH)) {
            return "HIGH_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_HIGH)) {
            return "HIGH_RUNNING_BILL";
        }
        if (bill.equals(BILL_JOB_START_DEFAULT)) {
            return "DEFAULT_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_DEFAULT)) {
            return "DEFAULT_RUNNING_BILL";
        }
        if (bill.equals(BILL_JOB_START_LOW)) {
            return "LOW_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_LOW)) {
            return "LOW_RUNNING_BILL";
        }
        if (bill.equals(BILL_JOB_START_MIN)) {
            return "MIN_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_MIN)) {
            return "MIN_RUNNING_BILL";
        }
        return "UNKNOWN_BILL (" + bill + ")";
    }

    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        pw.print("Is enabled: ");
        pw.println(mIsEnabled);

        pw.println("Affordability cache:");
        pw.increaseIndent();
        mAffordabilityCache.forEach((userId, pkgName, billMap) -> {
            final int numBills = billMap.size();
            if (numBills > 0) {
                pw.print(userId);
                pw.print(":");
                pw.print(pkgName);
                pw.println(":");

                pw.increaseIndent();
                for (int i = 0; i < numBills; ++i) {
                    pw.print(getBillName(billMap.keyAt(i)));
                    pw.print(": ");
                    pw.println(billMap.valueAt(i));
                }
                pw.decreaseIndent();
            }
        });
        pw.decreaseIndent();
    }
}
