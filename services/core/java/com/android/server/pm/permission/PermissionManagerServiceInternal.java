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

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.permission.PermissionManagerInternal;

import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Internal interfaces services.
 *
 * TODO: Should be merged into PermissionManagerInternal, but currently uses internal classes.
 */
public abstract class PermissionManagerServiceInternal extends PermissionManagerInternal {

    /**
     * Provider for package names.
     */
    public interface PackagesProvider {

        /**
         * Gets the packages for a given user.
         * @param userId The user id.
         * @return The package names.
         */
        String[] getPackages(int userId);
    }

    /**
     * Provider for package names.
     */
    public interface SyncAdapterPackagesProvider {

        /**
         * Gets the sync adapter packages for given authority and user.
         * @param authority The authority.
         * @param userId The user id.
         * @return The package names.
         */
        String[] getPackages(String authority, int userId);
    }

    /**
     * Provider for default browser
     */
    public interface DefaultBrowserProvider {

        /**
         * Get the package name of the default browser.
         *
         * @param userId the user id
         *
         * @return the package name of the default browser, or {@code null} if none
         */
        @Nullable
        String getDefaultBrowser(@UserIdInt int userId);

        /**
         * Set the package name of the default browser.
         *
         * @param packageName package name of the default browser, or {@code null} to remove
         * @param userId the user id
         *
         * @return whether the default browser was successfully set.
         */
        boolean setDefaultBrowser(@Nullable String packageName, @UserIdInt int userId);

        /**
         * Set the package name of the default browser asynchronously.
         *
         * @param packageName package name of the default browser, or {@code null} to remove
         * @param userId the user id
         */
        void setDefaultBrowserAsync(@Nullable String packageName, @UserIdInt int userId);
    }

    /**
     * Provider for default dialer
     */
    public interface DefaultDialerProvider {

        /**
         * Get the package name of the default dialer.
         *
         * @param userId the user id
         *
         * @return the package name of the default dialer, or {@code null} if none
         */
        @Nullable
        String getDefaultDialer(@UserIdInt int userId);
    }

    /**
     * Provider for default home
     */
    public interface DefaultHomeProvider {

        /**
         * Get the package name of the default home.
         *
         * @param userId the user id
         *
         * @return the package name of the default home, or {@code null} if none
         */
        @Nullable
        String getDefaultHome(@UserIdInt int userId);

        /**
         * Set the package name of the default home.
         *
         * @param packageName package name of the default home, or {@code null} to remove
         * @param userId the user id
         * @param callback the callback made after the default home as been updated
         */
        void setDefaultHomeAsync(@Nullable String packageName, @UserIdInt int userId,
                @NonNull Consumer<Boolean> callback);
    }

