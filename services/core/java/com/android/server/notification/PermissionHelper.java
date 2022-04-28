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

package com.android.server.notification;

import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.RemoteException;
import android.permission.IPermissionManager;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * NotificationManagerService helper for querying/setting the app-level notification permission
 */
public final class PermissionHelper {
    private static final String TAG = "PermissionHelper";

    private static final String NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS;

    private final PermissionManagerServiceInternal mPmi;
    private final IPackageManager mPackageManager;
    private final IPermissionManager mPermManager;
    // TODO (b/194833441): Remove this boolean (but keep the isMigrationEnabled() method)
    //  when the migration is enabled
    private final boolean mMigrationEnabled;
    private final boolean mIsTv;
    private final boolean mForceUserSetOnUpgrade;

    public PermissionHelper(PermissionManagerServiceInternal pmi, IPackageManager packageManager,
            IPermissionManager permManager, boolean migrationEnabled,
            boolean forceUserSetOnUpgrade) {
        mPmi = pmi;
        mPackageManager = packageManager;
        mPermManager = permManager;
        mMigrationEnabled = migrationEnabled;
        mForceUserSetOnUpgrade = forceUserSetOnUpgrade;
        boolean isTv;
        try {
            isTv = mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK, 0);
        } catch (RemoteException e) {
            isTv = false;
        }
        mIsTv = isTv;
    }

    public boolean isMigrationEnabled() {
        return mMigrationEnabled && !mIsTv;
    }

    /**
     * Returns whether the given uid holds the notification permission. Must not be called
     * with a lock held.
     */
    public boolean hasPermission(int uid) {
        assertFlag();
        final long callingId = Binder.clearCallingIdentity();
        try {
            return mPmi.checkPostNotificationsPermissionGrantedOrLegacyAccess(uid)
                    == PERMISSION_GRANTED;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Returns all of the apps that have requested the notification permission in a given user.
     * Must not be called with a lock held. Format: uid, packageName
     */
    Set<Pair<Integer, String>> getAppsRequestingPermission(int userId) {
        assertFlag();
        Set<Pair<Integer, String>> requested = new HashSet<>();
        List<PackageInfo> pkgs = getInstalledPackages(userId);
        for (PackageInfo pi : pkgs) {
            // when data was stored in PreferencesHelper, we only had data for apps that
            // had ever registered an intent to send a notification. To match that behavior,
            // filter the app list to apps that have requested the notification permission.
            if (pi.requestedPermissions == null) {
                continue;
            }
            for (String perm : pi.requestedPermissions) {
                if (NOTIFICATION_PERMISSION.equals(perm)) {
                    requested.add(new Pair<>(pi.applicationInfo.uid, pi.packageName));
                    break;
                }
            }
        }
        return requested;
    }

    private List<PackageInfo> getInstalledPackages(int userId) {
        ParceledListSlice<PackageInfo> parceledList = null;
        try {
            parceledList = mPackageManager.getInstalledPackages(GET_PERMISSIONS, userId);
        } catch (RemoteException e) {
            Slog.d(TAG, "Could not reach system server", e);
        }
        if (parceledList == null) {
            return Collections.emptyList();
        }
        return parceledList.getList();
    }

    /**
     * Returns a list of apps that hold the notification permission. Must not be called
     * with a lock held. Format: uid, packageName.
     */
    Set<Pair<Integer, String>> getAppsGrantedPermission(int userId) {
        assertFlag();
        Set<Pair<Integer, String>> granted = new HashSet<>();
        ParceledListSlice<PackageInfo> parceledList = null;
        try {
            parceledList = mPackageManager.getPackagesHoldingPermissions(
                    new String[] {NOTIFICATION_PERMISSION}, 0, userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not reach system server", e);
        }
        if (parceledList == null) {
            return granted;
        }
        for (PackageInfo pi : parceledList.getList()) {
            granted.add(new Pair<>(pi.applicationInfo.uid, pi.packageName));
        }
        return granted;
    }

    // Key: (uid, package name); Value: (granted, user set)
    public @NonNull
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>>
                    getNotificationPermissionValues(int userId) {
        assertFlag();
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> notifPermissions = new ArrayMap<>();
        Set<Pair<Integer, String>> allRequestingUids = getAppsRequestingPermission(userId);
        Set<Pair<Integer, String>> allApprovedUids = getAppsGrantedPermission(userId);
        for (Pair<Integer, String> pair : allRequestingUids) {
            notifPermissions.put(pair, new Pair(allApprovedUids.contains(pair),
                    isPermissionUserSet(pair.second /* package name */, userId)));
        }
        return notifPermissions;
    }

    /**
     * @see setNotificationPermission(String, int, boolean, boolean, boolean)
     */
    public void setNotificationPermission(String packageName, @UserIdInt int userId, boolean grant,
            boolean userSet) {
        setNotificationPermission(packageName, userId, grant, userSet, false);
    }

    /**
     * Grants or revokes the notification permission for a given package/user. UserSet should
     * only be true if this method is being called to migrate existing user choice, because it
     * can prevent the user from seeing the in app permission dialog. Must not be called
     * with a lock held.
     */
    public void setNotificationPermission(String packageName, @UserIdInt int userId, boolean grant,
            boolean userSet, boolean reviewRequired) {
        assertFlag();
        final long callingId = Binder.clearCallingIdentity();
        try {
            // Do not change the permission if the package doesn't request it, do not change fixed
            // permissions, and do not change non-user set permissions that are granted by default,
            // or granted by role.
            if (!packageRequestsNotificationPermission(packageName, userId)
                    || isPermissionFixed(packageName, userId)
                    || (isPermissionGrantedByDefaultOrRole(packageName, userId) && !userSet)) {
                return;
            }

            boolean currentlyGranted = mPmi.checkPermission(packageName, NOTIFICATION_PERMISSION,
                    userId) != PackageManager.PERMISSION_DENIED;
            if (grant && !reviewRequired && !currentlyGranted) {
                mPermManager.grantRuntimePermission(packageName, NOTIFICATION_PERMISSION, userId);
            } else if (!grant && currentlyGranted) {
                mPermManager.revokeRuntimePermission(packageName, NOTIFICATION_PERMISSION,
                        userId, TAG);
            }
            if (userSet) {
                mPermManager.updatePermissionFlags(packageName, NOTIFICATION_PERMISSION,
                        FLAG_PERMISSION_USER_SET | FLAG_PERMISSION_REVIEW_REQUIRED,
                        FLAG_PERMISSION_USER_SET, true, userId);
            } else if (reviewRequired) {
                mPermManager.updatePermissionFlags(packageName, NOTIFICATION_PERMISSION,
                        FLAG_PERMISSION_REVIEW_REQUIRED, FLAG_PERMISSION_REVIEW_REQUIRED, true,
                        userId);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not reach system server", e);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Set the notification permission state upon phone version upgrade from S- to T+, or upon
     * restoring a pre-T backup on a T+ device
     */
    public void setNotificationPermission(PackagePermission pkgPerm) {
        assertFlag();
        if (pkgPerm == null || pkgPerm.packageName == null) {
            return;
        }
        if (!isPermissionFixed(pkgPerm.packageName, pkgPerm.userId)) {
            boolean userSet = mForceUserSetOnUpgrade ? true : pkgPerm.userModifiedSettings;
            setNotificationPermission(pkgPerm.packageName, pkgPerm.userId, pkgPerm.granted,
                    userSet, !userSet);
        }
    }

    public boolean isPermissionFixed(String packageName, @UserIdInt int userId) {
        assertFlag();
        final long callingId = Binder.clearCallingIdentity();
        try {
            try {
                int flags = mPermManager.getPermissionFlags(packageName, NOTIFICATION_PERMISSION,
                        userId);
                return (flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0
                        || (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0;
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not reach system server", e);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    boolean isPermissionUserSet(String packageName, @UserIdInt int userId) {
        assertFlag();
        final long callingId = Binder.clearCallingIdentity();
        try {
            try {
                int flags = mPermManager.getPermissionFlags(packageName, NOTIFICATION_PERMISSION,
                        userId);
                return (flags & (PackageManager.FLAG_PERMISSION_USER_SET
                        | PackageManager.FLAG_PERMISSION_USER_FIXED)) != 0;
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not reach system server", e);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    boolean isPermissionGrantedByDefaultOrRole(String packageName, @UserIdInt int userId) {
        assertFlag();
        final long callingId = Binder.clearCallingIdentity();
        try {
            try {
                int flags = mPermManager.getPermissionFlags(packageName, NOTIFICATION_PERMISSION,
                        userId);
                return (flags & (PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT
                        | PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE)) != 0;
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not reach system server", e);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private boolean packageRequestsNotificationPermission(String packageName,
            @UserIdInt int userId) {
        assertFlag();
        try {
            String[] permissions = mPackageManager.getPackageInfo(packageName, GET_PERMISSIONS,
                    userId).requestedPermissions;
            return ArrayUtils.contains(permissions, NOTIFICATION_PERMISSION);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not reach system server", e);
        }
        return false;
    }

    private void assertFlag() {
        if (!mMigrationEnabled) {
            throw new IllegalStateException("Method called without checking flag value");
        }
    }

    public static class PackagePermission {
        public final String packageName;
        public final @UserIdInt int userId;
        public final boolean granted;
        public final boolean userModifiedSettings;

        public PackagePermission(String pkg, int userId, boolean granted, boolean userSet) {
            this.packageName = pkg;
            this.userId = userId;
            this.granted = granted;
            this.userModifiedSettings = userSet;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PackagePermission that = (PackagePermission) o;
            return userId == that.userId && granted == that.granted && userModifiedSettings
                    == that.userModifiedSettings
                    && Objects.equals(packageName, that.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, userId, granted, userModifiedSettings);
        }

        @Override
        public String toString() {
            return "PackagePermission{" +
                    "packageName='" + packageName + '\'' +
                    ", userId=" + userId +
                    ", granted=" + granted +
                    ", userSet=" + userModifiedSettings +
                    '}';
        }
    }
}
