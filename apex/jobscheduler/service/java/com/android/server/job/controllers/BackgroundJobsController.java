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
import static com.android.server.job.JobSchedulerService.getPackageName;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.AppStateTracker;
import com.android.server.AppStateTrackerImpl;
import com.android.server.AppStateTrackerImpl.Listener;
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
 *
 * - the app's stopped state
 */
public final class BackgroundJobsController extends StateController {
    private static final String TAG = "JobScheduler.Background";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    // Tri-state about possible "is this uid 'active'?" knowledge
    static final int UNKNOWN = 0;
    static final int KNOWN_ACTIVE = 1;
    static final int KNOWN_INACTIVE = 2;

    private final ActivityManagerInternal mActivityManagerInternal;
    private final AppStateTrackerImpl mAppStateTracker;
    private final PackageManagerInternal mPackageManagerInternal;

    @GuardedBy("mLock")
    private final SparseArrayMap<String, Boolean> mPackageStoppedState = new SparseArrayMap<>();

    private final UpdateJobFunctor mUpdateJobFunctor = new UpdateJobFunctor();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String pkgName = getPackageName(intent);
            final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            final String action = intent.getAction();
            if (pkgUid == -1) {
                Slog.e(TAG, "Didn't get package UID in intent (" + action + ")");
                return;
            }

            if (DEBUG) {
                Slog.d(TAG, "Got " + action + " for " + pkgUid + "/" + pkgName);
            }

