/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;

import java.util.concurrent.TimeUnit;

public class PruneInstantAppsJobService extends JobService {
    private static final boolean DEBUG = false;

    private static final int JOB_ID = 765123;

    private static final long PRUNE_INSTANT_APPS_PERIOD_MILLIS = DEBUG
            ? TimeUnit.MINUTES.toMillis(1) : TimeUnit.DAYS.toMillis(1);

    public static void schedule(Context context) {
        JobInfo pruneJob = new JobInfo.Builder(JOB_ID, new ComponentName(
                context.getPackageName(), PruneInstantAppsJobService.class.getName()))
                .setRequiresDeviceIdle(true)
                .setPeriodic(PRUNE_INSTANT_APPS_PERIOD_MILLIS)
                .build();

        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.schedule(pruneJob);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.pruneInstantApps();
        jobFinished(params, false);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}