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
 * limitations under the License.
 */

package com.android.server.pm.dex;

import static com.android.server.pm.dex.PackageDynamicCodeLoading.FILE_TYPE_DEX;
import static com.android.server.pm.dex.PackageDynamicCodeLoading.FILE_TYPE_NATIVE;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.FileUtils;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.EventLog;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.dex.PackageDynamicCodeLoading.DynamicCodeFile;
import com.android.server.pm.dex.PackageDynamicCodeLoading.PackageDynamicCode;

import libcore.util.HexEncoding;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * This class is responsible for logging data about secondary dex files and native code executed
 * from an app's private directory. The data logged includes hashes of the name and content of each
 * file.
 */
public class DynamicCodeLogger {
    private static final String TAG = "DynamicCodeLogger";

    // Event log tag & subtags used for SafetyNet logging of dynamic code loading (DCL) -
    // see b/63927552.
    private static final int SNET_TAG = 0x534e4554;
    private static final String DCL_DEX_SUBTAG = "dcl";
    private static final String DCL_NATIVE_SUBTAG = "dcln";

    private final IPackageManager mPackageManager;
    private final PackageDynamicCodeLoading mPackageDynamicCodeLoading;
    private final Installer mInstaller;

    DynamicCodeLogger(IPackageManager pms, Installer installer) {
        this(pms, installer, new PackageDynamicCodeLoading());
    }

    @VisibleForTesting
    DynamicCodeLogger(IPackageManager pms, Installer installer,
            PackageDynamicCodeLoading packageDynamicCodeLoading) {
        mPackageManager = pms;
        mPackageDynamicCodeLoading = packageDynamicCodeLoading;
        mInstaller = installer;
    }

    public Set<String> getAllPackagesWithDynamicCodeLoading() {
        return mPackageDynamicCodeLoading.getAllPackagesWithDynamicCodeLoading();
    }

