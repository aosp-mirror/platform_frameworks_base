/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import androidx.annotation.NonNull;
import java.util.Arrays;

public class PackageUtil {

    private static final String TAG = InstallRepository.class.getSimpleName();
    private static final String DOWNLOADS_AUTHORITY = "downloads";

    /**
     * Determines if the UID belongs to the system downloads provider and returns the
     * {@link ApplicationInfo} of the provider
     *
     * @param uid UID of the caller
     * @return {@link ApplicationInfo} of the provider if a downloads provider exists, it is a
     *     system app, and its UID matches with the passed UID, null otherwise.
     */
    public static ApplicationInfo getSystemDownloadsProviderInfo(PackageManager pm, int uid) {
        final ProviderInfo providerInfo = pm.resolveContentProvider(
            DOWNLOADS_AUTHORITY, 0);
        if (providerInfo == null) {
            // There seems to be no currently enabled downloads provider on the system.
            return null;
        }
        ApplicationInfo appInfo = providerInfo.applicationInfo;
        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && uid == appInfo.uid) {
            return appInfo;
        }
        return null;
    }

    /**
     * Get the maximum target sdk for a UID.
     *
     * @param context The context to use
     * @param uid The UID requesting the install/uninstall
     * @return The maximum target SDK or -1 if the uid does not match any packages.
     */
    public static int getMaxTargetSdkVersionForUid(@NonNull Context context, int uid) {
        PackageManager pm = context.getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        int targetSdkVersion = -1;
        if (packages != null) {
            for (String packageName : packages) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                    targetSdkVersion = Math.max(targetSdkVersion, info.targetSdkVersion);
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore and try the next package
                }
            }
        }
        return targetSdkVersion;
    }

    /**
     * @param context the {@link Context} object
     * @param permission the permission name to check
     * @param callingUid the UID of the caller who's permission is being checked
     * @return {@code true} if the callingUid is granted the said permission
     */
    public static boolean isPermissionGranted(Context context, String permission, int callingUid) {
        return context.checkPermission(permission, -1, callingUid)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @param pm the {@link PackageManager} object
     * @param permission the permission name to check
     * @param packageName the name of the package who's permission is being checked
     * @return {@code true} if the package is granted the said permission
     */
    public static boolean isPermissionGranted(PackageManager pm, String permission,
        String packageName) {
        return pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @param context the {@link Context} object
     * @param callingUid the UID of the caller who's permission is being checked
     * @param originatingUid the UID from where install is being originated. This could be same as
     * callingUid or it will be the UID of the package performing a session based install
     * @param isTrustedSource whether install request is coming from a privileged app or an app that
     * has {@link Manifest.permission.INSTALL_PACKAGES} permission granted
     * @return {@code true} if the package is granted the said permission
     */
    public static boolean isInstallPermissionGrantedOrRequested(Context context, int callingUid,
        int originatingUid, boolean isTrustedSource) {
        boolean isDocumentsManager =
            isPermissionGranted(context, Manifest.permission.MANAGE_DOCUMENTS, callingUid);
        boolean isSystemDownloadsProvider =
            getSystemDownloadsProviderInfo(context.getPackageManager(), callingUid) != null;

        if (!isTrustedSource && !isSystemDownloadsProvider && !isDocumentsManager) {

            final int targetSdkVersion = getMaxTargetSdkVersionForUid(context, originatingUid);
            if (targetSdkVersion < 0) {
                // Invalid originating uid supplied. Abort install.
                Log.w(TAG, "Cannot get target sdk version for uid " + originatingUid);
                return false;
            } else if (targetSdkVersion >= Build.VERSION_CODES.O
                && !isUidRequestingPermission(context.getPackageManager(), originatingUid,
                Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
                Log.e(TAG, "Requesting uid " + originatingUid + " needs to declare permission "
                    + Manifest.permission.REQUEST_INSTALL_PACKAGES);
                return false;
            }
        }
        return true;
    }

    /**
     * @param pm the {@link PackageManager} object
     * @param uid the UID of the caller who's permission is being checked
     * @param permission the permission name to check
     * @return {@code true} if the caller is requesting the said permission in its Manifest
     */
    public static boolean isUidRequestingPermission(PackageManager pm, int uid, String permission) {
        final String[] packageNames = pm.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        for (final String packageName : packageNames) {
            final PackageInfo packageInfo;
            try {
                packageInfo = pm.getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore and try the next package
                continue;
            }
            if (packageInfo.requestedPermissions != null
                && Arrays.asList(packageInfo.requestedPermissions).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param pi the {@link PackageInstaller} object to use
     * @param originatingUid the UID of the package performing a session based install
     * @param sessionId ID of the install session
     * @return {@code true} if the caller is the session owner
     */
    public static boolean isCallerSessionOwner(PackageInstaller pi, int originatingUid,
        int sessionId) {
        if (sessionId == SessionInfo.INVALID_ID) {
            return false;
        }
        if (originatingUid == Process.ROOT_UID) {
            return true;
        }
        PackageInstaller.SessionInfo sessionInfo = pi.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            return false;
        }
        int installerUid = sessionInfo.getInstallerUid();
        return originatingUid == installerUid;
    }
}
