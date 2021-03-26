/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.permission;

import static android.os.Build.VERSION_CODES.S;

import android.Manifest;
import android.annotation.CheckResult;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.PropertyInvalidatedCache;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.Immutable;
import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * System level service for accessing the permission capabilities of the platform.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.PERMISSION_SERVICE)
public final class PermissionManager {
    private static final String LOG_TAG = PermissionManager.class.getName();

    /** @hide */
    public static final String LOG_TAG_TRACE_GRANTS = "PermissionGrantTrace";

    /** @hide */
    public static final String KILL_APP_REASON_PERMISSIONS_REVOKED =
            "permissions revoked";
    /** @hide */
    public static final String KILL_APP_REASON_GIDS_CHANGED =
            "permission grant or revoke changed gids";

    private static final String SYSTEM_PKG = "android";

    /**
     * Refuse to install package if groups of permissions are bad
     * - Permission groups should only be shared between apps sharing a certificate
     * - If a permission belongs to a group that group should be defined
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = S)
    public static final long CANNOT_INSTALL_WITH_BAD_PERMISSION_GROUPS = 146211400;

    /**
     * Note: Changing this won't do anything on its own - you should also change the filtering in
     * {@link #shouldTraceGrant}.
     *
     * See log output for tag {@link #LOG_TAG_TRACE_GRANTS}
     *
     * @hide
     */
    public static final boolean DEBUG_TRACE_GRANTS = false;
    /**
     * @hide
     */
    public static final boolean DEBUG_TRACE_PERMISSION_UPDATES = false;

    private final @NonNull Context mContext;

    private final IPackageManager mPackageManager;

    private final IPermissionManager mPermissionManager;

    private final LegacyPermissionManager mLegacyPermissionManager;

    private final ArrayMap<PackageManager.OnPermissionsChangedListener,
            IOnPermissionsChangeListener> mPermissionListeners = new ArrayMap<>();
    private PermissionUsageHelper mUsageHelper;

    private List<SplitPermissionInfo> mSplitPermissionInfos;

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate
     *
     * @hide
     */
    public PermissionManager(@NonNull Context context)
            throws ServiceManager.ServiceNotFoundException {
        mContext = context;
        mPackageManager = AppGlobals.getPackageManager();
        mPermissionManager = IPermissionManager.Stub.asInterface(ServiceManager.getServiceOrThrow(
                "permissionmgr"));
        mLegacyPermissionManager = context.getSystemService(LegacyPermissionManager.class);
    }

