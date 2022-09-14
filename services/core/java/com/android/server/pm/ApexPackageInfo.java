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
import android.content.pm.PackageManager;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.PrintWriterPrinter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
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
    public static final boolean ENABLE_FEATURE_SCAN_APEX = true;

    private static final String TAG = "ApexManager";
    private static final String VNDK_APEX_MODULE_NAME_PREFIX = "com.android.vndk.";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private List<Pair<ApexInfo, AndroidPackage>> mAllPackagesCache;

    @Nullable
    private final PackageManagerService mPackageManager;

    ApexPackageInfo() {
        mPackageManager = null;
    }

    ApexPackageInfo(@NonNull PackageManagerService pms) {
        mPackageManager = pms;
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

    void notifyScanResult(List<ApexManager.ScanResult> scanResults) {
        synchronized (mLock) {
            notifyScanResultLocked(scanResults);
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
    Pair<ApexInfo, AndroidPackage> getPackageInfo(String packageName,
            @ApexManager.PackageInfoFlags int flags) {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            boolean matchActive = (flags & MATCH_ACTIVE_PACKAGE) != 0;
            boolean matchFactory = (flags & MATCH_FACTORY_PACKAGE) != 0;
            for (int i = 0, size = mAllPackagesCache.size(); i < size; i++) {
                final Pair<ApexInfo, AndroidPackage> pair = mAllPackagesCache.get(i);
                var apexInfo = pair.first;
                var pkg = pair.second;
                if (!pkg.getPackageName().equals(packageName)) {
                    continue;
                }
                if ((matchActive && apexInfo.isActive)
                        || (matchFactory && apexInfo.isFactory)) {
                    return pair;
                }
            }
            return null;
        }
    }

    /**
     * Retrieves information about all active APEX packages.
     *
     * @return list containing information about different active packages.
     */
    @NonNull
    List<Pair<ApexInfo, AndroidPackage>> getActivePackages() {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<Pair<ApexInfo, AndroidPackage>> activePackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final var pair = mAllPackagesCache.get(i);
                if (pair.first.isActive) {
                    activePackages.add(pair);
                }
            }
            return activePackages;
        }
    }

    /**
     * Retrieves information about all pre-installed APEX packages.
     *
     * @return list containing information about different pre-installed packages.
     */
    @NonNull
    List<Pair<ApexInfo, AndroidPackage>> getFactoryPackages() {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<Pair<ApexInfo, AndroidPackage>> factoryPackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final var pair = mAllPackagesCache.get(i);
                if (pair.first.isFactory) {
                    factoryPackages.add(pair);
                }
            }
            return factoryPackages;
        }
    }

    /**
     * Retrieves information about all inactive APEX packages.
     *
     * @return list containing information about different inactive packages.
     */
    @NonNull
    List<Pair<ApexInfo, AndroidPackage>> getInactivePackages() {
        synchronized (mLock) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<Pair<ApexInfo, AndroidPackage>> inactivePackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final var pair = mAllPackagesCache.get(i);
                if (!pair.first.isActive) {
                    inactivePackages.add(pair);
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
                final var pair = mAllPackagesCache.get(i);
                if (pair.second.getPackageName().equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Called to update cached PackageInfo when installing rebootless APEX.
     */
    void notifyPackageInstalled(ApexInfo apexInfo, PackageParser2 packageParser)
            throws PackageManagerException {
        final int flags = PackageManager.GET_META_DATA
                | PackageManager.GET_SIGNING_CERTIFICATES
                | PackageManager.GET_SIGNATURES;
        final ParsedPackage parsedPackage = packageParser.parsePackage(
                new File(apexInfo.modulePath), flags, /* useCaches= */ false);
        notifyPackageInstalled(apexInfo, parsedPackage.hideAsFinal());
    }

    void notifyPackageInstalled(ApexInfo apexInfo, AndroidPackage pkg) {
        final String packageName = pkg.getPackageName();
        synchronized (mLock) {
            for (int i = 0, size = mAllPackagesCache.size(); i < size; i++) {
                var pair = mAllPackagesCache.get(i);
                var oldApexInfo = pair.first;
                var oldApexPkg = pair.second;
                if (oldApexInfo.isActive && oldApexPkg.getPackageName().equals(packageName)) {
                    if (oldApexInfo.isFactory) {
                        oldApexInfo.isActive = false;
                        mAllPackagesCache.add(Pair.create(apexInfo, pkg));
                    } else {
                        mAllPackagesCache.set(i, Pair.create(apexInfo, pkg));
                    }
                    break;
                }
            }
        }
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
        dumpPackages(getActivePackages(), packageName, ipw);
        ipw.println("Inactive APEX packages:");
        dumpPackages(getInactivePackages(), packageName, ipw);
        ipw.println("Factory APEX packages:");
        dumpPackages(getFactoryPackages(), packageName, ipw);
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
            String packageName = result.pkg.getPackageName();
            if (!packageName.equals(result.packageName)) {
                throw new IllegalStateException("Unmatched package name: "
                        + result.packageName + " != " + packageName
                        + ", path=" + ai.modulePath);
            }
            mAllPackagesCache.add(Pair.create(ai, result.pkg));
            if (ai.isActive) {
                if (!activePackagesSet.add(packageName)) {
                    throw new IllegalStateException(
                            "Two active packages have the same name: " + packageName);
                }
            }
            if (ai.isFactory) {
                // Don't throw when the duplicating APEX is VNDK APEX
                if (!factoryPackagesSet.add(packageName)
                        && !ai.moduleName.startsWith(VNDK_APEX_MODULE_NAME_PREFIX)) {
                    throw new IllegalStateException(
                            "Two factory packages have the same name: " + packageName);
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

                // TODO: When ENABLE_FEATURE_SCAN_APEX is finalized, remove this and the entire
                //  calling path code
                ScanPackageUtils.applyPolicy(parseResult.parsedPackage,
                        PackageManagerService.SCAN_AS_SYSTEM,
                        mPackageManager == null ? null : mPackageManager.getPlatformPackage(),
                        false);
                results.add(new ApexManager.ScanResult(
                        ai, parseResult.parsedPackage, parseResult.parsedPackage.getPackageName()));
            } else if (throwable instanceof PackageManagerException) {
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
     * @see #dumpPackages(List, String, IndentingPrintWriter)
     */
    static void dumpPackageStates(List<PackageStateInternal> packageStates, boolean isActive,
            @Nullable String packageName, IndentingPrintWriter ipw) {
        ipw.println();
        ipw.increaseIndent();
        for (int i = 0, size = packageStates.size(); i < size; i++) {
            final var packageState = packageStates.get(i);
            var pkg = packageState.getPkg();
            if (packageName != null && !packageName.equals(pkg.getPackageName())) {
                continue;
            }
            ipw.println(pkg.getPackageName());
            ipw.increaseIndent();
            ipw.println("Version: " + pkg.getLongVersionCode());
            ipw.println("Path: " + pkg.getBaseApkPath());
            ipw.println("IsActive: " + isActive);
            ipw.println("IsFactory: " + !packageState.isUpdatedSystemApp());
            ipw.println("ApplicationInfo: ");
            ipw.increaseIndent();
            // TODO: Dump the package manually
            AndroidPackageUtils.generateAppInfoWithoutState(pkg)
                    .dump(new PrintWriterPrinter(ipw), "");
            ipw.decreaseIndent();
            ipw.decreaseIndent();
        }
        ipw.decreaseIndent();
        ipw.println();
    }

    /**
     * Dump information about the packages contained in a particular cache
     * @param packagesCache the cache to print information about.
     * @param packageName a {@link String} containing a package name, or {@code null}. If set,
     *                    only information about that specific package will be dumped.
     * @param ipw the {@link IndentingPrintWriter} object to send information to.
     */
    static void dumpPackages(List<Pair<ApexInfo, AndroidPackage>> packagesCache,
            @Nullable String packageName, IndentingPrintWriter ipw) {
        ipw.println();
        ipw.increaseIndent();
        for (int i = 0, size = packagesCache.size(); i < size; i++) {
            final var pair = packagesCache.get(i);
            var apexInfo = pair.first;
            var pkg = pair.second;
            if (packageName != null && !packageName.equals(pkg.getPackageName())) {
                continue;
            }
            ipw.println(pkg.getPackageName());
            ipw.increaseIndent();
            ipw.println("Version: " + pkg.getLongVersionCode());
            ipw.println("Path: " + pkg.getBaseApkPath());
            ipw.println("IsActive: " + apexInfo.isActive);
            ipw.println("IsFactory: " + apexInfo.isFactory);
            ipw.println("ApplicationInfo: ");
            ipw.increaseIndent();
            // TODO: Dump the package manually
            AndroidPackageUtils.generateAppInfoWithoutState(pkg)
                    .dump(new PrintWriterPrinter(ipw), "");
            ipw.decreaseIndent();
            ipw.decreaseIndent();
        }
        ipw.decreaseIndent();
        ipw.println();
    }
}