    /**
     * Write information about code dynamically loaded by {@code packageName} to the event log.
     */
    public void logDynamicCodeLoading(String packageName) {
        PackageDynamicCode info = getPackageDynamicCodeInfo(packageName);
        if (info == null) {
            return;
        }

        SparseArray<ApplicationInfo> appInfoByUser = new SparseArray<>();
        boolean needWrite = false;

        for (Map.Entry<String, DynamicCodeFile> fileEntry : info.mFileUsageMap.entrySet()) {
            String filePath = fileEntry.getKey();
            DynamicCodeFile fileInfo = fileEntry.getValue();
            int userId = fileInfo.mUserId;

            int index = appInfoByUser.indexOfKey(userId);
            ApplicationInfo appInfo;
            if (index >= 0) {
                appInfo = appInfoByUser.get(userId);
            } else {
                appInfo = null;

                try {
                    PackageInfo ownerInfo =
                            mPackageManager.getPackageInfo(packageName, /*flags*/ 0, userId);
                    appInfo = ownerInfo == null ? null : ownerInfo.applicationInfo;
                } catch (RemoteException ignored) {
                    // Can't happen, we're local.
                }
                appInfoByUser.put(userId, appInfo);
                if (appInfo == null) {
                    Slog.d(TAG, "Could not find package " + packageName + " for user " + userId);
                    // Package has probably been uninstalled for user.
                    needWrite |= mPackageDynamicCodeLoading.removeUserPackage(packageName, userId);
                }
            }

            if (appInfo == null) {
                continue;
            }

            int storageFlags;

            if (fileIsUnder(filePath, appInfo.credentialProtectedDataDir)) {
                storageFlags = StorageManager.FLAG_STORAGE_CE;
            } else if (fileIsUnder(filePath, appInfo.deviceProtectedDataDir)) {
                storageFlags = StorageManager.FLAG_STORAGE_DE;
            } else {
                Slog.e(TAG, "Could not infer CE/DE storage for path " + filePath);
                needWrite |= mPackageDynamicCodeLoading.removeFile(packageName, filePath, userId);
                continue;
            }

            byte[] hash = null;
            try {
                // Note that we do not take the install lock here. Hashing should never interfere
                // with app update/compilation/removal. We may get anomalous results if a file
                // changes while we hash it, but that can happen anyway and is harmless for our
                // purposes.
                hash = mInstaller.hashSecondaryDexFile(filePath, packageName, appInfo.uid,
                        appInfo.volumeUuid, storageFlags);
            } catch (InstallerException e) {
                Slog.e(TAG, "Got InstallerException when hashing file " + filePath
                        + ": " + e.getMessage());
            }

            String subtag = fileInfo.mFileType == FILE_TYPE_DEX
                    ? DCL_DEX_SUBTAG
                    : DCL_NATIVE_SUBTAG;
            String fileName = new File(filePath).getName();
            String message = PackageUtils.computeSha256Digest(fileName.getBytes());

            // Valid SHA256 will be 256 bits, 32 bytes.
            if (hash != null && hash.length == 32) {
                message = message + ' ' + HexEncoding.encodeToString(hash);
            } else {
                Slog.d(TAG, "Got no hash for " + filePath);
                // File has probably been deleted.
                needWrite |= mPackageDynamicCodeLoading.removeFile(packageName, filePath, userId);
            }

            for (String loadingPackageName : fileInfo.mLoadingPackages) {
                int loadingUid = -1;
                if (loadingPackageName.equals(packageName)) {
                    loadingUid = appInfo.uid;
                } else {
                    try {
                        loadingUid = mPackageManager.getPackageUid(loadingPackageName, /*flags*/ 0,
                                userId);
                    } catch (RemoteException ignored) {
                        // Can't happen, we're local.
                    }
                }

                if (loadingUid != -1) {
                    writeDclEvent(subtag, loadingUid, message);
                }
            }
        }

        if (needWrite) {
            mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    private boolean fileIsUnder(String filePath, String directoryPath) {
        if (directoryPath == null) {
            return false;
        }

        try {
            return FileUtils.contains(new File(directoryPath).getCanonicalPath(),
                    new File(filePath).getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    @VisibleForTesting
    PackageDynamicCode getPackageDynamicCodeInfo(String packageName) {
        return mPackageDynamicCodeLoading.getPackageDynamicCodeInfo(packageName);
    }

    @VisibleForTesting
    void writeDclEvent(String subtag, int uid, String message) {
        EventLog.writeEvent(SNET_TAG, subtag, uid, message);
    }

    void recordDex(int loaderUserId, String dexPath, String owningPackageName,
            String loadingPackageName) {
        if (mPackageDynamicCodeLoading.record(owningPackageName, dexPath,
                FILE_TYPE_DEX, loaderUserId, loadingPackageName)) {
            mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    /**
     * Record that an app running in the specified uid has executed native code from the file at
     * {@param path}.
     */
    public void recordNative(int loadingUid, String path) {
        String[] packages;
        try {
            packages = mPackageManager.getPackagesForUid(loadingUid);
            if (packages == null || packages.length == 0) {
                return;
            }
        } catch (RemoteException e) {
            // Can't happen, we're local.
            return;
        }

        String loadingPackageName = packages[0];
        int loadingUserId = UserHandle.getUserId(loadingUid);

        if (mPackageDynamicCodeLoading.record(loadingPackageName, path,
                FILE_TYPE_NATIVE, loadingUserId, loadingPackageName)) {
            mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    void clear() {
        mPackageDynamicCodeLoading.clear();
    }

    void removePackage(String packageName) {
        if (mPackageDynamicCodeLoading.removePackage(packageName)) {
            mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    void removeUserPackage(String packageName, int userId) {
        if (mPackageDynamicCodeLoading.removeUserPackage(packageName, userId)) {
            mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    void readAndSync(Map<String, Set<Integer>> packageToUsersMap) {
        mPackageDynamicCodeLoading.read();
        mPackageDynamicCodeLoading.syncData(packageToUsersMap);
    }

    void writeNow() {
        mPackageDynamicCodeLoading.writeNow();
    }
}
