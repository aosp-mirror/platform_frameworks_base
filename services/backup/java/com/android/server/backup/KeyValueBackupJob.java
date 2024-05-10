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

import static com.android.server.backup.BackupManagerService.DEBUG_SCHEDULING;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Random;

/**
 * Job for scheduling key/value backup work.  This module encapsulates all
 * of the policy around when those backup passes are executed.
 */
public class KeyValueBackupJob extends JobService {
    private static final String TAG = "KeyValueBackupJob";
    private static ComponentName sKeyValueJobService =
            new ComponentName(PLATFORM_PACKAGE_NAME, KeyValueBackupJob.class.getName());

    private static final String USER_ID_EXTRA_KEY = "userId";

    // Once someone asks for a backup, this is how long we hold off until we find
    // an on-charging opportunity.  If we hit this max latency we will run the operation
    // regardless.  Privileged callers can always trigger an immediate pass via
    // BackupManager.backupNow().
    private static final long MAX_DEFERRAL = AlarmManager.INTERVAL_DAY;

    @GuardedBy("KeyValueBackupJob.class")
    private static final SparseBooleanArray sScheduledForUserId = new SparseBooleanArray();
    @GuardedBy("KeyValueBackupJob.class")
    private static final SparseLongArray sNextScheduledForUserId = new SparseLongArray();

    @VisibleForTesting
    public static final int MIN_JOB_ID = 52417896;
    @VisibleForTesting
    public static final int MAX_JOB_ID = 52418896;

    public static void schedule(int userId, Context ctx,
            UserBackupManagerService userBackupManagerService) {
        schedule(userId, ctx, 0, userBackupManagerService);
    }

    public static void schedule(int userId, Context ctx, long delay,
            UserBackupManagerService userBackupManagerService) {
        synchronized (KeyValueBackupJob.class) {
            if (sScheduledForUserId.get(userId)
                    || !userBackupManagerService.isFrameworkSchedulingEnabled()) {
                return;
            }

            final long interval;
            final long fuzz;
            final int networkType;
            final boolean needsCharging;

            final BackupManagerConstants constants = userBackupManagerService.getConstants();
            synchronized (constants) {
                interval = constants.getKeyValueBackupIntervalMilliseconds();
                fuzz = constants.getKeyValueBackupFuzzMilliseconds();
                networkType = constants.getKeyValueBackupRequiredNetworkType();
                needsCharging = constants.getKeyValueBackupRequireCharging();
            }
            if (delay <= 0) {
                delay = interval + new Random().nextInt((int) fuzz);
            }
            if (DEBUG_SCHEDULING) {
                Slog.v(TAG, "Scheduling k/v pass in " + (delay / 1000 / 60) + " minutes");
            }

            JobInfo.Builder builder = new JobInfo.Builder(getJobIdForUserId(userId),
                    sKeyValueJobService)
                    .setMinimumLatency(delay)
                    .setRequiredNetworkType(networkType)
                    .setRequiresCharging(needsCharging)
                    .setOverrideDeadline(MAX_DEFERRAL);

            Bundle extraInfo = new Bundle();
            extraInfo.putInt(USER_ID_EXTRA_KEY, userId);
            builder.setTransientExtras(extraInfo);

            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            js.schedule(builder.build());

            sScheduledForUserId.put(userId, true);
            sNextScheduledForUserId.put(userId, System.currentTimeMillis() + delay);
        }
    }

    public static void cancel(int userId, Context ctx) {
        synchronized (KeyValueBackupJob.class) {
            JobScheduler js = (JobScheduler) ctx.getSystemService(
                    Context.JOB_SCHEDULER_SERVICE);
            js.cancel(getJobIdForUserId(userId));

            clearScheduledForUserId(userId);
        }
    }

    public static long nextScheduled(int userId) {
        synchronized (KeyValueBackupJob.class) {
            return sNextScheduledForUserId.get(userId);
        }
    }

    @VisibleForTesting
    public static boolean isScheduled(int userId) {
        synchronized (KeyValueBackupJob.class) {
            return sScheduledForUserId.get(userId);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int userId = params.getTransientExtras().getInt(USER_ID_EXTRA_KEY);

        synchronized (KeyValueBackupJob.class) {
            clearScheduledForUserId(userId);
        }

        // Time to run a key/value backup!
        BackupManagerService service = BackupManagerService.getInstance();
        try {
            service.backupNowForUser(userId);
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

    @GuardedBy("KeyValueBackupJob.class")
    private static void clearScheduledForUserId(int userId) {
        sScheduledForUserId.delete(userId);
        sNextScheduledForUserId.delete(userId);
    }

    @VisibleForTesting
    static int getJobIdForUserId(int userId) {
        return JobIdManager.getJobIdForUserId(MIN_JOB_ID, MAX_JOB_ID, userId);
    }
}
