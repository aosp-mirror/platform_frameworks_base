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
import android.app.AppOpsManager;
import android.content.pm.PermissionInfo;
import android.permission.PermissionManagerInternal;

import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Internal interfaces services.
 *
 * TODO: Move into module.
 */
public interface PermissionManagerServiceInternal extends PermissionManagerInternal,
        LegacyPermissionDataProvider {
    /**
     * Check whether a particular package has been granted a particular permission.
     *
     * @param packageName the name of the package you are checking against
     * @param permissionName the name of the permission you are checking for
     * @param userId the user ID
     * @return {@code PERMISSION_GRANTED} if the permission is granted, or {@code PERMISSION_DENIED}
     *         otherwise
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    int checkPermission(@NonNull String packageName, @NonNull String permissionName,
            @UserIdInt int userId);

    /**
     * Check whether a particular UID has been granted a particular permission.
     *
     * @param uid the UID
     * @param permissionName the name of the permission you are checking for
     * @return {@code PERMISSION_GRANTED} if the permission is granted, or {@code PERMISSION_DENIED}
     *         otherwise
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    int checkUidPermission(int uid, @NonNull String permissionName);

    /**
     * Adds a listener for runtime permission state (permissions or flags) changes.
     *
     * @param listener The listener.
     */
    void addOnRuntimePermissionStateChangedListener(
            @NonNull OnRuntimePermissionStateChangedListener listener);

    /**
     * Removes a listener for runtime permission state (permissions or flags) changes.
     *
     * @param listener The listener.
     */
    void removeOnRuntimePermissionStateChangedListener(
            @NonNull OnRuntimePermissionStateChangedListener listener);

    /**
     * Get whether permission review is required for a package.
     *
     * @param packageName the name of the package
     * @param userId the user ID
     * @return whether permission review is required
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    boolean isPermissionsReviewRequired(@NonNull String packageName,
            @UserIdInt int userId);

    /**
     * Reset the runtime permission state changes for a package.
     *
     * TODO(zhanghai): Turn this into package change callback?
     *
     * @param pkg the package
     * @param userId the user ID
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void resetRuntimePermissions(@NonNull AndroidPackage pkg,
            @UserIdInt int userId);

    /**
     * Read legacy permission state from package settings.
     *
     * TODO(zhanghai): This is a temporary method because we should not expose
     * {@code PackageSetting} which is a implementation detail that permission should not know.
     * Instead, it should retrieve the legacy state via a defined API.
     */
    void readLegacyPermissionStateTEMP();

    /**
     * Write legacy permission state to package settings.
     *
     * TODO(zhanghai): This is a temporary method and should be removed once we migrated persistence
     * for permission.
     */
    void writeLegacyPermissionStateTEMP();

    /**
     * Get all the permissions granted to a package.
     *
     * @param packageName the name of the package
     * @param userId the user ID
     * @return the names of the granted permissions
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @NonNull
    Set<String> getGrantedPermissions(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Get the GIDs of a permission.
     *
     * @param permissionName the name of the permission
     * @param userId the user ID
     * @return the GIDs of the permission
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @NonNull
    int[] getPermissionGids(@NonNull String permissionName, @UserIdInt int userId);

    /**
     * Get the packages that have requested an app op permission.
     *
     * @param permissionName the name of the app op permission
     * @return the names of the packages that have requested the app op permission
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @NonNull
    String[] getAppOpPermissionPackages(@NonNull String permissionName);

    /** HACK HACK methods to allow for partial migration of data to the PermissionManager class */
    @Nullable
    Permission getPermissionTEMP(@NonNull String permName);

    /** Get all permissions that have a certain protection */
    @NonNull
    ArrayList<PermissionInfo> getAllPermissionsWithProtection(
            @PermissionInfo.Protection int protection);

    /** Get all permissions that have certain protection flags */
    @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtectionFlags(
            @PermissionInfo.ProtectionFlags int protectionFlags);

    /**
     * Start delegate the permission identity of the shell UID to the given UID.
     *
     * @param uid the UID to delegate shell permission identity to
     * @param packageName the name of the package to delegate shell permission identity to
     * @param permissionNames the names of the permissions to delegate shell permission identity
     *                       for, or {@code null} for all permissions
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void startShellPermissionIdentityDelegation(int uid,
            @NonNull String packageName, @Nullable List<String> permissionNames);

    /**
     * Stop delegating the permission identity of the shell UID.
     *
     * @see #startShellPermissionIdentityDelegation(int, String, List)
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void stopShellPermissionIdentityDelegation();

    /**
     * Read legacy permissions from legacy permission settings.
     *
     * TODO(zhanghai): This is a temporary method because we should not expose
     * {@code LegacyPermissionSettings} which is a implementation detail that permission should not
     * know. Instead, it should retrieve the legacy permissions via a defined API.
     */
    void readLegacyPermissionsTEMP(@NonNull LegacyPermissionSettings legacyPermissionSettings);

    /**
     * Write legacy permissions to legacy permission settings.
     *
     * TODO(zhanghai): This is a temporary method and should be removed once we migrated persistence
     * for permission.
     */
    void writeLegacyPermissionsTEMP(@NonNull LegacyPermissionSettings legacyPermissionSettings);

    /**
     * Callback when the system is ready.
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onSystemReady();

    /**
     * Callback when a storage volume is mounted, so that all packages on it become available.
     *
     * @param volumeUuid the UUID of the storage volume
     * @param fingerprintChanged whether the current build fingerprint is different from what it was
     *                           when this volume was last mounted
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onStorageVolumeMounted(@NonNull String volumeUuid, boolean fingerprintChanged);

    /**
     * Callback when a user has been created.
     *
     * @param userId the created user ID
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onUserCreated(@UserIdInt int userId);

    /**
     * Callback when a user has been removed.
     *
     * @param userId the removed user ID
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onUserRemoved(@UserIdInt int userId);

    /**
     * Callback when a package has been added.
     *
     * @param pkg the added package
     * @param isInstantApp whether the added package is an instant app
     * @param oldPkg the old package, or {@code null} if none
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onPackageAdded(@NonNull AndroidPackage pkg, boolean isInstantApp,
            @Nullable AndroidPackage oldPkg);

    /**
     * Callback when a package has been installed for a user.
     *
     * @param pkg the installed package
     * @param params the parameters passed in for package installation
     * @param userId the user ID this package is installed for
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onPackageInstalled(@NonNull AndroidPackage pkg, @NonNull PackageInstalledParams params,
            @UserIdInt int userId);

    /**
     * Callback when a package has been removed.
     *
     * @param pkg the removed package
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onPackageRemoved(@NonNull AndroidPackage pkg);

    /**
     * Callback when a package has been uninstalled.
     * <p>
     * The package may have been fully removed from the system, or only marked as uninstalled for
     * this user but still instlaled for other users.
     *
     * TODO: Pass PackageState instead.
     *
     * @param packageName the name of the uninstalled package
     * @param appId the app ID of the uninstalled package
     * @param pkg the uninstalled package, or {@code null} if unavailable
     * @param sharedUserPkgs the packages that are in the same shared user
     * @param userId the user ID the package is uninstalled for
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onPackageUninstalled(@NonNull String packageName, int appId, @Nullable AndroidPackage pkg,
            @NonNull List<AndroidPackage> sharedUserPkgs, @UserIdInt int userId);

    /**
     * Listener for package permission state (permissions or flags) changes.
     */
    interface OnRuntimePermissionStateChangedListener {

        /**
         * Called when the runtime permission state (permissions or flags) changed.
         *
         * @param packageName The package for which the change happened.
         * @param userId the user id for which the change happened.
         */
        @Nullable
        void onRuntimePermissionStateChanged(@NonNull String packageName,
                @UserIdInt int userId);
    }

    /**
     * The permission-related parameters passed in for package installation.
     *
     * @see android.content.pm.PackageInstaller.SessionParams
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    final class PackageInstalledParams {
        /**
         * A static instance whose parameters are all in their default state.
         */
        public static final PackageInstalledParams DEFAULT = new Builder().build();

        @NonNull
        private final List<String> mGrantedPermissions;
        @NonNull
        private final List<String> mAllowlistedRestrictedPermissions;
        @NonNull
        private final int mAutoRevokePermissionsMode;

        private PackageInstalledParams(@NonNull List<String> grantedPermissions,
                @NonNull List<String> allowlistedRestrictedPermissions,
                int autoRevokePermissionsMode) {
            mGrantedPermissions = grantedPermissions;
            mAllowlistedRestrictedPermissions = allowlistedRestrictedPermissions;
            mAutoRevokePermissionsMode = autoRevokePermissionsMode;
        }

        /**
         * Get the permissions to be granted.
         *
         * @return the permissions to be granted
         */
        @NonNull
        public List<String> getGrantedPermissions() {
            return mGrantedPermissions;
        }

        /**
         * Get the restricted permissions to be allowlisted.
         *
         * @return the restricted permissions to be allowlisted
         */
        @NonNull
        public List<String> getAllowlistedRestrictedPermissions() {
            return mAllowlistedRestrictedPermissions;
        }

        /**
         * Get the mode for auto revoking permissions.
         *
         * @return the mode for auto revoking permissions
         */
        public int getAutoRevokePermissionsMode() {
            return mAutoRevokePermissionsMode;
        }

        /**
         * Builder class for {@link PackageInstalledParams}.
         */
        public static final class Builder {
            @NonNull
            private List<String> mGrantedPermissions = Collections.emptyList();
            @NonNull
            private List<String> mAllowlistedRestrictedPermissions = Collections.emptyList();
            @NonNull
            private int mAutoRevokePermissionsMode = AppOpsManager.MODE_DEFAULT;

            /**
             * Set the permissions to be granted.
             *
             * @param grantedPermissions the permissions to be granted
             *
             * @see android.content.pm.PackageInstaller.SessionParams#setGrantedRuntimePermissions(
             *      java.lang.String[])
             */
            public void setGrantedPermissions(@NonNull List<String> grantedPermissions) {
                Objects.requireNonNull(grantedPermissions);
                mGrantedPermissions = new ArrayList<>(grantedPermissions);
            }

            /**
             * Set the restricted permissions to be allowlisted.
             * <p>
             * Permissions that are not restricted are ignored, so one can just pass in all
             * requested permissions of a package to get all its restricted permissions allowlisted.
             *
             * @param allowlistedRestrictedPermissions the restricted permissions to be allowlisted
             *
             * @see android.content.pm.PackageInstaller.SessionParams#setWhitelistedRestrictedPermissions(Set)
             */
            public void setAllowlistedRestrictedPermissions(
                    @NonNull List<String> allowlistedRestrictedPermissions) {
                Objects.requireNonNull(mGrantedPermissions);
                mAllowlistedRestrictedPermissions = new ArrayList<>(
                        allowlistedRestrictedPermissions);
            }

            /**
             * Set the mode for auto revoking permissions.
             * <p>
             * {@link AppOpsManager#MODE_ALLOWED} means the system is allowed to auto revoke
             * permissions from this package, and {@link AppOpsManager#MODE_IGNORED} means this
             * package should be ignored when auto revoking permissions.
             * {@link AppOpsManager#MODE_DEFAULT} means no changes will be made to the auto revoke
             * mode of this package.
             *
             * @param autoRevokePermissionsMode the mode for auto revoking permissions
             *
             * @see android.content.pm.PackageInstaller.SessionParams#setAutoRevokePermissionsMode(
             *      boolean)
             */
            public void setAutoRevokePermissionsMode(int autoRevokePermissionsMode) {
                mAutoRevokePermissionsMode = autoRevokePermissionsMode;
            }

            /**
             * Build a new instance of {@link PackageInstalledParams}.
             *
             * @return the {@link PackageInstalledParams} built
             */
            @NonNull
            public PackageInstalledParams build() {
                return new PackageInstalledParams(mGrantedPermissions,
                        mAllowlistedRestrictedPermissions, mAutoRevokePermissionsMode);
            }
        }
    }
}
