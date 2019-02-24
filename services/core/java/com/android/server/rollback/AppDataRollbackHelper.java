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

import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.content.rollback.RollbackInfo;
import android.os.storage.StorageManager;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

    public AppDataRollbackHelper(Installer installer) {
        mInstaller = installer;
    }

    /**
     * Creates an app data snapshot for a specified {@code packageRollbackInfo}. Updates said {@code
     * packageRollbackInfo} with the inodes of the CE user data snapshot folders.
     */
    public void snapshotAppData(int snapshotId, PackageRollbackInfo packageRollbackInfo) {
        final int[] installedUsers = packageRollbackInfo.getInstalledUsers().toArray();
        for (int user : installedUsers) {
            final int storageFlags;
            if (isUserCredentialLocked(user)) {
                // We've encountered a user that hasn't unlocked on a FBE device, so we can't copy
                // across app user data until the user unlocks their device.
                Log.v(TAG, "User: " + user + " isn't unlocked, skipping CE userdata backup.");
                storageFlags = Installer.FLAG_STORAGE_DE;
                packageRollbackInfo.addPendingBackup(user);
            } else {
                storageFlags = Installer.FLAG_STORAGE_CE | Installer.FLAG_STORAGE_DE;
            }

            try {
                long ceSnapshotInode = mInstaller.snapshotAppData(
                        packageRollbackInfo.getPackageName(), user, snapshotId, storageFlags);
                if ((storageFlags & Installer.FLAG_STORAGE_CE) != 0) {
                    packageRollbackInfo.putCeSnapshotInode(user, ceSnapshotInode);
                }
            } catch (InstallerException ie) {
                Log.e(TAG, "Unable to create app data snapshot for: "
                        + packageRollbackInfo.getPackageName() + ", userId: " + user, ie);
            }
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

        final IntArray pendingBackups = packageRollbackInfo.getPendingBackups();
        final List<RestoreInfo> pendingRestores = packageRollbackInfo.getPendingRestores();
        boolean changedRollbackData = false;

        // If we still have a userdata backup pending for this user, it implies that the user
        // hasn't unlocked their device between the point of backup and the point of restore,
        // so the data cannot have changed. We simply skip restoring CE data in this case.
        if (pendingBackups != null && pendingBackups.indexOf(userId) != -1) {
            pendingBackups.remove(pendingBackups.indexOf(userId));
            changedRollbackData = true;
        } else {
            // There's no pending CE backup for this user, which means that we successfully
            // managed to backup data for the user, which means we seek to restore it
            if (isUserCredentialLocked(userId)) {
                // We've encountered a user that hasn't unlocked on a FBE device, so we can't
                // copy across app user data until the user unlocks their device.
                pendingRestores.add(new RestoreInfo(userId, appId, seInfo));
                changedRollbackData = true;
            } else {
                // This user has unlocked, we can proceed to restore both CE and DE data.
                storageFlags = storageFlags | Installer.FLAG_STORAGE_CE;
            }
        }

        try {
            mInstaller.restoreAppDataSnapshot(packageRollbackInfo.getPackageName(), appId, seInfo,
                    userId, rollbackId, storageFlags);
        } catch (InstallerException ie) {
            Log.e(TAG, "Unable to restore app data snapshot: "
                        + packageRollbackInfo.getPackageName(), ie);
        }

        return changedRollbackData;
    }

    /**
     * Deletes an app data snapshot with a given {@code rollbackId} for a specified package
     * {@code packageName} for a given {@code user}.
     */
    public void destroyAppDataSnapshot(int rollbackId, PackageRollbackInfo packageRollbackInfo,
            int user) {
        int storageFlags = Installer.FLAG_STORAGE_DE;
        final SparseLongArray ceSnapshotInodes = packageRollbackInfo.getCeSnapshotInodes();
        long ceSnapshotInode = ceSnapshotInodes.get(user);
        if (ceSnapshotInode > 0) {
            storageFlags |= Installer.FLAG_STORAGE_CE;
        }
        try {
            mInstaller.destroyAppDataSnapshot(packageRollbackInfo.getPackageName(), user,
                    ceSnapshotInode, rollbackId, storageFlags);
            if ((storageFlags & Installer.FLAG_STORAGE_CE) != 0) {
                ceSnapshotInodes.delete(user);
            }
        } catch (InstallerException ie) {
            Log.e(TAG, "Unable to delete app data snapshot for "
                        + packageRollbackInfo.getPackageName(), ie);
        }
    }

    /**
     * Computes the list of pending backups for {@code userId} given lists of available rollbacks.
     * Packages pending backup for the given user are added to {@code pendingBackupPackages} along
     * with their corresponding {@code PackageRollbackInfo}.
     *
     * @return the list of {@code RollbackData} that has pending backups. Note that some of the
     *         backups won't be performed, because they might be counteracted by pending restores.
     */
    private static List<RollbackData> computePendingBackups(int userId,
            Map<String, PackageRollbackInfo> pendingBackupPackages,
            List<RollbackData> availableRollbacks) {
        List<RollbackData> rd = new ArrayList<>();

        for (RollbackData data : availableRollbacks) {
            for (PackageRollbackInfo info : data.packages) {
                final IntArray pendingBackupUsers = info.getPendingBackups();
                if (pendingBackupUsers != null) {
                    final int idx = pendingBackupUsers.indexOf(userId);
                    if (idx != -1) {
                        pendingBackupPackages.put(info.getPackageName(), info);
                        if (rd.indexOf(data) == -1) {
                            rd.add(data);
                        }
                    }
                }
            }
        }
        return rd;
    }

    /**
     * Computes the list of pending restores for {@code userId} given lists of recent rollbacks.
     * Packages pending restore are added to {@code pendingRestores} along with their corresponding
     * {@code PackageRollbackInfo}.
     *
     * @return the list of {@code RollbackInfo} that has pending restores. Note that some of the
     *         restores won't be performed, because they might be counteracted by pending backups.
     */
    private static List<RollbackInfo> computePendingRestores(int userId,
            Map<String, PackageRollbackInfo> pendingRestorePackages,
            List<RollbackInfo> recentRollbacks) {
        List<RollbackInfo> rd = new ArrayList<>();

        for (RollbackInfo data : recentRollbacks) {
            for (PackageRollbackInfo info : data.getPackages()) {
                final RestoreInfo ri = info.getRestoreInfo(userId);
                if (ri != null) {
                    pendingRestorePackages.put(info.getPackageName(), info);
                    if (rd.indexOf(data) == -1) {
                        rd.add(data);
                    }
                }
            }
        }

        return rd;
    }

    /**
     * Commits the list of pending backups and restores for a given {@code userId}. For the pending
     * backups updates corresponding {@code changedRollbackData} with a mapping from {@code userId}
     * to a inode of theirs CE user data snapshot.
     *
     * @return a list {@code RollbackData} that have been changed and should be stored on disk.
     */
    public List<RollbackData> commitPendingBackupAndRestoreForUser(int userId,
            List<RollbackData> availableRollbacks, List<RollbackInfo> recentlyExecutedRollbacks) {

        final Map<String, PackageRollbackInfo> pendingBackupPackages = new HashMap<>();
        final List<RollbackData> pendingBackups = computePendingBackups(userId,
                pendingBackupPackages, availableRollbacks);

        final Map<String, PackageRollbackInfo> pendingRestorePackages = new HashMap<>();
        final List<RollbackInfo> pendingRestores = computePendingRestores(userId,
                pendingRestorePackages, recentlyExecutedRollbacks);

        // First remove unnecessary backups, i.e. when user did not unlock their phone between the
        // request to backup data and the request to restore it.
        Iterator<Map.Entry<String, PackageRollbackInfo>> iter =
                pendingBackupPackages.entrySet().iterator();
        while (iter.hasNext()) {
            PackageRollbackInfo backupPackage = iter.next().getValue();
            PackageRollbackInfo restorePackage =
                    pendingRestorePackages.get(backupPackage.getPackageName());
            if (restorePackage != null) {
                backupPackage.removePendingBackup(userId);
                backupPackage.removePendingRestoreInfo(userId);
                iter.remove();
                pendingRestorePackages.remove(backupPackage.getPackageName());
            }
        }

        if (!pendingBackupPackages.isEmpty()) {
            for (RollbackData data : pendingBackups) {
                for (PackageRollbackInfo info : data.packages) {
                    final IntArray pendingBackupUsers = info.getPendingBackups();
                    final int idx = pendingBackupUsers.indexOf(userId);
                    if (idx != -1) {
                        try {
                            long ceSnapshotInode = mInstaller.snapshotAppData(info.getPackageName(),
                                    userId, data.rollbackId, Installer.FLAG_STORAGE_CE);
                            info.putCeSnapshotInode(userId, ceSnapshotInode);
                            pendingBackupUsers.remove(idx);
                        } catch (InstallerException ie) {
                            Log.e(TAG,
                                    "Unable to create app data snapshot for: "
                                    + info.getPackageName() + ", userId: " + userId, ie);
                        }
                    }
                }
            }
        }

        if (!pendingRestorePackages.isEmpty()) {
            for (RollbackInfo data : pendingRestores) {
                for (PackageRollbackInfo info : data.getPackages()) {
                    final RestoreInfo ri = info.getRestoreInfo(userId);
                    if (ri != null) {
                        try {
                            mInstaller.restoreAppDataSnapshot(info.getPackageName(), ri.appId,
                                    ri.seInfo, userId, data.getRollbackId(),
                                    Installer.FLAG_STORAGE_CE);
                            info.removeRestoreInfo(ri);
                        } catch (InstallerException ie) {
                            Log.e(TAG, "Unable to restore app data snapshot for: "
                                    + info.getPackageName(), ie);
                        }
                    }
                }
            }
        }

        return pendingBackups;
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
