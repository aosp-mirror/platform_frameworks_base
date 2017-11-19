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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IDeviceIdleController;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.util.ArrayUtils;
import com.android.server.ForceAppStandbyTracker;
import com.android.server.ForceAppStandbyTracker.Listener;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import java.io.PrintWriter;
import java.util.function.Predicate;

public final class BackgroundJobsController extends StateController {

    private static final String LOG_TAG = "BackgroundJobsController";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    // Singleton factory
    private static final Object sCreationLock = new Object();
    private static volatile BackgroundJobsController sController;

    private final JobSchedulerService mJobSchedulerService;
    private final IDeviceIdleController mDeviceIdleController;

    private int[] mPowerWhitelistedUserAppIds;
    private int[] mTempWhitelistedAppIds;

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

    private BroadcastReceiver mDozeWhitelistReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                try {
                    switch (intent.getAction()) {
                        case PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED:
                            mPowerWhitelistedUserAppIds =
                                    mDeviceIdleController.getAppIdUserWhitelist();
                            break;
                        case PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED:
                            mTempWhitelistedAppIds = mDeviceIdleController.getAppIdTempWhitelist();
                            break;
                    }
                } catch (RemoteException rexc) {
                    Slog.e(LOG_TAG, "Device idle controller not reachable");
                }
                updateAllJobRestrictionsLocked();
            }
        }
    };

    private BackgroundJobsController(JobSchedulerService service, Context context, Object lock) {
        super(service, context, lock);
        mJobSchedulerService = service;
        mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));

        mForceAppStandbyTracker = ForceAppStandbyTracker.getInstance(context);

        try {
            mPowerWhitelistedUserAppIds = mDeviceIdleController.getAppIdUserWhitelist();
            mTempWhitelistedAppIds = mDeviceIdleController.getAppIdTempWhitelist();
        } catch (RemoteException rexc) {
            // Shouldn't happen as they are in the same process.
            Slog.e(LOG_TAG, "AppOps or DeviceIdle service not reachable", rexc);
        }
        IntentFilter powerWhitelistFilter = new IntentFilter();
        powerWhitelistFilter.addAction(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        powerWhitelistFilter.addAction(PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED);
        context.registerReceiverAsUser(mDozeWhitelistReceiver, UserHandle.ALL, powerWhitelistFilter,
                null, null);

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

        pw.print("Force all apps standby: ");
        pw.println(mForceAppStandbyTracker.isForceAllAppsStandbyEnabled());

        pw.print("Foreground uids: [");
        final SparseBooleanArray foregroundUids = mForceAppStandbyTracker.getForegroudUids();

        String sep = "";
        for (int i = 0; i < foregroundUids.size(); i++) {
            if (foregroundUids.valueAt(i)) {
                pw.print(sep);
                pw.print(UserHandle.formatUid(foregroundUids.keyAt(i)));
                sep = " ";
            }
        }
        pw.println("]");

        pw.println("Restricted packages:");
        for (Pair<Integer, String> uidAndPackage
                : mForceAppStandbyTracker.getRestrictedUidPackages()) {
            pw.print("  ");
            pw.print(UserHandle.formatUid(uidAndPackage.first));
            pw.print(" ");
            pw.print(uidAndPackage.second);
            pw.println();
        }

        pw.println("Job state:");
        mJobSchedulerService.getJobStore().forEachJob((jobStatus) -> {
            if (!jobStatus.shouldDump(filterUid)) {
                return;
            }
            final int uid = jobStatus.getSourceUid();
            pw.print("  #");
            jobStatus.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, uid);
            pw.print(mForceAppStandbyTracker.isInForeground(uid) ? " foreground" : " background");
            if (isWhitelistedLocked(uid)) {
                pw.print(", whitelisted");
            }
            pw.print(": ");
            pw.print(jobStatus.getSourcePackageName());

            pw.print(" [background restrictions ");
            pw.print(mForceAppStandbyTracker.isRunAnyInBackgroundAppOpsAllowed(
                    jobStatus.getSourceUid(), jobStatus.getSourcePackageName()) ? "off]" : "on]");

            if ((jobStatus.satisfiedConstraints
                    & JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0) {
                pw.println(" RUNNABLE");
            } else {
                pw.println(" WAITING");
            }
        });
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

    private boolean isWhitelistedLocked(int uid) {
        final int appId = UserHandle.getAppId(uid);
        return ArrayUtils.contains(mTempWhitelistedAppIds, appId)
                || ArrayUtils.contains(mPowerWhitelistedUserAppIds, appId);
    }

    boolean updateSingleJobRestrictionLocked(JobStatus jobStatus) {

        final int uid = jobStatus.getSourceUid();
        final String packageName = jobStatus.getSourcePackageName();

        final boolean canRun = isWhitelistedLocked(uid)
                || !mForceAppStandbyTracker.isRestricted(uid, packageName);

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
        public void onRestrictionChanged(int uid, String packageName) {
            updateJobRestrictionsForUidLocked(uid);
        }

        @Override
        public void onGlobalRestrictionChanged() {
            updateAllJobRestrictionsLocked();
        }
    };
}
