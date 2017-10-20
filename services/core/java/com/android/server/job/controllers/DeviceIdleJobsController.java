/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.DeviceIdleController;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * When device is dozing, set constraint for all jobs, except whitelisted apps, as not satisfied.
 * When device is not dozing, set constraint for all jobs as satisfied.
 */
public final class DeviceIdleJobsController extends StateController {

    private static final String LOG_TAG = "DeviceIdleJobsController";
    private static final boolean LOG_DEBUG = false;

    // Singleton factory
    private static Object sCreationLock = new Object();
    private static DeviceIdleJobsController sController;

    private final JobSchedulerService mJobSchedulerService;
    private final PowerManager mPowerManager;
    private final DeviceIdleController.LocalService mLocalDeviceIdleController;

    /**
     * True when in device idle mode, so we don't want to schedule any jobs.
     */
    private boolean mDeviceIdleMode;
    private int[] mDeviceIdleWhitelistAppIds;
    private int[] mPowerSaveTempWhitelistAppIds;
    // These jobs were added when the app was in temp whitelist, these should be exempted from doze
    private final ArraySet<JobStatus> mTempWhitelistedJobs;

    final JobStore.JobStatusFunctor mUpdateFunctor = new JobStore.JobStatusFunctor() {
        @Override public void process(JobStatus jobStatus) {
            updateTaskStateLocked(jobStatus);
        }
    };

    /**
     * Returns a singleton for the DeviceIdleJobsController
     */
    public static DeviceIdleJobsController get(JobSchedulerService service) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new DeviceIdleJobsController(service, service.getContext(),
                        service.getLock());
            }
            return sController;
        }
    }

    // onReceive
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED:
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    updateIdleMode(mPowerManager != null && (mPowerManager.isDeviceIdleMode()
                            || mPowerManager.isLightDeviceIdleMode()));
                    break;
                case PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED:
                    synchronized (mLock) {
                        mDeviceIdleWhitelistAppIds =
                                mLocalDeviceIdleController.getPowerSaveWhitelistUserAppIds();
                        if (LOG_DEBUG) {
                            Slog.d(LOG_TAG, "Got whitelist "
                                    + Arrays.toString(mDeviceIdleWhitelistAppIds));
                        }
                    }
                    break;
                case PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED:
                    synchronized (mLock) {
                        mPowerSaveTempWhitelistAppIds =
                                mLocalDeviceIdleController.getPowerSaveTempWhitelistAppIds();
                        if (LOG_DEBUG) {
                            Slog.d(LOG_TAG, "Got temp whitelist "
                                    + Arrays.toString(mPowerSaveTempWhitelistAppIds));
                        }
                        boolean changed = false;
                        for (int i = 0; i < mTempWhitelistedJobs.size(); i ++) {
                            changed |= updateTaskStateLocked(mTempWhitelistedJobs.valueAt(i));
                        }
                        if (changed) {
                            mStateChangedListener.onControllerStateChanged();
                        }
                    }
                    break;
            }
        }
    };

    private DeviceIdleJobsController(JobSchedulerService jobSchedulerService, Context context,
            Object lock) {
        super(jobSchedulerService, context, lock);

        mJobSchedulerService = jobSchedulerService;
        // Register for device idle mode changes
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mLocalDeviceIdleController =
                LocalServices.getService(DeviceIdleController.LocalService.class);
        mDeviceIdleWhitelistAppIds = mLocalDeviceIdleController.getPowerSaveWhitelistUserAppIds();
        mPowerSaveTempWhitelistAppIds =
                mLocalDeviceIdleController.getPowerSaveTempWhitelistAppIds();
        mTempWhitelistedJobs = new ArraySet<>();
        final IntentFilter filter = new IntentFilter();
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED);
        mContext.registerReceiverAsUser(
                mBroadcastReceiver, UserHandle.ALL, filter, null, null);
    }

    void updateIdleMode(boolean enabled) {
        boolean changed = false;
        synchronized (mLock) {
            if (mDeviceIdleMode != enabled) {
                changed = true;
            }
            mDeviceIdleMode = enabled;
            if (LOG_DEBUG) Slog.d(LOG_TAG, "mDeviceIdleMode=" + mDeviceIdleMode);
            mJobSchedulerService.getJobStore().forEachJob(mUpdateFunctor);
        }
        // Inform the job scheduler service about idle mode changes
        if (changed) {
            mStateChangedListener.onDeviceIdleStateChanged(enabled);
        }
    }

    /**
     * Checks if the given job's scheduling app id exists in the device idle user whitelist.
     */
    boolean isWhitelistedLocked(JobStatus job) {
        return ArrayUtils.contains(mDeviceIdleWhitelistAppIds,
                UserHandle.getAppId(job.getSourceUid()));
    }

    /**
     * Checks if the given job's scheduling app id exists in the device idle temp whitelist.
     */
    boolean isTempWhitelistedLocked(JobStatus job) {
        return ArrayUtils.contains(mPowerSaveTempWhitelistAppIds,
                UserHandle.getAppId(job.getSourceUid()));
    }

    private boolean updateTaskStateLocked(JobStatus task) {
        final boolean whitelisted = isWhitelistedLocked(task)
                || (mTempWhitelistedJobs.contains(task) && isTempWhitelistedLocked(task));
        final boolean enableTask = !mDeviceIdleMode || whitelisted;
        return task.setDeviceNotDozingConstraintSatisfied(enableTask, whitelisted);
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (isTempWhitelistedLocked(jobStatus)) {
            mTempWhitelistedJobs.add(jobStatus);
            jobStatus.setDeviceNotDozingConstraintSatisfied(true, true);
        } else {
            updateTaskStateLocked(jobStatus);
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        mTempWhitelistedJobs.remove(jobStatus);
    }

    @Override
    public void dumpControllerStateLocked(final PrintWriter pw, final int filterUid) {
        pw.println("DeviceIdleJobsController");
        mJobSchedulerService.getJobStore().forEachJob(new JobStore.JobStatusFunctor() {
            @Override public void process(JobStatus jobStatus) {
                if (!jobStatus.shouldDump(filterUid)) {
                    return;
                }
                pw.print("  #");
                jobStatus.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, jobStatus.getSourceUid());
                pw.print(": ");
                pw.print(jobStatus.getSourcePackageName());
                pw.print((jobStatus.satisfiedConstraints
                        & JobStatus.CONSTRAINT_DEVICE_NOT_DOZING) != 0
                                ? " RUNNABLE" : " WAITING");
                if (jobStatus.dozeWhitelisted) {
                    pw.print(" WHITELISTED");
                }
                pw.println();
            }
        });
    }
}