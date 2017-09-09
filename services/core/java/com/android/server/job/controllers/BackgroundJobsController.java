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

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IDeviceIdleController;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import java.io.PrintWriter;

public final class BackgroundJobsController extends StateController {

    private static final String LOG_TAG = "BackgroundJobsController";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    // Singleton factory
    private static final Object sCreationLock = new Object();
    private static volatile BackgroundJobsController sController;

    private final JobSchedulerService mJobSchedulerService;
    private final IAppOpsService mAppOpsService;
    private final IDeviceIdleController mDeviceIdleController;

    private final SparseBooleanArray mForegroundUids;
    private int[] mPowerWhitelistedUserAppIds;
    private int[] mTempWhitelistedAppIds;
    /**
     * Only tracks jobs for which source package app op RUN_ANY_IN_BACKGROUND is not ALLOWED.
     * Maps jobs to the sourceUid unlike the global {@link JobSchedulerService#mJobs JobStore}
     * which uses callingUid.
     */
    private SparseArray<ArraySet<JobStatus>> mTrackedJobs;

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
                if (checkAllTrackedJobsLocked()) {
                    mStateChangedListener.onControllerStateChanged();
                }
            }
        }
    };

    private BackgroundJobsController(JobSchedulerService service, Context context, Object lock) {
        super(service, context, lock);
        mJobSchedulerService = service;
        mAppOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));

        mForegroundUids = new SparseBooleanArray();
        mTrackedJobs = new SparseArray<>();
        try {
            mAppOpsService.startWatchingMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, null,
                    new AppOpsWatcher());
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
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        final int uid = jobStatus.getSourceUid();
        final String packageName = jobStatus.getSourcePackageName();
        try {
            final int mode = mAppOpsService.checkOperation(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                    uid, packageName);
            if (mode == AppOpsManager.MODE_ALLOWED) {
                jobStatus.setBackgroundNotRestrictedConstraintSatisfied(true);
                return;
            }
        } catch (RemoteException rexc) {
            Slog.e(LOG_TAG, "Cannot reach app ops service", rexc);
        }
        jobStatus.setBackgroundNotRestrictedConstraintSatisfied(canRunJobLocked(uid));
        startTrackingJobLocked(jobStatus);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        stopTrackingJobLocked(jobStatus);
    }

    /* Called by JobSchedulerService to report uid state changes between active and idle */
    public void setUidActiveLocked(int uid, boolean active) {
        final boolean changed = (active != mForegroundUids.get(uid));
        if (!changed) {
            return;
        }
        if (DEBUG) {
            Slog.d(LOG_TAG, "uid " + uid + " going to " + (active ? "fg" : "bg"));
        }
        if (active) {
            mForegroundUids.put(uid, true);
        } else {
            mForegroundUids.delete(uid);
        }
        if (checkTrackedJobsForUidLocked(uid)) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    @Override
    public void dumpControllerStateLocked(final PrintWriter pw, final int filterUid) {
        pw.print("Foreground uids: [");
        for (int i = 0; i < mForegroundUids.size(); i++) {
            if (mForegroundUids.valueAt(i)) pw.print(mForegroundUids.keyAt(i) + " ");
        }
        pw.println("]");
        mJobSchedulerService.getJobStore().forEachJob(new JobStore.JobStatusFunctor() {
            @Override
            public void process(JobStatus jobStatus) {
                if (!jobStatus.shouldDump(filterUid)) {
                    return;
                }
                final int uid = jobStatus.getSourceUid();
                pw.print("  #");
                jobStatus.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, uid);
                pw.print(mForegroundUids.get(uid) ? " foreground" : " background");
                if (isWhitelistedLocked(uid)) {
                    pw.print(", whitelisted");
                }
                pw.print(": ");
                pw.print(jobStatus.getSourcePackageName());
                pw.print(" [background restrictions");
                final ArraySet<JobStatus> jobsForUid = mTrackedJobs.get(uid);
                pw.print(jobsForUid != null && jobsForUid.contains(jobStatus) ? " on]" : " off]");
                if ((jobStatus.satisfiedConstraints
                        & JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0) {
                    pw.println(" RUNNABLE");
                } else {
                    pw.println(" WAITING");
                }
            }
        });
    }

    void startTrackingJobLocked(JobStatus jobStatus) {
        final int uid = jobStatus.getSourceUid();
        ArraySet<JobStatus> jobsForUid = mTrackedJobs.get(uid);
        if (jobsForUid == null) {
            jobsForUid = new ArraySet<>();
            mTrackedJobs.put(uid, jobsForUid);
        }
        jobsForUid.add(jobStatus);
    }

    void stopTrackingJobLocked(JobStatus jobStatus) {
        final int uid = jobStatus.getSourceUid();
        ArraySet<JobStatus> jobsForUid = mTrackedJobs.get(uid);
        if (jobsForUid != null) {
            jobsForUid.remove(jobStatus);
        }
    }

    boolean checkAllTrackedJobsLocked() {
        boolean changed = false;
        for (int i = 0; i < mTrackedJobs.size(); i++) {
            changed |= checkTrackedJobsForUidLocked(mTrackedJobs.keyAt(i));
        }
        return changed;
    }

    private boolean checkTrackedJobsForUidLocked(int uid) {
        final ArraySet<JobStatus> jobsForUid = mTrackedJobs.get(uid);
        boolean changed = false;
        if (jobsForUid != null) {
            for (int i = 0; i < jobsForUid.size(); i++) {
                JobStatus jobStatus = jobsForUid.valueAt(i);
                changed |= jobStatus.setBackgroundNotRestrictedConstraintSatisfied(
                        canRunJobLocked(uid));
            }
        }
        return changed;
    }

    boolean isWhitelistedLocked(int uid) {
        return ArrayUtils.contains(mTempWhitelistedAppIds, UserHandle.getAppId(uid))
                || ArrayUtils.contains(mPowerWhitelistedUserAppIds, UserHandle.getAppId(uid));
    }

    boolean canRunJobLocked(int uid) {
        return mForegroundUids.get(uid) || isWhitelistedLocked(uid);
    }

    private final class AppOpsWatcher extends IAppOpsCallback.Stub {
        @Override
        public void opChanged(int op, int uid, String packageName) throws RemoteException {
            synchronized (mLock) {
                final int mode = mAppOpsService.checkOperation(op, uid, packageName);
                if (DEBUG) {
                    Slog.d(LOG_TAG,
                            "Appop changed for " + uid + ", " + packageName + " to " + mode);
                }
                final boolean shouldTrack = (mode != AppOpsManager.MODE_ALLOWED);
                UpdateTrackedJobsFunc updateTrackedJobs = new UpdateTrackedJobsFunc(uid,
                        packageName, shouldTrack);
                mJobSchedulerService.getJobStore().forEachJob(updateTrackedJobs);
                if (updateTrackedJobs.mChanged) {
                    mStateChangedListener.onControllerStateChanged();
                }
            }
        }
    }

    private final class UpdateTrackedJobsFunc implements JobStore.JobStatusFunctor {
        private final String mPackageName;
        private final int mUid;
        private final boolean mShouldTrack;
        private boolean mChanged = false;

        UpdateTrackedJobsFunc(int uid, String packageName, boolean shouldTrack) {
            mUid = uid;
            mPackageName = packageName;
            mShouldTrack = shouldTrack;
        }

        @Override
        public void process(JobStatus jobStatus) {
            final String packageName = jobStatus.getSourcePackageName();
            final int uid = jobStatus.getSourceUid();
            if (mUid != uid || !mPackageName.equals(packageName)) {
                return;
            }
            if (mShouldTrack) {
                mChanged |= jobStatus.setBackgroundNotRestrictedConstraintSatisfied(
                        canRunJobLocked(uid));
                startTrackingJobLocked(jobStatus);
            } else {
                mChanged |= jobStatus.setBackgroundNotRestrictedConstraintSatisfied(true);
                stopTrackingJobLocked(jobStatus);
            }
        }
    }
}
