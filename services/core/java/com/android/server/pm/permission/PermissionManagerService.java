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

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_PERMISSIONS;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageParser.Package;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.permission.DefaultPermissionGrantPolicy.DefaultPermissionGrantedCallback;
import com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback;
import com.android.server.pm.permission.PermissionsState.PermissionState;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages all permissions and handles permissions related tasks.
 */
public class PermissionManagerService {
    private static final String TAG = "PackageManager";

    /** Permission grant: not grant the permission. */
    private static final int GRANT_DENIED = 1;
    /** Permission grant: grant the permission as an install permission. */
    private static final int GRANT_INSTALL = 2;
    /** Permission grant: grant the permission as a runtime one. */
    private static final int GRANT_RUNTIME = 3;
    /** Permission grant: grant as runtime a permission that was granted as an install time one. */
    private static final int GRANT_UPGRADE = 4;

    /** Cap the size of permission trees that 3rd party apps can define; in characters of text */
    private static final int MAX_PERMISSION_TREE_FOOTPRINT = 32768;
    /** Empty array to avoid allocations */
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /** Lock to protect internal data access */
    private final Object mLock;

    /** Internal connection to the package manager */
    private final PackageManagerInternal mPackageManagerInt;

    /** Internal connection to the user manager */
    private final UserManagerInternal mUserManagerInt;

    /** Default permission policy to provide proper behaviour out-of-the-box */
    private final DefaultPermissionGrantPolicy mDefaultPermissionGrantPolicy;

    /**
     * Built-in permissions. Read from system configuration files. Mapping is from
     * UID to permission name.
     */
    private final SparseArray<ArraySet<String>> mSystemPermissions;

    /** Built-in group IDs given to all packages. Read from system configuration files. */
    private final int[] mGlobalGids;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Context mContext;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    /** Internal storage for permissions and related settings */
    @GuardedBy("mLock")
    private final PermissionSettings mSettings;

    @GuardedBy("mLock")
    private ArraySet<String> mPrivappPermissionsViolations;

    @GuardedBy("mLock")
    private boolean mSystemReady;

    PermissionManagerService(Context context,
            @Nullable DefaultPermissionGrantedCallback defaultGrantCallback,
            @NonNull Object externalLock) {
        mContext = context;
        mLock = externalLock;
        mPackageManagerInt = LocalServices.getService(PackageManagerInternal.class);
        mUserManagerInt = LocalServices.getService(UserManagerInternal.class);
        mSettings = new PermissionSettings(context, mLock);

        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        Watchdog.getInstance().addThread(mHandler);

        mDefaultPermissionGrantPolicy = new DefaultPermissionGrantPolicy(
                context, mHandlerThread.getLooper(), defaultGrantCallback, this);
        SystemConfig systemConfig = SystemConfig.getInstance();
        mSystemPermissions = systemConfig.getSystemPermissions();
        mGlobalGids = systemConfig.getGlobalGids();

        // propagate permission configuration
        final ArrayMap<String, SystemConfig.PermissionEntry> permConfig =
                SystemConfig.getInstance().getPermissions();
        synchronized (mLock) {
            for (int i=0; i<permConfig.size(); i++) {
                final SystemConfig.PermissionEntry perm = permConfig.valueAt(i);
                BasePermission bp = mSettings.getPermissionLocked(perm.name);
                if (bp == null) {
                    bp = new BasePermission(perm.name, "android", BasePermission.TYPE_BUILTIN);
                    mSettings.putPermissionLocked(perm.name, bp);
                }
                if (perm.gids != null) {
                    bp.setGids(perm.gids, perm.perUser);
                }
            }
        }

        LocalServices.addService(
                PermissionManagerInternal.class, new PermissionManagerInternalImpl());
    }

    /**
     * Creates and returns an initialized, internal service for use by other components.
     * <p>
     * The object returned is identical to the one returned by the LocalServices class using:
     * {@code LocalServices.getService(PermissionManagerInternal.class);}
     * <p>
     * NOTE: The external lock is temporary and should be removed. This needs to be a
     * lock created by the permission manager itself.
     */
    public static PermissionManagerInternal create(Context context,
            @Nullable DefaultPermissionGrantedCallback defaultGrantCallback,
            @NonNull Object externalLock) {
        final PermissionManagerInternal permMgrInt =
                LocalServices.getService(PermissionManagerInternal.class);
        if (permMgrInt != null) {
            return permMgrInt;
        }
        new PermissionManagerService(context, defaultGrantCallback, externalLock);
        return LocalServices.getService(PermissionManagerInternal.class);
    }

    @Nullable BasePermission getPermission(String permName) {
        synchronized (mLock) {
            return mSettings.getPermissionLocked(permName);
        }
    }

