/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.pm;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.ServiceManager;
import android.util.Log;

import com.android.server.pm.dex.DexLogger;

import java.util.concurrent.TimeUnit;

/**
 * Scheduled job to trigger logging of app dynamic code loading. This runs daily while idle and
 * charging. The actual logging is performed by {@link DexLogger}.
 * {@hide}
 */
public class DynamicCodeLoggingService extends JobService {
    private static final String TAG = DynamicCodeLoggingService.class.getName();

    private static final int JOB_ID = 2030028;
    private static final long PERIOD_MILLIS = TimeUnit.DAYS.toMillis(1);

    private volatile boolean mStopRequested = false;

    private static final boolean DEBUG = false;

    /**
     * Schedule our job with the {@link JobScheduler}.
     */
    public static void schedule(Context context) {
        ComponentName serviceName = new ComponentName(
                "android", DynamicCodeLoggingService.class.getName());

        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_ID, serviceName)
                .setRequiresDeviceIdle(true)
                .setRequiresCharging(true)
                .setPeriodic(PERIOD_MILLIS)
                .build());
        if (DEBUG) {
            Log.d(TAG, "Job scheduled");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (DEBUG) {
            Log.d(TAG, "onStartJob");
        }
        mStopRequested = false;
        new IdleLoggingThread(params).start();
        return true;  // Job is running on another thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (DEBUG) {
            Log.d(TAG, "onStopJob");
        }
        mStopRequested = true;
        return true;  // Requests job be re-scheduled.
    }

    private class IdleLoggingThread extends Thread {
        private final JobParameters mParams;

        IdleLoggingThread(JobParameters params) {
            super("DynamicCodeLoggingService_IdleLoggingJob");
            mParams = params;
        }

        @Override
        public void run() {
            if (DEBUG) {
                Log.d(TAG, "Starting logging run");
            }

            PackageManagerService pm = (PackageManagerService) ServiceManager.getService("package");
            DexLogger dexLogger = pm.getDexManager().getDexLogger();
            for (String packageName : dexLogger.getAllPackagesWithDynamicCodeLoading()) {
                if (mStopRequested) {
                    Log.w(TAG, "Stopping logging run at scheduler request");
                    return;
                }

                dexLogger.logDynamicCodeLoading(packageName);
            }

            jobFinished(mParams, /* reschedule */ false);
            if (DEBUG) {
                Log.d(TAG, "Finished logging run");
            }
        }
    }
}
