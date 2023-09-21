/**
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.util.Calendar;

/**
 * This service runs everyday at 2am local time to remove expired bitmaps.
 */
public class NotificationBitmapJobService extends JobService {
    static final String TAG = "NotificationBitmapJob";

    static final int BASE_JOB_ID = 290381858; // Feature bug id

    static void scheduleJob(Context context) {
        if (context == null) {
            return;
        }
        try {
            JobScheduler jobScheduler =
                    context.getSystemService(JobScheduler.class).forNamespace(TAG);

            ComponentName component =
                    new ComponentName(context, NotificationBitmapJobService.class);

            JobInfo jobInfo = new JobInfo.Builder(BASE_JOB_ID, component)
                    .setRequiresDeviceIdle(true)
                    .setMinimumLatency(getRunAfterMs())
                    .build();

            final int result = jobScheduler.schedule(jobInfo);
            if (result != RESULT_SUCCESS) {
                Slog.e(TAG, "Failed to schedule bitmap removal job");
            }

        } catch (Throwable e) {
            Slog.wtf(TAG, "Failed bitmap removal job", e);
        }
    }

    /**
     * @return Milliseconds until the next time the job should run.
     */
    private static long getRunAfterMs() {
        Calendar cal = Calendar.getInstance();  // Uses local time zone
        final long now = cal.getTimeInMillis();

        cal.set(Calendar.HOUR_OF_DAY, 2);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.MILLISECOND, 0);
        final long today2AM = cal.getTimeInMillis();

        cal.add(Calendar.DAY_OF_YEAR, 1);
        final long tomorrow2AM = cal.getTimeInMillis();

        return getTimeUntilRemoval(now, today2AM, tomorrow2AM);
    }

    @VisibleForTesting
    static long getTimeUntilRemoval(long now, long today2AM, long tomorrow2AM) {
        if (now < today2AM) {
            return today2AM - now;
        }
        return tomorrow2AM - now;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            NotificationManagerInternal nmInternal =
                    LocalServices.getService(NotificationManagerInternal.class);
            nmInternal.removeBitmaps();

            // Schedule the next job here, since we cannot use setPeriodic and setMinimumLatency
            // together when creating JobInfo.
            scheduleJob(/* context= */ this);

            jobFinished(params, /* wantsReschedule= */ false);
        }).start();

        return true;  // This service continues to run in a separate thread.
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // No need to keep this job alive since the original thread is going to keep running and
        // call scheduleJob at the end of its execution.
        return false;
    }

    @Override
    @VisibleForTesting
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }
}