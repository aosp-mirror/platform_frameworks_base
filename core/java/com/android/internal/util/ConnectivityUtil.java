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

package com.android.internal.util;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;


/**
 * Utility methods for common functionality using by different networks.
 *
 * @hide
 */
public class ConnectivityUtil {

    private static final String TAG = "ConnectivityUtil";

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final UserManager mUserManager;

    public ConnectivityUtil(Context context) {
        mContext = context;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    }

    /**
     * API to determine if the caller has fine/coarse location permission (depending on
     * config/targetSDK level) and the location mode is enabled for the user. SecurityException is
     * thrown if the caller has no permission or the location mode is disabled.
     * @param pkgName package name of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param message A message describing why the permission was checked. Only needed if this is
     *                not inside of a two-way binder call from the data receiver
     */
    public void enforceLocationPermission(String pkgName, @Nullable String featureId, int uid,
            @Nullable String message)
            throws SecurityException {
        checkPackage(uid, pkgName);

        // Location mode must be enabled
        if (!isLocationModeEnabled()) {
            // Location mode is disabled, scan results cannot be returned
            throw new SecurityException("Location mode is disabled for the device");
        }

        // LocationAccess by App: caller must have Coarse/Fine Location permission to have access to
        // location information.
        boolean canAppPackageUseLocation = checkCallersLocationPermission(pkgName, featureId,
                uid, /* coarseForTargetSdkLessThanQ */ true, message);

        // If neither caller or app has location access, there is no need to check
        // any other permissions. Deny access to scan results.
        if (!canAppPackageUseLocation) {
            throw new SecurityException("UID " + uid + " has no location permission");
        }
        // If the User or profile is current, permission is granted
        // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission.
        if (!isCurrentProfile(uid) && !checkInteractAcrossUsersFull(uid)) {
            throw new SecurityException("UID " + uid + " profile not permitted");
        }
    }

    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_FINE_LOCATION or
     * android.Manifest.permission.ACCESS_COARSE_LOCATION (depending on config/targetSDK level)
     * and a corresponding app op is allowed for this package and uid.
     *
     * @param pkgName PackageName of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param coarseForTargetSdkLessThanQ If true and the targetSDK < Q then will check for COARSE
     *                                    else (false or targetSDK >= Q) then will check for FINE
     * @param message A message describing why the permission was checked. Only needed if this is
     *                not inside of a two-way binder call from the data receiver
     */
    public boolean checkCallersLocationPermission(String pkgName, @Nullable String featureId,
            int uid, boolean coarseForTargetSdkLessThanQ, @Nullable String message) {
        boolean isTargetSdkLessThanQ = isTargetSdkLessThan(pkgName, Build.VERSION_CODES.Q, uid);

        String permissionType = Manifest.permission.ACCESS_FINE_LOCATION;
        if (coarseForTargetSdkLessThanQ && isTargetSdkLessThanQ) {
            // Having FINE permission implies having COARSE permission (but not the reverse)
            permissionType = Manifest.permission.ACCESS_COARSE_LOCATION;
        }
        if (getUidPermission(permissionType, uid)
                == PackageManager.PERMISSION_DENIED) {
            return false;
        }

        // Always checking FINE - even if will not enforce. This will record the request for FINE
        // so that a location request by the app is surfaced to the user.
        boolean isFineLocationAllowed = noteAppOpAllowed(
                AppOpsManager.OPSTR_FINE_LOCATION, pkgName, featureId, uid, message);
        if (isFineLocationAllowed) {
            return true;
        }
        if (coarseForTargetSdkLessThanQ && isTargetSdkLessThanQ) {
            return noteAppOpAllowed(AppOpsManager.OPSTR_COARSE_LOCATION, pkgName, featureId, uid,
                    message);
        }
        return false;
    }

    /**
     * Retrieves a handle to LocationManager (if not already done) and check if location is enabled.
     */
    public boolean isLocationModeEnabled() {
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        try {
            return locationManager.isLocationEnabledForUser(UserHandle.of(
                    getCurrentUser()));
        } catch (Exception e) {
            Log.e(TAG, "Failure to get location mode via API, falling back to settings", e);
            return false;
        }
    }

    private boolean isTargetSdkLessThan(String packageName, int versionCode, int callingUid) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (mContext.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0,
                    UserHandle.getUserHandleForUid(callingUid)).targetSdkVersion
                    < versionCode) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume unknown app (more strict checking)
            // Note: This case will never happen since checkPackage is
            // called to verify validity before checking App's version.
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return false;
    }

    private boolean noteAppOpAllowed(String op, String pkgName, @Nullable String featureId,
            int uid, @Nullable String message) {
        return mAppOps.noteOp(op, uid, pkgName) == AppOpsManager.MODE_ALLOWED;
    }

    private void checkPackage(int uid, String pkgName) throws SecurityException {
        if (pkgName == null) {
            throw new SecurityException("Checking UID " + uid + " but Package Name is Null");
        }
        mAppOps.checkPackage(uid, pkgName);
    }

    private boolean isCurrentProfile(int uid) {
        UserHandle currentUser = UserHandle.of(getCurrentUser());
        UserHandle callingUser = UserHandle.getUserHandleForUid(uid);
        return currentUser.equals(callingUser)
                || mUserManager.isSameProfileGroup(
                        currentUser.getIdentifier(), callingUser.getIdentifier());
    }

    private boolean checkInteractAcrossUsersFull(int uid) {
        return getUidPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    @VisibleForTesting
    protected int getCurrentUser() {
        return ActivityManager.getCurrentUser();
    }

    private int getUidPermission(String permissionType, int uid) {
        // We don't care about pid, pass in -1
        return mContext.checkPermission(permissionType, -1, uid);
    }
}
