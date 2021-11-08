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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.content.pm.parsing.ApkLiteParseUtils.isApkFile;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_DATA_APP_AVG_SCAN_TIME;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_SYSTEM_APP_AVG_SCAN_TIME;
import static com.android.server.pm.PackageManagerService.COMPRESSED_EXTENSION;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
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
import static com.android.server.pm.PackageManagerServiceUtils.decompressFile;
import static com.android.server.pm.PackageManagerServiceUtils.getCompressedFiles;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;
import static com.android.server.pm.PackageManagerServiceUtils.makeDirRecursive;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ParsingPackageUtils;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.F2fsUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.utils.WatchedArrayMap;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Part of PackageManagerService that handles init and system packages. This class still needs
 * further cleanup and eventually all the installation/scanning related logic will go to another
 * class.
 */
final class InitAndSystemPackageHelper {
    private final PackageManagerService mPm;
    private final RemovePackageHelper mRemovePackageHelper;
    private final AppDataHelper mAppDataHelper;

    private final List<ScanPartition> mDirsToScanAsSystem;
    private final int mScanFlags;
    private final int mSystemParseFlags;
    private final int mSystemScanFlags;

    /**
     * Tracks new system packages [received in an OTA] that we expect to
     * find updated user-installed versions. Keys are package name, values
     * are package location.
     */
    private final ArrayMap<String, File> mExpectingBetter = new ArrayMap<>();

