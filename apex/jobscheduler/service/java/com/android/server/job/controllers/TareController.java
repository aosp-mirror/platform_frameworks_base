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

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.NonNull;
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
     * Bill to use when a default is currently running. We want to track and make sure the app can
     * continue to pay for 1 more second of execution time. We stop the job when the app can no
     * longer pay for that time.
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
    private static final ActionBill BILL_JOB_START_EXPEDITED =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 1, 0),
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 0, 30_000L)
            ));

    /**
     * Bill to use when an EJ is currently running (as an EJ). We want to track and make sure the
     * app can continue to pay for 1 more second of execution time. We stop the job when the app can
     * no longer pay for that time.
     */
    private static final ActionBill BILL_JOB_RUNNING_EXPEDITED =
            new ActionBill(List.of(
                    new EconomyManagerInternal.AnticipatedAction(
                            JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 0, 1_000L)
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
        if (jobStatus.shouldTreatAsExpeditedJob()) {
            addJobToBillList(jobStatus, BILL_JOB_RUNNING_EXPEDITED);
        }
        addJobToBillList(jobStatus, BILL_JOB_RUNNING_DEFAULT);
    }

    @Override
    public void unprepareFromExecutionLocked(JobStatus jobStatus) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
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
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        final int userId = jobStatus.getSourceUserId();
        final String pkgName = jobStatus.getSourcePackageName();
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
        return canAffordBillLocked(jobStatus, BILL_JOB_START_EXPEDITED);
    }

    @GuardedBy("mLock")
    public long getMaxJobExecutionTimeMsLocked(@NonNull JobStatus jobStatus) {
        if (!mIsEnabled) {
            return mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;
        }
        if (jobStatus.shouldTreatAsExpeditedJob()) {
            return mEconomyManagerInternal.getMaxDurationMs(
                    jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(),
                    BILL_JOB_RUNNING_EXPEDITED);
        }
        return mEconomyManagerInternal.getMaxDurationMs(
                jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(),
                BILL_JOB_RUNNING_DEFAULT);
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
            bills.add(BILL_JOB_START_EXPEDITED);
        }
        bills.add(BILL_JOB_START_DEFAULT);
        return bills;
    }

    @GuardedBy("mLock")
    private boolean canAffordBillLocked(@NonNull JobStatus jobStatus, @NonNull ActionBill bill) {
        if (!mIsEnabled) {
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
        if (mService.isCurrentlyRunningLocked(jobStatus)) {
            return canAffordBillLocked(jobStatus, BILL_JOB_RUNNING_EXPEDITED);
        }

        return canAffordBillLocked(jobStatus, BILL_JOB_START_EXPEDITED);
    }

    @GuardedBy("mLock")
    private boolean hasEnoughWealthLocked(@NonNull JobStatus jobStatus) {
        if (!mIsEnabled) {
            return true;
        }
        if (mService.isCurrentlyRunningLocked(jobStatus)) {
            if (jobStatus.isRequestedExpeditedJob()) {
                return canAffordBillLocked(jobStatus, BILL_JOB_RUNNING_EXPEDITED)
                        || canAffordBillLocked(jobStatus, BILL_JOB_RUNNING_DEFAULT);
            }
            return canAffordBillLocked(jobStatus, BILL_JOB_RUNNING_DEFAULT);
        }

        if (jobStatus.isRequestedExpeditedJob()
                && canAffordBillLocked(jobStatus, BILL_JOB_START_EXPEDITED)) {
            return true;
        }

        return canAffordBillLocked(jobStatus, BILL_JOB_START_DEFAULT);
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
        if (bill.equals(BILL_JOB_START_EXPEDITED)) {
            return "EJ_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_EXPEDITED)) {
            return "EJ_RUNNING_BILL";
        }
        if (bill.equals(BILL_JOB_START_DEFAULT)) {
            return "DEFAULT_START_BILL";
        }
        if (bill.equals(BILL_JOB_RUNNING_DEFAULT)) {
            return "DEFAULT_RUNNING_BILL";
        }
        return "UNKNOWN_BILL (" + bill.toString() + ")";
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
