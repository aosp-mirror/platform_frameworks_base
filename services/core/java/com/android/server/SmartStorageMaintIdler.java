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

package com.android.server;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

import java.util.concurrent.TimeUnit;

public class SmartStorageMaintIdler extends JobService {
    private static final String TAG = "SmartStorageMaintIdler";

    private static final ComponentName SMART_STORAGE_MAINT_SERVICE =
            new ComponentName("android", SmartStorageMaintIdler.class.getName());

    private static final int SMART_MAINT_JOB_ID = 2808;

    private boolean mStarted;
    private JobParameters mJobParams;
    private final Runnable mFinishCallback = new Runnable() {
        @Override
        public void run() {
            Slog.i(TAG, "Got smart storage maintenance service completion callback");
            if (mStarted) {
                jobFinished(mJobParams, false);
                mStarted = false;
            }
            // ... and try again in a next period
            scheduleSmartIdlePass(SmartStorageMaintIdler.this,
                StorageManagerService.sSmartIdleMaintPeriod);
        }
    };

    @Override
    public boolean onStartJob(JobParameters params) {
        mJobParams = params;
        StorageManagerService ms = StorageManagerService.sSelf;
        if (ms != null) {
            mStarted = true;
            ms.runSmartIdleMaint(mFinishCallback);
        }
        return ms != null;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        mStarted = false;
        return false;
    }

    /**
     * Schedule the smart storage idle maintenance job
     */
    public static void scheduleSmartIdlePass(Context context, int nMinutes) {
        StorageManagerService ms = StorageManagerService.sSelf;
        if ((ms == null) || ms.isPassedLifetimeThresh()) {
            return;
        }

        JobScheduler tm = context.getSystemService(JobScheduler.class);

        long nextScheduleTime = TimeUnit.MINUTES.toMillis(nMinutes);

        JobInfo.Builder builder = new JobInfo.Builder(SMART_MAINT_JOB_ID,
            SMART_STORAGE_MAINT_SERVICE);

        builder.setMinimumLatency(nextScheduleTime);
        tm.schedule(builder.build());
    }
}
