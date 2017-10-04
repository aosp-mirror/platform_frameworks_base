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

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.dex.DexoptUtils;
import com.android.server.pm.dex.PackageDexUsage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import dalvik.system.DexFile;

import static com.android.server.pm.Installer.DEXOPT_BOOTCOMPLETE;
import static com.android.server.pm.Installer.DEXOPT_DEBUGGABLE;
import static com.android.server.pm.Installer.DEXOPT_PROFILE_GUIDED;
import static com.android.server.pm.Installer.DEXOPT_PUBLIC;
import static com.android.server.pm.Installer.DEXOPT_SECONDARY_DEX;
import static com.android.server.pm.Installer.DEXOPT_FORCE;
import static com.android.server.pm.Installer.DEXOPT_STORAGE_CE;
import static com.android.server.pm.Installer.DEXOPT_STORAGE_DE;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;

import static com.android.server.pm.PackageManagerService.WATCHDOG_TIMEOUT;

import static dalvik.system.DexFile.getNonProfileGuidedCompilerFilter;
import static dalvik.system.DexFile.getSafeModeCompilerFilter;
import static dalvik.system.DexFile.isProfileGuidedCompilerFilter;

/**
 * Helper class for running dexopt command on packages.
 */
public class PackageDexOptimizer {
    private static final String TAG = "PackageManager.DexOptimizer";
    static final String OAT_DIR_NAME = "oat";
    // TODO b/19550105 Remove error codes and use exceptions
    public static final int DEX_OPT_SKIPPED = 0;
    public static final int DEX_OPT_PERFORMED = 1;
    public static final int DEX_OPT_FAILED = -1;
    // One minute over PM WATCHDOG_TIMEOUT
    private static final long WAKELOCK_TIMEOUT_MS = WATCHDOG_TIMEOUT + 1000 * 60;

    /** Special library name that skips shared libraries check during compilation. */
    public static final String SKIP_SHARED_LIBRARY_CHECK = "&";

    @GuardedBy("mInstallLock")
    private final Installer mInstaller;
    private final Object mInstallLock;

    @GuardedBy("mInstallLock")
    private final PowerManager.WakeLock mDexoptWakeLock;
    private volatile boolean mSystemReady;

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

    static boolean canOptimizePackage(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
    }

