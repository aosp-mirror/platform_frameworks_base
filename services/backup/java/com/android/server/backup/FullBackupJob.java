/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.backup;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

public class FullBackupJob extends JobService {
    private static final String USER_ID_EXTRA_KEY = "userId";

    @VisibleForTesting
    public static final int MIN_JOB_ID = 52418896;
    @VisibleForTesting
    public static final int MAX_JOB_ID = 52419896;

    private static ComponentName sIdleService =
            new ComponentName(PLATFORM_PACKAGE_NAME, FullBackupJob.class.getName());

    @GuardedBy("mParamsForUser")
    private final SparseArray<JobParameters> mParamsForUser = new SparseArray<>();

    public static void schedule(int userId, Context ctx, long minDelay,
            BackupManagerConstants constants) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo.Builder builder = new JobInfo.Builder(getJobIdForUserId(userId), sIdleService);
        synchronized (constants) {
            builder.setRequiresDeviceIdle(true)
                    .setRequiredNetworkType(constants.getFullBackupRequiredNetworkType())
                    .setRequiresCharging(constants.getFullBackupRequireCharging());
        }
        if (minDelay > 0) {
            builder.setMinimumLatency(minDelay);
        }

        Bundle extraInfo = new Bundle();
        extraInfo.putInt(USER_ID_EXTRA_KEY, userId);
        builder.setTransientExtras(extraInfo);

        js.schedule(builder.build());
    }

    public static void cancel(int userId, Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        js.cancel(getJobIdForUserId(userId));
    }

    // callback from the Backup Manager Service: it's finished its work for this pass
    public void finishBackupPass(int userId) {
        synchronized (mParamsForUser) {
            JobParameters jobParameters = mParamsForUser.get(userId);
            if (jobParameters != null) {
                jobFinished(jobParameters, false);
                mParamsForUser.remove(userId);
            }
        }
    }

    // ----- scheduled job interface -----

    @Override
    public boolean onStartJob(JobParameters params) {
        int userId = params.getTransientExtras().getInt(USER_ID_EXTRA_KEY);

        synchronized (mParamsForUser) {
            mParamsForUser.put(userId, params);
        }

        BackupManagerService service = BackupManagerService.getInstance();
        return service.beginFullBackup(userId, this);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        int userId = params.getTransientExtras().getInt(USER_ID_EXTRA_KEY);

        synchronized (mParamsForUser) {
            if (mParamsForUser.removeReturnOld(userId) == null) {
                return false;
            }
        }

        BackupManagerService service = BackupManagerService.getInstance();
        service.endFullBackup(userId);

        return false;
    }

    private static int getJobIdForUserId(int userId) {
        return JobIdManager.getJobIdForUserId(MIN_JOB_ID, MAX_JOB_ID, userId);
    }
}
