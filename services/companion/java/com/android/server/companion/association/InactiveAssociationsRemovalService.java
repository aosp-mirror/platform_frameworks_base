/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.association;

import static java.util.concurrent.TimeUnit.DAYS;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.companion.AssociationInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemProperties;
import android.util.Slog;

/**
 * A Job Service responsible for clean up self-managed associations if it's idle for 90 days.
 *
 * The job will be executed only if the device is charging and in idle mode due to the application
 * will be killed if association/role are revoked. See {@link DisassociationProcessor}
 */
public class InactiveAssociationsRemovalService extends JobService {

    private static final String TAG = "CDM_InactiveAssociationsRemovalService";
    private static final String JOB_NAMESPACE = "companion";
    private static final int JOB_ID = 1;
    private static final long ONE_DAY_INTERVAL = DAYS.toMillis(1);
    private static final String SYS_PROP_DEBUG_REMOVAL_TIME_WINDOW =
            "debug.cdm.cdmservice.removal_time_window";
    private static final long ASSOCIATION_REMOVAL_TIME_WINDOW_DEFAULT = DAYS.toMillis(90);

    private final AssociationStore mAssociationStore;
    private final DisassociationProcessor mDisassociationProcessor;

    public InactiveAssociationsRemovalService(AssociationStore associationStore,
            DisassociationProcessor disassociationProcessor) {
        mAssociationStore = associationStore;
        mDisassociationProcessor = disassociationProcessor;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Slog.i(TAG, "Execute the Association Removal job");

        removeIdleSelfManagedAssociations();

        jobFinished(params, false);
        return true;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        Slog.i(TAG, "Association removal job stopped; id=" + params.getJobId()
                + ", reason="
                + JobParameters.getInternalReasonCodeDescription(
                params.getInternalStopReasonCode()));
        return false;
    }

    /**
     * Schedule this job.
     */
    public static void schedule(Context context) {
        Slog.i(TAG, "Scheduling the Association Removal job");
        final JobScheduler jobScheduler =
                context.getSystemService(JobScheduler.class).forNamespace(JOB_NAMESPACE);
        final JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, InactiveAssociationsRemovalService.class))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPeriodic(ONE_DAY_INTERVAL)
                .build();
        jobScheduler.schedule(job);
    }

    /**
     * Remove idle self-managed associations.
     */
    public void removeIdleSelfManagedAssociations() {
        final long currentTime = System.currentTimeMillis();
        long removalWindow = SystemProperties.getLong(SYS_PROP_DEBUG_REMOVAL_TIME_WINDOW, -1);
        if (removalWindow <= 0) {
            // 0 or negative values indicate that the sysprop was never set or should be ignored.
            removalWindow = ASSOCIATION_REMOVAL_TIME_WINDOW_DEFAULT;
        }

        for (AssociationInfo association : mAssociationStore.getAssociations()) {
            if (!association.isSelfManaged()) continue;

            final boolean isInactive =
                    currentTime - association.getLastTimeConnectedMs() >= removalWindow;
            if (!isInactive) continue;

            final int id = association.getId();

            Slog.i(TAG, "Removing inactive self-managed association id=" + id);
            mDisassociationProcessor.disassociate(id);
        }
    }
}
