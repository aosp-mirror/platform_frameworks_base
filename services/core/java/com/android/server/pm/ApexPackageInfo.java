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

import static com.android.server.pm.ApexManager.MATCH_ACTIVE_PACKAGE;
import static com.android.server.pm.ApexManager.MATCH_FACTORY_PACKAGE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.ArrayMap;
import android.util.PrintWriterPrinter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.pkg.parsing.PackageInfoWithoutStateUtils;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * A temporary holder to store PackageInfo for scanned apex packages. We will unify the scan/install
 * flows of APK and APEX and PMS will be the only source of truth for all package information
 * including both APK and APEX. This class will no longer be needed when the migration is done.
 */
class ApexPackageInfo {
    private static final String TAG = "ApexManager";
    private static final String VNDK_APEX_MODULE_NAME_PREFIX = "com.android.vndk.";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private List<PackageInfo> mAllPackagesCache;

    /**
     * Whether an APEX package is active or not.
     *
     * @param packageInfo the package to check
     * @return {@code true} if this package is active, {@code false} otherwise.
     */
    private static boolean isActive(PackageInfo packageInfo) {
        return packageInfo.isActiveApex;
    }

    /**
     * Called by package manager service to scan apex package files when device boots up.
     *
     * @param allPackages All apex packages to scan.
     * @param packageParser The package parser to support apex package parsing and caching parsed
     *                      results.
     * @param executorService An executor to support parallel package parsing.
     */
    List<ApexManager.ScanResult> scanApexPackages(ApexInfo[] allPackages,
            @NonNull PackageParser2 packageParser, @NonNull ExecutorService executorService) {
        synchronized (mLock) {
            return scanApexPackagesInternalLocked(allPackages, packageParser, executorService);
        }
    }

