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

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

/**
 * JobService implementation for scheduling the notification informing users about
 * notification permissions updates and taking them to review their existing permissions.
 * @hide
 */
public class ReviewNotificationPermissionsJobService extends JobService {
    public static final String TAG = "ReviewNotificationPermissionsJobService";

    @VisibleForTesting
    protected static final int JOB_ID = 225373531;

    /**
     *  Schedule a new job that will show a notification the specified amount of time in the future.
     */
    public static void scheduleJob(Context context, long rescheduleTimeMillis) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        // if the job already exists for some reason, cancel & reschedule
        if (jobScheduler.getPendingJob(JOB_ID) != null) {
            jobScheduler.cancel(JOB_ID);
        }
        ComponentName component = new ComponentName(
                context, ReviewNotificationPermissionsJobService.class);
        JobInfo newJob = new JobInfo.Builder(JOB_ID, component)
                .setPersisted(true) // make sure it'll still get rescheduled after reboot
                .setMinimumLatency(rescheduleTimeMillis)  // run after specified amount of time
                .build();
        jobScheduler.schedule(newJob);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // While jobs typically should be run on different threads, this
        // job only posts a notification, which is not a long-running operation
        // as notification posting is asynchronous.
        NotificationManagerInternal nmi =
                LocalServices.getService(NotificationManagerInternal.class);
        nmi.sendReviewPermissionsNotification();

        // once the notification is posted, the job is done, so no need to
        // keep it alive afterwards
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // If we're interrupted for some reason, try again (though this may not
        // ever happen due to onStartJob not leaving a job running after being
        // called)
        return true;
    }
}
