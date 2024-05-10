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

import static com.android.internal.pm.pkg.parsing.ParsingPackageUtils.PARSE_APK_IN_APEX;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_DATA_APP_AVG_SCAN_TIME;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_SYSTEM_APP_AVG_SCAN_TIME;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APK_IN_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRIVILEGED;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE;
import static com.android.server.pm.PackageManagerService.SCAN_INITIAL;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_REQUIRE_KNOWN;
import static com.android.server.pm.PackageManagerService.SYSTEM_PARTITIONS;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.utils.WatchedArrayMap;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Part of PackageManagerService that handles init and system packages. This class still needs
 * further cleanup and eventually all the installation/scanning related logic will go to another
 * class.
 */
final class InitAppsHelper {
    private final PackageManagerService mPm;
    private final List<ScanPartition> mDirsToScanAsSystem;
    private final int mScanFlags;
    private final int mSystemParseFlags;
    private final int mSystemScanFlags;
    private final InstallPackageHelper mInstallPackageHelper;
    private final ApexManager mApexManager;
    private final ExecutorService mExecutorService;
    /* Tracks how long system scan took */
    private long mSystemScanTime;
    /* Track of the number of cached system apps */
    private int mCachedSystemApps;
    /* Track of the number of system apps */
    private int mSystemPackagesCount;
    private final boolean mIsDeviceUpgrading;
    private final List<ScanPartition> mSystemPartitions;

    /**
     * Tracks new system packages [received in an OTA] that we expect to
     * find updated user-installed versions. Keys are package name, values
     * are package location.
     */
    private final ArrayMap<String, File> mExpectingBetter = new ArrayMap<>();
    /* Tracks of any system packages that no longer exist that needs to be pruned. */
    private final List<String> mPossiblyDeletedUpdatedSystemApps = new ArrayList<>();
    // Tracks of stub packages that must either be replaced with full versions in the /data
    // partition or be disabled.
    private final List<String> mStubSystemApps = new ArrayList<>();

    // TODO(b/198166813): remove PMS dependency
    InitAppsHelper(PackageManagerService pm, ApexManager apexManager,
            InstallPackageHelper installPackageHelper,
            List<ScanPartition> systemPartitions) {
        mPm = pm;
        mApexManager = apexManager;
        mInstallPackageHelper = installPackageHelper;
        mSystemPartitions = systemPartitions;
        mDirsToScanAsSystem = getSystemScanPartitions();
        mIsDeviceUpgrading = mPm.isDeviceUpgrading();
        // Set flag to monitor and not change apk file paths when scanning install directories.
        int scanFlags = SCAN_BOOTING | SCAN_INITIAL;
        if (mIsDeviceUpgrading || mPm.isFirstBoot()) {
            mScanFlags = scanFlags | SCAN_FIRST_BOOT_OR_UPGRADE;
        } else {
            mScanFlags = scanFlags;
        }
        mSystemParseFlags = mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
        mSystemScanFlags = mScanFlags | SCAN_AS_SYSTEM;
        mExecutorService = ParallelPackageParser.makeExecutorService();
    }

    private List<ScanPartition> getSystemScanPartitions() {
        final List<ScanPartition> scanPartitions = new ArrayList<>();
        scanPartitions.addAll(mSystemPartitions);
        scanPartitions.addAll(getApexScanPartitions());
        Slog.d(TAG, "Directories scanned as system partitions: " + scanPartitions);
        return scanPartitions;
    }

