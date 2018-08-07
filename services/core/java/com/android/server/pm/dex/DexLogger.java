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

package com.android.server.pm.dex;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.RemoteException;

import android.util.ArraySet;
import android.util.ByteStringUtils;
import android.util.EventLog;
import android.util.PackageUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.io.File;
import java.util.Set;

import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;

/**
 * This class is responsible for logging data about secondary dex files.
 * The data logged includes hashes of the name and content of each file.
 */
public class DexLogger implements DexManager.Listener {
    private static final String TAG = "DexLogger";

    // Event log tag & subtag used for SafetyNet logging of dynamic
    // code loading (DCL) - see b/63927552.
    private static final int SNET_TAG = 0x534e4554;
    private static final String DCL_SUBTAG = "dcl";

    private final IPackageManager mPackageManager;
    private final Object mInstallLock;
    @GuardedBy("mInstallLock")
    private final Installer mInstaller;

    public static DexManager.Listener getListener(IPackageManager pms,
            Installer installer, Object installLock) {
        return new DexLogger(pms, installer, installLock);
    }

    @VisibleForTesting
    /*package*/ DexLogger(IPackageManager pms, Installer installer, Object installLock) {
        mPackageManager = pms;
        mInstaller = installer;
        mInstallLock = installLock;
    }

    /**
     * Compute and log hashes of the name and content of a secondary dex file.
     */
    @Override
    public void onReconcileSecondaryDexFile(ApplicationInfo appInfo, DexUseInfo dexUseInfo,
            String dexPath, int storageFlags) {
        int ownerUid = appInfo.uid;

        byte[] hash = null;
        synchronized(mInstallLock) {
            try {
                hash = mInstaller.hashSecondaryDexFile(dexPath, appInfo.packageName,
                        ownerUid, appInfo.volumeUuid, storageFlags);
            } catch (InstallerException e) {
                Slog.e(TAG, "Got InstallerException when hashing dex " + dexPath +
                        " : " + e.getMessage());
            }
        }
        if (hash == null) {
            return;
        }

        String dexFileName = new File(dexPath).getName();
        String message = PackageUtils.computeSha256Digest(dexFileName.getBytes());
        // Valid SHA256 will be 256 bits, 32 bytes.
        if (hash.length == 32) {
            message = message + ' ' + ByteStringUtils.toHexString(hash);
        }

        writeDclEvent(ownerUid, message);

        if (dexUseInfo.isUsedByOtherApps()) {
            Set<String> otherPackages = dexUseInfo.getLoadingPackages();
            Set<Integer> otherUids = new ArraySet<>(otherPackages.size());
            for (String otherPackageName : otherPackages) {
                try {
                    int otherUid = mPackageManager.getPackageUid(
                        otherPackageName, /*flags*/0, dexUseInfo.getOwnerUserId());
                    if (otherUid != -1 && otherUid != ownerUid) {
                        otherUids.add(otherUid);
                    }
                } catch (RemoteException ignore) {
                    // Can't happen, we're local.
                }
            }
            for (int otherUid : otherUids) {
                writeDclEvent(otherUid, message);
            }
        }
    }

    @VisibleForTesting
    /*package*/ void writeDclEvent(int uid, String message) {
        EventLog.writeEvent(SNET_TAG, DCL_SUBTAG, uid, message);
    }
}
