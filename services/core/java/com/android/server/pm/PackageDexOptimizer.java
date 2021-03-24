/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.pm;

import static android.content.pm.ApplicationInfo.HIDDEN_API_ENFORCEMENT_DISABLED;

import static com.android.server.pm.Installer.DEXOPT_BOOTCOMPLETE;
import static com.android.server.pm.Installer.DEXOPT_DEBUGGABLE;
import static com.android.server.pm.Installer.DEXOPT_ENABLE_HIDDEN_API_CHECKS;
import static com.android.server.pm.Installer.DEXOPT_FORCE;
import static com.android.server.pm.Installer.DEXOPT_FOR_RESTORE;
import static com.android.server.pm.Installer.DEXOPT_GENERATE_APP_IMAGE;
import static com.android.server.pm.Installer.DEXOPT_GENERATE_COMPACT_DEX;
import static com.android.server.pm.Installer.DEXOPT_IDLE_BACKGROUND_JOB;
import static com.android.server.pm.Installer.DEXOPT_PROFILE_GUIDED;
import static com.android.server.pm.Installer.DEXOPT_PUBLIC;
import static com.android.server.pm.Installer.DEXOPT_SECONDARY_DEX;
import static com.android.server.pm.Installer.DEXOPT_STORAGE_CE;
import static com.android.server.pm.Installer.DEXOPT_STORAGE_DE;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.WATCHDOG_TIMEOUT;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getReasonName;

import static dalvik.system.DexFile.getSafeModeCompilerFilter;
import static dalvik.system.DexFile.isProfileGuidedCompilerFilter;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.dex.ArtManager;
import android.content.pm.dex.DexMetadataHelper;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.ArtStatsLogUtils;
import com.android.server.pm.dex.ArtStatsLogUtils.ArtStatsLogger;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.dex.DexoptUtils;
import com.android.server.pm.dex.PackageDexUsage;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;

import dalvik.system.DexFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Helper class for running dexopt command on packages.
 */
public class PackageDexOptimizer {
    private static final String TAG = "PackageDexOptimizer";
    static final String OAT_DIR_NAME = "oat";
    // TODO b/19550105 Remove error codes and use exceptions
    public static final int DEX_OPT_SKIPPED = 0;
    public static final int DEX_OPT_PERFORMED = 1;
    public static final int DEX_OPT_FAILED = -1;
    // One minute over PM WATCHDOG_TIMEOUT
    private static final long WAKELOCK_TIMEOUT_MS = WATCHDOG_TIMEOUT + 1000 * 60;

    @GuardedBy("mInstallLock")
    private final Installer mInstaller;
    private final Object mInstallLock;

    @GuardedBy("mInstallLock")
    private final PowerManager.WakeLock mDexoptWakeLock;
    private volatile boolean mSystemReady;

    private final ArtStatsLogger mArtStatsLogger = new ArtStatsLogger();

