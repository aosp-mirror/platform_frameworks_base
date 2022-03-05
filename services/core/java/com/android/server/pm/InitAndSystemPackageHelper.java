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

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_DATA_APP_AVG_SCAN_TIME;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_SYSTEM_APP_AVG_SCAN_TIME;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APK_IN_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_FACTORY;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRIVILEGED;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE;
import static com.android.server.pm.PackageManagerService.SCAN_INITIAL;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_REQUIRE_KNOWN;
import static com.android.server.pm.PackageManagerService.SYSTEM_PARTITIONS;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.pkg.parsing.ParsingPackageUtils.PARSE_FRAMEWORK_RES_SPLITS;

import android.annotation.Nullable;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.utils.WatchedArrayMap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Part of PackageManagerService that handles init and system packages. This class still needs
 * further cleanup and eventually all the installation/scanning related logic will go to another
 * class.
 */
final class InitAndSystemPackageHelper {
    private final PackageManagerService mPm;

    private final List<ScanPartition> mDirsToScanAsSystem;
    private final int mScanFlags;
    private final int mSystemParseFlags;
    private final int mSystemScanFlags;
    private final InstallPackageHelper mInstallPackageHelper;

    /**
     * Tracks new system packages [received in an OTA] that we expect to
     * find updated user-installed versions. Keys are package name, values
     * are package location.
     */
    private final ArrayMap<String, File> mExpectingBetter = new ArrayMap<>();

    // TODO(b/198166813): remove PMS dependency
    InitAndSystemPackageHelper(PackageManagerService pm) {
        mPm = pm;
        mInstallPackageHelper = new InstallPackageHelper(pm);
        mDirsToScanAsSystem = getSystemScanPartitions();
        // Set flag to monitor and not change apk file paths when scanning install directories.
        int scanFlags = SCAN_BOOTING | SCAN_INITIAL;
        if (mPm.isDeviceUpgrading() || mPm.isFirstBoot()) {
            mScanFlags = scanFlags | SCAN_FIRST_BOOT_OR_UPGRADE;
        } else {
            mScanFlags = scanFlags;
        }
        mSystemParseFlags = mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
        mSystemScanFlags = scanFlags | SCAN_AS_SYSTEM;
    }