            switch (action) {
                case Intent.ACTION_PACKAGE_RESTARTED: {
                    synchronized (mLock) {
                        // ACTION_PACKAGE_RESTARTED doesn't always mean the app is placed and kept
                        // in the stopped state, so don't put TRUE in the cache. Remove any existing
                        // entry and rely on an explicit call to PackageManager's isStopped() API.
                        mPackageStoppedState.delete(pkgUid, pkgName);
                        updateJobRestrictionsForUidLocked(pkgUid, false);
                    }
                }
                break;

                case Intent.ACTION_PACKAGE_UNSTOPPED: {
                    synchronized (mLock) {
                        mPackageStoppedState.add(pkgUid, pkgName, Boolean.FALSE);
                        updateJobRestrictionsLocked(pkgUid, UNKNOWN);
                    }
                }
                break;
            }
        }
    };

    public BackgroundJobsController(JobSchedulerService service) {
        super(service);

        mActivityManagerInternal = (ActivityManagerInternal) Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mAppStateTracker = (AppStateTrackerImpl) Objects.requireNonNull(
                LocalServices.getService(AppStateTracker.class));
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    @Override
    @GuardedBy("mLock")
    public void startTrackingLocked() {
        mAppStateTracker.addListener(mForceAppStandbyListener);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        filter.addAction(Intent.ACTION_PACKAGE_UNSTOPPED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(
                mBroadcastReceiver, UserHandle.ALL, filter, null, null);
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        updateSingleJobRestrictionLocked(jobStatus, sElapsedRealtimeClock.millis(), UNKNOWN);
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob) {
    }

    @Override
    @GuardedBy("mLock")
    public void evaluateStateLocked(JobStatus jobStatus) {
        if (jobStatus.isRequestedExpeditedJob()) {
            // Only requested-EJs could have their run-in-bg constraint change outside of something
            // coming through the ForceAppStandbyListener.
            updateSingleJobRestrictionLocked(jobStatus, sElapsedRealtimeClock.millis(), UNKNOWN);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onAppRemovedLocked(String packageName, int uid) {
        mPackageStoppedState.delete(uid, packageName);
    }

    @Override
    @GuardedBy("mLock")
    public void onUserRemovedLocked(int userId) {
        for (int u = mPackageStoppedState.numMaps() - 1; u >= 0; --u) {
            final int uid = mPackageStoppedState.keyAt(u);
            if (UserHandle.getUserId(uid) == userId) {
                mPackageStoppedState.deleteAt(u);
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(final IndentingPrintWriter pw,
            final Predicate<JobStatus> predicate) {
        pw.println("Aconfig flags:");
        pw.increaseIndent();
        pw.print(android.content.pm.Flags.FLAG_STAY_STOPPED,
                android.content.pm.Flags.stayStopped());
        pw.println();
        pw.decreaseIndent();
        pw.println();

        mAppStateTracker.dump(pw);
        pw.println();

        pw.println("Stopped packages:");
        pw.increaseIndent();
        mPackageStoppedState.forEach((uid, pkgName, isStopped) -> {
            pw.print(uid);
            pw.print(":");
            pw.print(pkgName);
            pw.print("=");
            pw.println(isStopped);
        });
        pw.println();

        mService.getJobStore().forEachJob(predicate, (jobStatus) -> {
            final int uid = jobStatus.getSourceUid();
            final String sourcePkg = jobStatus.getSourcePackageName();
            pw.print("#");
            jobStatus.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, uid);
            pw.print(mAppStateTracker.isUidActive(uid) ? " active" : " idle");
            if (mAppStateTracker.isUidPowerSaveExempt(uid)
                    || mAppStateTracker.isUidTempPowerSaveExempt(uid)) {
                pw.print(", exempted");
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
    @GuardedBy("mLock")
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
                    mAppStateTracker.isUidPowerSaveExempt(sourceUid)
                            || mAppStateTracker.isUidTempPowerSaveExempt(sourceUid));

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

    @GuardedBy("mLock")
    private void updateAllJobRestrictionsLocked() {
        updateJobRestrictionsLocked(/*filterUid=*/ -1, UNKNOWN);
    }

    @GuardedBy("mLock")
    private void updateJobRestrictionsForUidLocked(int uid, boolean isActive) {
        updateJobRestrictionsLocked(uid, (isActive) ? KNOWN_ACTIVE : KNOWN_INACTIVE);
    }

    @GuardedBy("mLock")
    private void updateJobRestrictionsLocked(int filterUid, int newActiveState) {
        mUpdateJobFunctor.prepare(newActiveState);

        final long start = DEBUG ? SystemClock.elapsedRealtimeNanos() : 0;

        final JobStore store = mService.getJobStore();
        if (filterUid > 0) {
            store.forEachJobForSourceUid(filterUid, mUpdateJobFunctor);
        } else {
            store.forEachJob(mUpdateJobFunctor);
        }

        final long time = DEBUG ? (SystemClock.elapsedRealtimeNanos() - start) : 0;
        if (DEBUG) {
            Slog.d(TAG, String.format(
                    "Job status updated: %d/%d checked/total jobs, %d us",
                    mUpdateJobFunctor.mCheckedCount,
                    mUpdateJobFunctor.mTotalCount,
                    (time / 1000)
            ));
        }

        if (mUpdateJobFunctor.mChangedJobs.size() > 0) {
            mStateChangedListener.onControllerStateChanged(mUpdateJobFunctor.mChangedJobs);
        }
    }

    @GuardedBy("mLock")
    private boolean isPackageStoppedLocked(String packageName, int uid) {
        if (mPackageStoppedState.contains(uid, packageName)) {
            return mPackageStoppedState.get(uid, packageName);
        }

        try {
            final boolean isStopped = mPackageManagerInternal.isPackageStopped(packageName, uid);
            mPackageStoppedState.add(uid, packageName, isStopped);
            return isStopped;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Couldn't determine stopped state for unknown package: " + packageName);
            return false;
        }
    }

    @GuardedBy("mLock")
    boolean updateSingleJobRestrictionLocked(JobStatus jobStatus, final long nowElapsed,
            int activeState) {
        final int uid = jobStatus.getSourceUid();
        final String packageName = jobStatus.getSourcePackageName();

        final boolean isSourcePkgStopped =
                isPackageStoppedLocked(jobStatus.getSourcePackageName(), jobStatus.getSourceUid());
        final boolean isCallingPkgStopped;
        if (!jobStatus.isProxyJob()) {
            isCallingPkgStopped = isSourcePkgStopped;
        } else {
            isCallingPkgStopped =
                    isPackageStoppedLocked(jobStatus.getCallingPackageName(), jobStatus.getUid());
        }
        final boolean isStopped = android.content.pm.Flags.stayStopped()
                && (isCallingPkgStopped || isSourcePkgStopped);
        final boolean isUserBgRestricted = isStopped
                || (!mActivityManagerInternal.isBgAutoRestrictedBucketFeatureFlagEnabled()
                        && !mAppStateTracker.isRunAnyInBackgroundAppOpsAllowed(uid, packageName));
        // If a job started with the foreground flag, it'll cause the UID to stay active
        // and thus cause areJobsRestricted() to always return false, so if
        // areJobsRestricted() returns false and the app is BG restricted and not TOP,
        // we need to stop any jobs that started with the foreground flag so they don't
        // keep the app in an elevated proc state. If we were to get in this situation,
        // then the user restricted the app after the job started, so it's best to stop
        // the job as soon as possible, especially since the job would be visible to the
        // user (with a notification and in Task Manager).
        // There are several other reasons that uidActive can be true for an app even if its
        // proc state is less important than BFGS.
        // JobScheduler has historically (at least up through UDC) allowed the app's jobs to run
        // when its UID was active, even if it's background restricted. This has been fine because
        // JobScheduler stops the job as soon as the UID becomes inactive and the jobs themselves
        // will not keep the UID active. The logic here is to ensure that special jobs
        // (e.g. user-initiated jobs) themselves do not keep the UID active when the app is
        // background restricted.
        final boolean shouldStopImmediately = jobStatus.startedWithForegroundFlag
                && isUserBgRestricted
                && mService.getUidProcState(uid)
                        > ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
        // Don't let jobs (including proxied jobs) run if the app is in the stopped state.
        final boolean canRun = !isStopped && !shouldStopImmediately
                && !mAppStateTracker.areJobsRestricted(
                        uid, packageName, jobStatus.canRunInBatterySaver());

        final boolean isActive;
        if (activeState == UNKNOWN) {
            isActive = mAppStateTracker.isUidActive(uid);
        } else {
            isActive = (activeState == KNOWN_ACTIVE);
        }
        if (isActive && jobStatus.getStandbyBucket() == NEVER_INDEX) {
            jobStatus.maybeLogBucketMismatch();
        }
        boolean didChange =
                jobStatus.setBackgroundNotRestrictedConstraintSatisfied(nowElapsed, canRun,
                        isUserBgRestricted);
        didChange |= jobStatus.setUidActive(isActive);
        return didChange;
    }

    private final class UpdateJobFunctor implements Consumer<JobStatus> {
        int mActiveState;
        final ArraySet<JobStatus> mChangedJobs = new ArraySet<>();
        int mTotalCount = 0;
        int mCheckedCount = 0;
        long mUpdateTimeElapsed = 0;

        void prepare(int newActiveState) {
            mActiveState = newActiveState;
            mUpdateTimeElapsed = sElapsedRealtimeClock.millis();
            mChangedJobs.clear();
            mTotalCount = 0;
            mCheckedCount = 0;
        }

        @Override
        public void accept(JobStatus jobStatus) {
            mTotalCount++;
            mCheckedCount++;
            if (updateSingleJobRestrictionLocked(jobStatus, mUpdateTimeElapsed, mActiveState)) {
                mChangedJobs.add(jobStatus);
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
