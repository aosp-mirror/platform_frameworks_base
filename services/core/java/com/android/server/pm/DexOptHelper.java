/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_REASON_DEVICE_RESTORE;
import static android.content.pm.PackageManager.INSTALL_REASON_DEVICE_SETUP;
import static android.os.Trace.TRACE_TAG_DALVIK;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;

import static com.android.server.LocalManagerRegistry.ManagerNotFoundException;
import static com.android.server.pm.ApexManager.ActiveApexInfo;
import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.REASON_BOOT_AFTER_MAINLINE_UPDATE;
import static com.android.server.pm.PackageManagerService.REASON_BOOT_AFTER_OTA;
import static com.android.server.pm.PackageManagerService.REASON_CMDLINE;
import static com.android.server.pm.PackageManagerService.REASON_FIRST_BOOT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.REMOVE_IF_APEX_PKG;
import static com.android.server.pm.PackageManagerServiceUtils.REMOVE_IF_NULL_PKG;
import static com.android.server.pm.PackageManagerServiceUtils.getPackageManagerLocal;

import static dalvik.system.DexFile.isProfileGuidedCompilerFilter;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApexStagedEvent;
import android.content.pm.Flags;
import android.content.pm.IPackageManagerNative;
import android.content.pm.IStagedApexObserver;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.PinnerService;
import com.android.server.art.ArtManagerLocal;
import com.android.server.art.DexUseManagerLocal;
import com.android.server.art.ReasonMapping;
import com.android.server.art.model.ArtFlags;
import com.android.server.art.model.DexoptParams;
import com.android.server.art.model.DexoptResult;
import com.android.server.pm.PackageDexOptimizer.DexOptResult;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Helper class for dex optimization operations in PackageManagerService.
 */
public final class DexOptHelper {
    private static final long SEVEN_DAYS_IN_MILLISECONDS = 7 * 24 * 60 * 60 * 1000;

    private static boolean sArtManagerLocalIsInitialized = false;

    private final PackageManagerService mPm;

    // Start time for the boot dexopt in performPackageDexOptUpgradeIfNeeded when ART Service is
    // used, to make it available to the onDexoptDone callback.
    private volatile long mBootDexoptStartTime;

    DexOptHelper(PackageManagerService pm) {
        mPm = pm;
    }

    /*
     * Return the prebuilt profile path given a package base code path.
     */
    private static String getPrebuildProfilePath(AndroidPackage pkg) {
        return pkg.getBaseApkPath() + ".prof";
    }

    /**
     * Called during startup to do any boot time dexopting. This can occasionally be time consuming
     * (30+ seconds) and the function will block until it is complete.
     */
    public void performPackageDexOptUpgradeIfNeeded() {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system can request package update");

        int reason;
        if (mPm.isFirstBoot()) {
            reason = REASON_FIRST_BOOT; // First boot or factory reset.
        } else if (mPm.isDeviceUpgrading()) {
            reason = REASON_BOOT_AFTER_OTA;
        } else if (hasBcpApexesChanged()) {
            reason = REASON_BOOT_AFTER_MAINLINE_UPDATE;
        } else {
            return;
        }

        Log.i(TAG,
                "Starting boot dexopt for reason "
                        + DexoptOptions.convertToArtServiceDexoptReason(reason));

        final long startTime = System.nanoTime();

        mBootDexoptStartTime = startTime;
        getArtManagerLocal().onBoot(DexoptOptions.convertToArtServiceDexoptReason(reason),
                null /* progressCallbackExecutor */, null /* progressCallback */);
    }

    private void reportBootDexopt(long startTime, int numDexopted, int numSkipped, int numFailed) {
        final int elapsedTimeSeconds =
                (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);

        final Computer newSnapshot = mPm.snapshotComputer();

        MetricsLogger.histogram(mPm.mContext, "opt_dialog_num_dexopted", numDexopted);
        MetricsLogger.histogram(mPm.mContext, "opt_dialog_num_skipped", numSkipped);
        MetricsLogger.histogram(mPm.mContext, "opt_dialog_num_failed", numFailed);
        // TODO(b/251903639): getOptimizablePackages calls PackageDexOptimizer.canOptimizePackage
        // which duplicates logic in ART Service (com.android.server.art.Utils.canDexoptPackage).
        MetricsLogger.histogram(mPm.mContext, "opt_dialog_num_total",
                getOptimizablePackages(newSnapshot).size());
        MetricsLogger.histogram(mPm.mContext, "opt_dialog_time_s", elapsedTimeSeconds);
    }

