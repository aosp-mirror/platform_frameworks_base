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
     * Creates an app data snapshot for a specified {@code packageName} for {@code installedUsers},
     * a specified set of users for whom the package is installed.
     *
     * @return a {@link SnapshotAppDataResult}/
     * @see SnapshotAppDataResult
     */
    public SnapshotAppDataResult snapshotAppData(String packageName, int[] installedUsers) {
        final IntArray pendingBackups = new IntArray();
        final SparseLongArray ceSnapshotInodes = new SparseLongArray();

        for (int user : installedUsers) {
            final int storageFlags;
            if (isUserCredentialLocked(user)) {
                // We've encountered a user that hasn't unlocked on a FBE device, so we can't copy
                // across app user data until the user unlocks their device.
                Log.v(TAG, "User: " + user + " isn't unlocked, skipping CE userdata backup.");
                storageFlags = Installer.FLAG_STORAGE_DE;
                pendingBackups.add(user);
            } else {
                storageFlags = Installer.FLAG_STORAGE_CE | Installer.FLAG_STORAGE_DE;
            }

            try {
                long ceSnapshotInode = mInstaller.snapshotAppData(packageName, user, storageFlags);
                if ((storageFlags & Installer.FLAG_STORAGE_CE) != 0) {
                    ceSnapshotInodes.put(user, ceSnapshotInode);
                }
            } catch (InstallerException ie) {
                Log.e(TAG, "Unable to create app data snapshot for: " + packageName
                        + ", userId: " + user, ie);
            }
        }

        return new SnapshotAppDataResult(pendingBackups, ceSnapshotInodes);
    }

    /**
     * Restores an app data snapshot for a specified package ({@code packageName},
     * {@code rollbackData}) for a specified {@code userId}.
     *
     * @return {@code true} iff. a change to the {@code rollbackData} has been made. Changes to
     *         {@code rollbackData} are restricted to the removal or addition of {@code userId} to
     *         the list of pending backups or restores.
     */
    public boolean restoreAppData(String packageName, RollbackData rollbackData,
            int userId, int appId, long ceDataInode, String seInfo) {
        if (rollbackData == null) {
            return false;
        }

        if (!rollbackData.inProgress) {
            Log.e(TAG, "Request to restore userData for: " + packageName
                    + ", but no rollback in progress.");
            return false;
        }

        PackageRollbackInfo packageInfo = RollbackManagerServiceImpl.getPackageRollbackInfo(
                rollbackData, packageName);
        int storageFlags = Installer.FLAG_STORAGE_DE;

        final IntArray pendingBackups = packageInfo.getPendingBackups();
        final List<RestoreInfo> pendingRestores = packageInfo.getPendingRestores();
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
            mInstaller.restoreAppDataSnapshot(packageName, appId, ceDataInode,
                    seInfo, userId, storageFlags);
        } catch (InstallerException ie) {
            Log.e(TAG, "Unable to restore app data snapshot: " + packageName, ie);
        }

        return changedRollbackData;
    }

    /**
     * Deletes an app data data snapshot for a specified package {@code packageName} for a
     * given {@code user}.
     */
    public void destroyAppDataSnapshot(String packageName, int user, long ceSnapshotInode) {
        int storageFlags = Installer.FLAG_STORAGE_DE;
        if (ceSnapshotInode > 0) {
            storageFlags |= Installer.FLAG_STORAGE_CE;
        }
        try {
            mInstaller.destroyAppDataSnapshot(packageName, user, ceSnapshotInode, storageFlags);
        } catch (InstallerException ie) {
            Log.e(TAG, "Unable to delete app data snapshot for " + packageName, ie);
        }
    }

    /**
     * Computes the list of pending backups and restores for {@code userId} given lists of
     * available and recent rollbacks. Packages pending backup for the given user are added
     * to {@code pendingBackups} and packages pending restore are added to {@code pendingRestores}
     * along with their corresponding {@code RestoreInfo}.
     *
     * @return the list of {@code RollbackData} that have been modified during this computation.
     */
    public List<RollbackData> computePendingBackupsAndRestores(int userId,
            ArrayList<String> pendingBackupPackages, Map<String, RestoreInfo> pendingRestores,
            List<RollbackData> availableRollbacks, List<RollbackInfo> recentRollbacks) {
        List<RollbackData> rd = new ArrayList<>();
        // First check with the list of available rollbacks to see whether there are any
        // pending backup operations that we've not managed to execute.
        for (RollbackData data : availableRollbacks) {
            for (PackageRollbackInfo info : data.packages) {
                final IntArray pendingBackupUsers = info.getPendingBackups();
                if (pendingBackupUsers != null) {
                    final int idx = pendingBackupUsers.indexOf(userId);
                    if (idx != -1) {
                        pendingBackupPackages.add(info.getPackageName());
                        pendingBackupUsers.remove(idx);
                        if (rd.indexOf(data) == -1) {
                            rd.add(data);
                        }
                    }
                }
            }
        }

        // Then check with the list of recently executed rollbacks to see whether there are
        // any rollback operations
        for (RollbackInfo data : recentRollbacks) {
            for (PackageRollbackInfo info : data.getPackages()) {
                final RestoreInfo ri = info.getRestoreInfo(userId);
                if (ri != null) {
                    if (pendingBackupPackages.contains(info.getPackageName())) {
                        // This implies that the user hasn't unlocked their device between
                        // the request to backup data for this user and the request to restore
                        // it, so we do nothing here.
                        pendingBackupPackages.remove(info.getPackageName());
                    } else {
                        pendingRestores.put(info.getPackageName(), ri);
                    }

                    info.removeRestoreInfo(ri);
                }
            }
        }

        return rd;
    }

    /**
     * Commits the list of pending backups and restores for a given {@code userId}. For the pending
     * backups updates corresponding {@code changedRollbackData} with a mapping from {@code userId}
     * to a inode of theirs CE user data snapshot.
     */
    public void commitPendingBackupAndRestoreForUser(int userId,
            ArrayList<String> pendingBackups, Map<String, RestoreInfo> pendingRestores,
            List<RollbackData> changedRollbackData) {
        if (!pendingBackups.isEmpty()) {
            for (String packageName : pendingBackups) {
                try {
                    long ceSnapshotInode = mInstaller.snapshotAppData(packageName, userId,
                            Installer.FLAG_STORAGE_CE);
                    for (RollbackData data : changedRollbackData) {
                        for (PackageRollbackInfo info : data.packages) {
                            if (info.getPackageName().equals(packageName)) {
                                info.putCeSnapshotInode(userId, ceSnapshotInode);
                            }
                        }
                    }
                } catch (InstallerException ie) {
                    Log.e(TAG, "Unable to create app data snapshot for: " + packageName
                            + ", userId: " + userId, ie);
                }
            }
        }

        // TODO(narayan): Should we perform the restore before the backup for packages that have
        // both backups and restores pending ? We could get into this case if we have a pending
        // restore from a rollback + a snapshot request from a new restore.
        if (!pendingRestores.isEmpty()) {
            for (String packageName : pendingRestores.keySet()) {
                try {
                    final RestoreInfo ri = pendingRestores.get(packageName);

                    // TODO(narayan): Verify that the user of "0" for ceDataInode is accurate
                    // here. We know that the user has unlocked (and that their CE data is
                    // available) so we shouldn't need to resort to the fallback path.
                    mInstaller.restoreAppDataSnapshot(packageName, ri.appId,
                            0 /* ceDataInode */, ri.seInfo, userId, Installer.FLAG_STORAGE_CE);
                } catch (InstallerException ie) {
                    Log.e(TAG, "Unable to restore app data snapshot for: " + packageName, ie);
                }
            }
        }
    }

    /**
     * @return {@code true} iff. {@code userId} is locked on an FBE device.
     */
    @VisibleForTesting
    public boolean isUserCredentialLocked(int userId) {
        return StorageManager.isFileEncryptedNativeOrEmulated()
                && !StorageManager.isUserKeyUnlocked(userId);
    }

    /**
     * Encapsulates a result of {@link #snapshotAppData} method.
     */
    public static final class SnapshotAppDataResult {

        /**
         * A list of users for which the snapshot is pending, usually because data for one or more
         * users is still credential locked.
         */
        public final IntArray pendingBackups;

        /**
         * A mapping between user and an inode of theirs CE data snapshot.
         */
        public final SparseLongArray ceSnapshotInodes;

        public SnapshotAppDataResult(IntArray pendingBackups, SparseLongArray ceSnapshotInodes) {
            this.pendingBackups = pendingBackups;
            this.ceSnapshotInodes = ceSnapshotInodes;
        }
    }
}
