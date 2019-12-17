/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.os.FileUtils;
import android.os.SystemProperties;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Schedules jobs for triggering zram writeback.
 */
public final class ZramWriteback extends JobService {
    private static final String TAG = "ZramWriteback";
    private static final boolean DEBUG = false;

    private static final ComponentName sZramWriteback =
            new ComponentName("android", ZramWriteback.class.getName());

    private static final int MARK_IDLE_JOB_ID = 811;
    private static final int WRITEBACK_IDLE_JOB_ID = 812;

    private static final int MAX_ZRAM_DEVICES = 256;
    private static int sZramDeviceId = 0;

    private static final String IDLE_SYS = "/sys/block/zram%d/idle";
    private static final String IDLE_SYS_ALL_PAGES = "all";

    private static final String WB_SYS = "/sys/block/zram%d/writeback";
    private static final String WB_SYS_IDLE_PAGES = "idle";

    private static final String WB_STATS_SYS = "/sys/block/zram%d/bd_stat";
    private static final int WB_STATS_MAX_FILE_SIZE = 128;

    private static final String BDEV_SYS = "/sys/block/zram%d/backing_dev";

    private static final String MARK_IDLE_DELAY_PROP = "ro.zram.mark_idle_delay_mins";
    private static final String FIRST_WB_DELAY_PROP = "ro.zram.first_wb_delay_mins";
    private static final String PERIODIC_WB_DELAY_PROP = "ro.zram.periodic_wb_delay_hours";
    private static final String FORCE_WRITEBACK_PROP = "zram.force_writeback";

    private void markPagesAsIdle() {
        String idlePath = String.format(IDLE_SYS, sZramDeviceId);
        try {
            FileUtils.stringToFile(new File(idlePath), IDLE_SYS_ALL_PAGES);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write to " + idlePath);
        }
    }

    private void flushIdlePages() {
        if (DEBUG) Slog.d(TAG, "Start writing back idle pages to disk");
        String wbPath = String.format(WB_SYS, sZramDeviceId);
        try {
            FileUtils.stringToFile(new File(wbPath), WB_SYS_IDLE_PAGES);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write to " + wbPath);
        }
        if (DEBUG) Slog.d(TAG, "Finished writeback back idle pages");
    }

    private int getWrittenPageCount() {
        String wbStatsPath = String.format(WB_STATS_SYS, sZramDeviceId);
        try {
            String wbStats = FileUtils
                    .readTextFile(new File(wbStatsPath), WB_STATS_MAX_FILE_SIZE, "");
            return Integer.parseInt(wbStats.trim().split("\\s+")[2], 10);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read writeback stats from " + wbStatsPath);
        }

        return -1;
    }

    private void markAndFlushPages() {
        int pageCount = getWrittenPageCount();

        flushIdlePages();
        markPagesAsIdle();

        if (pageCount != -1) {
            Slog.i(TAG, "Total pages written to disk is " + (getWrittenPageCount() - pageCount));
        }
    }

    private static boolean isWritebackEnabled() {
        try {
            String backingDev = FileUtils
                    .readTextFile(new File(String.format(BDEV_SYS, sZramDeviceId)), 128, "");
            if (!"none".equals(backingDev.trim())) {
                return true;
            } else {
                Slog.w(TAG, "Writeback device is not set");
            }
        } catch (IOException e) {
            Slog.w(TAG, "Writeback is not enabled on zram");
        }
        return false;
    }

    private static void schedNextWriteback(Context context) {
        int nextWbDelay = SystemProperties.getInt(PERIODIC_WB_DELAY_PROP, 24);
        boolean forceWb = SystemProperties.getBoolean(FORCE_WRITEBACK_PROP, false);
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        js.schedule(new JobInfo.Builder(WRITEBACK_IDLE_JOB_ID, sZramWriteback)
                        .setMinimumLatency(TimeUnit.HOURS.toMillis(nextWbDelay))
                        .setRequiresDeviceIdle(!forceWb)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {

        if (!isWritebackEnabled()) {
            jobFinished(params, false);
            return false;
        }

        if (params.getJobId() == MARK_IDLE_JOB_ID) {
            markPagesAsIdle();
            jobFinished(params, false);
            return false;
        } else {
            new Thread("ZramWriteback_WritebackIdlePages") {
                @Override
                public void run() {
                    markAndFlushPages();
                    schedNextWriteback(ZramWriteback.this);
                    jobFinished(params, false);
                }
            }.start();
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The thread that triggers the writeback is non-interruptible
        return false;
    }

    /**
     * Schedule the zram writeback job to trigger a writeback when idle
     */
    public static void scheduleZramWriteback(Context context) {
        int markIdleDelay = SystemProperties.getInt(MARK_IDLE_DELAY_PROP, 20);
        int firstWbDelay = SystemProperties.getInt(FIRST_WB_DELAY_PROP, 180);
        boolean forceWb = SystemProperties.getBoolean(FORCE_WRITEBACK_PROP, false);

        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        // Schedule a one time job to mark pages as idle. These pages will be written
        // back at later point if they remain untouched.
        js.schedule(new JobInfo.Builder(MARK_IDLE_JOB_ID, sZramWriteback)
                        .setMinimumLatency(TimeUnit.MINUTES.toMillis(markIdleDelay))
                        .setOverrideDeadline(TimeUnit.MINUTES.toMillis(markIdleDelay))
                        .build());

        // Schedule a one time job to flush idle pages to disk.
        // After the initial writeback, subsequent writebacks are done at interval set
        // by ro.zram.periodic_wb_delay_hours.
        js.schedule(new JobInfo.Builder(WRITEBACK_IDLE_JOB_ID, sZramWriteback)
                        .setMinimumLatency(TimeUnit.MINUTES.toMillis(firstWbDelay))
                        .setRequiresDeviceIdle(!forceWb)
                        .build());
    }
}
