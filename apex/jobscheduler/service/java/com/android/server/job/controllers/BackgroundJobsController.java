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

import static com.android.server.job.JobSchedulerService.NEVER_INDEX;

import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.AppStateTracker;
import com.android.server.AppStateTracker.Listener;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;
import com.android.server.job.StateControllerProto;
import com.android.server.job.StateControllerProto.BackgroundJobsController.TrackedJob;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Tracks the following pieces of JobStatus state:
 *
 * - the CONSTRAINT_BACKGROUND_NOT_RESTRICTED general constraint bit, which
 *    is used to selectively permit battery-saver exempted jobs to run; and
 *
 * - the uid-active boolean state expressed by the AppStateTracker.  Jobs in 'active'
 *    uids are inherently eligible to run jobs regardless of the uid's standby bucket.
 */
public final class BackgroundJobsController extends StateController {
    private static final String TAG = "JobScheduler.Background";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    // Tri-state about possible "is this uid 'active'?" knowledge
    static final int UNKNOWN = 0;
    static final int KNOWN_ACTIVE = 1;
    static final int KNOWN_INACTIVE = 2;

    private final AppStateTracker mAppStateTracker;

    public BackgroundJobsController(JobSchedulerService service) {
        super(service);

        mAppStateTracker = Objects.requireNonNull(
                LocalServices.getService(AppStateTracker.class));
        mAppStateTracker.addListener(mForceAppStandbyListener);
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        updateSingleJobRestrictionLocked(jobStatus, UNKNOWN);
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
                StateControllerProto.BackgroundJobsController.APP_STATE_TRACKER);

        mService.getJobStore().forEachJob(predicate, (jobStatus) -> {
            final long jsToken =
                    proto.start(StateControllerProto.BackgroundJobsController.TRACKED_JOBS);

            jobStatus.writeToShortProto(proto, TrackedJob.INFO);
            final int sourceUid = jobStatus.getSourceUid();
            proto.write(TrackedJob.SOURCE_UID, sourceUid);
            final String sourcePkg = jobStatus.getSourcePackageName();
            proto.write(TrackedJob.SOURCE_PACKAGE_NAME, sourcePkg);

            proto.write(TrackedJob.IS_IN_FOREGROUND, mAppStateTracker.isUidActive(sourceUid));
            proto.write(TrackedJob.IS_WHITELISTED,
                    mAppStateTracker.isUidPowerSaveWhitelisted(sourceUid) ||
                    mAppStateTracker.isUidTempPowerSaveWhitelisted(sourceUid));

            proto.write(TrackedJob.CAN_RUN_ANY_IN_BACKGROUND,
                    mAppStateTracker.isRunAnyInBackgroundAppOpsAllowed(sourceUid, sourcePkg));

            proto.write(TrackedJob.ARE_CONSTRAINTS_SATISFIED,
                    (jobStatus.satisfiedConstraints &
                            JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0);

            proto.end(jsToken);
        });

        proto.end(mToken);
        proto.end(token);
    }

    private void updateAllJobRestrictionsLocked() {
        updateJobRestrictionsLocked(/*filterUid=*/ -1, UNKNOWN);
    }

    private void updateJobRestrictionsForUidLocked(int uid, boolean isActive) {
        updateJobRestrictionsLocked(uid, (isActive) ? KNOWN_ACTIVE : KNOWN_INACTIVE);
    }

    private void updateJobRestrictionsLocked(int filterUid, int newActiveState) {
        final UpdateJobFunctor updateTrackedJobs = new UpdateJobFunctor(newActiveState);

        final long start = DEBUG ? SystemClock.elapsedRealtimeNanos() : 0;

        final JobStore store = mService.getJobStore();
        if (filterUid > 0) {
            store.forEachJobForSourceUid(filterUid, updateTrackedJobs);
        } else {
            store.forEachJob(updateTrackedJobs);
        }

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

    boolean updateSingleJobRestrictionLocked(JobStatus jobStatus, int activeState) {
        final int uid = jobStatus.getSourceUid();
        final String packageName = jobStatus.getSourcePackageName();

        final boolean canRun = !mAppStateTracker.areJobsRestricted(uid, packageName,
                (jobStatus.getInternalFlags() & JobStatus.INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION)
                        != 0);

        final boolean isActive;
        if (activeState == UNKNOWN) {
            isActive = mAppStateTracker.isUidActive(uid);
        } else {
            isActive = (activeState == KNOWN_ACTIVE);
        }
        if (isActive && jobStatus.getStandbyBucket() == NEVER_INDEX) {
            Slog.wtf(TAG, "App " + packageName + " became active but still in NEVER bucket");
        }
        boolean didChange = jobStatus.setBackgroundNotRestrictedConstraintSatisfied(canRun);
        didChange |= jobStatus.setUidActive(isActive);
        return didChange;
    }

    private final class UpdateJobFunctor implements Consumer<JobStatus> {
        final int activeState;
        boolean mChanged = false;
        int mTotalCount = 0;
        int mCheckedCount = 0;

        public UpdateJobFunctor(int newActiveState) {
            activeState = newActiveState;
        }

        @Override
        public void accept(JobStatus jobStatus) {
            mTotalCount++;
            mCheckedCount++;
            if (updateSingleJobRestrictionLocked(jobStatus, activeState)) {
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
        public void updateJobsForUid(int uid, boolean isActive) {
            synchronized (mLock) {
                updateJobRestrictionsForUidLocked(uid, isActive);
            }
        }

        @Override
        public void updateJobsForUidPackage(int uid, String packageName, boolean isActive) {
            synchronized (mLock) {
                updateJobRestrictionsForUidLocked(uid, isActive);
            }
        }
    };
}
