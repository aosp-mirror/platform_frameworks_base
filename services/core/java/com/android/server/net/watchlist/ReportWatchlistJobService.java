/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.net.watchlist;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkWatchlistManager;
import android.util.Slog;

import java.util.concurrent.TimeUnit;

/**
 * A job that periodically report watchlist records.
 */
public class ReportWatchlistJobService extends JobService {

    private static final boolean DEBUG = NetworkWatchlistService.DEBUG;
    private static final String TAG = "WatchlistJobService";

    // Unique job id used in system service, other jobs should not use the same value.
    public static final int REPORT_WATCHLIST_RECORDS_JOB_ID = 0xd7689;
    public static final long REPORT_WATCHLIST_RECORDS_PERIOD_MILLIS =
            TimeUnit.HOURS.toMillis(12);

    @Override
    public boolean onStartJob(final JobParameters jobParameters) {
        if (jobParameters.getJobId() != REPORT_WATCHLIST_RECORDS_JOB_ID) {
            return false;
        }
        if (DEBUG) Slog.d(TAG, "Start scheduled job.");
        new NetworkWatchlistManager(this).reportWatchlistIfNecessary();
        jobFinished(jobParameters, false);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        return true; // Reschedule when possible.
    }

    /**
     * Schedule the {@link ReportWatchlistJobService} to run periodically.
     */
    public static void schedule(Context context) {
        if (DEBUG) Slog.d(TAG, "Scheduling records aggregator task");
        final JobScheduler scheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.schedule(new JobInfo.Builder(REPORT_WATCHLIST_RECORDS_JOB_ID,
                new ComponentName(context, ReportWatchlistJobService.class))
                //.setOverrideDeadline(45 * 1000) // Schedule job soon, for testing.
                .setPeriodic(REPORT_WATCHLIST_RECORDS_PERIOD_MILLIS)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setPersisted(false)
                .build());
    }

}
