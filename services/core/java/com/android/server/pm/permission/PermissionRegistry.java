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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Collection;

/**
 * Permission registry for permissions, permission trees, permission groups and related things.
 */
public class PermissionRegistry {
    /**
     * All of the permissions known to the system. The mapping is from permission
     * name to permission object.
     */
    private final ArrayMap<String, Permission> mPermissions = new ArrayMap<>();

    /**
     * All permission trees known to the system. The mapping is from permission tree
     * name to permission object.
     */
    private final ArrayMap<String, Permission> mPermissionTrees = new ArrayMap<>();

    /**
     * All permisson groups know to the system. The mapping is from permission group
     * name to permission group object.
     */
    private final ArrayMap<String, ParsedPermissionGroup> mPermissionGroups = new ArrayMap<>();

    /**
     * Set of packages that request a particular app op. The mapping is from permission
     * name to package names.
     */
    private final ArrayMap<String, ArraySet<String>> mAppOpPermissionPackages = new ArrayMap<>();

    @NonNull
    public Collection<Permission> getPermissions() {
        return mPermissions.values();
    }

    @Nullable
    public Permission getPermission(@NonNull String permissionName) {
        return mPermissions.get(permissionName);
    }

    public void addPermission(@NonNull Permission permission) {
        mPermissions.put(permission.getName(), permission);
    }

    public void removePermission(@NonNull String permissionName) {
        mPermissions.remove(permissionName);
    }

    @NonNull
    public Collection<Permission> getPermissionTrees() {
        return mPermissionTrees.values();
    }

    @Nullable
    public Permission getPermissionTree(@NonNull String permissionTreeName) {
        return mPermissionTrees.get(permissionTreeName);
    }

    public void addPermissionTree(@NonNull Permission permissionTree) {
        mPermissionTrees.put(permissionTree.getName(), permissionTree);
    }

    /**
     * Transfers ownership of permissions from one package to another.
     */
    public void transferPermissions(@NonNull String oldPackageName,
            @NonNull String newPackageName) {
        for (int i = 0; i < 2; i++) {
            ArrayMap<String, Permission> permissions = i == 0 ? mPermissionTrees : mPermissions;
            for (final Permission permission : permissions.values()) {
                permission.transfer(oldPackageName, newPackageName);
            }
        }
    }

    @NonNull
    public Collection<ParsedPermissionGroup> getPermissionGroups() {
        return mPermissionGroups.values();
    }

    @Nullable
    public ParsedPermissionGroup getPermissionGroup(@NonNull String permissionGroupName) {
        return mPermissionGroups.get(permissionGroupName);
    }

    public void addPermissionGroup(@NonNull ParsedPermissionGroup permissionGroup) {
        mPermissionGroups.put(permissionGroup.getName(), permissionGroup);
    }

    @NonNull
    public ArrayMap<String, ArraySet<String>> getAllAppOpPermissionPackages() {
        return mAppOpPermissionPackages;
    }

    @Nullable
    public ArraySet<String> getAppOpPermissionPackages(@NonNull String permissionName) {
        return mAppOpPermissionPackages.get(permissionName);
    }

    public void addAppOpPermissionPackage(@NonNull String permissionName,
            @NonNull String packageName) {
        ArraySet<String> packageNames = mAppOpPermissionPackages.get(permissionName);
        if (packageNames == null) {
            packageNames = new ArraySet<>();
            mAppOpPermissionPackages.put(permissionName, packageNames);
        }
        packageNames.add(packageName);
    }

    public void removeAppOpPermissionPackage(@NonNull String permissionName,
            @NonNull String packageName) {
        final ArraySet<String> packageNames = mAppOpPermissionPackages.get(permissionName);
        if (packageNames == null) {
            return;
        }
        final boolean removed = packageNames.remove(packageName);
        if (removed && packageNames.isEmpty()) {
            mAppOpPermissionPackages.remove(permissionName);
        }
    }

    /**
     * Returns the permission tree for the given permission.
     * @throws SecurityException If the calling UID is not allowed to add permissions to the
     * found permission tree.
     */
    @NonNull
    public Permission enforcePermissionTree(@NonNull String permissionName, int callingUid) {
        return Permission.enforcePermissionTree(mPermissionTrees.values(), permissionName,
                callingUid);
    }
}