    public List<String> getOptimizablePackages(@NonNull Computer snapshot) {
        ArrayList<String> pkgs = new ArrayList<>();
        mPm.forEachPackageState(snapshot, packageState -> {
            final AndroidPackage pkg = packageState.getPkg();
            if (pkg != null && mPm.mPackageDexOptimizer.canOptimizePackage(pkg)) {
                pkgs.add(packageState.getPackageName());
            }
        });
        return pkgs;
    }

    /*package*/ boolean performDexOpt(DexoptOptions options) {
        final Computer snapshot = mPm.snapshotComputer();
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        } else if (snapshot.isInstantApp(options.getPackageName(), UserHandle.getCallingUserId())) {
            return false;
        }
        var pkg = snapshot.getPackage(options.getPackageName());
        if (pkg != null && pkg.isApex()) {
            // skip APEX
            return true;
        }

        @DexOptResult int dexoptStatus;
        if (options.isDexoptOnlySecondaryDex()) {
            dexoptStatus = performDexOptWithArtService(options, 0 /* extraFlags */);
        } else {
            dexoptStatus = performDexOptWithStatus(options);
        }
        return dexoptStatus != PackageDexOptimizer.DEX_OPT_FAILED;
    }

    /**
     * Perform dexopt on the given package and return one of following result:
     * {@link PackageDexOptimizer#DEX_OPT_SKIPPED}
     * {@link PackageDexOptimizer#DEX_OPT_PERFORMED}
     * {@link PackageDexOptimizer#DEX_OPT_CANCELLED}
     * {@link PackageDexOptimizer#DEX_OPT_FAILED}
     */
    @DexOptResult
    /* package */ int performDexOptWithStatus(DexoptOptions options) {
        return performDexOptTraced(options);
    }

    @DexOptResult
    private int performDexOptTraced(DexoptOptions options) {
        Trace.traceBegin(TRACE_TAG_DALVIK, "dexopt");
        try {
            return performDexOptInternal(options);
        } finally {
            Trace.traceEnd(TRACE_TAG_DALVIK);
        }
    }

    // Run dexopt on a given package. Returns true if dexopt did not fail, i.e.
    // if the package can now be considered up to date for the given filter.
    @DexOptResult
    private int performDexOptInternal(DexoptOptions options) {
        return performDexOptWithArtService(options, ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES);
    }

    /**
     * Performs dexopt on the given package using ART Service.
     */
    @DexOptResult
    private int performDexOptWithArtService(DexoptOptions options,
            /*@DexoptFlags*/ int extraFlags) {
        try (PackageManagerLocal.FilteredSnapshot snapshot =
                        getPackageManagerLocal().withFilteredSnapshot()) {
            PackageState ops = snapshot.getPackageState(options.getPackageName());
            if (ops == null) {
                return PackageDexOptimizer.DEX_OPT_FAILED;
            }
            AndroidPackage oap = ops.getAndroidPackage();
            if (oap == null) {
                return PackageDexOptimizer.DEX_OPT_FAILED;
            }
            DexoptParams params = options.convertToDexoptParams(extraFlags);
            DexoptResult result =
                    getArtManagerLocal().dexoptPackage(snapshot, options.getPackageName(), params);
            return convertToDexOptResult(result);
        }
    }

    public boolean performDexOptMode(@NonNull Computer snapshot, String packageName,
            String targetCompilerFilter, boolean force, boolean bootComplete, String splitName) {
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell()
                && !isCallerInstallerForPackage(snapshot, packageName)) {
            throw new SecurityException("performDexOptMode");
        }

        int flags = (force ? DexoptOptions.DEXOPT_FORCE : 0)
                | (bootComplete ? DexoptOptions.DEXOPT_BOOT_COMPLETE : 0);

        if (isProfileGuidedCompilerFilter(targetCompilerFilter)) {
            // Set this flag whenever the filter is profile guided, to align with ART Service
            // behavior.
            flags |= DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES;
        }

        return performDexOpt(new DexoptOptions(packageName, REASON_CMDLINE,
                targetCompilerFilter, splitName, flags));
    }

    private boolean isCallerInstallerForPackage(@NonNull Computer snapshot, String packageName) {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        if (packageState == null) {
            return false;
        }
        final InstallSource installSource = packageState.getInstallSource();

        final PackageStateInternal installerPackageState =
                snapshot.getPackageStateInternal(installSource.mInstallerPackageName);
        if (installerPackageState == null) {
            return false;
        }
        final AndroidPackage installerPkg = installerPackageState.getPkg();
        return installerPkg.getUid() == Binder.getCallingUid();
    }

    public boolean performDexOptSecondary(
            String packageName, String compilerFilter, boolean force) {
        int flags = DexoptOptions.DEXOPT_ONLY_SECONDARY_DEX
                | DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES
                | DexoptOptions.DEXOPT_BOOT_COMPLETE
                | (force ? DexoptOptions.DEXOPT_FORCE : 0);
        return performDexOpt(new DexoptOptions(packageName, REASON_CMDLINE,
                compilerFilter, null /* splitName */, flags));
    }

    // Sort apps by importance for dexopt ordering. Important apps are given
    // more priority in case the device runs out of space.
    public static List<PackageStateInternal> getPackagesForDexopt(
            Collection<? extends PackageStateInternal> packages,
            PackageManagerService packageManagerService) {
        return getPackagesForDexopt(packages, packageManagerService, DEBUG_DEXOPT);
    }

    public static List<PackageStateInternal> getPackagesForDexopt(
            Collection<? extends PackageStateInternal> pkgSettings,
            PackageManagerService packageManagerService,
            boolean debug) {
        List<PackageStateInternal> result = new ArrayList<>();
        ArrayList<PackageStateInternal> remainingPkgSettings = new ArrayList<>(pkgSettings);

        // First, remove all settings without available packages
        remainingPkgSettings.removeIf(REMOVE_IF_NULL_PKG);
        remainingPkgSettings.removeIf(REMOVE_IF_APEX_PKG);

        ArrayList<PackageStateInternal> sortTemp = new ArrayList<>(remainingPkgSettings.size());

        final Computer snapshot = packageManagerService.snapshotComputer();

        // Give priority to core apps.
        applyPackageFilter(snapshot, pkgSetting -> pkgSetting.getPkg().isCoreApp(), result,
                remainingPkgSettings, sortTemp, packageManagerService);

        // Give priority to system apps that listen for pre boot complete.
        Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        final ArraySet<String> pkgNames = getPackageNamesForIntent(intent, UserHandle.USER_SYSTEM);
        applyPackageFilter(snapshot, pkgSetting -> pkgNames.contains(pkgSetting.getPackageName()), result,
                remainingPkgSettings, sortTemp, packageManagerService);

        // Give priority to apps used by other apps.
        DexManager dexManager = packageManagerService.getDexManager();
        applyPackageFilter(snapshot, pkgSetting ->
                        dexManager.getPackageUseInfoOrDefault(pkgSetting.getPackageName())
                                .isAnyCodePathUsedByOtherApps(),
                result, remainingPkgSettings, sortTemp, packageManagerService);

        // Filter out packages that aren't recently used, add all remaining apps.
        // TODO: add a property to control this?
        Predicate<PackageStateInternal> remainingPredicate;
        if (!remainingPkgSettings.isEmpty()
                && packageManagerService.isHistoricalPackageUsageAvailable()) {
            if (debug) {
                Log.i(TAG, "Looking at historical package use");
            }
            // Get the package that was used last.
            PackageStateInternal lastUsed = Collections.max(remainingPkgSettings,
                    Comparator.comparingLong(
                            pkgSetting -> pkgSetting.getTransientState()
                                    .getLatestForegroundPackageUseTimeInMills()));
            if (debug) {
                Log.i(TAG, "Taking package " + lastUsed.getPackageName()
                        + " as reference in time use");
            }
            long estimatedPreviousSystemUseTime = lastUsed.getTransientState()
                    .getLatestForegroundPackageUseTimeInMills();
            // Be defensive if for some reason package usage has bogus data.
            if (estimatedPreviousSystemUseTime != 0) {
                final long cutoffTime = estimatedPreviousSystemUseTime - SEVEN_DAYS_IN_MILLISECONDS;
                remainingPredicate = pkgSetting -> pkgSetting.getTransientState()
                        .getLatestForegroundPackageUseTimeInMills() >= cutoffTime;
            } else {
                // No meaningful historical info. Take all.
                remainingPredicate = pkgSetting -> true;
            }
            sortPackagesByUsageDate(remainingPkgSettings, packageManagerService);
        } else {
            // No historical info. Take all.
            remainingPredicate = pkgSetting -> true;
        }
        applyPackageFilter(snapshot, remainingPredicate, result, remainingPkgSettings, sortTemp,
                packageManagerService);

        // Make sure the system server isn't in the result, because it can never be dexopted here.
        result.removeIf(pkgSetting -> PLATFORM_PACKAGE_NAME.equals(pkgSetting.getPackageName()));

        if (debug) {
            Log.i(TAG, "Packages to be dexopted: " + packagesToString(result));
            Log.i(TAG, "Packages skipped from dexopt: " + packagesToString(remainingPkgSettings));
        }

        return result;
    }

    // Apply the given {@code filter} to all packages in {@code packages}. If tested positive, the
    // package will be removed from {@code packages} and added to {@code result} with its
    // dependencies. If usage data is available, the positive packages will be sorted by usage
    // data (with {@code sortTemp} as temporary storage).
    private static void applyPackageFilter(@NonNull Computer snapshot,
            Predicate<PackageStateInternal> filter,
            Collection<PackageStateInternal> result,
            Collection<PackageStateInternal> packages,
            @NonNull List<PackageStateInternal> sortTemp,
            PackageManagerService packageManagerService) {
        for (PackageStateInternal pkgSetting : packages) {
            if (filter.test(pkgSetting)) {
                sortTemp.add(pkgSetting);
            }
        }

        sortPackagesByUsageDate(sortTemp, packageManagerService);
        packages.removeAll(sortTemp);

        for (PackageStateInternal pkgSetting : sortTemp) {
            result.add(pkgSetting);

            List<PackageStateInternal> deps = snapshot.findSharedNonSystemLibraries(pkgSetting);
            if (!deps.isEmpty()) {
                deps.removeAll(result);
                result.addAll(deps);
                packages.removeAll(deps);
            }
        }

        sortTemp.clear();
    }

    // Sort a list of apps by their last usage, most recently used apps first. The order of
    // packages without usage data is undefined (but they will be sorted after the packages
    // that do have usage data).
    private static void sortPackagesByUsageDate(List<PackageStateInternal> pkgSettings,
            PackageManagerService packageManagerService) {
        if (!packageManagerService.isHistoricalPackageUsageAvailable()) {
            return;
        }

        Collections.sort(pkgSettings, (pkgSetting1, pkgSetting2) ->
                Long.compare(
                        pkgSetting2.getTransientState().getLatestForegroundPackageUseTimeInMills(),
                        pkgSetting1.getTransientState().getLatestForegroundPackageUseTimeInMills())
        );
    }

    private static ArraySet<String> getPackageNamesForIntent(Intent intent, int userId) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(intent, null, 0, userId)
                    .getList();
        } catch (RemoteException e) {
        }
        ArraySet<String> pkgNames = new ArraySet<String>();
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                pkgNames.add(ri.activityInfo.packageName);
            }
        }
        return pkgNames;
    }

    public static String packagesToString(List<PackageStateInternal> pkgSettings) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < pkgSettings.size(); index++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(pkgSettings.get(index).getPackageName());
        }
        return sb.toString();
    }

     /**
     * Requests that files preopted on a secondary system partition be copied to the data partition
     * if possible.  Note that the actual copying of the files is accomplished by init for security
     * reasons. This simply requests that the copy takes place and awaits confirmation of its
     * completion. See platform/system/extras/cppreopt/ for the implementation of the actual copy.
     */
    public static void requestCopyPreoptedFiles() {
        final int WAIT_TIME_MS = 100;
        final String CP_PREOPT_PROPERTY = "sys.cppreopt";
        if (SystemProperties.getInt("ro.cp_system_other_odex", 0) == 1) {
            SystemProperties.set(CP_PREOPT_PROPERTY, "requested");
            // We will wait for up to 100 seconds.
            final long timeStart = SystemClock.uptimeMillis();
            final long timeEnd = timeStart + 100 * 1000;
            long timeNow = timeStart;
            while (!SystemProperties.get(CP_PREOPT_PROPERTY).equals("finished")) {
                try {
                    Thread.sleep(WAIT_TIME_MS);
                } catch (InterruptedException e) {
                    // Do nothing
                }
                timeNow = SystemClock.uptimeMillis();
                if (timeNow > timeEnd) {
                    SystemProperties.set(CP_PREOPT_PROPERTY, "timed-out");
                    Slog.wtf(TAG, "cppreopt did not finish!");
                    break;
                }
            }

            Slog.i(TAG, "cppreopts took " + (timeNow - timeStart) + " ms");
        }
    }

    /**
     * Dumps the dexopt state for the given package, or all packages if it is null.
     */
    public static void dumpDexoptState(
            @NonNull IndentingPrintWriter ipw, @Nullable String packageName) {
        try (PackageManagerLocal.FilteredSnapshot snapshot =
                        getPackageManagerLocal().withFilteredSnapshot()) {
            if (packageName != null) {
                try {
                    DexOptHelper.getArtManagerLocal().dumpPackage(ipw, snapshot, packageName);
                } catch (IllegalArgumentException e) {
                    // Package isn't found, but that should only happen due to race.
                    ipw.println(e);
                }
            } else {
                DexOptHelper.getArtManagerLocal().dump(ipw, snapshot);
            }
        }
    }

    /**
     * Returns the module names of the APEXes that contribute to bootclasspath.
     */
    private static List<String> getBcpApexes() {
        String bcp = System.getenv("BOOTCLASSPATH");
        if (TextUtils.isEmpty(bcp)) {
            Log.e(TAG, "Unable to get BOOTCLASSPATH");
            return List.of();
        }

        ArrayList<String> bcpApexes = new ArrayList<>();
        for (String pathStr : bcp.split(":")) {
            Path path = Paths.get(pathStr);
            // Check if the path is in the format of `/apex/<apex-module-name>/...` and extract the
            // apex module name from the path.
            if (path.getNameCount() >= 2 && path.getName(0).toString().equals("apex")) {
                bcpApexes.add(path.getName(1).toString());
            }
        }

        return bcpApexes;
    }

    /**
     * Returns true of any of the APEXes that contribute to bootclasspath has changed during this
     * boot.
     */
    private static boolean hasBcpApexesChanged() {
        Set<String> bcpApexes = new HashSet<>(getBcpApexes());
        ApexManager apexManager = ApexManager.getInstance();
        for (ActiveApexInfo apexInfo : apexManager.getActiveApexInfos()) {
            if (bcpApexes.contains(apexInfo.apexModuleName) && apexInfo.activeApexChanged) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@link DexUseManagerLocal} if ART Service should be used for package optimization.
     */
    public static @Nullable DexUseManagerLocal getDexUseManagerLocal() {
        try {
            return LocalManagerRegistry.getManagerOrThrow(DexUseManagerLocal.class);
        } catch (ManagerNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private class DexoptDoneHandler implements ArtManagerLocal.DexoptDoneCallback {
        /**
         * Called after every package dexopt operation done by {@link ArtManagerLocal} (when ART
         * Service is in use).
         */
        @Override
        public void onDexoptDone(@NonNull DexoptResult result) {
            switch (result.getReason()) {
                case ReasonMapping.REASON_FIRST_BOOT:
                case ReasonMapping.REASON_BOOT_AFTER_OTA:
                case ReasonMapping.REASON_BOOT_AFTER_MAINLINE_UPDATE:
                    int numDexopted = 0;
                    int numSkipped = 0;
                    int numFailed = 0;
                    for (DexoptResult.PackageDexoptResult pkgRes :
                            result.getPackageDexoptResults()) {
                        switch (pkgRes.getStatus()) {
                            case DexoptResult.DEXOPT_PERFORMED:
                                numDexopted += 1;
                                break;
                            case DexoptResult.DEXOPT_SKIPPED:
                                numSkipped += 1;
                                break;
                            case DexoptResult.DEXOPT_FAILED:
                                numFailed += 1;
                                break;
                        }
                    }

                    reportBootDexopt(mBootDexoptStartTime, numDexopted, numSkipped, numFailed);
                    break;
            }

            for (DexoptResult.PackageDexoptResult pkgRes : result.getPackageDexoptResults()) {
                CompilerStats.PackageStats stats =
                        mPm.getOrCreateCompilerPackageStats(pkgRes.getPackageName());
                for (DexoptResult.DexContainerFileDexoptResult dexRes :
                        pkgRes.getDexContainerFileDexoptResults()) {
                    stats.setCompileTime(
                            dexRes.getDexContainerFile(), dexRes.getDex2oatWallTimeMillis());
                }
            }

            synchronized (mPm.mLock) {
                mPm.getPackageUsage().maybeWriteAsync(mPm.mSettings.getPackagesLocked());
                mPm.mCompilerStats.maybeWriteAsync();
            }

            if (result.getReason().equals(ReasonMapping.REASON_INACTIVE)) {
                for (DexoptResult.PackageDexoptResult pkgRes : result.getPackageDexoptResults()) {
                    if (pkgRes.getStatus() == DexoptResult.DEXOPT_PERFORMED) {
                        long pkgSizeBytes = 0;
                        long pkgSizeBeforeBytes = 0;
                        for (DexoptResult.DexContainerFileDexoptResult dexRes :
                                pkgRes.getDexContainerFileDexoptResults()) {
                            long dexContainerSize = new File(dexRes.getDexContainerFile()).length();
                            pkgSizeBytes += dexRes.getSizeBytes() + dexContainerSize;
                            pkgSizeBeforeBytes += dexRes.getSizeBeforeBytes() + dexContainerSize;
                        }
                        FrameworkStatsLog.write(FrameworkStatsLog.APP_DOWNGRADED,
                                pkgRes.getPackageName(), pkgSizeBeforeBytes, pkgSizeBytes,
                                false /* aggressive */);
                    }
                }
            }

            var updatedPackages = new ArraySet<String>();
            for (DexoptResult.PackageDexoptResult pkgRes : result.getPackageDexoptResults()) {
                if (pkgRes.hasUpdatedArtifacts()) {
                    updatedPackages.add(pkgRes.getPackageName());
                }
            }
            if (!updatedPackages.isEmpty()) {
                LocalServices.getService(PinnerService.class)
                        .update(updatedPackages, false /* force */);
            }
        }
    }

    /**
     * Initializes {@link ArtManagerLocal} before {@link getArtManagerLocal} is called.
     */
    public static void initializeArtManagerLocal(
            @NonNull Context systemContext, @NonNull PackageManagerService pm) {
        ArtManagerLocal artManager = new ArtManagerLocal(systemContext);
        artManager.addDexoptDoneCallback(false /* onlyIncludeUpdates */, Runnable::run,
                pm.getDexOptHelper().new DexoptDoneHandler());
        LocalManagerRegistry.addManager(ArtManagerLocal.class, artManager);
        sArtManagerLocalIsInitialized = true;

        // Schedule the background job when boot is complete. This decouples us from when
        // JobSchedulerService is initialized.
        systemContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                context.unregisterReceiver(this);
                artManager.scheduleBackgroundDexoptJob();
            }
        }, new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        StagedApexObserver.registerForStagedApexUpdates(artManager);
    }

    /**
     * Returns true if an {@link ArtManagerLocal} instance has been created.
     *
     * Avoid this function if at all possible, because it may hide initialization order problems.
     */
    public static boolean artManagerLocalIsInitialized() {
        return sArtManagerLocalIsInitialized;
    }

    /**
     * Returns the registered {@link ArtManagerLocal} instance, or else throws an unchecked error.
     */
    public static @NonNull ArtManagerLocal getArtManagerLocal() {
        try {
            return LocalManagerRegistry.getManagerOrThrow(ArtManagerLocal.class);
        } catch (ManagerNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts an ART Service {@link DexoptResult} to {@link DexOptResult}.
     *
     * For interfacing {@link ArtManagerLocal} with legacy dex optimization code in PackageManager.
     */
    @DexOptResult
    private static int convertToDexOptResult(DexoptResult result) {
        /*@DexoptResultStatus*/ int status = result.getFinalStatus();
        switch (status) {
            case DexoptResult.DEXOPT_SKIPPED:
                return PackageDexOptimizer.DEX_OPT_SKIPPED;
            case DexoptResult.DEXOPT_FAILED:
                return PackageDexOptimizer.DEX_OPT_FAILED;
            case DexoptResult.DEXOPT_PERFORMED:
                return PackageDexOptimizer.DEX_OPT_PERFORMED;
            case DexoptResult.DEXOPT_CANCELLED:
                return PackageDexOptimizer.DEX_OPT_CANCELLED;
            default:
                throw new IllegalArgumentException("DexoptResult for "
                        + result.getPackageDexoptResults().get(0).getPackageName()
                        + " has unsupported status " + status);
        }
    }

    /**
     * Returns DexoptOptions by the given InstallRequest.
     */
    static DexoptOptions getDexoptOptionsByInstallRequest(InstallRequest installRequest,
            DexManager dexManager) {
        final PackageSetting ps = installRequest.getScannedPackageSetting();
        final String packageName = ps.getPackageName();
        final boolean isBackupOrRestore =
                installRequest.getInstallReason() == INSTALL_REASON_DEVICE_RESTORE
                        || installRequest.getInstallReason() == INSTALL_REASON_DEVICE_SETUP;
        final int dexoptFlags = DexoptOptions.DEXOPT_BOOT_COMPLETE
                | DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES
                | DexoptOptions.DEXOPT_INSTALL_WITH_DEX_METADATA_FILE
                | (isBackupOrRestore ? DexoptOptions.DEXOPT_FOR_RESTORE : 0);
        // Compute the compilation reason from the installation scenario.
        final int compilationReason =
                dexManager.getCompilationReasonForInstallScenario(
                        installRequest.getInstallScenario());
        final AndroidPackage pkg = ps.getPkg();
        var options = new DexoptOptions(packageName, compilationReason, dexoptFlags);
        if (installRequest.getDexoptCompilerFilter() != null) {
            options = options.overrideCompilerFilter(installRequest.getDexoptCompilerFilter());
        } else if (pkg != null && pkg.isDebuggable()) {
            options = options.overrideCompilerFilter(DexoptParams.COMPILER_FILTER_NOOP);
        }
        return options;
    }

    /**
     * Perform dexopt if needed for the installation
     */
    static void performDexoptIfNeeded(InstallRequest installRequest, DexManager dexManager,
            Context context, PackageManagerTracedLock.RawLock installLock) {

        // Construct the DexoptOptions early to see if we should skip running dexopt.
        //
        // Do not run PackageDexOptimizer through the local performDexOpt
        // method because `pkg` may not be in `mPackages` yet.
        //
        // Also, don't fail application installs if the dexopt step fails.
        DexoptOptions dexoptOptions = getDexoptOptionsByInstallRequest(installRequest, dexManager);
        // Check whether we need to dexopt the app.
        //
        // NOTE: it is IMPORTANT to call dexopt:
        //   - after doRename which will sync the package data from AndroidPackage and
        //     its corresponding ApplicationInfo.
        //   - after installNewPackageLIF or replacePackageLIF which will update result with the
        //     uid of the application (pkg.applicationInfo.uid).
        //     This update happens in place!
        //
        // We only need to dexopt if the package meets ALL of the following conditions:
        //   1) it is not an instant app or if it is then dexopt is enabled via gservices.
        //   2) it is not debuggable.
        //   3) it is not on Incremental File System.
        //
        // Note that we do not dexopt instant apps by default. dexopt can take some time to
        // complete, so we skip this step during installation. Instead, we'll take extra time
        // the first time the instant app starts. It's preferred to do it this way to provide
        // continuous progress to the useur instead of mysteriously blocking somewhere in the
        // middle of running an instant app. The default behaviour can be overridden
        // via gservices.
        //
        // Furthermore, dexopt may be skipped, depending on the install scenario and current
        // state of the device.
        //
        // TODO(b/174695087): instantApp and onIncremental should be removed and their install
        //       path moved to SCENARIO_FAST.

        final boolean performDexopt = DexOptHelper.shouldPerformDexopt(installRequest,
                dexoptOptions, context);
        if (performDexopt) {
            // dexopt can take long, and ArtService doesn't require installd, so we release
            // the lock here and re-acquire the lock after dexopt is finished.
            if (installLock != null) {
                installLock.unlock();
            }
            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dexopt");

                // This mirrors logic from commitReconciledScanResultLocked, where the library
                // files needed for dexopt are assigned.
                PackageSetting realPkgSetting = installRequest.getRealPackageSetting();
                // Unfortunately, the updated system app flag is only tracked on this
                // PackageSetting
                boolean isUpdatedSystemApp =
                        installRequest.getScannedPackageSetting().isUpdatedSystemApp();
                realPkgSetting.getPkgState().setUpdatedSystemApp(isUpdatedSystemApp);

                DexoptResult dexOptResult = DexOptHelper.dexoptPackageUsingArtService(
                        installRequest, dexoptOptions);
                installRequest.onDexoptFinished(dexOptResult);
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                if (installLock != null) {
                    installLock.lock();
                }
            }
        }
    }

    /**
     * Use ArtService to perform dexopt by the given InstallRequest.
     */
    static DexoptResult dexoptPackageUsingArtService(InstallRequest installRequest,
            DexoptOptions dexoptOptions) {
        final PackageSetting ps = installRequest.getScannedPackageSetting();
        final String packageName = ps.getPackageName();

        PackageManagerLocal packageManagerLocal =
                LocalManagerRegistry.getManager(PackageManagerLocal.class);
        try (PackageManagerLocal.FilteredSnapshot snapshot =
                     packageManagerLocal.withFilteredSnapshot()) {
            boolean ignoreDexoptProfile =
                    (installRequest.getInstallFlags()
                            & PackageManager.INSTALL_IGNORE_DEXOPT_PROFILE)
                            != 0;
            /*@DexoptFlags*/ int extraFlags =
                    ignoreDexoptProfile ? ArtFlags.FLAG_IGNORE_PROFILE : 0;
            DexoptParams params = dexoptOptions.convertToDexoptParams(extraFlags);
            DexoptResult dexOptResult = getArtManagerLocal().dexoptPackage(
                    snapshot, packageName, params);

            return dexOptResult;
        }
    }

    /**
     * Returns whether to perform dexopt by the given InstallRequest.
     */
    static boolean shouldPerformDexopt(InstallRequest installRequest, DexoptOptions dexoptOptions,
            Context context) {
        final boolean isApex = ((installRequest.getScanFlags() & SCAN_AS_APEX) != 0);
        final boolean instantApp = ((installRequest.getScanFlags() & SCAN_AS_INSTANT_APP) != 0);
        final PackageSetting ps = installRequest.getScannedPackageSetting();
        final AndroidPackage pkg = ps.getPkg();
        final boolean onIncremental = isIncrementalPath(ps.getPathString());
        final boolean performDexOptForRollback = Flags.recoverabilityDetection()
                ? !(installRequest.isRollback()
                && installRequest.getInstallSource().mInitiatingPackageName.equals("android"))
                : true;

        // Don't skip the dexopt call if the compiler filter is "skip". Instead, call dexopt with
        // the "skip" filter so that ART Service gets notified and skips dexopt itself.
        return (!instantApp || Global.getInt(context.getContentResolver(),
                Global.INSTANT_APP_DEXOPT_ENABLED, 0) != 0)
                && pkg != null
                && (!onIncremental)
                && !isApex
                && performDexOptForRollback;
    }

    private static class StagedApexObserver extends IStagedApexObserver.Stub {
        private final @NonNull ArtManagerLocal mArtManager;

        static void registerForStagedApexUpdates(@NonNull ArtManagerLocal artManager) {
            IPackageManagerNative packageNative = IPackageManagerNative.Stub.asInterface(
                    ServiceManager.getService("package_native"));
            if (packageNative == null) {
                Log.e(TAG, "No IPackageManagerNative");
                return;
            }

            try {
                packageNative.registerStagedApexObserver(new StagedApexObserver(artManager));
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register staged apex observer", e);
            }
        }

        private StagedApexObserver(@NonNull ArtManagerLocal artManager) {
            mArtManager = artManager;
        }

        @Override
        public void onApexStaged(@NonNull ApexStagedEvent event) {
            mArtManager.onApexStaged(event.stagedApexModuleNames);
        }
    }
}
