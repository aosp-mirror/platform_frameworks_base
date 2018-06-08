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
 * limitations under the License
 */

package android.telephony;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.util.List;

/**
 * Helper for performing location access checks.
 * @hide
 */
public final class LocationAccessPolicy {
    private static final String LOG_TAG = LocationAccessPolicy.class.getSimpleName();

    /**
     * API to determine if the caller has permissions to get cell location.
     *
     * @param pkgName Package name of the application requesting access
     * @param uid The uid of the package
     * @param pid The pid of the package
     * @param throwOnDeniedPermission Whether to throw if the location permission is denied.
     * @return boolean true or false if permissions is granted
     */
    public static boolean canAccessCellLocation(@NonNull Context context, @NonNull String pkgName,
            int uid, int pid, boolean throwOnDeniedPermission) throws SecurityException {
        Trace.beginSection("TelephonyLohcationCheck");
        try {
            // Always allow the phone process to access location. This avoid breaking legacy code
            // that rely on public-facing APIs to access cell location, and it doesn't create a
            // info leak risk because the cell location is stored in the phone process anyway.
            if (uid == Process.PHONE_UID) {
                return true;
            }

            // We always require the location permission and also require the
            // location mode to be on for non-legacy apps. Legacy apps are
            // required to be in the foreground to at least mitigate the case
            // where a legacy app the user is not using tracks their location.
            // Granting ACCESS_FINE_LOCATION to an app automatically grants it
            // ACCESS_COARSE_LOCATION.
            if (throwOnDeniedPermission) {
                context.enforcePermission(Manifest.permission.ACCESS_COARSE_LOCATION,
                        pid, uid, "canAccessCellLocation");
            } else if (context.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION,
                    pid, uid) == PackageManager.PERMISSION_DENIED) {
                return false;
            }
            final int opCode = AppOpsManager.permissionToOpCode(
                    Manifest.permission.ACCESS_COARSE_LOCATION);
            if (opCode != AppOpsManager.OP_NONE && context.getSystemService(AppOpsManager.class)
                    .noteOpNoThrow(opCode, uid, pkgName) != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            if (!isLocationModeEnabled(context, UserHandle.getUserId(uid))) {
                return false;
            }
            // If the user or profile is current, permission is granted.
            // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission.
            return isCurrentProfile(context, uid) || checkInteractAcrossUsersFull(context);
        } finally {
            Trace.endSection();
        }
    }

    private static boolean isLocationModeEnabled(@NonNull Context context, @UserIdInt int userId) {
        LocationManager locationManager = context.getSystemService(LocationManager.class);
        if (locationManager == null) {
            Log.w(LOG_TAG, "Couldn't get location manager, denying location access");
            return false;
        }
        return locationManager.isLocationEnabledForUser(UserHandle.of(userId));
    }

    private static boolean checkInteractAcrossUsersFull(@NonNull Context context) {
        return context.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isCurrentProfile(@NonNull Context context, int uid) {
        long token = Binder.clearCallingIdentity();
        try {
            final int currentUser = ActivityManager.getCurrentUser();
            final int callingUserId = UserHandle.getUserId(uid);
            if (callingUserId == currentUser) {
                return true;
            } else {
                List<UserInfo> userProfiles = context.getSystemService(
                        UserManager.class).getProfiles(currentUser);
                for (UserInfo user : userProfiles) {
                    if (user.id == callingUserId) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
