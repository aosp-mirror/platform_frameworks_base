/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job.controllers;

import android.content.Context;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.server.ForceAppStandbyTracker;
import com.android.server.ForceAppStandbyTracker.Listener;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;
import com.android.server.job.StateControllerProto;
import com.android.server.job.StateControllerProto.BackgroundJobsController.TrackedJob;

import java.io.PrintWriter;

public final class BackgroundJobsController extends StateController {

    private static final String LOG_TAG = "BackgroundJobsController";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    // Singleton factory
    private static final Object sCreationLock = new Object();
    private static volatile BackgroundJobsController sController;

    private final JobSchedulerService mJobSchedulerService;

    private final ForceAppStandbyTracker mForceAppStandbyTracker;


    public static BackgroundJobsController get(JobSchedulerService service) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new BackgroundJobsController(service, service.getContext(),
                        service.getLock());
            }
            return sController;
        }
    }

    private BackgroundJobsController(JobSchedulerService service, Context context, Object lock) {
        super(service, context, lock);
        mJobSchedulerService = service;

        mForceAppStandbyTracker = ForceAppStandbyTracker.getInstance(context);

        mForceAppStandbyTracker.addListener(mForceAppStandbyListener);
        mForceAppStandbyTracker.start();
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        updateSingleJobRestrictionLocked(jobStatus);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
    }

    @Override
    public void dumpControllerStateLocked(final PrintWriter pw, final int filterUid) {
        pw.println("BackgroundJobsController");

        mForceAppStandbyTracker.dump(pw, "");

        pw.println("Job state:");
        mJobSchedulerService.getJobStore().forEachJob((jobStatus) -> {
            if (!jobStatus.shouldDump(filterUid)) {
                return;
            }
            final int uid = jobStatus.getSourceUid();
            final String sourcePkg = jobStatus.getSourcePackageName();
            pw.print("  #");
            jobStatus.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, uid);
            pw.print(mForceAppStandbyTracker.isUidActive(uid) ? " active" : " idle");
            if (mForceAppStandbyTracker.isUidPowerSaveWhitelisted(uid) ||
                    mForceAppStandbyTracker.isUidTempPowerSaveWhitelisted(uid)) {
                pw.print(", whitelisted");
            }
            pw.print(": ");
            pw.print(sourcePkg);

            pw.print(" [RUN_ANY_IN_BACKGROUND ");
            pw.print(mForceAppStandbyTracker.isRunAnyInBackgroundAppOpsAllowed(uid, sourcePkg)
                    ? "allowed]" : "disallowed]");

            if ((jobStatus.satisfiedConstraints
                    & JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0) {
                pw.println(" RUNNABLE");
            } else {
                pw.println(" WAITING");
            }
        });
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId, int filterUid) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.BACKGROUND);

        mForceAppStandbyTracker.dumpProto(proto,
                StateControllerProto.BackgroundJobsController.FORCE_APP_STANDBY_TRACKER);

        mJobSchedulerService.getJobStore().forEachJob((jobStatus) -> {
            if (!jobStatus.shouldDump(filterUid)) {
                return;
            }
            final long jsToken =
                    proto.start(StateControllerProto.BackgroundJobsController.TRACKED_JOBS);

            jobStatus.writeToShortProto(proto,
                    TrackedJob.INFO);
            final int sourceUid = jobStatus.getSourceUid();
            proto.write(TrackedJob.SOURCE_UID, sourceUid);
            final String sourcePkg = jobStatus.getSourcePackageName();
            proto.write(TrackedJob.SOURCE_PACKAGE_NAME, sourcePkg);

            proto.write(TrackedJob.IS_IN_FOREGROUND,
                    mForceAppStandbyTracker.isUidActive(sourceUid));
            proto.write(TrackedJob.IS_WHITELISTED,
                    mForceAppStandbyTracker.isUidPowerSaveWhitelisted(sourceUid) ||
                    mForceAppStandbyTracker.isUidTempPowerSaveWhitelisted(sourceUid));

            proto.write(
                    TrackedJob.CAN_RUN_ANY_IN_BACKGROUND,
                    mForceAppStandbyTracker.isRunAnyInBackgroundAppOpsAllowed(
                            sourceUid, sourcePkg));

            proto.write(
                    TrackedJob.ARE_CONSTRAINTS_SATISFIED,
                    (jobStatus.satisfiedConstraints &
                            JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0);

            proto.end(jsToken);
        });

        proto.end(mToken);
        proto.end(token);
    }

    private void updateAllJobRestrictionsLocked() {
        updateJobRestrictionsLocked(/*filterUid=*/ -1);
    }

    private void updateJobRestrictionsForUidLocked(int uid) {

        // TODO Use forEachJobForSourceUid() once we have it.

        updateJobRestrictionsLocked(/*filterUid=*/ uid);
    }

    private void updateJobRestrictionsLocked(int filterUid) {
        final UpdateJobFunctor updateTrackedJobs =
                new UpdateJobFunctor(filterUid);

        final long start = DEBUG ? SystemClock.elapsedRealtimeNanos() : 0;

        mJobSchedulerService.getJobStore().forEachJob(updateTrackedJobs);

        final long time = DEBUG ? (SystemClock.elapsedRealtimeNanos() - start) : 0;
        if (DEBUG) {
            Slog.d(LOG_TAG, String.format(
                    "Job status updated: %d/%d checked/total jobs, %d us",
                    updateTrackedJobs.mCheckedCount,
                    updateTrackedJobs.mTotalCount,
                    (time / 1000)
                    ));
        }

        if (updateTrackedJobs.mChanged) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    boolean updateSingleJobRestrictionLocked(JobStatus jobStatus) {

        final int uid = jobStatus.getSourceUid();
        final String packageName = jobStatus.getSourcePackageName();

        final boolean canRun = !mForceAppStandbyTracker.areJobsRestricted(uid, packageName,
                (jobStatus.getInternalFlags() & JobStatus.INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION)
                        != 0);

        return jobStatus.setBackgroundNotRestrictedConstraintSatisfied(canRun);
    }

    private final class UpdateJobFunctor implements JobStore.JobStatusFunctor {
        private final int mFilterUid;

        boolean mChanged = false;
        int mTotalCount = 0;
        int mCheckedCount = 0;

        UpdateJobFunctor(int filterUid) {
            mFilterUid = filterUid;
        }

        @Override
        public void process(JobStatus jobStatus) {
            mTotalCount++;
            if ((mFilterUid > 0) && (mFilterUid != jobStatus.getSourceUid())) {
                return;
            }
            mCheckedCount++;
            if (updateSingleJobRestrictionLocked(jobStatus)) {
                mChanged = true;
            }
        }
    }

    private final Listener mForceAppStandbyListener = new Listener() {
        @Override
        public void updateAllJobs() {
            synchronized (mLock) {
                updateAllJobRestrictionsLocked();
            }
        }

        @Override
        public void updateJobsForUid(int uid) {
            synchronized (mLock) {
                updateJobRestrictionsForUidLocked(uid);
            }
        }

        @Override
        public void updateJobsForUidPackage(int uid, String packageName) {
            synchronized (mLock) {
                updateJobRestrictionsForUidLocked(uid);
            }
        }
    };
}
