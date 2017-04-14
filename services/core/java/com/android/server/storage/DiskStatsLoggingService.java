/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.storage;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageStats;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.UserHandle;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.storage.FileCollector.MeasurementResult;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DiskStatsLoggingService is a JobService which collects storage categorization information and
 * app size information on a roughly daily cadence.
 */
public class DiskStatsLoggingService extends JobService {
    private static final String TAG = "DiskStatsLogService";
    public static final String DUMPSYS_CACHE_PATH = "/data/system/diskstats_cache.json";
    private static final int JOB_DISKSTATS_LOGGING = 0x4449534b; // DISK
    private static ComponentName sDiskStatsLoggingService = new ComponentName(
            "android",
            DiskStatsLoggingService.class.getName());

    @Override
    public boolean onStartJob(JobParameters params) {
        // We need to check the preconditions again because they may not be enforced for
        // subsequent runs.
        if (!isCharging(this) || !isDumpsysTaskEnabled(getContentResolver())) {
            jobFinished(params, true);
            return false;
        }


        VolumeInfo volume = getPackageManager().getPrimaryStorageCurrentVolume();
        // volume is null if the primary storage is not yet mounted.
        if (volume == null) {
            return false;
        }
        AppCollector collector = new AppCollector(this, volume);

        final int userId = UserHandle.myUserId();
        UserEnvironment environment = new UserEnvironment(userId);
        LogRunnable task = new LogRunnable();
        task.setRootDirectory(environment.getExternalStorageDirectory());
        task.setDownloadsDirectory(
                environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        task.setSystemSize(FileCollector.getSystemSize(this));
        task.setLogOutputFile(new File(DUMPSYS_CACHE_PATH));
        task.setAppCollector(collector);
        task.setJobService(this, params);
        AsyncTask.execute(task);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // TODO: Try to stop being handled.
        return false;
    }

    /**
     * Schedules a DiskStats collection task. This task only runs on device idle while charging
     * once every 24 hours.
     * @param context Context to use to get a job scheduler.
     */
    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        js.schedule(new JobInfo.Builder(JOB_DISKSTATS_LOGGING, sDiskStatsLoggingService)
                .setRequiresDeviceIdle(true)
                .setRequiresCharging(true)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .build());
    }

    private static boolean isCharging(Context context) {
        BatteryManager batteryManager = context.getSystemService(BatteryManager.class);
        if (batteryManager != null) {
            return batteryManager.isCharging();
        }
        return false;
    }

    @VisibleForTesting
    static boolean isDumpsysTaskEnabled(ContentResolver resolver) {
        // The default is to treat the task as enabled.
        return Settings.Global.getInt(resolver, Settings.Global.ENABLE_DISKSTATS_LOGGING, 1) != 0;
    }

    @VisibleForTesting
    static class LogRunnable implements Runnable {
        private static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);

        private JobService mJobService;
        private JobParameters mParams;
        private AppCollector mCollector;
        private File mOutputFile;
        private File mRootDirectory;
        private File mDownloadsDirectory;
        private long mSystemSize;

        public void setRootDirectory(File file) {
            mRootDirectory = file;
        }

        public void setDownloadsDirectory(File file) {
            mDownloadsDirectory = file;
        }

        public void setAppCollector(AppCollector collector) {
            mCollector = collector;
        }

        public void setLogOutputFile(File file) {
            mOutputFile = file;
        }

        public void setSystemSize(long size) {
            mSystemSize = size;
        }

        public void setJobService(JobService jobService, JobParameters params) {
            mJobService = jobService;
            mParams = params;
        }

        public void run() {
            FileCollector.MeasurementResult mainCategories =
                    FileCollector.getMeasurementResult(mRootDirectory);
            FileCollector.MeasurementResult downloads =
                    FileCollector.getMeasurementResult(mDownloadsDirectory);

            boolean needsReschedule = true;
            List<PackageStats> stats = mCollector.getPackageStats(TIMEOUT_MILLIS);
            if (stats != null) {
                needsReschedule = false;
                logToFile(mainCategories, downloads, stats, mSystemSize);
            } else {
                Log.w("TAG", "Timed out while fetching package stats.");
            }

            if (mJobService != null) {
                mJobService.jobFinished(mParams, needsReschedule);
            }
        }

        private void logToFile(MeasurementResult mainCategories, MeasurementResult downloads,
                List<PackageStats> stats, long systemSize) {
            DiskStatsFileLogger logger = new DiskStatsFileLogger(mainCategories, downloads, stats,
                    systemSize);
            try {
                mOutputFile.createNewFile();
                logger.dumpToFile(mOutputFile);
            } catch (IOException e) {
                Log.e(TAG, "Exception while writing opportunistic disk file cache.", e);
            }
        }
    }
}