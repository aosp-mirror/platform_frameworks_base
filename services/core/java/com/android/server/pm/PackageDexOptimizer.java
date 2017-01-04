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
import android.os.Environment;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.pm.Installer.InstallerException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexFile;

import static com.android.server.pm.Installer.DEXOPT_BOOTCOMPLETE;
import static com.android.server.pm.Installer.DEXOPT_DEBUGGABLE;
import static com.android.server.pm.Installer.DEXOPT_PROFILE_GUIDED;
import static com.android.server.pm.Installer.DEXOPT_PUBLIC;
import static com.android.server.pm.Installer.DEXOPT_SAFEMODE;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;

import static com.android.server.pm.PackageManagerServiceCompilerMapping.getNonProfileGuidedCompilerFilter;
import static dalvik.system.DexFile.isProfileGuidedCompilerFilter;

/**
 * Helper class for running dexopt command on packages.
 */
class PackageDexOptimizer {
    private static final String TAG = "PackageManager.DexOptimizer";
    static final String OAT_DIR_NAME = "oat";
    // TODO b/19550105 Remove error codes and use exceptions
    static final int DEX_OPT_SKIPPED = 0;
    static final int DEX_OPT_PERFORMED = 1;
    static final int DEX_OPT_FAILED = -1;

    private final Installer mInstaller;
    private final Object mInstallLock;

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
            String[] instructionSets, boolean checkProfiles, String targetCompilationFilter,
            CompilerStats.PackageStats packageStats) {
        if (!canOptimizePackage(pkg)) {
            return DEX_OPT_SKIPPED;
        }
        synchronized (mInstallLock) {
            final boolean useLock = mSystemReady;
            if (useLock) {
                mDexoptWakeLock.setWorkSource(new WorkSource(pkg.applicationInfo.uid));
                mDexoptWakeLock.acquire();
            }
            try {
                return performDexOptLI(pkg, sharedLibraries, instructionSets, checkProfiles,
                        targetCompilationFilter, packageStats);
            } finally {
                if (useLock) {
                    mDexoptWakeLock.release();
                }
            }
        }
    }

    /**
     * Performs dexopt on all code paths of the given package.
     * It assumes the install lock is held.
     */
    @GuardedBy("mInstallLock")
    private int performDexOptLI(PackageParser.Package pkg, String[] sharedLibraries,
            String[] targetInstructionSets, boolean checkForProfileUpdates,
            String targetCompilerFilter, CompilerStats.PackageStats packageStats) {
        final String[] instructionSets = targetInstructionSets != null ?
                targetInstructionSets : getAppDexInstructionSets(pkg.applicationInfo);
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
        final List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();
        final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);

        final String compilerFilter = getRealCompilerFilter(pkg, targetCompilerFilter);
        final boolean profileUpdated = checkForProfileUpdates &&
                isProfileUpdated(pkg, sharedGid, compilerFilter);
        // TODO(calin,jeffhao): shared library paths should be adjusted to include previous code
        // paths (b/34169257).
        final String sharedLibrariesPath = getSharedLibrariesPath(sharedLibraries);
        final int dexoptFlags = getDexFlags(pkg, compilerFilter);

        int result = DEX_OPT_SKIPPED;
        for (String path : paths) {
            for (String dexCodeIsa : dexCodeInstructionSets) {
                int newResult = dexOptPath(pkg, path, dexCodeIsa, compilerFilter, profileUpdated,
                        sharedLibrariesPath, dexoptFlags, sharedGid, packageStats);
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
            int dexoptFlags, int uid, CompilerStats.PackageStats packageStats) {
        int dexoptNeeded = getDexoptNeeded(path, isa, compilerFilter, profileUpdated);
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

            mInstaller.dexopt(path, uid, pkg.packageName, isa, dexoptNeeded, oatDir, dexoptFlags,
                    compilerFilter, pkg.volumeUuid, sharedLibrariesPath);

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
    void dumpDexoptState(IndentingPrintWriter pw, PackageParser.Package pkg) {
        final String[] instructionSets = getAppDexInstructionSets(pkg.applicationInfo);
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);

        final List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();

        for (String instructionSet : dexCodeInstructionSets) {
             pw.println("Instruction Set: " + instructionSet);
             pw.increaseIndent();
             for (String path : paths) {
                  String status = null;
                  try {
                      status = DexFile.getDexFileStatus(path, instructionSet);
                  } catch (IOException ioe) {
                      status = "[Exception]: " + ioe.getMessage();
                  }
                  pw.println("path: " + path);
                  pw.println("status: " + status);
             }
             pw.decreaseIndent();
        }
    }

    /**
     * Returns the compiler filter that should be used to optimize the package code.
     * The target filter will be updated if the package code is used by other apps
     * or if it has the safe mode flag set.
     */
    private String getRealCompilerFilter(PackageParser.Package pkg, String targetCompilerFilter) {
        int flags = pkg.applicationInfo.flags;
        boolean vmSafeMode = (flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0;
        if (vmSafeMode) {
            // For the compilation, it doesn't really matter what we return here because installd
            // will replace the filter with interpret-only anyway.
            // However, we return a non profile guided filter so that we simplify the logic of
            // merging profiles.
            // TODO(calin): safe mode path could be simplified if we pass interpret-only from
            //              here rather than letting installd decide on the filter.
            return getNonProfileGuidedCompilerFilter(targetCompilerFilter);
        }

        if (isProfileGuidedCompilerFilter(targetCompilerFilter) && isUsedByOtherApps(pkg)) {
            // If the dex files is used by other apps, we cannot use profile-guided compilation.
            return getNonProfileGuidedCompilerFilter(targetCompilerFilter);
        }

        return targetCompilerFilter;
    }

    /**
     * Computes the dex flags that needs to be pass to installd for the given package and compiler
     * filter.
     */
    private int getDexFlags(PackageParser.Package pkg, String compilerFilter) {
        int flags = pkg.applicationInfo.flags;
        boolean vmSafeMode = (flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0;
        boolean debuggable = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        // Profile guide compiled oat files should not be public.
        boolean isProfileGuidedFilter = isProfileGuidedCompilerFilter(compilerFilter);
        boolean isPublic = !pkg.isForwardLocked() && !isProfileGuidedFilter;
        int profileFlag = isProfileGuidedFilter ? DEXOPT_PROFILE_GUIDED : 0;
        int dexFlags =
                (isPublic ? DEXOPT_PUBLIC : 0)
                | (vmSafeMode ? DEXOPT_SAFEMODE : 0)
                | (debuggable ? DEXOPT_DEBUGGABLE : 0)
                | profileFlag
                | DEXOPT_BOOTCOMPLETE;
        return adjustDexoptFlags(dexFlags);
    }

    /**
     * Assesses if there's a need to perform dexopt on {@code path} for the given
     * configuration (isa, compiler filter, profile).
     */
    private int getDexoptNeeded(String path, String isa, String compilerFilter,
            boolean newProfile) {
        int dexoptNeeded;
        try {
            dexoptNeeded = DexFile.getDexOptNeeded(path, isa, compilerFilter, newProfile);
        } catch (IOException ioe) {
            Slog.w(TAG, "IOException reading apk: " + path, ioe);
            return DEX_OPT_FAILED;
        }
        return adjustDexoptNeeded(dexoptNeeded);
    }

    /**
     * Computes the shared libraries path that should be passed to dexopt.
     */
    private String getSharedLibrariesPath(String[] sharedLibraries) {
        if (sharedLibraries == null || sharedLibraries.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String lib : sharedLibraries) {
            if (sb.length() != 0) {
                sb.append(":");
            }
            sb.append(lib);
        }
        return sb.toString();
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

    /**
     * Returns true if the profiling data collected for the given app indicate
     * that the apps's APK has been loaded by another app.
     * Note that this returns false for all forward-locked apps and apps without
     * any collected profiling data.
     */
    public static boolean isUsedByOtherApps(PackageParser.Package pkg) {
        if (pkg.isForwardLocked()) {
            // Skip the check for forward locked packages since they don't share their code.
            return false;
        }

        for (String apkPath : pkg.getAllCodePathsExcludingResourceOnly()) {
            try {
                apkPath = PackageManagerServiceUtils.realpath(new File(apkPath));
            } catch (IOException e) {
                // Log an error but continue without it.
                Slog.w(TAG, "Failed to get canonical path", e);
                continue;
            }
            String useMarker = apkPath.replace('/', '@');
            final int[] currentUserIds = UserManagerService.getInstance().getUserIds();
            for (int i = 0; i < currentUserIds.length; i++) {
                File profileDir =
                        Environment.getDataProfilesDeForeignDexDirectory(currentUserIds[i]);
                File foreignUseMark = new File(profileDir, useMarker);
                if (foreignUseMark.exists()) {
                    return true;
                }
            }
        }
        return false;
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
        if ((flags & DEXOPT_SAFEMODE) == DEXOPT_SAFEMODE) {
            flagsList.add("safemode");
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
            // Ensure compilation, no matter the current state.
            // TODO: The return value is wrong when patchoat is needed.
            return DexFile.DEX2OAT_FROM_SCRATCH;
        }
    }
}
