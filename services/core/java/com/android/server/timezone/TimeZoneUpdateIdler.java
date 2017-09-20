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

package com.android.server.timezone;

import com.android.server.LocalServices;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

/**
 * A JobService used to trigger time zone rules update work when a device falls idle.
 */
public final class TimeZoneUpdateIdler extends JobService {

    private static final String TAG = "timezone.TimeZoneUpdateIdler";

    /** The static job ID used to handle on-idle work. */
    // Must be unique within UID (system service)
    private static final int TIME_ZONE_UPDATE_IDLE_JOB_ID = 27042305;

    @Override
    public boolean onStartJob(JobParameters params) {
        RulesManagerService rulesManagerService =
                LocalServices.getService(RulesManagerService.class);

        Slog.d(TAG, "onStartJob() called");

        // Note: notifyIdle() explicitly handles canceling / re-scheduling so no need to reschedule
        // here.
        rulesManagerService.notifyIdle();

        // Everything is handled synchronously. We are done.
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Reschedule if stopped unless it was cancelled due to unschedule().
        boolean reschedule = params.getStopReason() != JobParameters.REASON_CANCELED;
        Slog.d(TAG, "onStopJob() called: Reschedule=" + reschedule);
        return reschedule;
    }

    /**
     * Schedules the TimeZoneUpdateIdler job service to run once.
     *
     * @param context Context to use to get a job scheduler.
     */
    public static void schedule(Context context, long minimumDelayMillis) {
        // Request that the JobScheduler tell us when the device falls idle.
        JobScheduler jobScheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        // The TimeZoneUpdateIdler will send an intent that will trigger the Receiver.
        ComponentName idlerJobServiceName =
                new ComponentName(context, TimeZoneUpdateIdler.class);

        // We require the device is idle, but also that it is charging to be as non-invasive as
        // we can.
        JobInfo.Builder jobInfoBuilder =
                new JobInfo.Builder(TIME_ZONE_UPDATE_IDLE_JOB_ID, idlerJobServiceName)
                        .setRequiresDeviceIdle(true)
                        .setRequiresCharging(true)
                        .setMinimumLatency(minimumDelayMillis);

        Slog.d(TAG, "schedule() called: minimumDelayMillis=" + minimumDelayMillis);
        jobScheduler.schedule(jobInfoBuilder.build());
    }

    /**
     * Unschedules the TimeZoneUpdateIdler job service.
     *
     * @param context Context to use to get a job scheduler.
     */
    public static void unschedule(Context context) {
        JobScheduler jobScheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        Slog.d(TAG, "unschedule() called");
        jobScheduler.cancel(TIME_ZONE_UPDATE_IDLE_JOB_ID);
    }
}