    /**
     * Performs dexopt on all code paths and libraries of the specified package for specified
     * instruction sets.
     *
     * <p>Calls to {@link com.android.server.pm.Installer#dexopt} on {@link #mInstaller} are
     * synchronized on {@link #mInstallLock}.
     */
    int performDexOpt(PackageParser.Package pkg, String[] sharedLibraries,
            String[] instructionSets, CompilerStats.PackageStats packageStats,
            PackageDexUsage.PackageUseInfo packageUseInfo, DexoptOptions options) {
        if (!canOptimizePackage(pkg)) {
            return DEX_OPT_SKIPPED;
        }
        synchronized (mInstallLock) {
            final long acquireTime = acquireWakeLockLI(pkg.applicationInfo.uid);
            try {
                return performDexOptLI(pkg, sharedLibraries, instructionSets,
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
    private int performDexOptLI(PackageParser.Package pkg, String[] sharedLibraries,
            String[] targetInstructionSets, CompilerStats.PackageStats packageStats,
            PackageDexUsage.PackageUseInfo packageUseInfo, DexoptOptions options) {
        final String[] instructionSets = targetInstructionSets != null ?
                targetInstructionSets : getAppDexInstructionSets(pkg.applicationInfo);
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
        final List<String> paths = pkg.getAllCodePaths();
        final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);

        // Get the class loader context dependencies.
        // For each code path in the package, this array contains the class loader context that
        // needs to be passed to dexopt in order to ensure correct optimizations.
        boolean[] pathsWithCode = new boolean[paths.size()];
        pathsWithCode[0] = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
        for (int i = 1; i < paths.size(); i++) {
            pathsWithCode[i] = (pkg.splitFlags[i - 1] & ApplicationInfo.FLAG_HAS_CODE) != 0;
        }
        String[] classLoaderContexts = DexoptUtils.getClassLoaderContexts(
                pkg.applicationInfo, sharedLibraries, pathsWithCode);

        // Sanity check that we do not call dexopt with inconsistent data.
        if (paths.size() != classLoaderContexts.length) {
            String[] splitCodePaths = pkg.applicationInfo.getSplitCodePaths();
            throw new IllegalStateException("Inconsistent information "
                + "between PackageParser.Package and its ApplicationInfo. "
                + "pkg.getAllCodePaths=" + paths
                + " pkg.applicationInfo.getBaseCodePath=" + pkg.applicationInfo.getBaseCodePath()
                + " pkg.applicationInfo.getSplitCodePaths="
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

            final boolean isUsedByOtherApps = options.isDexoptAsSharedLibrary()
                    || packageUseInfo.isUsedByOtherApps(path);
            final String compilerFilter = getRealCompilerFilter(pkg.applicationInfo,
                options.getCompilerFilter(), isUsedByOtherApps);
            final boolean profileUpdated = options.isCheckForProfileUpdates() &&
                isProfileUpdated(pkg, sharedGid, compilerFilter);

            // Get the dexopt flags after getRealCompilerFilter to make sure we get the correct
            // flags.
            final int dexoptFlags = getDexFlags(pkg, compilerFilter, options.isBootComplete());

            for (String dexCodeIsa : dexCodeInstructionSets) {
                int newResult = dexOptPath(pkg, path, dexCodeIsa, compilerFilter,
                        profileUpdated, classLoaderContexts[i], dexoptFlags, sharedGid,
                        packageStats, options.isDowngrade());
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
    private int dexOptPath(PackageParser.Package pkg, String path, String isa,
            String compilerFilter, boolean profileUpdated, String sharedLibrariesPath,
            int dexoptFlags, int uid, CompilerStats.PackageStats packageStats, boolean downgrade) {
        int dexoptNeeded = getDexoptNeeded(path, isa, compilerFilter, profileUpdated, downgrade);
        if (Math.abs(dexoptNeeded) == DexFile.NO_DEXOPT_NEEDED) {
            return DEX_OPT_SKIPPED;
        }

        // TODO(calin): there's no need to try to create the oat dir over and over again,
        //              especially since it involve an extra installd call. We should create
        //              if (if supported) on the fly during the dexopt call.
        String oatDir = createOatDirIfSupported(pkg, isa);

        Log.i(TAG, "Running dexopt (dexoptNeeded=" + dexoptNeeded + ") on: " + path
                + " pkg=" + pkg.applicationInfo.packageName + " isa=" + isa
                + " dexoptFlags=" + printDexoptFlags(dexoptFlags)
                + " target-filter=" + compilerFilter + " oatDir=" + oatDir
                + " sharedLibraries=" + sharedLibrariesPath);

        try {
            long startTime = System.currentTimeMillis();

            // TODO: Consider adding 2 different APIs for primary and secondary dexopt.
            // installd only uses downgrade flag for secondary dex files and ignores it for
            // primary dex files.
            mInstaller.dexopt(path, uid, pkg.packageName, isa, dexoptNeeded, oatDir, dexoptFlags,
                    compilerFilter, pkg.volumeUuid, sharedLibrariesPath, pkg.applicationInfo.seInfo,
                    false /* downgrade*/);

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
        int dexoptFlags = getDexFlags(info, compilerFilter, /* bootComplete */ true)
                | DEXOPT_SECONDARY_DEX;
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
        Log.d(TAG, "Running dexopt on: " + path
                + " pkg=" + info.packageName + " isa=" + dexUseInfo.getLoaderIsas()
                + " dexoptFlags=" + printDexoptFlags(dexoptFlags)
                + " target-filter=" + compilerFilter);

        // TODO(calin): b/64530081 b/66984396. Use SKIP_SHARED_LIBRARY_CHECK for the context
        // (instead of dexUseInfo.getClassLoaderContext()) in order to compile secondary dex files
        // in isolation (and avoid to extract/verify the main apk if it's in the class path).
        // Note this trades correctness for performance since the resulting slow down is
        // unacceptable in some cases until b/64530081 is fixed.
        String classLoaderContext = SKIP_SHARED_LIBRARY_CHECK;

        try {
            for (String isa : dexUseInfo.getLoaderIsas()) {
                // Reuse the same dexopt path as for the primary apks. We don't need all the
                // arguments as some (dexopNeeded and oatDir) will be computed by installd because
                // system server cannot read untrusted app content.
                // TODO(calin): maybe add a separate call.
                mInstaller.dexopt(path, info.uid, info.packageName, isa, /*dexoptNeeded*/ 0,
                        /*oatDir*/ null, dexoptFlags,
                        compilerFilter, info.volumeUuid, classLoaderContext, info.seInfoUser,
                        options.isDowngrade());
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
    void dumpDexoptState(IndentingPrintWriter pw, PackageParser.Package pkg,
            PackageDexUsage.PackageUseInfo useInfo) {
        final String[] instructionSets = getAppDexInstructionSets(pkg.applicationInfo);
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);

        final List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();

        for (String path : paths) {
            pw.println("path: " + path);
            pw.increaseIndent();

            for (String isa : dexCodeInstructionSets) {
                String status = null;
                try {
                    status = DexFile.getDexFileStatus(path, isa);
                } catch (IOException ioe) {
                     status = "[Exception]: " + ioe.getMessage();
                }
                pw.println(isa + ": " + status);
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
        int flags = info.flags;
        boolean vmSafeMode = (flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0;
        if (vmSafeMode) {
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
     * Computes the dex flags that needs to be pass to installd for the given package and compiler
     * filter.
     */
    private int getDexFlags(PackageParser.Package pkg, String compilerFilter,
            boolean bootComplete) {
        return getDexFlags(pkg.applicationInfo, compilerFilter, bootComplete);
    }

    private int getDexFlags(ApplicationInfo info, String compilerFilter, boolean bootComplete) {
        int flags = info.flags;
        boolean debuggable = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        // Profile guide compiled oat files should not be public.
        boolean isProfileGuidedFilter = isProfileGuidedCompilerFilter(compilerFilter);
        boolean isPublic = !info.isForwardLocked() && !isProfileGuidedFilter;
        int profileFlag = isProfileGuidedFilter ? DEXOPT_PROFILE_GUIDED : 0;
        int dexFlags =
                (isPublic ? DEXOPT_PUBLIC : 0)
                | (debuggable ? DEXOPT_DEBUGGABLE : 0)
                | profileFlag
                | (bootComplete ? DEXOPT_BOOTCOMPLETE : 0);
        return adjustDexoptFlags(dexFlags);
    }

    /**
     * Assesses if there's a need to perform dexopt on {@code path} for the given
     * configuration (isa, compiler filter, profile).
     */
    private int getDexoptNeeded(String path, String isa, String compilerFilter,
            boolean newProfile, boolean downgrade) {
        int dexoptNeeded;
        try {
            dexoptNeeded = DexFile.getDexOptNeeded(path, isa, compilerFilter, newProfile,
                    downgrade);
        } catch (IOException ioe) {
            Slog.w(TAG, "IOException reading apk: " + path, ioe);
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
    private boolean isProfileUpdated(PackageParser.Package pkg, int uid, String compilerFilter) {
        // Check if we are allowed to merge and if the compiler filter is profile guided.
        if (!isProfileGuidedCompilerFilter(compilerFilter)) {
            return false;
        }
        // Merge profiles. It returns whether or not there was an updated in the profile info.
        try {
            return mInstaller.mergeProfiles(uid, pkg.packageName);
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to merge profiles", e);
        }
        return false;
    }

    /**
     * Creates oat dir for the specified package if needed and supported.
     * In certain cases oat directory
     * <strong>cannot</strong> be created:
     * <ul>
     *      <li>{@code pkg} is a system app, which is not updated.</li>
     *      <li>Package location is not a directory, i.e. monolithic install.</li>
     * </ul>
     *
     * @return Absolute path to the oat directory or null, if oat directory
     * cannot be created.
     */
    @Nullable
    private String createOatDirIfSupported(PackageParser.Package pkg, String dexInstructionSet) {
        if (!pkg.canHaveOatDir()) {
            return null;
        }
        File codePath = new File(pkg.codePath);
        if (codePath.isDirectory()) {
            // TODO(calin): why do we create this only if the codePath is a directory? (i.e for
            //              cluster packages). It seems that the logic for the folder creation is
            //              split between installd and here.
            File oatDir = getOatDir(codePath);
            try {
                mInstaller.createOatDir(oatDir.getAbsolutePath(), dexInstructionSet);
            } catch (InstallerException e) {
                Slog.w(TAG, "Failed to create oat dir", e);
                return null;
            }
            return oatDir.getAbsolutePath();
        }
        return null;
    }

    static File getOatDir(File codePath) {
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
