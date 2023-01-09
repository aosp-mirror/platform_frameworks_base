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

package com.android.server.job;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.util.ArraySet;

import com.android.server.job.controllers.JobStatus;
import com.android.server.job.restrictions.JobRestriction;

import java.util.List;

/**
 * Interface through which a {@link com.android.server.job.controllers.StateController} informs
 * the {@link com.android.server.job.JobSchedulerService} that there are some tasks potentially
 * ready to be run.
 */
public interface StateChangedListener {
    /**
     * Called by the controller to notify the JobScheduler that it should check on the state of a
     * set of jobs. If {@code changedJobs} is null, then all registered jobs will be evaluated.
     */
    void onControllerStateChanged(@Nullable ArraySet<JobStatus> changedJobs);

    /**
     * Called by a {@link com.android.server.job.restrictions.JobRestriction} to notify the
     * JobScheduler that it should check on the state of all jobs.
     *
     * @param stopOvertimeJobs Whether to stop any jobs that have run for more than their minimum
     *                         execution guarantee and are restricted by the changed restriction
     */
    void onRestrictionStateChanged(@NonNull JobRestriction restriction,
            boolean stopOvertimeJobs);

    /**
     * Called by the controller to notify the JobManager that regardless of the state of the task,
     * it must be run immediately.
     * @param jobStatus The state of the task which is to be run immediately. <strong>null
     *                  indicates to the scheduler that any ready jobs should be flushed.</strong>
     */
    public void onRunJobNow(JobStatus jobStatus);

    public void onDeviceIdleStateChanged(boolean deviceIdle);

    void onNetworkChanged(JobStatus jobStatus, Network newNetwork);

    /**
     * Called when these jobs are added or removed from the
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket.
     */
    void onRestrictedBucketChanged(@NonNull List<JobStatus> jobs);
}
