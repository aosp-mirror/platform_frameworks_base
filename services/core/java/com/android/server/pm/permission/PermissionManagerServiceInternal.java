/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PermissionInfoFlags;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.permission.PermissionManagerInternal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Internal interfaces services.
 *
 * TODO: Should be merged into PermissionManagerInternal, but currently uses internal classes.
 */
public abstract class PermissionManagerServiceInternal extends PermissionManagerInternal {
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
        public void onPermissionUpdated(int[] updatedUserIds, boolean sync) {
        }
        public void onPermissionRemoved() {
        }
        public void onInstallPermissionUpdated() {
        }
    }

    public abstract void systemReady();

    public abstract boolean isPermissionsReviewRequired(@NonNull PackageParser.Package pkg,
            @UserIdInt int userId);

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
    public abstract @Nullable List<String> getWhitelistedRestrictedPermissions(
            @NonNull PackageParser.Package pkg,
            @PackageManager.PermissionWhitelistFlags int whitelistFlags, int userId);
    public abstract void setWhitelistedRestrictedPermissions(
            @NonNull PackageParser.Package pkg, @NonNull int[] userIds,
            @NonNull List<String> permissions, int callingUid,
            @PackageManager.PermissionWhitelistFlags int whitelistFlags,
            @Nullable PermissionCallback callback);
    public abstract void revokeRuntimePermission(@NonNull String permName,
            @NonNull String packageName, boolean overridePolicy, int userId,
            @Nullable PermissionCallback callback);

    public abstract void updatePermissions(@Nullable String packageName,
            @Nullable PackageParser.Package pkg, boolean replaceGrant,
            @NonNull Collection<PackageParser.Package> allPacakges, PermissionCallback callback);
    public abstract void updateAllPermissions(@Nullable String volumeUuid, boolean sdkUpdate,
            @NonNull Collection<PackageParser.Package> allPacakges, PermissionCallback callback);

    /**
     * We might auto-grant permissions if any permission of the group is already granted. Hence if
     * the group of a granted permission changes we need to revoke it to avoid having permissions of
     * the new group auto-granted.
     *
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     * @param allPackageNames All packages
     * @param permissionCallback Callback for permission changed
     */
    public abstract void revokeRuntimePermissionsIfGroupChanged(
            @NonNull PackageParser.Package newPackage,
            @NonNull PackageParser.Package oldPackage,
            @NonNull ArrayList<String> allPackageNames,
            @NonNull PermissionCallback permissionCallback);

    /**
     * Some permissions might have been owned by a non-system package, and the system then defined
     * said permission. Some other permissions may one have been install permissions, but are now
     * runtime or higher. These permissions should be revoked.
     *
     * @param permissionsToRevoke A list of permission names to revoke
     * @param allPackageNames All packages
     */
    public abstract void revokeRuntimePermissionsIfPermissionDefinitionChanged(
            @NonNull List<String> permissionsToRevoke,
            @NonNull ArrayList<String> allPackageNames,
            @NonNull PermissionCallback permissionCallback);

    /**
     * Add all permissions in the given package.
     * <p>
     * NOTE: argument {@code groupTEMP} is temporary until mPermissionGroups is moved to
     * the permission settings.
     *
     * @return A list of BasePermissions that were updated, and need to be revoked from packages
     */
    public abstract List<String> addAllPermissions(@NonNull PackageParser.Package pkg, boolean chatty);
    public abstract void addAllPermissionGroups(@NonNull PackageParser.Package pkg, boolean chatty);
    public abstract void removeAllPermissions(@NonNull PackageParser.Package pkg, boolean chatty);
    public abstract boolean addDynamicPermission(@NonNull PermissionInfo info, boolean async,
            int callingUid, @Nullable PermissionCallback callback);
    public abstract void removeDynamicPermission(@NonNull String permName, int callingUid,
            @Nullable PermissionCallback callback);

    public abstract @Nullable String[] getAppOpPermissionPackages(@NonNull String permName);

    public abstract int getPermissionFlags(@NonNull String permName,
            @NonNull String packageName, int callingUid, int userId);
    /**
     * Retrieve all of the information we know about a particular group of permissions.
     */
    public abstract @Nullable PermissionGroupInfo getPermissionGroupInfo(
            @NonNull String groupName, int flags, int callingUid);
    /**
     * Retrieve all of the known permission groups in the system.
     */
    public abstract @Nullable List<PermissionGroupInfo> getAllPermissionGroups(int flags,
            int callingUid);
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

    /**
     * Updates the flags associated with a permission by replacing the flags in
     * the specified mask with the provided flag values.
     */
    public abstract void updatePermissionFlags(@NonNull String permName,
            @NonNull String packageName, int flagMask, int flagValues, int callingUid, int userId,
            boolean overridePolicy, @Nullable PermissionCallback callback);
    /**
     * Updates the flags for all applications by replacing the flags in the specified mask
     * with the provided flag values.
     */
    public abstract boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues,
            int callingUid, int userId, @NonNull Collection<PackageParser.Package> packages,
            @Nullable PermissionCallback callback);

    public abstract int checkPermission(@NonNull String permName, @NonNull String packageName,
            int callingUid, int userId);
    public abstract int checkUidPermission(@NonNull String permName,
            @Nullable PackageParser.Package pkg, int uid, int callingUid);

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userid} is not for the caller.
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    public abstract void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, boolean checkShell, @NonNull String message);
    /**
     * @see #enforceCrossUserPermission(int, int, boolean, boolean, String)
     * @param requirePermissionWhenSameUser When {@code true}, still require the cross user
     * permission to be held even if the callingUid and userId reference the same user.
     */
    public abstract void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, @NonNull String message);
    public abstract void enforceGrantRevokeRuntimePermissionPermissions(@NonNull String message);

    public abstract @NonNull PermissionSettings getPermissionSettings();
    public abstract @NonNull DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy();

    /** HACK HACK methods to allow for partial migration of data to the PermissionManager class */
    public abstract @Nullable BasePermission getPermissionTEMP(@NonNull String permName);

    /** Get all permission that have a certain protection level */
    public abstract @NonNull ArrayList<PermissionInfo> getAllPermissionWithProtectionLevel(
            @PermissionInfo.Protection int protectionLevel);
}
