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
import android.content.pm.PackageParser.Package;
import android.os.Environment;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.storage.StorageManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.InstallerConnection.InstallerException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexFile;

import static com.android.server.pm.Installer.DEXOPT_BOOTCOMPLETE;
import static com.android.server.pm.Installer.DEXOPT_DEBUGGABLE;
import static com.android.server.pm.Installer.DEXOPT_PUBLIC;
import static com.android.server.pm.Installer.DEXOPT_SAFEMODE;
import static com.android.server.pm.Installer.DEXOPT_EXTRACTONLY;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;

/**
 * Helper class for running dexopt command on packages.
 */
class PackageDexOptimizer {
    private static final String TAG = "PackageManager.DexOptimizer";
    static final String OAT_DIR_NAME = "oat";
    // TODO b/19550105 Remove error codes and use exceptions
    static final int DEX_OPT_SKIPPED = 0;
    static final int DEX_OPT_PERFORMED = 1;
    static final int DEX_OPT_DEFERRED = 2;
    static final int DEX_OPT_FAILED = -1;

    private static final boolean DEBUG_DEXOPT = PackageManagerService.DEBUG_DEXOPT;

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
    int performDexOpt(PackageParser.Package pkg, String[] instructionSets, boolean useProfiles,
            boolean extractOnly) {
        synchronized (mInstallLock) {
            final boolean useLock = mSystemReady;
            if (useLock) {
                mDexoptWakeLock.setWorkSource(new WorkSource(pkg.applicationInfo.uid));
                mDexoptWakeLock.acquire();
            }
            try {
                return performDexOptLI(pkg, instructionSets, useProfiles, extractOnly);
            } finally {
                if (useLock) {
                    mDexoptWakeLock.release();
                }
            }
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

    private int performDexOptLI(PackageParser.Package pkg, String[] targetInstructionSets,
            boolean useProfiles, boolean extractOnly) {
        final String[] instructionSets = targetInstructionSets != null ?
                targetInstructionSets : getAppDexInstructionSets(pkg.applicationInfo);

        if (!canOptimizePackage(pkg)) {
            return DEX_OPT_SKIPPED;
        }

        final boolean vmSafeMode = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0;
        final boolean debuggable = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        final List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();
        boolean performedDexOpt = false;
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
        for (String dexCodeInstructionSet : dexCodeInstructionSets) {
            for (String path : paths) {
                if (useProfiles && isUsedByOtherApps(path)) {
                    // We cannot use profile guided compilation if the apk was used by another app.
                    useProfiles = false;
                }
                int dexoptNeeded;

                try {
                    int compilationTypeMask = 0;
                    if (extractOnly) {
                        // For extract only, any type of compilation is good.
                        compilationTypeMask = DexFile.COMPILATION_TYPE_FULL
                            | DexFile.COMPILATION_TYPE_PROFILE_GUIDE
                            | DexFile.COMPILATION_TYPE_EXTRACT_ONLY;
                    } else {
                        // Branch taken for profile guide and full compilation.
                        // Profile guide compilation should only recompile a previous
                        // profile compiled/extract only file and should not be attempted if the
                        // apk is already fully compiled. So test against a full compilation type.
                        compilationTypeMask = DexFile.COMPILATION_TYPE_FULL;
                    }
                    dexoptNeeded = DexFile.getDexOptNeeded(path,
                            dexCodeInstructionSet, compilationTypeMask);
                } catch (IOException ioe) {
                    Slog.w(TAG, "IOException reading apk: " + path, ioe);
                    return DEX_OPT_FAILED;
                }
                dexoptNeeded = adjustDexoptNeeded(dexoptNeeded);

                final String dexoptType;
                String oatDir = null;
                switch (dexoptNeeded) {
                    case DexFile.NO_DEXOPT_NEEDED:
                        continue;
                    case DexFile.DEX2OAT_NEEDED:
                        dexoptType = "dex2oat";
                        oatDir = createOatDirIfSupported(pkg, dexCodeInstructionSet);
                        break;
                    case DexFile.PATCHOAT_NEEDED:
                        dexoptType = "patchoat";
                        break;
                    case DexFile.SELF_PATCHOAT_NEEDED:
                        dexoptType = "self patchoat";
                        break;
                    default:
                        throw new IllegalStateException("Invalid dexopt:" + dexoptNeeded);
                }


                Log.i(TAG, "Running dexopt (" + dexoptType + ") on: " + path + " pkg="
                        + pkg.applicationInfo.packageName + " isa=" + dexCodeInstructionSet
                        + " vmSafeMode=" + vmSafeMode + " debuggable=" + debuggable
                        + " extractOnly=" + extractOnly + " oatDir = " + oatDir);
                final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
                // Profile guide compiled oat files should not be public.
                final boolean isPublic = !pkg.isForwardLocked() && !useProfiles;
                final int dexFlags = adjustDexoptFlags(
                        ( isPublic ? DEXOPT_PUBLIC : 0)
                        | (vmSafeMode ? DEXOPT_SAFEMODE : 0)
                        | (debuggable ? DEXOPT_DEBUGGABLE : 0)
                        | (extractOnly ? DEXOPT_EXTRACTONLY : 0)
                        | DEXOPT_BOOTCOMPLETE);

                try {
                    mInstaller.dexopt(path, sharedGid, pkg.packageName, dexCodeInstructionSet,
                            dexoptNeeded, oatDir, dexFlags, pkg.volumeUuid, useProfiles);
                    performedDexOpt = true;
                } catch (InstallerException e) {
                    Slog.w(TAG, "Failed to dexopt", e);
                }
            }
        }

        // If we've gotten here, we're sure that no error occurred and that we haven't
        // deferred dex-opt. We've either dex-opted one more paths or instruction sets or
        // we've skipped all of them because they are up to date. In both cases this
        // package doesn't need dexopt any longer.
        return performedDexOpt ? DEX_OPT_PERFORMED : DEX_OPT_SKIPPED;
    }

    /**
     * Creates oat dir for the specified package. In certain cases oat directory
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

    private boolean isUsedByOtherApps(String apkPath) {
        try {
            apkPath = new File(apkPath).getCanonicalPath();
        } catch (IOException e) {
            // Log an error but continue without it.
            Slog.w(TAG, "Failed to get canonical path", e);
        }
        String useMarker = apkPath.replace('/', '@');
        final int[] currentUserIds = UserManagerService.getInstance().getUserIds();
        for (int i = 0; i < currentUserIds.length; i++) {
            File profileDir = Environment.getDataProfilesDeForeignDexDirectory(currentUserIds[i]);
            File foreignUseMark = new File(profileDir, useMarker);
            if (foreignUseMark.exists()) {
                return true;
            }
        }
        return false;
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
            return DexFile.DEX2OAT_NEEDED;
        }
    }
}
