/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.net;

import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_REMOVED;
import static android.net.TrafficStats.UID_TETHERING;

import android.Manifest;
import android.annotation.IntDef;
import android.app.AppOpsManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.telephony.TelephonyManager;

import com.android.server.LocalServices;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Utility methods for controlling access to network stats APIs. */
public final class NetworkStatsAccess {
    private NetworkStatsAccess() {}

    /**
     * Represents an access level for the network usage history and statistics APIs.
     *
     * <p>Access levels are in increasing order; that is, it is reasonable to check access by
     * verifying that the caller's access level is at least the minimum required level.
     */
    @IntDef({
            Level.DEFAULT,
            Level.USER,
            Level.DEVICESUMMARY,
            Level.DEVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Level {
        /**
         * Default, unprivileged access level.
         *
         * <p>Can only access usage for one's own UID.
         *
         * <p>Every app will have at least this access level.
         */
        int DEFAULT = 0;

        /**
         * Access level for apps which can access usage for any app running in the same user.
         *
         * <p>Granted to:
         * <ul>
         * <li>Profile owners.
         * </ul>
         */
        int USER = 1;

        /**
         * Access level for apps which can access usage summary of device. Device summary includes
         * usage by apps running in any profiles/users, however this access level does not
         * allow querying usage of individual apps running in other profiles/users.
         *
         * <p>Granted to:
         * <ul>
         * <li>Apps with the PACKAGE_USAGE_STATS permission granted. Note that this is an AppOps bit
         * so it is not necessarily sufficient to declare this in the manifest.
         * <li>Apps with the (signature/privileged) READ_NETWORK_USAGE_HISTORY permission.
         * </ul>
         */
        int DEVICESUMMARY = 2;

        /**
         * Access level for apps which can access usage for any app on the device, including apps
         * running on other users/profiles.
         *
         * <p>Granted to:
         * <ul>
         * <li>Device owners.
         * <li>Carrier-privileged applications.
         * <li>The system UID.
         * </ul>
         */
        int DEVICE = 3;
    }

    /** Returns the {@link NetworkStatsAccess.Level} for the given caller. */
    public static @NetworkStatsAccess.Level int checkAccessLevel(
            Context context, int callingUid, String callingPackage) {
        final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                DevicePolicyManagerInternal.class);
        final TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        boolean hasCarrierPrivileges = tm != null &&
                tm.checkCarrierPrivilegesForPackage(callingPackage) ==
                        TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
        boolean isDeviceOwner = dpmi != null && dpmi.isActiveAdminWithPolicy(callingUid,
                DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        if (hasCarrierPrivileges || isDeviceOwner
                || UserHandle.getAppId(callingUid) == android.os.Process.SYSTEM_UID) {
            // Carrier-privileged apps and device owners, and the system can access data usage for
            // all apps on the device.
            return NetworkStatsAccess.Level.DEVICE;
        }

        boolean hasAppOpsPermission = hasAppOpsPermission(context, callingUid, callingPackage);
        if (hasAppOpsPermission || context.checkCallingOrSelfPermission(
                READ_NETWORK_USAGE_HISTORY) == PackageManager.PERMISSION_GRANTED) {
            return NetworkStatsAccess.Level.DEVICESUMMARY;
        }

        boolean isProfileOwner = dpmi != null && dpmi.isActiveAdminWithPolicy(callingUid,
                DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        if (isProfileOwner) {
            // Apps with the AppOps permission, profile owners, and apps with the privileged
            // permission can access data usage for all apps in this user/profile.
            return NetworkStatsAccess.Level.USER;
        }

        // Everyone else gets default access (only to their own UID).
        return NetworkStatsAccess.Level.DEFAULT;
    }

    /**
     * Returns whether the given caller should be able to access the given UID when the caller has
     * the given {@link NetworkStatsAccess.Level}.
     */
    public static boolean isAccessibleToUser(int uid, int callerUid,
            @NetworkStatsAccess.Level int accessLevel) {
        switch (accessLevel) {
            case NetworkStatsAccess.Level.DEVICE:
                // Device-level access - can access usage for any uid.
                return true;
            case NetworkStatsAccess.Level.DEVICESUMMARY:
                // Can access usage for any app running in the same user, along
                // with some special uids (system, removed, or tethering) and
                // anonymized uids
                return uid == android.os.Process.SYSTEM_UID || uid == UID_REMOVED
                        || uid == UID_TETHERING || uid == UID_ALL
                        || UserHandle.getUserId(uid) == UserHandle.getUserId(callerUid);
            case NetworkStatsAccess.Level.USER:
                // User-level access - can access usage for any app running in the same user, along
                // with some special uids (system, removed, or tethering).
                return uid == android.os.Process.SYSTEM_UID || uid == UID_REMOVED
                        || uid == UID_TETHERING
                        || UserHandle.getUserId(uid) == UserHandle.getUserId(callerUid);
            case NetworkStatsAccess.Level.DEFAULT:
            default:
                // Default access level - can only access one's own usage.
                return uid == callerUid;
        }
    }

    private static boolean hasAppOpsPermission(
            Context context, int callingUid, String callingPackage) {
        if (callingPackage != null) {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(
                    Context.APP_OPS_SERVICE);

            final int mode = appOps.noteOp(AppOpsManager.OP_GET_USAGE_STATS,
                    callingUid, callingPackage);
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // The default behavior here is to check if PackageManager has given the app
                // permission.
                final int permissionCheck = context.checkCallingPermission(
                        Manifest.permission.PACKAGE_USAGE_STATS);
                return permissionCheck == PackageManager.PERMISSION_GRANTED;
            }
            return (mode == AppOpsManager.MODE_ALLOWED);
        }
        return false;
    }
}
