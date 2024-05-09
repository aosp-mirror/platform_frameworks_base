/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.job.restrictions;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.JobStatus;

/**
 * Used by {@link JobSchedulerService} to impose additional restrictions regarding whether jobs
 * should be scheduled or not based on the state of the system/device.
 * Every restriction is associated with exactly one stop reason, which could be retrieved using
 * {@link #getStopReason()}, one pending reason (retrievable via {@link #getPendingReason()},
 * (and the internal reason via {@link #getInternalReason()}).
 * Note, that this is not taken into account for the jobs that have
 * {@link JobInfo#BIAS_FOREGROUND_SERVICE} bias or higher.
 */
public abstract class JobRestriction {

    final JobSchedulerService mService;
    private final int mStopReason;
    private final int mPendingReason;
    private final int mInternalReason;

    protected JobRestriction(JobSchedulerService service, @JobParameters.StopReason int stopReason,
            @JobScheduler.PendingJobReason int pendingReason, int internalReason) {
        mService = service;
        mPendingReason = pendingReason;
        mStopReason = stopReason;
        mInternalReason = internalReason;
    }

    /**
     * Called when the system boot phase has reached
     * {@link com.android.server.SystemService#PHASE_SYSTEM_SERVICES_READY}.
     */
    public void onSystemServicesReady() {
    }

    /**
     * Called by {@link JobSchedulerService} to check if it may proceed with scheduling the job (in
     * case all constraints are satisfied and all other {@link JobRestriction JobRestrictions} are
     * fine with it).
     *
     * @param job to be checked
     * @param bias job bias to be checked
     * @return false if the {@link JobSchedulerService} should not schedule this job at the moment,
     * true - otherwise
     */
    public abstract boolean isJobRestricted(JobStatus job, int bias);

    /** Dump any internal constants the Restriction may have. */
    public abstract void dumpConstants(IndentingPrintWriter pw);

    /** Dump any internal constants the Restriction may have. */
    public void dumpConstants(ProtoOutputStream proto) {
    }

    @JobScheduler.PendingJobReason
    public final int getPendingReason() {
        return mPendingReason;
    }

    /** @return stop reason code for the Restriction. */
    @JobParameters.StopReason
    public final int getStopReason() {
        return mStopReason;
    }

    public final int getInternalReason() {
        return mInternalReason;
    }
}
