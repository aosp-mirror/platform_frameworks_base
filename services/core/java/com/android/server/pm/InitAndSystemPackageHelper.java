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
import static android.os.incremental.IncrementalManager.isIncrementalPath;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_DATA_APP_AVG_SCAN_TIME;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_SYSTEM_APP_AVG_SCAN_TIME;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerService.COMPRESSED_EXTENSION;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.DEBUG_VERIFY;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APK_IN_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRIVILEGED;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_REQUIRE_KNOWN;
import static com.android.server.pm.PackageManagerService.SCAN_UPDATE_SIGNATURE;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.decompressFile;
import static com.android.server.pm.PackageManagerServiceUtils.getCompressedFiles;
import static com.android.server.pm.PackageManagerServiceUtils.getLastModifiedTime;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;
import static com.android.server.pm.PackageManagerServiceUtils.makeDirRecursive;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.F2fsUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.utils.WatchedArrayMap;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.security.DigestException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Part of PackageManagerService that handles init and system packages. This class still needs
 * further cleanup and eventually all the installation/scanning related logic will go to another
 * class.
 */
public class InitAndSystemPackageHelper {
    final PackageManagerService mPm;

    // TODO(b/198166813): remove PMS dependency
    public InitAndSystemPackageHelper(PackageManagerService pm) {
        mPm = pm;
    }

    /**
     * First part of init dir scanning
     */
    // TODO(b/197876467): consolidate this with cleanupSystemPackagesAndInstallStubs
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public void scanSystemDirs(List<ScanPartition>  dirsToScanAsSystem,
            boolean isUpgrade, PackageParser2 packageParser,
            ExecutorService executorService, AndroidPackage platformPackage,
            boolean isPreNMR1Upgrade, int systemParseFlags, int systemScanFlags) {
        File frameworkDir = new File(Environment.getRootDirectory(), "framework");

        // Collect vendor/product/system_ext overlay packages. (Do this before scanning
        // any apps.)
        // For security and version matching reason, only consider overlay packages if they
        // reside in the right directory.
        for (int i = dirsToScanAsSystem.size() - 1; i >= 0; i--) {
            final ScanPartition partition = dirsToScanAsSystem.get(i);
            if (partition.getOverlayFolder() == null) {
                continue;
            }
            scanDirTracedLI(partition.getOverlayFolder(), systemParseFlags,
                    systemScanFlags | partition.scanFlag, 0,
                    packageParser, executorService, platformPackage, isUpgrade,
                    isPreNMR1Upgrade);
        }

        scanDirTracedLI(frameworkDir, systemParseFlags,
                systemScanFlags | SCAN_NO_DEX | SCAN_AS_PRIVILEGED, 0,
                packageParser, executorService, platformPackage, isUpgrade, isPreNMR1Upgrade);
        if (!mPm.mPackages.containsKey("android")) {
            throw new IllegalStateException(
                    "Failed to load frameworks package; check log for warnings");
        }

        for (int i = 0, size = dirsToScanAsSystem.size(); i < size; i++) {
            final ScanPartition partition = dirsToScanAsSystem.get(i);
            if (partition.getPrivAppFolder() != null) {
                scanDirTracedLI(partition.getPrivAppFolder(), systemParseFlags,
                        systemScanFlags | SCAN_AS_PRIVILEGED | partition.scanFlag, 0,
                        packageParser, executorService, platformPackage, isUpgrade,
                        isPreNMR1Upgrade);
            }
            scanDirTracedLI(partition.getAppFolder(), systemParseFlags,
                    systemScanFlags | partition.scanFlag, 0,
                    packageParser, executorService, platformPackage, isUpgrade,
                    isPreNMR1Upgrade);
        }
    }

    /**
     * Second part of init dir scanning
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public void cleanupSystemPackagesAndInstallStubs(List<ScanPartition> dirsToScanAsSystem,
            boolean isUpgrade, PackageParser2 packageParser,
            ExecutorService executorService, boolean onlyCore,
            WatchedArrayMap<String, PackageSetting> packageSettings,
            long startTime, File appInstallDir, AndroidPackage platformPackage,
            boolean isPreNMR1Upgrade, int scanFlags, int systemParseFlags, int systemScanFlags,
            int[] userIds) {
        // Prune any system packages that no longer exist.
        final List<String> possiblyDeletedUpdatedSystemApps = new ArrayList<>();
        // Stub packages must either be replaced with full versions in the /data
        // partition or be disabled.
        final List<String> stubSystemApps = new ArrayList<>();

        if (!onlyCore) {
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
                final AndroidPackage scannedPkg = mPm.mPackages.get(ps.name);
                if (scannedPkg != null) {
                    /*
                     * If the system app is both scanned and in the
                     * disabled packages list, then it must have been
                     * added via OTA. Remove it from the currently
                     * scanned package so the previously user-installed
                     * application can be scanned.
                     */
                    if (mPm.mSettings.isDisabledSystemPackageLPr(ps.name)) {
                        logCriticalInfo(Log.WARN,
                                "Expecting better updated system app for " + ps.name
                                        + "; removing system app.  Last known"
                                        + " codePath=" + ps.getPathString()
                                        + ", versionCode=" + ps.versionCode
                                        + "; scanned versionCode="
                                        + scannedPkg.getLongVersionCode());
                        mPm.removePackageLI(scannedPkg, true);
                        mPm.mExpectingBetter.put(ps.name, ps.getPath());
                    }

