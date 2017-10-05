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
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.PermissionInfoFlags;
import android.content.pm.PackageParser.Permission;

import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Internal interfaces to be used by other components within the system server.
 */
public abstract class PermissionManagerInternal {
    /**
     * Callbacks invoked when interesting actions have been taken on a permission.
     * <p>
     * NOTE: The current arguments are merely to support the existing use cases. This
     * needs to be properly thought out with appropriate arguments for each of the
     * callback methods.
     */
    public static class PermissionCallback {
        public void onGidsChanged(int appId, int userId) {
        }
        public void onPermissionChanged() {
        }
        public void onPermissionGranted(int uid, int userId) {
        }
        public void onInstallPermissionGranted() {
        }
        public void onPermissionRevoked(int uid, int userId) {
        }
        public void onInstallPermissionRevoked() {
        }
        public void onPermissionUpdated(int userId) {
        }
        public void onPermissionRemoved() {
        }
        public void onInstallPermissionUpdated() {
        }
    }

    public abstract void grantRuntimePermission(
            @NonNull String permName, @NonNull String packageName, boolean overridePolicy,
            int callingUid, int userId, @Nullable PermissionCallback callback);
    public abstract void grantRuntimePermissionsGrantedToDisabledPackage(
            @NonNull PackageParser.Package pkg, int callingUid,
            @Nullable PermissionCallback callback);
    public abstract void grantRequestedRuntimePermissions(
            @NonNull PackageParser.Package pkg, @NonNull int[] userIds,
            @NonNull String[] grantedPermissions, int callingUid,
            @Nullable PermissionCallback callback);
    public abstract void revokeRuntimePermission(@NonNull String permName,
            @NonNull String packageName, boolean overridePolicy, int callingUid, int userId,
            @Nullable PermissionCallback callback);
    public abstract int[] revokeUnusedSharedUserPermissions(@NonNull SharedUserSetting suSetting,
            @NonNull int[] allUserIds);


    public abstract boolean addPermission(@NonNull PermissionInfo info, boolean async,
            int callingUid, @Nullable PermissionCallback callback);
    public abstract void removePermission(@NonNull String permName, int callingUid,
            @Nullable PermissionCallback callback);

    public abstract int getPermissionFlags(@NonNull String permName,
            @NonNull String packageName, int callingUid, int userId);
    /**
     * Retrieve all of the information we know about a particular permission.
     */
    public abstract @Nullable PermissionInfo getPermissionInfo(@NonNull String permName,
            @NonNull String packageName, @PermissionInfoFlags int flags, int callingUid);
    /**
     * Retrieve all of the permissions associated with a particular group.
     */
    public abstract @Nullable List<PermissionInfo> getPermissionInfoByGroup(@NonNull String group,
            @PermissionInfoFlags int flags, int callingUid);
    public abstract boolean isPermissionAppOp(@NonNull String permName);
    public abstract boolean isPermissionInstant(@NonNull String permName);

    /**
     * Updates the flags associated with a permission by replacing the flags in
     * the specified mask with the provided flag values.
     */
    public abstract void updatePermissionFlags(@NonNull String permName,
            @NonNull String packageName, int flagMask, int flagValues, int callingUid, int userId,
            @Nullable PermissionCallback callback);
    /**
     * Updates the flags for all applications by replacing the flags in the specified mask
     * with the provided flag values.
     */
    public abstract boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues,
            int callingUid, int userId, @NonNull Collection<PackageParser.Package> packages,
            @Nullable PermissionCallback callback);

    public abstract int checkPermission(@NonNull String permName, @NonNull String packageName,
            int callingUid, int userId);

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userid} is not for the caller.
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    public abstract void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, boolean checkShell, @NonNull String message);
    public abstract void enforceGrantRevokeRuntimePermissionPermissions(@NonNull String message);

    public abstract @NonNull PermissionSettings getPermissionSettings();
    public abstract @NonNull DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy();

    /** HACK HACK methods to allow for partial migration of data to the PermissionManager class */
    public abstract Iterator<BasePermission> getPermissionIteratorTEMP();
    public abstract @Nullable BasePermission getPermissionTEMP(@NonNull String permName);
    public abstract void putPermissionTEMP(@NonNull String permName,
            @NonNull BasePermission permission);
}