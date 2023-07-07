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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
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
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
        private final long mFinishTime;

        /**
         * Note {@code timeoutMillis} will be clamped to 0 ~ one week to avoid overflow.
         */
        PendingInstallConstraintsCheck(List<String> packageNames,
                InstallConstraints constraints,
                CompletableFuture<InstallConstraintsResult> future,
                long timeoutMillis) {
            this.packageNames = packageNames;
            this.constraints = constraints;
            this.future = future;

            timeoutMillis = Math.max(0, Math.min(DateUtils.WEEK_IN_MILLIS, timeoutMillis));
            mFinishTime = SystemClock.elapsedRealtime() + timeoutMillis;
        }
        public boolean isTimedOut() {
            return SystemClock.elapsedRealtime() >= mFinishTime;
        }
        /**
         * The remaining time before this pending check is timed out.
         */
        public long getRemainingTimeMillis() {
            long timeout = mFinishTime - SystemClock.elapsedRealtime();
            return Math.max(timeout, 0);
        }

        void dump(IndentingPrintWriter pw) {
            pw.printPair("packageNames", packageNames);
            pw.println();
            pw.printPair("finishTime", mFinishTime);
            pw.println();
            pw.printPair("constraints notInCallRequired", constraints.isNotInCallRequired());
            pw.println();
            pw.printPair("constraints deviceIdleRequired", constraints.isDeviceIdleRequired());
            pw.println();
            pw.printPair("constraints appNotForegroundRequired",
                    constraints.isAppNotForegroundRequired());
            pw.println();
            pw.printPair("constraints appNotInteractingRequired",
                    constraints.isAppNotInteractingRequired());
            pw.println();
            pw.printPair("constraints appNotTopVisibleRequired",
                    constraints.isAppNotTopVisibleRequired());
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final AppStateHelper mAppStateHelper;
    // Worker thread only
    private final ArrayDeque<PendingInstallConstraintsCheck> mPendingChecks = new ArrayDeque<>();
    private final ArrayList<CompletableFuture<Boolean>> mPendingIdleFutures = new ArrayList<>();
    private boolean mHasPendingIdleJob;

    GentleUpdateHelper(Context context, Looper looper, AppStateHelper appStateHelper) {
        mContext = context;
        mHandler = new Handler(looper);
        mAppStateHelper = appStateHelper;
    }

    void systemReady() {
        var am = mContext.getSystemService(ActivityManager.class);
        // Monitor top-visible apps
        am.addOnUidImportanceListener(this::onUidImportance, IMPORTANCE_FOREGROUND);
        // Monitor foreground apps
        am.addOnUidImportanceListener(this::onUidImportance, IMPORTANCE_FOREGROUND_SERVICE);
    }

    /**
     * Checks if install constraints are satisfied for the given packages.
     */
    CompletableFuture<InstallConstraintsResult> checkInstallConstraints(
            List<String> packageNames, InstallConstraints constraints,
            long timeoutMillis) {
        var resultFuture = new CompletableFuture<InstallConstraintsResult>();
        mHandler.post(() -> {
            var pendingCheck = new PendingInstallConstraintsCheck(
                    packageNames, constraints, resultFuture, timeoutMillis);
            var deviceIdleFuture = constraints.isDeviceIdleRequired()
                    ? checkDeviceIdle() : CompletableFuture.completedFuture(false);
            deviceIdleFuture.thenAccept(isIdle -> {
                Preconditions.checkState(mHandler.getLooper().isCurrentThread());
                if (!processPendingCheck(pendingCheck, isIdle)) {
                    // Not resolved. Schedule a job for re-check
                    mPendingChecks.add(pendingCheck);
                    scheduleIdleJob();
                    // Ensure the pending check is resolved after timeout, no matter constraints
                    // satisfied or not.
                    mHandler.postDelayed(() -> processPendingCheck(
                            pendingCheck, false), pendingCheck.getRemainingTimeMillis());
                }
            });
        });
        return resultFuture;
    }

    /**
     * Checks if the device is idle or not.
     * @return A future resolved to {@code true} if the device is idle, or {@code false} if not.
     */
    @WorkerThread
    private CompletableFuture<Boolean> checkDeviceIdle() {
        // JobScheduler doesn't provide queries about whether the device is idle.
        // We schedule 2 tasks here and the task which resolves
        // the future first will determine whether the device is idle or not.
        var future = new CompletableFuture<Boolean>();
        mPendingIdleFutures.add(future);
        scheduleIdleJob();
        mHandler.postDelayed(() -> future.complete(false), PENDING_CHECK_MILLIS);
        return future;
    }

    @WorkerThread
    private void scheduleIdleJob() {
        // Simulate idle jobs during test. Otherwise we need to wait for
        // more than 30 mins for JS to trigger the job.
        boolean isIdle = SystemProperties.getBoolean("debug.pm.gentle_update_test.is_idle", false);
        if (isIdle) {
            mHandler.post(this::runIdleJob);
            return;
        }

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

        for (var f : mPendingIdleFutures) {
            f.complete(true);
        }
        mPendingIdleFutures.clear();
    }

    @WorkerThread
    private boolean areConstraintsSatisfied(List<String> packageNames,
            InstallConstraints constraints, boolean isIdle) {
        return (!constraints.isDeviceIdleRequired() || isIdle)
                && (!constraints.isAppNotForegroundRequired()
                || !mAppStateHelper.hasForegroundApp(packageNames))
                && (!constraints.isAppNotInteractingRequired()
                || !mAppStateHelper.hasInteractingApp(packageNames))
                && (!constraints.isAppNotTopVisibleRequired()
                || !mAppStateHelper.hasTopVisibleApp(packageNames))
                && (!constraints.isNotInCallRequired()
                || !mAppStateHelper.isInCall());
    }

    @WorkerThread
    private boolean processPendingCheck(
            PendingInstallConstraintsCheck pendingCheck, boolean isIdle) {
        var future = pendingCheck.future;
        if (future.isDone()) {
            return true;
        }
        var constraints = pendingCheck.constraints;
        var packageNames = mAppStateHelper.getDependencyPackages(pendingCheck.packageNames);
        var satisfied = areConstraintsSatisfied(packageNames, constraints, isIdle);
        if (satisfied || pendingCheck.isTimedOut()) {
            future.complete(new InstallConstraintsResult((satisfied)));
            return true;
        }
        return false;
    }

    @WorkerThread
    private void processPendingChecksInIdle() {
        int size = mPendingChecks.size();
        for (int i = 0; i < size; ++i) {
            var pendingCheck = mPendingChecks.remove();
            if (!processPendingCheck(pendingCheck, true)) {
                // Not resolved. Put it back in the queue.
                mPendingChecks.add(pendingCheck);
            }
        }
        if (!mPendingChecks.isEmpty()) {
            // Schedule a job for remaining pending checks
            scheduleIdleJob();
        }
    }

    @WorkerThread
    private void onUidImportance(String packageName,
            @RunningAppProcessInfo.Importance int importance) {
        int size = mPendingChecks.size();
        for (int i = 0; i < size; ++i) {
            var pendingCheck = mPendingChecks.remove();
            var dependencyPackages =
                    mAppStateHelper.getDependencyPackages(pendingCheck.packageNames);
            if (!dependencyPackages.contains(packageName)
                    || !processPendingCheck(pendingCheck, false)) {
                mPendingChecks.add(pendingCheck);
            }
        }
        if (!mPendingChecks.isEmpty()) {
            // Schedule a job for remaining pending checks
            scheduleIdleJob();
        }
    }

    private void onUidImportance(int uid, @RunningAppProcessInfo.Importance int importance) {
        var pm = ActivityThread.getPackageManager();
        try {
            var packageName = pm.getNameForUid(uid);
            mHandler.post(() -> onUidImportance(packageName, importance));
        } catch (RemoteException ignore) {
        }
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Gentle update with constraints info:");
        pw.increaseIndent();
        pw.printPair("hasPendingIdleJob", mHasPendingIdleJob);
        pw.println();
        pw.printPair("Num of PendingIdleFutures", mPendingIdleFutures.size());
        pw.println();
        ArrayDeque<PendingInstallConstraintsCheck> pendingChecks = mPendingChecks.clone();
        int size = pendingChecks.size();
        pw.printPair("Num of PendingChecks", size);
        pw.println();
        pw.increaseIndent();
        for (int i = 0; i < size; i++) {
            pw.print(i); pw.print(":");
            PendingInstallConstraintsCheck pendingInstallConstraintsCheck = pendingChecks.remove();
            pendingInstallConstraintsCheck.dump(pw);
            pw.println();
        }
    }
}
