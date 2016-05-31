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

import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * {@hide}
 */
public class BackgroundDexOptService extends JobService {
    static final String TAG = "BackgroundDexOptService";

    static final long RETRY_LATENCY = 4 * AlarmManager.INTERVAL_HOUR;

    static final int JOB_IDLE_OPTIMIZE = 800;
    static final int JOB_POST_BOOT_UPDATE = 801;

    private static ComponentName sDexoptServiceName = new ComponentName(
            "android",
            BackgroundDexOptService.class.getName());

    /**
     * Set of failed packages remembered across job runs.
     */
    static final ArraySet<String> sFailedPackageNames = new ArraySet<String>();

    /**
     * Atomics set to true if the JobScheduler requests an abort.
     */
    final AtomicBoolean mAbortPostBootUpdate = new AtomicBoolean(false);
    final AtomicBoolean mAbortIdleOptimization = new AtomicBoolean(false);

    /**
     * Atomic set to true if one job should exit early because another job was started.
     */
    final AtomicBoolean mExitPostBootUpdate = new AtomicBoolean(false);

    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        // Schedule a one-off job which scans installed packages and updates
        // out-of-date oat files.
        js.schedule(new JobInfo.Builder(JOB_POST_BOOT_UPDATE, sDexoptServiceName)
                    .setMinimumLatency(TimeUnit.MINUTES.toMillis(1))
                    .setOverrideDeadline(TimeUnit.MINUTES.toMillis(1))
                    .build());

        // Schedule a daily job which scans installed packages and compiles
        // those with fresh profiling data.
        js.schedule(new JobInfo.Builder(JOB_IDLE_OPTIMIZE, sDexoptServiceName)
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .setPeriodic(TimeUnit.DAYS.toMillis(1))
                    .build());

        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Jobs scheduled");
        }
    }

    public static void notifyPackageChanged(String packageName) {
        // The idle maintanance job skips packages which previously failed to
        // compile. The given package has changed and may successfully compile
        // now. Remove it from the list of known failing packages.
        synchronized (sFailedPackageNames) {
            sFailedPackageNames.remove(packageName);
        }
    }

    // Returns the current battery level as a 0-100 integer.
    private int getBatteryLevel() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent intent = registerReceiver(null, filter);
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        if (level < 0 || scale <= 0) {
            // Battery data unavailable. This should never happen, so assume the worst.
            return 0;
        }

        return (100 * level / scale);
    }

    private boolean runPostBootUpdate(final JobParameters jobParams,
            final PackageManagerService pm, final ArraySet<String> pkgs) {
        if (mExitPostBootUpdate.get()) {
            // This job has already been superseded. Do not start it.
            return false;
        }

        // Load low battery threshold from the system config. This is a 0-100 integer.
        final int lowBatteryThreshold = getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);

        mAbortPostBootUpdate.set(false);
        new Thread("BackgroundDexOptService_PostBootUpdate") {
            @Override
            public void run() {
                for (String pkg : pkgs) {
                    if (mAbortPostBootUpdate.get()) {
                        // JobScheduler requested an early abort.
                        return;
                    }
                    if (mExitPostBootUpdate.get()) {
                        // Different job, which supersedes this one, is running.
                        break;
                    }
                    if (getBatteryLevel() < lowBatteryThreshold) {
                        // Rather bail than completely drain the battery.
                        break;
                    }
                    if (DEBUG_DEXOPT) {
                        Log.i(TAG, "Updating package " + pkg);
                    }

                    // Update package if needed. Note that there can be no race between concurrent
                    // jobs because PackageDexOptimizer.performDexOpt is synchronized.

                    // checkProfiles is false to avoid merging profiles during boot which
                    // might interfere with background compilation (b/28612421).
                    // Unfortunately this will also means that "pm.dexopt.boot=speed-profile" will
                    // behave differently than "pm.dexopt.bg-dexopt=speed-profile" but that's a
                    // trade-off worth doing to save boot time work.
                    pm.performDexOpt(pkg,
                            /* checkProfiles */ false,
                            PackageManagerService.REASON_BOOT,
                            /* force */ false);
                }
                // Ran to completion, so we abandon our timeslice and do not reschedule.
                jobFinished(jobParams, /* reschedule */ false);
            }
        }.start();
        return true;
    }

    private boolean runIdleOptimization(final JobParameters jobParams,
            final PackageManagerService pm, final ArraySet<String> pkgs) {
        // If post-boot update is still running, request that it exits early.
        mExitPostBootUpdate.set(true);

        mAbortIdleOptimization.set(false);
        new Thread("BackgroundDexOptService_IdleOptimization") {
            @Override
            public void run() {
                for (String pkg : pkgs) {
                    if (mAbortIdleOptimization.get()) {
                        // JobScheduler requested an early abort.
                        return;
                    }
                    if (sFailedPackageNames.contains(pkg)) {
                        // Skip previously failing package
                        continue;
                    }
                    // Conservatively add package to the list of failing ones in case performDexOpt
                    // never returns.
                    synchronized (sFailedPackageNames) {
                        sFailedPackageNames.add(pkg);
                    }
                    // Optimize package if needed. Note that there can be no race between
                    // concurrent jobs because PackageDexOptimizer.performDexOpt is synchronized.
                    if (pm.performDexOpt(pkg,
                            /* checkProfiles */ true,
                            PackageManagerService.REASON_BACKGROUND_DEXOPT,
                            /* force */ false)) {
                        // Dexopt succeeded, remove package from the list of failing ones.
                        synchronized (sFailedPackageNames) {
                            sFailedPackageNames.remove(pkg);
                        }
                    }
                }
                // Ran to completion, so we abandon our timeslice and do not reschedule.
                jobFinished(jobParams, /* reschedule */ false);
            }
        }.start();
        return true;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "onStartJob");
        }

        PackageManagerService pm = (PackageManagerService)ServiceManager.getService("package");
        if (pm.isStorageLow()) {
            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Low storage, skipping this run");
            }
            return false;
        }

        final ArraySet<String> pkgs = pm.getOptimizablePackages();
        if (pkgs == null || pkgs.isEmpty()) {
            if (DEBUG_DEXOPT) {
                Log.i(TAG, "No packages to optimize");
            }
            return false;
        }

        if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
            return runPostBootUpdate(params, pm, pkgs);
        } else {
            return runIdleOptimization(params, pm, pkgs);
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "onStopJob");
        }

        if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
            mAbortPostBootUpdate.set(true);
        } else {
            mAbortIdleOptimization.set(true);
        }
        return false;
    }
}
