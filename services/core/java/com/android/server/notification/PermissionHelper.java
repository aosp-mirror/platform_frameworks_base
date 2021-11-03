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

import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.permission.PermissionManager.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.UserIdInt;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.RemoteException;
import android.os.UserHandle;
import android.permission.IPermissionManager;
import android.util.Slog;

import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NotificationManagerService helper for querying/setting the app-level notification permission
 */
public final class PermissionHelper {
    private static final String TAG = "PermissionHelper";

    private static String NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS;

    private final PermissionManagerServiceInternal mPmi;
    private final IPackageManager mPackageManager;
    private final IPermissionManager mPermManager;
    // TODO (b/194833441): Remove when the migration is enabled
    private final boolean mMigrationEnabled;

    public PermissionHelper(PermissionManagerServiceInternal pmi, IPackageManager packageManager,
            IPermissionManager permManager, boolean migrationEnabled) {
        mPmi = pmi;
        mPackageManager = packageManager;
        mPermManager = permManager;
        mMigrationEnabled = migrationEnabled;
    }

    public boolean isMigrationEnabled() {
        return mMigrationEnabled;
    }

    /**
     * Returns whether the given uid holds the notification permission. Must not be called
     * with a lock held.
     */
    public boolean hasPermission(int uid) {
        assertFlag();
        return mPmi.checkUidPermission(uid, NOTIFICATION_PERMISSION) == PERMISSION_GRANTED;
    }

    /**
     * Returns all of the apps that have requested the notification permission in a given user.
     * Must not be called with a lock held. Format: uid, packageName
     */
    public Map<Integer, String> getAppsRequestingPermission(int userId) {
        assertFlag();
        Map<Integer, String> requested = new HashMap<>();
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
                    requested.put(pi.applicationInfo.uid, pi.packageName);
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
    public Map<Integer, String> getAppsGrantedPermission(int userId) {
        assertFlag();
        Map<Integer, String> granted = new HashMap<>();
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
            granted.put(pi.applicationInfo.uid, pi.packageName);
        }
        return granted;
    }

    /**
     * Grants or revokes the notification permission for a given package/user. UserSet should
     * only be true if this method is being called to migrate existing user choice, because it
     * can prevent the user from seeing the in app permission dialog. Must not be called
     * with a lock held.
     */
    public void setNotificationPermission(String packageName, @UserIdInt int userId, boolean grant,
            boolean userSet) {
        assertFlag();
        try {
            if (grant) {
                mPermManager.grantRuntimePermission(packageName, NOTIFICATION_PERMISSION, userId);
            } else {
                mPermManager.revokeRuntimePermission(packageName, NOTIFICATION_PERMISSION, userId,
                        TAG);
            }
            if (userSet) {
                mPermManager.updatePermissionFlags(packageName, NOTIFICATION_PERMISSION,
                        FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET, true, userId);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not reach system server", e);
        }
    }

    public boolean isPermissionFixed(String packageName, @UserIdInt int userId) {
        assertFlag();
        try {
            int flags = mPermManager.getPermissionFlags(packageName, NOTIFICATION_PERMISSION,
                    userId);
            return (flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0
                    || (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0;
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
}
