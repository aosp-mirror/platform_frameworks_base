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

import android.annotation.UserIdInt;
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
     * Namespace for prune job
     */
    private static final String PRUNE_JOB_NS = "usagestats_prune";

    /**
     * Namespace for update mappings job
     */
    private static final String UPDATE_MAPPINGS_JOB_NS = "usagestats_mapping";

    private static final String USER_ID_KEY = "user_id";

    /** Schedule a prune job */
    static void schedulePruneJob(Context context, @UserIdInt int userId) {
        final ComponentName component = new ComponentName(context.getPackageName(),
                UsageStatsIdleService.class.getName());
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(USER_ID_KEY, userId);
        final JobInfo pruneJob = new JobInfo.Builder(userId, component)
                .setRequiresDeviceIdle(true)
                .setExtras(bundle)
                .setPersisted(true)
                .build();

        scheduleJobInternal(context, pruneJob, PRUNE_JOB_NS, userId);
    }

    static void scheduleUpdateMappingsJob(Context context, @UserIdInt int userId) {
        final ComponentName component = new ComponentName(context.getPackageName(),
                UsageStatsIdleService.class.getName());
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(USER_ID_KEY, userId);
        final JobInfo updateMappingsJob = new JobInfo.Builder(userId, component)
                .setPersisted(true)
                .setMinimumLatency(TimeUnit.DAYS.toMillis(1))
                .setOverrideDeadline(TimeUnit.DAYS.toMillis(2))
                .setExtras(bundle)
                .build();

        scheduleJobInternal(context, updateMappingsJob, UPDATE_MAPPINGS_JOB_NS, userId);
    }

    private static void scheduleJobInternal(Context context, JobInfo jobInfo,
            String namespace, int jobId) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler = jobScheduler.forNamespace(namespace);
        final JobInfo pendingJob = jobScheduler.getPendingJob(jobId);
        // only schedule a new job if one doesn't exist already for this user
        if (!jobInfo.equals(pendingJob)) {
            jobScheduler.cancel(jobId); // cancel any previously scheduled job
            jobScheduler.schedule(jobInfo);
        }
    }

    static void cancelPruneJob(Context context, @UserIdInt int userId) {
        cancelJobInternal(context, PRUNE_JOB_NS, userId);
    }

    static void cancelUpdateMappingsJob(Context context, @UserIdInt int userId) {
        cancelJobInternal(context, UPDATE_MAPPINGS_JOB_NS, userId);
    }

    private static void cancelJobInternal(Context context, String namespace, int jobId) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler != null) {
            jobScheduler = jobScheduler.forNamespace(namespace);
            jobScheduler.cancel(jobId);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final PersistableBundle bundle = params.getExtras();
        final int userId = bundle.getInt(USER_ID_KEY, -1);

        if (userId == -1) { // legacy job
            return false;
        }

        // Do async
        AsyncTask.execute(() -> {
            final UsageStatsManagerInternal usageStatsManagerInternal = LocalServices.getService(
                    UsageStatsManagerInternal.class);
            final String jobNs = params.getJobNamespace();
            if (UPDATE_MAPPINGS_JOB_NS.equals(jobNs)) {
                final boolean jobFinished =
                        usageStatsManagerInternal.updatePackageMappingsData(userId);
                jobFinished(params, !jobFinished); // reschedule if data was not updated
            } else {
                final boolean jobFinished =
                        usageStatsManagerInternal.pruneUninstalledPackagesData(userId);
                jobFinished(params, !jobFinished); // reschedule if data was not pruned
            }
        });

        // Job is running asynchronously
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Since the pruning job isn't a heavy job, we don't want to cancel it's execution midway.
        return false;
    }
}