    private List<File> getFrameworkResApkSplitFiles() {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanFrameworkResApkSplits");
        try {
            final List<File> splits = new ArrayList<>();
            final List<ApexManager.ActiveApexInfo> activeApexInfos =
                    mPm.mApexManager.getActiveApexInfos();
            for (int i = 0; i < activeApexInfos.size(); i++) {
                ApexManager.ActiveApexInfo apexInfo = activeApexInfos.get(i);
                File splitsFolder = new File(apexInfo.apexDirectory, "etc/splits");
                if (splitsFolder.isDirectory()) {
                    for (File file : splitsFolder.listFiles()) {
                        if (ApkLiteParseUtils.isApkFile(file)) {
                            splits.add(file);
                        }
                    }
                }
            }
            return splits;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private List<ScanPartition> getSystemScanPartitions() {
        final List<ScanPartition> scanPartitions = new ArrayList<>();
        scanPartitions.addAll(mPm.mInjector.getSystemPartitions());
        scanPartitions.addAll(getApexScanPartitions());
        Slog.d(TAG, "Directories scanned as system partitions: " + scanPartitions);
        return scanPartitions;
    }

    private List<ScanPartition> getApexScanPartitions() {
        final List<ScanPartition> scanPartitions = new ArrayList<>();
        final List<ApexManager.ActiveApexInfo> activeApexInfos =
                mPm.mApexManager.getActiveApexInfos();
        for (int i = 0; i < activeApexInfos.size(); i++) {
            final ScanPartition scanPartition = resolveApexToScanPartition(activeApexInfos.get(i));
            if (scanPartition != null) {
                scanPartitions.add(scanPartition);
            }
        }
        return scanPartitions;
    }

    private static @Nullable ScanPartition resolveApexToScanPartition(
            ApexManager.ActiveApexInfo apexInfo) {
        for (int i = 0, size = SYSTEM_PARTITIONS.size(); i < size; i++) {
            ScanPartition sp = SYSTEM_PARTITIONS.get(i);
            if (apexInfo.preInstalledApexPath.getAbsolutePath().equals(
                    sp.getFolder().getAbsolutePath())
                    || apexInfo.preInstalledApexPath.getAbsolutePath().startsWith(
                        sp.getFolder().getAbsolutePath() + File.separator)) {
                int additionalScanFlag = SCAN_AS_APK_IN_APEX;
                if (apexInfo.isFactory) {
                    additionalScanFlag |= SCAN_AS_FACTORY;
                }
                return new ScanPartition(apexInfo.apexDirectory, sp, additionalScanFlag);
            }
        }
        return null;
    }

    public OverlayConfig initPackages(
            WatchedArrayMap<String, PackageSetting> packageSettings, int[] userIds,
            long startTime) {
        PackageParser2 packageParser = mPm.mInjector.getScanningCachingPackageParser();

        ExecutorService executorService = ParallelPackageParser.makeExecutorService();
        // Prepare apex package info before scanning APKs, this information is needed when
        // scanning apk in apex.
        mPm.mApexManager.scanApexPackagesTraced(packageParser, executorService);

        scanSystemDirs(packageParser, executorService);
        // Parse overlay configuration files to set default enable state, mutability, and
        // priority of system overlays.
        final ArrayMap<String, File> apkInApexPreInstalledPaths = new ArrayMap<>();
        for (ApexManager.ActiveApexInfo apexInfo : mPm.mApexManager.getActiveApexInfos()) {
            for (String packageName : mPm.mApexManager.getApksInApex(apexInfo.apexModuleName)) {
                apkInApexPreInstalledPaths.put(packageName, apexInfo.preInstalledApexPath);
            }
        }
        OverlayConfig overlayConfig = OverlayConfig.initializeSystemInstance(
                consumer -> mPm.forEachPackage(
                        pkg -> consumer.accept(pkg, pkg.isSystem(),
                          apkInApexPreInstalledPaths.get(pkg.getPackageName()))));
        // Prune any system packages that no longer exist.
        final List<String> possiblyDeletedUpdatedSystemApps = new ArrayList<>();
        // Stub packages must either be replaced with full versions in the /data
        // partition or be disabled.
        final List<String> stubSystemApps = new ArrayList<>();

        if (!mPm.isOnlyCoreApps()) {
            // do this first before mucking with mPackages for the "expecting better" case
            updateStubSystemAppsList(stubSystemApps);
            mInstallPackageHelper.prepareSystemPackageCleanUp(packageSettings,
                    possiblyDeletedUpdatedSystemApps, mExpectingBetter, userIds);
        }

        final int cachedSystemApps = PackageCacher.sCachedPackageReadCount.get();

        // Remove any shared userIDs that have no associated packages
        mPm.mSettings.pruneSharedUsersLPw();
        final long systemScanTime = SystemClock.uptimeMillis() - startTime;
        final int systemPackagesCount = mPm.mPackages.size();
        Slog.i(TAG, "Finished scanning system apps. Time: " + systemScanTime
                + " ms, packageCount: " + systemPackagesCount
                + " , timePerPackage: "
                + (systemPackagesCount == 0 ? 0 : systemScanTime / systemPackagesCount)
                + " , cached: " + cachedSystemApps);
        if (mPm.isDeviceUpgrading() && systemPackagesCount > 0) {
            //CHECKSTYLE:OFF IndentationCheck
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                    BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_SYSTEM_APP_AVG_SCAN_TIME,
                    systemScanTime / systemPackagesCount);
            //CHECKSTYLE:ON IndentationCheck
        }

        if (!mPm.isOnlyCoreApps()) {
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_DATA_SCAN_START,
                    SystemClock.uptimeMillis());
            scanDirTracedLI(mPm.getAppInstallDir(), /* frameworkSplits= */ null, 0,
                    mScanFlags | SCAN_REQUIRE_KNOWN, 0,
                    packageParser, executorService);

        }

        List<Runnable> unfinishedTasks = executorService.shutdownNow();
        if (!unfinishedTasks.isEmpty()) {
            throw new IllegalStateException("Not all tasks finished before calling close: "
                    + unfinishedTasks);
        }

        if (!mPm.isOnlyCoreApps()) {
            mInstallPackageHelper.cleanupDisabledPackageSettings(possiblyDeletedUpdatedSystemApps,
                    userIds, mScanFlags);
            mInstallPackageHelper.checkExistingBetterPackages(mExpectingBetter,
                    stubSystemApps, mSystemScanFlags, mSystemParseFlags);

            // Uncompress and install any stubbed system applications.
            // This must be done last to ensure all stubs are replaced or disabled.
            mInstallPackageHelper.installSystemStubPackages(stubSystemApps, mScanFlags);

            final int cachedNonSystemApps = PackageCacher.sCachedPackageReadCount.get()
                    - cachedSystemApps;

            final long dataScanTime = SystemClock.uptimeMillis() - systemScanTime - startTime;
            final int dataPackagesCount = mPm.mPackages.size() - systemPackagesCount;
            Slog.i(TAG, "Finished scanning non-system apps. Time: " + dataScanTime
                    + " ms, packageCount: " + dataPackagesCount
                    + " , timePerPackage: "
                    + (dataPackagesCount == 0 ? 0 : dataScanTime / dataPackagesCount)
                    + " , cached: " + cachedNonSystemApps);
            if (mPm.isDeviceUpgrading() && dataPackagesCount > 0) {
                //CHECKSTYLE:OFF IndentationCheck
                FrameworkStatsLog.write(
                        FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                        BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_DATA_APP_AVG_SCAN_TIME,
                        dataScanTime / dataPackagesCount);
                //CHECKSTYLE:OFF IndentationCheck
            }
        }
        mExpectingBetter.clear();

        mPm.mSettings.pruneRenamedPackagesLPw();
        packageParser.close();
        return overlayConfig;
    }