    private List<ScanPartition> getApexScanPartitions() {
        final List<ScanPartition> scanPartitions = new ArrayList<>();
        final List<ApexManager.ActiveApexInfo> activeApexInfos = mApexManager.getActiveApexInfos();
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
                return new ScanPartition(apexInfo.apexDirectory, sp, apexInfo);
            }
        }
        return null;
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private List<ApexManager.ScanResult> scanApexPackagesTraced(PackageParser2 packageParser) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanApexPackages");

        try {
            return mInstallPackageHelper.scanApexPackages(mApexManager.getAllApexInfos(),
                    mSystemParseFlags, mSystemScanFlags, packageParser, mExecutorService);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Install apps from system dirs.
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public OverlayConfig initSystemApps(PackageParser2 packageParser,
            WatchedArrayMap<String, PackageSetting> packageSettings,
            int[] userIds, long startTime) {
        // Prepare apex package info before scanning APKs, this information is needed when
        // scanning apk in apex.
        final List<ApexManager.ScanResult> apexScanResults = scanApexPackagesTraced(packageParser);
        mApexManager.notifyScanResult(apexScanResults);

        scanSystemDirs(packageParser, mExecutorService);
        // Parse overlay configuration files to set default enable state, mutability, and
        // priority of system overlays.
        final ArrayMap<String, File> apkInApexPreInstalledPaths = new ArrayMap<>();
        for (ApexManager.ActiveApexInfo apexInfo : mApexManager.getActiveApexInfos()) {
            for (String packageName : mApexManager.getApksInApex(apexInfo.apexModuleName)) {
                apkInApexPreInstalledPaths.put(packageName, apexInfo.preInstalledApexPath);
            }
        }
        final OverlayConfig overlayConfig = OverlayConfig.initializeSystemInstance(
                consumer -> mPm.forEachPackageState(mPm.snapshotComputer(),
                        packageState -> {
                            var pkg = packageState.getPkg();
                            if (pkg != null) {
                                consumer.accept(pkg, packageState.isSystem(),
                                        apkInApexPreInstalledPaths.get(pkg.getPackageName()));
                            }
                        }));

        // do this first before mucking with mPackages for the "expecting better" case
        updateStubSystemAppsList(mStubSystemApps);
        mInstallPackageHelper.prepareSystemPackageCleanUp(packageSettings,
                mPossiblyDeletedUpdatedSystemApps, mExpectingBetter, userIds);

        logSystemAppsScanningTime(startTime);
        return overlayConfig;
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void logSystemAppsScanningTime(long startTime) {
        mCachedSystemApps = PackageCacher.sCachedPackageReadCount.get();

        // Remove any shared userIDs that have no associated packages
        mPm.mSettings.pruneSharedUsersLPw();
        mSystemScanTime = SystemClock.uptimeMillis() - startTime;
        mSystemPackagesCount = mPm.mPackages.size();
        Slog.i(TAG, "Finished scanning system apps. Time: " + mSystemScanTime
                + " ms, packageCount: " + mSystemPackagesCount
                + " , timePerPackage: "
                + (mSystemPackagesCount == 0 ? 0 : mSystemScanTime / mSystemPackagesCount)
                + " , cached: " + mCachedSystemApps);
        if (mIsDeviceUpgrading && mSystemPackagesCount > 0) {
            //CHECKSTYLE:OFF IndentationCheck
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                    BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_SYSTEM_APP_AVG_SCAN_TIME,
                    mSystemScanTime / mSystemPackagesCount);
            //CHECKSTYLE:ON IndentationCheck
        }
    }

    /**
     * Fix up the previously-installed app directory mode - they can't be readable by non-system
     * users to prevent them from listing the dir to discover installed package names.
     */
    void fixInstalledAppDirMode() {
        try (var files = Files.newDirectoryStream(mPm.getAppInstallDir().toPath())) {
            files.forEach(dir -> {
                try {
                    Os.chmod(dir.toString(), 0771);
                } catch (ErrnoException e) {
                    Slog.w(TAG, "Failed to fix an installed app dir mode", e);
                }
            });
        } catch (Exception e) {
            Slog.w(TAG, "Failed to walk the app install directory to fix the modes", e);
        }
    }

    /**
     * Install apps/updates from data dir and fix system apps that are affected.
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public void initNonSystemApps(PackageParser2 packageParser, @NonNull int[] userIds,
            long startTime) {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_DATA_SCAN_START,
                SystemClock.uptimeMillis());

        if ((mScanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) == SCAN_FIRST_BOOT_OR_UPGRADE) {
            fixInstalledAppDirMode();
        }

        scanDirTracedLI(mPm.getAppInstallDir(), 0,
                mScanFlags | SCAN_REQUIRE_KNOWN, packageParser, mExecutorService, null);

        List<Runnable> unfinishedTasks = mExecutorService.shutdownNow();
        if (!unfinishedTasks.isEmpty()) {
            throw new IllegalStateException("Not all tasks finished before calling close: "
                    + unfinishedTasks);
        }
        fixSystemPackages(userIds);
        logNonSystemAppScanningTime(startTime);
        mExpectingBetter.clear();
        mPm.mSettings.pruneRenamedPackagesLPw();
    }

    /**
     * Clean up system packages now that some system package updates have been installed from
     * the data dir. Also install system stub packages as the last step.
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void fixSystemPackages(@NonNull int[] userIds) {
        mInstallPackageHelper.cleanupDisabledPackageSettings(mPossiblyDeletedUpdatedSystemApps,
                userIds, mScanFlags);
        mInstallPackageHelper.checkExistingBetterPackages(mExpectingBetter,
                mStubSystemApps, mSystemScanFlags, mSystemParseFlags);

        // Uncompress and install any stubbed system applications.
        // This must be done last to ensure all stubs are replaced or disabled.
        mInstallPackageHelper.installSystemStubPackages(mStubSystemApps, mScanFlags);
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void logNonSystemAppScanningTime(long startTime) {
        final int cachedNonSystemApps = PackageCacher.sCachedPackageReadCount.get()
                - mCachedSystemApps;

        final long dataScanTime = SystemClock.uptimeMillis() - mSystemScanTime - startTime;
        final int dataPackagesCount = mPm.mPackages.size() - mSystemPackagesCount;
        Slog.i(TAG, "Finished scanning non-system apps. Time: " + dataScanTime
                + " ms, packageCount: " + dataPackagesCount
                + " , timePerPackage: "
                + (dataPackagesCount == 0 ? 0 : dataScanTime / dataPackagesCount)
                + " , cached: " + cachedNonSystemApps);
        if (mIsDeviceUpgrading && dataPackagesCount > 0) {
            //CHECKSTYLE:OFF IndentationCheck
            FrameworkStatsLog.write(
                    FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                    BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_DATA_APP_AVG_SCAN_TIME,
                    dataScanTime / dataPackagesCount);
            //CHECKSTYLE:OFF IndentationCheck
        }
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
            scanDirTracedLI(partition.getOverlayFolder(),
                    mSystemParseFlags, mSystemScanFlags | partition.scanFlag,
                    packageParser, executorService, partition.apexInfo);
        }

        scanDirTracedLI(frameworkDir,
                mSystemParseFlags, mSystemScanFlags | SCAN_NO_DEX | SCAN_AS_PRIVILEGED,
                packageParser, executorService, null);
        if (!mPm.mPackages.containsKey("android")) {
            throw new IllegalStateException(
                    "Failed to load frameworks package; check log for warnings");
        }

        for (int i = 0, size = mDirsToScanAsSystem.size(); i < size; i++) {
            final ScanPartition partition = mDirsToScanAsSystem.get(i);
            if (partition.getPrivAppFolder() != null) {
                scanDirTracedLI(partition.getPrivAppFolder(),
                        mSystemParseFlags,
                        mSystemScanFlags | SCAN_AS_PRIVILEGED | partition.scanFlag,
                        packageParser, executorService, partition.apexInfo);
            }
            scanDirTracedLI(partition.getAppFolder(),
                    mSystemParseFlags, mSystemScanFlags | partition.scanFlag,
                    packageParser, executorService, partition.apexInfo);
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
    private void scanDirTracedLI(File scanDir, int parseFlags, int scanFlags,
            PackageParser2 packageParser, ExecutorService executorService,
            @Nullable ApexManager.ActiveApexInfo apexInfo) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanDir [" + scanDir.getAbsolutePath() + "]");
        try {
            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0) {
                // when scanning apk in apexes, we want to check the maxSdkVersion
                parseFlags |= PARSE_APK_IN_APEX;
            }
            mInstallPackageHelper.installPackagesFromDir(scanDir, parseFlags,
                    scanFlags, packageParser, executorService, apexInfo);
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

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public int getSystemScanFlags() {
        return mSystemScanFlags;
    }
}
