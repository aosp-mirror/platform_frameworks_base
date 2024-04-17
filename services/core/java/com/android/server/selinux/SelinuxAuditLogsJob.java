/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Slog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class handles the start and stop requests for the logs collector job, in particular making
 * sure that at most one job is running at any given moment.
 */
final class SelinuxAuditLogsJob {

    private static final String TAG = "SelinuxAuditLogs";

    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final SelinuxAuditLogsCollector mAuditLogsCollector;

    SelinuxAuditLogsJob(SelinuxAuditLogsCollector auditLogsCollector) {
        mAuditLogsCollector = auditLogsCollector;
    }

    void requestStop() {
        mAuditLogsCollector.mStopRequested.set(true);
    }

    boolean isRunning() {
        return mIsRunning.get();
    }

    public void start(JobService jobService, JobParameters params) {
        mAuditLogsCollector.mStopRequested.set(false);
        if (mIsRunning.get()) {
            Slog.i(TAG, "Selinux audit job is already running, ignore start request.");
            return;
        }
        mIsRunning.set(true);
        boolean done = mAuditLogsCollector.collect(SelinuxAuditLogsService.AUDITD_TAG_CODE);
        if (done) {
            jobService.jobFinished(params, /* wantsReschedule= */ false);
        }
        mIsRunning.set(false);
    }
}
