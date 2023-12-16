/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;

import java.util.function.Predicate;

/**
 * Simple controller that tracks whether the phone is charging or not. The phone is considered to
 * be charging when it's been plugged in for more than two minutes, and the system has broadcast
 * ACTION_BATTERY_OK.
 */
public final class BatteryController extends RestrictingController {
    private static final String TAG = "JobScheduler.Battery";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mTrackedTasks = new ArraySet<>();
    /**
     * List of jobs that started while the UID was in the TOP state.
     */
    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mTopStartedJobs = new ArraySet<>();

    private final PowerTracker mPowerTracker;

    private final FlexibilityController mFlexibilityController;
    /**
     * Helper set to avoid too much GC churn from frequent calls to
     * {@link #maybeReportNewChargingStateLocked()}.
     */
    private final ArraySet<JobStatus> mChangedJobs = new ArraySet<>();

    @GuardedBy("mLock")
    private Boolean mLastReportedStatsdBatteryNotLow = null;
    @GuardedBy("mLock")
    private Boolean mLastReportedStatsdStablePower = null;

    public BatteryController(JobSchedulerService service,
            FlexibilityController flexibilityController) {
        super(service);
        mPowerTracker = new PowerTracker();
        mFlexibilityController = flexibilityController;
    }

    @Override
    public void startTrackingLocked() {
        mPowerTracker.startTracking();
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasPowerConstraint()) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_BATTERY);
            if (taskStatus.hasChargingConstraint()) {
                if (hasTopExemptionLocked(taskStatus)) {
                    taskStatus.setChargingConstraintSatisfied(nowElapsed,
                            mPowerTracker.isPowerConnected());
                } else {
                    taskStatus.setChargingConstraintSatisfied(nowElapsed,
                            mService.isBatteryCharging() && mService.isBatteryNotLow());
                }
            }
            taskStatus.setBatteryNotLowConstraintSatisfied(nowElapsed, mService.isBatteryNotLow());
        }
    }

    @Override
    public void startTrackingRestrictedJobLocked(JobStatus jobStatus) {
        maybeStartTrackingJobLocked(jobStatus, null);
    }

    @Override
    @GuardedBy("mLock")
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        if (!jobStatus.hasPowerConstraint()) {
            // Ignore all jobs the controller wouldn't be tracking.
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Prepping for " + jobStatus.toShortString());
        }

        final int uid = jobStatus.getSourceUid();
        if (mService.getUidBias(uid) == JobInfo.BIAS_TOP_APP) {
            if (DEBUG) {
                Slog.d(TAG, jobStatus.toShortString() + " is top started job");
            }
            mTopStartedJobs.add(jobStatus);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void unprepareFromExecutionLocked(JobStatus jobStatus) {
        mTopStartedJobs.remove(jobStatus);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_BATTERY)) {
            mTrackedTasks.remove(taskStatus);
            mTopStartedJobs.remove(taskStatus);
        }
    }

    @Override
    public void stopTrackingRestrictedJobLocked(JobStatus jobStatus) {
        if (!jobStatus.hasPowerConstraint()) {
            maybeStopTrackingJobLocked(jobStatus, null);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onBatteryStateChangedLocked() {
        // Update job bookkeeping out of band.
        AppSchedulingModuleThread.getHandler().post(() -> {
            synchronized (mLock) {
                maybeReportNewChargingStateLocked();
            }
        });
    }

    @Override
    @GuardedBy("mLock")
    public void onUidBiasChangedLocked(int uid, int prevBias, int newBias) {
        if (prevBias == JobInfo.BIAS_TOP_APP || newBias == JobInfo.BIAS_TOP_APP) {
            maybeReportNewChargingStateLocked();
        }
    }

    @GuardedBy("mLock")
    private boolean hasTopExemptionLocked(@NonNull JobStatus taskStatus) {
        return mService.getUidBias(taskStatus.getSourceUid()) == JobInfo.BIAS_TOP_APP
                || mTopStartedJobs.contains(taskStatus);
    }

    @GuardedBy("mLock")
    private void maybeReportNewChargingStateLocked() {
        final boolean powerConnected = mPowerTracker.isPowerConnected();
        final boolean stablePower = mService.isBatteryCharging() && mService.isBatteryNotLow();
        final boolean batteryNotLow = mService.isBatteryNotLow();
        if (DEBUG) {
            Slog.d(TAG, "maybeReportNewChargingStateLocked: "
                    + powerConnected + "/" + stablePower + "/" + batteryNotLow);
        }

        if (mLastReportedStatsdStablePower == null
                || mLastReportedStatsdStablePower != stablePower) {
            logDeviceWideConstraintStateToStatsd(JobStatus.CONSTRAINT_CHARGING, stablePower);
            mLastReportedStatsdStablePower = stablePower;
        }
        if (mLastReportedStatsdBatteryNotLow == null
                || mLastReportedStatsdBatteryNotLow != batteryNotLow) {
            logDeviceWideConstraintStateToStatsd(JobStatus.CONSTRAINT_BATTERY_NOT_LOW,
                    batteryNotLow);
            mLastReportedStatsdBatteryNotLow = batteryNotLow;
        }

        final long nowElapsed = sElapsedRealtimeClock.millis();

        mFlexibilityController.setConstraintSatisfied(
                JobStatus.CONSTRAINT_CHARGING, mService.isBatteryCharging(), nowElapsed);
        mFlexibilityController.setConstraintSatisfied(
                JobStatus.CONSTRAINT_BATTERY_NOT_LOW, batteryNotLow, nowElapsed);

        for (int i = mTrackedTasks.size() - 1; i >= 0; i--) {
            final JobStatus ts = mTrackedTasks.valueAt(i);
            if (ts.hasChargingConstraint()) {
                if (hasTopExemptionLocked(ts)
                        && ts.getEffectivePriority() >= JobInfo.PRIORITY_DEFAULT) {
                    // If the job started while the app was on top or the app is currently on top,
                    // let the job run as long as there's power connected, even if the device isn't
                    // officially charging.
                    // For user requested/initiated jobs, users may be confused when the task stops
                    // running even though the device is plugged in.
                    // Low priority jobs don't need to be exempted.
                    if (ts.setChargingConstraintSatisfied(nowElapsed, powerConnected)) {
                        mChangedJobs.add(ts);
                    }
                } else if (ts.setChargingConstraintSatisfied(nowElapsed, stablePower)) {
                    mChangedJobs.add(ts);
                }
            }
            if (ts.hasBatteryNotLowConstraint()
                    && ts.setBatteryNotLowConstraintSatisfied(nowElapsed, batteryNotLow)) {
                mChangedJobs.add(ts);
            }
        }
        if (stablePower || batteryNotLow) {
            // If one of our conditions has been satisfied, always schedule any newly ready jobs.
            mStateChangedListener.onRunJobNow(null);
        } else if (mChangedJobs.size() > 0) {
            // Otherwise, just let the job scheduler know the state has changed and take care of it
            // as it thinks is best.
            mStateChangedListener.onControllerStateChanged(mChangedJobs);
        }
        mChangedJobs.clear();
    }

    private final class PowerTracker extends BroadcastReceiver {
        /**
         * Track whether there is power connected. It doesn't mean the device is charging.
         * Use {@link JobSchedulerService#isBatteryCharging()} to determine if the device is
         * charging.
         */
        private boolean mPowerConnected;

        PowerTracker() {
        }

        void startTracking() {
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            mContext.registerReceiver(this, filter);

            // Initialize tracker state.
            BatteryManagerInternal batteryManagerInternal =
                    LocalServices.getService(BatteryManagerInternal.class);
            mPowerConnected = batteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
        }

        boolean isPowerConnected() {
            return mPowerConnected;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                final String action = intent.getAction();

                if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Power connected @ " + sElapsedRealtimeClock.millis());
                    }
                    if (mPowerConnected) {
                        return;
                    }
                    mPowerConnected = true;
                } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Power disconnected @ " + sElapsedRealtimeClock.millis());
                    }
                    if (!mPowerConnected) {
                        return;
                    }
                    mPowerConnected = false;
                }

                maybeReportNewChargingStateLocked();
            }
        }
    }

    @VisibleForTesting
    ArraySet<JobStatus> getTrackedJobs() {
        return mTrackedTasks;
    }

    @VisibleForTesting
    ArraySet<JobStatus> getTopStartedJobs() {
        return mTopStartedJobs;
    }

    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
        pw.println("Power connected: " + mPowerTracker.isPowerConnected());
        pw.println("Stable power: " + (mService.isBatteryCharging() && mService.isBatteryNotLow()));
        pw.println("Not low: " + mService.isBatteryNotLow());

        for (int i = 0; i < mTrackedTasks.size(); i++) {
            final JobStatus js = mTrackedTasks.valueAt(i);
            if (!predicate.test(js)) {
                continue;
            }
            pw.print("#");
            js.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, js.getSourceUid());
            pw.println();
        }
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.BATTERY);

        proto.write(StateControllerProto.BatteryController.IS_ON_STABLE_POWER,
                mService.isBatteryCharging() && mService.isBatteryNotLow());
        proto.write(StateControllerProto.BatteryController.IS_BATTERY_NOT_LOW,
                mService.isBatteryNotLow());

        for (int i = 0; i < mTrackedTasks.size(); i++) {
            final JobStatus js = mTrackedTasks.valueAt(i);
            if (!predicate.test(js)) {
                continue;
            }
            final long jsToken = proto.start(StateControllerProto.BatteryController.TRACKED_JOBS);
            js.writeToShortProto(proto, StateControllerProto.BatteryController.TrackedJob.INFO);
            proto.write(StateControllerProto.BatteryController.TrackedJob.SOURCE_UID,
                    js.getSourceUid());
            proto.end(jsToken);
        }

        proto.end(mToken);
        proto.end(token);
    }
}
