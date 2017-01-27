/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.pm;

import android.content.Context;
import android.os.Environment;
import android.os.FileUtils;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import java.util.Objects;

import static com.android.server.pm.PackageManagerService.logCriticalInfo;

/**
 * Helper class for preparing and destroying user storage
 */
class UserDataPreparer {
    private final Object mInstallLock;
    private final Context mContext;
    private final boolean mOnlyCore;
    private final Installer mInstaller;

    UserDataPreparer(Installer installer, Object installLock, Context context, boolean onlyCore) {
        mInstallLock = installLock;
        mContext = context;
        mOnlyCore = onlyCore;
        mInstaller = installer;
    }

    /**
     * Prepare storage areas for given user on all mounted devices.
     */
    void prepareUserData(int userId, int userSerial, int flags) {
        synchronized (mInstallLock) {
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
                final String volumeUuid = vol.getFsUuid();
                prepareUserDataLI(volumeUuid, userId, userSerial, flags, true);
            }
        }
    }

    private void prepareUserDataLI(String volumeUuid, int userId, int userSerial, int flags,
            boolean allowRecover) {
        // Prepare storage and verify that serial numbers are consistent; if
        // there's a mismatch we need to destroy to avoid leaking data
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        try {
            storage.prepareUserStorage(volumeUuid, userId, userSerial, flags);

            if ((flags & StorageManager.FLAG_STORAGE_DE) != 0 && !mOnlyCore) {
                UserManagerService.enforceSerialNumber(
                        Environment.getDataUserDeDirectory(volumeUuid, userId), userSerial);
                if (Objects.equals(volumeUuid, StorageManager.UUID_PRIVATE_INTERNAL)) {
                    UserManagerService.enforceSerialNumber(
                            Environment.getDataSystemDeDirectory(userId), userSerial);
                }
            }
            if ((flags & StorageManager.FLAG_STORAGE_CE) != 0 && !mOnlyCore) {
                UserManagerService.enforceSerialNumber(
                        Environment.getDataUserCeDirectory(volumeUuid, userId), userSerial);
                if (Objects.equals(volumeUuid, StorageManager.UUID_PRIVATE_INTERNAL)) {
                    UserManagerService.enforceSerialNumber(
                            Environment.getDataSystemCeDirectory(userId), userSerial);
                }
            }

            mInstaller.createUserData(volumeUuid, userId, userSerial, flags);
        } catch (Exception e) {
            logCriticalInfo(Log.WARN, "Destroying user " + userId + " on volume " + volumeUuid
                    + " because we failed to prepare: " + e);
            destroyUserDataLI(volumeUuid, userId,
                    StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);

            if (allowRecover) {
                // Try one last time; if we fail again we're really in trouble
                prepareUserDataLI(volumeUuid, userId, userSerial, flags, false);
            }
        }
    }

    /**
     * Destroy storage areas for given user on all mounted devices.
     */
    void destroyUserData(int userId, int flags) {
        synchronized (mInstallLock) {
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
                final String volumeUuid = vol.getFsUuid();
                destroyUserDataLI(volumeUuid, userId, flags);
            }
        }
    }

    void destroyUserDataLI(String volumeUuid, int userId, int flags) {
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        try {
            // Clean up app data, profile data, and media data
            mInstaller.destroyUserData(volumeUuid, userId, flags);

            // Clean up system data
            if (Objects.equals(volumeUuid, StorageManager.UUID_PRIVATE_INTERNAL)) {
                if ((flags & StorageManager.FLAG_STORAGE_DE) != 0) {
                    FileUtils.deleteContentsAndDir(Environment.getUserSystemDirectory(userId));
                    FileUtils.deleteContentsAndDir(Environment.getDataSystemDeDirectory(userId));
                    FileUtils.deleteContentsAndDir(Environment.getDataMiscDeDirectory(userId));
                }
                if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
                    FileUtils.deleteContentsAndDir(Environment.getDataSystemCeDirectory(userId));
                    FileUtils.deleteContentsAndDir(Environment.getDataMiscCeDirectory(userId));
                }
            }

            // Data with special labels is now gone, so finish the job
            storage.destroyUserStorage(volumeUuid, userId, flags);

        } catch (Exception e) {
            logCriticalInfo(Log.WARN,
                    "Failed to destroy user " + userId + " on volume " + volumeUuid + ": " + e);
        }
    }

}
