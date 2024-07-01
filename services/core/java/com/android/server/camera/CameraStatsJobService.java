/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.camera;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

import com.android.server.LocalServices;

import java.util.concurrent.TimeUnit;

/**
 * A JobService to periodically collect camera usage stats.
 */
public class CameraStatsJobService extends JobService {
    private static final String TAG = "CameraStatsJobService";

    // Must be unique within UID (system service)
    private static final int CAMERA_REPORTING_JOB_ID = 0xCA3E7A;

    private static ComponentName sCameraStatsJobServiceName = new ComponentName(
            "android",
            CameraStatsJobService.class.getName());

    @Override
    public boolean onStartJob(JobParameters params) {
        CameraServiceProxy serviceProxy = LocalServices.getService(CameraServiceProxy.class);
        if (serviceProxy == null) {
            Slog.w(TAG, "Can't collect camera usage stats - no camera service proxy found");
            return false;
        }

        serviceProxy.dumpCameraEvents();
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // All work is done in onStartJob, so nothing to stop here
        return false;
    }

    public static void schedule(Context context) {

        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            Slog.e(TAG, "Can't collect camera usage stats - no Job Scheduler");
            return;
        }
        js.schedule(new JobInfo.Builder(CAMERA_REPORTING_JOB_ID, sCameraStatsJobServiceName)
                .setMinimumLatency(TimeUnit.DAYS.toMillis(1))
                .setRequiresDeviceIdle(true)
                .build());

    }

}