    private int checkPermission(String permName, String pkgName, int callingUid, int userId) {
        if (!mUserManagerInt.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        final PackageParser.Package pkg = mPackageManagerInt.getPackage(pkgName);
        if (pkg != null && pkg.mExtras != null) {
            if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
                return PackageManager.PERMISSION_DENIED;
            }
            final PackageSetting ps = (PackageSetting) pkg.mExtras;
            final boolean instantApp = ps.getInstantApp(userId);
            final PermissionsState permissionsState = ps.getPermissionsState();
            if (permissionsState.hasPermission(permName, userId)) {
                if (instantApp) {
                    synchronized (mLock) {
                        BasePermission bp = mSettings.getPermissionLocked(permName);
                        if (bp != null && bp.isInstant()) {
                            return PackageManager.PERMISSION_GRANTED;
                        }
                    }
                } else {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
            // Special case: ACCESS_FINE_LOCATION permission includes ACCESS_COARSE_LOCATION
            if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permName) && permissionsState
                    .hasPermission(Manifest.permission.ACCESS_FINE_LOCATION, userId)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }

        return PackageManager.PERMISSION_DENIED;
    }

    private int checkUidPermission(String permName, PackageParser.Package pkg, int uid,
            int callingUid) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        final boolean isCallerInstantApp =
                mPackageManagerInt.getInstantAppPackageName(callingUid) != null;
        final boolean isUidInstantApp =
                mPackageManagerInt.getInstantAppPackageName(uid) != null;
        final int userId = UserHandle.getUserId(uid);
        if (!mUserManagerInt.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        if (pkg != null) {
            if (pkg.mSharedUserId != null) {
                if (isCallerInstantApp) {
                    return PackageManager.PERMISSION_DENIED;
                }
            } else if (mPackageManagerInt.filterAppAccess(pkg, callingUid, callingUserId)) {
                return PackageManager.PERMISSION_DENIED;
            }
            final PermissionsState permissionsState =
                    ((PackageSetting) pkg.mExtras).getPermissionsState();
            if (permissionsState.hasPermission(permName, userId)) {
                if (isUidInstantApp) {
                    if (mSettings.isPermissionInstant(permName)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                } else {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
            // Special case: ACCESS_FINE_LOCATION permission includes ACCESS_COARSE_LOCATION
            if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permName) && permissionsState
                    .hasPermission(Manifest.permission.ACCESS_FINE_LOCATION, userId)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        } else {
            ArraySet<String> perms = mSystemPermissions.get(uid);
            if (perms != null) {
                if (perms.contains(permName)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permName) && perms
                        .contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    private PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags,
            int callingUid) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (mLock) {
            return PackageParser.generatePermissionGroupInfo(
                    mSettings.mPermissionGroups.get(groupName), flags);
        }
    }

    private List<PermissionGroupInfo> getAllPermissionGroups(int flags, int callingUid) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (mLock) {
            final int N = mSettings.mPermissionGroups.size();
            final ArrayList<PermissionGroupInfo> out
                    = new ArrayList<PermissionGroupInfo>(N);
            for (PackageParser.PermissionGroup pg : mSettings.mPermissionGroups.values()) {
                out.add(PackageParser.generatePermissionGroupInfo(pg, flags));
            }
            return out;
        }
    }

    private PermissionInfo getPermissionInfo(String permName, String packageName, int flags,
            int callingUid) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        // reader
        synchronized (mLock) {
            final BasePermission bp = mSettings.getPermissionLocked(permName);
            if (bp == null) {
                return null;
            }
            final int adjustedProtectionLevel = adjustPermissionProtectionFlagsLocked(
                    bp.getProtectionLevel(), packageName, callingUid);
            return bp.generatePermissionInfo(adjustedProtectionLevel, flags);
        }
    }

    private List<PermissionInfo> getPermissionInfoByGroup(
            String groupName, int flags, int callingUid) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (mLock) {
            if (groupName != null && !mSettings.mPermissionGroups.containsKey(groupName)) {
                return null;
            }
            final ArrayList<PermissionInfo> out = new ArrayList<PermissionInfo>(10);
            for (BasePermission bp : mSettings.mPermissions.values()) {
                final PermissionInfo pi = bp.generatePermissionInfo(groupName, flags);
                if (pi != null) {
                    out.add(pi);
                }
            }
            return out;
        }
    }

    private int adjustPermissionProtectionFlagsLocked(
            int protectionLevel, String packageName, int uid) {
        // Signature permission flags area always reported
        final int protectionLevelMasked = protectionLevel
                & (PermissionInfo.PROTECTION_NORMAL
                | PermissionInfo.PROTECTION_DANGEROUS
                | PermissionInfo.PROTECTION_SIGNATURE);
        if (protectionLevelMasked == PermissionInfo.PROTECTION_SIGNATURE) {
            return protectionLevel;
        }
        // System sees all flags.
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.SYSTEM_UID || appId == Process.ROOT_UID
                || appId == Process.SHELL_UID) {
            return protectionLevel;
        }
        // Normalize package name to handle renamed packages and static libs
        final PackageParser.Package pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return protectionLevel;
        }
        if (pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.O) {
            return protectionLevelMasked;
        }
        // Apps that target O see flags for all protection levels.
        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return protectionLevel;
        }
        if (ps.getAppId() != appId) {
            return protectionLevel;
        }
        return protectionLevel;
    }

    /**
     * We might auto-grant permissions if any permission of the group is already granted. Hence if
     * the group of a granted permission changes we need to revoke it to avoid having permissions of
     * the new group auto-granted.
     *
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     * @param allPackageNames All package names
     * @param permissionCallback Callback for permission changed
     */
    private void revokeRuntimePermissionsIfGroupChanged(
            @NonNull PackageParser.Package newPackage,
            @NonNull PackageParser.Package oldPackage,
            @NonNull ArrayList<String> allPackageNames,
            @NonNull PermissionCallback permissionCallback) {
        final int numOldPackagePermissions = oldPackage.permissions.size();
        final ArrayMap<String, String> oldPermissionNameToGroupName
                = new ArrayMap<>(numOldPackagePermissions);

        for (int i = 0; i < numOldPackagePermissions; i++) {
            final PackageParser.Permission permission = oldPackage.permissions.get(i);

            if (permission.group != null) {
                oldPermissionNameToGroupName.put(permission.info.name,
                        permission.group.info.name);
            }
        }

        final int numNewPackagePermissions = newPackage.permissions.size();
        for (int newPermissionNum = 0; newPermissionNum < numNewPackagePermissions;
                newPermissionNum++) {
            final PackageParser.Permission newPermission =
                    newPackage.permissions.get(newPermissionNum);
            final int newProtection = newPermission.info.getProtection();

            if ((newProtection & PermissionInfo.PROTECTION_DANGEROUS) != 0) {
                final String permissionName = newPermission.info.name;
                final String newPermissionGroupName =
                        newPermission.group == null ? null : newPermission.group.info.name;
                final String oldPermissionGroupName = oldPermissionNameToGroupName.get(
                        permissionName);

                if (newPermissionGroupName != null
                        && !newPermissionGroupName.equals(oldPermissionGroupName)) {
                    final int[] userIds = mUserManagerInt.getUserIds();
                    final int numUserIds = userIds.length;
                    for (int userIdNum = 0; userIdNum < numUserIds; userIdNum++) {
                        final int userId = userIds[userIdNum];

                        final int numPackages = allPackageNames.size();
                        for (int packageNum = 0; packageNum < numPackages; packageNum++) {
                            final String packageName = allPackageNames.get(packageNum);

                            if (checkPermission(permissionName, packageName, UserHandle.USER_SYSTEM,
                                    userId) == PackageManager.PERMISSION_GRANTED) {
                                EventLog.writeEvent(0x534e4554, "72710897",
                                        newPackage.applicationInfo.uid,
                                        "Revoking permission", permissionName, "from package",
                                        packageName, "as the group changed from",
                                        oldPermissionGroupName, "to", newPermissionGroupName);

                                try {
                                    revokeRuntimePermission(permissionName, packageName, false,
                                            Process.SYSTEM_UID, userId, permissionCallback);
                                } catch (IllegalArgumentException e) {
                                    Slog.e(TAG, "Could not revoke " + permissionName + " from "
                                            + packageName, e);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void addAllPermissions(PackageParser.Package pkg, boolean chatty) {
        final int N = pkg.permissions.size();
        for (int i=0; i<N; i++) {
            PackageParser.Permission p = pkg.permissions.get(i);

            // Assume by default that we did not install this permission into the system.
            p.info.flags &= ~PermissionInfo.FLAG_INSTALLED;

            synchronized (PermissionManagerService.this.mLock) {
                // Now that permission groups have a special meaning, we ignore permission
                // groups for legacy apps to prevent unexpected behavior. In particular,
                // permissions for one app being granted to someone just because they happen
                // to be in a group defined by another app (before this had no implications).
                if (pkg.applicationInfo.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
                    p.group = mSettings.mPermissionGroups.get(p.info.group);
                    // Warn for a permission in an unknown group.
                    if (DEBUG_PERMISSIONS
                            && p.info.group != null && p.group == null) {
                        Slog.i(TAG, "Permission " + p.info.name + " from package "
                                + p.info.packageName + " in an unknown group " + p.info.group);
                    }
                }

                if (p.tree) {
                    final BasePermission bp = BasePermission.createOrUpdate(
                            mSettings.getPermissionTreeLocked(p.info.name), p, pkg,
                            mSettings.getAllPermissionTreesLocked(), chatty);
                    mSettings.putPermissionTreeLocked(p.info.name, bp);
                } else {
                    final BasePermission bp = BasePermission.createOrUpdate(
                            mSettings.getPermissionLocked(p.info.name),
                            p, pkg, mSettings.getAllPermissionTreesLocked(), chatty);
                    mSettings.putPermissionLocked(p.info.name, bp);
                }
            }
        }
    }

    private void addAllPermissionGroups(PackageParser.Package pkg, boolean chatty) {
        final int N = pkg.permissionGroups.size();
        StringBuilder r = null;
        for (int i=0; i<N; i++) {
            final PackageParser.PermissionGroup pg = pkg.permissionGroups.get(i);
            final PackageParser.PermissionGroup cur = mSettings.mPermissionGroups.get(pg.info.name);
            final String curPackageName = (cur == null) ? null : cur.info.packageName;
            final boolean isPackageUpdate = pg.info.packageName.equals(curPackageName);
            if (cur == null || isPackageUpdate) {
                mSettings.mPermissionGroups.put(pg.info.name, pg);
                if (chatty && DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    if (isPackageUpdate) {
                        r.append("UPD:");
                    }
                    r.append(pg.info.name);
                }
            } else {
                Slog.w(TAG, "Permission group " + pg.info.name + " from package "
                        + pg.info.packageName + " ignored: original from "
                        + cur.info.packageName);
                if (chatty && DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append("DUP:");
                    r.append(pg.info.name);
                }
            }
        }
        if (r != null && DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "  Permission Groups: " + r);
        }

    }

    private void removeAllPermissions(PackageParser.Package pkg, boolean chatty) {
        synchronized (mLock) {
            int N = pkg.permissions.size();
            StringBuilder r = null;
            for (int i=0; i<N; i++) {
                PackageParser.Permission p = pkg.permissions.get(i);
                BasePermission bp = (BasePermission) mSettings.mPermissions.get(p.info.name);
                if (bp == null) {
                    bp = mSettings.mPermissionTrees.get(p.info.name);
                }
                if (bp != null && bp.isPermission(p)) {
                    bp.setPermission(null);
                    if (DEBUG_REMOVE && chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.info.name);
                    }
                }
                if (p.isAppOp()) {
                    ArraySet<String> appOpPkgs =
                            mSettings.mAppOpPermissionPackages.get(p.info.name);
                    if (appOpPkgs != null) {
                        appOpPkgs.remove(pkg.packageName);
                    }
                }
            }
            if (r != null) {
                if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
            }

            N = pkg.requestedPermissions.size();
            r = null;
            for (int i=0; i<N; i++) {
                String perm = pkg.requestedPermissions.get(i);
                if (mSettings.isPermissionAppOp(perm)) {
                    ArraySet<String> appOpPkgs = mSettings.mAppOpPermissionPackages.get(perm);
                    if (appOpPkgs != null) {
                        appOpPkgs.remove(pkg.packageName);
                        if (appOpPkgs.isEmpty()) {
                            mSettings.mAppOpPermissionPackages.remove(perm);
                        }
                    }
                }
            }
            if (r != null) {
                if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
            }
        }
    }

    private boolean addDynamicPermission(
            PermissionInfo info, int callingUid, PermissionCallback callback) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant apps can't add permissions");
        }
        if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
            throw new SecurityException("Label must be specified in permission");
        }
        final BasePermission tree = mSettings.enforcePermissionTree(info.name, callingUid);
        final boolean added;
        final boolean changed;
        synchronized (mLock) {
            BasePermission bp = mSettings.getPermissionLocked(info.name);
            added = bp == null;
            int fixedLevel = PermissionInfo.fixProtectionLevel(info.protectionLevel);
            if (added) {
                enforcePermissionCapLocked(info, tree);
                bp = new BasePermission(info.name, tree.getSourcePackageName(),
                        BasePermission.TYPE_DYNAMIC);
            } else if (bp.isDynamic()) {
                // TODO: switch this back to SecurityException
                Slog.wtf(TAG, "Not allowed to modify non-dynamic permission "
                        + info.name);
            }
            changed = bp.addToTree(fixedLevel, info, tree);
            if (added) {
                mSettings.putPermissionLocked(info.name, bp);
            }
        }
        if (changed && callback != null) {
            callback.onPermissionChanged();
        }
        return added;
    }

    private void removeDynamicPermission(
            String permName, int callingUid, PermissionCallback callback) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        final BasePermission tree = mSettings.enforcePermissionTree(permName, callingUid);
        synchronized (mLock) {
            final BasePermission bp = mSettings.getPermissionLocked(permName);
            if (bp == null) {
                return;
            }
            if (bp.isDynamic()) {
                // TODO: switch this back to SecurityException
                Slog.wtf(TAG, "Not allowed to modify non-dynamic permission "
                        + permName);
            }
            mSettings.removePermissionLocked(permName);
            if (callback != null) {
                callback.onPermissionRemoved();
            }
        }
    }

    private void grantPermissions(PackageParser.Package pkg, boolean replace,
            String packageOfInterest, PermissionCallback callback) {
        // IMPORTANT: There are two types of permissions: install and runtime.
        // Install time permissions are granted when the app is installed to
        // all device users and users added in the future. Runtime permissions
        // are granted at runtime explicitly to specific users. Normal and signature
        // protected permissions are install time permissions. Dangerous permissions
        // are install permissions if the app's target SDK is Lollipop MR1 or older,
        // otherwise they are runtime permissions. This function does not manage
        // runtime permissions except for the case an app targeting Lollipop MR1
        // being upgraded to target a newer SDK, in which case dangerous permissions
        // are transformed from install time to runtime ones.

        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }
        final boolean isLegacySystemApp = mPackageManagerInt.isLegacySystemApp(pkg);

        final PermissionsState permissionsState = ps.getPermissionsState();
        PermissionsState origPermissions = permissionsState;

        final int[] currentUserIds = UserManagerService.getInstance().getUserIds();

        boolean runtimePermissionsRevoked = false;
        int[] updatedUserIds = EMPTY_INT_ARRAY;

        boolean changedInstallPermission = false;

        if (replace) {
            ps.setInstallPermissionsFixed(false);
            if (!ps.isSharedUser()) {
                origPermissions = new PermissionsState(permissionsState);
                permissionsState.reset();
            } else {
                // We need to know only about runtime permission changes since the
                // calling code always writes the install permissions state but
                // the runtime ones are written only if changed. The only cases of
                // changed runtime permissions here are promotion of an install to
                // runtime and revocation of a runtime from a shared user.
                synchronized (mLock) {
                    updatedUserIds = revokeUnusedSharedUserPermissionsLocked(
                            ps.getSharedUser(), UserManagerService.getInstance().getUserIds());
                    if (!ArrayUtils.isEmpty(updatedUserIds)) {
                        runtimePermissionsRevoked = true;
                    }
                }
            }
        }

        permissionsState.setGlobalGids(mGlobalGids);

        synchronized (mLock) {
            final int N = pkg.requestedPermissions.size();
            for (int i = 0; i < N; i++) {
                final String permName = pkg.requestedPermissions.get(i);
                final BasePermission bp = mSettings.getPermissionLocked(permName);
                final boolean appSupportsRuntimePermissions =
                        pkg.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.M;

                if (DEBUG_INSTALL) {
                    Log.i(TAG, "Package " + pkg.packageName + " checking " + permName + ": " + bp);
                }

                if (bp == null || bp.getSourcePackageSetting() == null) {
                    if (packageOfInterest == null || packageOfInterest.equals(pkg.packageName)) {
                        if (DEBUG_PERMISSIONS) {
                            Slog.i(TAG, "Unknown permission " + permName
                                    + " in package " + pkg.packageName);
                        }
                    }
                    continue;
                }

                // Limit ephemeral apps to ephemeral allowed permissions.
                if (pkg.applicationInfo.isInstantApp() && !bp.isInstant()) {
                    if (DEBUG_PERMISSIONS) {
                        Log.i(TAG, "Denying non-ephemeral permission " + bp.getName()
                                + " for package " + pkg.packageName);
                    }
                    continue;
                }

                if (bp.isRuntimeOnly() && !appSupportsRuntimePermissions) {
                    if (DEBUG_PERMISSIONS) {
                        Log.i(TAG, "Denying runtime-only permission " + bp.getName()
                                + " for package " + pkg.packageName);
                    }
                    continue;
                }

                final String perm = bp.getName();
                boolean allowedSig = false;
                int grant = GRANT_DENIED;

                // Keep track of app op permissions.
                if (bp.isAppOp()) {
                    mSettings.addAppOpPackage(perm, pkg.packageName);
                }

                if (bp.isNormal()) {
                    // For all apps normal permissions are install time ones.
                    grant = GRANT_INSTALL;
                } else if (bp.isRuntime()) {
                    // If a permission review is required for legacy apps we represent
                    // their permissions as always granted runtime ones since we need
                    // to keep the review required permission flag per user while an
                    // install permission's state is shared across all users.
                    if (!appSupportsRuntimePermissions && !mSettings.mPermissionReviewRequired) {
                        // For legacy apps dangerous permissions are install time ones.
                        grant = GRANT_INSTALL;
                    } else if (origPermissions.hasInstallPermission(bp.getName())) {
                        // For legacy apps that became modern, install becomes runtime.
                        grant = GRANT_UPGRADE;
                    } else if (isLegacySystemApp) {
                        // For legacy system apps, install becomes runtime.
                        // We cannot check hasInstallPermission() for system apps since those
                        // permissions were granted implicitly and not persisted pre-M.
                        grant = GRANT_UPGRADE;
                    } else {
                        // For modern apps keep runtime permissions unchanged.
                        grant = GRANT_RUNTIME;
                    }
                } else if (bp.isSignature()) {
                    // For all apps signature permissions are install time ones.
                    allowedSig = grantSignaturePermission(perm, pkg, bp, origPermissions);
                    if (allowedSig) {
                        grant = GRANT_INSTALL;
                    }
                }

                if (DEBUG_PERMISSIONS) {
                    Slog.i(TAG, "Granting permission " + perm + " to package " + pkg.packageName);
                }

                if (grant != GRANT_DENIED) {
                    if (!ps.isSystem() && ps.areInstallPermissionsFixed()) {
                        // If this is an existing, non-system package, then
                        // we can't add any new permissions to it.
                        if (!allowedSig && !origPermissions.hasInstallPermission(perm)) {
                            // Except...  if this is a permission that was added
                            // to the platform (note: need to only do this when
                            // updating the platform).
                            if (!isNewPlatformPermissionForPackage(perm, pkg)) {
                                grant = GRANT_DENIED;
                            }
                        }
                    }

                    switch (grant) {
                        case GRANT_INSTALL: {
                            // Revoke this as runtime permission to handle the case of
                            // a runtime permission being downgraded to an install one.
                            // Also in permission review mode we keep dangerous permissions
                            // for legacy apps
                            for (int userId : UserManagerService.getInstance().getUserIds()) {
                                if (origPermissions.getRuntimePermissionState(
                                        perm, userId) != null) {
                                    // Revoke the runtime permission and clear the flags.
                                    origPermissions.revokeRuntimePermission(bp, userId);
                                    origPermissions.updatePermissionFlags(bp, userId,
                                          PackageManager.MASK_PERMISSION_FLAGS, 0);
                                    // If we revoked a permission permission, we have to write.
                                    updatedUserIds = ArrayUtils.appendInt(
                                            updatedUserIds, userId);
                                }
                            }
                            // Grant an install permission.
                            if (permissionsState.grantInstallPermission(bp) !=
                                    PermissionsState.PERMISSION_OPERATION_FAILURE) {
                                changedInstallPermission = true;
                            }
                        } break;

                        case GRANT_RUNTIME: {
                            // Grant previously granted runtime permissions.
                            for (int userId : UserManagerService.getInstance().getUserIds()) {
                                final PermissionState permissionState = origPermissions
                                        .getRuntimePermissionState(perm, userId);
                                int flags = permissionState != null
                                        ? permissionState.getFlags() : 0;
                                if (origPermissions.hasRuntimePermission(perm, userId)) {
                                    // Don't propagate the permission in a permission review
                                    // mode if the former was revoked, i.e. marked to not
                                    // propagate on upgrade. Note that in a permission review
                                    // mode install permissions are represented as constantly
                                    // granted runtime ones since we need to keep a per user
                                    // state associated with the permission. Also the revoke
                                    // on upgrade flag is no longer applicable and is reset.
                                    final boolean revokeOnUpgrade = (flags & PackageManager
                                            .FLAG_PERMISSION_REVOKE_ON_UPGRADE) != 0;
                                    if (revokeOnUpgrade) {
                                        flags &= ~PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
                                        // Since we changed the flags, we have to write.
                                        updatedUserIds = ArrayUtils.appendInt(
                                                updatedUserIds, userId);
                                    }
                                    if (!mSettings.mPermissionReviewRequired || !revokeOnUpgrade) {
                                        if (permissionsState.grantRuntimePermission(bp, userId) ==
                                                PermissionsState.PERMISSION_OPERATION_FAILURE) {
                                            // If we cannot put the permission as it was,
                                            // we have to write.
                                            updatedUserIds = ArrayUtils.appendInt(
                                                    updatedUserIds, userId);
                                        }
                                    }

                                    // If the app supports runtime permissions no need for a review.
                                    if (mSettings.mPermissionReviewRequired
                                            && appSupportsRuntimePermissions
                                            && (flags & PackageManager
                                                    .FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                                        flags &= ~PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
                                        // Since we changed the flags, we have to write.
                                        updatedUserIds = ArrayUtils.appendInt(
                                                updatedUserIds, userId);
                                    }
                                } else if (mSettings.mPermissionReviewRequired
                                        && !appSupportsRuntimePermissions) {
                                    // For legacy apps that need a permission review, every new
                                    // runtime permission is granted but it is pending a review.
                                    // We also need to review only platform defined runtime
                                    // permissions as these are the only ones the platform knows
                                    // how to disable the API to simulate revocation as legacy
                                    // apps don't expect to run with revoked permissions.
                                    if (PLATFORM_PACKAGE_NAME.equals(bp.getSourcePackageName())) {
                                        if ((flags & FLAG_PERMISSION_REVIEW_REQUIRED) == 0) {
                                            flags |= FLAG_PERMISSION_REVIEW_REQUIRED;
                                            // We changed the flags, hence have to write.
                                            updatedUserIds = ArrayUtils.appendInt(
                                                    updatedUserIds, userId);
                                        }
                                    }
                                    if (permissionsState.grantRuntimePermission(bp, userId)
                                            != PermissionsState.PERMISSION_OPERATION_FAILURE) {
                                        // We changed the permission, hence have to write.
                                        updatedUserIds = ArrayUtils.appendInt(
                                                updatedUserIds, userId);
                                    }
                                }
                                // Propagate the permission flags.
                                permissionsState.updatePermissionFlags(bp, userId, flags, flags);
                            }
                        } break;

                        case GRANT_UPGRADE: {
                            // Grant runtime permissions for a previously held install permission.
                            final PermissionState permissionState = origPermissions
                                    .getInstallPermissionState(perm);
                            final int flags =
                                    (permissionState != null) ? permissionState.getFlags() : 0;

                            if (origPermissions.revokeInstallPermission(bp)
                                    != PermissionsState.PERMISSION_OPERATION_FAILURE) {
                                // We will be transferring the permission flags, so clear them.
                                origPermissions.updatePermissionFlags(bp, UserHandle.USER_ALL,
                                        PackageManager.MASK_PERMISSION_FLAGS, 0);
                                changedInstallPermission = true;
                            }

                            // If the permission is not to be promoted to runtime we ignore it and
                            // also its other flags as they are not applicable to install permissions.
                            if ((flags & PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE) == 0) {
                                for (int userId : currentUserIds) {
                                    if (permissionsState.grantRuntimePermission(bp, userId) !=
                                            PermissionsState.PERMISSION_OPERATION_FAILURE) {
                                        // Transfer the permission flags.
                                        permissionsState.updatePermissionFlags(bp, userId,
                                                flags, flags);
                                        // If we granted the permission, we have to write.
                                        updatedUserIds = ArrayUtils.appendInt(
                                                updatedUserIds, userId);
                                    }
                                }
                            }
                        } break;

                        default: {
                            if (packageOfInterest == null
                                    || packageOfInterest.equals(pkg.packageName)) {
                                if (DEBUG_PERMISSIONS) {
                                    Slog.i(TAG, "Not granting permission " + perm
                                            + " to package " + pkg.packageName
                                            + " because it was previously installed without");
                                }
                            }
                        } break;
                    }
                } else {
                    if (permissionsState.revokeInstallPermission(bp) !=
                            PermissionsState.PERMISSION_OPERATION_FAILURE) {
                        // Also drop the permission flags.
                        permissionsState.updatePermissionFlags(bp, UserHandle.USER_ALL,
                                PackageManager.MASK_PERMISSION_FLAGS, 0);
                        changedInstallPermission = true;
                        Slog.i(TAG, "Un-granting permission " + perm
                                + " from package " + pkg.packageName
                                + " (protectionLevel=" + bp.getProtectionLevel()
                                + " flags=0x" + Integer.toHexString(pkg.applicationInfo.flags)
                                + ")");
                    } else if (bp.isAppOp()) {
                        // Don't print warning for app op permissions, since it is fine for them
                        // not to be granted, there is a UI for the user to decide.
                        if (DEBUG_PERMISSIONS
                                && (packageOfInterest == null
                                        || packageOfInterest.equals(pkg.packageName))) {
                            Slog.i(TAG, "Not granting permission " + perm
                                    + " to package " + pkg.packageName
                                    + " (protectionLevel=" + bp.getProtectionLevel()
                                    + " flags=0x" + Integer.toHexString(pkg.applicationInfo.flags)
                                    + ")");
                        }
                    }
                }
            }

            if ((changedInstallPermission || replace) && !ps.areInstallPermissionsFixed() &&
                    !ps.isSystem() || ps.isUpdatedSystem()) {
                // This is the first that we have heard about this package, so the
                // permissions we have now selected are fixed until explicitly
                // changed.
                ps.setInstallPermissionsFixed(true);
            }
        }

        // Persist the runtime permissions state for users with changes. If permissions
        // were revoked because no app in the shared user declares them we have to
        // write synchronously to avoid losing runtime permissions state.
        if (callback != null) {
            callback.onPermissionUpdated(updatedUserIds, runtimePermissionsRevoked);
        }
    }

    private boolean isNewPlatformPermissionForPackage(String perm, PackageParser.Package pkg) {
        boolean allowed = false;
        final int NP = PackageParser.NEW_PERMISSIONS.length;
        for (int ip=0; ip<NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (npi.name.equals(perm)
                    && pkg.applicationInfo.targetSdkVersion < npi.sdkVersion) {
                allowed = true;
                Log.i(TAG, "Auto-granting " + perm + " to old pkg "
                        + pkg.packageName);
                break;
            }
        }
        return allowed;
    }

    /**
     * Determines whether a package is whitelisted for a particular privapp permission.
     *
     * <p>Does NOT check whether the package is a privapp, just whether it's whitelisted.
     *
     * <p>This handles parent/child apps.
     */
    private boolean hasPrivappWhitelistEntry(String perm, PackageParser.Package pkg) {
        ArraySet<String> wlPermissions = null;
        if (pkg.isVendor()) {
            wlPermissions =
                    SystemConfig.getInstance().getVendorPrivAppPermissions(pkg.packageName);
        } else if (pkg.isProduct()) {
            wlPermissions =
                    SystemConfig.getInstance().getProductPrivAppPermissions(pkg.packageName);
        } else {
            wlPermissions = SystemConfig.getInstance().getPrivAppPermissions(pkg.packageName);
        }
        // Let's check if this package is whitelisted...
        boolean whitelisted = wlPermissions != null && wlPermissions.contains(perm);
        // If it's not, we'll also tail-recurse to the parent.
        return whitelisted ||
                pkg.parentPackage != null && hasPrivappWhitelistEntry(perm, pkg.parentPackage);
    }

    private boolean grantSignaturePermission(String perm, PackageParser.Package pkg,
            BasePermission bp, PermissionsState origPermissions) {
        boolean oemPermission = bp.isOEM();
        boolean vendorPrivilegedPermission = bp.isVendorPrivileged();
        boolean privilegedPermission = bp.isPrivileged() || bp.isVendorPrivileged();
        boolean privappPermissionsDisable =
                RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_DISABLE;
        boolean platformPermission = PLATFORM_PACKAGE_NAME.equals(bp.getSourcePackageName());
        boolean platformPackage = PLATFORM_PACKAGE_NAME.equals(pkg.packageName);
        if (!privappPermissionsDisable && privilegedPermission && pkg.isPrivileged()
                && !platformPackage && platformPermission) {
            if (!hasPrivappWhitelistEntry(perm, pkg)) {
                // Only report violations for apps on system image
                if (!mSystemReady && !pkg.isUpdatedSystemApp()) {
                    // it's only a reportable violation if the permission isn't explicitly denied
                    ArraySet<String> deniedPermissions = null;
                    if (pkg.isVendor()) {
                        deniedPermissions = SystemConfig.getInstance()
                                .getVendorPrivAppDenyPermissions(pkg.packageName);
                    } else if (pkg.isProduct()) {
                        deniedPermissions = SystemConfig.getInstance()
                                .getProductPrivAppDenyPermissions(pkg.packageName);
                    } else {
                        deniedPermissions = SystemConfig.getInstance()
                                .getPrivAppDenyPermissions(pkg.packageName);
                    }
                    final boolean permissionViolation =
                            deniedPermissions == null || !deniedPermissions.contains(perm);
                    if (permissionViolation) {
                        Slog.w(TAG, "Privileged permission " + perm + " for package "
                                + pkg.packageName + " - not in privapp-permissions whitelist");

                        if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                            if (mPrivappPermissionsViolations == null) {
                                mPrivappPermissionsViolations = new ArraySet<>();
                            }
                            mPrivappPermissionsViolations.add(pkg.packageName + ": " + perm);
                        }
                    } else {
                        return false;
                    }
                }
                if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                    return false;
                }
            }
        }
        final String systemPackageName = mPackageManagerInt.getKnownPackageName(
                PackageManagerInternal.PACKAGE_SYSTEM, UserHandle.USER_SYSTEM);
        final PackageParser.Package systemPackage =
                mPackageManagerInt.getPackage(systemPackageName);

        // check if the package is allow to use this signature permission.  A package is allowed to
        // use a signature permission if:
        //     - it has the same set of signing certificates as the source package
        //     - or its signing certificate was rotated from the source package's certificate
        //     - or its signing certificate is a previous signing certificate of the defining
        //       package, and the defining package still trusts the old certificate for permissions
        //     - or it shares the above relationships with the system package
        boolean allowed =
                pkg.mSigningDetails.hasAncestorOrSelf(
                        bp.getSourcePackageSetting().getSigningDetails())
                || bp.getSourcePackageSetting().getSigningDetails().checkCapability(
                        pkg.mSigningDetails,
                        PackageParser.SigningDetails.CertCapabilities.PERMISSION)
                || pkg.mSigningDetails.hasAncestorOrSelf(systemPackage.mSigningDetails)
                || systemPackage.mSigningDetails.checkCapability(
                        pkg.mSigningDetails,
                        PackageParser.SigningDetails.CertCapabilities.PERMISSION);
        if (!allowed && (privilegedPermission || oemPermission)) {
            if (pkg.isSystem()) {
                // For updated system applications, a privileged/oem permission
                // is granted only if it had been defined by the original application.
                if (pkg.isUpdatedSystemApp()) {
                    final PackageParser.Package disabledPkg =
                            mPackageManagerInt.getDisabledPackage(pkg.packageName);
                    final PackageSetting disabledPs =
                            (disabledPkg != null) ? (PackageSetting) disabledPkg.mExtras : null;
                    if (disabledPs != null
                            && disabledPs.getPermissionsState().hasInstallPermission(perm)) {
                        // If the original was granted this permission, we take
                        // that grant decision as read and propagate it to the
                        // update.
                        if ((privilegedPermission && disabledPs.isPrivileged())
                                || (oemPermission && disabledPs.isOem()
                                        && canGrantOemPermission(disabledPs, perm))) {
                            allowed = true;
                        }
                    } else {
                        // The system apk may have been updated with an older
                        // version of the one on the data partition, but which
                        // granted a new system permission that it didn't have
                        // before.  In this case we do want to allow the app to
                        // now get the new permission if the ancestral apk is
                        // privileged to get it.
                        if (disabledPs != null && disabledPkg != null
                                && isPackageRequestingPermission(disabledPkg, perm)
                                && ((privilegedPermission && disabledPs.isPrivileged())
                                        || (oemPermission && disabledPs.isOem()
                                                && canGrantOemPermission(disabledPs, perm)))) {
                            allowed = true;
                        }
                        // Also if a privileged parent package on the system image or any of
                        // its children requested a privileged/oem permission, the updated child
                        // packages can also get the permission.
                        if (pkg.parentPackage != null) {
                            final PackageParser.Package disabledParentPkg = mPackageManagerInt
                                    .getDisabledPackage(pkg.parentPackage.packageName);
                            final PackageSetting disabledParentPs = (disabledParentPkg != null)
                                    ? (PackageSetting) disabledParentPkg.mExtras : null;
                            if (disabledParentPkg != null
                                    && ((privilegedPermission && disabledParentPs.isPrivileged())
                                            || (oemPermission && disabledParentPs.isOem()))) {
                                if (isPackageRequestingPermission(disabledParentPkg, perm)
                                        && canGrantOemPermission(disabledParentPs, perm)) {
                                    allowed = true;
                                } else if (disabledParentPkg.childPackages != null) {
                                    for (PackageParser.Package disabledChildPkg
                                            : disabledParentPkg.childPackages) {
                                        final PackageSetting disabledChildPs =
                                                (disabledChildPkg != null)
                                                        ? (PackageSetting) disabledChildPkg.mExtras
                                                        : null;
                                        if (isPackageRequestingPermission(disabledChildPkg, perm)
                                                && canGrantOemPermission(
                                                        disabledChildPs, perm)) {
                                            allowed = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    final PackageSetting ps = (PackageSetting) pkg.mExtras;
                    allowed = (privilegedPermission && pkg.isPrivileged())
                            || (oemPermission && pkg.isOem()
                                    && canGrantOemPermission(ps, perm));
                }
                // In any case, don't grant a privileged permission to privileged vendor apps, if
                // the permission's protectionLevel does not have the extra 'vendorPrivileged'
                // flag.
                if (allowed && privilegedPermission &&
                        !vendorPrivilegedPermission && pkg.isVendor()) {
                   Slog.w(TAG, "Permission " + perm + " cannot be granted to privileged vendor apk "
                           + pkg.packageName + " because it isn't a 'vendorPrivileged' permission.");
                   allowed = false;
                }
            }
        }
        if (!allowed) {
            if (!allowed
                    && bp.isPre23()
                    && pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
                // If this was a previously normal/dangerous permission that got moved
                // to a system permission as part of the runtime permission redesign, then
                // we still want to blindly grant it to old apps.
                allowed = true;
            }
            if (!allowed && bp.isInstaller()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM))) {
                // If this permission is to be granted to the system installer and
                // this app is an installer, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isVerifier()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_VERIFIER, UserHandle.USER_SYSTEM))) {
                // If this permission is to be granted to the system verifier and
                // this app is a verifier, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isPreInstalled()
                    && pkg.isSystem()) {
                // Any pre-installed system app is allowed to get this permission.
                allowed = true;
            }
            if (!allowed && bp.isDevelopment()) {
                // For development permissions, a development permission
                // is granted only if it was already granted.
                allowed = origPermissions.hasInstallPermission(perm);
            }
            if (!allowed && bp.isSetup()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM))) {
                // If this permission is to be granted to the system setup wizard and
                // this app is a setup wizard, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isSystemTextClassifier()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_SYSTEM_TEXT_CLASSIFIER,
                            UserHandle.USER_SYSTEM))) {
                // Special permissions for the system default text classifier.
                allowed = true;
            }
        }
        return allowed;
    }

    private static boolean canGrantOemPermission(PackageSetting ps, String permission) {
        if (!ps.isOem()) {
            return false;
        }
        // all oem permissions must explicitly be granted or denied
        final Boolean granted =
                SystemConfig.getInstance().getOemPermissions(ps.name).get(permission);
        if (granted == null) {
            throw new IllegalStateException("OEM permission" + permission + " requested by package "
                    + ps.name + " must be explicitly declared granted or not");
        }
        return Boolean.TRUE == granted;
    }

    private boolean isPermissionsReviewRequired(PackageParser.Package pkg, int userId) {
        if (!mSettings.mPermissionReviewRequired) {
            return false;
        }

        // Permission review applies only to apps not supporting the new permission model.
        if (pkg.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.M) {
            return false;
        }

        // Legacy apps have the permission and get user consent on launch.
        if (pkg == null || pkg.mExtras == null) {
            return false;
        }
        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        final PermissionsState permissionsState = ps.getPermissionsState();
        return permissionsState.isPermissionReviewRequired(userId);
    }

    private boolean isPackageRequestingPermission(PackageParser.Package pkg, String permission) {
        final int permCount = pkg.requestedPermissions.size();
        for (int j = 0; j < permCount; j++) {
            String requestedPermission = pkg.requestedPermissions.get(j);
            if (permission.equals(requestedPermission)) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private void grantRuntimePermissionsGrantedToDisabledPackageLocked(
            PackageParser.Package pkg, int callingUid, PermissionCallback callback) {
        if (pkg.parentPackage == null) {
            return;
        }
        if (pkg.requestedPermissions == null) {
            return;
        }
        final PackageParser.Package disabledPkg =
                mPackageManagerInt.getDisabledPackage(pkg.parentPackage.packageName);
        if (disabledPkg == null || disabledPkg.mExtras == null) {
            return;
        }
        final PackageSetting disabledPs = (PackageSetting) disabledPkg.mExtras;
        if (!disabledPs.isPrivileged() || disabledPs.hasChildPackages()) {
            return;
        }
        final int permCount = pkg.requestedPermissions.size();
        for (int i = 0; i < permCount; i++) {
            String permission = pkg.requestedPermissions.get(i);
            BasePermission bp = mSettings.getPermissionLocked(permission);
            if (bp == null || !(bp.isRuntime() || bp.isDevelopment())) {
                continue;
            }
            for (int userId : mUserManagerInt.getUserIds()) {
                if (disabledPs.getPermissionsState().hasRuntimePermission(permission, userId)) {
                    grantRuntimePermission(
                            permission, pkg.packageName, false, callingUid, userId, callback);
                }
            }
        }
    }

    private void grantRequestedRuntimePermissions(PackageParser.Package pkg, int[] userIds,
            String[] grantedPermissions, int callingUid, PermissionCallback callback) {
        for (int userId : userIds) {
            grantRequestedRuntimePermissionsForUser(pkg, userId, grantedPermissions, callingUid,
                    callback);
        }
    }

    private void grantRequestedRuntimePermissionsForUser(PackageParser.Package pkg, int userId,
            String[] grantedPermissions, int callingUid, PermissionCallback callback) {
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }

        PermissionsState permissionsState = ps.getPermissionsState();

        final int immutableFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED;

        final boolean supportsRuntimePermissions = pkg.applicationInfo.targetSdkVersion
                >= Build.VERSION_CODES.M;

        final boolean instantApp = mPackageManagerInt.isInstantApp(pkg.packageName, userId);

        for (String permission : pkg.requestedPermissions) {
            final BasePermission bp;
            synchronized (mLock) {
                bp = mSettings.getPermissionLocked(permission);
            }
            if (bp != null && (bp.isRuntime() || bp.isDevelopment())
                    && (!instantApp || bp.isInstant())
                    && (supportsRuntimePermissions || !bp.isRuntimeOnly())
                    && (grantedPermissions == null
                           || ArrayUtils.contains(grantedPermissions, permission))) {
                final int flags = permissionsState.getPermissionFlags(permission, userId);
                if (supportsRuntimePermissions) {
                    // Installer cannot change immutable permissions.
                    if ((flags & immutableFlags) == 0) {
                        grantRuntimePermission(permission, pkg.packageName, false, callingUid,
                                userId, callback);
                    }
                } else if (mSettings.mPermissionReviewRequired) {
                    // In permission review mode we clear the review flag when we
                    // are asked to install the app with all permissions granted.
                    if ((flags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                        updatePermissionFlags(permission, pkg.packageName,
                                PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED, 0, callingUid,
                                userId, callback);
                    }
                }
            }
        }
    }

    private void grantRuntimePermission(String permName, String packageName, boolean overridePolicy,
            int callingUid, final int userId, PermissionCallback callback) {
        if (!mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                "grantRuntimePermission");

        enforceCrossUserPermission(callingUid, userId,
                true /* requireFullPermission */, true /* checkShell */,
                "grantRuntimePermission");

        final PackageParser.Package pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        final BasePermission bp;
        synchronized(mLock) {
            bp = mSettings.getPermissionLocked(permName);
        }
        if (bp == null) {
            throw new IllegalArgumentException("Unknown permission: " + permName);
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        bp.enforceDeclaredUsedAndRuntimeOrDevelopment(pkg);

        // If a permission review is required for legacy apps we represent
        // their permissions as always granted runtime ones since we need
        // to keep the review required permission flag per user while an
        // install permission's state is shared across all users.
        if (mSettings.mPermissionReviewRequired
                && pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M
                && bp.isRuntime()) {
            return;
        }

        final int uid = UserHandle.getUid(userId, pkg.applicationInfo.uid);

        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        final PermissionsState permissionsState = ps.getPermissionsState();

        final int flags = permissionsState.getPermissionFlags(permName, userId);
        if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0) {
            throw new SecurityException("Cannot grant system fixed permission "
                    + permName + " for package " + packageName);
        }
        if (!overridePolicy && (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
            throw new SecurityException("Cannot grant policy fixed permission "
                    + permName + " for package " + packageName);
        }

        if (bp.isDevelopment()) {
            // Development permissions must be handled specially, since they are not
            // normal runtime permissions.  For now they apply to all users.
            if (permissionsState.grantInstallPermission(bp) !=
                    PermissionsState.PERMISSION_OPERATION_FAILURE) {
                if (callback != null) {
                    callback.onInstallPermissionGranted();
                }
            }
            return;
        }

        if (ps.getInstantApp(userId) && !bp.isInstant()) {
            throw new SecurityException("Cannot grant non-ephemeral permission"
                    + permName + " for package " + packageName);
        }

        if (pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
            Slog.w(TAG, "Cannot grant runtime permission to a legacy app");
            return;
        }

        final int result = permissionsState.grantRuntimePermission(bp, userId);
        switch (result) {
            case PermissionsState.PERMISSION_OPERATION_FAILURE: {
                return;
            }

            case PermissionsState.PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED: {
                if (callback != null) {
                    callback.onGidsChanged(UserHandle.getAppId(pkg.applicationInfo.uid), userId);
                }
            }
            break;
        }

        if (bp.isRuntime()) {
            logPermission(MetricsEvent.ACTION_PERMISSION_GRANTED, permName, packageName);
        }

        if (callback != null) {
            callback.onPermissionGranted(uid, userId);
        }

        // Only need to do this if user is initialized. Otherwise it's a new user
        // and there are no processes running as the user yet and there's no need
        // to make an expensive call to remount processes for the changed permissions.
        if (READ_EXTERNAL_STORAGE.equals(permName)
                || WRITE_EXTERNAL_STORAGE.equals(permName)) {
            final long token = Binder.clearCallingIdentity();
            try {
                if (mUserManagerInt.isUserInitialized(userId)) {
                    StorageManagerInternal storageManagerInternal = LocalServices.getService(
                            StorageManagerInternal.class);
                    storageManagerInternal.onExternalStoragePolicyChanged(uid, packageName);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

    }

    private void revokeRuntimePermission(String permName, String packageName,
            boolean overridePolicy, int callingUid, int userId, PermissionCallback callback) {
        if (!mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                "revokeRuntimePermission");

        enforceCrossUserPermission(Binder.getCallingUid(), userId,
                true /* requireFullPermission */, true /* checkShell */,
                "revokeRuntimePermission");

        final int appId;

        final PackageParser.Package pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        if (mPackageManagerInt.filterAppAccess(pkg, Binder.getCallingUid(), userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        final BasePermission bp = mSettings.getPermissionLocked(permName);
        if (bp == null) {
            throw new IllegalArgumentException("Unknown permission: " + permName);
        }

        bp.enforceDeclaredUsedAndRuntimeOrDevelopment(pkg);

        // If a permission review is required for legacy apps we represent
        // their permissions as always granted runtime ones since we need
        // to keep the review required permission flag per user while an
        // install permission's state is shared across all users.
        if (mSettings.mPermissionReviewRequired
                && pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M
                && bp.isRuntime()) {
            return;
        }

        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        final PermissionsState permissionsState = ps.getPermissionsState();

        final int flags = permissionsState.getPermissionFlags(permName, userId);
        // Only the system may revoke SYSTEM_FIXED permissions.
        if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0
                && UserHandle.getCallingAppId() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-System UID cannot revoke system fixed permission "
                    + permName + " for package " + packageName);
        }
        if (!overridePolicy && (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
            throw new SecurityException("Cannot revoke policy fixed permission "
                    + permName + " for package " + packageName);
        }

        if (bp.isDevelopment()) {
            // Development permissions must be handled specially, since they are not
            // normal runtime permissions.  For now they apply to all users.
            if (permissionsState.revokeInstallPermission(bp) !=
                    PermissionsState.PERMISSION_OPERATION_FAILURE) {
                if (callback != null) {
                    callback.onInstallPermissionRevoked();
                }
            }
            return;
        }

        if (permissionsState.revokeRuntimePermission(bp, userId) ==
                PermissionsState.PERMISSION_OPERATION_FAILURE) {
            return;
        }

        if (bp.isRuntime()) {
            logPermission(MetricsEvent.ACTION_PERMISSION_REVOKED, permName, packageName);
        }

        if (callback != null) {
            final int uid = UserHandle.getUid(userId, pkg.applicationInfo.uid);
            callback.onPermissionRevoked(pkg.applicationInfo.uid, userId);
        }
    }

    @GuardedBy("mLock")
    private int[] revokeUnusedSharedUserPermissionsLocked(
            SharedUserSetting suSetting, int[] allUserIds) {
        // Collect all used permissions in the UID
        final ArraySet<String> usedPermissions = new ArraySet<>();
        final List<PackageParser.Package> pkgList = suSetting.getPackages();
        if (pkgList == null || pkgList.size() == 0) {
            return EmptyArray.INT;
        }
        for (PackageParser.Package pkg : pkgList) {
            if (pkg.requestedPermissions == null) {
                continue;
            }
            final int requestedPermCount = pkg.requestedPermissions.size();
            for (int j = 0; j < requestedPermCount; j++) {
                String permission = pkg.requestedPermissions.get(j);
                BasePermission bp = mSettings.getPermissionLocked(permission);
                if (bp != null) {
                    usedPermissions.add(permission);
                }
            }
        }

        PermissionsState permissionsState = suSetting.getPermissionsState();
        // Prune install permissions
        List<PermissionState> installPermStates = permissionsState.getInstallPermissionStates();
        final int installPermCount = installPermStates.size();
        for (int i = installPermCount - 1; i >= 0;  i--) {
            PermissionState permissionState = installPermStates.get(i);
            if (!usedPermissions.contains(permissionState.getName())) {
                BasePermission bp = mSettings.getPermissionLocked(permissionState.getName());
                if (bp != null) {
                    permissionsState.revokeInstallPermission(bp);
                    permissionsState.updatePermissionFlags(bp, UserHandle.USER_ALL,
                            PackageManager.MASK_PERMISSION_FLAGS, 0);
                }
            }
        }

        int[] runtimePermissionChangedUserIds = EmptyArray.INT;

        // Prune runtime permissions
        for (int userId : allUserIds) {
            List<PermissionState> runtimePermStates = permissionsState
                    .getRuntimePermissionStates(userId);
            final int runtimePermCount = runtimePermStates.size();
            for (int i = runtimePermCount - 1; i >= 0; i--) {
                PermissionState permissionState = runtimePermStates.get(i);
                if (!usedPermissions.contains(permissionState.getName())) {
                    BasePermission bp = mSettings.getPermissionLocked(permissionState.getName());
                    if (bp != null) {
                        permissionsState.revokeRuntimePermission(bp, userId);
                        permissionsState.updatePermissionFlags(bp, userId,
                                PackageManager.MASK_PERMISSION_FLAGS, 0);
                        runtimePermissionChangedUserIds = ArrayUtils.appendInt(
                                runtimePermissionChangedUserIds, userId);
                    }
                }
            }
        }

        return runtimePermissionChangedUserIds;
    }

    private String[] getAppOpPermissionPackages(String permName) {
        if (mPackageManagerInt.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (mLock) {
            final ArraySet<String> pkgs = mSettings.mAppOpPermissionPackages.get(permName);
            if (pkgs == null) {
                return null;
            }
            return pkgs.toArray(new String[pkgs.size()]);
        }
    }

    private int getPermissionFlags(
            String permName, String packageName, int callingUid, int userId) {
        if (!mUserManagerInt.exists(userId)) {
            return 0;
        }

        enforceGrantRevokeRuntimePermissionPermissions("getPermissionFlags");

        enforceCrossUserPermission(callingUid, userId,
                true /* requireFullPermission */, false /* checkShell */,
                "getPermissionFlags");

        final PackageParser.Package pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            return 0;
        }
        synchronized (mLock) {
            if (mSettings.getPermissionLocked(permName) == null) {
                return 0;
            }
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            return 0;
        }
        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        PermissionsState permissionsState = ps.getPermissionsState();
        return permissionsState.getPermissionFlags(permName, userId);
    }

    private static final int UPDATE_PERMISSIONS_ALL = 1<<0;
    private static final int UPDATE_PERMISSIONS_REPLACE_PKG = 1<<1;
    private static final int UPDATE_PERMISSIONS_REPLACE_ALL = 1<<2;

    private void updatePermissions(String packageName, PackageParser.Package pkg,
            boolean replaceGrant, Collection<PackageParser.Package> allPackages,
            PermissionCallback callback) {
        final int flags = (pkg != null ? UPDATE_PERMISSIONS_ALL : 0) |
                (replaceGrant ? UPDATE_PERMISSIONS_REPLACE_PKG : 0);
        updatePermissions(
                packageName, pkg, getVolumeUuidForPackage(pkg), flags, allPackages, callback);
        if (pkg != null && pkg.childPackages != null) {
            for (PackageParser.Package childPkg : pkg.childPackages) {
                updatePermissions(childPkg.packageName, childPkg,
                        getVolumeUuidForPackage(childPkg), flags, allPackages, callback);
            }
        }
    }

    private void updateAllPermissions(String volumeUuid, boolean sdkUpdated,
            Collection<PackageParser.Package> allPackages, PermissionCallback callback) {
        final int flags = UPDATE_PERMISSIONS_ALL |
                (sdkUpdated
                        ? UPDATE_PERMISSIONS_REPLACE_PKG | UPDATE_PERMISSIONS_REPLACE_ALL
                        : 0);
        updatePermissions(null, null, volumeUuid, flags, allPackages, callback);
    }

    private void updatePermissions(String changingPkgName, PackageParser.Package changingPkg,
            String replaceVolumeUuid, int flags, Collection<PackageParser.Package> allPackages,
            PermissionCallback callback) {
        // TODO: Most of the methods exposing BasePermission internals [source package name,
        // etc..] shouldn't be needed. Instead, when we've parsed a permission that doesn't
        // have package settings, we should make note of it elsewhere [map between
        // source package name and BasePermission] and cycle through that here. Then we
        // define a single method on BasePermission that takes a PackageSetting, changing
        // package name and a package.
        // NOTE: With this approach, we also don't need to tree trees differently than
        // normal permissions. Today, we need two separate loops because these BasePermission
        // objects are stored separately.
        // Make sure there are no dangling permission trees.
        flags = updatePermissionTrees(changingPkgName, changingPkg, flags);

        // Make sure all dynamic permissions have been assigned to a package,
        // and make sure there are no dangling permissions.
        flags = updatePermissions(changingPkgName, changingPkg, flags);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "grantPermissions");
        // Now update the permissions for all packages, in particular
        // replace the granted permissions of the system packages.
        if ((flags & UPDATE_PERMISSIONS_ALL) != 0) {
            for (PackageParser.Package pkg : allPackages) {
                if (pkg != changingPkg) {
                    // Only replace for packages on requested volume
                    final String volumeUuid = getVolumeUuidForPackage(pkg);
                    final boolean replace = ((flags & UPDATE_PERMISSIONS_REPLACE_ALL) != 0)
                            && Objects.equals(replaceVolumeUuid, volumeUuid);
                    grantPermissions(pkg, replace, changingPkgName, callback);
                }
            }
        }

        if (changingPkg != null) {
            // Only replace for packages on requested volume
            final String volumeUuid = getVolumeUuidForPackage(changingPkg);
            final boolean replace = ((flags & UPDATE_PERMISSIONS_REPLACE_PKG) != 0)
                    && Objects.equals(replaceVolumeUuid, volumeUuid);
            grantPermissions(changingPkg, replace, changingPkgName, callback);
        }
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    private int updatePermissions(String packageName, PackageParser.Package pkg, int flags) {
        Set<BasePermission> needsUpdate = null;
        synchronized (mLock) {
            final Iterator<BasePermission> it = mSettings.mPermissions.values().iterator();
            while (it.hasNext()) {
                final BasePermission bp = it.next();
                if (bp.isDynamic()) {
                    bp.updateDynamicPermission(mSettings.mPermissionTrees.values());
                }
                if (bp.getSourcePackageSetting() != null) {
                    if (packageName != null && packageName.equals(bp.getSourcePackageName())
                        && (pkg == null || !hasPermission(pkg, bp.getName()))) {
                        Slog.i(TAG, "Removing old permission tree: " + bp.getName()
                                + " from package " + bp.getSourcePackageName());
                        flags |= UPDATE_PERMISSIONS_ALL;
                        it.remove();
                    }
                    continue;
                }
                if (needsUpdate == null) {
                    needsUpdate = new ArraySet<>(mSettings.mPermissions.size());
                }
                needsUpdate.add(bp);
            }
        }
        if (needsUpdate != null) {
            for (final BasePermission bp : needsUpdate) {
                final PackageParser.Package sourcePkg =
                        mPackageManagerInt.getPackage(bp.getSourcePackageName());
                synchronized (mLock) {
                    if (sourcePkg != null && sourcePkg.mExtras != null) {
                        final PackageSetting sourcePs = (PackageSetting) sourcePkg.mExtras;
                        if (bp.getSourcePackageSetting() == null) {
                            bp.setSourcePackageSetting(sourcePs);
                        }
                        continue;
                    }
                    Slog.w(TAG, "Removing dangling permission: " + bp.getName()
                            + " from package " + bp.getSourcePackageName());
                    mSettings.removePermissionLocked(bp.getName());
                }
            }
        }
        return flags;
    }

    private int updatePermissionTrees(String packageName, PackageParser.Package pkg,
            int flags) {
        Set<BasePermission> needsUpdate = null;
        synchronized (mLock) {
            final Iterator<BasePermission> it = mSettings.mPermissionTrees.values().iterator();
            while (it.hasNext()) {
                final BasePermission bp = it.next();
                if (bp.getSourcePackageSetting() != null) {
                    if (packageName != null && packageName.equals(bp.getSourcePackageName())
                        && (pkg == null || !hasPermission(pkg, bp.getName()))) {
                        Slog.i(TAG, "Removing old permission tree: " + bp.getName()
                                + " from package " + bp.getSourcePackageName());
                        flags |= UPDATE_PERMISSIONS_ALL;
                        it.remove();
                    }
                    continue;
                }
                if (needsUpdate == null) {
                    needsUpdate = new ArraySet<>(mSettings.mPermissionTrees.size());
                }
                needsUpdate.add(bp);
            }
        }
        if (needsUpdate != null) {
            for (final BasePermission bp : needsUpdate) {
                final PackageParser.Package sourcePkg =
                        mPackageManagerInt.getPackage(bp.getSourcePackageName());
                synchronized (mLock) {
                    if (sourcePkg != null && sourcePkg.mExtras != null) {
                        final PackageSetting sourcePs = (PackageSetting) sourcePkg.mExtras;
                        if (bp.getSourcePackageSetting() == null) {
                            bp.setSourcePackageSetting(sourcePs);
                        }
                        continue;
                    }
                    Slog.w(TAG, "Removing dangling permission tree: " + bp.getName()
                            + " from package " + bp.getSourcePackageName());
                    mSettings.removePermissionLocked(bp.getName());
                }
            }
        }
        return flags;
    }

    private void updatePermissionFlags(String permName, String packageName, int flagMask,
            int flagValues, int callingUid, int userId, PermissionCallback callback) {
        if (!mUserManagerInt.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlags");

        enforceCrossUserPermission(callingUid, userId,
                true /* requireFullPermission */, true /* checkShell */,
                "updatePermissionFlags");

        // Only the system can change these flags and nothing else.
        if (callingUid != Process.SYSTEM_UID) {
            flagMask &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagValues &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagMask &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
        }

        final PackageParser.Package pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        final BasePermission bp;
        synchronized (mLock) {
            bp = mSettings.getPermissionLocked(permName);
        }
        if (bp == null) {
            throw new IllegalArgumentException("Unknown permission: " + permName);
        }

        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        final PermissionsState permissionsState = ps.getPermissionsState();
        final boolean hadState =
                permissionsState.getRuntimePermissionState(permName, userId) != null;
        final boolean permissionUpdated =
                permissionsState.updatePermissionFlags(bp, userId, flagMask, flagValues);
        if (permissionUpdated && callback != null) {
            // Install and runtime permissions are stored in different places,
            // so figure out what permission changed and persist the change.
            if (permissionsState.getInstallPermissionState(permName) != null) {
                callback.onInstallPermissionUpdated();
            } else if (permissionsState.getRuntimePermissionState(permName, userId) != null
                    || hadState) {
                callback.onPermissionUpdated(new int[] { userId }, false);
            }
        }
    }

    private boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues, int callingUid,
            int userId, Collection<Package> packages, PermissionCallback callback) {
        if (!mUserManagerInt.exists(userId)) {
            return false;
        }

        enforceGrantRevokeRuntimePermissionPermissions(
                "updatePermissionFlagsForAllApps");
        enforceCrossUserPermission(callingUid, userId,
                true /* requireFullPermission */, true /* checkShell */,
                "updatePermissionFlagsForAllApps");

        // Only the system can change system fixed flags.
        if (callingUid != Process.SYSTEM_UID) {
            flagMask &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagValues &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
        }

        boolean changed = false;
        for (PackageParser.Package pkg : packages) {
            final PackageSetting ps = (PackageSetting) pkg.mExtras;
            if (ps == null) {
                continue;
            }
            PermissionsState permissionsState = ps.getPermissionsState();
            changed |= permissionsState.updatePermissionFlagsForAllPermissions(
                    userId, flagMask, flagValues);
        }
        return changed;
    }

    private void enforceGrantRevokeRuntimePermissionPermissions(String message) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.GRANT_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);
        }
    }

    /**
     * Checks if the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the userid is not for the caller.
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    private void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction(
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        if (userId == UserHandle.getUserId(callingUid)) return;
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            if (requireFullPermission) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
            } else {
                try {
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
                } catch (SecurityException se) {
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.INTERACT_ACROSS_USERS, message);
                }
            }
        }
    }

    private int calculateCurrentPermissionFootprintLocked(BasePermission tree) {
        int size = 0;
        for (BasePermission perm : mSettings.mPermissions.values()) {
            size += tree.calculateFootprint(perm);
        }
        return size;
    }

    private void enforcePermissionCapLocked(PermissionInfo info, BasePermission tree) {
        // We calculate the max size of permissions defined by this uid and throw
        // if that plus the size of 'info' would exceed our stated maximum.
        if (tree.getUid() != Process.SYSTEM_UID) {
            final int curTreeSize = calculateCurrentPermissionFootprintLocked(tree);
            if (curTreeSize + info.calculateFootprint() > MAX_PERMISSION_TREE_FOOTPRINT) {
                throw new SecurityException("Permission tree size cap exceeded");
            }
        }
    }

    private void systemReady() {
        mSystemReady = true;
        if (mPrivappPermissionsViolations != null) {
            throw new IllegalStateException("Signature|privileged permissions not in "
                    + "privapp-permissions whitelist: " + mPrivappPermissionsViolations);
        }
    }

    private static String getVolumeUuidForPackage(PackageParser.Package pkg) {
        if (pkg == null) {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
        if (pkg.isExternal()) {
            if (TextUtils.isEmpty(pkg.volumeUuid)) {
                return StorageManager.UUID_PRIMARY_PHYSICAL;
            } else {
                return pkg.volumeUuid;
            }
        } else {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
    }

    private static boolean hasPermission(PackageParser.Package pkgInfo, String permName) {
        for (int i=pkgInfo.permissions.size()-1; i>=0; i--) {
            if (pkgInfo.permissions.get(i).info.name.equals(permName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Log that a permission request was granted/revoked.
     *
     * @param action the action performed
     * @param name name of the permission
     * @param packageName package permission is for
     */
    private void logPermission(int action, @NonNull String name, @NonNull String packageName) {
        final LogMaker log = new LogMaker(action);
        log.setPackageName(packageName);
        log.addTaggedData(MetricsEvent.FIELD_PERMISSION, name);

        mMetricsLogger.write(log);
    }

    private class PermissionManagerInternalImpl extends PermissionManagerInternal {
        @Override
        public void systemReady() {
            PermissionManagerService.this.systemReady();
        }
        @Override
        public boolean isPermissionsReviewRequired(Package pkg, int userId) {
            return PermissionManagerService.this.isPermissionsReviewRequired(pkg, userId);
        }
        @Override
        public void revokeRuntimePermissionsIfGroupChanged(
                @NonNull PackageParser.Package newPackage,
                @NonNull PackageParser.Package oldPackage,
                @NonNull ArrayList<String> allPackageNames,
                @NonNull PermissionCallback permissionCallback) {
            PermissionManagerService.this.revokeRuntimePermissionsIfGroupChanged(newPackage,
                    oldPackage, allPackageNames, permissionCallback);
        }
        @Override
        public void addAllPermissions(Package pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissions(pkg, chatty);
        }
        @Override
        public void addAllPermissionGroups(Package pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissionGroups(pkg, chatty);
        }
        @Override
        public void removeAllPermissions(Package pkg, boolean chatty) {
            PermissionManagerService.this.removeAllPermissions(pkg, chatty);
        }
        @Override
        public boolean addDynamicPermission(PermissionInfo info, boolean async, int callingUid,
                PermissionCallback callback) {
            return PermissionManagerService.this.addDynamicPermission(info, callingUid, callback);
        }
        @Override
        public void removeDynamicPermission(String permName, int callingUid,
                PermissionCallback callback) {
            PermissionManagerService.this.removeDynamicPermission(permName, callingUid, callback);
        }
        @Override
        public void grantRuntimePermission(String permName, String packageName,
                boolean overridePolicy, int callingUid, int userId,
                PermissionCallback callback) {
            PermissionManagerService.this.grantRuntimePermission(
                    permName, packageName, overridePolicy, callingUid, userId, callback);
        }
        @Override
        public void grantRequestedRuntimePermissions(PackageParser.Package pkg, int[] userIds,
                String[] grantedPermissions, int callingUid, PermissionCallback callback) {
            PermissionManagerService.this.grantRequestedRuntimePermissions(
                    pkg, userIds, grantedPermissions, callingUid, callback);
        }
        @Override
        public void grantRuntimePermissionsGrantedToDisabledPackage(PackageParser.Package pkg,
                int callingUid, PermissionCallback callback) {
            PermissionManagerService.this.grantRuntimePermissionsGrantedToDisabledPackageLocked(
                    pkg, callingUid, callback);
        }
        @Override
        public void revokeRuntimePermission(String permName, String packageName,
                boolean overridePolicy, int callingUid, int userId,
                PermissionCallback callback) {
            PermissionManagerService.this.revokeRuntimePermission(permName, packageName,
                    overridePolicy, callingUid, userId, callback);
        }
        @Override
        public void updatePermissions(String packageName, Package pkg, boolean replaceGrant,
                Collection<PackageParser.Package> allPackages, PermissionCallback callback) {
            PermissionManagerService.this.updatePermissions(
                    packageName, pkg, replaceGrant, allPackages, callback);
        }
        @Override
        public void updateAllPermissions(String volumeUuid, boolean sdkUpdated,
                Collection<PackageParser.Package> allPackages, PermissionCallback callback) {
            PermissionManagerService.this.updateAllPermissions(
                    volumeUuid, sdkUpdated, allPackages, callback);
        }
        @Override
        public String[] getAppOpPermissionPackages(String permName) {
            return PermissionManagerService.this.getAppOpPermissionPackages(permName);
        }
        @Override
        public int getPermissionFlags(String permName, String packageName, int callingUid,
                int userId) {
            return PermissionManagerService.this.getPermissionFlags(permName, packageName,
                    callingUid, userId);
        }
        @Override
        public void updatePermissionFlags(String permName, String packageName, int flagMask,
                int flagValues, int callingUid, int userId, PermissionCallback callback) {
            PermissionManagerService.this.updatePermissionFlags(
                    permName, packageName, flagMask, flagValues, callingUid, userId, callback);
        }
        @Override
        public boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues, int callingUid,
                int userId, Collection<Package> packages, PermissionCallback callback) {
            return PermissionManagerService.this.updatePermissionFlagsForAllApps(
                    flagMask, flagValues, callingUid, userId, packages, callback);
        }
        @Override
        public void enforceCrossUserPermission(int callingUid, int userId,
                boolean requireFullPermission, boolean checkShell, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId,
                    requireFullPermission, checkShell, message);
        }
        @Override
        public void enforceGrantRevokeRuntimePermissionPermissions(String message) {
            PermissionManagerService.this.enforceGrantRevokeRuntimePermissionPermissions(message);
        }
        @Override
        public int checkPermission(String permName, String packageName, int callingUid,
                int userId) {
            return PermissionManagerService.this.checkPermission(
                    permName, packageName, callingUid, userId);
        }
        @Override
        public int checkUidPermission(String permName, PackageParser.Package pkg, int uid,
                int callingUid) {
            return PermissionManagerService.this.checkUidPermission(permName, pkg, uid, callingUid);
        }
        @Override
        public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags,
                int callingUid) {
            return PermissionManagerService.this.getPermissionGroupInfo(
                    groupName, flags, callingUid);
        }
        @Override
        public List<PermissionGroupInfo> getAllPermissionGroups(int flags, int callingUid) {
            return PermissionManagerService.this.getAllPermissionGroups(flags, callingUid);
        }
        @Override
        public PermissionInfo getPermissionInfo(String permName, String packageName, int flags,
                int callingUid) {
            return PermissionManagerService.this.getPermissionInfo(
                    permName, packageName, flags, callingUid);
        }
        @Override
        public List<PermissionInfo> getPermissionInfoByGroup(String group, int flags,
                int callingUid) {
            return PermissionManagerService.this.getPermissionInfoByGroup(group, flags, callingUid);
        }
        @Override
        public PermissionSettings getPermissionSettings() {
            return mSettings;
        }
        @Override
        public DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy() {
            return mDefaultPermissionGrantPolicy;
        }
        @Override
        public BasePermission getPermissionTEMP(String permName) {
            synchronized (PermissionManagerService.this.mLock) {
                return mSettings.getPermissionLocked(permName);
            }
        }
    }
}
