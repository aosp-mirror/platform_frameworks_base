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
    private static final String REMOVED_TOKENS_KEY = "removed_tokens";

    static void scheduleJob(Context context, int userId, int token) {
        final int userJobId = PRUNE_JOB_ID + userId; // unique job id per user
        final ComponentName component = new ComponentName(context.getPackageName(),
                UsageStatsIdleService.class.getName());
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(USER_ID_KEY, userId);
        bundle.putIntArray(REMOVED_TOKENS_KEY,
                getOrCreateRemovedTokens(fetchRemovedTokens(context, userId), token));
        final JobInfo pruneJob = new JobInfo.Builder(userJobId, component)
                .setRequiresDeviceIdle(true)
                .setExtras(bundle)
                .setPersisted(true)
                .build();

        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.schedule(pruneJob);
    }

    static void cancelJob(Context context, int userId) {
        final int userJobId = PRUNE_JOB_ID + userId; // unique job id per user
        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(userJobId);
    }

    /**
     * Fetches an array of removed tokens from previous prune jobs, if any.
     */
    static int[] fetchRemovedTokens(Context context, int userId) {
        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        final int userJobId = PRUNE_JOB_ID + userId; // unique job id per user
        final JobInfo pendingJob = jobScheduler.getPendingJob(userJobId);
        if (pendingJob != null) {
            final PersistableBundle bundle = pendingJob.getExtras();
            return bundle.getIntArray(REMOVED_TOKENS_KEY);
        }
        return null;
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

    /**
     * Helper method to create a cloned array of removed tokens from previous jobs (if any) with
     * the newly removed token at index 0. If there are no removed tokens from previous jobs, it
     * simply returns a new array containing the current token.
     */
    private static int[] getOrCreateRemovedTokens(int[] previousRemovedTokens, int token) {
        final int[] removedTokens;
        if (previousRemovedTokens == null) {
            removedTokens = new int[1];
        } else {
            removedTokens = new int[previousRemovedTokens.length + 1];
            System.arraycopy(previousRemovedTokens, 0, removedTokens, 1,
                    previousRemovedTokens.length);
        }
        removedTokens[0] = token;
        return removedTokens;
    }
}
