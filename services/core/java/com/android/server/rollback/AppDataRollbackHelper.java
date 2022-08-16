/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.rollback;

import android.content.pm.PackageManager;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.os.storage.StorageManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.ApexManager;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.util.List;

/**
 * Encapsulates the logic for initiating userdata snapshots and rollbacks via installd.
 */
@VisibleForTesting
// TODO(narayan): Reason about the failure scenarios that involve one or more IPCs to installd
// failing. We need to decide what course of action to take if calls to snapshotAppData or
// restoreAppDataSnapshot fail.
public class AppDataRollbackHelper {
    private static final String TAG = "RollbackManager";

    private final Installer mInstaller;
    private final ApexManager mApexManager;

    AppDataRollbackHelper(Installer installer) {
        mInstaller = installer;
        mApexManager = ApexManager.getInstance();
    }

    @VisibleForTesting
    AppDataRollbackHelper(Installer installer, ApexManager apexManager) {
        mInstaller = installer;
        mApexManager = apexManager;
    }

    /**
     * Creates an app data snapshot for a specified {@code packageRollbackInfo} and the specified
     * {@code userIds}. Updates said {@code packageRollbackInfo} with the inodes of the CE user data
     * snapshot folders.
     */
    public void snapshotAppData(
            int rollbackId, PackageRollbackInfo packageRollbackInfo, int[] userIds) {
        for (int user : userIds) {
            final int storageFlags;
            if (isUserCredentialLocked(user)) {
                // We've encountered a user that hasn't unlocked on a FBE device, so we can't copy
                // across app user data until the user unlocks their device.
                Slog.v(TAG, "User: " + user + " isn't unlocked, skipping CE userdata backup.");
                storageFlags = Installer.FLAG_STORAGE_DE;
                packageRollbackInfo.addPendingBackup(user);
            } else {
                storageFlags = Installer.FLAG_STORAGE_CE | Installer.FLAG_STORAGE_DE;
            }

            doSnapshot(packageRollbackInfo, user, rollbackId, storageFlags);
        }
    }

    /**
     * Restores an app data snapshot for a specified {@code packageRollbackInfo}, for a specified
     * {@code userId}.
     *
     * @return {@code true} iff. a change to the {@code packageRollbackInfo} has been made. Changes
     *         to {@code packageRollbackInfo} are restricted to the removal or addition of {@code
     *         userId} to the list of pending backups or restores.
     */
    public boolean restoreAppData(int rollbackId, PackageRollbackInfo packageRollbackInfo,
            int userId, int appId, String seInfo) {
        int storageFlags = Installer.FLAG_STORAGE_DE;

        final List<Integer> pendingBackups = packageRollbackInfo.getPendingBackups();
        final List<RestoreInfo> pendingRestores = packageRollbackInfo.getPendingRestores();
        boolean changedRollback = false;

        // If we still have a userdata backup pending for this user, it implies that the user
        // hasn't unlocked their device between the point of backup and the point of restore,
        // so the data cannot have changed. We simply skip restoring CE data in this case.
        if (pendingBackups != null && pendingBackups.indexOf(userId) != -1) {
            pendingBackups.remove(pendingBackups.indexOf(userId));
            changedRollback = true;
        } else {
            // There's no pending CE backup for this user, which means that we successfully
            // managed to backup data for the user, which means we seek to restore it
            if (isUserCredentialLocked(userId)) {
                // We've encountered a user that hasn't unlocked on a FBE device, so we can't
                // copy across app user data until the user unlocks their device.
                pendingRestores.add(new RestoreInfo(userId, appId, seInfo));
                changedRollback = true;
            } else {
                // This user has unlocked, we can proceed to restore both CE and DE data.
                storageFlags = storageFlags | Installer.FLAG_STORAGE_CE;
            }
        }

        doRestoreOrWipe(packageRollbackInfo, userId, rollbackId, appId, seInfo, storageFlags);

        return changedRollback;
    }

    private boolean doSnapshot(
            PackageRollbackInfo packageRollbackInfo, int userId, int rollbackId, int flags) {
        if (packageRollbackInfo.isApex()) {
            // For APEX, only snapshot CE here
            if ((flags & Installer.FLAG_STORAGE_CE) != 0) {
                return mApexManager.snapshotCeData(
                        userId, rollbackId, packageRollbackInfo.getPackageName());
            }
        } else {
            // APK
            try {
                return mInstaller.snapshotAppData(
                        packageRollbackInfo.getPackageName(), userId, rollbackId, flags);
            } catch (InstallerException ie) {
                Slog.e(TAG, "Unable to create app data snapshot for: "
                        + packageRollbackInfo.getPackageName() + ", userId: " + userId, ie);
                return false;
            }
        }
        return true;
    }

