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

package com.android.packageinstaller.permission.service;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.permission.RuntimePermissionPresentationInfo;
import android.permissionpresenterservice.RuntimePermissionPresenterService;
import android.util.Log;

import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.utils.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Service that provides presentation information for runtime permissions.
 */
public final class RuntimePermissionPresenterServiceImpl extends RuntimePermissionPresenterService {
    private static final String LOG_TAG = "PermissionPresenter";

    @Override
    public List<RuntimePermissionPresentationInfo> onGetAppPermissions(String packageName) {
        final PackageInfo packageInfo;
        try {
            packageInfo = getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Error getting package:" + packageName, e);
            return null;
        }

        List<RuntimePermissionPresentationInfo> permissions = new ArrayList<>();

        AppPermissions appPermissions = new AppPermissions(this, packageInfo, false, null);
        for (AppPermissionGroup group : appPermissions.getPermissionGroups()) {
            if (Utils.shouldShowPermission(group)) {
                final boolean granted = group.areRuntimePermissionsGranted();
                final boolean standard = Utils.OS_PKG.equals(group.getDeclaringPackage());
                RuntimePermissionPresentationInfo permission =
                        new RuntimePermissionPresentationInfo(group.getLabel(),
                                granted, standard);
                permissions.add(permission);
            }
        }

        return permissions;
    }

    @Override
    public void onRevokeRuntimePermission(String packageName, String permissionName) {
        try {
            final PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
            final AppPermissions appPermissions = new AppPermissions(this, packageInfo, false,
                    null);

            final AppPermissionGroup appPermissionGroup = appPermissions.getGroupForPermission(
                    permissionName);

            if (appPermissionGroup != null) {
                appPermissionGroup.revokeRuntimePermissions(false);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Error getting package:" + packageName, e);
        }
    }
}
