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

import static com.android.server.job.JobSchedulerService.DEBUG;

import android.annotation.NonNull;
import android.content.Context;
import android.provider.DeviceConfig;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.StateChangedListener;

import java.util.function.Predicate;

/**
 * Incorporates shared controller logic between the various controllers of the JobManager.
 * These are solely responsible for tracking a list of jobs, and notifying the JM when these
 * are ready to run, or whether they must be stopped.
 */
public abstract class StateController {
    private static final String TAG = "JobScheduler.SC";

    protected final JobSchedulerService mService;
    protected final StateChangedListener mStateChangedListener;
    protected final Context mContext;
    protected final Object mLock;
    protected final Constants mConstants;

    StateController(JobSchedulerService service) {
        mService = service;
        mStateChangedListener = service;
        mContext = service.getTestableContext();
        mLock = service.getLock();
        mConstants = service.getConstants();
    }

    /**
     * Called when the system boot phase has reached
     * {@link com.android.server.SystemService#PHASE_SYSTEM_SERVICES_READY}.
     */
    public void onSystemServicesReady() {
    }

    /**
     * Implement the logic here to decide whether a job should be tracked by this controller.
     * This logic is put here so the JobManager can be completely agnostic of Controller logic.
     * Also called when updating a task, so implementing controllers have to be aware of
     * preexisting tasks.
     */
    public abstract void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob);

    /**
     * Optionally implement logic here to prepare the job to be executed.
     */
    public void prepareForExecutionLocked(JobStatus jobStatus) {
    }

    /**
     * Optionally implement logic here for when a job that was about to be executed failed to start.
     */
    public void unprepareFromExecutionLocked(JobStatus jobStatus) {
    }

    /**
     * Remove task - this will happen if the task is cancelled, completed, etc.
     */
    public abstract void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate);

    /**
     * Called when a new job is being created to reschedule an old failed job.
     */
    public void rescheduleForFailureLocked(JobStatus newJob, JobStatus failureToReschedule) {
    }

    /** Notice that updated configuration constants are about to be read. */
    public void prepareForUpdatedConstantsLocked() {}

    /** Process the specified constant and update internal constants if relevant. */
    public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
            @NonNull String key) {}

    /**
     * Called when the JobScheduler.Constants are updated.
     */
    public void onConstantsUpdatedLocked() {
    }

    /** Called when a package is uninstalled from the device (not for an update). */
    public void onAppRemovedLocked(String packageName, int uid) {
    }

    /** Called when a user is added to the device. */
    public void onUserAddedLocked(int userId) {
    }

    /** Called when a user is removed from the device. */
    public void onUserRemovedLocked(int userId) {
    }

    /**
     * Called when JobSchedulerService has determined that the job is not ready to be run. The
     * Controller can evaluate if it can or should do something to promote this job's readiness.
     */
    public void evaluateStateLocked(JobStatus jobStatus) {
    }

    /**
     * Called when something with the UID has changed. The controller should re-evaluate any
     * internal state tracking dependent on this UID.
     */
    public void reevaluateStateLocked(int uid) {
    }

    /**
     * Called when a UID's base priority has changed. The more positive the priority, the more
     * important the UID is.
     */
    @GuardedBy("mLock")
    public void onUidPriorityChangedLocked(int uid, int newPriority) {
    }

    protected boolean wouldBeReadyWithConstraintLocked(JobStatus jobStatus, int constraint) {
        // This is very cheap to check (just a few conditions on data in JobStatus).
        final boolean jobWouldBeReady = jobStatus.wouldBeReadyWithConstraint(constraint);
        if (DEBUG) {
            Slog.v(TAG, "wouldBeReadyWithConstraintLocked: " + jobStatus.toShortString()
                    + " constraint=" + constraint
                    + " readyWithConstraint=" + jobWouldBeReady);
        }
        if (!jobWouldBeReady) {
            // If the job wouldn't be ready, nothing to do here.
            return false;
        }

        // This is potentially more expensive since JSS may have to query component
        // presence.
        return mService.areComponentsInPlaceLocked(jobStatus);
    }

    public abstract void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate);
    public abstract void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate);

    /** Dump any internal constants the Controller may have. */
    public void dumpConstants(IndentingPrintWriter pw) {
    }

    /** Dump any internal constants the Controller may have. */
    public void dumpConstants(ProtoOutputStream proto) {
    }
}
