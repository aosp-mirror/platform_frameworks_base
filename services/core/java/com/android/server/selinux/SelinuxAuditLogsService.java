/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.selinux;

import static com.android.sdksandbox.flags.Flags.selinuxSdkSandboxAudit;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.EventLog;
import android.util.Log;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduled jobs related to logging of SELinux denials and audits. The job runs daily on idle
 * devices.
 */
public class SelinuxAuditLogsService extends JobService {

    private static final String TAG = "SelinuxAuditLogs";
    private static final String SELINUX_AUDIT_NAMESPACE = "SelinuxAuditLogsNamespace";

    static final int AUDITD_TAG_CODE = EventLog.getTagCode("auditd");

    private static final int SELINUX_AUDIT_JOB_ID = 25327386;
    private static final JobInfo SELINUX_AUDIT_JOB =
            new JobInfo.Builder(
                            SELINUX_AUDIT_JOB_ID,
                            new ComponentName("android", SelinuxAuditLogsService.class.getName()))
                    .setPeriodic(TimeUnit.DAYS.toMillis(1))
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build();

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
    private static final AtomicReference<Boolean> IS_RUNNING = new AtomicReference<>(false);

    // Audit logging is subject to both rate and quota limiting. We can only push one atom every 10
    // milliseconds, and no more than 50K atoms can be pushed each day.
    private static final SelinuxAuditLogsCollector AUDIT_LOGS_COLLECTOR =
            new SelinuxAuditLogsCollector(
                    new RateLimiter(/* window= */ Duration.ofMillis(10)),
                    new QuotaLimiter(/* maxPermitsPerDay= */ 50000));

    /** Schedule jobs with the {@link JobScheduler}. */
    public static void schedule(Context context) {
        if (!selinuxSdkSandboxAudit()) {
            Log.d(TAG, "SelinuxAuditLogsService not enabled");
            return;
        }

        if (AUDITD_TAG_CODE == -1) {
            Log.e(TAG, "auditd is not a registered tag on this system");
            return;
        }

        if (context.getSystemService(JobScheduler.class)
                        .forNamespace(SELINUX_AUDIT_NAMESPACE)
                        .schedule(SELINUX_AUDIT_JOB)
                == JobScheduler.RESULT_FAILURE) {
            Log.e(TAG, "SelinuxAuditLogsService could not be started.");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() != SELINUX_AUDIT_JOB_ID) {
            Log.e(TAG, "The job id does not match the expected selinux job id.");
            return false;
        }

        AUDIT_LOGS_COLLECTOR.mStopRequested.set(false);
        IS_RUNNING.set(true);
        EXECUTOR_SERVICE.execute(new LogsCollectorJob(this, params));

        return true; // the job is running
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (params.getJobId() != SELINUX_AUDIT_JOB_ID) {
            return false;
        }

        AUDIT_LOGS_COLLECTOR.mStopRequested.set(true);
        return IS_RUNNING.get();
    }

    private static class LogsCollectorJob implements Runnable {
        private final JobService mAuditLogService;
        private final JobParameters mParams;

        LogsCollectorJob(JobService auditLogService, JobParameters params) {
            mAuditLogService = auditLogService;
            mParams = params;
        }

        @Override
        public void run() {
            IS_RUNNING.updateAndGet(
                    isRunning -> {
                        boolean done = AUDIT_LOGS_COLLECTOR.collect(AUDITD_TAG_CODE);
                        if (done) {
                            mAuditLogService.jobFinished(mParams, /* wantsReschedule= */ false);
                        }
                        return !done;
                    });
        }
    }
}