    // TODO(b/198166813): remove PMS dependency
    InitAndSystemPackageHelper(PackageManagerService pm, RemovePackageHelper removePackageHelper,
            AppDataHelper appDataHelper) {
        mPm = pm;
        mRemovePackageHelper = removePackageHelper;
        mAppDataHelper = appDataHelper;
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
            if (apexInfo.preInstalledApexPath.getAbsolutePath().startsWith(
                    sp.getFolder().getAbsolutePath() + File.separator)) {
                return new ScanPartition(apexInfo.apexDirectory, sp, SCAN_AS_APK_IN_APEX);
            }
        }
        return null;
    }

    public OverlayConfig setUpSystemPackages(
            WatchedArrayMap<String, PackageSetting> packageSettings, int[] userIds,
            long startTime) {
        PackageParser2 packageParser = mPm.mInjector.getScanningCachingPackageParser();

        ExecutorService executorService = ParallelPackageParser.makeExecutorService();
        // Prepare apex package info before scanning APKs, these information are needed when
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
        cleanupSystemPackagesAndInstallStubs(packageParser, executorService, packageSettings,
                startTime, userIds);
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
            scanDirTracedLI(partition.getOverlayFolder(), mSystemParseFlags,
                    mSystemScanFlags | partition.scanFlag, 0,
                    packageParser, executorService);
        }

        scanDirTracedLI(frameworkDir, mSystemParseFlags,
                mSystemScanFlags | SCAN_NO_DEX | SCAN_AS_PRIVILEGED, 0,
                packageParser, executorService);
        if (!mPm.mPackages.containsKey("android")) {
            throw new IllegalStateException(
                    "Failed to load frameworks package; check log for warnings");
        }

        for (int i = 0, size = mDirsToScanAsSystem.size(); i < size; i++) {
            final ScanPartition partition = mDirsToScanAsSystem.get(i);
            if (partition.getPrivAppFolder() != null) {
                scanDirTracedLI(partition.getPrivAppFolder(), mSystemParseFlags,
                        mSystemScanFlags | SCAN_AS_PRIVILEGED | partition.scanFlag, 0,
                        packageParser, executorService);
            }
            scanDirTracedLI(partition.getAppFolder(), mSystemParseFlags,
                    mSystemScanFlags | partition.scanFlag, 0,
                    packageParser, executorService);
        }
    }

    /**
     * Second part of init dir scanning
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void cleanupSystemPackagesAndInstallStubs(PackageParser2 packageParser,
            ExecutorService executorService,
            WatchedArrayMap<String, PackageSetting> packageSettings,
            long startTime, int[] userIds) {
        // Prune any system packages that no longer exist.
        final List<String> possiblyDeletedUpdatedSystemApps = new ArrayList<>();
        // Stub packages must either be replaced with full versions in the /data
        // partition or be disabled.
        final List<String> stubSystemApps = new ArrayList<>();

        if (!mPm.isOnlyCoreApps()) {
            // do this first before mucking with mPackages for the "expecting better" case
            final int numPackages = mPm.mPackages.size();
            for (int index = 0; index < numPackages; index++) {
                final AndroidPackage pkg = mPm.mPackages.valueAt(index);
                if (pkg.isStub()) {
                    stubSystemApps.add(pkg.getPackageName());
                }
            }

            // Iterates PackageSettings in reversed order because the item could be removed
            // during the iteration.
            for (int index = packageSettings.size() - 1; index >= 0; index--) {
                final PackageSetting ps = packageSettings.valueAt(index);

                /*
                 * If this is not a system app, it can't be a
                 * disable system app.
                 */
                if ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    continue;
                }

                /*
                 * If the package is scanned, it's not erased.
                 */
                final AndroidPackage scannedPkg = mPm.mPackages.get(ps.getPackageName());
                if (scannedPkg != null) {
                    /*
                     * If the system app is both scanned and in the
                     * disabled packages list, then it must have been
                     * added via OTA. Remove it from the currently
                     * scanned package so the previously user-installed
                     * application can be scanned.
                     */
                    if (mPm.mSettings.isDisabledSystemPackageLPr(ps.getPackageName())) {
                        logCriticalInfo(Log.WARN,
                                "Expecting better updated system app for "
                                        + ps.getPackageName()
                                        + "; removing system app.  Last known"
                                        + " codePath=" + ps.getPathString()
                                        + ", versionCode=" + ps.getVersionCode()
                                        + "; scanned versionCode="
                                        + scannedPkg.getLongVersionCode());
                        mRemovePackageHelper.removePackageLI(scannedPkg, true);
                        mExpectingBetter.put(ps.getPackageName(), ps.getPath());
                    }

                    continue;
                }

                if (!mPm.mSettings.isDisabledSystemPackageLPr(ps.getPackageName())) {
                    logCriticalInfo(Log.WARN, "System package " + ps.getPackageName()
                            + " no longer exists; its data will be wiped");
                    mRemovePackageHelper.removePackageDataLIF(ps, userIds, null, 0, false);
                } else {
                    // we still have a disabled system package, but, it still might have
                    // been removed. check the code path still exists and check there's
                    // still a package. the latter can happen if an OTA keeps the same
                    // code path, but, changes the package name.
                    final PackageSetting disabledPs =
                            mPm.mSettings.getDisabledSystemPkgLPr(ps.getPackageName());
                    if (disabledPs.getPath() == null || !disabledPs.getPath().exists()
                            || disabledPs.getPkg() == null) {
                        possiblyDeletedUpdatedSystemApps.add(ps.getPackageName());
                    } else {
                        // We're expecting that the system app should remain disabled, but add
                        // it to expecting better to recover in case the data version cannot
                        // be scanned.
                        mExpectingBetter.put(disabledPs.getPackageName(), disabledPs.getPath());
                    }
                }
            }
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
            scanDirTracedLI(mPm.getAppInstallDir(), 0, mScanFlags | SCAN_REQUIRE_KNOWN, 0,
                    packageParser, executorService);

        }

        List<Runnable> unfinishedTasks = executorService.shutdownNow();
        if (!unfinishedTasks.isEmpty()) {
            throw new IllegalStateException("Not all tasks finished before calling close: "
                    + unfinishedTasks);
        }

        if (!mPm.isOnlyCoreApps()) {
            final ScanPackageHelper scanPackageHelper = new ScanPackageHelper(mPm);
            // Remove disable package settings for updated system apps that were
            // removed via an OTA. If the update is no longer present, remove the
            // app completely. Otherwise, revoke their system privileges.
            for (int i = possiblyDeletedUpdatedSystemApps.size() - 1; i >= 0; --i) {
                final String packageName = possiblyDeletedUpdatedSystemApps.get(i);
                final AndroidPackage pkg = mPm.mPackages.get(packageName);
                final String msg;

                // remove from the disabled system list; do this first so any future
                // scans of this package are performed without this state
                mPm.mSettings.removeDisabledSystemPackageLPw(packageName);

                if (pkg == null) {
                    // should have found an update, but, we didn't; remove everything
                    msg = "Updated system package " + packageName
                            + " no longer exists; removing its data";
                    // Actual deletion of code and data will be handled by later
                    // reconciliation step
                } else {
                    // found an update; revoke system privileges
                    msg = "Updated system package " + packageName
                            + " no longer exists; rescanning package on data";

                    // NOTE: We don't do anything special if a stub is removed from the
                    // system image. But, if we were [like removing the uncompressed
                    // version from the /data partition], this is where it'd be done.

                    // remove the package from the system and re-scan it without any
                    // special privileges
                    mRemovePackageHelper.removePackageLI(pkg, true);
                    try {
                        final File codePath = new File(pkg.getPath());
                        scanPackageHelper.scanPackageTracedLI(codePath, 0, mScanFlags, 0, null);
                    } catch (PackageManagerException e) {
                        Slog.e(TAG, "Failed to parse updated, ex-system package: "
                                + e.getMessage());
                    }
                }

                // one final check. if we still have a package setting [ie. it was
                // previously scanned and known to the system], but, we don't have
                // a package [ie. there was an error scanning it from the /data
                // partition], completely remove the package data.
                final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
                if (ps != null && mPm.mPackages.get(packageName) == null) {
                    mRemovePackageHelper.removePackageDataLIF(ps, userIds, null, 0, false);

                }
                logCriticalInfo(Log.WARN, msg);
            }

            /*
             * Make sure all system apps that we expected to appear on
             * the userdata partition actually showed up. If they never
             * appeared, crawl back and revive the system version.
             */
            for (int i = 0; i < mExpectingBetter.size(); i++) {
                final String packageName = mExpectingBetter.keyAt(i);
                if (!mPm.mPackages.containsKey(packageName)) {
                    final File scanFile = mExpectingBetter.valueAt(i);

                    logCriticalInfo(Log.WARN, "Expected better " + packageName
                            + " but never showed up; reverting to system");

                    @ParsingPackageUtils.ParseFlags int reparseFlags = 0;
                    @PackageManagerService.ScanFlags int rescanFlags = 0;
                    for (int i1 = mDirsToScanAsSystem.size() - 1; i1 >= 0; i1--) {
                        final ScanPartition partition = mDirsToScanAsSystem.get(i1);
                        if (partition.containsPrivApp(scanFile)) {
                            reparseFlags = mSystemParseFlags;
                            rescanFlags = mSystemScanFlags | SCAN_AS_PRIVILEGED
                                    | partition.scanFlag;
                            break;
                        }
                        if (partition.containsApp(scanFile)) {
                            reparseFlags = mSystemParseFlags;
                            rescanFlags = mSystemScanFlags | partition.scanFlag;
                            break;
                        }
                    }
                    if (rescanFlags == 0) {
                        Slog.e(TAG, "Ignoring unexpected fallback path " + scanFile);
                        continue;
                    }
                    mPm.mSettings.enableSystemPackageLPw(packageName);

                    try {
                        final AndroidPackage newPkg = scanPackageHelper.scanPackageTracedLI(
                                scanFile, reparseFlags, rescanFlags, 0, null);
                        // We rescanned a stub, add it to the list of stubbed system packages
                        if (newPkg.isStub()) {
                            stubSystemApps.add(packageName);
                        }
                    } catch (PackageManagerException e) {
                        Slog.e(TAG, "Failed to parse original system package: "
                                + e.getMessage());
                    }
                }
            }

            // Uncompress and install any stubbed system applications.
            // This must be done last to ensure all stubs are replaced or disabled.
            installSystemStubPackages(stubSystemApps, mScanFlags);

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
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void scanDirTracedLI(File scanDir, final int parseFlags, int scanFlags,
            long currentTime, PackageParser2 packageParser, ExecutorService executorService) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanDir [" + scanDir.getAbsolutePath() + "]");
        try {
            scanDirLI(scanDir, parseFlags, scanFlags, currentTime, packageParser, executorService);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void scanDirLI(File scanDir, int parseFlags, int scanFlags, long currentTime,
            PackageParser2 packageParser, ExecutorService executorService) {
        final File[] files = scanDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            Log.d(TAG, "No files in app dir " + scanDir);
            return;
        }

        if (DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "Scanning app dir " + scanDir + " scanFlags=" + scanFlags
                    + " flags=0x" + Integer.toHexString(parseFlags));
        }

        ParallelPackageParser parallelPackageParser =
                new ParallelPackageParser(packageParser, executorService);

        // Submit files for parsing in parallel
        int fileCount = 0;
        for (File file : files) {
            final boolean isPackage = (isApkFile(file) || file.isDirectory())
                    && !PackageInstallerService.isStageName(file.getName());
            if (!isPackage) {
                // Ignore entries which are not packages
                continue;
            }
            parallelPackageParser.submit(file, parseFlags);
            fileCount++;
        }

        final ScanPackageHelper scanPackageHelper = new ScanPackageHelper(mPm);
        // Process results one by one
        for (; fileCount > 0; fileCount--) {
            ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
            Throwable throwable = parseResult.throwable;
            int errorCode = PackageManager.INSTALL_SUCCEEDED;
            String errorMsg = null;

            if (throwable == null) {
                // TODO(b/194319951): move lower in the scan chain
                // Static shared libraries have synthetic package names
                if (parseResult.parsedPackage.isStaticSharedLibrary()) {
                    PackageManagerService.renameStaticSharedLibraryPackage(
                            parseResult.parsedPackage);
                }
                try {
                    scanPackageHelper.addForInitLI(parseResult.parsedPackage, parseFlags, scanFlags,
                            currentTime, null);
                } catch (PackageManagerException e) {
                    errorCode = e.error;
                    errorMsg = "Failed to scan " + parseResult.scanFile + ": " + e.getMessage();
                    Slog.w(TAG, errorMsg);
                }
            } else if (throwable instanceof PackageManagerException) {
                PackageManagerException e = (PackageManagerException) throwable;
                errorCode = e.error;
                errorMsg = "Failed to parse " + parseResult.scanFile + ": " + e.getMessage();
                Slog.w(TAG, errorMsg);
            } else {
                throw new IllegalStateException("Unexpected exception occurred while parsing "
                        + parseResult.scanFile, throwable);
            }

            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0 && errorCode != INSTALL_SUCCEEDED) {
                mPm.mApexManager.reportErrorWithApkInApex(scanDir.getAbsolutePath(), errorMsg);
            }

            // Delete invalid userdata apps
            if ((scanFlags & SCAN_AS_SYSTEM) == 0
                    && errorCode != PackageManager.INSTALL_SUCCEEDED) {
                logCriticalInfo(Log.WARN,
                        "Deleting invalid package at " + parseResult.scanFile);
                mRemovePackageHelper.removeCodePathLI(parseResult.scanFile);
            }
        }
    }

    /**
     * Uncompress and install stub applications.
     * <p>In order to save space on the system partition, some applications are shipped in a
     * compressed form. In addition the compressed bits for the full application, the
     * system image contains a tiny stub comprised of only the Android manifest.
     * <p>During the first boot, attempt to uncompress and install the full application. If
     * the application can't be installed for any reason, disable the stub and prevent
     * uncompressing the full application during future boots.
     * <p>In order to forcefully attempt an installation of a full application, go to app
     * settings and enable the application.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    private void installSystemStubPackages(@NonNull List<String> systemStubPackageNames,
            @PackageManagerService.ScanFlags int scanFlags) {
        for (int i = systemStubPackageNames.size() - 1; i >= 0; --i) {
            final String packageName = systemStubPackageNames.get(i);
            // skip if the system package is already disabled
            if (mPm.mSettings.isDisabledSystemPackageLPr(packageName)) {
                systemStubPackageNames.remove(i);
                continue;
            }
            // skip if the package isn't installed (?!); this should never happen
            final AndroidPackage pkg = mPm.mPackages.get(packageName);
            if (pkg == null) {
                systemStubPackageNames.remove(i);
                continue;
            }
            // skip if the package has been disabled by the user
            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null) {
                final int enabledState = ps.getEnabled(UserHandle.USER_SYSTEM);
                if (enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    systemStubPackageNames.remove(i);
                    continue;
                }
            }

            // install the package to replace the stub on /system
            try {
                installStubPackageLI(pkg, 0, scanFlags);
                ps.setEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        UserHandle.USER_SYSTEM, "android");
                systemStubPackageNames.remove(i);
            } catch (PackageManagerException e) {
                Slog.e(TAG, "Failed to parse uncompressed system package: " + e.getMessage());
            }

            // any failed attempt to install the package will be cleaned up later
        }

        // disable any stub still left; these failed to install the full application
        for (int i = systemStubPackageNames.size() - 1; i >= 0; --i) {
            final String pkgName = systemStubPackageNames.get(i);
            final PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
            ps.setEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    UserHandle.USER_SYSTEM, "android");
            logCriticalInfo(Log.ERROR, "Stub disabled; pkg: " + pkgName);
        }
    }

    /**
     * Extract, install and enable a stub package.
     * <p>If the compressed file can not be extracted / installed for any reason, the stub
     * APK will be installed and the package will be disabled. To recover from this situation,
     * the user will need to go into system settings and re-enable the package.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    public boolean enableCompressedPackage(AndroidPackage stubPkg,
            @NonNull PackageSetting stubPkgSetting) {
        final int parseFlags = mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_CHATTY
                | ParsingPackageUtils.PARSE_ENFORCE_CODE;
        synchronized (mPm.mInstallLock) {
            final AndroidPackage pkg;
            try (PackageFreezer freezer =
                         mPm.freezePackage(stubPkg.getPackageName(), "setEnabledSetting")) {
                pkg = installStubPackageLI(stubPkg, parseFlags, 0 /*scanFlags*/);
                synchronized (mPm.mLock) {
                    mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);
                    try {
                        mPm.updateSharedLibrariesLocked(pkg, stubPkgSetting, null, null,
                                Collections.unmodifiableMap(mPm.mPackages));
                    } catch (PackageManagerException e) {
                        Slog.w(TAG, "updateAllSharedLibrariesLPw failed: ", e);
                    }
                    mPm.mPermissionManager.onPackageInstalled(pkg,
                            Process.INVALID_UID /* previousAppId */,
                            PermissionManagerServiceInternal.PackageInstalledParams.DEFAULT,
                            UserHandle.USER_ALL);
                    mPm.writeSettingsLPrTEMP();
                }
            } catch (PackageManagerException e) {
                // Whoops! Something went very wrong; roll back to the stub and disable the package
                try (PackageFreezer freezer =
                             mPm.freezePackage(stubPkg.getPackageName(), "setEnabledSetting")) {
                    synchronized (mPm.mLock) {
                        // NOTE: Ensure the system package is enabled; even for a compressed stub.
                        // If we don't, installing the system package fails during scan
                        enableSystemPackageLPw(stubPkg);
                    }
                    installPackageFromSystemLIF(stubPkg.getPath(),
                            mPm.mUserManager.getUserIds() /*allUserHandles*/,
                            null /*origUserHandles*/,
                            true /*writeSettings*/);
                } catch (PackageManagerException pme) {
                    // Serious WTF; we have to be able to install the stub
                    Slog.wtf(TAG, "Failed to restore system package:" + stubPkg.getPackageName(),
                            pme);
                } finally {
                    // Disable the package; the stub by itself is not runnable
                    synchronized (mPm.mLock) {
                        final PackageSetting stubPs = mPm.mSettings.getPackageLPr(
                                stubPkg.getPackageName());
                        if (stubPs != null) {
                            stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED,
                                    UserHandle.USER_SYSTEM, "android");
                        }
                        mPm.writeSettingsLPrTEMP();
                    }
                }
                return false;
            }
            mAppDataHelper.clearAppDataLIF(pkg, UserHandle.USER_ALL,
                    FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                            | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
            mPm.getDexManager().notifyPackageUpdated(pkg.getPackageName(),
                    pkg.getBaseApkPath(), pkg.getSplitCodePaths());
        }
        return true;
    }

    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    private AndroidPackage installStubPackageLI(AndroidPackage stubPkg,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags)
            throws PackageManagerException {
        if (DEBUG_COMPRESSION) {
            Slog.i(TAG, "Uncompressing system stub; pkg: " + stubPkg.getPackageName());
        }
        // uncompress the binary to its eventual destination on /data
        final File scanFile = decompressPackage(stubPkg.getPackageName(), stubPkg.getPath());
        if (scanFile == null) {
            throw new PackageManagerException(
                    "Unable to decompress stub at " + stubPkg.getPath());
        }
        synchronized (mPm.mLock) {
            mPm.mSettings.disableSystemPackageLPw(stubPkg.getPackageName(), true /*replaced*/);
        }
        mRemovePackageHelper.removePackageLI(stubPkg, true /*chatty*/);
        final ScanPackageHelper scanPackageHelper = new ScanPackageHelper(mPm);
        try {
            return scanPackageHelper.scanPackageTracedLI(scanFile, parseFlags, scanFlags, 0, null);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to install compressed system package:" + stubPkg.getPackageName(),
                    e);
            // Remove the failed install
            mRemovePackageHelper.removeCodePathLI(scanFile);
            throw e;
        }
    }

    /**
     * Decompresses the given package on the system image onto
     * the /data partition.
     * @return The directory the package was decompressed into. Otherwise, {@code null}.
     */
    @GuardedBy("mPm.mInstallLock")
    private File decompressPackage(String packageName, String codePath) {
        final File[] compressedFiles = getCompressedFiles(codePath);
        if (compressedFiles == null || compressedFiles.length == 0) {
            if (DEBUG_COMPRESSION) {
                Slog.i(TAG, "No files to decompress: " + codePath);
            }
            return null;
        }
        final File dstCodePath =
                PackageManagerServiceUtils.getNextCodePath(Environment.getDataAppDirectory(null),
                        packageName);
        int ret = PackageManager.INSTALL_SUCCEEDED;
        try {
            makeDirRecursive(dstCodePath, 0755);
            for (File srcFile : compressedFiles) {
                final String srcFileName = srcFile.getName();
                final String dstFileName = srcFileName.substring(
                        0, srcFileName.length() - COMPRESSED_EXTENSION.length());
                final File dstFile = new File(dstCodePath, dstFileName);
                ret = decompressFile(srcFile, dstFile);
                if (ret != PackageManager.INSTALL_SUCCEEDED) {
                    logCriticalInfo(Log.ERROR, "Failed to decompress"
                            + "; pkg: " + packageName
                            + ", file: " + dstFileName);
                    break;
                }
            }
        } catch (ErrnoException e) {
            logCriticalInfo(Log.ERROR, "Failed to decompress"
                    + "; pkg: " + packageName
                    + ", err: " + e.errno);
        }
        if (ret == PackageManager.INSTALL_SUCCEEDED) {
            final File libraryRoot = new File(dstCodePath, LIB_DIR_NAME);
            NativeLibraryHelper.Handle handle = null;
            try {
                handle = NativeLibraryHelper.Handle.create(dstCodePath);
                ret = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                        null /*abiOverride*/, false /*isIncremental*/);
            } catch (IOException e) {
                logCriticalInfo(Log.ERROR, "Failed to extract native libraries"
                        + "; pkg: " + packageName);
                ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            } finally {
                IoUtils.closeQuietly(handle);
            }
        }
        if (ret == PackageManager.INSTALL_SUCCEEDED) {
            // NOTE: During boot, we have to delay releasing cblocks for no other reason than
            // we cannot retrieve the setting {@link Secure#RELEASE_COMPRESS_BLOCKS_ON_INSTALL}.
            // When we no longer need to read that setting, cblock release can occur always
            // occur here directly
            if (!mPm.isSystemReady()) {
                if (mPm.mReleaseOnSystemReady == null) {
                    mPm.mReleaseOnSystemReady = new ArrayList<>();
                }
                mPm.mReleaseOnSystemReady.add(dstCodePath);
            } else {
                final ContentResolver resolver = mPm.mContext.getContentResolver();
                F2fsUtils.releaseCompressedBlocks(resolver, dstCodePath);
            }
        }
        if (ret != PackageManager.INSTALL_SUCCEEDED) {
            if (!dstCodePath.exists()) {
                return null;
            }
            mRemovePackageHelper.removeCodePathLI(dstCodePath);
            return null;
        }

        return dstCodePath;
    }


    @GuardedBy("mPm.mLock")
    private void enableSystemPackageLPw(AndroidPackage pkg) {
        mPm.mSettings.enableSystemPackageLPw(pkg.getPackageName());
    }

    /**
     * Tries to restore the disabled system package after an update has been deleted.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    public void restoreDisabledSystemPackageLIF(DeletePackageAction action,
            PackageSetting deletedPs, @NonNull int[] allUserHandles,
            @Nullable PackageRemovedInfo outInfo,
            boolean writeSettings,
            PackageSetting disabledPs)
            throws SystemDeleteException {
        // writer
        synchronized (mPm.mLock) {
            // NOTE: The system package always needs to be enabled; even if it's for
            // a compressed stub. If we don't, installing the system package fails
            // during scan [scanning checks the disabled packages]. We will reverse
            // this later, after we've "installed" the stub.
            // Reinstate the old system package
            enableSystemPackageLPw(disabledPs.getPkg());
            // Remove any native libraries from the upgraded package.
            removeNativeBinariesLI(deletedPs);
        }

        // Install the system package
        if (DEBUG_REMOVE) Slog.d(TAG, "Re-installing system package: " + disabledPs);
        try {
            installPackageFromSystemLIF(disabledPs.getPathString(), allUserHandles,
                    outInfo == null ? null : outInfo.mOrigUsers, writeSettings);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to restore system package:" + deletedPs.getPackageName() + ": "
                    + e.getMessage());
            // TODO(b/194319951): can we avoid this; throw would come from scan...
            throw new SystemDeleteException(e);
        } finally {
            if (disabledPs.getPkg().isStub()) {
                // We've re-installed the stub; make sure it's disabled here. If package was
                // originally enabled, we'll install the compressed version of the application
                // and re-enable it afterward.
                final PackageSetting stubPs = mPm.mSettings.getPackageLPr(
                        deletedPs.getPackageName());
                if (stubPs != null) {
                    int userId = action.mUser == null
                            ? UserHandle.USER_ALL : action.mUser.getIdentifier();
                    if (userId == UserHandle.USER_ALL) {
                        for (int aUserId : allUserHandles) {
                            stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, aUserId, "android");
                        }
                    } else if (userId >= UserHandle.USER_SYSTEM) {
                        stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, userId, "android");
                    }
                }
            }
        }
    }

    private void removeNativeBinariesLI(PackageSetting ps) {
        if (ps != null) {
            NativeLibraryHelper.removeNativeBinariesLI(ps.getLegacyNativeLibraryPath());
        }
    }

    /**
     * Installs a package that's already on the system partition.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    private void installPackageFromSystemLIF(@NonNull String codePathString,
            @NonNull int[] allUserHandles, @Nullable int[] origUserHandles, boolean writeSettings)
            throws PackageManagerException {
        final File codePath = new File(codePathString);
        @ParsingPackageUtils.ParseFlags int parseFlags =
                mPm.getDefParseFlags()
                        | ParsingPackageUtils.PARSE_MUST_BE_APK
                        | ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
        @PackageManagerService.ScanFlags int scanFlags = SCAN_AS_SYSTEM;
        for (int i = mDirsToScanAsSystem.size() - 1; i >= 0; i--) {
            ScanPartition partition = mDirsToScanAsSystem.get(i);
            if (partition.containsFile(codePath)) {
                scanFlags |= partition.scanFlag;
                if (partition.containsPrivApp(codePath)) {
                    scanFlags |= SCAN_AS_PRIVILEGED;
                }
                break;
            }
        }

        final ScanPackageHelper scanPackageHelper = new ScanPackageHelper(mPm);
        final AndroidPackage pkg =
                scanPackageHelper.scanPackageTracedLI(
                        codePath, parseFlags, scanFlags, 0 /*currentTime*/, null);

        PackageSetting pkgSetting = mPm.mSettings.getPackageLPr(pkg.getPackageName());

        try {
            // update shared libraries for the newly re-installed system package
            mPm.updateSharedLibrariesLocked(pkg, pkgSetting, null, null,
                    Collections.unmodifiableMap(mPm.mPackages));
        } catch (PackageManagerException e) {
            Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
        }

        mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);

        // writer
        synchronized (mPm.mLock) {
            PackageSetting ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());

            final boolean applyUserRestrictions = origUserHandles != null;
            if (applyUserRestrictions) {
                boolean installedStateChanged = false;
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Propagating install state across reinstall");
                }
                for (int userId : allUserHandles) {
                    final boolean installed = ArrayUtils.contains(origUserHandles, userId);
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "    user " + userId + " => " + installed);
                    }
                    if (installed != ps.getInstalled(userId)) {
                        installedStateChanged = true;
                    }
                    ps.setInstalled(installed, userId);
                    if (installed) {
                        ps.setUninstallReason(UNINSTALL_REASON_UNKNOWN, userId);
                    }
                }
                // Regardless of writeSettings we need to ensure that this restriction
                // state propagation is persisted
                mPm.mSettings.writeAllUsersPackageRestrictionsLPr();
                if (installedStateChanged) {
                    mPm.mSettings.writeKernelMappingLPr(ps);
                }
            }

            // The method below will take care of removing obsolete permissions and granting
            // install permissions.
            mPm.mPermissionManager.onPackageInstalled(pkg,
                    Process.INVALID_UID /* previousAppId */,
                    PermissionManagerServiceInternal.PackageInstalledParams.DEFAULT,
                    UserHandle.USER_ALL);
            for (final int userId : allUserHandles) {
                if (applyUserRestrictions) {
                    mPm.mSettings.writePermissionStateForUserLPr(userId, false);
                }
            }

            // can downgrade to reader here
            if (writeSettings) {
                mPm.writeSettingsLPrTEMP();
            }
        }
    }

    public boolean isExpectingBetter(String packageName) {
        return mExpectingBetter.containsKey(packageName);
    }
}
