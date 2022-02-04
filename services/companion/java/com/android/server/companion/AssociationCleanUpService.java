/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.companion;

import static com.android.server.companion.CompanionDeviceManagerService.LOG_TAG;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.companion.AssociationRequest;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Slog;

import com.android.server.LocalServices;

/**
 * A Job Service responsible for clean up the Association.
 * The job will be executed only if the device is charging and in idle mode due to the application
 * will be killed if association/role are revoked.
 */
public class AssociationCleanUpService extends JobService {
    private static final int JOB_ID = AssociationCleanUpService.class.hashCode();
    private static final long ONE_DAY_INTERVAL = 3 * 24 * 60 * 60 * 1000; // 1 Day
    private CompanionDeviceManagerServiceInternal mCdmServiceInternal = LocalServices.getService(
            CompanionDeviceManagerServiceInternal.class);

    @Override
    public boolean onStartJob(final JobParameters params) {
        Slog.i(LOG_TAG, "Execute the Association CleanUp job");
        // Special policy for APP_STREAMING role that need to revoke associations if the device
        // does not connect for 3 months.
        AsyncTask.execute(() -> {
            mCdmServiceInternal.associationCleanUp(AssociationRequest.DEVICE_PROFILE_APP_STREAMING);
            jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        Slog.i(LOG_TAG, "Association cleanup job stopped; id=" + params.getJobId()
                + ", reason="
                + JobParameters.getInternalReasonCodeDescription(
                params.getInternalStopReasonCode()));
        return false;
    }

    static void schedule(Context context) {
        Slog.i(LOG_TAG, "Scheduling the Association Cleanup job");
        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        final JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, AssociationCleanUpService.class))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPeriodic(ONE_DAY_INTERVAL)
                .build();
        jobScheduler.schedule(job);
    }
}