    PackageDexOptimizer(Installer installer, Object installLock, Context context,
            String wakeLockTag) {
        this.mInstaller = installer;
        this.mInstallLock = installLock;

        PowerManager powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mDexoptWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag);
    }

    protected PackageDexOptimizer(PackageDexOptimizer from) {
        this.mInstaller = from.mInstaller;
        this.mInstallLock = from.mInstallLock;
        this.mDexoptWakeLock = from.mDexoptWakeLock;
        this.mSystemReady = from.mSystemReady;
    }

    static boolean canOptimizePackage(AndroidPackage pkg) {
        // We do not dexopt a package with no code.
        // Note that the system package is marked as having no code, however we can
        // still optimize it via dexoptSystemServerPath.
        if (!PLATFORM_PACKAGE_NAME.equals(pkg.getPackageName()) && !pkg.isHasCode()) {
            return false;
        }

        return true;
    }

    /**
     * Performs dexopt on all code paths and libraries of the specified package for specified
     * instruction sets.
     *
     * <p>Calls to {@link com.android.server.pm.Installer#dexopt} on {@link #mInstaller} are
     * synchronized on {@link #mInstallLock}.
     */
    int performDexOpt(AndroidPackage pkg, @NonNull PackageSetting pkgSetting,
            String[] instructionSets, CompilerStats.PackageStats packageStats,
            PackageDexUsage.PackageUseInfo packageUseInfo, DexoptOptions options) {
        if (PLATFORM_PACKAGE_NAME.equals(pkg.getPackageName())) {
            throw new IllegalArgumentException("System server dexopting should be done via "
                    + " DexManager and PackageDexOptimizer#dexoptSystemServerPath");
        }
        if (pkg.getUid() == -1) {
            throw new IllegalArgumentException("Dexopt for " + pkg.getPackageName()
                    + " has invalid uid.");
        }
        if (!canOptimizePackage(pkg)) {
            return DEX_OPT_SKIPPED;
        }
        synchronized (mInstallLock) {
            final long acquireTime = acquireWakeLockLI(pkg.getUid());
            try {
                return performDexOptLI(pkg, pkgSetting, instructionSets,
                        packageStats, packageUseInfo, options);
            } finally {
                releaseWakeLockLI(acquireTime);
            }
        }
    }

    /**
     * Performs dexopt on all code paths of the given package.
     * It assumes the install lock is held.
     */
    @GuardedBy("mInstallLock")
    private int performDexOptLI(AndroidPackage pkg, @NonNull PackageSetting pkgSetting,
            String[] targetInstructionSets, CompilerStats.PackageStats packageStats,
            PackageDexUsage.PackageUseInfo packageUseInfo, DexoptOptions options) {
        final List<SharedLibraryInfo> sharedLibraries = pkgSetting.getPkgState()
                .getUsesLibraryInfos();
        final String[] instructionSets = targetInstructionSets != null ?
                targetInstructionSets : getAppDexInstructionSets(
                AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting),
                AndroidPackageUtils.getSecondaryCpuAbi(pkg, pkgSetting));
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
        final List<String> paths = AndroidPackageUtils.getAllCodePaths(pkg);

        int sharedGid = UserHandle.getSharedAppGid(pkg.getUid());
        if (sharedGid == -1) {
            Slog.wtf(TAG, "Well this is awkward; package " + pkg.getPackageName() + " had UID "
                    + pkg.getUid(), new Throwable());
            sharedGid = android.os.Process.NOBODY_UID;
        }

        // Get the class loader context dependencies.
        // For each code path in the package, this array contains the class loader context that
        // needs to be passed to dexopt in order to ensure correct optimizations.
        boolean[] pathsWithCode = new boolean[paths.size()];
        pathsWithCode[0] = pkg.isHasCode();
        for (int i = 1; i < paths.size(); i++) {
            pathsWithCode[i] = (pkg.getSplitFlags()[i - 1] & ApplicationInfo.FLAG_HAS_CODE) != 0;
        }
        String[] classLoaderContexts = DexoptUtils.getClassLoaderContexts(
                pkg, sharedLibraries, pathsWithCode);

        // Validity check that we do not call dexopt with inconsistent data.
        if (paths.size() != classLoaderContexts.length) {
            String[] splitCodePaths = pkg.getSplitCodePaths();
            throw new IllegalStateException("Inconsistent information "
                + "between PackageParser.Package and its ApplicationInfo. "
                + "pkg.getAllCodePaths=" + paths
                + " pkg.getBaseCodePath=" + pkg.getBaseCodePath()
                + " pkg.getSplitCodePaths="
                + (splitCodePaths == null ? "null" : Arrays.toString(splitCodePaths)));
        }

        int result = DEX_OPT_SKIPPED;
        for (int i = 0; i < paths.size(); i++) {
            // Skip paths that have no code.
            if (!pathsWithCode[i]) {
                continue;
            }
            if (classLoaderContexts[i] == null) {
                throw new IllegalStateException("Inconsistent information in the "
                        + "package structure. A split is marked to contain code "
                        + "but has no dependency listed. Index=" + i + " path=" + paths.get(i));
            }

            // Append shared libraries with split dependencies for this split.
            String path = paths.get(i);
            if (options.getSplitName() != null) {
                // We are asked to compile only a specific split. Check that the current path is
                // what we are looking for.
                if (!options.getSplitName().equals(new File(path).getName())) {
                    continue;
                }
            }

            String profileName = ArtManager.getProfileName(
                    i == 0 ? null : pkg.getSplitNames()[i - 1]);

            String dexMetadataPath = null;
            if (options.isDexoptInstallWithDexMetadata()) {
                File dexMetadataFile = DexMetadataHelper.findDexMetadataForFile(new File(path));
                dexMetadataPath = dexMetadataFile == null
                        ? null : dexMetadataFile.getAbsolutePath();
            }

            final boolean isUsedByOtherApps = options.isDexoptAsSharedLibrary()
                    || packageUseInfo.isUsedByOtherApps(path);
            final String compilerFilter = getRealCompilerFilter(pkg,
                options.getCompilerFilter(), isUsedByOtherApps);
            final boolean profileUpdated = options.isCheckForProfileUpdates() &&
                isProfileUpdated(pkg, sharedGid, profileName, compilerFilter);

            // Get the dexopt flags after getRealCompilerFilter to make sure we get the correct
            // flags.
            final int dexoptFlags = getDexFlags(pkg, pkgSetting, compilerFilter, options);

            for (String dexCodeIsa : dexCodeInstructionSets) {
                int newResult = dexOptPath(pkg, pkgSetting, path, dexCodeIsa, compilerFilter,
                        profileUpdated, classLoaderContexts[i], dexoptFlags, sharedGid,
                        packageStats, options.isDowngrade(), profileName, dexMetadataPath,
                        options.getCompilationReason());

                // Only report metrics for base apk for now.
                // TODO: add ISA and APK type to metrics.
                // OTAPreopt doesn't have stats so don't report in that case.
                if (pkg.getBaseCodePath().equals(path) && packageStats != null) {
                    Trace.traceBegin(Trace.TRACE_TAG_PACKAGE_MANAGER, "dex2oat-metrics");
                    try {
                        long sessionId = Math.randomLongInternal();
                        ArtStatsLogUtils.writeStatsLog(
                                mArtStatsLogger,
                                sessionId,
                                path,
                                compilerFilter,
                                sharedGid,
                                packageStats.getCompileTime(path),
                                dexMetadataPath,
                                options.getCompilationReason(),
                                newResult);
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_PACKAGE_MANAGER);
                    }
                }

                // The end result is:
                //  - FAILED if any path failed,
                //  - PERFORMED if at least one path needed compilation,
                //  - SKIPPED when all paths are up to date
                if ((result != DEX_OPT_FAILED) && (newResult != DEX_OPT_SKIPPED)) {
                    result = newResult;
                }
            }
        }
        return result;
    }

    /**
     * Performs dexopt on the {@code path} belonging to the package {@code pkg}.
     *
     * @return
     *      DEX_OPT_FAILED if there was any exception during dexopt
     *      DEX_OPT_PERFORMED if dexopt was performed successfully on the given path.
     *      DEX_OPT_SKIPPED if the path does not need to be deopt-ed.
     */
    @GuardedBy("mInstallLock")
    private int dexOptPath(AndroidPackage pkg, @NonNull PackageSetting pkgSetting, String path,
            String isa, String compilerFilter, boolean profileUpdated, String classLoaderContext,
            int dexoptFlags, int uid, CompilerStats.PackageStats packageStats, boolean downgrade,
            String profileName, String dexMetadataPath, int compilationReason) {
        int dexoptNeeded = getDexoptNeeded(path, isa, compilerFilter, classLoaderContext,
                profileUpdated, downgrade);
        if (Math.abs(dexoptNeeded) == DexFile.NO_DEXOPT_NEEDED) {
            return DEX_OPT_SKIPPED;
        }

        String oatDir = getPackageOatDirIfSupported(pkg,
                pkgSetting.getPkgState().isUpdatedSystemApp());

        Log.i(TAG, "Running dexopt (dexoptNeeded=" + dexoptNeeded + ") on: " + path
                + " pkg=" + pkg.getPackageName() + " isa=" + isa
                + " dexoptFlags=" + printDexoptFlags(dexoptFlags)
                + " targetFilter=" + compilerFilter + " oatDir=" + oatDir
                + " classLoaderContext=" + classLoaderContext);

        try {
            long startTime = System.currentTimeMillis();

            // TODO: Consider adding 2 different APIs for primary and secondary dexopt.
            // installd only uses downgrade flag for secondary dex files and ignores it for
            // primary dex files.
            String seInfo = AndroidPackageUtils.getSeInfo(pkg, pkgSetting);
            mInstaller.dexopt(path, uid, pkg.getPackageName(), isa, dexoptNeeded, oatDir,
                    dexoptFlags, compilerFilter, pkg.getVolumeUuid(), classLoaderContext,
                    seInfo, false /* downgrade*/, pkg.getTargetSdkVersion(),
                    profileName, dexMetadataPath,
                    getAugmentedReasonName(compilationReason, dexMetadataPath != null));

            if (packageStats != null) {
                long endTime = System.currentTimeMillis();
                packageStats.setCompileTime(path, (int)(endTime - startTime));
            }
            return DEX_OPT_PERFORMED;
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to dexopt", e);
            return DEX_OPT_FAILED;
        }
    }

    /**
     * Perform dexopt (if needed) on a system server code path).
     */
    public int dexoptSystemServerPath(
            String dexPath, PackageDexUsage.DexUseInfo dexUseInfo, DexoptOptions options) {
        int dexoptFlags = DEXOPT_PUBLIC
                | (options.isBootComplete() ? DEXOPT_BOOTCOMPLETE : 0)
                | (options.isDexoptIdleBackgroundJob() ? DEXOPT_IDLE_BACKGROUND_JOB : 0);

        int result = DEX_OPT_SKIPPED;
        for (String isa : dexUseInfo.getLoaderIsas()) {
            int dexoptNeeded = getDexoptNeeded(
                    dexPath,
                    isa,
                    options.getCompilerFilter(),
                    dexUseInfo.getClassLoaderContext(),
                    /* newProfile= */false,
                    /* downgrade= */ false);

            if (dexoptNeeded == DexFile.NO_DEXOPT_NEEDED) {
                continue;
            }
            try {
                mInstaller.dexopt(
                        dexPath,
                        android.os.Process.SYSTEM_UID,
                        /* packageName= */ "android",
                        isa,
                        dexoptNeeded,
                        /* oatDir= */ null,
                        dexoptFlags,
                        options.getCompilerFilter(),
                        StorageManager.UUID_PRIVATE_INTERNAL,
                        dexUseInfo.getClassLoaderContext(),
                        /* seInfo= */ null,
                        /* downgrade= */ false ,
                        /* targetSdk= */ 0,
                        /* profileName */ null,
                        /* dexMetadataPath */ null,
                        getReasonName(options.getCompilationReason()));
            } catch (InstallerException e) {
                Slog.w(TAG, "Failed to dexopt", e);
                return DEX_OPT_FAILED;
            }
            result = DEX_OPT_PERFORMED;
        }
        return result;
    }

    private String getAugmentedReasonName(int compilationReason, boolean useDexMetadata) {
        String annotation = useDexMetadata
                ? ArtManagerService.DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION : "";
        return getReasonName(compilationReason) + annotation;
    }

    /**
     * Performs dexopt on the secondary dex {@code path} belonging to the app {@code info}.
     *
     * @return
     *      DEX_OPT_FAILED if there was any exception during dexopt
     *      DEX_OPT_PERFORMED if dexopt was performed successfully on the given path.
     * NOTE that DEX_OPT_PERFORMED for secondary dex files includes the case when the dex file
     * didn't need an update. That's because at the moment we don't get more than success/failure
     * from installd.
     *
     * TODO(calin): Consider adding return codes to installd dexopt invocation (rather than
     * throwing exceptions). Or maybe make a separate call to installd to get DexOptNeeded, though
     * that seems wasteful.
     */
    public int dexOptSecondaryDexPath(ApplicationInfo info, String path,
            PackageDexUsage.DexUseInfo dexUseInfo, DexoptOptions options) {
        if (info.uid == -1) {
            throw new IllegalArgumentException("Dexopt for path " + path + " has invalid uid.");
        }
        synchronized (mInstallLock) {
            final long acquireTime = acquireWakeLockLI(info.uid);
            try {
                return dexOptSecondaryDexPathLI(info, path, dexUseInfo, options);
            } finally {
                releaseWakeLockLI(acquireTime);
            }
        }
    }

    @GuardedBy("mInstallLock")
    private long acquireWakeLockLI(final int uid) {
        // During boot the system doesn't need to instantiate and obtain a wake lock.
        // PowerManager might not be ready, but that doesn't mean that we can't proceed with
        // dexopt.
        if (!mSystemReady) {
            return -1;
        }
        mDexoptWakeLock.setWorkSource(new WorkSource(uid));
        mDexoptWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        return SystemClock.elapsedRealtime();
    }

    @GuardedBy("mInstallLock")
    private void releaseWakeLockLI(final long acquireTime) {
        if (acquireTime < 0) {
            return;
        }
        try {
            if (mDexoptWakeLock.isHeld()) {
                mDexoptWakeLock.release();
            }
            final long duration = SystemClock.elapsedRealtime() - acquireTime;
            if (duration >= WAKELOCK_TIMEOUT_MS) {
                Slog.wtf(TAG, "WakeLock " + mDexoptWakeLock.getTag()
                        + " time out. Operation took " + duration + " ms. Thread: "
                        + Thread.currentThread().getName());
            }
        } catch (Exception e) {
            Slog.wtf(TAG, "Error while releasing " + mDexoptWakeLock.getTag() + " lock", e);
        }
    }

    @GuardedBy("mInstallLock")
    private int dexOptSecondaryDexPathLI(ApplicationInfo info, String path,
            PackageDexUsage.DexUseInfo dexUseInfo, DexoptOptions options) {
        if (options.isDexoptOnlySharedDex() && !dexUseInfo.isUsedByOtherApps()) {
            // We are asked to optimize only the dex files used by other apps and this is not
            // on of them: skip it.
            return DEX_OPT_SKIPPED;
        }

        String compilerFilter = getRealCompilerFilter(info, options.getCompilerFilter(),
                dexUseInfo.isUsedByOtherApps());
        // Get the dexopt flags after getRealCompilerFilter to make sure we get the correct flags.
        // Secondary dex files are currently not compiled at boot.
        int dexoptFlags = getDexFlags(info, compilerFilter, options) | DEXOPT_SECONDARY_DEX;
        // Check the app storage and add the appropriate flags.
        if (info.deviceProtectedDataDir != null &&
                FileUtils.contains(info.deviceProtectedDataDir, path)) {
            dexoptFlags |= DEXOPT_STORAGE_DE;
        } else if (info.credentialProtectedDataDir != null &&
                FileUtils.contains(info.credentialProtectedDataDir, path)) {
            dexoptFlags |= DEXOPT_STORAGE_CE;
        } else {
            Slog.e(TAG, "Could not infer CE/DE storage for package " + info.packageName);
            return DEX_OPT_FAILED;
        }
        String classLoaderContext = null;
        if (dexUseInfo.isUnsupportedClassLoaderContext()
                || dexUseInfo.isVariableClassLoaderContext()) {
            // If we have an unknown (not yet set), or a variable class loader chain. Just extract
            // the dex file.
            compilerFilter = "extract";
        } else {
            classLoaderContext = dexUseInfo.getClassLoaderContext();
        }

        int reason = options.getCompilationReason();
        Log.d(TAG, "Running dexopt on: " + path
                + " pkg=" + info.packageName + " isa=" + dexUseInfo.getLoaderIsas()
                + " reason=" + getReasonName(reason)
                + " dexoptFlags=" + printDexoptFlags(dexoptFlags)
                + " target-filter=" + compilerFilter
                + " class-loader-context=" + classLoaderContext);

        try {
            for (String isa : dexUseInfo.getLoaderIsas()) {
                // Reuse the same dexopt path as for the primary apks. We don't need all the
                // arguments as some (dexopNeeded and oatDir) will be computed by installd because
                // system server cannot read untrusted app content.
                // TODO(calin): maybe add a separate call.
                mInstaller.dexopt(path, info.uid, info.packageName, isa, /*dexoptNeeded*/ 0,
                        /*oatDir*/ null, dexoptFlags,
                        compilerFilter, info.volumeUuid, classLoaderContext, info.seInfo,
                        options.isDowngrade(), info.targetSdkVersion, /*profileName*/ null,
                        /*dexMetadataPath*/ null, getReasonName(reason));
            }

            return DEX_OPT_PERFORMED;
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to dexopt", e);
            return DEX_OPT_FAILED;
        }
    }

    /**
     * Adjust the given dexopt-needed value. Can be overridden to influence the decision to
     * optimize or not (and in what way).
     */
    protected int adjustDexoptNeeded(int dexoptNeeded) {
        return dexoptNeeded;
    }

    /**
     * Adjust the given dexopt flags that will be passed to the installer.
     */
    protected int adjustDexoptFlags(int dexoptFlags) {
        return dexoptFlags;
    }

    /**
     * Dumps the dexopt state of the given package {@code pkg} to the given {@code PrintWriter}.
     */
    void dumpDexoptState(IndentingPrintWriter pw, AndroidPackage pkg, PackageSetting pkgSetting,
            PackageDexUsage.PackageUseInfo useInfo) {
        final String[] instructionSets = getAppDexInstructionSets(
                AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting),
                AndroidPackageUtils.getSecondaryCpuAbi(pkg, pkgSetting));
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);

        final List<String> paths = AndroidPackageUtils.getAllCodePathsExcludingResourceOnly(pkg);

        for (String path : paths) {
            pw.println("path: " + path);
            pw.increaseIndent();

            for (String isa : dexCodeInstructionSets) {
                try {
                    DexFile.OptimizationInfo info = DexFile.getDexFileOptimizationInfo(path, isa);
                    pw.println(isa + ": [status=" + info.getStatus()
                            +"] [reason=" + info.getReason() + "]");
                } catch (IOException ioe) {
                    pw.println(isa + ": [Exception]: " + ioe.getMessage());
                }
            }

            if (useInfo.isUsedByOtherApps(path)) {
                pw.println("used by other apps: " + useInfo.getLoadingPackages(path));
            }

            Map<String, PackageDexUsage.DexUseInfo> dexUseInfoMap = useInfo.getDexUseInfoMap();

            if (!dexUseInfoMap.isEmpty()) {
                pw.println("known secondary dex files:");
                pw.increaseIndent();
                for (Map.Entry<String, PackageDexUsage.DexUseInfo> e : dexUseInfoMap.entrySet()) {
                    String dex = e.getKey();
                    PackageDexUsage.DexUseInfo dexUseInfo = e.getValue();
                    pw.println(dex);
                    pw.increaseIndent();
                    // TODO(calin): get the status of the oat file (needs installd call)
                    pw.println("class loader context: " + dexUseInfo.getClassLoaderContext());
                    if (dexUseInfo.isUsedByOtherApps()) {
                        pw.println("used by other apps: " + dexUseInfo.getLoadingPackages());
                    }
                    pw.decreaseIndent();
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }
    }

    /**
     * Returns the compiler filter that should be used to optimize the package code.
     * The target filter will be updated if the package code is used by other apps
     * or if it has the safe mode flag set.
     */
    private String getRealCompilerFilter(ApplicationInfo info, String targetCompilerFilter,
            boolean isUsedByOtherApps) {
        // When an app or priv app is configured to run out of box, only verify it.
        if (info.isEmbeddedDexUsed()
                || (info.isPrivilegedApp()
                && DexManager.isPackageSelectedToRunOob(info.packageName))) {
            return "verify";
        }

        // We force vmSafeMode on debuggable apps as well:
        //  - the runtime ignores their compiled code
        //  - they generally have lots of methods that could make the compiler used run
        //    out of memory (b/130828957)
        // Note that forcing the compiler filter here applies to all compilations (even if they
        // are done via adb shell commands). That's ok because right now the runtime will ignore
        // the compiled code anyway. The alternative would have been to update either
        // PackageDexOptimizer#canOptimizePackage or PackageManagerService#getOptimizablePackages
        // but that would have the downside of possibly producing a big odex files which would
        // be ignored anyway.
        boolean vmSafeModeOrDebuggable = ((info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0)
                || ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        if (vmSafeModeOrDebuggable) {
            return getSafeModeCompilerFilter(targetCompilerFilter);
        }

        if (isProfileGuidedCompilerFilter(targetCompilerFilter) && isUsedByOtherApps) {
            // If the dex files is used by other apps, apply the shared filter.
            return PackageManagerServiceCompilerMapping.getCompilerFilterForReason(
                    PackageManagerService.REASON_SHARED);
        }

        return targetCompilerFilter;
    }

    /**
     * Returns the compiler filter that should be used to optimize the package code.
     * The target filter will be updated if the package code is used by other apps
     * or if it has the safe mode flag set.
     */
    private String getRealCompilerFilter(AndroidPackage pkg, String targetCompilerFilter,
            boolean isUsedByOtherApps) {
        // When an app or priv app is configured to run out of box, only verify it.
        if (pkg.isUseEmbeddedDex()
                || (pkg.isPrivileged()
                    && DexManager.isPackageSelectedToRunOob(pkg.getPackageName()))) {
            return "verify";
        }

        // We force vmSafeMode on debuggable apps as well:
        //  - the runtime ignores their compiled code
        //  - they generally have lots of methods that could make the compiler used run
        //    out of memory (b/130828957)
        // Note that forcing the compiler filter here applies to all compilations (even if they
        // are done via adb shell commands). That's ok because right now the runtime will ignore
        // the compiled code anyway. The alternative would have been to update either
        // PackageDexOptimizer#canOptimizePackage or PackageManagerService#getOptimizablePackages
        // but that would have the downside of possibly producing a big odex files which would
        // be ignored anyway.
        boolean vmSafeModeOrDebuggable = pkg.isVmSafeMode() || pkg.isDebuggable();

        if (vmSafeModeOrDebuggable) {
            return getSafeModeCompilerFilter(targetCompilerFilter);
        }

        if (isProfileGuidedCompilerFilter(targetCompilerFilter) && isUsedByOtherApps) {
            // If the dex files is used by other apps, apply the shared filter.
            return PackageManagerServiceCompilerMapping.getCompilerFilterForReason(
                    PackageManagerService.REASON_SHARED);
        }

        return targetCompilerFilter;
    }

    private boolean isAppImageEnabled() {
        return SystemProperties.get("dalvik.vm.appimageformat", "").length() > 0;
    }

    private int getDexFlags(ApplicationInfo info, String compilerFilter, DexoptOptions options) {
        return getDexFlags((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                info.getHiddenApiEnforcementPolicy(), info.splitDependencies,
                info.requestsIsolatedSplitLoading(), compilerFilter, options);
    }
    private int getDexFlags(AndroidPackage pkg, @NonNull PackageSetting pkgSetting,
            String compilerFilter, DexoptOptions options) {
        return getDexFlags(pkg.isDebuggable(),
                AndroidPackageUtils.getHiddenApiEnforcementPolicy(pkg, pkgSetting),
                pkg.getSplitDependencies(), pkg.isIsolatedSplitLoading(), compilerFilter,
                options);
    }

    /**
     * Computes the dex flags that needs to be pass to installd for the given package and compiler
     * filter.
     */
    private int getDexFlags(boolean debuggable, int hiddenApiEnforcementPolicy,
            SparseArray<int[]> splitDependencies, boolean requestsIsolatedSplitLoading,
            String compilerFilter, DexoptOptions options) {
        // Profile guide compiled oat files should not be public unles they are based
        // on profiles from dex metadata archives.
        // The flag isDexoptInstallWithDexMetadata applies only on installs when we know that
        // the user does not have an existing profile.
        boolean isProfileGuidedFilter = isProfileGuidedCompilerFilter(compilerFilter);
        boolean isPublic = !isProfileGuidedFilter || options.isDexoptInstallWithDexMetadata();
        int profileFlag = isProfileGuidedFilter ? DEXOPT_PROFILE_GUIDED : 0;
        // Some apps are executed with restrictions on hidden API usage. If this app is one
        // of them, pass a flag to dexopt to enable the same restrictions during compilation.
        // TODO we should pass the actual flag value to dexopt, rather than assuming denylist
        // TODO(b/135203078): This flag is no longer set as part of AndroidPackage
        //  and may not be preserved
        int hiddenApiFlag = hiddenApiEnforcementPolicy == HIDDEN_API_ENFORCEMENT_DISABLED
                ? 0
                : DEXOPT_ENABLE_HIDDEN_API_CHECKS;
        // Avoid generating CompactDex for modes that are latency critical.
        final int compilationReason = options.getCompilationReason();
        boolean generateCompactDex = true;
        switch (compilationReason) {
            case PackageManagerService.REASON_FIRST_BOOT:
            case PackageManagerService.REASON_BOOT_AFTER_OTA:
            case PackageManagerService.REASON_POST_BOOT:
            case PackageManagerService.REASON_INSTALL:
                 generateCompactDex = false;
        }
        // Use app images only if it is enabled and we are compiling
        // profile-guided (so the app image doesn't conservatively contain all classes).
        // If the app didn't request for the splits to be loaded in isolation or if it does not
        // declare inter-split dependencies, then all the splits will be loaded in the base
        // apk class loader (in the order of their definition, otherwise disable app images
        // because they are unsupported for multiple class loaders. b/7269679
        boolean generateAppImage = isProfileGuidedFilter && (splitDependencies == null ||
                !requestsIsolatedSplitLoading) && isAppImageEnabled();
        int dexFlags =
                (isPublic ? DEXOPT_PUBLIC : 0)
                | (debuggable ? DEXOPT_DEBUGGABLE : 0)
                | profileFlag
                | (options.isBootComplete() ? DEXOPT_BOOTCOMPLETE : 0)
                | (options.isDexoptIdleBackgroundJob() ? DEXOPT_IDLE_BACKGROUND_JOB : 0)
                | (generateCompactDex ? DEXOPT_GENERATE_COMPACT_DEX : 0)
                | (generateAppImage ? DEXOPT_GENERATE_APP_IMAGE : 0)
                | (options.isDexoptInstallForRestore() ? DEXOPT_FOR_RESTORE : 0)
                | hiddenApiFlag;
        return adjustDexoptFlags(dexFlags);
    }

    /**
     * Assesses if there's a need to perform dexopt on {@code path} for the given
     * configuration (isa, compiler filter, profile).
     */
    private int getDexoptNeeded(String path, String isa, String compilerFilter,
            String classLoaderContext, boolean newProfile, boolean downgrade) {
        int dexoptNeeded;
        try {
            dexoptNeeded = DexFile.getDexOptNeeded(path, isa, compilerFilter, classLoaderContext,
                    newProfile, downgrade);
        } catch (IOException ioe) {
            Slog.w(TAG, "IOException reading apk: " + path, ioe);
            return DEX_OPT_FAILED;
        } catch (Exception e) {
            Slog.wtf(TAG, "Unexpected exception when calling dexoptNeeded on " + path, e);
            return DEX_OPT_FAILED;
        }
        return adjustDexoptNeeded(dexoptNeeded);
    }

    /**
     * Checks if there is an update on the profile information of the {@code pkg}.
     * If the compiler filter is not profile guided the method returns false.
     *
     * Note that this is a "destructive" operation with side effects. Under the hood the
     * current profile and the reference profile will be merged and subsequent calls
     * may return a different result.
     */
    private boolean isProfileUpdated(AndroidPackage pkg, int uid, String profileName,
            String compilerFilter) {
        // Check if we are allowed to merge and if the compiler filter is profile guided.
        if (!isProfileGuidedCompilerFilter(compilerFilter)) {
            return false;
        }
        // Merge profiles. It returns whether or not there was an updated in the profile info.
        try {
            return mInstaller.mergeProfiles(uid, pkg.getPackageName(), profileName);
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to merge profiles", e);
        }
        return false;
    }

    /**
     * Gets oat dir for the specified package if needed and supported.
     * In certain cases oat directory
     * <strong>cannot</strong> be created:
     * <ul>
     *      <li>{@code pkg} is a system app, which is not updated.</li>
     *      <li>Package location is not a directory, i.e. monolithic install.</li>
     * </ul>
     *
     * @return Absolute path to the oat directory or null, if oat directories
     * not needed or unsupported for the package.
     */
    @Nullable
    private String getPackageOatDirIfSupported(AndroidPackage pkg, boolean isUpdatedSystemApp) {
        if (!AndroidPackageUtils.canHaveOatDir(pkg, isUpdatedSystemApp)) {
            return null;
        }
        File codePath = new File(pkg.getCodePath());
        if (!codePath.isDirectory()) {
            return null;
        }
        return getOatDir(codePath).getAbsolutePath();
    }

    /** Returns the oat dir for the given code path */
    public static File getOatDir(File codePath) {
        return new File(codePath, OAT_DIR_NAME);
    }

    void systemReady() {
        mSystemReady = true;
    }

    private String printDexoptFlags(int flags) {
        ArrayList<String> flagsList = new ArrayList<>();

        if ((flags & DEXOPT_BOOTCOMPLETE) == DEXOPT_BOOTCOMPLETE) {
            flagsList.add("boot_complete");
        }
        if ((flags & DEXOPT_DEBUGGABLE) == DEXOPT_DEBUGGABLE) {
            flagsList.add("debuggable");
        }
        if ((flags & DEXOPT_PROFILE_GUIDED) == DEXOPT_PROFILE_GUIDED) {
            flagsList.add("profile_guided");
        }
        if ((flags & DEXOPT_PUBLIC) == DEXOPT_PUBLIC) {
            flagsList.add("public");
        }
        if ((flags & DEXOPT_SECONDARY_DEX) == DEXOPT_SECONDARY_DEX) {
            flagsList.add("secondary");
        }
        if ((flags & DEXOPT_FORCE) == DEXOPT_FORCE) {
            flagsList.add("force");
        }
        if ((flags & DEXOPT_STORAGE_CE) == DEXOPT_STORAGE_CE) {
            flagsList.add("storage_ce");
        }
        if ((flags & DEXOPT_STORAGE_DE) == DEXOPT_STORAGE_DE) {
            flagsList.add("storage_de");
        }
        if ((flags & DEXOPT_IDLE_BACKGROUND_JOB) == DEXOPT_IDLE_BACKGROUND_JOB) {
            flagsList.add("idle_background_job");
        }
        if ((flags & DEXOPT_ENABLE_HIDDEN_API_CHECKS) == DEXOPT_ENABLE_HIDDEN_API_CHECKS) {
            flagsList.add("enable_hidden_api_checks");
        }

        return String.join(",", flagsList);
    }

    /**
     * A specialized PackageDexOptimizer that overrides already-installed checks, forcing a
     * dexopt path.
     */
    public static class ForcedUpdatePackageDexOptimizer extends PackageDexOptimizer {

        public ForcedUpdatePackageDexOptimizer(Installer installer, Object installLock,
                Context context, String wakeLockTag) {
            super(installer, installLock, context, wakeLockTag);
        }

        public ForcedUpdatePackageDexOptimizer(PackageDexOptimizer from) {
            super(from);
        }

        @Override
        protected int adjustDexoptNeeded(int dexoptNeeded) {
            if (dexoptNeeded == DexFile.NO_DEXOPT_NEEDED) {
                // Ensure compilation by pretending a compiler filter change on the
                // apk/odex location (the reason for the '-'. A positive value means
                // the 'oat' location).
                return -DexFile.DEX2OAT_FOR_FILTER;
            }
            return dexoptNeeded;
        }

        @Override
        protected int adjustDexoptFlags(int flags) {
            // Add DEXOPT_FORCE flag to signal installd that it should force compilation
            // and discard dexoptanalyzer result.
            return flags | DEXOPT_FORCE;
        }
    }
}
