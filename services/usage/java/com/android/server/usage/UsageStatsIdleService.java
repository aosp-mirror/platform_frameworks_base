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

import java.util.concurrent.TimeUnit;

/**
 * JobService used to do any work for UsageStats while the device is idle.
 */
public class UsageStatsIdleService extends JobService {

    /**
     * Base job ID for the pruning job - must be unique within the system server uid.
     */
    private static final int PRUNE_JOB_ID = 546357475;
    /**
     * Job ID for the update mappings job - must be unique within the system server uid.
     * Incrementing PRUNE_JOB_ID by 21475 (MAX_USER_ID) to ensure there is no overlap in job ids.
     */
    private static final int UPDATE_MAPPINGS_JOB_ID = 546378950;

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

        scheduleJobInternal(context, pruneJob, userJobId);
    }

    static void scheduleUpdateMappingsJob(Context context) {
        final ComponentName component = new ComponentName(context.getPackageName(),
                UsageStatsIdleService.class.getName());
        final JobInfo updateMappingsJob = new JobInfo.Builder(UPDATE_MAPPINGS_JOB_ID, component)
                .setPersisted(true)
                .setMinimumLatency(TimeUnit.DAYS.toMillis(1))
                .setOverrideDeadline(TimeUnit.DAYS.toMillis(2))
                .build();

        scheduleJobInternal(context, updateMappingsJob, UPDATE_MAPPINGS_JOB_ID);
    }

    private static void scheduleJobInternal(Context context, JobInfo pruneJob, int jobId) {
        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        final JobInfo pendingPruneJob = jobScheduler.getPendingJob(jobId);
        // only schedule a new prune job if one doesn't exist already for this user
        if (!pruneJob.equals(pendingPruneJob)) {
            jobScheduler.cancel(jobId); // cancel any previously scheduled prune job
            jobScheduler.schedule(pruneJob);
        }
    }

    static void cancelJob(Context context, int userId) {
        cancelJobInternal(context, PRUNE_JOB_ID + userId);
    }

    static void cancelUpdateMappingsJob(Context context) {
        cancelJobInternal(context, UPDATE_MAPPINGS_JOB_ID);
    }

    private static void cancelJobInternal(Context context, int jobId) {
        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler != null) {
            jobScheduler.cancel(jobId);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final PersistableBundle bundle = params.getExtras();
        final int userId = bundle.getInt(USER_ID_KEY, -1);
        if (userId == -1 && params.getJobId() != UPDATE_MAPPINGS_JOB_ID) {
            return false;
        }

        AsyncTask.execute(() -> {
            final UsageStatsManagerInternal usageStatsManagerInternal = LocalServices.getService(
                    UsageStatsManagerInternal.class);
            if (params.getJobId() == UPDATE_MAPPINGS_JOB_ID) {
                final boolean jobFinished = usageStatsManagerInternal.updatePackageMappingsData();
                jobFinished(params, !jobFinished); // reschedule if data was not updated
            } else {
                final boolean jobFinished =
                        usageStatsManagerInternal.pruneUninstalledPackagesData(userId);
                jobFinished(params, !jobFinished); // reschedule if data was not pruned
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Since the pruning job isn't a heavy job, we don't want to cancel it's execution midway.
        return false;
    }
}
