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

import static android.os.Trace.TRACE_TAG_DALVIK;

import static com.android.server.LocalManagerRegistry.ManagerNotFoundException;
import static com.android.server.pm.ApexManager.ActiveApexInfo;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.REASON_BOOT_AFTER_MAINLINE_UPDATE;
import static com.android.server.pm.PackageManagerService.REASON_BOOT_AFTER_OTA;
import static com.android.server.pm.PackageManagerService.REASON_CMDLINE;
import static com.android.server.pm.PackageManagerService.REASON_FIRST_BOOT;
import static com.android.server.pm.PackageManagerService.STUB_SUFFIX;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getCompilerFilterForReason;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getDefaultCompilerFilter;
import static com.android.server.pm.PackageManagerServiceUtils.REMOVE_IF_APEX_PKG;
import static com.android.server.pm.PackageManagerServiceUtils.REMOVE_IF_NULL_PKG;
import static com.android.server.pm.PackageManagerServiceUtils.getPackageManagerLocal;

import static dalvik.system.DexFile.isProfileGuidedCompilerFilter;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.dex.ArtManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
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
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.Installer.LegacyDexoptDisabledException;
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
     * Performs dexopt on the set of packages in {@code packages} and returns an int array
     * containing statistics about the invocation. The array consists of three elements,
     * which are (in order) {@code numberOfPackagesOptimized}, {@code numberOfPackagesSkipped}
     * and {@code numberOfPackagesFailed}.
     */
    public int[] performDexOptUpgrade(List<PackageStateInternal> packageStates,
            final int compilationReason, boolean bootComplete)
            throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();
        int numberOfPackagesVisited = 0;
        int numberOfPackagesOptimized = 0;
        int numberOfPackagesSkipped = 0;
        int numberOfPackagesFailed = 0;
        final int numberOfPackagesToDexopt = packageStates.size();

        for (var packageState : packageStates) {
            var pkg = packageState.getAndroidPackage();
            numberOfPackagesVisited++;

            boolean useProfileForDexopt = false;

            if ((mPm.isFirstBoot() || mPm.isDeviceUpgrading()) && packageState.isSystem()) {
                // Copy over initial preopt profiles since we won't get any JIT samples for methods
                // that are already compiled.
                File profileFile = new File(getPrebuildProfilePath(pkg));
                // Copy profile if it exists.
                if (profileFile.exists()) {
                    try {
                        // We could also do this lazily before calling dexopt in
                        // PackageDexOptimizer to prevent this happening on first boot. The issue
                        // is that we don't have a good way to say "do this only once".
                        if (!mPm.mInstaller.copySystemProfile(profileFile.getAbsolutePath(),
                                pkg.getUid(), pkg.getPackageName(),
                                ArtManager.getProfileName(null))) {
                            Log.e(TAG, "Installer failed to copy system profile!");
                        } else {
                            // Disabled as this causes speed-profile compilation during first boot
                            // even if things are already compiled.
                            // useProfileForDexopt = true;
                        }
                    } catch (InstallerException | RuntimeException e) {
                        Log.e(TAG, "Failed to copy profile " + profileFile.getAbsolutePath() + " ",
                                e);
                    }
                } else {
                    PackageSetting disabledPs = mPm.mSettings.getDisabledSystemPkgLPr(
                            pkg.getPackageName());
                    // Handle compressed APKs in this path. Only do this for stubs with profiles to
                    // minimize the number off apps being speed-profile compiled during first boot.
                    // The other paths will not change the filter.
                    if (disabledPs != null && disabledPs.getPkg().isStub()) {
                        // The package is the stub one, remove the stub suffix to get the normal
                        // package and APK names.
                        String systemProfilePath = getPrebuildProfilePath(disabledPs.getPkg())
                                .replace(STUB_SUFFIX, "");
                        profileFile = new File(systemProfilePath);
                        // If we have a profile for a compressed APK, copy it to the reference
                        // location.
                        // Note that copying the profile here will cause it to override the
                        // reference profile every OTA even though the existing reference profile
                        // may have more data. We can't copy during decompression since the
                        // directories are not set up at that point.
                        if (profileFile.exists()) {
                            try {
                                // We could also do this lazily before calling dexopt in
                                // PackageDexOptimizer to prevent this happening on first boot. The
                                // issue is that we don't have a good way to say "do this only
                                // once".
                                if (!mPm.mInstaller.copySystemProfile(profileFile.getAbsolutePath(),
                                        pkg.getUid(), pkg.getPackageName(),
                                        ArtManager.getProfileName(null))) {
                                    Log.e(TAG, "Failed to copy system profile for stub package!");
                                } else {
                                    useProfileForDexopt = true;
                                }
                            } catch (InstallerException | RuntimeException e) {
                                Log.e(TAG, "Failed to copy profile "
                                        + profileFile.getAbsolutePath() + " ", e);
                            }
                        }
                    }
                }
            }

            if (!mPm.mPackageDexOptimizer.canOptimizePackage(pkg)) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Skipping update of non-optimizable app " + pkg.getPackageName());
                }
                numberOfPackagesSkipped++;
                continue;
            }

            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Updating app " + numberOfPackagesVisited + " of "
                        + numberOfPackagesToDexopt + ": " + pkg.getPackageName());
            }

            int pkgCompilationReason = compilationReason;
            if (useProfileForDexopt) {
                // Use background dexopt mode to try and use the profile. Note that this does not
                // guarantee usage of the profile.
                pkgCompilationReason = PackageManagerService.REASON_BACKGROUND_DEXOPT;
            }

            int dexoptFlags = bootComplete ? DexoptOptions.DEXOPT_BOOT_COMPLETE : 0;

            String filter = getCompilerFilterForReason(pkgCompilationReason);
            if (isProfileGuidedCompilerFilter(filter)) {
                // DEXOPT_CHECK_FOR_PROFILES_UPDATES used to be false to avoid merging profiles
                // during boot which might interfere with background compilation (b/28612421).
                // However those problems were related to the verify-profile compiler filter which
                // doesn't exist any more, so enable it again.
                dexoptFlags |= DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES;
            }

            if (compilationReason == REASON_FIRST_BOOT) {
                // TODO: This doesn't cover the upgrade case, we should check for this too.
                dexoptFlags |= DexoptOptions.DEXOPT_INSTALL_WITH_DEX_METADATA_FILE;
            }
            int primaryDexOptStatus = performDexOptTraced(
                    new DexoptOptions(pkg.getPackageName(), pkgCompilationReason, filter,
                            /*splitName*/ null, dexoptFlags));

            switch (primaryDexOptStatus) {
                case PackageDexOptimizer.DEX_OPT_PERFORMED:
                    numberOfPackagesOptimized++;
                    break;
                case PackageDexOptimizer.DEX_OPT_SKIPPED:
                    numberOfPackagesSkipped++;
                    break;
                case PackageDexOptimizer.DEX_OPT_CANCELLED:
                    // ignore this case
                    break;
                case PackageDexOptimizer.DEX_OPT_FAILED:
                    numberOfPackagesFailed++;
                    break;
                default:
                    Log.e(TAG, "Unexpected dexopt return code " + primaryDexOptStatus);
                    break;
            }
        }

        return new int[]{numberOfPackagesOptimized, numberOfPackagesSkipped,
                numberOfPackagesFailed};
    }

    /**
     * Checks if system UI package (typically "com.android.systemui") needs to be re-compiled, and
     * compiles it if needed.
     */
    private void checkAndDexOptSystemUi(int reason) throws LegacyDexoptDisabledException {
        Computer snapshot = mPm.snapshotComputer();
        String sysUiPackageName =
                mPm.mContext.getString(com.android.internal.R.string.config_systemUi);
        AndroidPackage pkg = snapshot.getPackage(sysUiPackageName);
        if (pkg == null) {
            Log.w(TAG, "System UI package " + sysUiPackageName + " is not found for dexopting");
            return;
        }

        String defaultCompilerFilter = getCompilerFilterForReason(reason);
        String targetCompilerFilter =
                SystemProperties.get("dalvik.vm.systemuicompilerfilter", defaultCompilerFilter);
        String compilerFilter;

        if (isProfileGuidedCompilerFilter(targetCompilerFilter)) {
            compilerFilter = "verify";
            File profileFile = new File(getPrebuildProfilePath(pkg));

            // Copy the profile to the reference profile path if it exists. Installd can only use a
            // profile at the reference profile path for dexopt.
            if (profileFile.exists()) {
                try {
                    synchronized (mPm.mInstallLock) {
                        if (mPm.mInstaller.copySystemProfile(profileFile.getAbsolutePath(),
                                    pkg.getUid(), pkg.getPackageName(),
                                    ArtManager.getProfileName(null))) {
                            compilerFilter = targetCompilerFilter;
                        } else {
                            Log.e(TAG, "Failed to copy profile " + profileFile.getAbsolutePath());
                        }
                    }
                } catch (InstallerException | RuntimeException e) {
                    Log.e(TAG, "Failed to copy profile " + profileFile.getAbsolutePath(), e);
                }
            }
        } else {
            compilerFilter = targetCompilerFilter;
        }

        performDexoptPackage(sysUiPackageName, reason, compilerFilter);
    }

    private void dexoptLauncher(int reason) throws LegacyDexoptDisabledException {
        Computer snapshot = mPm.snapshotComputer();
        RoleManager roleManager = mPm.mContext.getSystemService(RoleManager.class);
        for (var packageName : roleManager.getRoleHolders(RoleManager.ROLE_HOME)) {
            AndroidPackage pkg = snapshot.getPackage(packageName);
            if (pkg == null) {
                Log.w(TAG, "Launcher package " + packageName + " is not found for dexopting");
            } else {
                performDexoptPackage(packageName, reason, "speed-profile");
            }
        }
    }

    private void performDexoptPackage(@NonNull String packageName, int reason,
            @NonNull String compilerFilter) throws LegacyDexoptDisabledException {
        Installer.checkLegacyDexoptDisabled();

        // DEXOPT_CHECK_FOR_PROFILES_UPDATES is set to replicate behaviour that will be
        // unconditionally enabled for profile guided filters when ART Service is called instead of
        // the legacy PackageDexOptimizer implementation.
        int dexoptFlags = isProfileGuidedCompilerFilter(compilerFilter)
                ? DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES
                : 0;

        performDexOptTraced(new DexoptOptions(
                packageName, reason, compilerFilter, null /* splitName */, dexoptFlags));
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

        if (useArtService()) {
            mBootDexoptStartTime = startTime;
            getArtManagerLocal().onBoot(DexoptOptions.convertToArtServiceDexoptReason(reason),
                    null /* progressCallbackExecutor */, null /* progressCallback */);
        } else {
            try {
                // System UI and the launcher are important to user experience, so we check them
                // after a mainline update or OTA. They may need to be re-compiled in these cases.
                checkAndDexOptSystemUi(reason);
                dexoptLauncher(reason);

                if (reason != REASON_BOOT_AFTER_OTA && reason != REASON_FIRST_BOOT) {
                    return;
                }

                final Computer snapshot = mPm.snapshotComputer();

                // TODO(b/251903639): Align this with how ART Service selects packages for boot
                // compilation.
                List<PackageStateInternal> pkgSettings =
                        getPackagesForDexopt(snapshot.getPackageStates().values(), mPm);

                final int[] stats =
                        performDexOptUpgrade(pkgSettings, reason, false /* bootComplete */);
                reportBootDexopt(startTime, stats[0], stats[1], stats[2]);
            } catch (LegacyDexoptDisabledException e) {
                throw new RuntimeException(e);
            }
        }
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
            if (useArtService()) {
                dexoptStatus = performDexOptWithArtService(options, 0 /* extraFlags */);
            } else {
                try {
                    return mPm.getDexManager().dexoptSecondaryDex(options);
                } catch (LegacyDexoptDisabledException e) {
                    throw new RuntimeException(e);
                }
            }
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
        if (useArtService()) {
            return performDexOptWithArtService(options, ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES);
        }

        AndroidPackage p;
        PackageSetting pkgSetting;
        synchronized (mPm.mLock) {
            p = mPm.mPackages.get(options.getPackageName());
            pkgSetting = mPm.mSettings.getPackageLPr(options.getPackageName());
            if (p == null || pkgSetting == null) {
                // Package could not be found. Report failure.
                return PackageDexOptimizer.DEX_OPT_FAILED;
            }
            if (p.isApex()) {
                // APEX needs no dexopt
                return PackageDexOptimizer.DEX_OPT_SKIPPED;
            }
            mPm.getPackageUsage().maybeWriteAsync(mPm.mSettings.getPackagesLocked());
            mPm.mCompilerStats.maybeWriteAsync();
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            return performDexOptInternalWithDependenciesLI(p, pkgSetting, options);
        } catch (LegacyDexoptDisabledException e) {
            throw new RuntimeException(e);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Performs dexopt on the given package using ART Service. May only be called when ART Service
     * is enabled, i.e. when {@link useArtService} returns true.
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

    @DexOptResult
    private int performDexOptInternalWithDependenciesLI(
            AndroidPackage p, @NonNull PackageStateInternal pkgSetting, DexoptOptions options)
            throws LegacyDexoptDisabledException {
        if (PLATFORM_PACKAGE_NAME.equals(p.getPackageName())) {
            // This needs to be done in odrefresh in early boot, for security reasons.
            throw new IllegalArgumentException("Cannot dexopt the system server");
        }

        // Select the dex optimizer based on the force parameter.
        // Note: The force option is rarely used (cmdline input for testing, mostly), so it's OK to
        //       allocate an object here.
        PackageDexOptimizer pdo = options.isForce()
                ? new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(mPm.mPackageDexOptimizer)
                : mPm.mPackageDexOptimizer;

        // Dexopt all dependencies first. Note: we ignore the return value and march on
        // on errors.
        // Note that we are going to call performDexOpt on those libraries as many times as
        // they are referenced in packages. When we do a batch of performDexOpt (for example
        // at boot, or background job), the passed 'targetCompilerFilter' stays the same,
        // and the first package that uses the library will dexopt it. The
        // others will see that the compiled code for the library is up to date.
        Collection<SharedLibraryInfo> deps = SharedLibraryUtils.findSharedLibraries(pkgSetting);
        final String[] instructionSets = getAppDexInstructionSets(
                pkgSetting.getPrimaryCpuAbi(),
                pkgSetting.getSecondaryCpuAbi());
        if (!deps.isEmpty()) {
            DexoptOptions libraryOptions = new DexoptOptions(options.getPackageName(),
                    options.getCompilationReason(), options.getCompilerFilter(),
                    options.getSplitName(),
                    options.getFlags() | DexoptOptions.DEXOPT_AS_SHARED_LIBRARY);
            for (SharedLibraryInfo info : deps) {
                Computer snapshot = mPm.snapshotComputer();
                AndroidPackage depPackage = snapshot.getPackage(info.getPackageName());
                PackageStateInternal depPackageStateInternal =
                        snapshot.getPackageStateInternal(info.getPackageName());
                if (depPackage != null && depPackageStateInternal != null) {
                    // TODO: Analyze and investigate if we (should) profile libraries.
                    pdo.performDexOpt(depPackage, depPackageStateInternal, instructionSets,
                            mPm.getOrCreateCompilerPackageStats(depPackage),
                            mPm.getDexManager().getPackageUseInfoOrDefault(
                                    depPackage.getPackageName()), libraryOptions);
                } else {
                    // TODO(ngeoffray): Support dexopting system shared libraries.
                }
            }
        }

        return pdo.performDexOpt(p, pkgSetting, instructionSets,
                mPm.getOrCreateCompilerPackageStats(p),
                mPm.getDexManager().getPackageUseInfoOrDefault(p.getPackageName()), options);
    }

    /** @deprecated For legacy shell command only. */
    @Deprecated
    public void forceDexOpt(@NonNull Computer snapshot, String packageName)
            throws LegacyDexoptDisabledException {
        PackageManagerServiceUtils.enforceSystemOrRoot("forceDexOpt");

        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        final AndroidPackage pkg = packageState == null ? null : packageState.getPkg();
        if (packageState == null || pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        if (pkg.isApex()) {
            throw new IllegalArgumentException("Can't dexopt APEX package: " + packageName);
        }

        Trace.traceBegin(TRACE_TAG_DALVIK, "dexopt");

        // Whoever is calling forceDexOpt wants a compiled package.
        // Don't use profiles since that may cause compilation to be skipped.
        DexoptOptions options = new DexoptOptions(packageName, REASON_CMDLINE,
                getDefaultCompilerFilter(), null /* splitName */,
                DexoptOptions.DEXOPT_FORCE | DexoptOptions.DEXOPT_BOOT_COMPLETE);

        @DexOptResult int res = performDexOptInternalWithDependenciesLI(pkg, packageState, options);

        Trace.traceEnd(TRACE_TAG_DALVIK);
        if (res != PackageDexOptimizer.DEX_OPT_PERFORMED) {
            throw new IllegalStateException("Failed to dexopt: " + res);
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

    /*package*/ void controlDexOptBlocking(boolean block) throws LegacyDexoptDisabledException {
        mPm.mPackageDexOptimizer.controlDexOptBlocking(block);
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
     * Returns true if ART Service should be used for package optimization.
     */
    public static boolean useArtService() {
        return SystemProperties.getBoolean("dalvik.vm.useartservice", false);
    }

    /**
     * Returns {@link DexUseManagerLocal} if ART Service should be used for package optimization.
     */
    public static @Nullable DexUseManagerLocal getDexUseManagerLocal() {
        if (!useArtService()) {
            return null;
        }
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
        if (!useArtService()) {
            return;
        }

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
}
