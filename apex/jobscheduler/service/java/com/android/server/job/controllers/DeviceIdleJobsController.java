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

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.ArrayUtils;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;
import com.android.server.job.StateControllerProto.DeviceIdleJobsController.TrackedJob;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * When device is dozing, set constraint for all jobs, except whitelisted apps, as not satisfied.
 * When device is not dozing, set constraint for all jobs as satisfied.
 */
public final class DeviceIdleJobsController extends StateController {
    private static final String TAG = "JobScheduler.DeviceIdle";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private static final long BACKGROUND_JOBS_DELAY = 3000;

    static final int PROCESS_BACKGROUND_JOBS = 1;

    /**
     * These are jobs added with a special flag to indicate that they should be exempted from doze
     * when the app is temp whitelisted or in the foreground.
     */
    private final ArraySet<JobStatus> mAllowInIdleJobs;
    private final SparseBooleanArray mForegroundUids = new SparseBooleanArray();
    private final DeviceIdleUpdateFunctor mDeviceIdleUpdateFunctor;
    private final DeviceIdleJobsDelayHandler mHandler;
    private final PowerManager mPowerManager;
    private final DeviceIdleInternal mLocalDeviceIdleController;

    /**
     * True when in device idle mode, so we don't want to schedule any jobs.
     */
    private boolean mDeviceIdleMode;
    private int[] mDeviceIdleWhitelistAppIds;
    private int[] mPowerSaveTempWhitelistAppIds;

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
                        if (DEBUG) {
                            Slog.d(TAG, "Got whitelist "
                                    + Arrays.toString(mDeviceIdleWhitelistAppIds));
                        }
                    }
                    break;
                case PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED:
                    synchronized (mLock) {
                        mPowerSaveTempWhitelistAppIds =
                                mLocalDeviceIdleController.getPowerSaveTempWhitelistAppIds();
                        if (DEBUG) {
                            Slog.d(TAG, "Got temp whitelist "
                                    + Arrays.toString(mPowerSaveTempWhitelistAppIds));
                        }
                        final ArraySet<JobStatus> changedJobs = new ArraySet<>();
                        final long nowElapsed = sElapsedRealtimeClock.millis();
                        for (int i = 0; i < mAllowInIdleJobs.size(); i++) {
                            if (updateTaskStateLocked(mAllowInIdleJobs.valueAt(i), nowElapsed)) {
                                changedJobs.add(mAllowInIdleJobs.valueAt(i));
                            }
                        }
                        if (changedJobs.size() > 0) {
                            mStateChangedListener.onControllerStateChanged(changedJobs);
                        }
                    }
                    break;
            }
        }
    };

    /** Criteria for whether or not we should a job's rush evaluation when the device exits Doze. */
    private final Predicate<JobStatus> mShouldRushEvaluation = (jobStatus) ->
            jobStatus.isRequestedExpeditedJob() || mForegroundUids.get(jobStatus.getSourceUid());

    public DeviceIdleJobsController(JobSchedulerService service) {
        super(service);

        mHandler = new DeviceIdleJobsDelayHandler(mContext.getMainLooper());
        // Register for device idle mode changes
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mLocalDeviceIdleController =
                LocalServices.getService(DeviceIdleInternal.class);
        mDeviceIdleWhitelistAppIds = mLocalDeviceIdleController.getPowerSaveWhitelistUserAppIds();
        mPowerSaveTempWhitelistAppIds =
                mLocalDeviceIdleController.getPowerSaveTempWhitelistAppIds();
        mDeviceIdleUpdateFunctor = new DeviceIdleUpdateFunctor();
        mAllowInIdleJobs = new ArraySet<>();
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
            if (DEBUG) Slog.d(TAG, "mDeviceIdleMode=" + mDeviceIdleMode);
            mDeviceIdleUpdateFunctor.prepare();
            if (enabled) {
                mHandler.removeMessages(PROCESS_BACKGROUND_JOBS);
                mService.getJobStore().forEachJob(mDeviceIdleUpdateFunctor);
            } else {
                // When coming out of doze, process all foreground uids and EJs immediately,
                // while others will be processed after a delay of 3 seconds.
                mService.getJobStore().forEachJob(mShouldRushEvaluation, mDeviceIdleUpdateFunctor);
                mHandler.sendEmptyMessageDelayed(PROCESS_BACKGROUND_JOBS, BACKGROUND_JOBS_DELAY);
            }
        }
        // Inform the job scheduler service about idle mode changes
        if (changed) {
            mStateChangedListener.onDeviceIdleStateChanged(enabled);
        }
    }

    /**
     *  Called by jobscheduler service to report uid state changes between active and idle
     */
    public void setUidActiveLocked(int uid, boolean active) {
        final boolean changed = (active != mForegroundUids.get(uid));
        if (!changed) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "uid " + uid + " going " + (active ? "active" : "inactive"));
        }
        mForegroundUids.put(uid, active);
        mDeviceIdleUpdateFunctor.prepare();
        mService.getJobStore().forEachJobForSourceUid(uid, mDeviceIdleUpdateFunctor);
        if (mDeviceIdleUpdateFunctor.mChangedJobs.size() > 0) {
            mStateChangedListener.onControllerStateChanged(mDeviceIdleUpdateFunctor.mChangedJobs);
        }
    }

    /**
     * Checks if the given job's scheduling app id exists in the device idle user whitelist.
     */
    boolean isWhitelistedLocked(JobStatus job) {
        return Arrays.binarySearch(mDeviceIdleWhitelistAppIds,
                UserHandle.getAppId(job.getSourceUid())) >= 0;
    }

    /**
     * Checks if the given job's scheduling app id exists in the device idle temp whitelist.
     */
    boolean isTempWhitelistedLocked(JobStatus job) {
        return ArrayUtils.contains(mPowerSaveTempWhitelistAppIds,
                UserHandle.getAppId(job.getSourceUid()));
    }

    private boolean updateTaskStateLocked(JobStatus task, final long nowElapsed) {
        final boolean allowInIdle = ((task.getFlags()&JobInfo.FLAG_IMPORTANT_WHILE_FOREGROUND) != 0)
                && (mForegroundUids.get(task.getSourceUid()) || isTempWhitelistedLocked(task));
        final boolean whitelisted = isWhitelistedLocked(task);
        final boolean enableTask = !mDeviceIdleMode || whitelisted || allowInIdle;
        return task.setDeviceNotDozingConstraintSatisfied(nowElapsed, enableTask, whitelisted);
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if ((jobStatus.getFlags()&JobInfo.FLAG_IMPORTANT_WHILE_FOREGROUND) != 0) {
            mAllowInIdleJobs.add(jobStatus);
        }
        updateTaskStateLocked(jobStatus, sElapsedRealtimeClock.millis());
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if ((jobStatus.getFlags()&JobInfo.FLAG_IMPORTANT_WHILE_FOREGROUND) != 0) {
            mAllowInIdleJobs.remove(jobStatus);
        }
    }

    @Override
    public void dumpControllerStateLocked(final IndentingPrintWriter pw,
            final Predicate<JobStatus> predicate) {
        pw.println("Idle mode: " + mDeviceIdleMode);
        pw.println();

        mService.getJobStore().forEachJob(predicate, (jobStatus) -> {
            pw.print("#");
            jobStatus.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, jobStatus.getSourceUid());
            pw.print(": ");
            pw.print(jobStatus.getSourcePackageName());
            pw.print((jobStatus.satisfiedConstraints
                    & JobStatus.CONSTRAINT_DEVICE_NOT_DOZING) != 0
                            ? " RUNNABLE" : " WAITING");
            if (jobStatus.appHasDozeExemption) {
                pw.print(" WHITELISTED");
            }
            if (mAllowInIdleJobs.contains(jobStatus)) {
                pw.print(" ALLOWED_IN_DOZE");
            }
            pw.println();
        });
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.DEVICE_IDLE);

        proto.write(StateControllerProto.DeviceIdleJobsController.IS_DEVICE_IDLE_MODE,
                mDeviceIdleMode);
        mService.getJobStore().forEachJob(predicate, (jobStatus) -> {
            final long jsToken =
                    proto.start(StateControllerProto.DeviceIdleJobsController.TRACKED_JOBS);

            jobStatus.writeToShortProto(proto, TrackedJob.INFO);
            proto.write(TrackedJob.SOURCE_UID, jobStatus.getSourceUid());
            proto.write(TrackedJob.SOURCE_PACKAGE_NAME, jobStatus.getSourcePackageName());
            proto.write(TrackedJob.ARE_CONSTRAINTS_SATISFIED,
                    (jobStatus.satisfiedConstraints & JobStatus.CONSTRAINT_DEVICE_NOT_DOZING) != 0);
            proto.write(TrackedJob.IS_DOZE_WHITELISTED, jobStatus.appHasDozeExemption);
            proto.write(TrackedJob.IS_ALLOWED_IN_DOZE, mAllowInIdleJobs.contains(jobStatus));

            proto.end(jsToken);
        });

        proto.end(mToken);
        proto.end(token);
    }

    final class DeviceIdleUpdateFunctor implements Consumer<JobStatus> {
        final ArraySet<JobStatus> mChangedJobs = new ArraySet<>();
        long mUpdateTimeElapsed = 0;

        void prepare() {
            mChangedJobs.clear();
            mUpdateTimeElapsed = sElapsedRealtimeClock.millis();
        }

        @Override
        public void accept(JobStatus jobStatus) {
            if (updateTaskStateLocked(jobStatus, mUpdateTimeElapsed)) {
                mChangedJobs.add(jobStatus);
            }
        }
    }

    final class DeviceIdleJobsDelayHandler extends Handler {
        public DeviceIdleJobsDelayHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PROCESS_BACKGROUND_JOBS:
                    // Just process all the jobs, the ones in foreground should already be running.
                    synchronized (mLock) {
                        mDeviceIdleUpdateFunctor.prepare();
                        mService.getJobStore().forEachJob(mDeviceIdleUpdateFunctor);
                        if (mDeviceIdleUpdateFunctor.mChangedJobs.size() > 0) {
                            mStateChangedListener.onControllerStateChanged(
                                    mDeviceIdleUpdateFunctor.mChangedJobs);
                        }
                    }
                    break;
            }
        }
    }
}
