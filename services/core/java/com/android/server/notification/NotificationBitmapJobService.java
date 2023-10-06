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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;

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
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = Instant.now().atZone(zoneId);

        LocalDate today = now.toLocalDate();
        LocalTime twoAM = LocalTime.of(/* hour= */ 2, /* minute= */ 0);

        ZonedDateTime today2AM = ZonedDateTime.of(today, twoAM, zoneId);
        ZonedDateTime tomorrow2AM = today2AM.plusDays(1);

        return getTimeUntilRemoval(now, today2AM, tomorrow2AM);
    }

    @VisibleForTesting
    static long getTimeUntilRemoval(ZonedDateTime now, ZonedDateTime today2AM,
                                    ZonedDateTime tomorrow2AM) {
        if (Duration.between(now, today2AM).isNegative()) {
            return Duration.between(now, tomorrow2AM).toMillis();
        }
        return Duration.between(now, today2AM).toMillis();
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