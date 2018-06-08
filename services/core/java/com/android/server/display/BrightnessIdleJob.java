/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.display;


import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.util.Slog;

import com.android.server.LocalServices;

import java.util.concurrent.TimeUnit;

/**
 * JobService used to persists brightness slider events when the device
 * is idle and charging.
 */
public class BrightnessIdleJob extends JobService {

    // Must be unique within the system server uid.
    private static final int JOB_ID = 3923512;

    public static void scheduleJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);

        JobInfo pending = jobScheduler.getPendingJob(JOB_ID);
        JobInfo jobInfo =
                new JobInfo.Builder(JOB_ID, new ComponentName(context, BrightnessIdleJob.class))
                        .setRequiresDeviceIdle(true)
                        .setRequiresCharging(true)
                        .setPeriodic(TimeUnit.HOURS.toMillis(24)).build();

        if (pending != null && !pending.equals(jobInfo)) {
            jobScheduler.cancel(JOB_ID);
            pending = null;
        }

        if (pending == null) {
            jobScheduler.schedule(jobInfo);
        }
    }

    public static void cancelJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (BrightnessTracker.DEBUG) {
            Slog.d(BrightnessTracker.TAG, "Scheduled write of brightness events");
        }
        DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
        dmi.persistBrightnessTrackerState();
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}