    /**
     * Retrieve all of the information we know about a particular permission.
     *
     * @param permissionName the fully qualified name (e.g. com.android.permission.LOGIN) of the
     *                       permission you are interested in
     * @param flags additional option flags to modify the data returned
     * @return a {@link PermissionInfo} containing information about the permission, or {@code null}
     *         if not found
     *
     * @hide Pending API
     */
    @Nullable
    public PermissionInfo getPermissionInfo(@NonNull String permissionName,
            @PackageManager.PermissionInfoFlags int flags) {
        try {
            final String packageName = mContext.getOpPackageName();
            return mPermissionManager.getPermissionInfo(permissionName, packageName, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query for all of the permissions associated with a particular group.
     *
     * @param groupName the fully qualified name (e.g. com.android.permission.LOGIN) of the
     *                  permission group you are interested in. Use {@code null} to find all of the
     *                  permissions not associated with a group
     * @param flags additional option flags to modify the data returned
     * @return a list of {@link PermissionInfo} containing information about all of the permissions
     *         in the given group, or {@code null} if the group is not found
     *
     * @hide Pending API
     */
    @Nullable
    public List<PermissionInfo> queryPermissionsByGroup(@NonNull String groupName,
            @PackageManager.PermissionInfoFlags int flags) {
        try {
            final ParceledListSlice<PermissionInfo> parceledList =
                    mPermissionManager.queryPermissionsByGroup(groupName, flags);
            if (parceledList == null) {
                return null;
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
     * @param permissionInfo description of the permission to be added
     * @param async whether the persistence of the permission should be asynchronous, allowing it to
     *              return quicker and batch a series of adds, at the expense of no guarantee the
     *              added permission will be retained if the device is rebooted before it is
     *              written.
     * @return {@code true} if a new permission was created, {@code false} if an existing one was
     *         updated
     * @throws SecurityException if you are not allowed to add the given permission name
     *
     * @see #removePermission(String)
     *
     * @hide Pending API
     */
    public boolean addPermission(@NonNull PermissionInfo permissionInfo, boolean async) {
        try {
            return mPermissionManager.addPermission(permissionInfo, async);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a permission that was previously added with
     * {@link #addPermission(PermissionInfo, boolean)}. The same ownership rules apply -- you are
     * only allowed to remove permissions that you are allowed to add.
     *
     * @param permissionName the name of the permission to remove
     * @throws SecurityException if you are not allowed to remove the given permission name
     *
     * @see #addPermission(PermissionInfo, boolean)
     *
     * @hide Pending API
     */
    public void removePermission(@NonNull String permissionName) {
        try {
            mPermissionManager.removePermission(permissionName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve all of the information we know about a particular group of permissions.
     *
     * @param groupName the fully qualified name (e.g. com.android.permission_group.APPS) of the
     *                  permission you are interested in
     * @param flags additional option flags to modify the data returned
     * @return a {@link PermissionGroupInfo} containing information about the permission, or
     *         {@code null} if not found
     *
     * @hide Pending API
     */
    @Nullable
    public PermissionGroupInfo getPermissionGroupInfo(@NonNull String groupName,
            @PackageManager.PermissionGroupInfoFlags int flags) {
        try {
            return mPermissionManager.getPermissionGroupInfo(groupName, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve all of the known permission groups in the system.
     *
     * @param flags additional option flags to modify the data returned
     * @return a list of {@link PermissionGroupInfo} containing information about all of the known
     *         permission groups
     *
     * @hide Pending API
     */
    @NonNull
    public List<PermissionGroupInfo> getAllPermissionGroups(
            @PackageManager.PermissionGroupInfoFlags int flags) {
        try {
            final ParceledListSlice<PermissionGroupInfo> parceledList =
                    mPermissionManager.getAllPermissionGroups(flags);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether a particular permissions has been revoked for a package by policy. Typically
     * the device owner or the profile owner may apply such a policy. The user cannot grant policy
     * revoked permissions, hence the only way for an app to get such a permission is by a policy
     * change.
     *
     * @param packageName the name of the package you are checking against
     * @param permissionName the name of the permission you are checking for
     *
     * @return whether the permission is restricted by policy
     *
     * @hide Pending API
     */
    @CheckResult
    public boolean isPermissionRevokedByPolicy(@NonNull String packageName,
            @NonNull String permissionName) {
        try {
            return mPermissionManager.isPermissionRevokedByPolicy(packageName, permissionName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static boolean shouldTraceGrant(
            @NonNull String packageName, @NonNull String permissionName, int userId) {
        // To be modified when debugging
        // template: if ("".equals(packageName) && "".equals(permissionName)) return true;
        return false;
    }

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
     * @param permissionName the permission name to grant
     * @param user the user for which to grant the permission
     *
     * @see #revokeRuntimePermission(String, String, android.os.UserHandle, String)
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    //@SystemApi
    public void grantRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName, @NonNull UserHandle user) {
        if (DEBUG_TRACE_GRANTS
                && shouldTraceGrant(packageName, permissionName, user.getIdentifier())) {
            Log.i(LOG_TAG_TRACE_GRANTS, "App " + mContext.getPackageName() + " is granting "
                    + packageName + " "
                    + permissionName + " for user " + user.getIdentifier(), new RuntimeException());
        }
        try {
            mPermissionManager.grantRuntimePermission(packageName, permissionName,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Revoke a runtime permission that was previously granted by
     * {@link #grantRuntimePermission(String, String, android.os.UserHandle)}. The permission must
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
     * @param user the user for which to revoke the permission
     * @param reason the reason for the revoke, or {@code null} for unspecified
     *
     * @see #grantRuntimePermission(String, String, android.os.UserHandle)
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
    //@SystemApi
    public void revokeRuntimePermission(@NonNull String packageName,
            @NonNull String permName, @NonNull UserHandle user, @Nullable String reason) {
        if (DEBUG_TRACE_PERMISSION_UPDATES
                && shouldTraceGrant(packageName, permName, user.getIdentifier())) {
            Log.i(LOG_TAG, "App " + mContext.getPackageName() + " is revoking " + packageName + " "
                    + permName + " for user " + user.getIdentifier() + " with reason "
                    + reason, new RuntimeException());
        }
        try {
            mPermissionManager
                    .revokeRuntimePermission(packageName, permName, user.getIdentifier(), reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the state flags associated with a permission.
     *
     * @param packageName the package name for which to get the flags
     * @param permissionName the permission for which to get the flags
     * @param user the user for which to get permission flags
     * @return the permission flags
     *
     * @hide
     */
    @PackageManager.PermissionFlags
    @RequiresPermission(anyOf = {
            android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            android.Manifest.permission.GET_RUNTIME_PERMISSIONS
    })
    //@SystemApi
    public int getPermissionFlags(@NonNull String packageName, @NonNull String permissionName,
            @NonNull UserHandle user) {
        try {
            return mPermissionManager.getPermissionFlags(packageName, permissionName,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the flags associated with a permission by replacing the flags in the specified mask
     * with the provided flag values.
     *
     * @param packageName The package name for which to update the flags
     * @param permissionName The permission for which to update the flags
     * @param flagMask The flags which to replace
     * @param flagValues The flags with which to replace
     * @param user The user for which to update the permission flags
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
    })
    //@SystemApi
    public void updatePermissionFlags(@NonNull String packageName, @NonNull String permissionName,
            @PackageManager.PermissionFlags int flagMask,
            @PackageManager.PermissionFlags int flagValues, @NonNull UserHandle user) {
        if (DEBUG_TRACE_PERMISSION_UPDATES && shouldTraceGrant(packageName, permissionName,
                user.getIdentifier())) {
            Log.i(LOG_TAG, "App " + mContext.getPackageName() + " is updating flags for "
                    + packageName + " " + permissionName + " for user "
                    + user.getIdentifier() + ": " + DebugUtils.flagsToString(
                    PackageManager.class, "FLAG_PERMISSION_", flagMask) + " := "
                    + DebugUtils.flagsToString(PackageManager.class, "FLAG_PERMISSION_",
                    flagValues), new RuntimeException());
        }
        try {
            final boolean checkAdjustPolicyFlagPermission =
                    mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.Q;
            mPermissionManager.updatePermissionFlags(packageName, permissionName, flagMask,
                    flagValues, checkAdjustPolicyFlagPermission, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
     * @param allowlistFlag the flag to determine which allowlist to query. Only one flag can be
     *                      passed.
     * @return the allowlisted permissions that are on any of the allowlists you query for
     * @throws SecurityException if you try to access a allowlist that you have no access to
     *
     * @see #addAllowlistedRestrictedPermission(String, String, int)
     * @see #removeAllowlistedRestrictedPermission(String, String, int)
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_INSTALLER
     *
     * @hide Pending API
     */
    @NonNull
    @RequiresPermission(value = Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS,
            conditional = true)
    public Set<String> getAllowlistedRestrictedPermissions(@NonNull String packageName,
            @PackageManager.PermissionWhitelistFlags int allowlistFlag) {
        try {
            final List<String> allowlist = mPermissionManager.getAllowlistedRestrictedPermissions(
                    packageName, allowlistFlag, mContext.getUserId());
            if (allowlist == null) {
                return Collections.emptySet();
            }
            return new ArraySet<>(allowlist);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds a allowlisted restricted permission for an app.
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
     * @param permissionName the allowlisted permission to add
     * @param allowlistFlags the allowlists to which to add. Passing multiple flags updates all
     *                       specified allowlists.
     * @return whether the permission was added to the allowlist
     * @throws SecurityException if you try to modify a allowlist that you have no access to.
     *
     * @see #getAllowlistedRestrictedPermissions(String, int)
     * @see #removeAllowlistedRestrictedPermission(String, String, int)
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_INSTALLER
     *
     * @hide Pending API
     */
    @RequiresPermission(value = Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS,
            conditional = true)
    public boolean addAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permissionName,
            @PackageManager.PermissionWhitelistFlags int allowlistFlags) {
        try {
            return mPermissionManager.addAllowlistedRestrictedPermission(packageName,
                    permissionName, allowlistFlags, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
     * @param permissionName the allowlisted permission to remove
     * @param allowlistFlags the allowlists from which to remove. Passing multiple flags updates all
     *                       specified allowlists.
     * @return whether the permission was removed from the allowlist
     * @throws SecurityException if you try to modify a allowlist that you have no access to.
     *
     * @see #getAllowlistedRestrictedPermissions(String, int)
     * @see #addAllowlistedRestrictedPermission(String, String, int)
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_SYSTEM
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_UPGRADE
     * @see PackageManager#FLAG_PERMISSION_WHITELIST_INSTALLER
     *
     * @hide Pending API
     */
    @RequiresPermission(value = Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS,
            conditional = true)
    public boolean removeAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permissionName,
            @PackageManager.PermissionWhitelistFlags int allowlistFlags) {
        try {
            return mPermissionManager.removeAllowlistedRestrictedPermission(packageName,
                    permissionName, allowlistFlags, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether an application is exempted from having its permissions be automatically
     * revoked when the app is unused for an extended period of time.
     * <p>
     * Only the installer on record that installed the given package, or a holder of
     * {@code WHITELIST_AUTO_REVOKE_PERMISSIONS} is allowed to call this.
     *
     * @param packageName the app for which to set exemption
     * @return whether the app is exempted
     * @throws SecurityException if you you have no access to this
     *
     * @see #setAutoRevokeExempted
     *
     * @hide Pending API
     */
    @RequiresPermission(value = Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS,
            conditional = true)
    public boolean isAutoRevokeExempted(@NonNull String packageName) {
        try {
            return mPermissionManager.isAutoRevokeExempted(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Marks an application exempted from having its permissions be automatically revoked when the
     * app is unused for an extended period of time.
     * <p>
     * Only the installer on record that installed the given package is allowed to call this.
     * <p>
     * Packages start in exempted state, and it is the installer's responsibility to un-exempt the
     * packages it installs, unless auto-revoking permissions from that package would cause
     * breakages beyond having to re-request the permission(s).
     *
     * @param packageName the app for which to set exemption
     * @param exempted whether the app should be exempted
     * @return whether any change took effect
     * @throws SecurityException if you you have no access to modify this
     *
     * @see #isAutoRevokeExempted
     *
     * @hide Pending API
     */
    @RequiresPermission(value = Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS,
            conditional = true)
    public boolean setAutoRevokeExempted(@NonNull String packageName, boolean exempted) {
        try {
            return mPermissionManager.setAutoRevokeExempted(packageName, exempted,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get whether you should show UI with rationale for requesting a permission. You should do this
     * only if you do not have the permission and the context in which the permission is requested
     * does not clearly communicate to the user what would be the benefit from grating this
     * permission.
     *
     * @param permissionName a permission your app wants to request
     * @return whether you can show permission rationale UI
     *
     * @hide
     */
    //@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public boolean shouldShowRequestPermissionRationale(@NonNull String permissionName) {
        try {
            final String packageName = mContext.getPackageName();
            return mPermissionManager.shouldShowRequestPermissionRationale(packageName,
                    permissionName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a listener for permission changes for installed packages.
     *
     * @param listener the listener to add
     *
     * @hide
     */
    //@SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS)
    public void addOnPermissionsChangeListener(
            @NonNull PackageManager.OnPermissionsChangedListener listener) {
        synchronized (mPermissionListeners) {
            if (mPermissionListeners.get(listener) != null) {
                return;
            }
            final OnPermissionsChangeListenerDelegate delegate =
                    new OnPermissionsChangeListenerDelegate(listener, Looper.getMainLooper());
            try {
                mPermissionManager.addOnPermissionsChangeListener(delegate);
                mPermissionListeners.put(listener, delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Remove a listener for permission changes for installed packages.
     *
     * @param listener the listener to remove
     *
     * @hide
     */
    //@SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS)
    public void removeOnPermissionsChangeListener(
            @NonNull PackageManager.OnPermissionsChangedListener listener) {
        synchronized (mPermissionListeners) {
            final IOnPermissionsChangeListener delegate = mPermissionListeners.get(listener);
            if (delegate != null) {
                try {
                    mPermissionManager.removeOnPermissionsChangeListener(delegate);
                    mPermissionListeners.remove(listener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Gets the version of the runtime permission database.
     *
     * @return The database version, -1 when this is an upgrade from pre-Q, 0 when this is a fresh
     * install.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
            Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS
    })
    public @IntRange(from = 0) int getRuntimePermissionsVersion() {
        try {
            return mPackageManager.getRuntimePermissionsVersion(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the version of the runtime permission database.
     *
     * @param version The new version.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
            Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS
    })
    public void setRuntimePermissionsVersion(@IntRange(from = 0) int version) {
        try {
            mPackageManager.setRuntimePermissionsVersion(version, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get set of permissions that have been split into more granular or dependent permissions.
     *
     * <p>E.g. before {@link android.os.Build.VERSION_CODES#Q} an app that was granted
     * {@link Manifest.permission#ACCESS_COARSE_LOCATION} could access the location while it was in
     * foreground and background. On platforms after {@link android.os.Build.VERSION_CODES#Q}
     * the location permission only grants location access while the app is in foreground. This
     * would break apps that target before {@link android.os.Build.VERSION_CODES#Q}. Hence whenever
     * such an old app asks for a location permission (i.e. the
     * {@link SplitPermissionInfo#getSplitPermission()}), then the
     * {@link Manifest.permission#ACCESS_BACKGROUND_LOCATION} permission (inside
     * {@link SplitPermissionInfo#getNewPermissions}) is added.
     *
     * <p>Note: Regular apps do not have to worry about this. The platform and permission controller
     * automatically add the new permissions where needed.
     *
     * @return All permissions that are split.
     */
    public @NonNull List<SplitPermissionInfo> getSplitPermissions() {
        if (mSplitPermissionInfos != null) {
            return mSplitPermissionInfos;
        }

        List<SplitPermissionInfoParcelable> parcelableList;
        try {
            parcelableList = ActivityThread.getPermissionManager().getSplitPermissions();
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Error getting split permissions", e);
            return Collections.emptyList();
        }

        mSplitPermissionInfos = splitPermissionInfoListToNonParcelableList(parcelableList);

        return mSplitPermissionInfos;
    }

    /**
     * @return A list of permission groups currently or recently used by all apps by all users in
     * the current profile group.
     *
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(Manifest.permission.GET_APP_OPS_STATS)
    public List<PermGroupUsage> getIndicatorAppOpUsageData() {
        // Lazily initialize the usage helper
        if (mUsageHelper == null) {
            mUsageHelper = new PermissionUsageHelper(mContext);
        }
        return mUsageHelper.getOpUsageData(new AudioManager().isMicrophoneMute());
    }

    /**
     * Check if this package/op combination is exempted from indicators
     * @return
     * @hide
     */
    public static boolean isSpecialCaseShownIndicator(@NonNull Context context,
            @NonNull String packageName) {

        if (packageName.equals(SYSTEM_PKG)) {
            return false;
        }

        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY, "permissions_hub_2_enabled",
                false)
                || packageName.equals(context.getString(R.string.config_systemSpeechRecognizer))
                || context.getSystemService(LocationManager.class).isProviderPackage(packageName);
    }
    /**
     * Gets the list of packages that have permissions that specified
     * {@code requestDontAutoRevokePermissions=true} in their
     * {@code application} manifest declaration.
     *
     * @return the list of packages for current user
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
    public Set<String> getAutoRevokeExemptionRequestedPackages() {
        try {
            return CollectionUtils.toSet(mPermissionManager.getAutoRevokeExemptionRequestedPackages(
                    mContext.getUser().getIdentifier()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the list of packages that have permissions that specified
     * {@code autoRevokePermissions=disallowed} in their
     * {@code application} manifest declaration.
     *
     * @return the list of packages for current user
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
    public Set<String> getAutoRevokeExemptionGrantedPackages() {
        try {
            return CollectionUtils.toSet(mPermissionManager.getAutoRevokeExemptionGrantedPackages(
                    mContext.getUser().getIdentifier()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private List<SplitPermissionInfo> splitPermissionInfoListToNonParcelableList(
            List<SplitPermissionInfoParcelable> parcelableList) {
        final int size = parcelableList.size();
        List<SplitPermissionInfo> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new SplitPermissionInfo(parcelableList.get(i)));
        }
        return list;
    }

    /**
     * Converts a {@link List} of {@link SplitPermissionInfo} into a List of
     * {@link SplitPermissionInfoParcelable} and returns it.
     * @hide
     */
    public static List<SplitPermissionInfoParcelable> splitPermissionInfoListToParcelableList(
            List<SplitPermissionInfo> splitPermissionsList) {
        final int size = splitPermissionsList.size();
        List<SplitPermissionInfoParcelable> outList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            SplitPermissionInfo info = splitPermissionsList.get(i);
            outList.add(new SplitPermissionInfoParcelable(
                    info.getSplitPermission(), info.getNewPermissions(), info.getTargetSdk()));
        }
        return outList;
    }

    /**
     * A permission that was added in a previous API level might have split into several
     * permissions. This object describes one such split.
     */
    @Immutable
    public static final class SplitPermissionInfo {
        private @NonNull final SplitPermissionInfoParcelable mSplitPermissionInfoParcelable;

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SplitPermissionInfo that = (SplitPermissionInfo) o;
            return mSplitPermissionInfoParcelable.equals(that.mSplitPermissionInfoParcelable);
        }

        @Override
        public int hashCode() {
            return mSplitPermissionInfoParcelable.hashCode();
        }

        /**
         * Get the permission that is split.
         */
        public @NonNull String getSplitPermission() {
            return mSplitPermissionInfoParcelable.getSplitPermission();
        }

        /**
         * Get the permissions that are added.
         */
        public @NonNull List<String> getNewPermissions() {
            return mSplitPermissionInfoParcelable.getNewPermissions();
        }

        /**
         * Get the target API level when the permission was split.
         */
        public int getTargetSdk() {
            return mSplitPermissionInfoParcelable.getTargetSdk();
        }

        /**
         * Constructs a split permission.
         *
         * @param splitPerm old permission that will be split
         * @param newPerms list of new permissions that {@code rootPerm} will be split into
         * @param targetSdk apps targetting SDK versions below this will have {@code rootPerm}
         * split into {@code newPerms}
         * @hide
         */
        public SplitPermissionInfo(@NonNull String splitPerm, @NonNull List<String> newPerms,
                int targetSdk) {
            this(new SplitPermissionInfoParcelable(splitPerm, newPerms, targetSdk));
        }

        private SplitPermissionInfo(@NonNull SplitPermissionInfoParcelable parcelable) {
            mSplitPermissionInfoParcelable = parcelable;
        }
    }

    /**
     * Starts a one-time permission session for a given package. A one-time permission session is
     * ended if app becomes inactive. Inactivity is defined as the package's uid importance level
     * staying > importanceToResetTimer for timeoutMillis milliseconds. If the package's uid
     * importance level goes <= importanceToResetTimer then the timer is reset and doesn't start
     * until going > importanceToResetTimer.
     * <p>
     * When this timeoutMillis is reached if the importance level is <= importanceToKeepSessionAlive
     * then the session is extended until either the importance goes above
     * importanceToKeepSessionAlive which will end the session or <= importanceToResetTimer which
     * will continue the session and reset the timer.
     * </p>
     * <p>
     * Importance levels are defined in {@link android.app.ActivityManager.RunningAppProcessInfo}.
     * </p>
     * <p>
     * Once the session ends
     * {@link PermissionControllerService#onOneTimePermissionSessionTimeout(String)} is invoked.
     * </p>
     * <p>
     * Note that if there is currently an active session for a package a new one isn't created and
     * the existing one isn't changed.
     * </p>
     * @param packageName The package to start a one-time permission session for
     * @param timeoutMillis Number of milliseconds for an app to be in an inactive state
     * @param importanceToResetTimer The least important level to uid must be to reset the timer
     * @param importanceToKeepSessionAlive The least important level the uid must be to keep the
     *                                    session alive
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS)
    public void startOneTimePermissionSession(@NonNull String packageName, long timeoutMillis,
            @ActivityManager.RunningAppProcessInfo.Importance int importanceToResetTimer,
            @ActivityManager.RunningAppProcessInfo.Importance int importanceToKeepSessionAlive) {
        try {
            mPermissionManager.startOneTimePermissionSession(packageName, mContext.getUserId(),
                    timeoutMillis, importanceToResetTimer, importanceToKeepSessionAlive);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the one-time permission session for the package. The callback to the end of session is
     * not invoked. If there is no one-time session for the package then nothing happens.
     *
     * @param packageName Package to stop the one-time permission session for
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS)
    public void stopOneTimePermissionSession(@NonNull String packageName) {
        try {
            mPermissionManager.stopOneTimePermissionSession(packageName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the package with the given pid/uid can read device identifiers.
     *
     * @param packageName      the name of the package to be checked for identifier access
     * @param message          the message to be used for logging during identifier access
     *                         verification
     * @param callingFeatureId the feature in the package
     * @param pid              the process id of the package to be checked
     * @param uid              the uid of the package to be checked
     * @return {@link PackageManager#PERMISSION_GRANTED} if the package is allowed identifier
     * access, {@link PackageManager#PERMISSION_DENIED} otherwise
     * @hide
     */
    @SystemApi
    public int checkDeviceIdentifierAccess(@Nullable String packageName, @Nullable String message,
            @Nullable String callingFeatureId, int pid, int uid) {
        return mLegacyPermissionManager.checkDeviceIdentifierAccess(packageName, message,
                callingFeatureId, pid, uid);
    }

    /* @hide */
    private static int checkPermissionUncached(@Nullable String permission, int pid, int uid) {
        final IActivityManager am = ActivityManager.getService();
        if (am == null) {
            // Well this is super awkward; we somehow don't have an active ActivityManager
            // instance. If we're testing a root or system UID, then they totally have whatever
            // permission this is.
            final int appId = UserHandle.getAppId(uid);
            if (appId == Process.ROOT_UID || appId == Process.SYSTEM_UID) {
                Slog.w(LOG_TAG, "Missing ActivityManager; assuming " + uid + " holds "
                        + permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            Slog.w(LOG_TAG, "Missing ActivityManager; assuming " + uid + " does not hold "
                    + permission);
            return PackageManager.PERMISSION_DENIED;
        }
        try {
            return am.checkPermission(permission, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Identifies a permission query.
     *
     * N.B. we include the checking pid for tracking purposes but don't include it in the equality
     * comparison: we use only uid for the actual security check, so comparing pid would result
     * in spurious misses.
     *
     * @hide
     */
    @Immutable
    private static final class PermissionQuery {
        final String permission;
        final int pid;
        final int uid;

        PermissionQuery(@Nullable String permission, int pid, int uid) {
            this.permission = permission;
            this.pid = pid;
            this.uid = uid;
        }

        @Override
        public String toString() {
            return String.format("PermissionQuery(permission=\"%s\", pid=%s, uid=%s)",
                    permission, pid, uid);
        }

        @Override
        public int hashCode() {
            // N.B. pid doesn't count toward equality and therefore shouldn't count for
            // hashing either.
            int hash = Objects.hashCode(permission);
            hash = hash * 13 + Objects.hashCode(uid);
            return hash;
        }

        @Override
        public boolean equals(@Nullable Object rval) {
            // N.B. pid doesn't count toward equality!
            if (rval == null) {
                return false;
            }
            PermissionQuery other;
            try {
                other = (PermissionQuery) rval;
            } catch (ClassCastException ex) {
                return false;
            }
            return uid == other.uid
                    && Objects.equals(permission, other.permission);
        }
    }

    /** @hide */
    public static final String CACHE_KEY_PACKAGE_INFO = "cache_key.package_info";

    /** @hide */
    private static final PropertyInvalidatedCache<PermissionQuery, Integer> sPermissionCache =
            new PropertyInvalidatedCache<PermissionQuery, Integer>(
                    2048, CACHE_KEY_PACKAGE_INFO, "checkPermission") {
                @Override
                protected Integer recompute(PermissionQuery query) {
                    return checkPermissionUncached(query.permission, query.pid, query.uid);
                }
            };

    /** @hide */
    public static int checkPermission(@Nullable String permission, int pid, int uid) {
        return sPermissionCache.query(new PermissionQuery(permission, pid, uid));
    }

    /**
     * Make checkPermission() above bypass the permission cache in this process.
     *
     * @hide
     */
    public static void disablePermissionCache() {
        sPermissionCache.disableLocal();
    }

    /**
     * Like PermissionQuery, but for permission checks based on a package name instead of
     * a UID.
     */
    @Immutable
    private static final class PackageNamePermissionQuery {
        final String permName;
        final String pkgName;
        final int userId;

        PackageNamePermissionQuery(@Nullable String permName, @Nullable String pkgName,
                @UserIdInt int userId) {
            this.permName = permName;
            this.pkgName = pkgName;
            this.userId = userId;
        }

        @Override
        public String toString() {
            return String.format(
                    "PackageNamePermissionQuery(pkgName=\"%s\", permName=\"%s, userId=%s\")",
                    pkgName, permName, userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(permName, pkgName, userId);
        }

        @Override
        public boolean equals(@Nullable Object rval) {
            if (rval == null) {
                return false;
            }
            PackageNamePermissionQuery other;
            try {
                other = (PackageNamePermissionQuery) rval;
            } catch (ClassCastException ex) {
                return false;
            }
            return Objects.equals(permName, other.permName)
                    && Objects.equals(pkgName, other.pkgName)
                    && userId == other.userId;
        }
    }

    /* @hide */
    private static int checkPackageNamePermissionUncached(
            String permName, String pkgName, @UserIdInt int userId) {
        try {
            return ActivityThread.getPackageManager().checkPermission(
                    permName, pkgName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /* @hide */
    private static PropertyInvalidatedCache<PackageNamePermissionQuery, Integer>
            sPackageNamePermissionCache =
            new PropertyInvalidatedCache<PackageNamePermissionQuery, Integer>(
                    16, CACHE_KEY_PACKAGE_INFO, "checkPackageNamePermission") {
                @Override
                protected Integer recompute(PackageNamePermissionQuery query) {
                    return checkPackageNamePermissionUncached(
                            query.permName, query.pkgName, query.userId);
                }
            };

    /**
     * Check whether a package has a permission.
     *
     * @hide
     */
    public static int checkPackageNamePermission(String permName, String pkgName,
            @UserIdInt int userId) {
        return sPackageNamePermissionCache.query(
                new PackageNamePermissionQuery(permName, pkgName, userId));
    }

    /**
     * Make checkPackageNamePermission() bypass the cache in this process.
     *
     * @hide
     */
    public static void disablePackageNamePermissionCache() {
        sPackageNamePermissionCache.disableLocal();
    }

    private final class OnPermissionsChangeListenerDelegate
            extends IOnPermissionsChangeListener.Stub implements Handler.Callback{
        private static final int MSG_PERMISSIONS_CHANGED = 1;

        private final PackageManager.OnPermissionsChangedListener mListener;
        private final Handler mHandler;

        public OnPermissionsChangeListenerDelegate(
                PackageManager.OnPermissionsChangedListener listener, Looper looper) {
            mListener = listener;
            mHandler = new Handler(looper, this);
        }

        @Override
        public void onPermissionsChanged(int uid) {
            mHandler.obtainMessage(MSG_PERMISSIONS_CHANGED, uid, 0).sendToTarget();
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PERMISSIONS_CHANGED: {
                    final int uid = msg.arg1;
                    mListener.onPermissionsChanged(uid);
                    return true;
                }
                default:
                    return false;
            }
        }
    }
}
