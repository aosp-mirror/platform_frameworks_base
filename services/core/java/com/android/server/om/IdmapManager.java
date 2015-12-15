/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.Installer;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Handle the creation and deletion of idmap files.
 *
 * The actual work is performed by the idmap binary, launched through Installer
 * and installd.
 *
 * Note: this class is subclassed in the OMS unit tests, and hence not marked as final.
 */
class IdmapManager {
    private final Installer mInstaller;

    IdmapManager(final Installer installer) {
        mInstaller = installer;
    }

    boolean createIdmap(@NonNull final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        if (DEBUG) {
            Slog.d(TAG, "create idmap for " + targetPackage.packageName + " and "
                    + overlayPackage.packageName);
        }
        final int sharedGid = UserHandle.getSharedAppGid(targetPackage.applicationInfo.uid);
        final String targetPath = targetPackage.applicationInfo.getBaseCodePath();
        final String overlayPath = overlayPackage.applicationInfo.getBaseCodePath();
        try {
            mInstaller.idmap(targetPath, overlayPath, sharedGid);
        } catch (InstallerException e) {
            Slog.w(TAG, "failed to generate idmap for " + targetPath + " and "
                    + overlayPath + ": " + e.getMessage());
            return false;
        }
        return true;
    }

    boolean removeIdmap(@NonNull final OverlayInfo oi, final int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        if (DEBUG) {
            Slog.d(TAG, "remove idmap for " + oi.baseCodePath);
        }
        try {
            mInstaller.removeIdmap(oi.baseCodePath);
        } catch (InstallerException e) {
            Slog.w(TAG, "failed to remove idmap for " + oi.baseCodePath + ": " + e.getMessage());
            return false;
        }
        return true;
    }

    boolean idmapExists(@NonNull final OverlayInfo oi) {
        // unused OverlayInfo.userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        return new File(getIdmapPath(oi.baseCodePath)).isFile();
    }

    boolean idmapExists(@NonNull final PackageInfo overlayPackage, final int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        return new File(getIdmapPath(overlayPackage.applicationInfo.getBaseCodePath())).isFile();
    }

    boolean isDangerous(@NonNull final PackageInfo overlayPackage, final int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        return isDangerous(getIdmapPath(overlayPackage.applicationInfo.getBaseCodePath()));
    }

    private String getIdmapPath(@NonNull final String baseCodePath) {
        final StringBuilder sb = new StringBuilder("/data/resource-cache/");
        sb.append(baseCodePath.substring(1).replace('/', '@'));
        sb.append("@idmap");
        return sb.toString();
    }

    private boolean isDangerous(@NonNull final String idmapPath) {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(idmapPath))) {
            final int magic = dis.readInt();
            final int version = dis.readInt();
            final int dangerous = dis.readInt();
            return dangerous != 0;
        } catch (IOException e) {
            return true;
        }
    }
}
