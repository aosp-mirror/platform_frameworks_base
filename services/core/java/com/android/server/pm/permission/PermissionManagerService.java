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
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.permissionToOp;
import static android.app.AppOpsManager.permissionToOpCode;
import static android.content.pm.PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.MASK_PERMISSION_FLAGS;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.UserHandle.getAppId;
import static android.os.UserHandle.getUid;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_PERMISSIONS;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.permission.PermissionsState.PERMISSION_OPERATION_FAILURE;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
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
import android.permission.PermissionControllerManager;
import android.permission.PermissionManager;
import android.permission.PermissionManagerInternal;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

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
import com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback;
import com.android.server.pm.permission.PermissionsState.PermissionState;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static final long BACKUP_TIMEOUT_MILLIS = SECONDS.toMillis(60);

    /** Cap the size of permission trees that 3rd party apps can define; in characters of text */
    private static final int MAX_PERMISSION_TREE_FOOTPRINT = 32768;
    /** Empty array to avoid allocations */
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * When these flags are set, the system should not automatically modify the permission grant
     * state.
     */
    private static final int BLOCKING_PERMISSION_FLAGS = FLAG_PERMISSION_SYSTEM_FIXED
            | FLAG_PERMISSION_POLICY_FIXED
            | FLAG_PERMISSION_GRANTED_BY_DEFAULT;

    /** Permission flags set by the user */
    private static final int USER_PERMISSION_FLAGS = FLAG_PERMISSION_USER_SET
            | FLAG_PERMISSION_USER_FIXED;

    /** If the permission of the value is granted, so is the key */
    private static final Map<String, String> FULLER_PERMISSION_MAP = new HashMap<>();

    static {
        FULLER_PERMISSION_MAP.put(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);
        FULLER_PERMISSION_MAP.put(Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    /** Lock to protect internal data access */
    private final Object mLock;

    /** Internal connection to the package manager */
    private final PackageManagerInternal mPackageManagerInt;

    /** Internal connection to the user manager */
    private final UserManagerInternal mUserManagerInt;

    /** Permission controller: User space permission management */
    private PermissionControllerManager mPermissionControllerManager;

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

    /**
     * For each foreground/background permission the mapping:
     * Background permission -> foreground permissions
     */
    @GuardedBy("mLock")
    private ArrayMap<String, List<String>> mBackgroundPermissions;

    /**
     * A permission backup might contain apps that are not installed. In this case we delay the
     * restoration until the app is installed.
     *
     * <p>This array ({@code userId -> noDelayedBackupLeft}) is {@code true} for all the users where
     * there is <u>no more</u> delayed backup left.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mHasNoDelayedPermBackup = new SparseBooleanArray();

    PermissionManagerService(Context context,
            @Nullable DefaultPermissionGrantedCallback defaultGrantCallback,
            @NonNull Object externalLock) {
        mContext = context;
        mLock = externalLock;
        mPackageManagerInt = LocalServices.getService(PackageManagerInternal.class);
        mUserManagerInt = LocalServices.getService(UserManagerInternal.class);
        mSettings = new PermissionSettings(mLock);

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

        PermissionManagerServiceInternalImpl localService =
                new PermissionManagerServiceInternalImpl();
        LocalServices.addService(PermissionManagerServiceInternal.class, localService);
        LocalServices.addService(PermissionManagerInternal.class, localService);
    }

    /**
     * Creates and returns an initialized, internal service for use by other components.
     * <p>
     * The object returned is identical to the one returned by the LocalServices class using:
     * {@code LocalServices.getService(PermissionManagerServiceInternal.class);}
     * <p>
     * NOTE: The external lock is temporary and should be removed. This needs to be a
     * lock created by the permission manager itself.
     */
    public static PermissionManagerServiceInternal create(Context context,
            @Nullable DefaultPermissionGrantedCallback defaultGrantCallback,
            @NonNull Object externalLock) {
        final PermissionManagerServiceInternal permMgrInt =
                LocalServices.getService(PermissionManagerServiceInternal.class);
        if (permMgrInt != null) {
            return permMgrInt;
        }
        new PermissionManagerService(context, defaultGrantCallback, externalLock);
        return LocalServices.getService(PermissionManagerServiceInternal.class);
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
            if (isImpliedPermissionGranted(permissionsState, permName, userId)) {
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
            if (isImpliedPermissionGranted(permissionsState, permName, userId)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        } else {
            ArraySet<String> perms = mSystemPermissions.get(uid);
            if (perms != null) {
                if (perms.contains(permName)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                if (FULLER_PERMISSION_MAP.containsKey(permName)
                        && perms.contains(FULLER_PERMISSION_MAP.get(permName))) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    /**
     * Get the state of the runtime permissions as xml file.
     *
     * <p>Can not be called on main thread.
     *
     * @param user The user the data should be extracted for
     *
     * @return The state as a xml file
     */
    private @Nullable byte[] backupRuntimePermissions(@NonNull UserHandle user) {
        CompletableFuture<byte[]> backup = new CompletableFuture<>();
        mPermissionControllerManager.getRuntimePermissionBackup(user, mContext.getMainExecutor(),
                backup::complete);

        try {
            return backup.get(BACKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException  | TimeoutException e) {
            Slog.e(TAG, "Cannot create permission backup for " + user, e);
            return null;
        }
    }

    /**
     * Restore a permission state previously backed up via {@link #backupRuntimePermissions}.
     *
     * <p>If not all state can be restored, the un-appliable state will be delayed and can be
     * applied via {@link #restoreDelayedRuntimePermissions}.
     *
     * @param backup The state as an xml file
     * @param user The user the data should be restored for
     */
    private void restoreRuntimePermissions(@NonNull byte[] backup, @NonNull UserHandle user) {
        synchronized (mLock) {
            mHasNoDelayedPermBackup.delete(user.getIdentifier());
            mPermissionControllerManager.restoreRuntimePermissionBackup(backup, user);
        }
    }

    /**
     * Try to apply permission backup that was previously not applied.
     *
     * <p>Can not be called on main thread.
     *
     * @param packageName The package that is newly installed
     * @param user The user the package is installed for
     *
     * @see #restoreRuntimePermissions
     */
    private void restoreDelayedRuntimePermissions(@NonNull String packageName,
            @NonNull UserHandle user) {
        synchronized (mLock) {
            if (mHasNoDelayedPermBackup.get(user.getIdentifier(), false)) {
                return;
            }

            mPermissionControllerManager.restoreDelayedRuntimePermissionBackup(packageName, user,
                    mContext.getMainExecutor(), (hasMoreBackup) -> {
                        if (hasMoreBackup) {
                            return;
                        }

                        synchronized (mLock) {
                            mHasNoDelayedPermBackup.put(user.getIdentifier(), true);
                        }
                    });
        }
    }

    /**
     * Returns {@code true} if the permission can be implied from another granted permission.
     * <p>Some permissions, such as ACCESS_FINE_LOCATION, imply other permissions,
     * such as ACCESS_COURSE_LOCATION. If the caller holds an umbrella permission, give
     * it access to any implied permissions.
     */
    private static boolean isImpliedPermissionGranted(PermissionsState permissionsState,
            String permName, int userId) {
        return FULLER_PERMISSION_MAP.containsKey(permName)
                && permissionsState.hasPermission(FULLER_PERMISSION_MAP.get(permName), userId);
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
                                        "Revoking permission " + permissionName +
                                        " from package " + packageName +
                                        " as the group changed from " + oldPermissionGroupName +
                                        " to " + newPermissionGroupName);

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
            } else if (!bp.isDynamic()) {
                throw new SecurityException("Not allowed to modify non-dynamic permission "
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

    /**
     * Restore the permission state for a package.
     *
     * <ul>
     *     <li>During boot the state gets restored from the disk</li>
     *     <li>During app update the state gets restored from the last version of the app</li>
     * </ul>
     *
     * <p>This restores the permission state for all users.
     *
     * @param pkg the package the permissions belong to
     * @param replace if the package is getting replaced (this might change the requested
     *                permissions of this package)
     * @param packageOfInterest If this is the name of {@code pkg} add extra logging
     * @param callback Result call back
     */
    private void restorePermissionState(@NonNull PackageParser.Package pkg, boolean replace,
            @Nullable String packageOfInterest, @Nullable PermissionCallback callback) {
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
                    if (origPermissions.hasInstallPermission(bp.getName())) {
                        // Before Q we represented some runtime permissions as install permissions,
                        // in Q we cannot do this anymore. Hence upgrade them all.
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
                    Slog.i(TAG, "Considering granting permission " + perm + " to package "
                            + pkg.packageName);
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
                                    PERMISSION_OPERATION_FAILURE) {
                                changedInstallPermission = true;
                            }
                        } break;

                        case GRANT_RUNTIME: {
                            for (int userId : currentUserIds) {
                                PermissionState permState = origPermissions
                                        .getRuntimePermissionState(perm, userId);
                                int flags = permState != null ? permState.getFlags() : 0;

                                boolean wasChanged = false;

                                if (appSupportsRuntimePermissions) {
                                    // Remove review flag as it is not necessary anymore
                                    if ((flags & FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                                        flags &= ~FLAG_PERMISSION_REVIEW_REQUIRED;
                                        wasChanged = true;
                                    }

                                    if ((flags & FLAG_PERMISSION_REVOKE_ON_UPGRADE) != 0) {
                                        flags &= ~FLAG_PERMISSION_REVOKE_ON_UPGRADE;
                                        wasChanged = true;
                                    } else {
                                        if (permState != null && permState.isGranted()) {
                                            if (permissionsState.grantRuntimePermission(bp, userId)
                                                    == PERMISSION_OPERATION_FAILURE) {
                                                wasChanged = true;
                                            }
                                        }
                                    }
                                } else {
                                    if (permState == null) {
                                        // New permission
                                        if (PLATFORM_PACKAGE_NAME.equals(
                                                bp.getSourcePackageName())) {
                                            if (!bp.isRemoved()) {
                                                flags |= FLAG_PERMISSION_REVIEW_REQUIRED
                                                        | FLAG_PERMISSION_REVOKE_ON_UPGRADE;
                                                wasChanged = true;
                                            }
                                        }
                                    }

                                    if (permissionsState.grantRuntimePermission(bp, userId)
                                            != PERMISSION_OPERATION_FAILURE) {
                                        wasChanged = true;
                                    }
                                }

                                if (wasChanged) {
                                    updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                                }

                                permissionsState.updatePermissionFlags(bp, userId,
                                        MASK_PERMISSION_FLAGS, flags);
                            }
                        } break;

                        case GRANT_UPGRADE: {
                            // Upgrade from Pre-Q to Q permission model. Make all permissions
                            // runtime
                            PermissionState permState = origPermissions
                                    .getInstallPermissionState(perm);
                            int flags = (permState != null) ? permState.getFlags() : 0;

                            // Remove install permission
                            if (origPermissions.revokeInstallPermission(bp)
                                    != PERMISSION_OPERATION_FAILURE) {
                                origPermissions.updatePermissionFlags(bp, UserHandle.USER_ALL,
                                        PackageManager.MASK_PERMISSION_FLAGS, 0);
                                changedInstallPermission = true;
                            }

                            for (int userId : currentUserIds) {
                                boolean wasChanged = false;

                                if (appSupportsRuntimePermissions) {
                                    // Remove review flag as it is not necessary anymore
                                    if ((flags & FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                                        flags &= ~FLAG_PERMISSION_REVIEW_REQUIRED;
                                        wasChanged = true;
                                    }

                                    if ((flags & FLAG_PERMISSION_REVOKE_ON_UPGRADE) != 0) {
                                        flags &= ~FLAG_PERMISSION_REVOKE_ON_UPGRADE;
                                        wasChanged = true;
                                    } else {
                                        if (permissionsState.grantRuntimePermission(bp, userId) !=
                                                PERMISSION_OPERATION_FAILURE) {
                                             wasChanged = true;
                                        }
                                    }
                                } else {
                                    if (permissionsState.grantRuntimePermission(bp, userId) !=
                                            PERMISSION_OPERATION_FAILURE) {
                                        flags |= FLAG_PERMISSION_REVIEW_REQUIRED;
                                        wasChanged = true;
                                    }
                                }

                                if (wasChanged) {
                                    updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                                }

                                permissionsState.updatePermissionFlags(bp, userId,
                                        MASK_PERMISSION_FLAGS, flags);
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
                            PERMISSION_OPERATION_FAILURE) {
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

            updatedUserIds = revokePermissionsNoLongerImplicitLocked(permissionsState, pkg,
                    updatedUserIds);
            updatedUserIds = setInitialGrantForNewImplicitPermissionsLocked(origPermissions,
                    permissionsState, pkg, updatedUserIds);

            setAppOpsLocked(permissionsState, pkg);
        }

        // Persist the runtime permissions state for users with changes. If permissions
        // were revoked because no app in the shared user declares them we have to
        // write synchronously to avoid losing runtime permissions state.
        if (callback != null) {
            callback.onPermissionUpdated(updatedUserIds, runtimePermissionsRevoked);
        }
    }

    /**
     * Set app op for a app-op related to a permission.
     *
     * @param permission The permission the app-op belongs to
     * @param pkg The package the permission belongs to
     * @param userId The user to be changed
     * @param mode The new mode to set
     */
    private void setAppOpMode(@NonNull String permission, @NonNull PackageParser.Package pkg,
            @UserIdInt int userId, int mode) {
        AppOpsManagerInternal appOpsInternal = LocalServices.getService(
                AppOpsManagerInternal.class);

        appOpsInternal.setUidMode(permissionToOpCode(permission),
                getUid(userId, getAppId(pkg.applicationInfo.uid)), mode);
    }

    /**
     * Revoke permissions that are not implicit anymore and that have
     * {@link PackageManager#FLAG_PERMISSION_REVOKE_WHEN_REQUESTED} set.
     *
     * @param ps The state of the permissions of the package
     * @param pkg The package that is currently looked at
     * @param updatedUserIds a list of user ids that needs to be amended if the permission state
     *                       for a user is changed.
     *
     * @return The updated value of the {@code updatedUserIds} parameter
     */
    private @NonNull int[] revokePermissionsNoLongerImplicitLocked(
            @NonNull PermissionsState ps, @NonNull PackageParser.Package pkg,
            @NonNull int[] updatedUserIds) {
        AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);

        String pkgName = pkg.packageName;
        boolean supportsRuntimePermissions = pkg.applicationInfo.targetSdkVersion
                >= Build.VERSION_CODES.M;

        int[] users = UserManagerService.getInstance().getUserIds();
        int numUsers = users.length;
        for (int i = 0; i < numUsers; i++) {
            int userId = users[i];

            for (String permission : ps.getPermissions(userId)) {
                if (!pkg.implicitPermissions.contains(permission)) {
                    if (!ps.hasInstallPermission(permission)) {
                        int flags = ps.getRuntimePermissionState(permission, userId).getFlags();

                        if ((flags & FLAG_PERMISSION_REVOKE_WHEN_REQUESTED) != 0) {
                            BasePermission bp = mSettings.getPermissionLocked(permission);

                            int flagsToRemove = FLAG_PERMISSION_REVOKE_WHEN_REQUESTED;

                            if ((flags & BLOCKING_PERMISSION_FLAGS) == 0
                                    && supportsRuntimePermissions) {
                                int revokeResult = ps.revokeRuntimePermission(bp, userId);
                                if (revokeResult != PERMISSION_OPERATION_FAILURE) {
                                    if (DEBUG_PERMISSIONS) {
                                        Slog.i(TAG, "Revoking runtime permission "
                                                + permission + " for " + pkgName
                                                + " as it is now requested");
                                    }
                                }

                                flagsToRemove |= USER_PERMISSION_FLAGS;

                                List<String> fgPerms = mBackgroundPermissions.get(permission);
                                if (fgPerms != null) {
                                    int numFgPerms = fgPerms.size();
                                    for (int fgPermNum = 0; fgPermNum < numFgPerms; fgPermNum++) {
                                        String fgPerm = fgPerms.get(fgPermNum);

                                        int mode = appOpsManager.unsafeCheckOpRaw(
                                                permissionToOp(fgPerm),
                                                getUid(userId, getAppId(pkg.applicationInfo.uid)),
                                                pkgName);

                                        if (mode == MODE_ALLOWED) {
                                            setAppOpMode(fgPerm, pkg, userId, MODE_FOREGROUND);
                                        }
                                    }
                                }
                            }

                            ps.updatePermissionFlags(bp, userId, flagsToRemove, 0);
                            updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                        }
                    }
                }
            }
        }

        return updatedUserIds;
    }

    /**
     * {@code newPerm} is newly added; Inherit the state from {@code sourcePerms}.
     *
     * <p>A single new permission can be split off from several source permissions. In this case
     * the most leniant state is inherited.
     *
     * <p>Warning: This does not handle foreground / background permissions
     *
     * @param sourcePerms The permissions to inherit from
     * @param newPerm The permission to inherit to
     * @param ps The permission state of the package
     * @param pkg The package requesting the permissions
     * @param userId The user the permission belongs to
     */
    private void inheritPermissionStateToNewImplicitPermissionLocked(
            @NonNull ArraySet<String> sourcePerms, @NonNull String newPerm,
            @NonNull PermissionsState ps, @NonNull PackageParser.Package pkg,
            @UserIdInt int userId) {
        AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
        String pkgName = pkg.packageName;

        if (pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
            if (permissionToOp(newPerm) != null) {
                int mostLenientSourceMode = MODE_ERRORED;
                int flags = 0;

                // Find most lenient source permission state.
                int numSourcePerms = sourcePerms.size();
                for (int i = 0; i < numSourcePerms; i++) {
                    String sourcePerm = sourcePerms.valueAt(i);

                    if (ps.hasRuntimePermission(sourcePerm, userId)) {
                        String sourceOp = permissionToOp(sourcePerm);

                        if (sourceOp != null) {
                            int mode = appOpsManager.unsafeCheckOpRaw(sourceOp,
                                    getUid(userId, getAppId(pkg.applicationInfo.uid)), pkgName);

                            if (mode == MODE_FOREGROUND || mode == MODE_ERRORED) {
                                Log.wtf(TAG, "split permission" + sourcePerm + " has app-op state "
                                        + AppOpsManager.MODE_NAMES[mode]);

                                continue;
                            }

                            // Leniency order: allowed < ignored < default
                            if (mode < mostLenientSourceMode) {
                                mostLenientSourceMode = mode;
                                flags = ps.getPermissionFlags(sourcePerm, userId);
                            } else if (mode == mostLenientSourceMode) {
                                flags |= ps.getPermissionFlags(sourcePerm, userId);
                            }
                        }
                    }
                }

                if (mostLenientSourceMode != MODE_ERRORED) {
                    if (DEBUG_PERMISSIONS) {
                        Slog.i(TAG, newPerm + " inherits app-ops state " + mostLenientSourceMode
                                + " from " + sourcePerms + " for " + pkgName);
                    }

                    setAppOpMode(newPerm, pkg, userId, mostLenientSourceMode);

                    // Add permission flags
                    ps.updatePermissionFlags(mSettings.getPermission(newPerm), userId, flags,
                            flags);
                }
            }
        } else {
            boolean isGranted = false;
            int flags = 0;

            int numSourcePerm = sourcePerms.size();
            for (int i = 0; i < numSourcePerm; i++) {
                String sourcePerm = sourcePerms.valueAt(i);
                if ((ps.hasRuntimePermission(sourcePerm, userId))
                        || ps.hasInstallPermission(sourcePerm)) {
                    if (!isGranted) {
                        flags = 0;
                    }

                    isGranted = true;
                    flags |= ps.getPermissionFlags(sourcePerm, userId);
                } else {
                    if (!isGranted) {
                        flags |= ps.getPermissionFlags(sourcePerm, userId);
                    }
                }
            }

            if (isGranted) {
                if (DEBUG_PERMISSIONS) {
                    Slog.i(TAG, newPerm + " inherits runtime perm grant from " + sourcePerms
                            + " for " + pkgName);
                }

                ps.grantRuntimePermission(mSettings.getPermissionLocked(newPerm), userId);
            }

            // Add permission flags
            ps.updatePermissionFlags(mSettings.getPermission(newPerm), userId, flags, flags);
        }
    }

    /**
     * Set the state of a implicit permission that is seen for the first time.
     *
     * @param origPs The permission state of the package before the split
     * @param ps The new permission state
     * @param pkg The package the permission belongs to
     * @param updatedUserIds List of users for which the permission state has already been changed
     *
     * @return  List of users for which the permission state has been changed
     */
    private @NonNull int[] setInitialGrantForNewImplicitPermissionsLocked(
            @NonNull PermissionsState origPs,
            @NonNull PermissionsState ps, @NonNull PackageParser.Package pkg,
            @NonNull int[] updatedUserIds) {
        String pkgName = pkg.packageName;
        ArraySet<String> newImplicitPermissions = new ArraySet<>();

        int numRequestedPerms = pkg.requestedPermissions.size();
        for (int i = 0; i < numRequestedPerms; i++) {
            BasePermission bp = mSettings.getPermissionLocked(pkg.requestedPermissions.get(i));
            if (bp != null) {
                String perm = bp.getName();

                if (!origPs.hasRequestedPermission(perm) && pkg.implicitPermissions.contains(
                        perm)) {
                    newImplicitPermissions.add(perm);

                    if (DEBUG_PERMISSIONS) {
                        Slog.i(TAG, perm + " is newly added for " + pkgName);
                    }
                }
            }
        }

        ArrayMap<String, ArraySet<String>> newToSplitPerms = new ArrayMap<>();

        int numSplitPerms = PermissionManager.SPLIT_PERMISSIONS.size();
        for (int splitPermNum = 0; splitPermNum < numSplitPerms; splitPermNum++) {
            PermissionManager.SplitPermissionInfo spi =
                    PermissionManager.SPLIT_PERMISSIONS.get(splitPermNum);

            List<String> newPerms = spi.getNewPermissions();
            int numNewPerms = newPerms.size();
            for (int newPermNum = 0; newPermNum < numNewPerms; newPermNum++) {
                String newPerm = newPerms.get(newPermNum);

                ArraySet<String> splitPerms = newToSplitPerms.get(newPerm);
                if (splitPerms == null) {
                    splitPerms = new ArraySet<>();
                    newToSplitPerms.put(newPerm, splitPerms);
                }

                splitPerms.add(spi.getSplitPermission());
            }
        }

        int numNewImplicitPerms = newImplicitPermissions.size();
        for (int newImplicitPermNum = 0; newImplicitPermNum < numNewImplicitPerms;
                newImplicitPermNum++) {
            String newPerm = newImplicitPermissions.valueAt(newImplicitPermNum);
            ArraySet<String> sourcePerms = newToSplitPerms.get(newPerm);

            if (sourcePerms != null) {
                if (!ps.hasInstallPermission(newPerm)) {
                    BasePermission bp = mSettings.getPermissionLocked(newPerm);

                    int[] users = UserManagerService.getInstance().getUserIds();
                    int numUsers = users.length;
                    for (int userNum = 0; userNum < numUsers; userNum++) {
                        int userId = users[userNum];

                        ps.updatePermissionFlags(bp, userId,
                                FLAG_PERMISSION_REVOKE_WHEN_REQUESTED,
                                FLAG_PERMISSION_REVOKE_WHEN_REQUESTED);
                        updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);

                        boolean inheritsFromInstallPerm = false;
                        for (int sourcePermNum = 0; sourcePermNum < sourcePerms.size();
                                sourcePermNum++) {
                            if (ps.hasInstallPermission(sourcePerms.valueAt(sourcePermNum))) {
                                inheritsFromInstallPerm = true;
                                break;
                            }
                        }

                        if (!origPs.hasRequestedPermission(sourcePerms)
                                && !inheritsFromInstallPerm) {
                            // Both permissions are new so nothing to inherit.
                            if (DEBUG_PERMISSIONS) {
                                Slog.i(TAG, newPerm + " does not inherit from " + sourcePerms
                                        + " for " + pkgName + " as split permission is also new");
                            }

                            break;
                        } else {
                            // Inherit from new install or existing runtime permissions
                            inheritPermissionStateToNewImplicitPermissionLocked(sourcePerms,
                                    newPerm, ps, pkg, userId);
                        }
                    }
                }
            }
        }

        return updatedUserIds;
    }

    /**
     * Fix app-op modes for runtime permissions.
     *
     * @param permsState The state of the permissions of the package
     * @param pkg The package information
     */
    private void setAppOpsLocked(@NonNull PermissionsState permsState,
            @NonNull PackageParser.Package pkg) {
        for (int userId : UserManagerService.getInstance().getUserIds()) {
            int numPerms = pkg.requestedPermissions.size();
            for (int i = 0; i < numPerms; i++) {
                String permission = pkg.requestedPermissions.get(i);

                int op = permissionToOpCode(permission);
                if (op == OP_NONE) {
                    continue;
                }

                // Runtime permissions are per uid, not per package, hence per package app-op
                // modes should never have been set. It is possible to set them via the shell
                // though. Revert such settings during boot to get the device back into a good
                // state.
                LocalServices.getService(AppOpsManagerInternal.class).setAllPkgModesToDefault(
                        op, getUid(userId, getAppId(pkg.applicationInfo.uid)));

                // For pre-M apps the runtime permission do not store the state
                if (pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
                    continue;
                }

                PermissionState state = permsState.getRuntimePermissionState(permission, userId);
                if (state == null) {
                    continue;
                }

                // Adjust app-op mods for foreground/background permissions. If an package used to
                // have both fg and bg permission granted and it lost the bg permission during an
                // upgrade the app-op mode should get downgraded to foreground.
                if (state.isGranted()) {
                    BasePermission bp = mSettings.getPermission(permission);

                    if (bp != null && bp.perm != null && bp.perm.info != null
                            && bp.perm.info.backgroundPermission != null) {
                        PermissionState bgState = permsState.getRuntimePermissionState(
                                bp.perm.info.backgroundPermission, userId);

                        setAppOpMode(permission, pkg, userId, bgState != null && bgState.isGranted()
                                        ? MODE_ALLOWED : MODE_FOREGROUND);
                    }
                }
            }
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
        } else if (pkg.isProductServices()) {
            wlPermissions =
                    SystemConfig.getInstance().getProductServicesPrivAppPermissions(
                            pkg.packageName);
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
                    } else if (pkg.isProductServices()) {
                        deniedPermissions = SystemConfig.getInstance()
                                .getProductServicesPrivAppDenyPermissions(pkg.packageName);
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
                            mPackageManagerInt.getDisabledSystemPackage(pkg.packageName);
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
                                    .getDisabledSystemPackage(pkg.parentPackage.packageName);
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
            // TODO (moltmann): The installer now shares the platforms signature. Hence it does not
            //                  need a separate flag anymore. Hence we need to check which
            //                  permissions are needed by the permission controller
            if (!allowed && bp.isInstaller()
                    && (pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM))
                    || pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_PERMISSION_CONTROLLER,
                            UserHandle.USER_SYSTEM)))) {
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
            if (!allowed && bp.isConfigurator()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                    PackageManagerInternal.PACKAGE_CONFIGURATOR,
                    UserHandle.USER_SYSTEM))) {
                // Special permissions for the device configurator.
                allowed = true;
            }
            if (!allowed && bp.isWellbeing()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                    PackageManagerInternal.PACKAGE_WELLBEING, UserHandle.USER_SYSTEM))) {
                // Special permission granted only to the OEM specified wellbeing app
                allowed = true;
            }
            if (!allowed && bp.isDocumenter()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_DOCUMENTER, UserHandle.USER_SYSTEM))) {
                // If this permission is to be granted to the documenter and
                // this app is the documenter, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isIncidentReportApprover()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                            PackageManagerInternal.PACKAGE_INCIDENT_REPORT_APPROVER,
                            UserHandle.USER_SYSTEM))) {
                // If this permission is to be granted to the incident report approver and
                // this app is the incident report approver, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isAppPredictor()
                    && pkg.packageName.equals(mPackageManagerInt.getKnownPackageName(
                        PackageManagerInternal.PACKAGE_APP_PREDICTOR, UserHandle.USER_SYSTEM))) {
                // Special permissions for the system app predictor.
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
                mPackageManagerInt.getDisabledSystemPackage(pkg.parentPackage.packageName);
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
                } else {
                    // In permission review mode we clear the review flag when we
                    // are asked to install the app with all permissions granted.
                    if ((flags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                        updatePermissionFlags(permission, pkg.packageName,
                                PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED, 0, callingUid,
                                userId, false, callback);
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
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
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
        if (pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M
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
                    PERMISSION_OPERATION_FAILURE) {
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
            case PERMISSION_OPERATION_FAILURE: {
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
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
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
        if (pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M
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
                    PERMISSION_OPERATION_FAILURE) {
                if (callback != null) {
                    callback.onInstallPermissionRevoked();
                }
            }
            return;
        }

        if (permissionsState.revokeRuntimePermission(bp, userId) ==
                PERMISSION_OPERATION_FAILURE) {
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
                true,  // requireFullPermission
                false, // checkShell
                false, // requirePermissionWhenSameUser
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

    private void updateAllPermissions(String volumeUuid, int oldSdkVersion,
            Collection<PackageParser.Package> allPackages, PermissionCallback callback) {
        boolean sdkUpdated = oldSdkVersion < Build.VERSION.SDK_INT;

        final int flags = UPDATE_PERMISSIONS_ALL |
                (sdkUpdated
                        ? UPDATE_PERMISSIONS_REPLACE_PKG | UPDATE_PERMISSIONS_REPLACE_ALL
                        : 0);
        updatePermissions(null, null, volumeUuid, flags, allPackages, callback);

        if (oldSdkVersion < Build.VERSION_CODES.Q) {
            final int[] userIds = UserManagerService.getInstance().getUserIds();

            for (PackageParser.Package pkg : allPackages) {
                final PackageSetting ps = (PackageSetting) pkg.mExtras;
                if (ps == null) {
                    return;
                }

                final boolean appSupportsRuntimePermissions =
                        pkg.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.M;
                final PermissionsState permsState = ps.getPermissionsState();

                for (String permName : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION}) {
                    final BasePermission bp = mSettings.getPermissionLocked(permName);

                    for (int userId : userIds) {
                        final PermissionState permState = permsState.getRuntimePermissionState(
                                permName, userId);

                        if (permState != null
                                && (permState.getFlags() & BLOCKING_PERMISSION_FLAGS) == 0) {
                            if (permState.isGranted()) {
                                permsState.updatePermissionFlags(bp, userId,
                                        USER_PERMISSION_FLAGS, 0);
                            }

                            if (appSupportsRuntimePermissions) {
                                permsState.revokeRuntimePermission(bp, userId);
                            } else {
                                // Force a review even for apps that were already installed
                                permsState.updatePermissionFlags(bp, userId,
                                        FLAG_PERMISSION_REVIEW_REQUIRED,
                                        FLAG_PERMISSION_REVIEW_REQUIRED);
                            }
                        }
                    }
                }
            }
        }
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

        synchronized (mLock) {
            if (mBackgroundPermissions == null) {
                // Cache background -> foreground permission mapping.
                // Only system declares background permissions, hence mapping does never change.
                mBackgroundPermissions = new ArrayMap<>();
                for (BasePermission bp : mSettings.getAllPermissionsLocked()) {
                    if (bp.perm != null && bp.perm.info != null
                            && bp.perm.info.backgroundPermission != null) {
                        String fgPerm = bp.name;
                        String bgPerm = bp.perm.info.backgroundPermission;

                        List<String> fgPerms = mBackgroundPermissions.get(bgPerm);
                        if (fgPerms == null) {
                            fgPerms = new ArrayList<>();
                            mBackgroundPermissions.put(bgPerm, fgPerms);
                        }

                        fgPerms.add(fgPerm);
                    }
                }
            }
        }

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "restorePermissionState");
        // Now update the permissions for all packages, in particular
        // replace the granted permissions of the system packages.
        if ((flags & UPDATE_PERMISSIONS_ALL) != 0) {
            for (PackageParser.Package pkg : allPackages) {
                if (pkg != changingPkg) {
                    // Only replace for packages on requested volume
                    final String volumeUuid = getVolumeUuidForPackage(pkg);
                    final boolean replace = ((flags & UPDATE_PERMISSIONS_REPLACE_ALL) != 0)
                            && Objects.equals(replaceVolumeUuid, volumeUuid);
                    restorePermissionState(pkg, replace, changingPkgName, callback);
                }
            }
        }

        if (changingPkg != null) {
            // Only replace for packages on requested volume
            final String volumeUuid = getVolumeUuidForPackage(changingPkg);
            final boolean replace = ((flags & UPDATE_PERMISSIONS_REPLACE_PKG) != 0)
                    && Objects.equals(replaceVolumeUuid, volumeUuid);
            restorePermissionState(changingPkg, replace, changingPkgName, callback);
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
            int flagValues, int callingUid, int userId, boolean overridePolicy,
            PermissionCallback callback) {
        if (!mUserManagerInt.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlags");

        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
                "updatePermissionFlags");

        if ((flagMask & FLAG_PERMISSION_POLICY_FIXED) != 0 && !overridePolicy) {
            throw new SecurityException("updatePermissionFlags requires "
                    + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);
        }

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
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
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
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction(
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        if (!requirePermissionWhenSameUser && userId == UserHandle.getUserId(callingUid)) return;
        if (callingUid != Process.SYSTEM_UID && callingUid != Process.ROOT_UID) {
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

    @GuardedBy({"mSettings.mLock", "mLock"})
    private int calculateCurrentPermissionFootprintLocked(BasePermission tree) {
        int size = 0;
        for (BasePermission perm : mSettings.mPermissions.values()) {
            size += tree.calculateFootprint(perm);
        }
        return size;
    }

    @GuardedBy({"mSettings.mLock", "mLock"})
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

        mPermissionControllerManager = mContext.getSystemService(PermissionControllerManager.class);
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

    /**
     * Get the mapping of background permissions to their foreground permissions.
     *
     * <p>Only initialized in the system server.
     *
     * @return the map &lt;bg permission -> list&lt;fg perm&gt;&gt;
     */
    public @Nullable ArrayMap<String, List<String>> getBackgroundPermissions() {
        return mBackgroundPermissions;
    }

    private class PermissionManagerServiceInternalImpl extends PermissionManagerServiceInternal {
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
        public void updateAllPermissions(String volumeUuid, int oldSdkVersion,
                Collection<PackageParser.Package> allPackages, PermissionCallback callback) {
            PermissionManagerService.this.updateAllPermissions(
                    volumeUuid, oldSdkVersion, allPackages, callback);
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
                int flagValues, int callingUid, int userId, boolean overridePolicy,
                PermissionCallback callback) {
            PermissionManagerService.this.updatePermissionFlags(
                    permName, packageName, flagMask, flagValues, callingUid, userId,
                    overridePolicy, callback);
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
                    requireFullPermission, checkShell, false, message);
        }
        @Override
        public void enforceCrossUserPermission(int callingUid, int userId,
                boolean requireFullPermission, boolean checkShell,
                boolean requirePermissionWhenSameUser, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId,
                    requireFullPermission, checkShell, requirePermissionWhenSameUser, message);
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

        @Override
        public @Nullable byte[] backupRuntimePermissions(@NonNull UserHandle user) {
            return PermissionManagerService.this.backupRuntimePermissions(user);
        }

        @Override
        public void restoreRuntimePermissions(@NonNull byte[] backup, @NonNull UserHandle user) {
            PermissionManagerService.this.restoreRuntimePermissions(backup, user);
        }

        @Override
        public void restoreDelayedRuntimePermissions(@NonNull String packageName,
                @NonNull UserHandle user) {
            PermissionManagerService.this.restoreDelayedRuntimePermissions(packageName, user);
        }
    }
}
