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

import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.os.FileUtils;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Helper class for preparing and destroying user storage
 */
class UserDataPreparer {
    private static final String TAG = "UserDataPreparer";
    private static final String XATTR_SERIAL = "user.serial";

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
                enforceSerialNumber(getDataUserDeDirectory(volumeUuid, userId), userSerial);
                if (Objects.equals(volumeUuid, StorageManager.UUID_PRIVATE_INTERNAL)) {
                    enforceSerialNumber(getDataSystemDeDirectory(userId), userSerial);
                }
            }
            if ((flags & StorageManager.FLAG_STORAGE_CE) != 0 && !mOnlyCore) {
                enforceSerialNumber(getDataUserCeDirectory(volumeUuid, userId), userSerial);
                if (Objects.equals(volumeUuid, StorageManager.UUID_PRIVATE_INTERNAL)) {
                    enforceSerialNumber(getDataSystemCeDirectory(userId), userSerial);
                }
            }

            mInstaller.createUserData(volumeUuid, userId, userSerial, flags);

            // CE storage is available after they are prepared.
            if ((flags & StorageManager.FLAG_STORAGE_CE) != 0 &&
                    (userId == UserHandle.USER_SYSTEM)) {
                String propertyName = "sys.user." + userId + ".ce_available";
                Slog.d(TAG, "Setting property: " + propertyName + "=true");
                SystemProperties.set(propertyName, "true");
            }
        } catch (Exception e) {
            logCriticalInfo(Log.WARN, "Destroying user " + userId + " on volume " + volumeUuid
                    + " because we failed to prepare: " + e);
            destroyUserDataLI(volumeUuid, userId, flags);

            if (allowRecover) {
                // Try one last time; if we fail again we're really in trouble
                prepareUserDataLI(volumeUuid, userId, userSerial,
                    flags | StorageManager.FLAG_STORAGE_DE, false);
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
                    FileUtils.deleteContentsAndDir(getUserSystemDirectory(userId));
                    FileUtils.deleteContentsAndDir(getDataSystemDeDirectory(userId));
                }
                if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
                    FileUtils.deleteContentsAndDir(getDataSystemCeDirectory(userId));
                }
            }

            // Data with special labels is now gone, so finish the job
            storage.destroyUserStorage(volumeUuid, userId, flags);

        } catch (Exception e) {
            logCriticalInfo(Log.WARN,
                    "Failed to destroy user " + userId + " on volume " + volumeUuid + ": " + e);
        }
    }

    /**
     * Examine all users present on given mounted volume, and destroy data
     * belonging to users that are no longer valid, or whose user ID has been
     * recycled.
     */
    void reconcileUsers(String volumeUuid, List<UserInfo> validUsersList) {
        final List<File> files = new ArrayList<>();
        Collections.addAll(files, FileUtils
                .listFilesOrEmpty(Environment.getDataUserDeDirectory(volumeUuid)));
        Collections.addAll(files, FileUtils
                .listFilesOrEmpty(Environment.getDataUserCeDirectory(volumeUuid)));
        Collections.addAll(files, FileUtils
                .listFilesOrEmpty(Environment.getDataSystemDeDirectory()));
        Collections.addAll(files, FileUtils
                .listFilesOrEmpty(Environment.getDataSystemCeDirectory()));
        Collections.addAll(files, FileUtils
                .listFilesOrEmpty(Environment.getDataMiscCeDirectory()));
        reconcileUsers(volumeUuid, validUsersList, files);
    }

    @VisibleForTesting
    void reconcileUsers(String volumeUuid, List<UserInfo> validUsersList, List<File> files) {
        final int userCount = validUsersList.size();
        SparseArray<UserInfo> users = new SparseArray<>(userCount);
        for (int i = 0; i < userCount; i++) {
            UserInfo user = validUsersList.get(i);
            users.put(user.id, user);
        }
        for (File file : files) {
            if (!file.isDirectory()) {
                continue;
            }

            final int userId;
            final UserInfo info;
            try {
                userId = Integer.parseInt(file.getName());
                info = users.get(userId);
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Invalid user directory " + file);
                continue;
            }

            boolean destroyUser = false;
            if (info == null) {
                logCriticalInfo(Log.WARN, "Destroying user directory " + file
                        + " because no matching user was found");
                destroyUser = true;
            } else if (!mOnlyCore) {
                try {
                    enforceSerialNumber(file, info.serialNumber);
                } catch (IOException e) {
                    logCriticalInfo(Log.WARN, "Destroying user directory " + file
                            + " because we failed to enforce serial number: " + e);
                    destroyUser = true;
                }
            }

            if (destroyUser) {
                synchronized (mInstallLock) {
                    destroyUserDataLI(volumeUuid, userId,
                            StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);
                }
            }
        }
    }

    @VisibleForTesting
    protected File getDataMiscCeDirectory(int userId) {
        return Environment.getDataMiscCeDirectory(userId);
    }

    @VisibleForTesting
    protected File getDataSystemCeDirectory(int userId) {
        return Environment.getDataSystemCeDirectory(userId);
    }

    @VisibleForTesting
    protected File getDataMiscDeDirectory(int userId) {
        return Environment.getDataMiscDeDirectory(userId);
    }

    @VisibleForTesting
    protected File getUserSystemDirectory(int userId) {
        return Environment.getUserSystemDirectory(userId);
    }

    @VisibleForTesting
    protected File getDataUserCeDirectory(String volumeUuid, int userId) {
        return Environment.getDataUserCeDirectory(volumeUuid, userId);
    }

    @VisibleForTesting
    protected File getDataSystemDeDirectory(int userId) {
        return Environment.getDataSystemDeDirectory(userId);
    }

    @VisibleForTesting
    protected File getDataUserDeDirectory(String volumeUuid, int userId) {
        return Environment.getDataUserDeDirectory(volumeUuid, userId);
    }

    @VisibleForTesting
    protected boolean isFileEncryptedEmulatedOnly() {
        return StorageManager.isFileEncryptedEmulatedOnly();
    }

    /**
     * Enforce that serial number stored in user directory inode matches the
     * given expected value. Gracefully sets the serial number if currently
     * undefined.
     *
     * @throws IOException when problem extracting serial number, or serial
     *             number is mismatched.
     */
    void enforceSerialNumber(File file, int serialNumber) throws IOException {
        if (isFileEncryptedEmulatedOnly()) {
            // When we're emulating FBE, the directory may have been chmod
            // 000'ed, meaning we can't read the serial number to enforce it;
            // instead of destroying the user, just log a warning.
            Slog.w(TAG, "Device is emulating FBE; assuming current serial number is valid");
            return;
        }

        final int foundSerial = getSerialNumber(file);
        Slog.v(TAG, "Found " + file + " with serial number " + foundSerial);

        if (foundSerial == -1) {
            Slog.d(TAG, "Serial number missing on " + file + "; assuming current is valid");
            try {
                setSerialNumber(file, serialNumber);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to set serial number on " + file, e);
            }

        } else if (foundSerial != serialNumber) {
            throw new IOException("Found serial number " + foundSerial
                    + " doesn't match expected " + serialNumber);
        }
    }

    /**
     * Set serial number stored in user directory inode.
     *
     * @throws IOException if serial number was already set
     */
    private static void setSerialNumber(File file, int serialNumber) throws IOException {
        try {
            final byte[] buf = Integer.toString(serialNumber).getBytes(StandardCharsets.UTF_8);
            Os.setxattr(file.getAbsolutePath(), XATTR_SERIAL, buf, OsConstants.XATTR_CREATE);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Return serial number stored in user directory inode.
     *
     * @return parsed serial number, or -1 if not set
     */
    @VisibleForTesting
    static int getSerialNumber(File file) throws IOException {
        try {
            final byte[] buf = Os.getxattr(file.getAbsolutePath(), XATTR_SERIAL);
            final String serial = new String(buf);
            try {
                return Integer.parseInt(serial);
            } catch (NumberFormatException e) {
                throw new IOException("Bad serial number: " + serial);
            }
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENODATA) {
                return -1;
            } else {
                throw e.rethrowAsIOException();
            }
        }
    }

}
