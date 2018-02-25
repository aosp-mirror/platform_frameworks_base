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

import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.AppStateTracker;
import com.android.server.AppStateTracker.Listener;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;
import com.android.server.job.StateControllerProto.BackgroundJobsController.TrackedJob;

import java.util.function.Consumer;
import java.util.function.Predicate;

public final class BackgroundJobsController extends StateController {
    private static final String TAG = "JobScheduler.Background";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final AppStateTracker mAppStateTracker;

    public BackgroundJobsController(JobSchedulerService service) {
        super(service);

        mAppStateTracker = Preconditions.checkNotNull(
                LocalServices.getService(AppStateTracker.class));
        mAppStateTracker.addListener(mForceAppStandbyListener);
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
    public void dumpControllerStateLocked(final IndentingPrintWriter pw,
            final Predicate<JobStatus> predicate) {
        mAppStateTracker.dump(pw);
        pw.println();

        mService.getJobStore().forEachJob(predicate, (jobStatus) -> {
            final int uid = jobStatus.getSourceUid();
            final String sourcePkg = jobStatus.getSourcePackageName();
            pw.print("#");
            jobStatus.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, uid);
            pw.print(mAppStateTracker.isUidActive(uid) ? " active" : " idle");
            if (mAppStateTracker.isUidPowerSaveWhitelisted(uid) ||
                    mAppStateTracker.isUidTempPowerSaveWhitelisted(uid)) {
                pw.print(", whitelisted");
            }
            pw.print(": ");
            pw.print(sourcePkg);

            pw.print(" [RUN_ANY_IN_BACKGROUND ");
            pw.print(mAppStateTracker.isRunAnyInBackgroundAppOpsAllowed(uid, sourcePkg)
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
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.BACKGROUND);

        mAppStateTracker.dumpProto(proto,
                StateControllerProto.BackgroundJobsController.FORCE_APP_STANDBY_TRACKER);

        mService.getJobStore().forEachJob(predicate, (jobStatus) -> {
            final long jsToken =
                    proto.start(StateControllerProto.BackgroundJobsController.TRACKED_JOBS);

            jobStatus.writeToShortProto(proto,
                    TrackedJob.INFO);
            final int sourceUid = jobStatus.getSourceUid();
            proto.write(TrackedJob.SOURCE_UID, sourceUid);
            final String sourcePkg = jobStatus.getSourcePackageName();
            proto.write(TrackedJob.SOURCE_PACKAGE_NAME, sourcePkg);

            proto.write(TrackedJob.IS_IN_FOREGROUND,
                    mAppStateTracker.isUidActive(sourceUid));
            proto.write(TrackedJob.IS_WHITELISTED,
                    mAppStateTracker.isUidPowerSaveWhitelisted(sourceUid) ||
                    mAppStateTracker.isUidTempPowerSaveWhitelisted(sourceUid));

            proto.write(
                    TrackedJob.CAN_RUN_ANY_IN_BACKGROUND,
                    mAppStateTracker.isRunAnyInBackgroundAppOpsAllowed(
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

        mService.getJobStore().forEachJob(updateTrackedJobs);

        final long time = DEBUG ? (SystemClock.elapsedRealtimeNanos() - start) : 0;
        if (DEBUG) {
            Slog.d(TAG, String.format(
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

        final boolean canRun = !mAppStateTracker.areJobsRestricted(uid, packageName,
                (jobStatus.getInternalFlags() & JobStatus.INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION)
                        != 0);

        return jobStatus.setBackgroundNotRestrictedConstraintSatisfied(canRun);
    }

    private final class UpdateJobFunctor implements Consumer<JobStatus> {
        private final int mFilterUid;

        boolean mChanged = false;
        int mTotalCount = 0;
        int mCheckedCount = 0;

        UpdateJobFunctor(int filterUid) {
            mFilterUid = filterUid;
        }

        @Override
        public void accept(JobStatus jobStatus) {
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