    /**
     * Callbacks invoked when interesting actions have been taken on a permission.
     * <p>
     * NOTE: The current arguments are merely to support the existing use cases. This
     * needs to be properly thought out with appropriate arguments for each of the
     * callback methods.
     */
    public static class PermissionCallback {
        public void onGidsChanged(@AppIdInt int appId, @UserIdInt int userId) {
        }
        public void onPermissionChanged() {
        }
        public void onPermissionGranted(int uid, @UserIdInt int userId) {
        }
        public void onInstallPermissionGranted() {
        }
        public void onPermissionRevoked(int uid, @UserIdInt int userId, String reason) {
        }
        public void onInstallPermissionRevoked() {
        }
        public void onPermissionUpdated(@UserIdInt int[] updatedUserIds, boolean sync) {
        }
        public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds, boolean sync,
                int uid) {
            onPermissionUpdated(updatedUserIds, sync);
        }
        public void onPermissionRemoved() {
        }
        public void onInstallPermissionUpdated() {
        }
        public void onInstallPermissionUpdatedNotifyListener(int uid) {
            onInstallPermissionUpdated();
        }
    }

    public abstract void systemReady();

    public abstract boolean isPermissionsReviewRequired(@NonNull AndroidPackage pkg,
            @UserIdInt int userId);

    public abstract void grantRequestedRuntimePermissions(
            @NonNull AndroidPackage pkg, @NonNull int[] userIds,
            @NonNull String[] grantedPermissions, int callingUid);
    public abstract void setWhitelistedRestrictedPermissions(
            @NonNull AndroidPackage pkg, @NonNull int[] userIds,
            @NonNull List<String> permissions, int callingUid,
            @PackageManager.PermissionWhitelistFlags int whitelistFlags);
    /** Sets the whitelisted, restricted permissions for the given package. */
    public abstract void setWhitelistedRestrictedPermissions(
            @NonNull String packageName, @NonNull List<String> permissions,
            @PackageManager.PermissionWhitelistFlags int flags, int userId);
    public abstract void setAutoRevokeWhitelisted(
            @NonNull String packageName, boolean whitelisted, int userId);

    /**
     * Update permissions when a package changed.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param packageName The package that is updated
     * @param pkg The package that is updated, or {@code null} if package is deleted
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    public abstract void updatePermissions(@NonNull String packageName,
            @Nullable AndroidPackage pkg);

    /**
     * Update all permissions for all apps.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param volumeUuid The volume of the packages to be updated, {@code null} for all volumes
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    public abstract void updateAllPermissions(@Nullable String volumeUuid, boolean sdkUpdate);

    /**
     * Resets any user permission state changes (eg. permissions and flags) of all
     * packages installed for the given user.
     *
     * @see #resetRuntimePermissions(AndroidPackage, int)
     */
    public abstract void resetAllRuntimePermissions(@UserIdInt int userId);

    /**
     * Resets any user permission state changes (eg. permissions and flags) of the
     * specified package for the given user.
     */
    public abstract void resetRuntimePermissions(@NonNull AndroidPackage pkg,
            @UserIdInt int userId);

    /**
     * We might auto-grant permissions if any permission of the group is already granted. Hence if
     * the group of a granted permission changes we need to revoke it to avoid having permissions of
     * the new group auto-granted.
     *
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     * @param allPackageNames All packages
     */
    public abstract void revokeRuntimePermissionsIfGroupChanged(
            @NonNull AndroidPackage newPackage,
            @NonNull AndroidPackage oldPackage,
            @NonNull ArrayList<String> allPackageNames);

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
            @NonNull ArrayList<String> allPackageNames);

    /**
     * If the app is updated, and has scoped storage permissions, then it is possible that the
     * app updated in an attempt to get unscoped storage. If so, revoke all storage permissions.
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     */
    public abstract void revokeStoragePermissionsIfScopeExpanded(
            @NonNull AndroidPackage newPackage,
            @NonNull AndroidPackage oldPackage
    );

    /**
     * Add all permissions in the given package.
     * <p>
     * NOTE: argument {@code groupTEMP} is temporary until mPermissionGroups is moved to
     * the permission settings.
     *
     * @return A list of BasePermissions that were updated, and need to be revoked from packages
     */
    public abstract List<String> addAllPermissions(@NonNull AndroidPackage pkg, boolean chatty);
    public abstract void addAllPermissionGroups(@NonNull AndroidPackage pkg, boolean chatty);
    public abstract void removeAllPermissions(@NonNull AndroidPackage pkg, boolean chatty);

    /** Retrieve the packages that have requested the given app op permission */
    public abstract @Nullable String[] getAppOpPermissionPackages(
            @NonNull String permName, int callingUid);

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userid} is not for the caller.
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    public abstract void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, boolean checkShell, @NonNull String message);

    /**
     * Similar to {@link #enforceCrossUserPermission(int, int, boolean, boolean, String)}
     * but also allows INTERACT_ACROSS_PROFILES permission if calling user and {@code userId} are
     * in the same profile group.
     */
    public abstract void enforceCrossUserOrProfilePermission(int callingUid, int userId,
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

    /** Grants default browser permissions to the given package */
    public abstract void grantDefaultPermissionsToDefaultBrowser(
            @NonNull String packageName, @UserIdInt int userId);

    /** HACK HACK methods to allow for partial migration of data to the PermissionManager class */
    public abstract @Nullable BasePermission getPermissionTEMP(@NonNull String permName);

    /** Get all permissions that have a certain protection */
    public abstract @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtection(
            @PermissionInfo.Protection int protection);

    /** Get all permissions that have certain protection flags */
    public abstract @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtectionFlags(
            @PermissionInfo.ProtectionFlags int protectionFlags);

    /**
     * Returns the delegate used to influence permission checking.
     *
     * @return The delegate instance.
     */
    public abstract @Nullable CheckPermissionDelegate getCheckPermissionDelegate();

    /**
     * Sets the delegate used to influence permission checking.
     *
     * @param delegate A delegate instance or {@code null} to clear.
     */
    public abstract void setCheckPermissionDelegate(@Nullable CheckPermissionDelegate delegate);

    /**
     * Sets the dialer application packages provider.
     * @param provider The provider.
     */
    public abstract void setDialerAppPackagesProvider(PackagesProvider provider);

    /**
     * Set the location extra packages provider.
     * @param provider The packages provider.
     */
    public abstract  void setLocationExtraPackagesProvider(PackagesProvider provider);

    /**
     * Sets the location provider packages provider.
     * @param provider The packages provider.
     */
    public abstract void setLocationPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SIM call manager packages provider.
     * @param provider The provider.
     */
    public abstract void setSimCallManagerPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SMS application packages provider.
     * @param provider The provider.
     */
    public abstract void setSmsAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the sync adapter packages provider.
     * @param provider The provider.
     */
    public abstract void setSyncAdapterPackagesProvider(SyncAdapterPackagesProvider provider);

    /**
     * Sets the Use Open Wifi packages provider.
     * @param provider The packages provider.
     */
    public abstract void setUseOpenWifiAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the voice interaction packages provider.
     * @param provider The packages provider.
     */
    public abstract void setVoiceInteractionPackagesProvider(PackagesProvider provider);

    /**
     * Sets the default browser provider.
     *
     * @param provider the provider
     */
    public abstract void setDefaultBrowserProvider(@NonNull DefaultBrowserProvider provider);

    /**
     * Sets the package name of the default browser provider for the given user.
     *
     * @param packageName The package name of the default browser or {@code null}
     *          to clear the default browser
     * @param async If {@code true}, set the default browser asynchronously,
     *          otherwise set it synchronously
     * @param doGrant If {@code true} and if {@code packageName} is not {@code null},
     *          perform default permission grants on the browser, otherwise skip the
     *          default permission grants.
     * @param userId The user to set the default browser for.
     */
    public abstract void setDefaultBrowser(@Nullable String packageName, boolean async,
            boolean doGrant, @UserIdInt int userId);

    /**
     * Sets the default dialer provider.
     *
     * @param provider the provider
     */
    public abstract void setDefaultDialerProvider(@NonNull DefaultDialerProvider provider);

    /**
     * Sets the default home provider.
     *
     * @param provider the provider
     */
    public abstract void setDefaultHomeProvider(@NonNull DefaultHomeProvider provider);

    /**
     * Asynchronously sets the package name of the default home provider for the given user.
     *
     * @param packageName The package name of the default home or {@code null}
     *          to clear the default browser
     * @param userId The user to set the default browser for
     * @param callback Invoked after the default home has been set
     */
    public abstract void setDefaultHome(@Nullable String packageName, @UserIdInt int userId,
            @NonNull Consumer<Boolean> callback);

    /**
     * Returns the default browser package name for the given user.
     */
    @Nullable
    public abstract String getDefaultBrowser(@UserIdInt int userId);

    /**
     * Returns the default dialer package name for the given user.
     */
    @Nullable
    public abstract String getDefaultDialer(@UserIdInt int userId);

    /**
     * Returns the default home package name for the given user.
     */
    @Nullable
    public abstract String getDefaultHome(@UserIdInt int userId);

    /**
     * Requests granting of the default permissions to the current default Use Open Wifi app.
     * @param packageName The default use open wifi package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultSimCallManager(
            @NonNull String packageName, @UserIdInt int userId);

    /**
     * Requests granting of the default permissions to the current default Use Open Wifi app.
     * @param packageName The default use open wifi package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultUseOpenWifiApp(
            @NonNull String packageName, @UserIdInt int userId);

    /** Called when a new user has been created. */
    public abstract void onNewUserCreated(@UserIdInt int userId);

    /**
     * Removes invalid permissions which are not {@link PermissionInfo#FLAG_HARD_RESTRICTED} or
     * {@link PermissionInfo#FLAG_SOFT_RESTRICTED} from the input.
     */
    public abstract void retainHardAndSoftRestrictedPermissions(@NonNull List<String> permissions);
}
