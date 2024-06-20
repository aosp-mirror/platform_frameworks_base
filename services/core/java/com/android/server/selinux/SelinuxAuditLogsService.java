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
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.EventLog;
import android.util.Slog;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled jobs related to logging of SELinux denials and audits. The job runs daily on idle
 * devices.
 */
public class SelinuxAuditLogsService extends JobService {

    private static final String TAG = "SelinuxAuditLogs";
    private static final String SELINUX_AUDIT_NAMESPACE = "SelinuxAuditLogsNamespace";

    static final int AUDITD_TAG_CODE = EventLog.getTagCode("auditd");

    private static final String CONFIG_SELINUX_AUDIT_JOB_FREQUENCY_HOURS =
            "selinux_audit_job_frequency_hours";
    private static final String CONFIG_SELINUX_ENABLE_AUDIT_JOB = "selinux_enable_audit_job";
    private static final String CONFIG_SELINUX_AUDIT_CAP = "selinux_audit_cap";
    private static final int MAX_PERMITS_CAP_DEFAULT = 50000;

    private static final int SELINUX_AUDIT_JOB_ID = 25327386;
    private static final ComponentName SELINUX_AUDIT_JOB_COMPONENT =
            new ComponentName("android", SelinuxAuditLogsService.class.getName());

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    // Audit logging is subject to both rate and quota limiting. A {@link RateLimiter} makes sure
    // that we push no more than one atom every 10 milliseconds. A {@link QuotaLimiter} caps the
    // number of atoms pushed per day to CONFIG_SELINUX_AUDIT_CAP. The quota limiter is static
    // because new job executions happen in a new instance of this class. Making the quota limiter
    // an instance reference would reset the quota limitations between jobs executions.
    private static final Duration RATE_LIMITER_WINDOW = Duration.ofMillis(10);
    private static final QuotaLimiter QUOTA_LIMITER =
            new QuotaLimiter(
                    DeviceConfig.getInt(
                            DeviceConfig.NAMESPACE_ADSERVICES,
                            CONFIG_SELINUX_AUDIT_CAP,
                            MAX_PERMITS_CAP_DEFAULT));
    private static final SelinuxAuditLogsJob LOGS_COLLECTOR_JOB =
            new SelinuxAuditLogsJob(
                    new SelinuxAuditLogsCollector(
                            new RateLimiter(RATE_LIMITER_WINDOW), QUOTA_LIMITER));

    /** Schedule jobs with the {@link JobScheduler}. */
    public static void schedule(Context context) {
        if (!selinuxSdkSandboxAudit()) {
            Slog.d(TAG, "SelinuxAuditLogsService not enabled");
            return;
        }

        if (AUDITD_TAG_CODE == -1) {
            Slog.e(TAG, "auditd is not a registered tag on this system");
            return;
        }

        LogsCollectorJobScheduler propertiesListener =
                new LogsCollectorJobScheduler(
                        context.getSystemService(JobScheduler.class)
                                .forNamespace(SELINUX_AUDIT_NAMESPACE));
        propertiesListener.schedule();
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_ADSERVICES, context.getMainExecutor(), propertiesListener);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() != SELINUX_AUDIT_JOB_ID) {
            Slog.e(TAG, "The job id does not match the expected selinux job id.");
            return false;
        }
        if (!selinuxSdkSandboxAudit()) {
            Slog.i(TAG, "Selinux audit job disabled.");
            return false;
        }

        EXECUTOR_SERVICE.execute(() -> LOGS_COLLECTOR_JOB.start(this, params));
        return true; // the job is running
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (params.getJobId() != SELINUX_AUDIT_JOB_ID) {
            return false;
        }

        if (LOGS_COLLECTOR_JOB.isRunning()) {
            LOGS_COLLECTOR_JOB.requestStop();
            return true;
        }
        return false;
    }

    /**
     * This class is in charge of scheduling the job service, and keeping the scheduling up to date
     * when the parameters change.
     */
    private static final class LogsCollectorJobScheduler
            implements DeviceConfig.OnPropertiesChangedListener {

        private final JobScheduler mJobScheduler;

        private LogsCollectorJobScheduler(JobScheduler jobScheduler) {
            mJobScheduler = jobScheduler;
        }

        @Override
        public void onPropertiesChanged(Properties changedProperties) {
            Set<String> keyset = changedProperties.getKeyset();

            if (keyset.contains(CONFIG_SELINUX_AUDIT_CAP)) {
                QUOTA_LIMITER.setMaxPermits(
                        changedProperties.getInt(
                                CONFIG_SELINUX_AUDIT_CAP, MAX_PERMITS_CAP_DEFAULT));
            }

            if (keyset.contains(CONFIG_SELINUX_ENABLE_AUDIT_JOB)) {
                boolean enabled =
                        changedProperties.getBoolean(
                                CONFIG_SELINUX_ENABLE_AUDIT_JOB, /* defaultValue= */ false);
                if (enabled) {
                    schedule();
                } else {
                    mJobScheduler.cancel(SELINUX_AUDIT_JOB_ID);
                }
            } else if (keyset.contains(CONFIG_SELINUX_AUDIT_JOB_FREQUENCY_HOURS)) {
                // The job frequency changed, reschedule.
                schedule();
            }
        }

        private void schedule() {
            long frequencyMillis =
                    TimeUnit.HOURS.toMillis(
                            DeviceConfig.getInt(
                                    DeviceConfig.NAMESPACE_ADSERVICES,
                                    CONFIG_SELINUX_AUDIT_JOB_FREQUENCY_HOURS,
                                    24));
            if (mJobScheduler.schedule(
                            new JobInfo.Builder(SELINUX_AUDIT_JOB_ID, SELINUX_AUDIT_JOB_COMPONENT)
                                    .setPeriodic(frequencyMillis)
                                    .setRequiresDeviceIdle(true)
                                    .setRequiresBatteryNotLow(true)
                                    .build())
                    == JobScheduler.RESULT_FAILURE) {
                Slog.e(TAG, "SelinuxAuditLogsService could not be scheduled.");
            } else {
                Slog.d(TAG, "SelinuxAuditLogsService scheduled successfully.");
            }
        }
    }
}
