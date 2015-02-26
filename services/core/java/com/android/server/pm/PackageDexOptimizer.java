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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexFile;
import dalvik.system.StaleDexCacheError;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;

/**
 * Helper class for running dexopt command on packages.
 */
final class PackageDexOptimizer {
    static final String TAG = "PackageManager.DexOptimizer";
    static final int DEX_OPT_SKIPPED = 0;
    static final int DEX_OPT_PERFORMED = 1;
    static final int DEX_OPT_DEFERRED = 2;
    static final int DEX_OPT_FAILED = -1;

    private final PackageManagerService mPackageManagerService;
    private ArraySet<PackageParser.Package> mDeferredDexOpt;

    PackageDexOptimizer(PackageManagerService packageManagerService) {
        this.mPackageManagerService = packageManagerService;
    }

    /**
     * Performs dexopt on all code paths and libraries of the specified package for specified
     * instruction sets.
     *
     * <p>Calls to {@link com.android.server.pm.Installer#dexopt} are synchronized on
     * {@link PackageManagerService#mInstallLock}.
     */
    int performDexOpt(PackageParser.Package pkg, String[] instructionSets,
            boolean forceDex, boolean defer, boolean inclDependencies) {
        ArraySet<String> done;
        if (inclDependencies && (pkg.usesLibraries != null || pkg.usesOptionalLibraries != null)) {
            done = new ArraySet<String>();
            done.add(pkg.packageName);
        } else {
            done = null;
        }
        synchronized (mPackageManagerService.mInstallLock) {
            return performDexOptLI(pkg, instructionSets, forceDex, defer, done);
        }
    }

    private int performDexOptLI(PackageParser.Package pkg, String[] targetInstructionSets,
            boolean forceDex, boolean defer, ArraySet<String> done) {
        final String[] instructionSets = targetInstructionSets != null ?
                targetInstructionSets : getAppDexInstructionSets(pkg.applicationInfo);

        if (done != null) {
            done.add(pkg.packageName);
            if (pkg.usesLibraries != null) {
                performDexOptLibsLI(pkg.usesLibraries, instructionSets, forceDex, defer, done);
            }
            if (pkg.usesOptionalLibraries != null) {
                performDexOptLibsLI(pkg.usesOptionalLibraries, instructionSets, forceDex, defer,
                        done);
            }
        }

        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) {
            return DEX_OPT_SKIPPED;
        }

        final boolean vmSafeMode = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0;

        final List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();
        boolean performedDexOpt = false;
        // There are three basic cases here:
        // 1.) we need to dexopt, either because we are forced or it is needed
        // 2.) we are deferring a needed dexopt
        // 3.) we are skipping an unneeded dexopt
        final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
        for (String dexCodeInstructionSet : dexCodeInstructionSets) {
            if (!forceDex && pkg.mDexOptPerformed.contains(dexCodeInstructionSet)) {
                continue;
            }

            for (String path : paths) {
                try {
                    // This will return DEXOPT_NEEDED if we either cannot find any odex file for this
                    // package or the one we find does not match the image checksum (i.e. it was
                    // compiled against an old image). It will return PATCHOAT_NEEDED if we can find a
                    // odex file and it matches the checksum of the image but not its base address,
                    // meaning we need to move it.
                    final byte isDexOptNeeded = DexFile.isDexOptNeededInternal(path,
                            pkg.packageName, dexCodeInstructionSet, defer);
                    if (forceDex || (!defer && isDexOptNeeded == DexFile.DEXOPT_NEEDED)) {
                        Log.i(TAG, "Running dexopt on: " + path + " pkg="
                                + pkg.applicationInfo.packageName + " isa=" + dexCodeInstructionSet
                                + " vmSafeMode=" + vmSafeMode);
                        final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
                        final int ret = mPackageManagerService.mInstaller.dexopt(path, sharedGid,
                                !pkg.isForwardLocked(), pkg.packageName, dexCodeInstructionSet,
                                vmSafeMode);

                        if (ret < 0) {
                            // Don't bother running dexopt again if we failed, it will probably
                            // just result in an error again. Also, don't bother dexopting for other
                            // paths & ISAs.
                            return DEX_OPT_FAILED;
                        }

                        performedDexOpt = true;
                    } else if (!defer && isDexOptNeeded == DexFile.PATCHOAT_NEEDED) {
                        Log.i(TAG, "Running patchoat on: " + pkg.applicationInfo.packageName);
                        final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
                        final int ret = mPackageManagerService.mInstaller.patchoat(path, sharedGid,
                                !pkg.isForwardLocked(), pkg.packageName, dexCodeInstructionSet);

                        if (ret < 0) {
                            // Don't bother running patchoat again if we failed, it will probably
                            // just result in an error again. Also, don't bother dexopting for other
                            // paths & ISAs.
                            return DEX_OPT_FAILED;
                        }

                        performedDexOpt = true;
                    }

                    // We're deciding to defer a needed dexopt. Don't bother dexopting for other
                    // paths and instruction sets. We'll deal with them all together when we process
                    // our list of deferred dexopts.
                    if (defer && isDexOptNeeded != DexFile.UP_TO_DATE) {
                        addPackageForDeferredDexopt(pkg);
                        return DEX_OPT_DEFERRED;
                    }
                } catch (FileNotFoundException e) {
                    Slog.w(TAG, "Apk not found for dexopt: " + path);
                    return DEX_OPT_FAILED;
                } catch (IOException e) {
                    Slog.w(TAG, "IOException reading apk: " + path, e);
                    return DEX_OPT_FAILED;
                } catch (StaleDexCacheError e) {
                    Slog.w(TAG, "StaleDexCacheError when reading apk: " + path, e);
                    return DEX_OPT_FAILED;
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when doing dexopt : ", e);
                    return DEX_OPT_FAILED;
                }
            }

            // At this point we haven't failed dexopt and we haven't deferred dexopt. We must
            // either have either succeeded dexopt, or have had isDexOptNeededInternal tell us
            // it isn't required. We therefore mark that this package doesn't need dexopt unless
            // it's forced. performedDexOpt will tell us whether we performed dex-opt or skipped
            // it.
            pkg.mDexOptPerformed.add(dexCodeInstructionSet);
        }

        // If we've gotten here, we're sure that no error occurred and that we haven't
        // deferred dex-opt. We've either dex-opted one more paths or instruction sets or
        // we've skipped all of them because they are up to date. In both cases this
        // package doesn't need dexopt any longer.
        return performedDexOpt ? DEX_OPT_PERFORMED : DEX_OPT_SKIPPED;
    }

    private void performDexOptLibsLI(ArrayList<String> libs, String[] instructionSets,
            boolean forceDex, boolean defer, ArraySet<String> done) {
        for (String libName : libs) {
            PackageParser.Package libPkg = mPackageManagerService.findSharedNonSystemLibrary(
                    libName);
            if (libPkg != null && !done.contains(libName)) {
                performDexOptLI(libPkg, instructionSets, forceDex, defer, done);
            }
        }
    }

    /**
     * Clears set of deferred dexopt packages.
     * @return content of dexopt set if it was not empty
     */
    public ArraySet<PackageParser.Package> clearDeferredDexOptPackages() {
        ArraySet<PackageParser.Package> result = mDeferredDexOpt;
        mDeferredDexOpt = null;
        return result;
    }

    public void addPackageForDeferredDexopt(PackageParser.Package pkg) {
        if (mDeferredDexOpt == null) {
            mDeferredDexOpt = new ArraySet<PackageParser.Package>();
        }
        mDeferredDexOpt.add(pkg);
    }
}
