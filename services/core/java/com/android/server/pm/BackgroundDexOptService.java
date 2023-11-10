/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getCompilerFilterForReason;
import static com.android.server.pm.dex.ArtStatsLogUtils.BackgroundDexoptJobStatsLogger;

import static dalvik.system.DexFile.isProfileGuidedCompilerFilter;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.Environment;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.FunctionalUtils.ThrowingCheckedSupplier;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.PinnerService;
import com.android.server.pm.Installer.LegacyDexoptDisabledException;
import com.android.server.pm.PackageDexOptimizer.DexOptResult;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.utils.TimingsTraceAndSlog;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Controls background dex optimization run as idle job or command line.
 */
public final class BackgroundDexOptService {
    private static final String TAG = "BackgroundDexOptService";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting static final int JOB_IDLE_OPTIMIZE = 800;
    @VisibleForTesting static final int JOB_POST_BOOT_UPDATE = 801;

    private static final long IDLE_OPTIMIZATION_PERIOD = TimeUnit.DAYS.toMillis(1);

    private static final long CANCELLATION_WAIT_CHECK_INTERVAL_MS = 200;

    private static final ComponentName sDexoptServiceName =
            new ComponentName("android", BackgroundDexOptJobService.class.getName());

    // Possible return codes of individual optimization steps.
    /** Initial value. */
    public static final int STATUS_UNSPECIFIED = -1;
    /** Ok status: Optimizations finished, All packages were processed, can continue */
    public static final int STATUS_OK = 0;
    /** Optimizations should be aborted. Job scheduler requested it. */
    public static final int STATUS_ABORT_BY_CANCELLATION = 1;
    /** Optimizations should be aborted. No space left on device. */
    public static final int STATUS_ABORT_NO_SPACE_LEFT = 2;
    /** Optimizations should be aborted. Thermal throttling level too high. */
    public static final int STATUS_ABORT_THERMAL = 3;
    /** Battery level too low */
    public static final int STATUS_ABORT_BATTERY = 4;
    /**
     * {@link PackageDexOptimizer#DEX_OPT_FAILED} case. This state means some packages have failed
     * compilation during the job. Note that the failure will not be permanent as the next dexopt
     * job will exclude those failed packages.
     */
    public static final int STATUS_DEX_OPT_FAILED = 5;
    /** Encountered fatal error, such as a runtime exception. */
    public static final int STATUS_FATAL_ERROR = 6;

