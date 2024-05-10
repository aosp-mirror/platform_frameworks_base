/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.tare;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.Context;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;

import com.android.internal.util.ArrayUtils;

/** POJO to cache only the information about installed packages that TARE cares about. */
class InstalledPackageInfo {
    static final int NO_UID = -1;

    /**
     * Flags to use when querying for front door activities. Disabled components are included
     * are included for completeness since the app can enable them at any time.
     */
    private static final int HEADLESS_APP_QUERY_FLAGS = PackageManager.MATCH_DIRECT_BOOT_AWARE
            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
            | PackageManager.MATCH_DISABLED_COMPONENTS;

    public final int uid;
    public final String packageName;
    public final boolean hasCode;
    /**
     * Whether the app is a system app that is "headless." Headless in this context means that
     * the app doesn't have any "front door" activities --- activities that would show in the
     * launcher.
     */
    public final boolean isHeadlessSystemApp;
    public final boolean isSystemInstaller;
    @Nullable
    public final String installerPackageName;

    InstalledPackageInfo(@NonNull Context context, @UserIdInt int userId,
            @NonNull PackageInfo packageInfo) {
        final ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        uid = applicationInfo == null ? NO_UID : applicationInfo.uid;
        packageName = packageInfo.packageName;
        hasCode = applicationInfo != null && applicationInfo.hasCode();

        final PackageManager packageManager = context.getPackageManager();
        final Intent frontDoorActivityIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        isHeadlessSystemApp = applicationInfo != null
                && (applicationInfo.isSystemApp() || applicationInfo.isUpdatedSystemApp())
                && ArrayUtils.isEmpty(
                        packageManager.queryIntentActivitiesAsUser(
                                frontDoorActivityIntent, HEADLESS_APP_QUERY_FLAGS, userId));

        isSystemInstaller = applicationInfo != null
                && ArrayUtils.indexOf(
                packageInfo.requestedPermissions, Manifest.permission.INSTALL_PACKAGES) >= 0
                && PackageManager.PERMISSION_GRANTED
                == PermissionChecker.checkPermissionForPreflight(context,
                Manifest.permission.INSTALL_PACKAGES, PermissionChecker.PID_UNKNOWN,
                applicationInfo.uid, packageName);
        InstallSourceInfo installSourceInfo = null;
        try {
            installSourceInfo = AppGlobals.getPackageManager().getInstallSourceInfo(packageName,
                    userId);
        } catch (RemoteException e) {
            // Shouldn't happen.
        }
        installerPackageName =
                installSourceInfo == null ? null : installSourceInfo.getInstallingPackageName();
    }

    @Override
    public String toString() {
        return "IPO{"
                + "uid=" + uid
                + ", pkgName=" + packageName
                + (hasCode ? " HAS_CODE" : "")
                + (isHeadlessSystemApp ? " HEADLESS_SYSTEM" : "")
                + (isSystemInstaller ? " SYSTEM_INSTALLER" : "")
                + ", installer=" + installerPackageName
                + '}';
    }
}
