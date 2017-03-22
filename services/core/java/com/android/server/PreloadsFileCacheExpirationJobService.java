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
 * limitations under the License
 */

package com.android.server;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.os.Environment;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.R;

import java.util.concurrent.TimeUnit;

/**
 * {@link JobService} that marks
 * {@link Environment#getDataPreloadsFileCacheDirectory() preloaded file cache} as expired after a
 * pre-configured timeout.
 */
public class PreloadsFileCacheExpirationJobService extends JobService {
    private static final boolean DEBUG = false; // Do not submit with true
    private static final String TAG = "PreloadsFileCacheExpirationJobService";

    // TODO move all JOB_IDs into a single class to avoid collisions
    private static final int JOB_ID = 100500;

    private static final String PERSIST_SYS_PRELOADS_FILE_CACHE_EXPIRED
            = "persist.sys.preloads.file_cache_expired";

    public static void schedule(Context context) {
        int keepPreloadsMinDays = Resources.getSystem().getInteger(
                R.integer.config_keepPreloadsMinDays); // Default is 1 week
        long keepPreloadsMinTimeoutMs = DEBUG ? TimeUnit.MINUTES.toMillis(2)
                : TimeUnit.DAYS.toMillis(keepPreloadsMinDays);
        long keepPreloadsMaxTimeoutMs = DEBUG ? TimeUnit.MINUTES.toMillis(3)
                : TimeUnit.DAYS.toMillis(keepPreloadsMinDays + 1);

        if (DEBUG) {
            StringBuilder sb = new StringBuilder("Scheduling expiration job to run in ");
            TimeUtils.formatDuration(keepPreloadsMinTimeoutMs, sb);
            Slog.i(TAG, sb.toString());
        }
        JobInfo expirationJob = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, PreloadsFileCacheExpirationJobService.class))
                .setPersisted(true)
                .setMinimumLatency(keepPreloadsMinTimeoutMs)
                .setOverrideDeadline(keepPreloadsMaxTimeoutMs)
                .build();

        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.schedule(expirationJob);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        SystemProperties.set(PERSIST_SYS_PRELOADS_FILE_CACHE_EXPIRED, "1");
        Slog.i(TAG, "Set " + PERSIST_SYS_PRELOADS_FILE_CACHE_EXPIRED + "=1");
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