                    continue;
                }

                if (!mPm.mSettings.isDisabledSystemPackageLPr(ps.name)) {
                    logCriticalInfo(Log.WARN, "System package " + ps.name
                            + " no longer exists; its data will be wiped");
                    mPm.removePackageDataLIF(ps, userIds, null, 0, false);
                } else {
                    // we still have a disabled system package, but, it still might have
                    // been removed. check the code path still exists and check there's
                    // still a package. the latter can happen if an OTA keeps the same
                    // code path, but, changes the package name.
                    final PackageSetting disabledPs =
                            mPm.mSettings.getDisabledSystemPkgLPr(ps.name);
                    if (disabledPs.getPath() == null || !disabledPs.getPath().exists()
                            || disabledPs.pkg == null) {
                        possiblyDeletedUpdatedSystemApps.add(ps.name);
                    } else {
                        // We're expecting that the system app should remain disabled, but add
                        // it to expecting better to recover in case the data version cannot
                        // be scanned.
                        // TODO(b/197869066): mExpectingBetter should be able to pulled out into
                        // this class and used only by the PMS initialization
                        mPm.mExpectingBetter.put(disabledPs.name, disabledPs.getPath());
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
        if (isUpgrade && systemPackagesCount > 0) {
            //CHECKSTYLE:OFF IndentationCheck
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                    BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_SYSTEM_APP_AVG_SCAN_TIME,
                    systemScanTime / systemPackagesCount);
            //CHECKSTYLE:ON IndentationCheck
        }
        if (!onlyCore) {
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_DATA_SCAN_START,
                    SystemClock.uptimeMillis());
            scanDirTracedLI(appInstallDir, 0, scanFlags | SCAN_REQUIRE_KNOWN, 0,
                    packageParser, executorService, platformPackage, isUpgrade,
                    isPreNMR1Upgrade);

        }

        List<Runnable> unfinishedTasks = executorService.shutdownNow();
        if (!unfinishedTasks.isEmpty()) {
            throw new IllegalStateException("Not all tasks finished before calling close: "
                    + unfinishedTasks);
        }

        if (!onlyCore) {
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
                    mPm.removePackageLI(pkg, true);
                    try {
                        final File codePath = new File(pkg.getPath());
                        mPm.scanPackageTracedLI(codePath, 0, scanFlags, 0, null);
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
                    mPm.removePackageDataLIF(ps, userIds, null, 0, false);

                }
                logCriticalInfo(Log.WARN, msg);
            }

            /*
             * Make sure all system apps that we expected to appear on
             * the userdata partition actually showed up. If they never
             * appeared, crawl back and revive the system version.
             */
            for (int i = 0; i < mPm.mExpectingBetter.size(); i++) {
                final String packageName = mPm.mExpectingBetter.keyAt(i);
                if (!mPm.mPackages.containsKey(packageName)) {
                    final File scanFile = mPm.mExpectingBetter.valueAt(i);

                    logCriticalInfo(Log.WARN, "Expected better " + packageName
                            + " but never showed up; reverting to system");

                    @ParsingPackageUtils.ParseFlags int reparseFlags = 0;
                    @PackageManagerService.ScanFlags int rescanFlags = 0;
                    for (int i1 = dirsToScanAsSystem.size() - 1; i1 >= 0; i1--) {
                        final ScanPartition partition = dirsToScanAsSystem.get(i1);
                        if (partition.containsPrivApp(scanFile)) {
                            reparseFlags = systemParseFlags;
                            rescanFlags = systemScanFlags | SCAN_AS_PRIVILEGED
                                    | partition.scanFlag;
                            break;
                        }
                        if (partition.containsApp(scanFile)) {
                            reparseFlags = systemParseFlags;
                            rescanFlags = systemScanFlags | partition.scanFlag;
                            break;
                        }
                    }
                    if (rescanFlags == 0) {
                        Slog.e(TAG, "Ignoring unexpected fallback path " + scanFile);
                        continue;
                    }
                    mPm.mSettings.enableSystemPackageLPw(packageName);

                    try {
                        final AndroidPackage newPkg = mPm.scanPackageTracedLI(
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
            installSystemStubPackages(stubSystemApps, scanFlags);

            final int cachedNonSystemApps = PackageCacher.sCachedPackageReadCount.get()
                    - cachedSystemApps;

            final long dataScanTime = SystemClock.uptimeMillis() - systemScanTime - startTime;
            final int dataPackagesCount = mPm.mPackages.size() - systemPackagesCount;
            Slog.i(TAG, "Finished scanning non-system apps. Time: " + dataScanTime
                    + " ms, packageCount: " + dataPackagesCount
                    + " , timePerPackage: "
                    + (dataPackagesCount == 0 ? 0 : dataScanTime / dataPackagesCount)
                    + " , cached: " + cachedNonSystemApps);
            if (isUpgrade && dataPackagesCount > 0) {
                //CHECKSTYLE:OFF IndentationCheck
                FrameworkStatsLog.write(
                        FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                        BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_DATA_APP_AVG_SCAN_TIME,
                        dataScanTime / dataPackagesCount);
                //CHECKSTYLE:OFF IndentationCheck
            }
        }
        mPm.mExpectingBetter.clear();

        mPm.mSettings.pruneRenamedPackagesLPw();
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void scanDirTracedLI(File scanDir, final int parseFlags, int scanFlags,
            long currentTime, PackageParser2 packageParser, ExecutorService executorService,
            AndroidPackage platformPackage, boolean isUpgrade, boolean isPreNMR1Upgrade) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanDir [" + scanDir.getAbsolutePath() + "]");
        try {
            scanDirLI(scanDir, parseFlags, scanFlags, currentTime, packageParser, executorService,
                    platformPackage, isUpgrade, isPreNMR1Upgrade);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private void scanDirLI(File scanDir, int parseFlags, int scanFlags, long currentTime,
            PackageParser2 packageParser, ExecutorService executorService,
            AndroidPackage platformPackage, boolean isUpgrade, boolean isPreNMR1Upgrade) {
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
                    addForInitLI(parseResult.parsedPackage, parseFlags, scanFlags,
                            currentTime, null, platformPackage, isUpgrade,
                            isPreNMR1Upgrade);
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
                mPm.removeCodePathLI(parseResult.scanFile);
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
            @NonNull PackageSetting stubPkgSetting, int defParseFlags,
            List<ScanPartition> dirsToScanAsSystem) {
        final int parseFlags = defParseFlags | ParsingPackageUtils.PARSE_CHATTY
                | ParsingPackageUtils.PARSE_ENFORCE_CODE;
        synchronized (mPm.mInstallLock) {
            final AndroidPackage pkg;
            try (PackageFreezer freezer =
                         mPm.freezePackage(stubPkg.getPackageName(), "setEnabledSetting")) {
                pkg = installStubPackageLI(stubPkg, parseFlags, 0 /*scanFlags*/);
                synchronized (mPm.mLock) {
                    mPm.prepareAppDataAfterInstallLIF(pkg);
                    try {
                        mPm.updateSharedLibrariesLocked(pkg, stubPkgSetting, null, null,
                                Collections.unmodifiableMap(mPm.mPackages));
                    } catch (PackageManagerException e) {
                        Slog.w(TAG, "updateAllSharedLibrariesLPw failed: ", e);
                    }
                    mPm.mPermissionManager.onPackageInstalled(pkg,
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
                            true /*writeSettings*/, defParseFlags, dirsToScanAsSystem);
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
            mPm.clearAppDataLIF(pkg, UserHandle.USER_ALL, FLAG_STORAGE_DE | FLAG_STORAGE_CE
                    | FLAG_STORAGE_EXTERNAL | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
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
        mPm.removePackageLI(stubPkg, true /*chatty*/);
        try {
            return mPm.scanPackageTracedLI(scanFile, parseFlags, scanFlags, 0, null);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to install compressed system package:" + stubPkg.getPackageName(),
                    e);
            // Remove the failed install
            mPm.removeCodePathLI(scanFile);
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
                PackageManagerService.getNextCodePath(Environment.getDataAppDirectory(null),
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
            if (!mPm.mSystemReady) {
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
            mPm.removeCodePathLI(dstCodePath);
            return null;
        }

        return dstCodePath;
    }


    @GuardedBy("mPm.mLock")
    private void enableSystemPackageLPw(AndroidPackage pkg) {
        mPm.mSettings.enableSystemPackageLPw(pkg.getPackageName());
    }

    /**
     * Tries to delete system package.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    public void deleteSystemPackageLIF(DeletePackageAction action, PackageSetting deletedPs,
            @NonNull int[] allUserHandles, int flags, @Nullable PackageRemovedInfo outInfo,
            boolean writeSettings, int defParseFlags, List<ScanPartition> dirsToScanAsSystem)
            throws SystemDeleteException {
        final boolean applyUserRestrictions = outInfo != null && (outInfo.mOrigUsers != null);
        final AndroidPackage deletedPkg = deletedPs.pkg;
        // Confirm if the system package has been updated
        // An updated system app can be deleted. This will also have to restore
        // the system pkg from system partition
        // reader
        final PackageSetting disabledPs = action.mDisabledPs;
        if (DEBUG_REMOVE) {
            Slog.d(TAG, "deleteSystemPackageLI: newPs=" + deletedPkg.getPackageName()
                    + " disabledPs=" + disabledPs);
        }
        Slog.d(TAG, "Deleting system pkg from data partition");

        if (DEBUG_REMOVE) {
            if (applyUserRestrictions) {
                Slog.d(TAG, "Remembering install states:");
                for (int userId : allUserHandles) {
                    final boolean finstalled = ArrayUtils.contains(outInfo.mOrigUsers, userId);
                    Slog.d(TAG, "   u=" + userId + " inst=" + finstalled);
                }
            }
        }

        if (outInfo != null) {
            // Delete the updated package
            outInfo.mIsRemovedPackageSystemUpdate = true;
        }

        if (disabledPs.versionCode < deletedPs.versionCode) {
            // Delete data for downgrades
            flags &= ~PackageManager.DELETE_KEEP_DATA;
        } else {
            // Preserve data by setting flag
            flags |= PackageManager.DELETE_KEEP_DATA;
        }

        mPm.deleteInstalledPackageLIF(deletedPs, true, flags, allUserHandles,
                outInfo, writeSettings);

        // writer
        synchronized (mPm.mLock) {
            // NOTE: The system package always needs to be enabled; even if it's for
            // a compressed stub. If we don't, installing the system package fails
            // during scan [scanning checks the disabled packages]. We will reverse
            // this later, after we've "installed" the stub.
            // Reinstate the old system package
            enableSystemPackageLPw(disabledPs.pkg);
            // Remove any native libraries from the upgraded package.
            removeNativeBinariesLI(deletedPs);
        }

        // Install the system package
        if (DEBUG_REMOVE) Slog.d(TAG, "Re-installing system package: " + disabledPs);
        try {
            installPackageFromSystemLIF(disabledPs.getPathString(), allUserHandles,
                    outInfo == null ? null : outInfo.mOrigUsers, writeSettings, defParseFlags,
                    dirsToScanAsSystem);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to restore system package:" + deletedPkg.getPackageName() + ": "
                    + e.getMessage());
            // TODO(b/194319951): can we avoid this; throw would come from scan...
            throw new SystemDeleteException(e);
        } finally {
            if (disabledPs.pkg.isStub()) {
                // We've re-installed the stub; make sure it's disabled here. If package was
                // originally enabled, we'll install the compressed version of the application
                // and re-enable it afterward.
                final PackageSetting stubPs = mPm.mSettings.getPackageLPr(
                        deletedPkg.getPackageName());
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
            NativeLibraryHelper.removeNativeBinariesLI(ps.legacyNativeLibraryPathString);
        }
    }

    /**
     * Installs a package that's already on the system partition.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    private void installPackageFromSystemLIF(@NonNull String codePathString,
            @NonNull int[] allUserHandles, @Nullable int[] origUserHandles, boolean writeSettings,
            int defParseFlags, List<ScanPartition> dirsToScanAsSystem)
            throws PackageManagerException {
        final File codePath = new File(codePathString);
        @ParsingPackageUtils.ParseFlags int parseFlags =
                defParseFlags
                        | ParsingPackageUtils.PARSE_MUST_BE_APK
                        | ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
        @PackageManagerService.ScanFlags int scanFlags = SCAN_AS_SYSTEM;
        for (int i = dirsToScanAsSystem.size() - 1; i >= 0; i--) {
            ScanPartition partition = dirsToScanAsSystem.get(i);
            if (partition.containsFile(codePath)) {
                scanFlags |= partition.scanFlag;
                if (partition.containsPrivApp(codePath)) {
                    scanFlags |= SCAN_AS_PRIVILEGED;
                }
                break;
            }
        }

        final AndroidPackage pkg =
                mPm.scanPackageTracedLI(codePath, parseFlags, scanFlags, 0 /*currentTime*/, null);

        PackageSetting pkgSetting = mPm.mSettings.getPackageLPr(pkg.getPackageName());

        try {
            // update shared libraries for the newly re-installed system package
            mPm.updateSharedLibrariesLocked(pkg, pkgSetting, null, null,
                    Collections.unmodifiableMap(mPm.mPackages));
        } catch (PackageManagerException e) {
            Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
        }

        mPm.prepareAppDataAfterInstallLIF(pkg);

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

    /**
     * Adds a new package to the internal data structures during platform initialization.
     * <p>After adding, the package is known to the system and available for querying.
     * <p>For packages located on the device ROM [eg. packages located in /system, /vendor,
     * etc...], additional checks are performed. Basic verification [such as ensuring
     * matching signatures, checking version codes, etc...] occurs if the package is
     * identical to a previously known package. If the package fails a signature check,
     * the version installed on /data will be removed. If the version of the new package
     * is less than or equal than the version on /data, it will be ignored.
     * <p>Regardless of the package location, the results are applied to the internal
     * structures and the package is made available to the rest of the system.
     * <p>NOTE: The return value should be removed. It's the passed in package object.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    public AndroidPackage addForInitLI(ParsedPackage parsedPackage,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user, AndroidPackage platformPackage, boolean isUpgrade,
            boolean isPreNMR1Upgrade) throws PackageManagerException {
        final boolean scanSystemPartition =
                (parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) != 0;
        final String renamedPkgName;
        final PackageSetting disabledPkgSetting;
        final boolean isSystemPkgUpdated;
        final boolean pkgAlreadyExists;
        PackageSetting pkgSetting;

        synchronized (mPm.mLock) {
            renamedPkgName = mPm.mSettings.getRenamedPackageLPr(parsedPackage.getRealPackage());
            final String realPkgName = PackageManagerService.getRealPackageName(parsedPackage,
                    renamedPkgName);
            if (realPkgName != null) {
                PackageManagerService.ensurePackageRenamed(parsedPackage, renamedPkgName);
            }
            final PackageSetting originalPkgSetting = mPm.getOriginalPackageLocked(parsedPackage,
                    renamedPkgName);
            final PackageSetting installedPkgSetting = mPm.mSettings.getPackageLPr(
                    parsedPackage.getPackageName());
            pkgSetting = originalPkgSetting == null ? installedPkgSetting : originalPkgSetting;
            pkgAlreadyExists = pkgSetting != null;
            final String disabledPkgName = pkgAlreadyExists
                    ? pkgSetting.name : parsedPackage.getPackageName();
            if (scanSystemPartition && !pkgAlreadyExists
                    && mPm.mSettings.getDisabledSystemPkgLPr(disabledPkgName) != null) {
                // The updated-package data for /system apk remains inconsistently
                // after the package data for /data apk is lost accidentally.
                // To recover it, enable /system apk and install it as non-updated system app.
                Slog.w(TAG, "Inconsistent package setting of updated system app for "
                        + disabledPkgName + ". To recover it, enable the system app"
                        + "and install it as non-updated system app.");
                mPm.mSettings.removeDisabledSystemPackageLPw(disabledPkgName);
            }
            disabledPkgSetting = mPm.mSettings.getDisabledSystemPkgLPr(disabledPkgName);
            isSystemPkgUpdated = disabledPkgSetting != null;

            if (DEBUG_INSTALL && isSystemPkgUpdated) {
                Slog.d(TAG, "updatedPkg = " + disabledPkgSetting);
            }

            final SharedUserSetting sharedUserSetting = (parsedPackage.getSharedUserId() != null)
                    ? mPm.mSettings.getSharedUserLPw(parsedPackage.getSharedUserId(),
                    0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/, true)
                    : null;
            if (DEBUG_PACKAGE_SCANNING
                    && (parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0
                    && sharedUserSetting != null) {
                Log.d(TAG, "Shared UserID " + parsedPackage.getSharedUserId()
                        + " (uid=" + sharedUserSetting.userId + "):"
                        + " packages=" + sharedUserSetting.packages);
            }

            if (scanSystemPartition) {
                if (isSystemPkgUpdated) {
                    // we're updating the disabled package, so, scan it as the package setting
                    boolean isPlatformPackage = platformPackage != null
                            && Objects.equals(platformPackage.getPackageName(),
                            parsedPackage.getPackageName());
                    final ScanRequest request = new ScanRequest(parsedPackage, sharedUserSetting,
                            null, disabledPkgSetting /* pkgSetting */,
                            null /* disabledPkgSetting */, null /* originalPkgSetting */,
                            null, parseFlags, scanFlags, isPlatformPackage, user, null);
                    PackageManagerService.applyPolicy(parsedPackage, scanFlags,
                            platformPackage, true);
                    final ScanResult scanResult =
                            mPm.scanPackageOnlyLI(request, mPm.mInjector,
                                    mPm.mFactoryTest, -1L);
                    if (scanResult.mExistingSettingCopied
                            && scanResult.mRequest.mPkgSetting != null) {
                        scanResult.mRequest.mPkgSetting.updateFrom(scanResult.mPkgSetting);
                    }
                }
            }
        }

        final boolean newPkgChangedPaths = pkgAlreadyExists
                && !pkgSetting.getPathString().equals(parsedPackage.getPath());
        final boolean newPkgVersionGreater =
                pkgAlreadyExists && parsedPackage.getLongVersionCode() > pkgSetting.versionCode;
        final boolean isSystemPkgBetter = scanSystemPartition && isSystemPkgUpdated
                && newPkgChangedPaths && newPkgVersionGreater;
        if (isSystemPkgBetter) {
            // The version of the application on /system is greater than the version on
            // /data. Switch back to the application on /system.
            // It's safe to assume the application on /system will correctly scan. If not,
            // there won't be a working copy of the application.
            synchronized (mPm.mLock) {
                // just remove the loaded entries from package lists
                mPm.mPackages.remove(pkgSetting.name);
            }

            logCriticalInfo(Log.WARN,
                    "System package updated;"
                            + " name: " + pkgSetting.name
                            + "; " + pkgSetting.versionCode + " --> "
                            + parsedPackage.getLongVersionCode()
                            + "; " + pkgSetting.getPathString()
                            + " --> " + parsedPackage.getPath());

            final InstallArgs args = mPm.createInstallArgsForExisting(
                    pkgSetting.getPathString(), getAppDexInstructionSets(
                            pkgSetting.primaryCpuAbiString, pkgSetting.secondaryCpuAbiString));
            args.cleanUpResourcesLI();
            synchronized (mPm.mLock) {
                mPm.mSettings.enableSystemPackageLPw(pkgSetting.name);
            }
        }

        // The version of the application on the /system partition is less than or
        // equal to the version on the /data partition. Throw an exception and use
        // the application already installed on the /data partition.
        if (scanSystemPartition && isSystemPkgUpdated && !isSystemPkgBetter) {
            // In the case of a skipped package, commitReconciledScanResultLocked is not called to
            // add the object to the "live" data structures, so this is the final mutation step
            // for the package. Which means it needs to be finalized here to cache derived fields.
            // This is relevant for cases where the disabled system package is used for flags or
            // other metadata.
            parsedPackage.hideAsFinal();
            throw new PackageManagerException(Log.WARN, "Package " + parsedPackage.getPackageName()
                    + " at " + parsedPackage.getPath() + " ignored: updated version "
                    + (pkgAlreadyExists ? String.valueOf(pkgSetting.versionCode) : "unknown")
                    + " better than this " + parsedPackage.getLongVersionCode());
        }

        // Verify certificates against what was last scanned. Force re-collecting certificate in two
        // special cases:
        // 1) when scanning system, force re-collect only if system is upgrading.
        // 2) when scannning /data, force re-collect only if the app is privileged (updated from
        // preinstall, or treated as privileged, e.g. due to shared user ID).
        final boolean forceCollect = scanSystemPartition ? isUpgrade
                : PackageManagerServiceUtils.isApkVerificationForced(pkgSetting);
        if (DEBUG_VERIFY && forceCollect) {
            Slog.d(TAG, "Force collect certificate of " + parsedPackage.getPackageName());
        }

        // Full APK verification can be skipped during certificate collection, only if the file is
        // in verified partition, or can be verified on access (when apk verity is enabled). In both
        // cases, only data in Signing Block is verified instead of the whole file.
        // TODO(b/136132412): skip for Incremental installation
        final boolean skipVerify = scanSystemPartition
                || (forceCollect && canSkipForcedPackageVerification(parsedPackage));
        collectCertificatesLI(pkgSetting, parsedPackage, forceCollect, skipVerify,
                isPreNMR1Upgrade);

        // Reset profile if the application version is changed
        maybeClearProfilesForUpgradesLI(pkgSetting, parsedPackage);

        /*
         * A new system app appeared, but we already had a non-system one of the
         * same name installed earlier.
         */
        boolean shouldHideSystemApp = false;
        // A new application appeared on /system, but, we already have a copy of
        // the application installed on /data.
        if (scanSystemPartition && !isSystemPkgUpdated && pkgAlreadyExists
                && !pkgSetting.isSystem()) {

            if (!parsedPackage.getSigningDetails()
                    .checkCapability(pkgSetting.signatures.mSigningDetails,
                            SigningDetails.CertCapabilities.INSTALLED_DATA)
                    && !pkgSetting.signatures.mSigningDetails.checkCapability(
                    parsedPackage.getSigningDetails(),
                    SigningDetails.CertCapabilities.ROLLBACK)) {
                logCriticalInfo(Log.WARN,
                        "System package signature mismatch;"
                                + " name: " + pkgSetting.name);
                try (@SuppressWarnings("unused") PackageFreezer freezer = mPm.freezePackage(
                        parsedPackage.getPackageName(),
                        "scanPackageInternalLI")) {
                    mPm.deletePackageLIF(parsedPackage.getPackageName(), null, true,
                            mPm.mUserManager.getUserIds(), 0, null, false);
                }
                pkgSetting = null;
            } else if (newPkgVersionGreater) {
                // The application on /system is newer than the application on /data.
                // Simply remove the application on /data [keeping application data]
                // and replace it with the version on /system.
                logCriticalInfo(Log.WARN,
                        "System package enabled;"
                                + " name: " + pkgSetting.name
                                + "; " + pkgSetting.versionCode + " --> "
                                + parsedPackage.getLongVersionCode()
                                + "; " + pkgSetting.getPathString() + " --> "
                                + parsedPackage.getPath());
                InstallArgs args = mPm.createInstallArgsForExisting(
                        pkgSetting.getPathString(), getAppDexInstructionSets(
                                pkgSetting.primaryCpuAbiString, pkgSetting.secondaryCpuAbiString));
                synchronized (mPm.mInstallLock) {
                    args.cleanUpResourcesLI();
                }
            } else {
                // The application on /system is older than the application on /data. Hide
                // the application on /system and the version on /data will be scanned later
                // and re-added like an update.
                shouldHideSystemApp = true;
                logCriticalInfo(Log.INFO,
                        "System package disabled;"
                                + " name: " + pkgSetting.name
                                + "; old: " + pkgSetting.getPathString() + " @ "
                                + pkgSetting.versionCode
                                + "; new: " + parsedPackage.getPath() + " @ "
                                + parsedPackage.getPath());
            }
        }

        final ScanResult scanResult = mPm.scanPackageNewLI(parsedPackage, parseFlags, scanFlags
                | SCAN_UPDATE_SIGNATURE, currentTime, user, null);
        if (scanResult.mSuccess) {
            synchronized (mPm.mLock) {
                boolean appIdCreated = false;
                try {
                    final String pkgName = scanResult.mPkgSetting.name;
                    final Map<String, ReconciledPackage> reconcileResult =
                            mPm.reconcilePackagesLocked(
                            new ReconcileRequest(
                                    Collections.singletonMap(pkgName, scanResult),
                                    mPm.mSharedLibraries,
                                    mPm.mPackages,
                                    Collections.singletonMap(
                                            pkgName,
                                            mPm.getSettingsVersionForPackage(parsedPackage)),
                                    Collections.singletonMap(pkgName,
                                            mPm.getSharedLibLatestVersionSetting(scanResult))),
                                    mPm.mSettings.getKeySetManagerService(), mPm.mInjector);
                    appIdCreated = mPm.optimisticallyRegisterAppId(scanResult);
                    mPm.commitReconciledScanResultLocked(
                            reconcileResult.get(pkgName), mPm.mUserManager.getUserIds());
                } catch (PackageManagerException e) {
                    if (appIdCreated) {
                        mPm.cleanUpAppIdCreation(scanResult);
                    }
                    throw e;
                }
            }
        }

        if (shouldHideSystemApp) {
            synchronized (mPm.mLock) {
                mPm.mSettings.disableSystemPackageLPw(parsedPackage.getPackageName(), true);
            }
        }
        if (mPm.mIncrementalManager != null && isIncrementalPath(parsedPackage.getPath())) {
            if (pkgSetting != null && pkgSetting.isPackageLoading()) {
                // Continue monitoring loading progress of active incremental packages
                final IncrementalStatesCallback incrementalStatesCallback =
                        new IncrementalStatesCallback(parsedPackage.getPackageName(), mPm);
                pkgSetting.setIncrementalStatesCallback(incrementalStatesCallback);
                mPm.mIncrementalManager.registerLoadingProgressCallback(parsedPackage.getPath(),
                        new IncrementalProgressListener(parsedPackage.getPackageName(), mPm));
            }
        }
        return scanResult.mPkgSetting.pkg;
    }

    /**
     * Returns if forced apk verification can be skipped for the whole package, including splits.
     */
    private boolean canSkipForcedPackageVerification(AndroidPackage pkg) {
        if (!canSkipForcedApkVerification(pkg.getBaseApkPath())) {
            return false;
        }
        // TODO: Allow base and splits to be verified individually.
        String[] splitCodePaths = pkg.getSplitCodePaths();
        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            for (int i = 0; i < splitCodePaths.length; i++) {
                if (!canSkipForcedApkVerification(splitCodePaths[i])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns if forced apk verification can be skipped, depending on current FSVerity setup and
     * whether the apk contains signed root hash.  Note that the signer's certificate still needs to
     * match one in a trusted source, and should be done separately.
     */
    private boolean canSkipForcedApkVerification(String apkPath) {
        if (!PackageManagerServiceUtils.isLegacyApkVerityEnabled()) {
            return VerityUtils.hasFsverity(apkPath);
        }

        try {
            final byte[] rootHashObserved = VerityUtils.generateApkVerityRootHash(apkPath);
            if (rootHashObserved == null) {
                return false;  // APK does not contain Merkle tree root hash.
            }
            synchronized (mPm.mInstallLock) {
                // Returns whether the observed root hash matches what kernel has.
                mPm.mInstaller.assertFsverityRootHashMatches(apkPath, rootHashObserved);
                return true;
            }
        } catch (Installer.InstallerException | IOException | DigestException
                | NoSuchAlgorithmException e) {
            Slog.w(TAG, "Error in fsverity check. Fallback to full apk verification.", e);
        }
        return false;
    }

    private void collectCertificatesLI(PackageSetting ps, ParsedPackage parsedPackage,
            boolean forceCollect, boolean skipVerify, boolean mIsPreNMR1Upgrade)
            throws PackageManagerException {
        // When upgrading from pre-N MR1, verify the package time stamp using the package
        // directory and not the APK file.
        final long lastModifiedTime = mIsPreNMR1Upgrade
                ? new File(parsedPackage.getPath()).lastModified()
                : getLastModifiedTime(parsedPackage);
        final Settings.VersionInfo settingsVersionForPackage =
                mPm.getSettingsVersionForPackage(parsedPackage);
        if (ps != null && !forceCollect
                && ps.getPathString().equals(parsedPackage.getPath())
                && ps.timeStamp == lastModifiedTime
                && !PackageManagerService.isCompatSignatureUpdateNeeded(settingsVersionForPackage)
                && !PackageManagerService.isRecoverSignatureUpdateNeeded(
                        settingsVersionForPackage)) {
            if (ps.signatures.mSigningDetails.getSignatures() != null
                    && ps.signatures.mSigningDetails.getSignatures().length != 0
                    && ps.signatures.mSigningDetails.getSignatureSchemeVersion()
                    != SigningDetails.SignatureSchemeVersion.UNKNOWN) {
                // Optimization: reuse the existing cached signing data
                // if the package appears to be unchanged.
                parsedPackage.setSigningDetails(
                        new SigningDetails(ps.signatures.mSigningDetails));
                return;
            }

            Slog.w(TAG, "PackageSetting for " + ps.name
                    + " is missing signatures.  Collecting certs again to recover them.");
        } else {
            Slog.i(TAG, parsedPackage.getPath() + " changed; collecting certs"
                    + (forceCollect ? " (forced)" : ""));
        }

        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<SigningDetails> result = ParsingPackageUtils.getSigningDetails(
                    input, parsedPackage, skipVerify);
            if (result.isError()) {
                throw new PackageManagerException(
                        result.getErrorCode(), result.getErrorMessage(), result.getException());
            }
            parsedPackage.setSigningDetails(result.getResult());
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Clear the package profile if this was an upgrade and the package
     * version was updated.
     */
    private void maybeClearProfilesForUpgradesLI(
            @Nullable PackageSetting originalPkgSetting,
            @NonNull AndroidPackage pkg) {
        if (originalPkgSetting == null || !mPm.isDeviceUpgrading()) {
            return;
        }
        if (originalPkgSetting.versionCode == pkg.getVersionCode()) {
            return;
        }

        mPm.clearAppProfilesLIF(pkg);
        if (DEBUG_INSTALL) {
            Slog.d(TAG, originalPkgSetting.name
                    + " clear profile due to version change "
                    + originalPkgSetting.versionCode + " != "
                    + pkg.getVersionCode());
        }
    }
}
