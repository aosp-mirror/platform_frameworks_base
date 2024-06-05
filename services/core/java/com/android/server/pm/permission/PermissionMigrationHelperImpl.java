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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.content.pm.PackageManagerInternal;
import android.util.ArrayMap;
import android.util.Log;

import com.android.permission.persistence.RuntimePermissionsState;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.SharedUserApi;

import java.util.List;
import java.util.Map;

/**
 * Provider of legacy permissions data for new permission subsystem.
 *
 * @hide
 */
public class PermissionMigrationHelperImpl implements PermissionMigrationHelper {
    private static final String LOG_TAG = PermissionMigrationHelperImpl.class.getSimpleName();

    @Override
    public boolean hasLegacyPermission() {
        PackageManagerInternal packageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        LegacyPermissionSettings legacySettings = packageManagerInternal.getLegacyPermissions();
        return !(legacySettings.getPermissions().isEmpty()
                && legacySettings.getPermissionTrees().isEmpty());
    }

    /**
     * @return legacy permission definitions.
     */
    @NonNull
    public Map<String, LegacyPermission> getLegacyPermissions() {
        PackageManagerInternal mPackageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        return toLegacyPermissions(
                mPackageManagerInternal.getLegacyPermissions().getPermissions());
    }

    /**
     * @return legacy permission trees.
     */
    @NonNull
    public Map<String, LegacyPermission> getLegacyPermissionTrees() {
        PackageManagerInternal mPackageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        return toLegacyPermissions(
                mPackageManagerInternal.getLegacyPermissions().getPermissionTrees());
    }

    @NonNull
    private Map<String, LegacyPermission> toLegacyPermissions(
            List<com.android.server.pm.permission.LegacyPermission> legacyPermissions) {
        Map<String, LegacyPermission> permissions = new ArrayMap<>();
        legacyPermissions.forEach(legacyPermission -> {
            LegacyPermission permission = new LegacyPermission(legacyPermission.getPermissionInfo(),
                    legacyPermission.getType());
            permissions.put(legacyPermission.getPermissionInfo().name, permission);
        });

        return permissions;
    }

    /**
     * @return permissions state for a user, i.e. map of appId to map of permission name and state.
     */
    @NonNull
    public Map<Integer, Map<String, LegacyPermissionState>> getLegacyPermissionStates(int userId) {
        PackageManagerInternal mPackageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        Map<Integer, Map<String, LegacyPermissionState>> appIdPermissionStates = new ArrayMap<>();

        RuntimePermissionsState legacyState =
                (RuntimePermissionsState) mPackageManagerInternal.getLegacyPermissionsState(userId);
        PackageManagerLocal packageManagerLocal =
                LocalManagerRegistry.getManager(PackageManagerLocal.class);

        try (PackageManagerLocal.UnfilteredSnapshot snapshot =
                     packageManagerLocal.withUnfilteredSnapshot()) {
            Map<String, PackageState> packageStates = snapshot.getPackageStates();
            legacyState.getPackagePermissions().forEach((packageName, permissionStates) -> {
                if (!permissionStates.isEmpty()) {
                    PackageState packageState = packageStates.get(packageName);
                    if (packageState != null) {
                        int appId = packageState.getAppId();
                        appIdPermissionStates.put(appId,
                                toLegacyPermissionStates(permissionStates));
                    } else {
                        Log.w(LOG_TAG, "Package " + packageName + " not found.");
                    }
                }
            });

            Map<String, SharedUserApi> sharedUsers = snapshot.getSharedUsers();
            legacyState.getSharedUserPermissions().forEach((sharedUserName, permissionStates) -> {
                if (!permissionStates.isEmpty()) {
                    SharedUserApi sharedUser = sharedUsers.get(sharedUserName);
                    if (sharedUser != null) {
                        int appId = sharedUser.getAppId();
                        appIdPermissionStates.put(appId,
                                toLegacyPermissionStates(permissionStates));
                    } else {
                        Log.w(LOG_TAG, "Shared user " + sharedUserName + " not found.");
                    }
                }
            });
        }
        return appIdPermissionStates;
    }

    @Override
    public int getLegacyPermissionStateVersion(int userId) {
        PackageManagerInternal packageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        int version = packageManagerInternal.getLegacyPermissionsVersion(userId);
        // -1 No permission data available
        // 0 runtime-permissions.xml exist w/o any version
        switch (version) {
            case -1:
                return 0;
            case 0:
                return -1;
            default:
                return version;
        }
    }

    @Override
    public boolean hasLegacyPermissionState(int userId) {
        return getLegacyPermissionStateVersion(userId) > -1;
    }

    @NonNull
    private Map<String, LegacyPermissionState> toLegacyPermissionStates(
            List<RuntimePermissionsState.PermissionState> permissions) {
        Map<String, LegacyPermissionState> legacyPermissions = new ArrayMap<>();

        final int size = permissions.size();
        for (int i = 0; i < size; i++) {
            RuntimePermissionsState.PermissionState permState = permissions.get(i);
            legacyPermissions.put(permState.getName(), new LegacyPermissionState(
                    permState.isGranted(), permState.getFlags()));
        }

        return legacyPermissions;
    }
}
