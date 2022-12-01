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

package com.android.server.pm;

import android.annotation.WorkerThread;
import android.app.ActivityThread;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInstaller.InstallConstraints;
import android.content.pm.PackageInstaller.InstallConstraintsResult;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A helper class to coordinate install flow for sessions with install constraints.
 * These sessions will be pending and wait until the constraints are satisfied to
 * resume installation.
 */
public class GentleUpdateHelper {
    private static final String TAG = "GentleUpdateHelper";
    private static final int JOB_ID = 235306967; // bug id
    // The timeout used to determine whether the device is idle or not.
    private static final long PENDING_CHECK_MILLIS = TimeUnit.SECONDS.toMillis(10);

    /**
     * A wrapper class used by JobScheduler to schedule jobs.
     */
    public static class Service extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            try {
                var pis = (PackageInstallerService) ActivityThread.getPackageManager()
                        .getPackageInstaller();
                var helper = pis.getGentleUpdateHelper();
                helper.mHandler.post(helper::runIdleJob);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get PackageInstallerService", e);
            }
            return false;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }

    private static class PendingInstallConstraintsCheck {
        public final List<String> packageNames;
        public final InstallConstraints constraints;
        public final CompletableFuture<InstallConstraintsResult> future;
        PendingInstallConstraintsCheck(List<String> packageNames,
                InstallConstraints constraints,
                CompletableFuture<InstallConstraintsResult> future) {
            this.packageNames = packageNames;
            this.constraints = constraints;
            this.future = future;
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final AppStateHelper mAppStateHelper;
    // Worker thread only
    private final ArrayDeque<PendingInstallConstraintsCheck> mPendingChecks = new ArrayDeque<>();
    private boolean mHasPendingIdleJob;

    GentleUpdateHelper(Context context, Looper looper, AppStateHelper appStateHelper) {
        mContext = context;
        mHandler = new Handler(looper);
        mAppStateHelper = appStateHelper;
    }

    /**
     * Checks if install constraints are satisfied for the given packages.
     */
    CompletableFuture<InstallConstraintsResult> checkInstallConstraints(
            List<String> packageNames, InstallConstraints constraints) {
        var future = new CompletableFuture<InstallConstraintsResult>();
        mHandler.post(() -> {
            var pendingCheck = new PendingInstallConstraintsCheck(
                    packageNames, constraints, future);
            if (constraints.isRequireDeviceIdle()) {
                mPendingChecks.add(pendingCheck);
                // JobScheduler doesn't provide queries about whether the device is idle.
                // We schedule 2 tasks to determine device idle. If the idle job is executed
                // before the delayed runnable, we know the device is idle.
                // Note #processPendingCheck will be no-op for the task executed later.
                scheduleIdleJob();
                mHandler.postDelayed(() -> processPendingCheck(pendingCheck, false),
                        PENDING_CHECK_MILLIS);
            } else {
                processPendingCheck(pendingCheck, false);
            }
        });
        return future;
    }

    @WorkerThread
    private void scheduleIdleJob() {
        if (mHasPendingIdleJob) {
            // No need to schedule the job again
            return;
        }
        mHasPendingIdleJob = true;
        var componentName = new ComponentName(
                mContext.getPackageName(), GentleUpdateHelper.Service.class.getName());
        var jobInfo = new JobInfo.Builder(JOB_ID, componentName)
                .setRequiresDeviceIdle(true)
                .build();
        var jobScheduler = mContext.getSystemService(JobScheduler.class);
        jobScheduler.schedule(jobInfo);
    }

    @WorkerThread
    private void runIdleJob() {
        mHasPendingIdleJob = false;
        processPendingChecksInIdle();
    }

    @WorkerThread
    private void processPendingCheck(PendingInstallConstraintsCheck pendingCheck, boolean isIdle) {
        var future = pendingCheck.future;
        if (future.isDone()) {
            return;
        }
        var constraints = pendingCheck.constraints;
        var packageNames = mAppStateHelper.getDependencyPackages(pendingCheck.packageNames);
        var constraintsSatisfied = (!constraints.isRequireDeviceIdle() || isIdle)
                && (!constraints.isRequireAppNotForeground()
                        || !mAppStateHelper.hasForegroundApp(packageNames))
                && (!constraints.isRequireAppNotInteracting()
                        || !mAppStateHelper.hasInteractingApp(packageNames))
                && (!constraints.isRequireAppNotTopVisible()
                        || !mAppStateHelper.hasTopVisibleApp(packageNames))
                && (!constraints.isRequireNotInCall()
                        || !mAppStateHelper.isInCall());
        future.complete(new InstallConstraintsResult((constraintsSatisfied)));
    }

    @WorkerThread
    private void processPendingChecksInIdle() {
        while (!mPendingChecks.isEmpty()) {
            processPendingCheck(mPendingChecks.remove(), true);
        }
    }
}
