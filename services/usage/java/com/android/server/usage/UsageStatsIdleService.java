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
 * limitations under the License.
 */
package com.android.server.usage;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.os.PersistableBundle;

import com.android.server.LocalServices;

/**
 * JobService used to do any work for UsageStats while the device is idle.
 */
public class UsageStatsIdleService extends JobService {

    /**
     * Base job ID for the pruning job - must be unique within the system server uid.
     */
    private static final int PRUNE_JOB_ID = 546357475;

    private static final String USER_ID_KEY = "user_id";

    static void scheduleJob(Context context, int userId) {
        final int userJobId = PRUNE_JOB_ID + userId; // unique job id per user
        final ComponentName component = new ComponentName(context.getPackageName(),
                UsageStatsIdleService.class.getName());
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(USER_ID_KEY, userId);
        final JobInfo pruneJob = new JobInfo.Builder(userJobId, component)
                .setRequiresDeviceIdle(true)
                .setExtras(bundle)
                .setPersisted(true)
                .build();

        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        final JobInfo pendingPruneJob = jobScheduler.getPendingJob(userJobId);
        // only schedule a new prune job if one doesn't exist already for this user
        if (!pruneJob.equals(pendingPruneJob)) {
            jobScheduler.cancel(userJobId); // cancel any previously scheduled prune job
            jobScheduler.schedule(pruneJob);
        }

    }

    static void cancelJob(Context context, int userId) {
        final int userJobId = PRUNE_JOB_ID + userId; // unique job id per user
        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(userJobId);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final PersistableBundle bundle = params.getExtras();
        final int userId = bundle.getInt(USER_ID_KEY, -1);
        if (userId == -1) {
            return false;
        }

        AsyncTask.execute(() -> {
            final UsageStatsManagerInternal usageStatsManagerInternal = LocalServices.getService(
                    UsageStatsManagerInternal.class);
            final boolean pruned = usageStatsManagerInternal.pruneUninstalledPackagesData(userId);
            jobFinished(params, !pruned); // reschedule if data was not pruned
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Since the pruning job isn't a heavy job, we don't want to cancel it's execution midway.
        return false;
    }
}