    private boolean doRestoreOrWipe(PackageRollbackInfo packageRollbackInfo, int userId,
            int rollbackId, int appId, String seInfo, int flags) {
        if (packageRollbackInfo.isApex()) {
            switch (packageRollbackInfo.getRollbackDataPolicy()) {
                case PackageManager.ROLLBACK_DATA_POLICY_WIPE:
                    // TODO: Implement WIPE for apex CE data
                    break;
                case PackageManager.ROLLBACK_DATA_POLICY_RESTORE:
                    // For APEX, only restore of CE may be done here.
                    if ((flags & Installer.FLAG_STORAGE_CE) != 0) {
                        mApexManager.restoreCeData(
                                userId, rollbackId, packageRollbackInfo.getPackageName());
                    }
                    break;
                default:
                    break;
            }
        } else {
            // APK
            try {
                switch (packageRollbackInfo.getRollbackDataPolicy()) {
                    case PackageManager.ROLLBACK_DATA_POLICY_WIPE:
                        mInstaller.clearAppData(null, packageRollbackInfo.getPackageName(),
                                userId, flags, 0);
                        break;
                    case PackageManager.ROLLBACK_DATA_POLICY_RESTORE:

                        mInstaller.restoreAppDataSnapshot(packageRollbackInfo.getPackageName(),
                                appId, seInfo, userId, rollbackId, flags);
                        break;
                    default:
                        break;
                }
            } catch (InstallerException ie) {
                Slog.e(TAG, "Unable to restore/wipe app data: "
                        + packageRollbackInfo.getPackageName() + " policy="
                        + packageRollbackInfo.getRollbackDataPolicy(), ie);
                return false;
            }
        }
        return true;
    }

    /**
     * Deletes an app data snapshot with a given {@code rollbackId} for a specified package
     * {@code packageName} for a given {@code user}.
     */
    public void destroyAppDataSnapshot(int rollbackId, PackageRollbackInfo packageRollbackInfo,
            int user) {
        try {
            // Delete both DE and CE snapshots if any
            mInstaller.destroyAppDataSnapshot(packageRollbackInfo.getPackageName(), user,
                    rollbackId, Installer.FLAG_STORAGE_DE | Installer.FLAG_STORAGE_CE);
        } catch (InstallerException ie) {
            Slog.e(TAG, "Unable to delete app data snapshot for "
                        + packageRollbackInfo.getPackageName(), ie);
        }
    }

    /**
     * Deletes all device-encrypted apex data snapshots for the given rollback id.
     */
    public void destroyApexDeSnapshots(int rollbackId) {
        mApexManager.destroyDeSnapshots(rollbackId);
    }

    /**
     * Deletes snapshots of the credential encrypted apex data directories for the specified user,
     * for the given rollback id. This method will be a no-op if the user is not unlocked.
     */
    public void destroyApexCeSnapshots(int userId, int rollbackId) {
        if (!isUserCredentialLocked(userId)) {
            mApexManager.destroyCeSnapshots(userId, rollbackId);
        }
    }

    /**
     * Commits the pending backups and restores for a given {@code userId} and {@code rollback}. If
     * the rollback has a pending backup, it is updated with a mapping from {@code userId} to inode
     * of the CE user data snapshot.
     *
     * @return true if any backups or restores were found for the userId
     */
    boolean commitPendingBackupAndRestoreForUser(int userId, Rollback rollback) {
        boolean foundBackupOrRestore = false;
        for (PackageRollbackInfo info : rollback.info.getPackages()) {
            boolean hasPendingBackup = false;
            boolean hasPendingRestore = false;
            final List<Integer> pendingBackupUsers = info.getPendingBackups();
            if (pendingBackupUsers != null) {
                if (pendingBackupUsers.indexOf(userId) != -1) {
                    hasPendingBackup = true;
                    foundBackupOrRestore = true;
                }
            }

            RestoreInfo ri = info.getRestoreInfo(userId);
            if (ri != null) {
                hasPendingRestore = true;
                foundBackupOrRestore = true;
            }

            if (hasPendingBackup && hasPendingRestore) {
                // Remove unnecessary backup, i.e. when user did not unlock their phone between the
                // request to backup data and the request to restore it.
                info.removePendingBackup(userId);
                info.removePendingRestoreInfo(userId);
                continue;
            }

            if (hasPendingBackup) {
                int idx = pendingBackupUsers.indexOf(userId);
                if (doSnapshot(
                        info, userId, rollback.info.getRollbackId(), Installer.FLAG_STORAGE_CE)) {
                    pendingBackupUsers.remove(idx);
                }
            }

            if (hasPendingRestore && doRestoreOrWipe(info, userId, rollback.info.getRollbackId(),
                    ri.appId, ri.seInfo, Installer.FLAG_STORAGE_CE)) {
                info.removeRestoreInfo(ri);
            }
        }
        return foundBackupOrRestore;
    }

    /**
     * @return {@code true} iff. {@code userId} is locked on an FBE device.
     */
    @VisibleForTesting
    public boolean isUserCredentialLocked(int userId) {
        return StorageManager.isFileEncryptedNativeOrEmulated()
                && !StorageManager.isUserKeyUnlocked(userId);
    }
}
