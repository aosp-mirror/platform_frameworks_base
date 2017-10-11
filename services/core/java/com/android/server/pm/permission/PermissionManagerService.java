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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageParser.Package;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.StorageManagerInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ArrayUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.ProcessLoggingHandler;
import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.permission.DefaultPermissionGrantPolicy.DefaultPermissionGrantedCallback;
import com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback;
import com.android.server.pm.permission.PermissionsState.PermissionState;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Manages all permissions and handles permissions related tasks.
 */
public class PermissionManagerService {
    private static final String TAG = "PackageManager";

    /** All dangerous permission names in the same order as the events in MetricsEvent */
    private static final List<String> ALL_DANGEROUS_PERMISSIONS = Arrays.asList(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.ADD_VOICEMAIL,
            Manifest.permission.USE_SIP,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.READ_CELL_BROADCASTS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.ANSWER_PHONE_CALLS);

    /** Cap the size of permission trees that 3rd party apps can define */
    private static final int MAX_PERMISSION_TREE_FOOTPRINT = 32768;     // characters of text

    /** Lock to protect internal data access */
    private final Object mLock;

    /** Internal connection to the package manager */
    private final PackageManagerInternal mPackageManagerInt;

    /** Internal connection to the user manager */
    private final UserManagerInternal mUserManagerInt;

    /** Default permission policy to provide proper behaviour out-of-the-box */
    private final DefaultPermissionGrantPolicy mDefaultPermissionGrantPolicy;

    /** Internal storage for permissions and related settings */
    private final PermissionSettings mSettings;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Context mContext;

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

