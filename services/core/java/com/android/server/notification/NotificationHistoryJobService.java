/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.job.JobScheduler.RESULT_SUCCESS;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.CancellationSignal;
import android.util.Slog;

import com.android.server.LocalServices;

import java.util.concurrent.TimeUnit;

/**
 * This service runs every twenty minutes to ensure the retention policy for notification history
 * data.
 */
public class NotificationHistoryJobService extends JobService {
    private final static String TAG = "NotificationHistoryJob";
    private static final long JOB_RUN_INTERVAL = TimeUnit.MINUTES.toMillis(20);

    static final int BASE_JOB_ID = 237039804;

    static void scheduleJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler.getPendingJob(BASE_JOB_ID) == null) {
            ComponentName component =
                    new ComponentName(context, NotificationHistoryJobService.class);
            JobInfo newJob = new JobInfo.Builder(BASE_JOB_ID, component)
                    .setRequiresDeviceIdle(false)
                    .setPeriodic(JOB_RUN_INTERVAL)
                    .build();
            if (jobScheduler.schedule(newJob) != RESULT_SUCCESS) {
                Slog.w(TAG, "Failed to schedule history cleanup job");
            }
        }
    }

    private CancellationSignal mSignal;

    @Override
    public boolean onStartJob(JobParameters params) {
        mSignal = new CancellationSignal();
        new Thread(() -> {
            NotificationManagerInternal nmInternal =
                    LocalServices.getService(NotificationManagerInternal.class);
            nmInternal.cleanupHistoryFiles();
            jobFinished(params, mSignal.isCanceled());
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mSignal != null) {
            mSignal.cancel();
        }
        return false;
    }
}

