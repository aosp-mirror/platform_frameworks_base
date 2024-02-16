/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.permission.IOnPermissionsChangeListener;
import android.permission.PermissionManager.PermissionState;
import android.permission.PermissionManagerInternal;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for managing all permissions and handling permissions related tasks.
 */
public interface PermissionManagerServiceInterface extends PermissionManagerInternal {
    /**
     * Dump.
     */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    /**
     * Retrieve all of the known permission groups in the system.
     *
     * @param flags additional option flags to modify the data returned
     * @return a list of {@link PermissionGroupInfo} containing information about all of the known
     *         permission groups
     */
    List<PermissionGroupInfo> getAllPermissionGroups(
            @PackageManager.PermissionGroupInfoFlags int flags);

    /**
     * Retrieve all of the information we know about a particular group of permissions.
     *
     * @param groupName the fully qualified name (e.g. com.android.permission_group.APPS) of the
     *                  permission you are interested in
     * @param flags additional option flags to modify the data returned
     * @return a {@link PermissionGroupInfo} containing information about the permission, or
     *         {@code null} if not found
     */
    PermissionGroupInfo getPermissionGroupInfo(String groupName,
            @PackageManager.PermissionGroupInfoFlags int flags);

    /**
     * Retrieve all of the information we know about a particular permission.
     *
     * @param permName the fully qualified name (e.g. com.android.permission.LOGIN) of the
     *                       permission you are interested in
     * @param flags additional option flags to modify the data returned
     * @return a {@link PermissionInfo} containing information about the permission, or {@code null}
     *         if not found
     */
    PermissionInfo getPermissionInfo(@NonNull String permName,
            @PackageManager.PermissionInfoFlags int flags, @NonNull String opPackageName);

    /**
     * Query for all of the permissions associated with a particular group.
     *
     * @param groupName the fully qualified name (e.g. com.android.permission.LOGIN) of the
     *                  permission group you are interested in. Use {@code null} to find all of the
     *                  permissions not associated with a group
     * @param flags additional option flags to modify the data returned
     * @return a list of {@link PermissionInfo} containing information about all of the permissions
     *         in the given group, or {@code null} if the group is not found
     */
    List<PermissionInfo> queryPermissionsByGroup(String groupName,
            @PackageManager.PermissionInfoFlags int flags);

    /**
     * Add a new dynamic permission to the system. For this to work, your package must have defined
     * a permission tree through the
     * {@link android.R.styleable#AndroidManifestPermissionTree &lt;permission-tree&gt;} tag in its
     * manifest. A package can only add permissions to trees that were defined by either its own
     * package or another with the same user id; a permission is in a tree if it matches the name of
     * the permission tree + ".": for example, "com.foo.bar" is a member of the permission tree
     * "com.foo".
     * <p>
     * It is good to make your permission tree name descriptive, because you are taking possession
     * of that entire set of permission names. Thus, it must be under a domain you control, with a
     * suffix that will not match any normal permissions that may be declared in any applications
     * that are part of that domain.
     * <p>
     * New permissions must be added before any .apks are installed that use those permissions.
     * Permissions you add through this method are remembered across reboots of the device. If the
     * given permission already exists, the info you supply here will be used to update it.
     *
     * @param info description of the permission to be added
     * @param async whether the persistence of the permission should be asynchronous, allowing it to
     *              return quicker and batch a series of adds, at the expense of no guarantee the
     *              added permission will be retained if the device is rebooted before it is
     *              written.
     * @return {@code true} if a new permission was created, {@code false} if an existing one was
     *         updated
     * @throws SecurityException if you are not allowed to add the given permission name
     *
     * @see #removePermission(String)
     */
    boolean addPermission(PermissionInfo info, boolean async);

    /**
     * Removes a permission that was previously added with
     * {@link #addPermission(PermissionInfo, boolean)}. The same ownership rules apply -- you are
     * only allowed to remove permissions that you are allowed to add.
     *
     * @param permName the name of the permission to remove
     * @throws SecurityException if you are not allowed to remove the given permission name
     *
     * @see #addPermission(PermissionInfo, boolean)
     */
    void removePermission(String permName);

