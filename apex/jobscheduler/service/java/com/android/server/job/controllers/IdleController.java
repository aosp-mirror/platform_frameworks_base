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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;
import com.android.server.job.controllers.idle.CarIdlenessTracker;
import com.android.server.job.controllers.idle.DeviceIdlenessTracker;
import com.android.server.job.controllers.idle.IdlenessListener;
import com.android.server.job.controllers.idle.IdlenessTracker;

import java.util.function.Predicate;

/**
 * Simple controller that tracks whether the device is idle or not. Idleness depends on the device
 * type and is not related to device-idle (Doze mode) despite the similar naming.
 *
 * @see CarIdlenessTracker
 * @see DeviceIdlenessTracker
 * @see IdlenessTracker
 */
public final class IdleController extends RestrictingController implements IdlenessListener {
    private static final String TAG = "JobScheduler.IdleController";
    // Policy: we decide that we're "idle" if the device has been unused /
    // screen off or dreaming or wireless charging dock idle for at least this long
    final ArraySet<JobStatus> mTrackedTasks = new ArraySet<>();
    IdlenessTracker mIdleTracker;

    public IdleController(JobSchedulerService service) {
        super(service);
        initIdleStateTracking(mContext);
    }

    /**
     * StateController interface
     */
    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasIdleConstraint()) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_IDLE);
            taskStatus.setIdleConstraintSatisfied(nowElapsed, mIdleTracker.isIdle());
        }
    }

    @Override
    public void startTrackingRestrictedJobLocked(JobStatus jobStatus) {
        maybeStartTrackingJobLocked(jobStatus, null);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_IDLE)) {
            mTrackedTasks.remove(taskStatus);
        }
    }

    @Override
    public void stopTrackingRestrictedJobLocked(JobStatus jobStatus) {
        if (!jobStatus.hasIdleConstraint()) {
            maybeStopTrackingJobLocked(jobStatus, null, false);
        }
    }

    /**
     * State-change notifications from the idleness tracker
     */
    @Override
    public void reportNewIdleState(boolean isIdle) {
        synchronized (mLock) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            for (int i = mTrackedTasks.size()-1; i >= 0; i--) {
                mTrackedTasks.valueAt(i).setIdleConstraintSatisfied(nowElapsed, isIdle);
            }
        }
        mStateChangedListener.onControllerStateChanged();
    }

    /**
     * Idle state tracking, and messaging with the task manager when
     * significant state changes occur
     */
    private void initIdleStateTracking(Context ctx) {
        final boolean isCar = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE);
        if (isCar) {
            mIdleTracker = new CarIdlenessTracker();
        } else {
            mIdleTracker = new DeviceIdlenessTracker();
        }
        mIdleTracker.startTracking(ctx, this);
    }

    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
        pw.println("Currently idle: " + mIdleTracker.isIdle());
        pw.println("Idleness tracker:"); mIdleTracker.dump(pw);
        pw.println();

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
        final long mToken = proto.start(StateControllerProto.IDLE);

        proto.write(StateControllerProto.IdleController.IS_IDLE, mIdleTracker.isIdle());
        mIdleTracker.dump(proto, StateControllerProto.IdleController.IDLENESS_TRACKER);

        for (int i = 0; i < mTrackedTasks.size(); i++) {
            final JobStatus js = mTrackedTasks.valueAt(i);
            if (!predicate.test(js)) {
                continue;
            }
            final long jsToken = proto.start(StateControllerProto.IdleController.TRACKED_JOBS);
            js.writeToShortProto(proto, StateControllerProto.IdleController.TrackedJob.INFO);
            proto.write(StateControllerProto.IdleController.TrackedJob.SOURCE_UID,
                    js.getSourceUid());
            proto.end(jsToken);
        }

        proto.end(mToken);
        proto.end(token);
    }
}
