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

import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.BatteryManagerInternal;
import android.os.Environment;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.PinnerService;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@hide}
 */
public class BackgroundDexOptService extends JobService {
    private static final String TAG = "BackgroundDexOptService";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int JOB_IDLE_OPTIMIZE = 800;
    private static final int JOB_POST_BOOT_UPDATE = 801;

    private static final long IDLE_OPTIMIZATION_PERIOD = TimeUnit.DAYS.toMillis(1);

    private static ComponentName sDexoptServiceName = new ComponentName(
            "android",
            BackgroundDexOptService.class.getName());

    // Possible return codes of individual optimization steps.

    // Optimizations finished. All packages were processed.
    private static final int OPTIMIZE_PROCESSED = 0;
    // Optimizations should continue. Issued after checking the scheduler, disk space or battery.
    private static final int OPTIMIZE_CONTINUE = 1;
    // Optimizations should be aborted. Job scheduler requested it.
    private static final int OPTIMIZE_ABORT_BY_JOB_SCHEDULER = 2;
    // Optimizations should be aborted. No space left on device.
    private static final int OPTIMIZE_ABORT_NO_SPACE_LEFT = 3;
    // Optimizations should be aborted. Thermal throttling level too high.
    private static final int OPTIMIZE_ABORT_THERMAL = 4;

    // Used for calculating space threshold for downgrading unused apps.
    private static final int LOW_THRESHOLD_MULTIPLIER_FOR_DOWNGRADE = 2;

    // Thermal cutoff value used if one isn't defined by a system property.
    private static final int THERMAL_CUTOFF_DEFAULT = PowerManager.THERMAL_STATUS_MODERATE;

    /**
     * Set of failed packages remembered across job runs.
     */
    static final ArraySet<String> sFailedPackageNamesPrimary = new ArraySet<String>();
    static final ArraySet<String> sFailedPackageNamesSecondary = new ArraySet<String>();

    /**
     * Atomics set to true if the JobScheduler requests an abort.
     */
    private final AtomicBoolean mAbortPostBootUpdate = new AtomicBoolean(false);
    private final AtomicBoolean mAbortIdleOptimization = new AtomicBoolean(false);

    /**
     * Atomic set to true if one job should exit early because another job was started.
     */
    private final AtomicBoolean mExitPostBootUpdate = new AtomicBoolean(false);

    private final File mDataDir = Environment.getDataDirectory();
    private static final long mDowngradeUnusedAppsThresholdInMillis =
            getDowngradeUnusedAppsThresholdInMillis();

    private final IThermalService mThermalService =
            IThermalService.Stub.asInterface(
                ServiceManager.getService(Context.THERMAL_SERVICE));

    private static List<PackagesUpdatedListener> sPackagesUpdatedListeners = new ArrayList<>();

    private int mThermalStatusCutoff = THERMAL_CUTOFF_DEFAULT;