    /**
     * Retrieves information about an APEX package.
     *
     * @param packageName the package name to look for. Note that this is the package name reported
     *                    in the APK container manifest (i.e. AndroidManifest.xml), which might
     *                    differ from the one reported in the APEX manifest (i.e.
     *                    apex_manifest.json).
     * @param flags the type of package to return. This may match to active packages
     *              and factory (pre-installed) packages.
     * @return a PackageInfo object with the information about the package, or null if the package
     *         is not found.
     */
    @Nullable
    PackageInfo getPackageInfo(String packageName, @ApexManager.PackageInfoFlags int flags) {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            boolean matchActive = (flags & MATCH_ACTIVE_PACKAGE) != 0;
            boolean matchFactory = (flags & MATCH_FACTORY_PACKAGE) != 0;
            for (int i = 0, size = mAllPackagesCache.size(); i < size; i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (!packageInfo.packageName.equals(packageName)) {
                    continue;
                }
                if ((matchActive && isActive(packageInfo))
                        || (matchFactory && isFactory(packageInfo))) {
                    return packageInfo;
                }
            }
            return null;
        }
    }

    /**
     * Retrieves information about all active APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         active package.
     */
    List<PackageInfo> getActivePackages() {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<PackageInfo> activePackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (isActive(packageInfo)) {
                    activePackages.add(packageInfo);
                }
            }
            return activePackages;
        }
    }

    /**
     * Retrieves information about all active pre-installed APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         active pre-installed package.
     */
    List<PackageInfo> getFactoryPackages() {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<PackageInfo> factoryPackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (isFactory(packageInfo)) {
                    factoryPackages.add(packageInfo);
                }
            }
            return factoryPackages;
        }
    }

    /**
     * Retrieves information about all inactive APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         inactive package.
     */
    List<PackageInfo> getInactivePackages() {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<PackageInfo> inactivePackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (!isActive(packageInfo)) {
                    inactivePackages.add(packageInfo);
                }
            }
            return inactivePackages;
        }
    }

    /**
     * Checks if {@code packageName} is an apex package.
     *
     * @param packageName package to check.
     * @return {@code true} if {@code packageName} is an apex package.
     */
    boolean isApexPackage(String packageName) {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            for (int i = 0, size = mAllPackagesCache.size(); i < size; i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (packageInfo.packageName.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Called by ApexManager to update cached PackageInfo when installing rebootless APEX.
     */
    void notifyPackageInstalled(PackageInfo oldApexPkg, PackageInfo newApexPkg) {
        synchronized (mLock) {
            for (int i = 0, size = mAllPackagesCache.size(); i < size; i++) {
                if (mAllPackagesCache.get(i).equals(oldApexPkg)) {
                    if (isFactory(oldApexPkg)) {
                        oldApexPkg.isActiveApex = false;
                        mAllPackagesCache.add(newApexPkg);
                    } else {
                        mAllPackagesCache.set(i, newApexPkg);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Whether the APEX package is pre-installed or not.
     *
     * @param packageInfo the package to check
     * @return {@code true} if this package is pre-installed, {@code false} otherwise.
     */
    private static boolean isFactory(@NonNull PackageInfo packageInfo) {
        return (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    /**
     * Dumps various state information to the provided {@link PrintWriter} object.
     *
     * @param pw the {@link PrintWriter} object to send information to.
     * @param packageName a {@link String} containing a package name, or {@code null}. If set, only
     *                    information about that specific package will be dumped.
     */
    void dump(PrintWriter pw, @Nullable String packageName) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
        synchronized (mLock) {
            if (mAllPackagesCache == null) {
                ipw.println("APEX packages have not been scanned");
                return;
            }
        }
        ipw.println("Active APEX packages:");
        dumpFromPackagesCache(getActivePackages(), packageName, ipw);
        ipw.println("Inactive APEX packages:");
        dumpFromPackagesCache(getInactivePackages(), packageName, ipw);
        ipw.println("Factory APEX packages:");
        dumpFromPackagesCache(getFactoryPackages(), packageName, ipw);
    }

    @GuardedBy("mLock")
    private void notifyScanResultLocked(List<ApexManager.ScanResult> scanResults) {
        mAllPackagesCache = new ArrayList<>();
        final int flags = PackageManager.GET_META_DATA
                | PackageManager.GET_SIGNING_CERTIFICATES
                | PackageManager.GET_SIGNATURES;

        HashSet<String> activePackagesSet = new HashSet<>();
        HashSet<String> factoryPackagesSet = new HashSet<>();
        for (ApexManager.ScanResult result : scanResults) {
            ApexInfo ai = result.apexInfo;

            final PackageInfo packageInfo = PackageInfoWithoutStateUtils.generate(
                    result.parsedPackage, ai, flags);
            if (packageInfo == null) {
                throw new IllegalStateException("Unable to generate package info: "
                        + ai.modulePath);
            }
            if (!packageInfo.packageName.equals(result.packageName)) {
                throw new IllegalStateException("Unmatched package name: "
                        + result.packageName + " != " + packageInfo.packageName
                        + ", path=" + ai.modulePath);
            }
            mAllPackagesCache.add(packageInfo);
            if (ai.isActive) {
                if (!activePackagesSet.add(packageInfo.packageName)) {
                    throw new IllegalStateException(
                            "Two active packages have the same name: "
                                    + packageInfo.packageName);
                }
            }
            if (ai.isFactory) {
                // Don't throw when the duplicating APEX is VNDK APEX
                if (!factoryPackagesSet.add(packageInfo.packageName)
                        && !ai.moduleName.startsWith(VNDK_APEX_MODULE_NAME_PREFIX)) {
                    throw new IllegalStateException(
                            "Two factory packages have the same name: "
                                    + packageInfo.packageName);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private List<ApexManager.ScanResult> scanApexPackagesInternalLocked(final ApexInfo[] allPkgs,
            PackageParser2 packageParser, ExecutorService executorService) {
        if (allPkgs == null || allPkgs.length == 0) {
            notifyScanResultLocked(Collections.EMPTY_LIST);
            return Collections.EMPTY_LIST;
        }

        ArrayMap<File, ApexInfo> parsingApexInfo = new ArrayMap<>();
        ParallelPackageParser parallelPackageParser =
                new ParallelPackageParser(packageParser, executorService);
        for (ApexInfo ai : allPkgs) {
            File apexFile = new File(ai.modulePath);
            parallelPackageParser.submit(apexFile,
                    ParsingPackageUtils.PARSE_COLLECT_CERTIFICATES);
            parsingApexInfo.put(apexFile, ai);
        }

        List<ApexManager.ScanResult> results = new ArrayList<>(parsingApexInfo.size());
        // Process results one by one
        for (int i = 0; i < parsingApexInfo.size(); i++) {
            ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
            Throwable throwable = parseResult.throwable;
            ApexInfo ai = parsingApexInfo.get(parseResult.scanFile);

            if (throwable == null) {
                // Calling hideAsFinal to assign derived fields for the app info flags.
                parseResult.parsedPackage.hideAsFinal();
                results.add(new ApexManager.ScanResult(
                        ai, parseResult.parsedPackage, parseResult.parsedPackage.getPackageName()));
            } else if (throwable instanceof PackageManagerException) {
                final PackageManagerException e = (PackageManagerException) throwable;
                // Skip parsing non-coreApp apex file if system is in minimal boot state.
                if (e.error == PackageManager.INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED) {
                    Slog.w(TAG, "Scan apex failed, not a coreApp:" + ai.modulePath);
                    continue;
                }
                throw new IllegalStateException("Unable to parse: " + ai.modulePath, throwable);
            } else {
                throw new IllegalStateException("Unexpected exception occurred while parsing "
                        + ai.modulePath, throwable);
            }
        }

        notifyScanResultLocked(results);
        return results;
    }

    /**
     * Dump information about the packages contained in a particular cache
     * @param packagesCache the cache to print information about.
     * @param packageName a {@link String} containing a package name, or {@code null}. If set,
     *                    only information about that specific package will be dumped.
     * @param ipw the {@link IndentingPrintWriter} object to send information to.
     */
    private static void dumpFromPackagesCache(List<PackageInfo> packagesCache,
            @Nullable String packageName, IndentingPrintWriter ipw) {
        ipw.println();
        ipw.increaseIndent();
        for (int i = 0, size = packagesCache.size(); i < size; i++) {
            final PackageInfo pi = packagesCache.get(i);
            if (packageName != null && !packageName.equals(pi.packageName)) {
                continue;
            }
            ipw.println(pi.packageName);
            ipw.increaseIndent();
            ipw.println("Version: " + pi.versionCode);
            ipw.println("Path: " + pi.applicationInfo.sourceDir);
            ipw.println("IsActive: " + isActive(pi));
            ipw.println("IsFactory: " + isFactory(pi));
            ipw.println("ApplicationInfo: ");
            ipw.increaseIndent();
            pi.applicationInfo.dump(new PrintWriterPrinter(ipw), "");
            ipw.decreaseIndent();
            ipw.decreaseIndent();
        }
        ipw.decreaseIndent();
        ipw.println();
    }
}
