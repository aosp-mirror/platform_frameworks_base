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

import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.JobSchedulerBackgroundThread;
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

    private final ArraySet<JobStatus> mTrackedTasks = new ArraySet<>();

    public BatteryController(JobSchedulerService service) {
        super(service);
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasPowerConstraint()) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_BATTERY);
            taskStatus.setChargingConstraintSatisfied(nowElapsed,
                    mService.isBatteryCharging() && mService.isBatteryNotLow());
            taskStatus.setBatteryNotLowConstraintSatisfied(nowElapsed, mService.isBatteryNotLow());
        }
    }

    @Override
    public void startTrackingRestrictedJobLocked(JobStatus jobStatus) {
        maybeStartTrackingJobLocked(jobStatus, null);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob, boolean forUpdate) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_BATTERY)) {
            mTrackedTasks.remove(taskStatus);
        }
    }

    @Override
    public void stopTrackingRestrictedJobLocked(JobStatus jobStatus) {
        if (!jobStatus.hasPowerConstraint()) {
            maybeStopTrackingJobLocked(jobStatus, null, false);
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onBatteryStateChangedLocked() {
        // Update job bookkeeping out of band.
        JobSchedulerBackgroundThread.getHandler().post(() -> {
            synchronized (mLock) {
                maybeReportNewChargingStateLocked();
            }
        });
    }

    @GuardedBy("mLock")
    private void maybeReportNewChargingStateLocked() {
        final boolean stablePower = mService.isBatteryCharging() && mService.isBatteryNotLow();
        final boolean batteryNotLow = mService.isBatteryNotLow();
        if (DEBUG) {
            Slog.d(TAG, "maybeReportNewChargingStateLocked: " + stablePower);
        }
        final long nowElapsed = sElapsedRealtimeClock.millis();
        boolean reportChange = false;
        for (int i = mTrackedTasks.size() - 1; i >= 0; i--) {
            final JobStatus ts = mTrackedTasks.valueAt(i);
            reportChange |= ts.setChargingConstraintSatisfied(nowElapsed, stablePower);
            reportChange |= ts.setBatteryNotLowConstraintSatisfied(nowElapsed, batteryNotLow);
        }
        if (stablePower || batteryNotLow) {
            // If one of our conditions has been satisfied, always schedule any newly ready jobs.
            mStateChangedListener.onRunJobNow(null);
        } else if (reportChange) {
            // Otherwise, just let the job scheduler know the state has changed and take care of it
            // as it thinks is best.
            mStateChangedListener.onControllerStateChanged(mTrackedTasks);
        }
    }

    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
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