    /**
     * First part of init dir scanning
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void scanSystemDirs(PackageParser2 packageParser, ExecutorService executorService) {
        File frameworkDir = new File(Environment.getRootDirectory(), "framework");

        // Collect vendor/product/system_ext overlay packages. (Do this before scanning
        // any apps.)
        // For security and version matching reason, only consider overlay packages if they
        // reside in the right directory.
        for (int i = mDirsToScanAsSystem.size() - 1; i >= 0; i--) {
            final ScanPartition partition = mDirsToScanAsSystem.get(i);
            if (partition.getOverlayFolder() == null) {
                continue;
            }
            scanDirTracedLI(partition.getOverlayFolder(), /* frameworkSplits= */ null,
                    mSystemParseFlags, mSystemScanFlags | partition.scanFlag, 0,
                    packageParser, executorService);
        }

        List<File> frameworkSplits = getFrameworkResApkSplitFiles();
        scanDirTracedLI(frameworkDir, frameworkSplits,
                mSystemParseFlags | PARSE_FRAMEWORK_RES_SPLITS,
                mSystemScanFlags | SCAN_NO_DEX | SCAN_AS_PRIVILEGED, 0,
                packageParser, executorService);
        if (!mPm.mPackages.containsKey("android")) {
            throw new IllegalStateException(
                    "Failed to load frameworks package; check log for warnings");
        }

        for (int i = 0, size = mDirsToScanAsSystem.size(); i < size; i++) {
            final ScanPartition partition = mDirsToScanAsSystem.get(i);
            if (partition.getPrivAppFolder() != null) {
                scanDirTracedLI(partition.getPrivAppFolder(), /* frameworkSplits= */ null,
                        mSystemParseFlags,
                        mSystemScanFlags | SCAN_AS_PRIVILEGED | partition.scanFlag, 0,
                        packageParser, executorService);
            }
            scanDirTracedLI(partition.getAppFolder(), /* frameworkSplits= */ null,
                    mSystemParseFlags, mSystemScanFlags | partition.scanFlag, 0,
                    packageParser, executorService);
        }
    }

    @GuardedBy("mPm.mLock")
    private void updateStubSystemAppsList(List<String> stubSystemApps) {
        final int numPackages = mPm.mPackages.size();
        for (int index = 0; index < numPackages; index++) {
            final AndroidPackage pkg = mPm.mPackages.valueAt(index);
            if (pkg.isStub()) {
                stubSystemApps.add(pkg.getPackageName());
            }
        }
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void scanDirTracedLI(File scanDir, List<File> frameworkSplits,
            final int parseFlags, int scanFlags,
            long currentTime, PackageParser2 packageParser, ExecutorService executorService) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanDir [" + scanDir.getAbsolutePath() + "]");
        try {
            mInstallPackageHelper.installPackagesFromDir(scanDir, frameworkSplits, parseFlags,
                    scanFlags, currentTime, packageParser, executorService);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    public boolean isExpectingBetter(String packageName) {
        return mExpectingBetter.containsKey(packageName);
    }

    public List<ScanPartition> getDirsToScanAsSystem() {
        return mDirsToScanAsSystem;
    }
}