    public static void schedule(Context context) {
        if (isBackgroundDexoptDisabled()) {
            return;
        }

        final JobScheduler js = context.getSystemService(JobScheduler.class);

        // Schedule a one-off job which scans installed packages and updates
        // out-of-date oat files. Schedule it 10 minutes after the boot complete event,
        // so that we don't overload the boot with additional dex2oat compilations.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                js.schedule(new JobInfo.Builder(JOB_POST_BOOT_UPDATE, sDexoptServiceName)
                        .setMinimumLatency(TimeUnit.MINUTES.toMillis(10))
                        .setOverrideDeadline(TimeUnit.MINUTES.toMillis(60))
                        .build());
                context.unregisterReceiver(this);
                if (DEBUG) {
                    Slog.i(TAG, "BootBgDexopt scheduled");
                }
            }
        }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));

        // Schedule a daily job which scans installed packages and compiles
        // those with fresh profiling data.
        js.schedule(new JobInfo.Builder(JOB_IDLE_OPTIMIZE, sDexoptServiceName)
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .setPeriodic(IDLE_OPTIMIZATION_PERIOD)
                    .build());

        if (DEBUG) {
            Slog.d(TAG, "BgDexopt scheduled");
        }
    }

    public static void notifyPackageChanged(String packageName) {
        // The idle maintanance job skips packages which previously failed to
        // compile. The given package has changed and may successfully compile
        // now. Remove it from the list of known failing packages.
        synchronized (sFailedPackageNamesPrimary) {
            sFailedPackageNamesPrimary.remove(packageName);
        }
        synchronized (sFailedPackageNamesSecondary) {
            sFailedPackageNamesSecondary.remove(packageName);
        }
    }

    private long getLowStorageThreshold(Context context) {
        @SuppressWarnings("deprecation")
        final long lowThreshold = StorageManager.from(context).getStorageLowBytes(mDataDir);
        if (lowThreshold == 0) {
            Slog.e(TAG, "Invalid low storage threshold");
        }

        return lowThreshold;
    }

    private boolean runPostBootUpdate(final JobParameters jobParams,
            final PackageManagerService pm, final ArraySet<String> pkgs) {
        if (mExitPostBootUpdate.get()) {
            // This job has already been superseded. Do not start it.
            return false;
        }
        new Thread("BackgroundDexOptService_PostBootUpdate") {
            @Override
            public void run() {
                postBootUpdate(jobParams, pm, pkgs);
            }

        }.start();
        return true;
    }

    private void postBootUpdate(JobParameters jobParams, PackageManagerService pm,
            ArraySet<String> pkgs) {
        final BatteryManagerInternal batteryManagerInternal =
                LocalServices.getService(BatteryManagerInternal.class);
        final long lowThreshold = getLowStorageThreshold(this);

        mAbortPostBootUpdate.set(false);

        ArraySet<String> updatedPackages = new ArraySet<>();
        for (String pkg : pkgs) {
            if (mAbortPostBootUpdate.get()) {
                // JobScheduler requested an early abort.
                return;
            }
            if (mExitPostBootUpdate.get()) {
                // Different job, which supersedes this one, is running.
                break;
            }
            if (batteryManagerInternal.getBatteryLevelLow()) {
                // Rather bail than completely drain the battery.
                break;
            }
            long usableSpace = mDataDir.getUsableSpace();
            if (usableSpace < lowThreshold) {
                // Rather bail than completely fill up the disk.
                Slog.w(TAG, "Aborting background dex opt job due to low storage: " +
                        usableSpace);
                break;
            }
            if (DEBUG) {
                Slog.i(TAG, "Updating package " + pkg);
            }

            // Update package if needed. Note that there can be no race between concurrent
            // jobs because PackageDexOptimizer.performDexOpt is synchronized.

            // checkProfiles is false to avoid merging profiles during boot which
            // might interfere with background compilation (b/28612421).
            // Unfortunately this will also means that "pm.dexopt.boot=speed-profile" will
            // behave differently than "pm.dexopt.bg-dexopt=speed-profile" but that's a
            // trade-off worth doing to save boot time work.
            int result = pm.performDexOptWithStatus(new DexoptOptions(
                    pkg,
                    PackageManagerService.REASON_POST_BOOT,
                    DexoptOptions.DEXOPT_BOOT_COMPLETE));
            if (result == PackageDexOptimizer.DEX_OPT_PERFORMED)  {
                updatedPackages.add(pkg);
            }
        }
        notifyPinService(updatedPackages);
        notifyPackagesUpdated(updatedPackages);
        // Ran to completion, so we abandon our timeslice and do not reschedule.
        jobFinished(jobParams, /* reschedule */ false);
    }

    private boolean runIdleOptimization(final JobParameters jobParams,
            final PackageManagerService pm, final ArraySet<String> pkgs) {
        new Thread("BackgroundDexOptService_IdleOptimization") {
            @Override
            public void run() {
                int result = idleOptimization(pm, pkgs, BackgroundDexOptService.this);
                if (result == OPTIMIZE_PROCESSED) {
                    Slog.i(TAG, "Idle optimizations completed.");
                } else if (result == OPTIMIZE_ABORT_NO_SPACE_LEFT) {
                    Slog.w(TAG, "Idle optimizations aborted because of space constraints.");
                } else if (result == OPTIMIZE_ABORT_BY_JOB_SCHEDULER) {
                    Slog.w(TAG, "Idle optimizations aborted by job scheduler.");
                } else if (result == OPTIMIZE_ABORT_THERMAL) {
                    Slog.w(TAG, "Idle optimizations aborted by thermal throttling.");
                } else {
                    Slog.w(TAG, "Idle optimizations ended with unexpected code: " + result);
                }

                if (result == OPTIMIZE_ABORT_THERMAL) {
                    // Abandon our timeslice and reschedule
                    jobFinished(jobParams, /* wantsReschedule */ true);
                } else if (result != OPTIMIZE_ABORT_BY_JOB_SCHEDULER) {
                    // Abandon our timeslice and do not reschedule.
                    jobFinished(jobParams, /* wantsReschedule */ false);
                }
            }
        }.start();
        return true;
    }

    // Optimize the given packages and return the optimization result (one of the OPTIMIZE_* codes).
    private int idleOptimization(PackageManagerService pm, ArraySet<String> pkgs,
            Context context) {
        Slog.i(TAG, "Performing idle optimizations");
        // If post-boot update is still running, request that it exits early.
        mExitPostBootUpdate.set(true);
        mAbortIdleOptimization.set(false);

        long lowStorageThreshold = getLowStorageThreshold(context);
        int result = idleOptimizePackages(pm, pkgs, lowStorageThreshold);
        return result;
    }

    /**
     * Get the size of the directory. It uses recursion to go over all files.
     * @param f
     * @return
     */
    private long getDirectorySize(File f) {
        long size = 0;
        if (f.isDirectory()) {
            for (File file: f.listFiles()) {
                size += getDirectorySize(file);
            }
        } else {
            size = f.length();
        }
        return size;
    }

    /**
     * Get the size of a package.
     * @param pkg
     */
    private long getPackageSize(PackageManagerService pm, String pkg) {
        PackageInfo info = pm.getPackageInfo(pkg, 0, UserHandle.USER_SYSTEM);
        long size = 0;
        if (info != null && info.applicationInfo != null) {
            File path = Paths.get(info.applicationInfo.sourceDir).toFile();
            if (path.isFile()) {
                path = path.getParentFile();
            }
            size += getDirectorySize(path);
            if (!ArrayUtils.isEmpty(info.applicationInfo.splitSourceDirs)) {
                for (String splitSourceDir : info.applicationInfo.splitSourceDirs) {
                    path = Paths.get(splitSourceDir).toFile();
                    if (path.isFile()) {
                        path = path.getParentFile();
                    }
                    size += getDirectorySize(path);
                }
            }
            return size;
        }
        return 0;
    }

    private int idleOptimizePackages(PackageManagerService pm, ArraySet<String> pkgs,
            long lowStorageThreshold) {
        ArraySet<String> updatedPackages = new ArraySet<>();
        ArraySet<String> updatedPackagesDueToSecondaryDex = new ArraySet<>();

        try {
            final boolean supportSecondaryDex = supportSecondaryDex();

            if (supportSecondaryDex) {
                int result = reconcileSecondaryDexFiles(pm.getDexManager());
                if (result == OPTIMIZE_ABORT_BY_JOB_SCHEDULER) {
                    return result;
                }
            }

            // Only downgrade apps when space is low on device.
            // Threshold is selected above the lowStorageThreshold so that we can pro-actively clean
            // up disk before user hits the actual lowStorageThreshold.
            final long lowStorageThresholdForDowngrade = LOW_THRESHOLD_MULTIPLIER_FOR_DOWNGRADE
                    * lowStorageThreshold;
            boolean shouldDowngrade = shouldDowngrade(lowStorageThresholdForDowngrade);
            if (DEBUG) {
                Slog.d(TAG, "Should Downgrade " + shouldDowngrade);
            }
            if (shouldDowngrade) {
                Set<String> unusedPackages =
                        pm.getUnusedPackages(mDowngradeUnusedAppsThresholdInMillis);
                if (DEBUG) {
                    Slog.d(TAG, "Unsused Packages " +  String.join(",", unusedPackages));
                }

                if (!unusedPackages.isEmpty()) {
                    for (String pkg : unusedPackages) {
                        int abortCode = abortIdleOptimizations(/*lowStorageThreshold*/ -1);
                        if (abortCode != OPTIMIZE_CONTINUE) {
                            // Should be aborted by the scheduler.
                            return abortCode;
                        }
                        if (downgradePackage(pm, pkg, /*isForPrimaryDex*/ true)) {
                            updatedPackages.add(pkg);
                        }
                        if (supportSecondaryDex) {
                            downgradePackage(pm, pkg, /*isForPrimaryDex*/ false);
                        }
                    }

                    pkgs = new ArraySet<>(pkgs);
                    pkgs.removeAll(unusedPackages);
                }
            }

            int primaryResult = optimizePackages(pm, pkgs, lowStorageThreshold,
                    /*isForPrimaryDex*/ true, updatedPackages);
            if (primaryResult != OPTIMIZE_PROCESSED) {
                return primaryResult;
            }

            if (!supportSecondaryDex) {
                return OPTIMIZE_PROCESSED;
            }

            int secondaryResult = optimizePackages(pm, pkgs, lowStorageThreshold,
                    /*isForPrimaryDex*/ false, updatedPackagesDueToSecondaryDex);
            return secondaryResult;
        } finally {
            // Always let the pinner service know about changes.
            notifyPinService(updatedPackages);
            // Only notify IORap the primary dex opt, because we don't want to
            // invalidate traces unnecessary due to b/161633001 and that it's
            // better to have a trace than no trace at all.
            notifyPackagesUpdated(updatedPackages);
        }
    }

    private int optimizePackages(PackageManagerService pm, ArraySet<String> pkgs,
            long lowStorageThreshold, boolean isForPrimaryDex, ArraySet<String> updatedPackages) {
        for (String pkg : pkgs) {
            int abortCode = abortIdleOptimizations(lowStorageThreshold);
            if (abortCode != OPTIMIZE_CONTINUE) {
                // Either aborted by the scheduler or no space left.
                return abortCode;
            }

            boolean dexOptPerformed = optimizePackage(pm, pkg, isForPrimaryDex);
            if (dexOptPerformed) {
                updatedPackages.add(pkg);
            }
        }
        return OPTIMIZE_PROCESSED;
    }

    /**
     * Try to downgrade the package to a smaller compilation filter.
     * eg. if the package is in speed-profile the package will be downgraded to verify.
     * @param pm PackageManagerService
     * @param pkg The package to be downgraded.
     * @param isForPrimaryDex. Apps can have several dex file, primary and secondary.
     * @return true if the package was downgraded.
     */
    private boolean downgradePackage(PackageManagerService pm, String pkg,
            boolean isForPrimaryDex) {
        if (DEBUG) {
            Slog.d(TAG, "Downgrading " + pkg);
        }
        boolean dex_opt_performed = false;
        int reason = PackageManagerService.REASON_INACTIVE_PACKAGE_DOWNGRADE;
        int dexoptFlags = DexoptOptions.DEXOPT_BOOT_COMPLETE
                | DexoptOptions.DEXOPT_IDLE_BACKGROUND_JOB
                | DexoptOptions.DEXOPT_DOWNGRADE;
        long package_size_before = getPackageSize(pm, pkg);

        if (isForPrimaryDex || PLATFORM_PACKAGE_NAME.equals(pkg)) {
            // This applies for system apps or if packages location is not a directory, i.e.
            // monolithic install.
            if (!pm.canHaveOatDir(pkg)) {
                // For apps that don't have the oat directory, instead of downgrading,
                // remove their compiler artifacts from dalvik cache.
                pm.deleteOatArtifactsOfPackage(pkg);
            } else {
                dex_opt_performed = performDexOptPrimary(pm, pkg, reason, dexoptFlags);
            }
        } else {
            dex_opt_performed = performDexOptSecondary(pm, pkg, reason, dexoptFlags);
        }

        if (dex_opt_performed) {
            FrameworkStatsLog.write(FrameworkStatsLog.APP_DOWNGRADED, pkg, package_size_before,
                    getPackageSize(pm, pkg), /*aggressive=*/ false);
        }
        return dex_opt_performed;
    }

    private boolean supportSecondaryDex() {
        return (SystemProperties.getBoolean("dalvik.vm.dexopt.secondary", false));
    }

    private int reconcileSecondaryDexFiles(DexManager dm) {
        // TODO(calin): should we denylist packages for which we fail to reconcile?
        for (String p : dm.getAllPackagesWithSecondaryDexFiles()) {
            if (mAbortIdleOptimization.get()) {
                return OPTIMIZE_ABORT_BY_JOB_SCHEDULER;
            }
            dm.reconcileSecondaryDexFiles(p);
        }
        return OPTIMIZE_PROCESSED;
    }

    /**
     *
     * Optimize package if needed. Note that there can be no race between
     * concurrent jobs because PackageDexOptimizer.performDexOpt is synchronized.
     * @param pm An instance of PackageManagerService
     * @param pkg The package to be downgraded.
     * @param isForPrimaryDex. Apps can have several dex file, primary and secondary.
     * @return true if the package was downgraded.
     */
    private boolean optimizePackage(PackageManagerService pm, String pkg,
            boolean isForPrimaryDex) {
        int reason = PackageManagerService.REASON_BACKGROUND_DEXOPT;
        int dexoptFlags = DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES
                | DexoptOptions.DEXOPT_BOOT_COMPLETE
                | DexoptOptions.DEXOPT_IDLE_BACKGROUND_JOB;

        // System server share the same code path as primary dex files.
        // PackageManagerService will select the right optimization path for it.
        return (isForPrimaryDex || PLATFORM_PACKAGE_NAME.equals(pkg))
            ? performDexOptPrimary(pm, pkg, reason, dexoptFlags)
            : performDexOptSecondary(pm, pkg, reason, dexoptFlags);
    }

    private boolean performDexOptPrimary(PackageManagerService pm, String pkg, int reason,
            int dexoptFlags) {
        int result = trackPerformDexOpt(pkg, /*isForPrimaryDex=*/ false,
                () -> pm.performDexOptWithStatus(new DexoptOptions(pkg, reason, dexoptFlags)));
        return result == PackageDexOptimizer.DEX_OPT_PERFORMED;
    }

    private boolean performDexOptSecondary(PackageManagerService pm, String pkg, int reason,
            int dexoptFlags) {
        DexoptOptions dexoptOptions = new DexoptOptions(pkg, reason,
                dexoptFlags | DexoptOptions.DEXOPT_ONLY_SECONDARY_DEX);
        int result = trackPerformDexOpt(pkg, /*isForPrimaryDex=*/ true,
                () -> pm.performDexOpt(dexoptOptions)
                    ? PackageDexOptimizer.DEX_OPT_PERFORMED : PackageDexOptimizer.DEX_OPT_FAILED
        );
        return result == PackageDexOptimizer.DEX_OPT_PERFORMED;
    }

    /**
     * Execute the dexopt wrapper and make sure that if performDexOpt wrapper fails
     * the package is added to the list of failed packages.
     * Return one of following result:
     *  {@link PackageDexOptimizer#DEX_OPT_SKIPPED}
     *  {@link PackageDexOptimizer#DEX_OPT_PERFORMED}
     *  {@link PackageDexOptimizer#DEX_OPT_FAILED}
     */
    private int trackPerformDexOpt(String pkg, boolean isForPrimaryDex,
            Supplier<Integer> performDexOptWrapper) {
        ArraySet<String> sFailedPackageNames =
                isForPrimaryDex ? sFailedPackageNamesPrimary : sFailedPackageNamesSecondary;
        synchronized (sFailedPackageNames) {
            if (sFailedPackageNames.contains(pkg)) {
                // Skip previously failing package
                return PackageDexOptimizer.DEX_OPT_SKIPPED;
            }
            sFailedPackageNames.add(pkg);
        }
        int result = performDexOptWrapper.get();
        if (result != PackageDexOptimizer.DEX_OPT_FAILED) {
            synchronized (sFailedPackageNames) {
                sFailedPackageNames.remove(pkg);
            }
        }
        return result;
    }

    // Evaluate whether or not idle optimizations should continue.
    private int abortIdleOptimizations(long lowStorageThreshold) {
        if (mAbortIdleOptimization.get()) {
            // JobScheduler requested an early abort.
            return OPTIMIZE_ABORT_BY_JOB_SCHEDULER;
        }

        // Abort background dexopt if the device is in a moderate or stronger thermal throttling
        // state.
        try {
            final int thermalStatus = mThermalService.getCurrentThermalStatus();

            if (DEBUG) {
                Log.i(TAG, "Thermal throttling status during bgdexopt: " + thermalStatus);
            }

            if (thermalStatus >= mThermalStatusCutoff) {
                return OPTIMIZE_ABORT_THERMAL;
            }
        } catch (RemoteException ex) {
            // Because this is a intra-process Binder call it is impossible for a RemoteException
            // to be raised.
        }

        long usableSpace = mDataDir.getUsableSpace();
        if (usableSpace < lowStorageThreshold) {
            // Rather bail than completely fill up the disk.
            Slog.w(TAG, "Aborting background dex opt job due to low storage: " + usableSpace);
            return OPTIMIZE_ABORT_NO_SPACE_LEFT;
        }

        return OPTIMIZE_CONTINUE;
    }

    // Evaluate whether apps should be downgraded.
    private boolean shouldDowngrade(long lowStorageThresholdForDowngrade) {
        long usableSpace = mDataDir.getUsableSpace();
        if (usableSpace < lowStorageThresholdForDowngrade) {
            return true;
        }

        return false;
    }

    /**
     * Execute idle optimizations immediately on packages in packageNames. If packageNames is null,
     * then execute on all packages.
     */
    public static boolean runIdleOptimizationsNow(PackageManagerService pm, Context context,
            @Nullable List<String> packageNames) {
        // Create a new object to make sure we don't interfere with the scheduled jobs.
        // Note that this may still run at the same time with the job scheduled by the
        // JobScheduler but the scheduler will not be able to cancel it.
        BackgroundDexOptService bdos = new BackgroundDexOptService();
        ArraySet<String> packagesToOptimize;
        if (packageNames == null) {
            packagesToOptimize = pm.getOptimizablePackages();
        } else {
            packagesToOptimize = new ArraySet<>(packageNames);
        }
        int result = bdos.idleOptimization(pm, packagesToOptimize, context);
        return result == OPTIMIZE_PROCESSED;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (DEBUG) {
            Slog.i(TAG, "onStartJob");
        }

        // NOTE: PackageManagerService.isStorageLow uses a different set of criteria from
        // the checks above. This check is not "live" - the value is determined by a background
        // restart with a period of ~1 minute.
        PackageManagerService pm = (PackageManagerService)ServiceManager.getService("package");
        if (pm.isStorageLow()) {
            Slog.i(TAG, "Low storage, skipping this run");
            return false;
        }

        final ArraySet<String> pkgs = pm.getOptimizablePackages();
        if (pkgs.isEmpty()) {
            Slog.i(TAG, "No packages to optimize");
            return false;
        }

        mThermalStatusCutoff =
            SystemProperties.getInt("dalvik.vm.dexopt.thermal-cutoff", THERMAL_CUTOFF_DEFAULT);

        boolean result;
        if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
            result = runPostBootUpdate(params, pm, pkgs);
        } else {
            result = runIdleOptimization(params, pm, pkgs);
        }

        return result;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (DEBUG) {
            Slog.d(TAG, "onStopJob");
        }

        if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
            mAbortPostBootUpdate.set(true);

            // Do not reschedule.
            // TODO: We should reschedule if we didn't process all apps, yet.
            return false;
        } else {
            mAbortIdleOptimization.set(true);

            // Reschedule the run.
            // TODO: Should this be dependent on the stop reason?
            return true;
        }
    }

    private void notifyPinService(ArraySet<String> updatedPackages) {
        PinnerService pinnerService = LocalServices.getService(PinnerService.class);
        if (pinnerService != null) {
            Slog.i(TAG, "Pinning optimized code " + updatedPackages);
            pinnerService.update(updatedPackages, false /* force */);
        }
    }

    public static interface PackagesUpdatedListener {
        /** Callback when packages have been updated by the bg-dexopt service. */
        public void onPackagesUpdated(ArraySet<String> updatedPackages);
    }

    public static void addPackagesUpdatedListener(PackagesUpdatedListener listener) {
        synchronized (sPackagesUpdatedListeners) {
            sPackagesUpdatedListeners.add(listener);
        }
    }

    public static void removePackagesUpdatedListener(PackagesUpdatedListener listener) {
        synchronized (sPackagesUpdatedListeners) {
            sPackagesUpdatedListeners.remove(listener);
        }
    }

    /** Notify all listeners (#addPackagesUpdatedListener) that packages have been updated. */
    private void notifyPackagesUpdated(ArraySet<String> updatedPackages) {
        synchronized (sPackagesUpdatedListeners) {
            for (PackagesUpdatedListener listener : sPackagesUpdatedListeners) {
                listener.onPackagesUpdated(updatedPackages);
            }
        }
    }

    private static long getDowngradeUnusedAppsThresholdInMillis() {
        final String sysPropKey = "pm.dexopt.downgrade_after_inactive_days";
        String sysPropValue = SystemProperties.get(sysPropKey);
        if (sysPropValue == null || sysPropValue.isEmpty()) {
            Slog.w(TAG, "SysProp " + sysPropKey + " not set");
            return Long.MAX_VALUE;
        }
        return TimeUnit.DAYS.toMillis(Long.parseLong(sysPropValue));
    }

    private static boolean isBackgroundDexoptDisabled() {
        return SystemProperties.getBoolean("pm.dexopt.disable_bg_dexopt" /* key */,
                false /* default */);
    }
}
