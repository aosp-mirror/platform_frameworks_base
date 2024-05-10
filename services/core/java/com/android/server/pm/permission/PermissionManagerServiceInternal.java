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
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PermissionInfo;
import android.permission.PermissionManagerInternal;
import android.util.ArrayMap;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

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
     * @param deviceId the device ID
     * @param userId the user ID
     * @return {@code PERMISSION_GRANTED} if the permission is granted, or {@code PERMISSION_DENIED}
     *         otherwise
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    int checkPermission(@NonNull String packageName, @NonNull String permissionName, int deviceId,
            @UserIdInt int userId);

    /**
     * Check whether a particular UID has been granted a particular permission.
     *
     * @param uid the UID
     * @param permissionName the name of the permission you are checking for
     * @param deviceId the device for which you are checking the permission
     * @return {@code PERMISSION_GRANTED} if the permission is granted, or {@code PERMISSION_DENIED}
     *         otherwise
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    int checkUidPermission(int uid, @NonNull String permissionName, int deviceId);

    /**
     * Get whether permission review is required for a package.
     *
     * @param packageName the name of the package
     * @param userId the user ID
     * @return whether permission review is required
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    boolean isPermissionsReviewRequired(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Reset the runtime permission state changes for a package.
     *
     * TODO(zhanghai): Turn this into package change callback?
     *
     * @param pkg the package
     * @param userId the user ID
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void resetRuntimePermissions(@NonNull AndroidPackage pkg, @UserIdInt int userId);

    /**
     * Reset the runtime permission state changes for all packages in a user.
     *
     * @param userId the user ID
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void resetRuntimePermissionsForUser(@UserIdInt int userId);

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
     * Get all the permissions definitions from a package that's installed in the system.
     * <p>
     * A permission definition in a normal app may not be installed if it's overridden by the
     * platform or system app that contains a conflicting definition after system upgrade.
     *
     * @param packageName the name of the package
     * @return the names of the installed permissions
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @NonNull
    Set<String> getInstalledPermissions(@NonNull String packageName);

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
    List<PermissionInfo> getAllPermissionsWithProtection(
            @PermissionInfo.Protection int protection);

    /** Get all permissions that have certain protection flags
     * @return*/
    @NonNull List<PermissionInfo> getAllPermissionsWithProtectionFlags(
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
     * Get all delegated shell permissions.
     */
    @NonNull List<String> getDelegatedShellPermissions();

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
     * Get the fingerprint for default permission grants.
     */
    @Nullable
    String getDefaultPermissionGrantFingerprint(@UserIdInt int userId);

    /**
     * Set the fingerprint for default permission grants.
     */
    void setDefaultPermissionGrantFingerprint(@NonNull String fingerprint, @UserIdInt int userId);

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
     * @param packageState the added package
     * @param isInstantApp whether the added package is an instant app
     * @param oldPkg the old package, or {@code null} if none
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onPackageAdded(@NonNull PackageState packageState,
            boolean isInstantApp, @Nullable AndroidPackage oldPkg);

    /**
     * Callback when a package has been installed for a user.
     *
     * @param pkg the installed package
     * @param previousAppId the previous app ID if the package is leaving a shared UID,
     *                      or Process.INVALID_UID
     * @param params the parameters passed in for package installation
     * @param userId the user ID this package is installed for
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onPackageInstalled(@NonNull AndroidPackage pkg, int previousAppId,
            @NonNull PackageInstalledParams params,
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
     * @param packageName the name of the uninstalled package
     * @param appId the app ID of the uninstalled package
     * @param packageState the uninstalled package, or {@code null} if unavailable
     * @param sharedUserPkgs the packages that are in the same shared user
     * @param userId the user ID the package is uninstalled for
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void onPackageUninstalled(@NonNull String packageName, int appId,
            @Nullable PackageState packageState, @Nullable AndroidPackage pkg,
            @NonNull List<AndroidPackage> sharedUserPkgs, @UserIdInt int userId);

    /**
     * The permission-related parameters passed in for package installation.
     *
     * @see SessionParams
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    final class PackageInstalledParams {
        /**
         * A static instance whose parameters are all in their default state.
         */
        public static final PackageInstalledParams DEFAULT = new Builder().build();

        @NonNull
        private final ArrayMap<String, Integer> mPermissionStates;
        @NonNull
        private final List<String> mAllowlistedRestrictedPermissions;
        @NonNull
        private final int mAutoRevokePermissionsMode;

        private PackageInstalledParams(@NonNull ArrayMap<String, Integer> permissionStates,
                @NonNull List<String> allowlistedRestrictedPermissions,
                int autoRevokePermissionsMode) {
            mPermissionStates = permissionStates;
            mAllowlistedRestrictedPermissions = allowlistedRestrictedPermissions;
            mAutoRevokePermissionsMode = autoRevokePermissionsMode;
        }

        /**
         * @return the permissions states requested
         *
         * @see SessionParams#setPermissionState(String, int)
         */
        @NonNull
        public ArrayMap<String, Integer> getPermissionStates() {
            return mPermissionStates;
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
            @Nullable
            private ArrayMap<String, Integer> mPermissionStates = null;
            @NonNull
            private List<String> mAllowlistedRestrictedPermissions = Collections.emptyList();
            @NonNull
            private int mAutoRevokePermissionsMode = AppOpsManager.MODE_DEFAULT;

            /**
             * Set the permissions states requested by the installer.
             *
             * @see SessionParams#setPermissionState(String, int)
             */
            public Builder setPermissionStates(
                    @NonNull ArrayMap<String, Integer> permissionStates) {
                Objects.requireNonNull(permissionStates);
                mPermissionStates = permissionStates;
                return this;
            }

            /**
             * Set the restricted permissions to be allowlisted.
             * <p>
             * Permissions that are not restricted are ignored, so one can just pass in all
             * requested permissions of a package to get all its restricted permissions allowlisted.
             *
             * @param allowlistedRestrictedPermissions the restricted permissions to be allowlisted
             *
             * @see SessionParams#setWhitelistedRestrictedPermissions(Set)
             */
            public void setAllowlistedRestrictedPermissions(
                    @NonNull List<String> allowlistedRestrictedPermissions) {
                Objects.requireNonNull(allowlistedRestrictedPermissions);
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
             * @see SessionParams#setAutoRevokePermissionsMode(boolean)
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
                return new PackageInstalledParams(
                        mPermissionStates == null ? new ArrayMap<>() : mPermissionStates,
                        mAllowlistedRestrictedPermissions, mAutoRevokePermissionsMode);
            }
        }
    }

    /**
     * Sets the provider of the currently active HotwordDetectionService.
     *
     * @see HotwordDetectionServiceProvider
     */
    void setHotwordDetectionServiceProvider(@Nullable HotwordDetectionServiceProvider provider);

    /**
     * Gets the provider of the currently active HotwordDetectionService.
     *
     * @see HotwordDetectionServiceProvider
     */
    @Nullable
    HotwordDetectionServiceProvider getHotwordDetectionServiceProvider();

    /**
     * Provides the uid of the currently active
     * {@link android.service.voice.HotwordDetectionService}, which should be granted RECORD_AUDIO,
     * CAPTURE_AUDIO_HOTWORD and CAPTURE_AUDIO_OUTPUT permissions.
     */
    interface HotwordDetectionServiceProvider {
        int getUid();
    }
}
