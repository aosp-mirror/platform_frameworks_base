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

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public class FullBackupJob extends JobService {
    private static final String TAG = "FullBackupJob";
    private static final boolean DEBUG = true;

    private static ComponentName sIdleService =
            new ComponentName("android", FullBackupJob.class.getName());

    private static final int JOB_ID = 0x5038;

    JobParameters mParams;

    public static void schedule(Context ctx, long minDelay) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, sIdleService)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresCharging(true);
        if (minDelay > 0) {
            builder.setMinimumLatency(minDelay);
        }
        js.schedule(builder.build());
    }

    // callback from the Backup Manager Service: it's finished its work for this pass
    public void finishBackupPass() {
        if (mParams != null) {
            jobFinished(mParams, false);
            mParams = null;
        }
    }

    // ----- scheduled job interface -----

    @Override
    public boolean onStartJob(JobParameters params) {
        mParams = params;
        Trampoline service = BackupManagerService.getInstance();
        return service.beginFullBackup(this);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mParams != null) {
            mParams = null;
            Trampoline service = BackupManagerService.getInstance();
            service.endFullBackup();
        }
        return false;
    }

}
