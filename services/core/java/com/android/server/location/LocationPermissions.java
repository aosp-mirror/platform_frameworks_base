/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.IntDef;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Utility class for dealing with location permissions. */
public final class LocationPermissions {

    /**
     * Indicates no location permissions are present, or no location permission are required.
     */
    public static final int PERMISSION_NONE = 0;

    /**
     * Indicates the coarse location permission is present, or either the coarse or fine permissions
     * are required.
     */
    public static final int PERMISSION_COARSE = 1;

    /**
     * Indicates the fine location permission is present, or the fine location permission is
     * required.
     */
    public static final int PERMISSION_FINE = 2;

    @IntDef({PERMISSION_NONE, PERMISSION_COARSE, PERMISSION_FINE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionLevel {}

    /**
     * Converts the given permission level to the corresponding permission.
     */
    public static String asPermission(@PermissionLevel int permissionLevel) {
        switch (permissionLevel) {
            case PERMISSION_COARSE:
                return ACCESS_COARSE_LOCATION;
            case PERMISSION_FINE:
                return ACCESS_FINE_LOCATION;
            default:
                throw new IllegalArgumentException();
        }
    }

    /**
     * Converts the given permission level to the corresponding appop.
     */
    public static int asAppOp(@PermissionLevel int permissionLevel) {
        switch (permissionLevel) {
            case PERMISSION_COARSE:
                return AppOpsManager.OP_COARSE_LOCATION;
            case PERMISSION_FINE:
                return AppOpsManager.OP_FINE_LOCATION;
            default:
                throw new IllegalArgumentException();
        }
    }

    /**
     * Throws a security exception if the caller does not hold the required location permissions.
     */
    public static void enforceCallingOrSelfLocationPermission(Context context,
            @PermissionLevel int requiredPermissionLevel) {
        enforceLocationPermission(Binder.getCallingUid(),
                getPermissionLevel(context, Binder.getCallingUid(), Binder.getCallingPid()),
                requiredPermissionLevel);
    }

    /**
     * Throws a security exception if the given uid/pid does not hold the required location
     * permissions.
     */
    public static void enforceLocationPermission(Context context, int uid, int pid,
            @PermissionLevel int requiredPermissionLevel) {
        enforceLocationPermission(uid,
                getPermissionLevel(context, uid, pid),
                requiredPermissionLevel);
    }

    /**
     * Throws a security exception if the given permission level does not meet the required location
     * permission level.
     */
    public static void enforceLocationPermission(int uid, @PermissionLevel int permissionLevel,
            @PermissionLevel int requiredPermissionLevel) {
        if (checkLocationPermission(permissionLevel, requiredPermissionLevel)) {
            return;
        }

        if (requiredPermissionLevel == PERMISSION_COARSE) {
            throw new SecurityException("uid " + uid + " does not have " + ACCESS_COARSE_LOCATION
                    + " or " + ACCESS_FINE_LOCATION + ".");
        } else if (requiredPermissionLevel == PERMISSION_FINE) {
            throw new SecurityException("uid " + uid + " does not have " + ACCESS_FINE_LOCATION
                    + ".");
        }
    }

    /**
     * Returns false if the caller does not hold the required location permissions.
     */
    public static boolean checkCallingOrSelfLocationPermission(Context context,
            @PermissionLevel int requiredPermissionLevel) {
        return checkLocationPermission(
                getCallingOrSelfPermissionLevel(context),
                requiredPermissionLevel);
    }

    /**
     * Returns false if the given uid/pid does not hold the required location permissions.
     */
    public static boolean checkLocationPermission(Context context, int uid, int pid,
            @PermissionLevel int requiredPermissionLevel) {
        return checkLocationPermission(
                getPermissionLevel(context, uid, pid),
                requiredPermissionLevel);
    }

    /**
     * Returns false if the given permission level does not meet the required location permission
     * level.
     */
    public static boolean checkLocationPermission(@PermissionLevel int permissionLevel,
            @PermissionLevel int requiredPermissionLevel) {
        return permissionLevel >= requiredPermissionLevel;
    }

    /**
     * Returns the permission level of the caller.
     */
    @PermissionLevel
    public static int getCallingOrSelfPermissionLevel(Context context) {
        return getPermissionLevel(context, Binder.getCallingUid(), Binder.getCallingPid());
    }

    /**
     * Returns the permission level of the given uid/pid.
     */
    @PermissionLevel
    public static int getPermissionLevel(Context context, int uid, int pid) {
        if (context.checkPermission(ACCESS_FINE_LOCATION, pid, uid) == PERMISSION_GRANTED) {
            return PERMISSION_FINE;
        }
        if (context.checkPermission(ACCESS_COARSE_LOCATION, pid, uid) == PERMISSION_GRANTED) {
            return PERMISSION_COARSE;
        }

        return PERMISSION_NONE;
    }

    private LocationPermissions() {}
}
