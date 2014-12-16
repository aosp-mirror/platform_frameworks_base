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

package com.android.server.pm;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@hide}
 */
public class BackgroundDexOptService extends JobService {
    static final String TAG = "BackgroundDexOptService";

    static final int BACKGROUND_DEXOPT_JOB = 800;
    private static ComponentName sDexoptServiceName = new ComponentName(
            "android",
            BackgroundDexOptService.class.getName());

    /**
     * Set of failed packages remembered across job runs.
     */
    static final ArraySet<String> sFailedPackageNames = new ArraySet<String>();

    final AtomicBoolean mIdleTime = new AtomicBoolean(false);

    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo job = new JobInfo.Builder(BACKGROUND_DEXOPT_JOB, sDexoptServiceName)
                .setRequiresDeviceIdle(true)
                .setRequiresCharging(true)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "onIdleStart");
        final PackageManagerService pm =
                (PackageManagerService)ServiceManager.getService("package");

        if (pm.isStorageLow()) {
            return false;
        }
        final ArraySet<String> pkgs = pm.getPackagesThatNeedDexOpt();
        if (pkgs == null) {
            return false;
        }

        final JobParameters jobParams = params;
        mIdleTime.set(true);
        new Thread("BackgroundDexOptService_DexOpter") {
            @Override
            public void run() {
                for (String pkg : pkgs) {
                    if (!mIdleTime.get()) {
                        // stopped while still working, so we need to reschedule
                        schedule(BackgroundDexOptService.this);
                        return;
                    }
                    if (sFailedPackageNames.contains(pkg)) {
                        // skip previously failing package
                        continue;
                    }
                    if (!pm.performDexOpt(pkg, null /* instruction set */, true)) {
                        // there was a problem running dexopt,
                        // remember this so we do not keep retrying.
                        sFailedPackageNames.add(pkg);
                    }
                }
                // ran to completion, so we abandon our timeslice and do not reschedule
                jobFinished(jobParams, false);
            }
        }.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "onIdleStop");
        mIdleTime.set(false);
        return false;
    }
}