    private PermissionInfo getPermissionInfo(String name, String packageName, int flags,
            int callingUid) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        // reader
        synchronized (mLock) {
            final BasePermission bp = mSettings.getPermissionLocked(name);
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
        // reader
        synchronized (mLock) {
            // TODO Uncomment when mPermissionGroups moves to this class
//            if (groupName != null && !mPermissionGroups.containsKey(groupName)) {
//                // This is thrown as NameNotFoundException
//                return null;
//            }

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

    private void addAllPermissions(PackageParser.Package pkg, boolean chatty) {
        final int N = pkg.permissions.size();
        for (int i=0; i<N; i++) {
            PackageParser.Permission p = pkg.permissions.get(i);

            // Assume by default that we did not install this permission into the system.
            p.info.flags &= ~PermissionInfo.FLAG_INSTALLED;

            // Now that permission groups have a special meaning, we ignore permission
            // groups for legacy apps to prevent unexpected behavior. In particular,
            // permissions for one app being granted to someone just because they happen
            // to be in a group defined by another app (before this had no implications).
            if (pkg.applicationInfo.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
                p.group = mPackageManagerInt.getPermissionGroupTEMP(p.info.group);
                // Warn for a permission in an unknown group.
                if (PackageManagerService.DEBUG_PERMISSIONS
                        && p.info.group != null && p.group == null) {
                    Slog.i(TAG, "Permission " + p.info.name + " from package "
                            + p.info.packageName + " in an unknown group " + p.info.group);
                }
            }

            synchronized (PermissionManagerService.this.mLock) {
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
                    if (PackageManagerService.DEBUG_REMOVE && chatty) {
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
                if (PackageManagerService.DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
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
                if (PackageManagerService.DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
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
                throw new SecurityException(
                        "Not allowed to modify non-dynamic permission "
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
                throw new SecurityException(
                        "Not allowed to modify non-dynamic permission "
                        + permName);
            }
            mSettings.removePermissionLocked(permName);
            if (callback != null) {
                callback.onPermissionRemoved();
            }
        }
    }

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
            logPermissionGranted(mContext, permName, packageName);
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
        if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0) {
            throw new SecurityException("Cannot revoke system fixed permission "
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
            logPermissionRevoked(mContext, permName, packageName);
        }

        if (callback != null) {
            final int uid = UserHandle.getUid(userId, pkg.applicationInfo.uid);
            callback.onPermissionRevoked(pkg.applicationInfo.uid, userId);
        }
    }

    private int[] revokeUnusedSharedUserPermissions(SharedUserSetting suSetting, int[] allUserIds) {
        // Collect all used permissions in the UID
        final ArraySet<String> usedPermissions = new ArraySet<>();
        final List<PackageParser.Package> pkgList = suSetting.getPackages();
        if (pkgList == null || pkgList.size() == 0) {
            return EmptyArray.INT;
        }
        for (PackageParser.Package pkg : pkgList) {
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

    private int updatePermissions(String packageName, PackageParser.Package pkgInfo, int flags) {
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
                        && (pkgInfo == null || !hasPermission(pkgInfo, bp.getName()))) {
                        Slog.i(TAG, "Removing old permission tree: " + bp.getName()
                                + " from package " + bp.getSourcePackageName());
                        flags |= PackageManagerService.UPDATE_PERMISSIONS_ALL;
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
                final PackageParser.Package pkg =
                        mPackageManagerInt.getPackage(bp.getSourcePackageName());
                synchronized (mLock) {
                    if (pkg != null && pkg.mExtras != null) {
                        final PackageSetting ps = (PackageSetting) pkg.mExtras;
                        if (bp.getSourcePackageSetting() == null) {
                            bp.setSourcePackageSetting(ps);
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

    private int updatePermissionTrees(String packageName, PackageParser.Package pkgInfo,
            int flags) {
        Set<BasePermission> needsUpdate = null;
        synchronized (mLock) {
            final Iterator<BasePermission> it = mSettings.mPermissionTrees.values().iterator();
            while (it.hasNext()) {
                final BasePermission bp = it.next();
                if (bp.getSourcePackageSetting() != null) {
                    if (packageName != null && packageName.equals(bp.getSourcePackageName())
                        && (pkgInfo == null || !hasPermission(pkgInfo, bp.getName()))) {
                        Slog.i(TAG, "Removing old permission tree: " + bp.getName()
                                + " from package " + bp.getSourcePackageName());
                        flags |= PackageManagerService.UPDATE_PERMISSIONS_ALL;
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
                final PackageParser.Package pkg =
                        mPackageManagerInt.getPackage(bp.getSourcePackageName());
                synchronized (mLock) {
                    if (pkg != null && pkg.mExtras != null) {
                        final PackageSetting ps = (PackageSetting) pkg.mExtras;
                        if (bp.getSourcePackageSetting() == null) {
                            bp.setSourcePackageSetting(ps);
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
                callback.onPermissionUpdated(userId);
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

    private static boolean hasPermission(PackageParser.Package pkgInfo, String permName) {
        for (int i=pkgInfo.permissions.size()-1; i>=0; i--) {
            if (pkgInfo.permissions.get(i).info.name.equals(permName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the first event id for the permission.
     *
     * <p>There are four events for each permission: <ul>
     *     <li>Request permission: first id + 0</li>
     *     <li>Grant permission: first id + 1</li>
     *     <li>Request for permission denied: first id + 2</li>
     *     <li>Revoke permission: first id + 3</li>
     * </ul></p>
     *
     * @param name name of the permission
     *
     * @return The first event id for the permission
     */
    private static int getBaseEventId(@NonNull String name) {
        int eventIdIndex = ALL_DANGEROUS_PERMISSIONS.indexOf(name);

        if (eventIdIndex == -1) {
            if (AppOpsManager.permissionToOpCode(name) == AppOpsManager.OP_NONE
                    || Build.IS_USER) {
                Log.i(TAG, "Unknown permission " + name);

                return MetricsEvent.ACTION_PERMISSION_REQUEST_UNKNOWN;
            } else {
                // Most likely #ALL_DANGEROUS_PERMISSIONS needs to be updated.
                //
                // Also update
                // - EventLogger#ALL_DANGEROUS_PERMISSIONS
                // - metrics_constants.proto
                throw new IllegalStateException("Unknown permission " + name);
            }
        }

        return MetricsEvent.ACTION_PERMISSION_REQUEST_READ_CALENDAR + eventIdIndex * 4;
    }

    /**
     * Log that a permission was revoked.
     *
     * @param context Context of the caller
     * @param name name of the permission
     * @param packageName package permission if for
     */
    private static void logPermissionRevoked(@NonNull Context context, @NonNull String name,
            @NonNull String packageName) {
        MetricsLogger.action(context, getBaseEventId(name) + 3, packageName);
    }

    /**
     * Log that a permission request was granted.
     *
     * @param context Context of the caller
     * @param name name of the permission
     * @param packageName package permission if for
     */
    private static void logPermissionGranted(@NonNull Context context, @NonNull String name,
            @NonNull String packageName) {
        MetricsLogger.action(context, getBaseEventId(name) + 1, packageName);
    }

    private class PermissionManagerInternalImpl extends PermissionManagerInternal {
        @Override
        public void addAllPermissions(Package pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissions(pkg, chatty);
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
        public int[] revokeUnusedSharedUserPermissions(SharedUserSetting suSetting,
                int[] allUserIds) {
            return PermissionManagerService.this.revokeUnusedSharedUserPermissions(
                    (SharedUserSetting) suSetting, allUserIds);
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
        public int updatePermissions(String packageName,
                PackageParser.Package pkgInfo, int flags) {
            return PermissionManagerService.this.updatePermissions(packageName, pkgInfo, flags);
        }
        @Override
        public int updatePermissionTrees(String packageName,
                PackageParser.Package pkgInfo, int flags) {
            return PermissionManagerService.this.updatePermissionTrees(packageName, pkgInfo, flags);
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
        public void putPermissionTEMP(String permName, BasePermission permission) {
            synchronized (PermissionManagerService.this.mLock) {
                mSettings.putPermissionLocked(permName, (BasePermission) permission);
            }
        }
        @Override
        public Iterator<BasePermission> getPermissionIteratorTEMP() {
            synchronized (PermissionManagerService.this.mLock) {
                return mSettings.getAllPermissionsLocked().iterator();
            }
        }
    }
}
