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

import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.getPackageManagerLocal;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.os.CreateAppDataArgs;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeInfo;
import android.security.AndroidKeyStoreMaintenance;
import android.system.keystore2.Domain;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.TimingsTraceLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.pm.Installer.LegacyDexoptDisabledException;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SELinuxUtil;

import dalvik.system.VMRuntime;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Prepares app data for users
 */
public class AppDataHelper {
    private static final boolean DEBUG_APP_DATA = false;

    private final PackageManagerService mPm;
    private final Installer mInstaller;
    private final ArtManagerService mArtManagerService;
    private final PackageManagerServiceInjector mInjector;

    // TODO(b/198166813): remove PMS dependency
    AppDataHelper(PackageManagerService pm) {
        mPm = pm;
        mInjector = mPm.mInjector;
        mInstaller = mInjector.getInstaller();
        mArtManagerService = mInjector.getArtManagerService();
    }

    /**
     * Prepare app data for the given app just after it was installed or
     * upgraded. This method carefully only touches users that it's installed
     * for, and it forces a restorecon to handle any seinfo changes.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps. If there is an ownership mismatch, it
     * will wipe and recreate the data.
     * <p>
     * <em>Note: To avoid a deadlock, do not call this method with {@code mLock} lock held</em>
     */
    @GuardedBy("mPm.mInstallLock")
    public void prepareAppDataAfterInstallLIF(AndroidPackage pkg) {
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
        }

