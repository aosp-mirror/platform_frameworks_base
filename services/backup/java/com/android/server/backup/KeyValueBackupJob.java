/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Random;

/**
 * Job for scheduling key/value backup work.  This module encapsulates all
 * of the policy around when those backup passes are executed.
 */
public class KeyValueBackupJob extends JobService {
    private static final String TAG = "KeyValueBackupJob";
    private static ComponentName sKeyValueJobService =
            new ComponentName("android", KeyValueBackupJob.class.getName());
    private static final int JOB_ID = 0x5039;

    // Minimum wait time between backups even while we're on charger
    static final long BATCH_INTERVAL = 4 * AlarmManager.INTERVAL_HOUR;

    // Random variation in next-backup scheduling time to avoid server load spikes
    private static final int FUZZ_MILLIS = 10 * 60 * 1000;

    // Once someone asks for a backup, this is how long we hold off until we find
    // an on-charging opportunity.  If we hit this max latency we will run the operation
    // regardless.  Privileged callers can always trigger an immediate pass via
    // BackupManager.backupNow().
    private static final long MAX_DEFERRAL = AlarmManager.INTERVAL_DAY;

    private static boolean sScheduled = false;
    private static long sNextScheduled = 0;

    public static void schedule(Context ctx) {
        schedule(ctx, 0);
    }

    public static void schedule(Context ctx, long delay) {
        synchronized (KeyValueBackupJob.class) {
            if (!sScheduled) {
                if (delay <= 0) {
                    delay = BATCH_INTERVAL + new Random().nextInt(FUZZ_MILLIS);
                }
                if (BackupManagerService.DEBUG_SCHEDULING) {
                    Slog.v(TAG, "Scheduling k/v pass in "
                            + (delay / 1000 / 60) + " minutes");
                }
                JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, sKeyValueJobService)
                        .setMinimumLatency(delay)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setRequiresCharging(true)
                        .setOverrideDeadline(MAX_DEFERRAL);
                js.schedule(builder.build());

                sNextScheduled = System.currentTimeMillis() + delay;
                sScheduled = true;
            }
        }
    }

    public static void cancel(Context ctx) {
        synchronized (KeyValueBackupJob.class) {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            js.cancel(JOB_ID);
            sNextScheduled = 0;
            sScheduled = false;
        }
    }

    public static long nextScheduled() {
        synchronized (KeyValueBackupJob.class) {
            return sNextScheduled;
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        synchronized (KeyValueBackupJob.class) {
            sNextScheduled = 0;
            sScheduled = false;
        }

        // Time to run a key/value backup!
        Trampoline service = BackupManagerService.getInstance();
        try {
            service.backupNow();
        } catch (RemoteException e) {}

        // This was just a trigger; ongoing wakelock management is done by the
        // rest of the backup system.
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Intentionally empty; the job starting was just a trigger
        return false;
    }

}