    /**
     * Gets the permission state flags associated with a permission.
     *
     * @param packageName the package name for which to get the flags
     * @param permName the permission for which to get the flags
     * @param deviceId The device for which to get the flags
     * @param userId the user for which to get permission flags
     * @return the permission flags
     */
    int getPermissionFlags(String packageName, String permName, String deviceId,
            @UserIdInt int userId);

    /**
     * Updates the flags associated with a permission by replacing the flags in the specified mask
     * with the provided flag values.
     *
     * @param packageName The package name for which to update the flags
     * @param permName The permission for which to update the flags
     * @param flagMask The flags which to replace
     * @param flagValues The flags with which to replace
     * @param deviceId The device for which to update the permission flags
     * @param userId The user for which to update the permission flags
     */
    void updatePermissionFlags(String packageName, String permName, int flagMask, int flagValues,
            boolean checkAdjustPolicyFlagPermission, String deviceId,
            @UserIdInt int userId);

    /**
     * Update the permission flags for all packages and runtime permissions of a user in order
     * to allow device or profile owner to remove POLICY_FIXED.
     */
    void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId);

    /**
     * TODO: theianchen We should get rid of the IBinder interface which is an implementation detail
     *
     * Add a listener for permission changes for installed packages.
     * @param listener the listener to add
     */
    void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener);

    /**
     * Remove a listener for permission changes for installed packages.
     * @param listener the listener to remove
     */
    void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener);

    /**
     * addAllowlistedRestrictedPermission. TODO: theianchen add doc
     */
    boolean addAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PackageManager.PermissionWhitelistFlags int flags,
            @UserIdInt int userId);

    /**
     * Gets the restricted permissions that have been allowlisted and the app is allowed to have
     * them granted in their full form.
     * <p>
     * Permissions can be hard restricted which means that the app cannot hold them or soft
     * restricted where the app can hold the permission but in a weaker form. Whether a permission
     * is {@link PermissionInfo#FLAG_HARD_RESTRICTED hard restricted} or
     * {@link PermissionInfo#FLAG_SOFT_RESTRICTED soft restricted} depends on the permission
     * declaration. Allowlisting a hard restricted permission allows for the to hold that permission
     * and allowlisting a soft restricted permission allows the app to hold the permission in its
     * full, unrestricted form.
     * <p>
     * There are four allowlists:
     * <ol>
     * <li>
     * One for cases where the system permission policy allowlists a permission. This list
     * corresponds to the {@link PackageManager#FLAG_PERMISSION_WHITELIST_SYSTEM} flag. Can only be
     * accessed by pre-installed holders of a dedicated permission.
     * <li>
     * One for cases where the system allowlists the permission when upgrading from an OS version in
     * which the permission was not restricted to an OS version in which the permission is
     * restricted. This list corresponds to the
     * {@link PackageManager#FLAG_PERMISSION_WHITELIST_UPGRADE} flag. Can be accessed by
     * pre-installed holders of a dedicated permission or the installer on record.
     * <li>
     * One for cases where the installer of the package allowlists a permission. This list
     * corresponds to the {@link PackageManager#FLAG_PERMISSION_WHITELIST_INSTALLER} flag. Can be
     * accessed by pre-installed holders of a dedicated permission or the installer on record.
     * </ol>
     *
     * @param packageName the app for which to get allowlisted permissions
     * @param flags the flag to determine which allowlist to query. Only one flag can be
     *                      passed.
     * @return the allowlisted permissions that are on any of the allowlists you query for
     * @throws SecurityException if you try to access a allowlist that you have no access to
     *
     * @see #addAllowlistedRestrictedPermission(String, String, int)
     * @see #removeAllowlistedRestrictedPermission(String, String, int)
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_INSTALLER
     */
    List<String> getAllowlistedRestrictedPermissions(@NonNull String packageName,
            @PackageManager.PermissionWhitelistFlags int flags, @UserIdInt int userId);

    /**
     * Removes a allowlisted restricted permission for an app.
     * <p>
     * Permissions can be hard restricted which means that the app cannot hold them or soft
     * restricted where the app can hold the permission but in a weaker form. Whether a permission
     * is {@link PermissionInfo#FLAG_HARD_RESTRICTED hard restricted} or
     * {@link PermissionInfo#FLAG_SOFT_RESTRICTED soft restricted} depends on the permission
     * declaration. Allowlisting a hard restricted permission allows for the to hold that permission
     * and allowlisting a soft restricted permission allows the app to hold the permission in its
     * full, unrestricted form.
     * <p>There are four allowlists:
     * <ol>
     * <li>
     * One for cases where the system permission policy allowlists a permission. This list
     * corresponds to the {@link PackageManager#FLAG_PERMISSION_WHITELIST_SYSTEM} flag. Can only be
     * accessed by pre-installed holders of a dedicated permission.
     * <li>
     * One for cases where the system allowlists the permission when upgrading from an OS version in
     * which the permission was not restricted to an OS version in which the permission is
     * restricted. This list corresponds to the
     * {@link PackageManager#FLAG_PERMISSION_WHITELIST_UPGRADE} flag. Can be accessed by
     * pre-installed holders of a dedicated permission or the installer on record.
     * <li>
     * One for cases where the installer of the package allowlists a permission. This list
     * corresponds to the {@link PackageManager#FLAG_PERMISSION_WHITELIST_INSTALLER} flag. Can be
     * accessed by pre-installed holders of a dedicated permission or the installer on record.
     * </ol>
     * <p>
     * You need to specify the allowlists for which to set the allowlisted permissions which will
     * clear the previous allowlisted permissions and replace them with the provided ones.
     *
     * @param packageName the app for which to get allowlisted permissions
     * @param permName the allowlisted permission to remove
     * @param flags the allowlists from which to remove. Passing multiple flags updates all
     *                       specified allowlists.
     * @return whether the permission was removed from the allowlist
     * @throws SecurityException if you try to modify a allowlist that you have no access to.
     *
     * @see #getAllowlistedRestrictedPermissions(String, int)
     * @see #addAllowlistedRestrictedPermission(String, String, int)
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_INSTALLER
     */
    boolean removeAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PackageManager.PermissionWhitelistFlags int flags,
            @UserIdInt int userId);

    /**
     * Grant a runtime permission to an application which the application does not already have. The
     * permission must have been requested by the application. If the application is not allowed to
     * hold the permission, a {@link java.lang.SecurityException} is thrown. If the package or
     * permission is invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     * <p>
     * <strong>Note: </strong>Using this API requires holding
     * {@code android.permission.GRANT_RUNTIME_PERMISSIONS} and if the user ID is not the current
     * user {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param packageName the package to which to grant the permission
     * @param permName the permission name to grant
     * @param deviceId the device for which to grant the permission
     * @param userId the user for which to grant the permission
     *
     * @see #revokeRuntimePermission(String, String, String, int, String)
     */
    void grantRuntimePermission(String packageName, String permName, String deviceId,
            @UserIdInt int userId);

    /**
     * Revoke a runtime permission that was previously granted by
     * {@link #grantRuntimePermission(String, String, String, int)}. The permission must
     * have been requested by and granted to the application. If the application is not allowed to
     * hold the permission, a {@link java.lang.SecurityException} is thrown. If the package or
     * permission is invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     * <p>
     * <strong>Note: </strong>Using this API requires holding
     * {@code android.permission.REVOKE_RUNTIME_PERMISSIONS} and if the user ID is not the current
     * user {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param packageName the package from which to revoke the permission
     * @param permName the permission name to revoke
     * @param deviceId the device for which to revoke the permission
     * @param userId the user for which to revoke the permission
     * @param reason the reason for the revoke, or {@code null} for unspecified
     *
     * @see #grantRuntimePermission(String, String, String, int)
     */
    void revokeRuntimePermission(String packageName, String permName, String deviceId,
            @UserIdInt int userId, String reason);

    /**
     * Revoke the POST_NOTIFICATIONS permission, without killing the app. This method must ONLY BE
     * USED in CTS or local tests.
     *
     * @param packageName The package to be revoked
     * @param userId The user for which to revoke
     */
    void revokePostNotificationPermissionWithoutKillForTest(String packageName, int userId);

    /**
     * Get whether you should show UI with rationale for requesting a permission. You should do this
     * only if you do not have the permission and the context in which the permission is requested
     * does not clearly communicate to the user what would be the benefit from grating this
     * permission.
     *
     * @param packageName the package name
     * @param permName a permission your app wants to request
     * @param deviceId the device for which to check the permission
     * @param userId the user for which to check the permission
     * @return whether you can show permission rationale UI
     */
    boolean shouldShowRequestPermissionRationale(String packageName, String permName,
            String deviceId, @UserIdInt int userId);

    /**
     * Checks whether a particular permission has been revoked for a package by policy. Typically,
     * the device owner or the profile owner may apply such a policy. The user cannot grant policy
     * revoked permissions, hence the only way for an app to get such a permission is by a policy
     * change.
     *
     * @param packageName the name of the package you are checking against
     * @param permName the name of the permission you are checking for
     * @param deviceId the device for which you are checking the permission
     * @param userId the device for which you are checking the permission
     * @return whether the permission is restricted by policy
     */
    boolean isPermissionRevokedByPolicy(String packageName, String permName,
            String deviceId, @UserIdInt int userId);

    /**
     * Get set of permissions that have been split into more granular or dependent permissions.
     *
     * <p>E.g. before {@link android.os.Build.VERSION_CODES#Q} an app that was granted
     * {@link Manifest.permission#ACCESS_COARSE_LOCATION} could access the location while it was in
     * foreground and background. On platforms after {@link android.os.Build.VERSION_CODES#Q}
     * the location permission only grants location access while the app is in foreground. This
     * would break apps that target before {@link android.os.Build.VERSION_CODES#Q}. Hence whenever
     * such an old app asks for a location permission (i.e. the
     * {@link PermissionManager.SplitPermissionInfo#getSplitPermission()}), then the
     * {@link Manifest.permission#ACCESS_BACKGROUND_LOCATION} permission (inside
     * {@link PermissionManager.SplitPermissionInfo#getNewPermissions}) is added.
     *
     * <p>Note: Regular apps do not have to worry about this. The platform and permission controller
     * automatically add the new permissions where needed.
     *
     * @return All permissions that are split.
     */
    List<SplitPermissionInfoParcelable> getSplitPermissions();

    /**
     * Check whether a permission is granted or not to a package.
     *
     * @param pkgName package name
     * @param permName permission name
     * @param deviceId  persistent device ID
     * @param userId user ID
     * @return permission result {@link PackageManager.PermissionResult}
     */
    int checkPermission(String pkgName, String permName, String deviceId,
            @UserIdInt int userId);

    /**
     * Check whether a permission is granted or not to an UID.
     *
     * @param uid UID
     * @param permName permission name
     * @param deviceId persistent device ID
     * @return permission result {@link PackageManager.PermissionResult}
     */
    int checkUidPermission(int uid, String permName, String deviceId);

    /**
     * Gets the permission states for requested package, persistent device and user.
     *
     * @param packageName name of the package you are checking against
     * @param deviceId id of the persistent device you are checking against
     * @param userId id of the user for which to get permission flags
     * @return mapping of all permission states keyed by their permission names
     *
     * @hide
     */
    Map<String, PermissionState> getAllPermissionStates(@NonNull String packageName,
            @NonNull String deviceId, @UserIdInt int userId);

    /**
     * Get all the package names requesting app op permissions.
     *
     * @return a map of app op permission names to package names requesting them
     */
    Map<String, Set<String>> getAllAppOpPermissionPackages();

    /**
     * Get whether permission review is required for a package.
     *
     * @param packageName the name of the package
     * @param userId the user ID
     * @return whether permission review is required
     */
    boolean isPermissionsReviewRequired(@NonNull String packageName,
            @UserIdInt int userId);

    /**
     * Reset the runtime permission state changes for a package for all devices.
     *
     * TODO(zhanghai): Turn this into package change callback?
     */
    void resetRuntimePermissions(@NonNull AndroidPackage pkg, @UserIdInt int userId);

    /**
     * Reset the runtime permission state changes for all packages in a user.
     *
     * @param userId the user ID
     */
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
    @NonNull
    Set<String> getInstalledPermissions(@NonNull String packageName);

    /**
     * Get all the permissions granted to a package.
     *
     * @param packageName package name
     * @param userId user ID
     * @return the names of the granted permissions
     */
    @NonNull
    Set<String> getGrantedPermissions(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Get the GIDs of a permission.
     *
     * @param permissionName the name of the permission
     * @param userId the user ID
     * @return the GIDs of the permission
     */
    @NonNull
    int[] getPermissionGids(@NonNull String permissionName, @UserIdInt int userId);

    /**
     * Get the packages that have requested an app op permission.
     *
     * @param permissionName the name of the app op permission
     * @return the names of the packages that have requested the app op permission
     */
    @NonNull
    String[] getAppOpPermissionPackages(@NonNull String permissionName);

    /** HACK HACK methods to allow for partial migration of data to the PermissionManager class */
    @Nullable
    Permission getPermissionTEMP(@NonNull String permName);

    /** Get all permissions that have a certain protection */
    @NonNull
    List<PermissionInfo> getAllPermissionsWithProtection(
            @PermissionInfo.Protection int protection);

    /** Get all permissions that have certain protection flags */
    @NonNull List<PermissionInfo> getAllPermissionsWithProtectionFlags(
            @PermissionInfo.ProtectionFlags int protectionFlags);

    /**
     * Get all the legacy permissions currently registered in the system.
     *
     * @return the legacy permissions
     */
    @NonNull
    List<LegacyPermission> getLegacyPermissions();

    /**
     * Get the legacy permission state of an app ID, either a package or a shared user.
     *
     * @param appId the app ID
     * @return the legacy permission state
     */
    @NonNull
    LegacyPermissionState getLegacyPermissionState(@AppIdInt int appId);

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
    void onSystemReady();

    /**
     * Callback when a storage volume is mounted, so that all packages on it become available.
     *
     * @param volumeUuid the UUID of the storage volume
     * @param fingerprintChanged whether the current build fingerprint is different from what it was
     *                           when this volume was last mounted
     */
    void onStorageVolumeMounted(@NonNull String volumeUuid, boolean fingerprintChanged);

    /**
     * Get the GIDs computed from the permission state of a UID, either a package or a shared user.
     *
     * @param uid the UID
     * @return the GIDs for the UID
     */
    @NonNull
    int[] getGidsForUid(int uid);

    /**
     * Callback when a user has been created.
     *
     * @param userId the created user ID
     */
    void onUserCreated(@UserIdInt int userId);

    /**
     * Callback when a user has been removed.
     *
     * @param userId the removed user ID
     */
    void onUserRemoved(@UserIdInt int userId);

    /**
     * Callback when a package has been added.
     *
     * @param packageState the added package
     * @param isInstantApp whether the added package is an instant app
     * @param oldPkg the old package, or {@code null} if none
     */
    void onPackageAdded(@NonNull PackageState packageState, boolean isInstantApp,
            @Nullable AndroidPackage oldPkg);

    /**
     * Callback when a package has been installed for a user.
     *
     * @param pkg the installed package
     * @param previousAppId the previous app ID if the package is leaving a shared UID,
     * or Process.INVALID_UID
     * @param params the parameters passed in for package installation
     * @param userId the user ID this package is installed for
     */
    void onPackageInstalled(@NonNull AndroidPackage pkg, int previousAppId,
            @NonNull PermissionManagerServiceInternal.PackageInstalledParams params,
            @UserIdInt int userId);

    /**
     * Callback when a package has been removed.
     *
     * @param pkg the removed package
     */
    void onPackageRemoved(@NonNull AndroidPackage pkg);

    /**
     * Callback when a package has been uninstalled.
     * <p>
     * The package may have been fully removed from the system, or only marked as uninstalled for
     * this user but still installed for other users.
     *
     * @param packageName the name of the uninstalled package
     * @param appId the app ID of the uninstalled package
     * @param packageState the uninstalled package
     * @param pkg the uninstalled package
     * @param sharedUserPkgs the packages that are in the same shared user
     * @param userId the user ID the package is uninstalled for
     */
    void onPackageUninstalled(@NonNull String packageName, int appId,
            @NonNull PackageState packageState, @Nullable AndroidPackage pkg,
            @NonNull List<AndroidPackage> sharedUserPkgs, @UserIdInt int userId);
}
