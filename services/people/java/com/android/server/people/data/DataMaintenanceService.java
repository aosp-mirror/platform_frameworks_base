/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import android.annotation.UserIdInt;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.CancellationSignal;

import com.android.server.LocalServices;
import com.android.server.people.PeopleServiceInternal;

import java.util.concurrent.TimeUnit;

/**
 * This service runs periodically to ensure the data consistency and the retention policy for a
 * specific user's data.
 */
public class DataMaintenanceService extends JobService {

    private static final long JOB_RUN_INTERVAL = TimeUnit.HOURS.toMillis(24);

    /** This job ID must be unique within the system server. */
    private static final int BASE_JOB_ID = 0xC315BD7;  // 204561367

    static void scheduleJob(Context context, @UserIdInt int userId) {
        int jobId = getJobId(userId);
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler.getPendingJob(jobId) == null) {
            ComponentName component = new ComponentName(context, DataMaintenanceService.class);
            JobInfo newJob = new JobInfo.Builder(jobId, component)
                    .setRequiresDeviceIdle(true)
                    .setPeriodic(JOB_RUN_INTERVAL)
                    .build();
            jobScheduler.schedule(newJob);
        }
    }

    static void cancelJob(Context context, @UserIdInt int userId) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(getJobId(userId));
    }

    private CancellationSignal mSignal;

    @Override
    public boolean onStartJob(JobParameters params) {
        int userId = getUserId(params.getJobId());
        mSignal = new CancellationSignal();
        new Thread(() -> {
            PeopleServiceInternal peopleServiceInternal =
                    LocalServices.getService(PeopleServiceInternal.class);
            peopleServiceInternal.pruneDataForUser(userId, mSignal);
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

    private static int getJobId(@UserIdInt int userId) {
        return BASE_JOB_ID + userId;
    }

    private static @UserIdInt int getUserId(int jobId) {
        return jobId - BASE_JOB_ID;
    }
}