    @IntDef(prefix = {"STATUS_"},
            value =
                    {
                            STATUS_UNSPECIFIED,
                            STATUS_OK,
                            STATUS_ABORT_BY_CANCELLATION,
                            STATUS_ABORT_NO_SPACE_LEFT,
                            STATUS_ABORT_THERMAL,
                            STATUS_ABORT_BATTERY,
                            STATUS_DEX_OPT_FAILED,
                            STATUS_FATAL_ERROR,
                    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {}

    // Used for calculating space threshold for downgrading unused apps.
    private static final int LOW_THRESHOLD_MULTIPLIER_FOR_DOWNGRADE = 2;

    // Thermal cutoff value used if one isn't defined by a system property.
    private static final int THERMAL_CUTOFF_DEFAULT = PowerManager.THERMAL_STATUS_MODERATE;

    private final Injector mInjector;

    private final DexOptHelper mDexOptHelper;

    private final BackgroundDexoptJobStatsLogger mStatsLogger =
            new BackgroundDexoptJobStatsLogger();

    private final Object mLock = new Object();

    // Thread currently running dexopt. This will be null if dexopt is not running.
    // The thread running dexopt make sure to set this into null when the pending dexopt is
    // completed.
    @GuardedBy("mLock") @Nullable private Thread mDexOptThread;

    // Thread currently cancelling dexopt. This thread is in blocked wait state until
    // cancellation is done. Only this thread can change states for control. The other threads, if
    // need to wait for cancellation, should just wait without doing any control.
    @GuardedBy("mLock") @Nullable private Thread mDexOptCancellingThread;

    // Tells whether post boot update is completed or not.
    @GuardedBy("mLock") private boolean mFinishedPostBootUpdate;

    // True if JobScheduler invocations of dexopt have been disabled.
    @GuardedBy("mLock") private boolean mDisableJobSchedulerJobs;

    @GuardedBy("mLock") @Status private int mLastExecutionStatus = STATUS_UNSPECIFIED;

    @GuardedBy("mLock") private long mLastExecutionStartUptimeMs;
    @GuardedBy("mLock") private long mLastExecutionDurationMs;

    // Keeps packages cancelled from PDO for last session. This is for debugging.
    @GuardedBy("mLock")
    private final ArraySet<String> mLastCancelledPackages = new ArraySet<String>();

    /**
     * Set of failed packages remembered across job runs.
     */
    @GuardedBy("mLock")
    private final ArraySet<String> mFailedPackageNamesPrimary = new ArraySet<String>();
    @GuardedBy("mLock")
    private final ArraySet<String> mFailedPackageNamesSecondary = new ArraySet<String>();

    private final long mDowngradeUnusedAppsThresholdInMillis;

    private final List<PackagesUpdatedListener> mPackagesUpdatedListeners = new ArrayList<>();

    private int mThermalStatusCutoff = THERMAL_CUTOFF_DEFAULT;

    /** Listener for monitoring package change due to dexopt. */
    public interface PackagesUpdatedListener {
        /** Called when the packages are updated through dexopt */
        void onPackagesUpdated(ArraySet<String> updatedPackages);
    }

    public BackgroundDexOptService(Context context, DexManager dexManager, PackageManagerService pm)
            throws LegacyDexoptDisabledException {
        this(new Injector(context, dexManager, pm));
    }

    @VisibleForTesting
    public BackgroundDexOptService(Injector injector) throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();
        mInjector = injector;
        mDexOptHelper = mInjector.getDexOptHelper();
        LocalServices.addService(BackgroundDexOptService.class, this);
        mDowngradeUnusedAppsThresholdInMillis = mInjector.getDowngradeUnusedAppsThresholdInMillis();
    }

    /** Start scheduling job after boot completion */
    public void systemReady() throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();
        if (mInjector.isBackgroundDexOptDisabled()) {
            return;
        }

        mInjector.getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mInjector.getContext().unregisterReceiver(this);
                // queue both job. JOB_IDLE_OPTIMIZE will not start until JOB_POST_BOOT_UPDATE is
                // completed.
                scheduleAJob(JOB_POST_BOOT_UPDATE);
                scheduleAJob(JOB_IDLE_OPTIMIZE);
                if (DEBUG) {
                    Slog.d(TAG, "BootBgDexopt scheduled");
                }
            }
        }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
    }

    /** Dump the current state */
    public void dump(IndentingPrintWriter writer) {
        boolean disabled = mInjector.isBackgroundDexOptDisabled();
        writer.print("enabled:");
        writer.println(!disabled);
        if (disabled) {
            return;
        }
        synchronized (mLock) {
            writer.print("mDexOptThread:");
            writer.println(mDexOptThread);
            writer.print("mDexOptCancellingThread:");
            writer.println(mDexOptCancellingThread);
            writer.print("mFinishedPostBootUpdate:");
            writer.println(mFinishedPostBootUpdate);
            writer.print("mDisableJobSchedulerJobs:");
            writer.println(mDisableJobSchedulerJobs);
            writer.print("mLastExecutionStatus:");
            writer.println(mLastExecutionStatus);
            writer.print("mLastExecutionStartUptimeMs:");
            writer.println(mLastExecutionStartUptimeMs);
            writer.print("mLastExecutionDurationMs:");
            writer.println(mLastExecutionDurationMs);
            writer.print("now:");
            writer.println(SystemClock.elapsedRealtime());
            writer.print("mLastCancelledPackages:");
            writer.println(String.join(",", mLastCancelledPackages));
            writer.print("mFailedPackageNamesPrimary:");
            writer.println(String.join(",", mFailedPackageNamesPrimary));
            writer.print("mFailedPackageNamesSecondary:");
            writer.println(String.join(",", mFailedPackageNamesSecondary));
        }
    }

    /** Gets the instance of the service */
    public static BackgroundDexOptService getService() {
        return LocalServices.getService(BackgroundDexOptService.class);
    }

    /**
     * Executes the background dexopt job immediately for selected packages or all packages.
     *
     * <p>This is only for shell command and only root or shell user can use this.
     *
     * @param packageNames dex optimize the passed packages in the given order, or all packages in
     *         the default order if null
     *
     * @return true if dex optimization is complete. false if the task is cancelled or if there was
     *         an error.
     */
    public boolean runBackgroundDexoptJob(@Nullable List<String> packageNames)
            throws LegacyDexoptDisabledException {
        enforceRootOrShell();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                // Do not cancel and wait for completion if there is pending task.
                waitForDexOptThreadToFinishLocked();
                resetStatesForNewDexOptRunLocked(Thread.currentThread());
            }
            PackageManagerService pm = mInjector.getPackageManagerService();
            List<String> packagesToOptimize;
            if (packageNames == null) {
                packagesToOptimize = mDexOptHelper.getOptimizablePackages(pm.snapshotComputer());
            } else {
                packagesToOptimize = packageNames;
            }
            return runIdleOptimization(pm, packagesToOptimize, /* isPostBootUpdate= */ false);
        } finally {
            Binder.restoreCallingIdentity(identity);
            markDexOptCompleted();
        }
    }

    /**
     * Cancels currently running any idle optimization tasks started from JobScheduler
     * or runIdleOptimization call.
     *
     * <p>This is only for shell command and only root or shell user can use this.
     */
    public void cancelBackgroundDexoptJob() throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();
        enforceRootOrShell();
        Binder.withCleanCallingIdentity(() -> cancelDexOptAndWaitForCompletion());
    }

    /**
     * Sets a flag that disables jobs from being started from JobScheduler.
     *
     * This state is not persistent and is only retained in this service instance.
     *
     * This is intended for shell command use and only root or shell users can call it.
     *
     * @param disable True if JobScheduler invocations should be disabled, false otherwise.
     */
    public void setDisableJobSchedulerJobs(boolean disable) throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();
        enforceRootOrShell();
        synchronized (mLock) {
            mDisableJobSchedulerJobs = disable;
        }
    }

    /** Adds listener for package update */
    public void addPackagesUpdatedListener(PackagesUpdatedListener listener)
            throws LegacyDexoptDisabledException {
        // TODO(b/251903639): Evaluate whether this needs to support ART Service or not.
        Installer.checkLegacyDexoptDisabled();
        synchronized (mLock) {
            mPackagesUpdatedListeners.add(listener);
        }
    }

    /** Removes package update listener */
    public void removePackagesUpdatedListener(PackagesUpdatedListener listener)
            throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();
        synchronized (mLock) {
            mPackagesUpdatedListeners.remove(listener);
        }
    }

    /**
     * Notifies package change and removes the package from the failed package list so that
     * the package can run dexopt again.
     */
    public void notifyPackageChanged(String packageName) throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();
        // The idle maintenance job skips packages which previously failed to
        // compile. The given package has changed and may successfully compile
        // now. Remove it from the list of known failing packages.
        synchronized (mLock) {
            mFailedPackageNamesPrimary.remove(packageName);
            mFailedPackageNamesSecondary.remove(packageName);
        }
    }

    /** For BackgroundDexOptJobService to dispatch onStartJob event */
    /* package */ boolean onStartJob(BackgroundDexOptJobService job, JobParameters params) {
        Slog.i(TAG, "onStartJob:" + params.getJobId());

        boolean isPostBootUpdateJob = params.getJobId() == JOB_POST_BOOT_UPDATE;
        // NOTE: PackageManagerService.isStorageLow uses a different set of criteria from
        // the checks above. This check is not "live" - the value is determined by a background
        // restart with a period of ~1 minute.
        PackageManagerService pm = mInjector.getPackageManagerService();
        if (pm.isStorageLow()) {
            Slog.w(TAG, "Low storage, skipping this run");
            markPostBootUpdateCompleted(params);
            return false;
        }

        List<String> pkgs = mDexOptHelper.getOptimizablePackages(pm.snapshotComputer());
        if (pkgs.isEmpty()) {
            Slog.i(TAG, "No packages to optimize");
            markPostBootUpdateCompleted(params);
            return false;
        }

        mThermalStatusCutoff = mInjector.getDexOptThermalCutoff();

        synchronized (mLock) {
            if (mDisableJobSchedulerJobs) {
                Slog.i(TAG, "JobScheduler invocations disabled");
                return false;
            }
            if (mDexOptThread != null && mDexOptThread.isAlive()) {
                // Other task is already running.
                return false;
            }
            if (!isPostBootUpdateJob && !mFinishedPostBootUpdate) {
                // Post boot job not finished yet. Run post boot job first.
                return false;
            }
            try {
                resetStatesForNewDexOptRunLocked(mInjector.createAndStartThread(
                        "BackgroundDexOptService_" + (isPostBootUpdateJob ? "PostBoot" : "Idle"),
                        () -> {
                            TimingsTraceAndSlog tr =
                                    new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_DALVIK);
                            tr.traceBegin("jobExecution");
                            boolean completed = false;
                            boolean fatalError = false;
                            try {
                                completed = runIdleOptimization(
                                        pm, pkgs, params.getJobId() == JOB_POST_BOOT_UPDATE);
                            } catch (LegacyDexoptDisabledException e) {
                                Slog.wtf(TAG, e);
                            } catch (RuntimeException e) {
                                fatalError = true;
                                throw e;
                            } finally { // Those cleanup should be done always.
                                tr.traceEnd();
                                Slog.i(TAG,
                                        "dexopt finishing. jobid:" + params.getJobId()
                                                + " completed:" + completed);

                                writeStatsLog(params);

                                if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
                                    if (completed) {
                                        markPostBootUpdateCompleted(params);
                                    }
                                }
                                // Reschedule when cancelled. No need to reschedule when failed with
                                // fatal error because it's likely to fail again.
                                job.jobFinished(params, !completed && !fatalError);
                                markDexOptCompleted();
                            }
                        }));
            } catch (LegacyDexoptDisabledException e) {
                Slog.wtf(TAG, e);
            }
        }
        return true;
    }

    /** For BackgroundDexOptJobService to dispatch onStopJob event */
    /* package */ boolean onStopJob(BackgroundDexOptJobService job, JobParameters params) {
        Slog.i(TAG, "onStopJob:" + params.getJobId());
        // This cannot block as it is in main thread, thus dispatch to a newly created thread
        // and cancel it from there. As this event does not happen often, creating a new thread
        // is justified rather than having one thread kept permanently.
        mInjector.createAndStartThread("DexOptCancel", () -> {
            try {
                cancelDexOptAndWaitForCompletion();
            } catch (LegacyDexoptDisabledException e) {
                Slog.wtf(TAG, e);
            }
        });
        // Always reschedule for cancellation.
        return true;
    }

    /**
     * Cancels pending dexopt and wait for completion of the cancellation. This can block the caller
     * until cancellation is done.
     */
    private void cancelDexOptAndWaitForCompletion() throws LegacyDexoptDisabledException {
        synchronized (mLock) {
            if (mDexOptThread == null) {
                return;
            }
            if (mDexOptCancellingThread != null && mDexOptCancellingThread.isAlive()) {
                // No control, just wait
                waitForDexOptThreadToFinishLocked();
                // Do not wait for other cancellation's complete. That will be handled by the next
                // start flow.
                return;
            }
            mDexOptCancellingThread = Thread.currentThread();
            // Take additional caution to make sure that we do not leave this call
            // with controlDexOptBlockingLocked(true) state.
            try {
                controlDexOptBlockingLocked(true);
                waitForDexOptThreadToFinishLocked();
            } finally {
                // Reset to default states regardless of previous states
                mDexOptCancellingThread = null;
                mDexOptThread = null;
                controlDexOptBlockingLocked(false);
                mLock.notifyAll();
            }
        }
    }

    @GuardedBy("mLock")
    private void waitForDexOptThreadToFinishLocked() {
        TimingsTraceAndSlog tr = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_PACKAGE_MANAGER);
        // This tracing section doesn't have any correspondence in ART Service - it never waits for
        // cancellation to finish.
        tr.traceBegin("waitForDexOptThreadToFinishLocked");
        try {
            // Wait but check in regular internal to see if the thread is still alive.
            while (mDexOptThread != null && mDexOptThread.isAlive()) {
                mLock.wait(CANCELLATION_WAIT_CHECK_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Slog.w(TAG, "Interrupted while waiting for dexopt thread");
            Thread.currentThread().interrupt();
        }
        tr.traceEnd();
    }

    private void markDexOptCompleted() {
        synchronized (mLock) {
            if (mDexOptThread != Thread.currentThread()) {
                throw new IllegalStateException(
                        "Only mDexOptThread can mark completion, mDexOptThread:" + mDexOptThread
                        + " current:" + Thread.currentThread());
            }
            mDexOptThread = null;
            // Other threads may be waiting for completion.
            mLock.notifyAll();
        }
    }

    @GuardedBy("mLock")
    private void resetStatesForNewDexOptRunLocked(Thread thread)
            throws LegacyDexoptDisabledException {
        mDexOptThread = thread;
        mLastCancelledPackages.clear();
        controlDexOptBlockingLocked(false);
    }

    private void enforceRootOrShell() {
        int uid = mInjector.getCallingUid();
        if (uid != Process.ROOT_UID && uid != Process.SHELL_UID) {
            throw new SecurityException("Should be shell or root user");
        }
    }

    @GuardedBy("mLock")
    private void controlDexOptBlockingLocked(boolean block) throws LegacyDexoptDisabledException {
        PackageManagerService pm = mInjector.getPackageManagerService();
        mDexOptHelper.controlDexOptBlocking(block);
    }

    private void scheduleAJob(int jobId) {
        JobScheduler js = mInjector.getJobScheduler();
        JobInfo.Builder builder =
                new JobInfo.Builder(jobId, sDexoptServiceName).setRequiresDeviceIdle(true);
        if (jobId == JOB_IDLE_OPTIMIZE) {
            builder.setRequiresCharging(true).setPeriodic(IDLE_OPTIMIZATION_PERIOD);
        }
        js.schedule(builder.build());
    }

    private long getLowStorageThreshold() {
        long lowThreshold = mInjector.getDataDirStorageLowBytes();
        if (lowThreshold == 0) {
            Slog.e(TAG, "Invalid low storage threshold");
        }

        return lowThreshold;
    }

    private void logStatus(int status) {
        switch (status) {
            case STATUS_OK:
                Slog.i(TAG, "Idle optimizations completed.");
                break;
            case STATUS_ABORT_NO_SPACE_LEFT:
                Slog.w(TAG, "Idle optimizations aborted because of space constraints.");
                break;
            case STATUS_ABORT_BY_CANCELLATION:
                Slog.w(TAG, "Idle optimizations aborted by cancellation.");
                break;
            case STATUS_ABORT_THERMAL:
                Slog.w(TAG, "Idle optimizations aborted by thermal throttling.");
                break;
            case STATUS_ABORT_BATTERY:
                Slog.w(TAG, "Idle optimizations aborted by low battery.");
                break;
            case STATUS_DEX_OPT_FAILED:
                Slog.w(TAG, "Idle optimizations failed from dexopt.");
                break;
            default:
                Slog.w(TAG, "Idle optimizations ended with unexpected code: " + status);
                break;
        }
    }

    /**
     * Returns whether we've successfully run the job. Note that it will return true even if some
     * packages may have failed compiling.
     */
    private boolean runIdleOptimization(PackageManagerService pm, List<String> pkgs,
            boolean isPostBootUpdate) throws LegacyDexoptDisabledException {
        synchronized (mLock) {
            mLastExecutionStatus = STATUS_UNSPECIFIED;
            mLastExecutionStartUptimeMs = SystemClock.uptimeMillis();
            mLastExecutionDurationMs = -1;
        }

        int status = STATUS_UNSPECIFIED;
        try {
            long lowStorageThreshold = getLowStorageThreshold();
            status = idleOptimizePackages(pm, pkgs, lowStorageThreshold, isPostBootUpdate);
            logStatus(status);
            return status == STATUS_OK || status == STATUS_DEX_OPT_FAILED;
        } catch (RuntimeException e) {
            status = STATUS_FATAL_ERROR;
            throw e;
        } finally {
            synchronized (mLock) {
                mLastExecutionStatus = status;
                mLastExecutionDurationMs = SystemClock.uptimeMillis() - mLastExecutionStartUptimeMs;
            }
        }
    }

    /** Gets the size of the directory. It uses recursion to go over all files. */
    private long getDirectorySize(File f) {
        long size = 0;
        if (f.isDirectory()) {
            for (File file : f.listFiles()) {
                size += getDirectorySize(file);
            }
        } else {
            size = f.length();
        }
        return size;
    }

    /** Gets the size of a package. */
    private long getPackageSize(@NonNull Computer snapshot, String pkg) {
        // TODO(b/251903639): Make this in line with the calculation in
        // `DexOptHelper.DexoptDoneHandler`.
        PackageInfo info = snapshot.getPackageInfo(pkg, 0, UserHandle.USER_SYSTEM);
        long size = 0;
        if (info != null && info.applicationInfo != null) {
            File path = Paths.get(info.applicationInfo.sourceDir).toFile();
            if (path.isFile()) {
                path = path.getParentFile();
            }
            size += getDirectorySize(path);
            if (!ArrayUtils.isEmpty(info.applicationInfo.splitSourceDirs)) {
                for (String splitSourceDir : info.applicationInfo.splitSourceDirs) {
                    File pathSplitSourceDir = Paths.get(splitSourceDir).toFile();
                    if (pathSplitSourceDir.isFile()) {
                        pathSplitSourceDir = pathSplitSourceDir.getParentFile();
                    }
                    if (path.getAbsolutePath().equals(pathSplitSourceDir.getAbsolutePath())) {
                        continue;
                    }
                    size += getDirectorySize(pathSplitSourceDir);
                }
            }
            return size;
        }
        return 0;
    }

    @Status
    private int idleOptimizePackages(PackageManagerService pm, List<String> pkgs,
            long lowStorageThreshold, boolean isPostBootUpdate)
            throws LegacyDexoptDisabledException {
        ArraySet<String> updatedPackages = new ArraySet<>();

        try {
            boolean supportSecondaryDex = mInjector.supportSecondaryDex();

            if (supportSecondaryDex) {
                @Status int result = reconcileSecondaryDexFiles();
                if (result != STATUS_OK) {
                    return result;
                }
            }

            // Only downgrade apps when space is low on device.
            // Threshold is selected above the lowStorageThreshold so that we can pro-actively clean
            // up disk before user hits the actual lowStorageThreshold.
            long lowStorageThresholdForDowngrade =
                    LOW_THRESHOLD_MULTIPLIER_FOR_DOWNGRADE * lowStorageThreshold;
            boolean shouldDowngrade = shouldDowngrade(lowStorageThresholdForDowngrade);
            if (DEBUG) {
                Slog.d(TAG, "Should Downgrade " + shouldDowngrade);
            }
            if (shouldDowngrade) {
                final Computer snapshot = pm.snapshotComputer();
                Set<String> unusedPackages =
                        snapshot.getUnusedPackages(mDowngradeUnusedAppsThresholdInMillis);
                if (DEBUG) {
                    Slog.d(TAG, "Unsused Packages " + String.join(",", unusedPackages));
                }

                if (!unusedPackages.isEmpty()) {
                    for (String pkg : unusedPackages) {
                        @Status int abortCode = abortIdleOptimizations(/*lowStorageThreshold*/ -1);
                        if (abortCode != STATUS_OK) {
                            // Should be aborted by the scheduler.
                            return abortCode;
                        }
                        @DexOptResult
                        int downgradeResult = downgradePackage(snapshot, pm, pkg,
                                /* isForPrimaryDex= */ true, isPostBootUpdate);
                        if (downgradeResult == PackageDexOptimizer.DEX_OPT_PERFORMED) {
                            updatedPackages.add(pkg);
                        }
                        @Status
                        int status = convertPackageDexOptimizerStatusToInternal(downgradeResult);
                        if (status != STATUS_OK) {
                            return status;
                        }
                        if (supportSecondaryDex) {
                            downgradeResult = downgradePackage(snapshot, pm, pkg,
                                    /* isForPrimaryDex= */ false, isPostBootUpdate);
                            status = convertPackageDexOptimizerStatusToInternal(downgradeResult);
                            if (status != STATUS_OK) {
                                return status;
                            }
                        }
                    }

                    pkgs = new ArrayList<>(pkgs);
                    pkgs.removeAll(unusedPackages);
                }
            }

            return optimizePackages(pkgs, lowStorageThreshold, updatedPackages, isPostBootUpdate);
        } finally {
            // Always let the pinner service know about changes.
            // TODO(b/251903639): ART Service does this for all dexopts, while the code below only
            // runs for background jobs. We should try to make them behave the same.
            notifyPinService(updatedPackages);
            // Only notify IORap the primary dex opt, because we don't want to
            // invalidate traces unnecessary due to b/161633001 and that it's
            // better to have a trace than no trace at all.
            notifyPackagesUpdated(updatedPackages);
        }
    }

    @Status
    private int optimizePackages(List<String> pkgs, long lowStorageThreshold,
            ArraySet<String> updatedPackages, boolean isPostBootUpdate)
            throws LegacyDexoptDisabledException {
        boolean supportSecondaryDex = mInjector.supportSecondaryDex();

        // Keep the error if there is any error from any package.
        @Status int status = STATUS_OK;

        // Other than cancellation, all packages will be processed even if an error happens
        // in a package.
        for (String pkg : pkgs) {
            int abortCode = abortIdleOptimizations(lowStorageThreshold);
            if (abortCode != STATUS_OK) {
                // Either aborted by the scheduler or no space left.
                return abortCode;
            }

            @DexOptResult
            int primaryResult = optimizePackage(pkg, true /* isForPrimaryDex */, isPostBootUpdate);
            if (primaryResult == PackageDexOptimizer.DEX_OPT_CANCELLED) {
                return STATUS_ABORT_BY_CANCELLATION;
            }
            if (primaryResult == PackageDexOptimizer.DEX_OPT_PERFORMED) {
                updatedPackages.add(pkg);
            } else if (primaryResult == PackageDexOptimizer.DEX_OPT_FAILED) {
                status = convertPackageDexOptimizerStatusToInternal(primaryResult);
            }

            if (!supportSecondaryDex) {
                continue;
            }

            @DexOptResult
            int secondaryResult =
                    optimizePackage(pkg, false /* isForPrimaryDex */, isPostBootUpdate);
            if (secondaryResult == PackageDexOptimizer.DEX_OPT_CANCELLED) {
                return STATUS_ABORT_BY_CANCELLATION;
            }
            if (secondaryResult == PackageDexOptimizer.DEX_OPT_FAILED) {
                status = convertPackageDexOptimizerStatusToInternal(secondaryResult);
            }
        }
        return status;
    }

    /**
     * Try to downgrade the package to a smaller compilation filter.
     * eg. if the package is in speed-profile the package will be downgraded to verify.
     * @param pm PackageManagerService
     * @param pkg The package to be downgraded.
     * @param isForPrimaryDex Apps can have several dex file, primary and secondary.
     * @return PackageDexOptimizer.DEX_*
     */
    @DexOptResult
    private int downgradePackage(@NonNull Computer snapshot, PackageManagerService pm, String pkg,
            boolean isForPrimaryDex, boolean isPostBootUpdate)
            throws LegacyDexoptDisabledException {
        if (DEBUG) {
            Slog.d(TAG, "Downgrading " + pkg);
        }
        if (isCancelling()) {
            return PackageDexOptimizer.DEX_OPT_CANCELLED;
        }
        int reason = PackageManagerService.REASON_INACTIVE_PACKAGE_DOWNGRADE;
        String filter = getCompilerFilterForReason(reason);
        int dexoptFlags = DexoptOptions.DEXOPT_BOOT_COMPLETE | DexoptOptions.DEXOPT_DOWNGRADE;

        if (isProfileGuidedCompilerFilter(filter)) {
            // We don't expect updates in current profiles to be significant here, but
            // DEXOPT_CHECK_FOR_PROFILES_UPDATES is set to replicate behaviour that will be
            // unconditionally enabled for profile guided filters when ART Service is called instead
            // of the legacy PackageDexOptimizer implementation.
            dexoptFlags |= DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES;
        }

        if (!isPostBootUpdate) {
            dexoptFlags |= DexoptOptions.DEXOPT_IDLE_BACKGROUND_JOB;
        }

        long package_size_before = getPackageSize(snapshot, pkg);
        int result = PackageDexOptimizer.DEX_OPT_SKIPPED;
        if (isForPrimaryDex || PLATFORM_PACKAGE_NAME.equals(pkg)) {
            // This applies for system apps or if packages location is not a directory, i.e.
            // monolithic install.
            if (!pm.canHaveOatDir(snapshot, pkg)) {
                // For apps that don't have the oat directory, instead of downgrading,
                // remove their compiler artifacts from dalvik cache.
                pm.deleteOatArtifactsOfPackage(snapshot, pkg);
            } else {
                result = performDexOptPrimary(pkg, reason, filter, dexoptFlags);
            }
        } else {
            result = performDexOptSecondary(pkg, reason, filter, dexoptFlags);
        }

        if (result == PackageDexOptimizer.DEX_OPT_PERFORMED) {
            final Computer newSnapshot = pm.snapshotComputer();
            FrameworkStatsLog.write(FrameworkStatsLog.APP_DOWNGRADED, pkg, package_size_before,
                    getPackageSize(newSnapshot, pkg), /*aggressive=*/false);
        }
        return result;
    }

    @Status
    private int reconcileSecondaryDexFiles() throws LegacyDexoptDisabledException {
        // TODO(calin): should we denylist packages for which we fail to reconcile?
        for (String p : mInjector.getDexManager().getAllPackagesWithSecondaryDexFiles()) {
            if (isCancelling()) {
                return STATUS_ABORT_BY_CANCELLATION;
            }
            mInjector.getDexManager().reconcileSecondaryDexFiles(p);
        }
        return STATUS_OK;
    }

    /**
     *
     * Optimize package if needed. Note that there can be no race between
     * concurrent jobs because PackageDexOptimizer.performDexOpt is synchronized.
     * @param pkg The package to be downgraded.
     * @param isForPrimaryDex Apps can have several dex file, primary and secondary.
     * @param isPostBootUpdate is post boot update or not.
     * @return PackageDexOptimizer#DEX_OPT_*
     */
    @DexOptResult
    private int optimizePackage(String pkg, boolean isForPrimaryDex, boolean isPostBootUpdate)
            throws LegacyDexoptDisabledException {
        int reason = isPostBootUpdate ? PackageManagerService.REASON_POST_BOOT
                                      : PackageManagerService.REASON_BACKGROUND_DEXOPT;
        String filter = getCompilerFilterForReason(reason);

        int dexoptFlags = DexoptOptions.DEXOPT_BOOT_COMPLETE;
        if (!isPostBootUpdate) {
            dexoptFlags |= DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES
                    | DexoptOptions.DEXOPT_IDLE_BACKGROUND_JOB;
        }

        if (isProfileGuidedCompilerFilter(filter)) {
            // Ensure DEXOPT_CHECK_FOR_PROFILES_UPDATES is enabled if the filter is profile guided,
            // to replicate behaviour that will be unconditionally enabled when ART Service is
            // called instead of the legacy PackageDexOptimizer implementation.
            dexoptFlags |= DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES;
        }

        // System server share the same code path as primary dex files.
        // PackageManagerService will select the right optimization path for it.
        if (isForPrimaryDex || PLATFORM_PACKAGE_NAME.equals(pkg)) {
            return performDexOptPrimary(pkg, reason, filter, dexoptFlags);
        } else {
            return performDexOptSecondary(pkg, reason, filter, dexoptFlags);
        }
    }

    @DexOptResult
    private int performDexOptPrimary(String pkg, int reason, String filter, int dexoptFlags)
            throws LegacyDexoptDisabledException {
        DexoptOptions dexoptOptions =
                new DexoptOptions(pkg, reason, filter, /*splitName=*/null, dexoptFlags);
        return trackPerformDexOpt(pkg, /*isForPrimaryDex=*/true,
                () -> mDexOptHelper.performDexOptWithStatus(dexoptOptions));
    }

    @DexOptResult
    private int performDexOptSecondary(String pkg, int reason, String filter, int dexoptFlags)
            throws LegacyDexoptDisabledException {
        DexoptOptions dexoptOptions = new DexoptOptions(pkg, reason, filter, /*splitName=*/null,
                dexoptFlags | DexoptOptions.DEXOPT_ONLY_SECONDARY_DEX);
        return trackPerformDexOpt(pkg, /*isForPrimaryDex=*/false,
                ()
                        -> mDexOptHelper.performDexOpt(dexoptOptions)
                        ? PackageDexOptimizer.DEX_OPT_PERFORMED
                        : PackageDexOptimizer.DEX_OPT_FAILED);
    }

    /**
     * Execute the dexopt wrapper and make sure that if performDexOpt wrapper fails
     * the package is added to the list of failed packages.
     * Return one of following result:
     *  {@link PackageDexOptimizer#DEX_OPT_SKIPPED}
     *  {@link PackageDexOptimizer#DEX_OPT_CANCELLED}
     *  {@link PackageDexOptimizer#DEX_OPT_PERFORMED}
     *  {@link PackageDexOptimizer#DEX_OPT_FAILED}
     */
    @DexOptResult
    private int trackPerformDexOpt(String pkg, boolean isForPrimaryDex,
            ThrowingCheckedSupplier<Integer, LegacyDexoptDisabledException> performDexOptWrapper)
            throws LegacyDexoptDisabledException {
        ArraySet<String> failedPackageNames;
        synchronized (mLock) {
            failedPackageNames =
                    isForPrimaryDex ? mFailedPackageNamesPrimary : mFailedPackageNamesSecondary;
            if (failedPackageNames.contains(pkg)) {
                // Skip previously failing package
                return PackageDexOptimizer.DEX_OPT_SKIPPED;
            }
        }
        int result = performDexOptWrapper.get();
        if (result == PackageDexOptimizer.DEX_OPT_FAILED) {
            synchronized (mLock) {
                failedPackageNames.add(pkg);
            }
        } else if (result == PackageDexOptimizer.DEX_OPT_CANCELLED) {
            synchronized (mLock) {
                mLastCancelledPackages.add(pkg);
            }
        }
        return result;
    }

    @Status
    private int convertPackageDexOptimizerStatusToInternal(@DexOptResult int pdoStatus) {
        switch (pdoStatus) {
            case PackageDexOptimizer.DEX_OPT_CANCELLED:
                return STATUS_ABORT_BY_CANCELLATION;
            case PackageDexOptimizer.DEX_OPT_FAILED:
                return STATUS_DEX_OPT_FAILED;
            case PackageDexOptimizer.DEX_OPT_PERFORMED:
            case PackageDexOptimizer.DEX_OPT_SKIPPED:
                return STATUS_OK;
            default:
                Slog.e(TAG, "Unkknown error code from PackageDexOptimizer:" + pdoStatus,
                        new RuntimeException());
                return STATUS_DEX_OPT_FAILED;
        }
    }

    /** Evaluate whether or not idle optimizations should continue. */
    @Status
    private int abortIdleOptimizations(long lowStorageThreshold) {
        if (isCancelling()) {
            // JobScheduler requested an early abort.
            return STATUS_ABORT_BY_CANCELLATION;
        }

        // Abort background dexopt if the device is in a moderate or stronger thermal throttling
        // state.
        int thermalStatus = mInjector.getCurrentThermalStatus();
        if (DEBUG) {
            Log.d(TAG, "Thermal throttling status during bgdexopt: " + thermalStatus);
        }
        if (thermalStatus >= mThermalStatusCutoff) {
            return STATUS_ABORT_THERMAL;
        }

        if (mInjector.isBatteryLevelLow()) {
            return STATUS_ABORT_BATTERY;
        }

        long usableSpace = mInjector.getDataDirUsableSpace();
        if (usableSpace < lowStorageThreshold) {
            // Rather bail than completely fill up the disk.
            Slog.w(TAG, "Aborting background dex opt job due to low storage: " + usableSpace);
            return STATUS_ABORT_NO_SPACE_LEFT;
        }

        return STATUS_OK;
    }

    // Evaluate whether apps should be downgraded.
    private boolean shouldDowngrade(long lowStorageThresholdForDowngrade) {
        if (mInjector.getDataDirUsableSpace() < lowStorageThresholdForDowngrade) {
            return true;
        }

        return false;
    }

    private boolean isCancelling() {
        synchronized (mLock) {
            return mDexOptCancellingThread != null;
        }
    }

    private void markPostBootUpdateCompleted(JobParameters params) {
        if (params.getJobId() != JOB_POST_BOOT_UPDATE) {
            return;
        }
        synchronized (mLock) {
            if (!mFinishedPostBootUpdate) {
                mFinishedPostBootUpdate = true;
            }
        }
        // Safe to do this outside lock.
        mInjector.getJobScheduler().cancel(JOB_POST_BOOT_UPDATE);
    }

    private void notifyPinService(ArraySet<String> updatedPackages) {
        PinnerService pinnerService = mInjector.getPinnerService();
        if (pinnerService != null) {
            Slog.i(TAG, "Pinning optimized code " + updatedPackages);
            pinnerService.update(updatedPackages, false /* force */);
        }
    }

    /** Notify all listeners (#addPackagesUpdatedListener) that packages have been updated. */
    private void notifyPackagesUpdated(ArraySet<String> updatedPackages) {
        synchronized (mLock) {
            for (PackagesUpdatedListener listener : mPackagesUpdatedListeners) {
                listener.onPackagesUpdated(updatedPackages);
            }
        }
    }

    private void writeStatsLog(JobParameters params) {
        @Status int status;
        long durationMs;
        long durationIncludingSleepMs;
        synchronized (mLock) {
            status = mLastExecutionStatus;
            durationMs = mLastExecutionDurationMs;
        }

        mStatsLogger.write(status, params.getStopReason(), durationMs);
    }

    /** Injector pattern for testing purpose */
    @VisibleForTesting
    static final class Injector {
        private final Context mContext;
        private final DexManager mDexManager;
        private final PackageManagerService mPackageManagerService;
        private final File mDataDir = Environment.getDataDirectory();

        Injector(Context context, DexManager dexManager, PackageManagerService pm) {
            mContext = context;
            mDexManager = dexManager;
            mPackageManagerService = pm;
        }

        int getCallingUid() {
            return Binder.getCallingUid();
        }

        Context getContext() {
            return mContext;
        }

        PackageManagerService getPackageManagerService() {
            return mPackageManagerService;
        }

        DexOptHelper getDexOptHelper() {
            return new DexOptHelper(getPackageManagerService());
        }

        JobScheduler getJobScheduler() {
            return mContext.getSystemService(JobScheduler.class);
        }

        DexManager getDexManager() {
            return mDexManager;
        }

        PinnerService getPinnerService() {
            return LocalServices.getService(PinnerService.class);
        }

        boolean isBackgroundDexOptDisabled() {
            return SystemProperties.getBoolean(
                    "pm.dexopt.disable_bg_dexopt" /* key */, false /* default */);
        }

        boolean isBatteryLevelLow() {
            return LocalServices.getService(BatteryManagerInternal.class).getBatteryLevelLow();
        }

        long getDowngradeUnusedAppsThresholdInMillis() {
            String sysPropKey = "pm.dexopt.downgrade_after_inactive_days";
            String sysPropValue = SystemProperties.get(sysPropKey);
            if (sysPropValue == null || sysPropValue.isEmpty()) {
                Slog.w(TAG, "SysProp " + sysPropKey + " not set");
                return Long.MAX_VALUE;
            }
            return TimeUnit.DAYS.toMillis(Long.parseLong(sysPropValue));
        }

        boolean supportSecondaryDex() {
            return (SystemProperties.getBoolean("dalvik.vm.dexopt.secondary", false));
        }

        long getDataDirUsableSpace() {
            return mDataDir.getUsableSpace();
        }

        long getDataDirStorageLowBytes() {
            return mContext.getSystemService(StorageManager.class).getStorageLowBytes(mDataDir);
        }

        int getCurrentThermalStatus() {
            IThermalService thermalService = IThermalService.Stub.asInterface(
                    ServiceManager.getService(Context.THERMAL_SERVICE));
            try {
                return thermalService.getCurrentThermalStatus();
            } catch (RemoteException e) {
                return STATUS_ABORT_THERMAL;
            }
        }

        int getDexOptThermalCutoff() {
            return SystemProperties.getInt(
                    "dalvik.vm.dexopt.thermal-cutoff", THERMAL_CUTOFF_DEFAULT);
        }

        Thread createAndStartThread(String name, Runnable target) {
            Thread thread = new Thread(target, name);
            Slog.i(TAG, "Starting thread:" + name);
            thread.start();
            return thread;
        }
    }
}