        prepareAppDataPostCommitLIF(ps, 0 /* previousAppId */, getInstalledUsersForPackage(ps));
    }

    private int[] getInstalledUsersForPackage(PackageSetting ps) {
        UserManagerInternal umInternal = mInjector.getUserManagerInternal();
        var users = umInternal.getUsers(false /*excludeDying*/);
        int[] userIds = new int[users.size()];
        int userIdsCount = 0;
        for (int i = 0, size = users.size(); i < size; ++i) {
            int userId = users.get(i).id;
            if (ps.getInstalled(userId)) {
                userIds[userIdsCount++] = userId;
            }
        }
        return Arrays.copyOf(userIds, userIdsCount);
    }

    /**
     * For more details about data verification and previousAppId, check
     * {@link #prepareAppData}
     * @see #prepareAppDataAfterInstallLIF
     */
    @GuardedBy("mPm.mInstallLock")
    public void prepareAppDataPostCommitLIF(PackageSetting ps, int previousAppId, int[] userIds) {
        synchronized (mPm.mLock) {
            mPm.mSettings.writeKernelMappingLPr(ps);
        }

        // TODO(b/211761016): should we still create the profile dirs?
        if (ps.getPkg() != null && !shouldHaveAppStorage(ps.getPkg())) {
            Slog.w(TAG, "Skipping preparing app data for " + ps.getPackageName());
            return;
        }

        Installer.Batch batch = new Installer.Batch();
        UserManagerInternal umInternal = mInjector.getUserManagerInternal();
        StorageManagerInternal smInternal = mInjector.getLocalService(
                StorageManagerInternal.class);
        for (int userId : userIds) {
            final int flags;
            if (StorageManager.isCeStorageUnlocked(userId)
                    && smInternal.isCeStoragePrepared(userId)) {
                flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
            } else if (umInternal.isUserRunning(userId)) {
                flags = StorageManager.FLAG_STORAGE_DE;
            } else {
                continue;
            }

            // TODO: when user data is locked, mark that we're still dirty
            prepareAppData(batch, ps, previousAppId, userId, flags).thenRun(() -> {
                // Note: this code block is executed with the Installer lock
                // already held, since it's invoked as a side-effect of
                // executeBatchLI()
                if (umInternal.isUserUnlockingOrUnlocked(userId)) {
                    // Prepare app data on external storage; currently this is used to
                    // setup any OBB dirs that were created by the installer correctly.
                    int uid = UserHandle.getUid(userId, ps.getAppId());
                    smInternal.prepareAppDataAfterInstall(ps.getPackageName(), uid);
                }
            });
        }
        executeBatchLI(batch);
    }

    private void executeBatchLI(@NonNull Installer.Batch batch) {
        try {
            batch.execute(mInstaller);
        } catch (Installer.InstallerException e) {
            Slog.w(TAG, "Failed to execute pending operations", e);
        }
    }

    private void prepareAppDataAndMigrate(@NonNull Installer.Batch batch,
            @NonNull AndroidPackage pkg, @UserIdInt int userId,
            @StorageManager.StorageFlags int flags, boolean maybeMigrateAppData) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        if (!shouldHaveAppStorage(pkg)) {
            Slog.w(TAG, "Skipping preparing app data for " + pkg.getPackageName());
            return;
        }
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
        }
        prepareAppData(batch, ps, Process.INVALID_UID, userId, flags).thenRun(() -> {
            // Note: this code block is executed with the Installer lock
            // already held, since it's invoked as a side-effect of
            // executeBatchLI()
            if (maybeMigrateAppData && maybeMigrateAppDataLIF(ps, userId)) {
                // We may have just shuffled around app data directories, so
                // prepare them one more time
                final Installer.Batch batchInner = new Installer.Batch();
                prepareAppData(batchInner, ps, Process.INVALID_UID, userId, flags);
                executeBatchLI(batchInner);
            }
        });
    }

    /**
     * Prepare app data for the given app.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps. If there is an ownership mismatch:
     * <ul>
     * <li>If previousAppId < 0, app data will be migrated to the new app ID
     * <li>If previousAppId == 0, no migration will happen and data will be wiped and recreated
     * <li>If previousAppId > 0, app data owned by previousAppId will be migrated to the new app ID
     * </ul>
     */
    private @NonNull CompletableFuture<?> prepareAppData(@NonNull Installer.Batch batch,
            @NonNull PackageSetting ps, int previousAppId, int userId, int flags) {
        final String packageName = ps.getPackageName();

        if (DEBUG_APP_DATA) {
            Slog.v(TAG, "prepareAppData for " + packageName + " u" + userId + " 0x"
                    + Integer.toHexString(flags));
        }

        final String seInfoUser;
        synchronized (mPm.mLock) {
            seInfoUser = SELinuxUtil.getSeinfoUser(ps.readUserState(userId));
        }

        final AndroidPackage pkg = ps.getPkg();
        final String volumeUuid = ps.getVolumeUuid();
        final int appId = ps.getAppId();

        String pkgSeInfo = ps.getSeInfo();
        Preconditions.checkNotNull(pkgSeInfo);

        final String seInfo = pkgSeInfo + seInfoUser;
        final int targetSdkVersion = ps.getTargetSdkVersion();
        final boolean usesSdk = ps.getUsesSdkLibraries().length > 0;
        final CreateAppDataArgs args = Installer.buildCreateAppDataArgs(volumeUuid, packageName,
                userId, flags, appId, seInfo, targetSdkVersion, usesSdk);
        args.previousAppId = previousAppId;

        return batch.createAppData(args).whenComplete((createAppDataResult, e) -> {
            // Note: this code block is executed with the Installer lock
            // already held, since it's invoked as a side-effect of
            // executeBatchLI()
            if (e != null) {
                logCriticalInfo(Log.WARN, "Failed to create app data for " + packageName
                        + ", but trying to recover: " + e);
                destroyAppDataLeafLIF(packageName, volumeUuid, userId, flags);
                try {
                    createAppDataResult = mInstaller.createAppData(args);
                    logCriticalInfo(Log.DEBUG, "Recovery succeeded!");
                } catch (Installer.InstallerException e2) {
                    logCriticalInfo(Log.DEBUG, "Recovery failed!");
                }
            }

            if (!DexOptHelper.useArtService()) { // ART Service handles this on demand instead.
                // Prepare the application profiles only for upgrades and
                // first boot (so that we don't repeat the same operation at
                // each boot).
                //
                // We only have to cover the upgrade and first boot here
                // because for app installs we prepare the profiles before
                // invoking dexopt (in installPackageLI).
                //
                // We also have to cover non system users because we do not
                // call the usual install package methods for them.
                //
                // NOTE: in order to speed up first boot time we only create
                // the current profile and do not update the content of the
                // reference profile. A system image should already be
                // configured with the right profile keys and the profiles
                // for the speed-profile prebuilds should already be copied.
                // That's done in #performDexOptUpgrade.
                //
                // TODO(calin, mathieuc): We should use .dm files for
                // prebuilds profiles instead of manually copying them in
                // #performDexOptUpgrade. When we do that we should have a
                // more granular check here and only update the existing
                // profiles.
                if (pkg != null && (mPm.isDeviceUpgrading() || mPm.isFirstBoot()
                        || (userId != UserHandle.USER_SYSTEM))) {
                    try {
                        mArtManagerService.prepareAppProfiles(pkg, userId,
                                /* updateReferenceProfileContent= */ false);
                    } catch (LegacyDexoptDisabledException e2) {
                        throw new RuntimeException(e2);
                    }
                }
            }

            final long ceDataInode = createAppDataResult.ceDataInode;
            final long deDataInode = createAppDataResult.deDataInode;

            if ((flags & StorageManager.FLAG_STORAGE_CE) != 0 && ceDataInode != -1) {
                synchronized (mPm.mLock) {
                    ps.setCeDataInode(ceDataInode, userId);
                }
            }
            if ((flags & StorageManager.FLAG_STORAGE_DE) != 0 && deDataInode != -1) {
                synchronized (mPm.mLock) {
                    ps.setDeDataInode(deDataInode, userId);
                }
            }

            if (pkg != null) {
                prepareAppDataContentsLeafLIF(pkg, ps, userId, flags);
            }
        });
    }

    public void prepareAppDataContentsLIF(AndroidPackage pkg,
            @Nullable PackageStateInternal pkgSetting, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        prepareAppDataContentsLeafLIF(pkg, pkgSetting, userId, flags);
    }

    private void prepareAppDataContentsLeafLIF(AndroidPackage pkg,
            @Nullable PackageStateInternal pkgSetting, int userId, int flags) {
        final String volumeUuid = pkg.getVolumeUuid();
        final String packageName = pkg.getPackageName();

        if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
            // Create a native library symlink only if we have native libraries
            // and if the native libraries are 32 bit libraries. We do not provide
            // this symlink for 64 bit libraries.
            String primaryCpuAbi = pkgSetting == null
                    ? AndroidPackageUtils.getRawPrimaryCpuAbi(pkg) : pkgSetting.getPrimaryCpuAbi();
            if (primaryCpuAbi != null && !VMRuntime.is64BitAbi(primaryCpuAbi)) {
                final String nativeLibPath = pkg.getNativeLibraryDir();
                if (!(new File(nativeLibPath).exists())) {
                    return;
                }
                try {
                    mInstaller.linkNativeLibraryDirectory(volumeUuid, packageName,
                            nativeLibPath, userId);
                } catch (Installer.InstallerException e) {
                    Slog.e(TAG, "Failed to link native for " + packageName + ": " + e);
                }
            }
        }
    }

    /**
     * For system apps on non-FBE devices, this method migrates any existing
     * CE/DE data to match the {@code defaultToDeviceProtectedStorage} flag
     * requested by the app.
     */
    private boolean maybeMigrateAppDataLIF(@NonNull PackageSetting ps, @UserIdInt int userId) {
        if (ps.isSystem() && !StorageManager.isFileEncrypted()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            final int storageTarget = ps.isDefaultToDeviceProtectedStorage()
                    ? StorageManager.FLAG_STORAGE_DE : StorageManager.FLAG_STORAGE_CE;
            try {
                mInstaller.migrateAppData(ps.getVolumeUuid(), ps.getPackageName(), userId,
                        storageTarget);
            } catch (Installer.InstallerException e) {
                logCriticalInfo(Log.WARN,
                        "Failed to migrate " + ps.getPackageName() + ": " + e.getMessage());
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Reconcile all app data for the given user.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps on all mounted volumes.
     */
    @NonNull
    public void reconcileAppsData(int userId, @StorageManager.StorageFlags int flags,
            boolean migrateAppsData) {
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
            final String volumeUuid = vol.getFsUuid();
            synchronized (mPm.mInstallLock) {
                reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppsData);
            }
        }
    }

    @GuardedBy("mPm.mInstallLock")
    void reconcileAppsDataLI(String volumeUuid, int userId, @StorageManager.StorageFlags int flags,
            boolean migrateAppData) {
        reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppData, false /* onlyCoreApps */);
    }

    /**
     * Reconcile all app data on given mounted volume.
     * <p>
     * Destroys app data that isn't expected, either due to uninstallation or
     * reinstallation on another volume.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps.
     *
     * @return list of skipped non-core packages (if {@code onlyCoreApps} is true)
     */
    @GuardedBy("mPm.mInstallLock")
    private List<String> reconcileAppsDataLI(String volumeUuid, int userId,
            @StorageManager.StorageFlags int flags, boolean migrateAppData, boolean onlyCoreApps) {
        Slog.v(TAG, "reconcileAppsData for " + volumeUuid + " u" + userId + " 0x"
                + Integer.toHexString(flags) + " migrateAppData=" + migrateAppData);
        List<String> result = onlyCoreApps ? new ArrayList<>() : null;

        try {
            mInstaller.cleanupInvalidPackageDirs(volumeUuid, userId, flags);
        } catch (Installer.InstallerException e) {
            logCriticalInfo(Log.WARN, "Failed to cleanup deleted dirs: " + e);
        }

        final File ceDir = Environment.getDataUserCeDirectory(volumeUuid, userId);
        final File deDir = Environment.getDataUserDeDirectory(volumeUuid, userId);

        final Computer snapshot = mPm.snapshotComputer();
        // First look for stale data that doesn't belong, and check if things
        // have changed since we did our last restorecon
        if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
            if (StorageManager.isFileEncrypted() && !StorageManager.isCeStorageUnlocked(userId)) {
                throw new RuntimeException(
                        "Yikes, someone asked us to reconcile CE storage while " + userId
                                + " was still locked; this would have caused massive data loss!");
            }

            final File[] files = FileUtils.listFilesOrEmpty(ceDir);
            for (File file : files) {
                final String packageName = file.getName();
                try {
                    assertPackageStorageValid(snapshot, volumeUuid, packageName, userId);
                } catch (PackageManagerException e) {
                    logCriticalInfo(Log.WARN, "Destroying " + file + " due to: " + e);
                    try {
                        mInstaller.destroyAppData(volumeUuid, packageName, userId,
                                StorageManager.FLAG_STORAGE_CE, 0);
                    } catch (Installer.InstallerException e2) {
                        logCriticalInfo(Log.WARN, "Failed to destroy: " + e2);
                    }
                }
            }
        }
        if ((flags & StorageManager.FLAG_STORAGE_DE) != 0) {
            final File[] files = FileUtils.listFilesOrEmpty(deDir);
            for (File file : files) {
                final String packageName = file.getName();
                try {
                    assertPackageStorageValid(snapshot, volumeUuid, packageName, userId);
                } catch (PackageManagerException e) {
                    logCriticalInfo(Log.WARN, "Destroying " + file + " due to: " + e);
                    try {
                        mInstaller.destroyAppData(volumeUuid, packageName, userId,
                                StorageManager.FLAG_STORAGE_DE, 0);
                    } catch (Installer.InstallerException e2) {
                        logCriticalInfo(Log.WARN, "Failed to destroy: " + e2);
                    }
                }
            }
        }

        // Ensure that data directories are ready to roll for all packages
        // installed for this volume and user
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "prepareAppDataAndMigrate");
        Installer.Batch batch = new Installer.Batch();
        List<? extends PackageStateInternal> packages = snapshot.getVolumePackages(volumeUuid);
        int preparedCount = 0;
        for (PackageStateInternal ps : packages) {
            final String packageName = ps.getPackageName();
            if (ps.getPkg() == null) {
                Slog.w(TAG, "Odd, missing scanned package " + packageName);
                // TODO: might be due to legacy ASEC apps; we should circle back
                // and reconcile again once they're scanned
                continue;
            }
            // Skip non-core apps if requested
            if (onlyCoreApps && !ps.getPkg().isCoreApp()) {
                result.add(packageName);
                continue;
            }

            if (ps.getUserStateOrDefault(userId).isInstalled()) {
                prepareAppDataAndMigrate(batch, ps.getPkg(), userId, flags, migrateAppData);
                preparedCount++;
            }
        }
        executeBatchLI(batch);
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

        Slog.v(TAG, "reconcileAppsData finished " + preparedCount + " packages");
        return result;
    }

    /**
     * Asserts that storage path is valid by checking that {@code packageName} is present,
     * installed for the given {@code userId} and can have app data.
     */
    private void assertPackageStorageValid(@NonNull Computer snapshot, String volumeUuid,
            String packageName, int userId) throws PackageManagerException {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        if (packageState == null) {
            throw PackageManagerException.ofInternalError("Package " + packageName + " is unknown",
                    PackageManagerException.INTERNAL_ERROR_STORAGE_INVALID_PACKAGE_UNKNOWN);
        }
        if (!TextUtils.equals(volumeUuid, packageState.getVolumeUuid())) {
            throw PackageManagerException.ofInternalError(
                    "Package " + packageName + " found on unknown volume " + volumeUuid
                            + "; expected volume " + packageState.getVolumeUuid(),
                    PackageManagerException.INTERNAL_ERROR_STORAGE_INVALID_VOLUME_UNKNOWN);
        }
        final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
        if (!userState.isInstalled() && !userState.dataExists()) {
            throw PackageManagerException.ofInternalError(
                    "Package " + packageName + " not installed for user " + userId
                            + " or was deleted without DELETE_KEEP_DATA",
                    PackageManagerException.INTERNAL_ERROR_STORAGE_INVALID_NOT_INSTALLED_FOR_USER);
        }
        if (packageState.getPkg() != null
                && !shouldHaveAppStorage(packageState.getPkg())) {
            throw PackageManagerException.ofInternalError(
                    "Package " + packageName + " shouldn't have storage",
                    PackageManagerException.INTERNAL_ERROR_STORAGE_INVALID_SHOULD_NOT_HAVE_STORAGE);
        }
    }

    /**
     * Prepare storage for system user really early during boot,
     * since core system apps like SettingsProvider and SystemUI
     * can't wait for user to start
     */
    public Future<?> fixAppsDataOnBoot() {
        final @StorageManager.StorageFlags int storageFlags;
        if (StorageManager.isFileEncrypted()) {
            storageFlags = StorageManager.FLAG_STORAGE_DE;
        } else {
            storageFlags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
        }
        final List<String> deferPackages;
        synchronized (mPm.mInstallLock) {
           deferPackages = reconcileAppsDataLI(StorageManager.UUID_PRIVATE_INTERNAL,
                    UserHandle.USER_SYSTEM, storageFlags, true /* migrateAppData */,
                    true /* onlyCoreApps */);
        }
        Future<?> prepareAppDataFuture = SystemServerInitThreadPool.submit(() -> {
            TimingsTraceLog traceLog = new TimingsTraceLog("SystemServerTimingAsync",
                    Trace.TRACE_TAG_PACKAGE_MANAGER);
            traceLog.traceBegin("AppDataFixup");
            try {
                mInstaller.fixupAppData(StorageManager.UUID_PRIVATE_INTERNAL,
                        StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Trouble fixing GIDs", e);
            }
            traceLog.traceEnd();

            traceLog.traceBegin("AppDataPrepare");
            if (deferPackages == null || deferPackages.isEmpty()) {
                return;
            }
            int count = 0;
            final Installer.Batch batch = new Installer.Batch();
            for (String pkgName : deferPackages) {
                final Computer snapshot = mPm.snapshotComputer();
                final PackageStateInternal packageStateInternal = snapshot.getPackageStateInternal(
                        pkgName);
                if (packageStateInternal != null
                        && packageStateInternal.getUserStateOrDefault(
                                UserHandle.USER_SYSTEM).isInstalled()) {
                    AndroidPackage pkg = packageStateInternal.getPkg();
                    prepareAppDataAndMigrate(batch, pkg,
                            UserHandle.USER_SYSTEM, storageFlags, true /* maybeMigrateAppData */);
                    count++;
                }
            }
            synchronized (mPm.mInstallLock) {
                executeBatchLI(batch);
            }
            traceLog.traceEnd();
            Slog.i(TAG, "Deferred reconcileAppsData finished " + count + " packages");
        }, "prepareAppData");
        return prepareAppDataFuture;
    }

    void clearAppDataLIF(AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            return;
        }
        clearAppDataLeafLIF(pkg.getPackageName(), pkg.getVolumeUuid(), userId, flags);

        if ((flags & Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES) == 0) {
            clearAppProfilesLIF(pkg);
        }
    }

    void clearAppDataLeafLIF(String packageName, String volumeUuid, int userId, int flags) {
        final Computer snapshot = mPm.snapshotComputer();
        final PackageStateInternal packageStateInternal =
                snapshot.getPackageStateInternal(packageName);
        for (int realUserId : mPm.resolveUserIds(userId)) {
            final long ceDataInode = (packageStateInternal != null)
                    ? packageStateInternal.getUserStateOrDefault(realUserId).getCeDataInode() : 0;
            try {
                mInstaller.clearAppData(volumeUuid, packageName, realUserId,
                        flags, ceDataInode);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
        }
    }

    void clearAppProfilesLIF(AndroidPackage pkg) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        if (DexOptHelper.useArtService()) {
            destroyAppProfilesWithArtService(pkg.getPackageName());
        } else {
            try {
                mArtManagerService.clearAppProfiles(pkg);
            } catch (LegacyDexoptDisabledException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void destroyAppDataLIF(AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppDataLeafLIF(pkg.getPackageName(), pkg.getVolumeUuid(), userId, flags);
    }

    private void destroyAppDataLeafLIF(
            String packageName, String volumeUuid, int userId, int flags) {
        final Computer snapshot = mPm.snapshotComputer();
        final PackageStateInternal packageStateInternal =
                snapshot.getPackageStateInternal(packageName);
        for (int realUserId : mPm.resolveUserIds(userId)) {
            final long ceDataInode = (packageStateInternal != null)
                    ? packageStateInternal.getUserStateOrDefault(realUserId).getCeDataInode() : 0;
            try {
                mInstaller.destroyAppData(volumeUuid, packageName, realUserId,
                        flags, ceDataInode);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
            mPm.getDexManager().notifyPackageDataDestroyed(packageName, userId);
            mPm.getDynamicCodeLogger().notifyPackageDataDestroyed(packageName, userId);
        }
    }

    /**
     * Destroy ART app profiles for the package.
     */
    void destroyAppProfilesLIF(String packageName) {
        if (DexOptHelper.useArtService()) {
            destroyAppProfilesWithArtService(packageName);
        } else {
            try {
                mInstaller.destroyAppProfiles(packageName);
            } catch (LegacyDexoptDisabledException e) {
                throw new RuntimeException(e);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
        }
    }

    private void destroyAppProfilesWithArtService(String packageName) {
        if (!DexOptHelper.artManagerLocalIsInitialized()) {
            // This function may get called while PackageManagerService is constructed (via e.g.
            // InitAppsHelper.initSystemApps), and ART Service hasn't yet been started then (it
            // requires a registered PackageManagerLocal instance). We can skip clearing any stale
            // app profiles in this case, because ART Service and the runtime will ignore stale or
            // otherwise invalid ref and cur profiles.
            return;
        }

        try (PackageManagerLocal.FilteredSnapshot snapshot =
                        getPackageManagerLocal().withFilteredSnapshot()) {
            try {
                DexOptHelper.getArtManagerLocal().clearAppProfiles(snapshot, packageName);
            } catch (IllegalArgumentException e) {
                // Package isn't found, but that should only happen due to race.
                Slog.w(TAG, e);
            }
        }
    }

    /**
     * Returns {@code true} if app's internal storage should be created for this {@code pkg}.
     */
    private boolean shouldHaveAppStorage(AndroidPackage pkg) {
        PackageManager.Property noAppDataProp =
                pkg.getProperties().get(PackageManager.PROPERTY_NO_APP_DATA_STORAGE);
        return (noAppDataProp == null || !noAppDataProp.getBoolean()) && pkg.getUid() >= 0;
    }

    /**
     * Remove entries from the keystore daemon. Will only remove if the {@code appId} is valid.
     */
    public void clearKeystoreData(int userId, int appId) {
        if (appId < 0) {
            return;
        }

        for (int realUserId : mPm.resolveUserIds(userId)) {
            AndroidKeyStoreMaintenance.clearNamespace(
                    Domain.APP, UserHandle.getUid(realUserId, appId));
        }
    }
}
