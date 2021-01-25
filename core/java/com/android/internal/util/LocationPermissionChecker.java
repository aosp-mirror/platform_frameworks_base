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
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.NetworkStack;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * The methods used for location permission and location mode checking.
 *
 * @hide
 */
public class LocationPermissionChecker {

    private static final String TAG = "LocationPermissionChecker";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LOCATION_PERMISSION_CHECK_STATUS_"}, value = {
        SUCCEEDED,
        ERROR_LOCATION_MODE_OFF,
        ERROR_LOCATION_PERMISSION_MISSING,
    })
    public @interface LocationPermissionCheckStatus{}

    // The location permission check succeeded.
    public static final int SUCCEEDED = 0;
    // The location mode turns off for the caller.
    public static final int ERROR_LOCATION_MODE_OFF = 1;
    // The location permission isn't granted for the caller.
    public static final int ERROR_LOCATION_PERMISSION_MISSING = 2;

    private final Context mContext;
    private final AppOpsManager mAppOpsManager;

    public LocationPermissionChecker(Context context) {
        mContext = context;
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
    }

    /**
     * Check location permission granted by the caller.
     *
     * This API check if the location mode enabled for the caller and the caller has
     * ACCESS_COARSE_LOCATION permission is targetSDK<29, otherwise, has ACCESS_FINE_LOCATION.
     *
     * @param pkgName package name of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param message A message describing why the permission was checked. Only needed if this is
     *                not inside of a two-way binder call from the data receiver
     *
     * @return {@code true} returns if the caller has location permission and the location mode is
     *         enabled.
     */
    public boolean checkLocationPermission(String pkgName, @Nullable String featureId,
            int uid, @Nullable String message) {
        return checkLocationPermissionInternal(pkgName, featureId, uid, message) == SUCCEEDED;
    }

    /**
     * Check location permission granted by the caller.
     *
     * This API check if the location mode enabled for the caller and the caller has
     * ACCESS_COARSE_LOCATION permission is targetSDK<29, otherwise, has ACCESS_FINE_LOCATION.
     * Compared with {@link #checkLocationPermission(String, String, int, String)}, this API returns
     * the detail information about the checking result, including the reason why it's failed and
     * logs the error for the caller.
     *
     * @param pkgName package name of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param message A message describing why the permission was checked. Only needed if this is
     *                not inside of a two-way binder call from the data receiver
     *
     * @return {@link LocationPermissionCheckStatus} the result of the location permission check.
     */
    public @LocationPermissionCheckStatus int checkLocationPermissionWithDetailInfo(
            String pkgName, @Nullable String featureId, int uid, @Nullable String message) {
        final int result = checkLocationPermissionInternal(pkgName, featureId, uid, message);
        switch (result) {
            case ERROR_LOCATION_MODE_OFF:
                Log.e(TAG, "Location mode is disabled for the device");
                break;
            case ERROR_LOCATION_PERMISSION_MISSING:
                Log.e(TAG, "UID " + uid + " has no location permission");
                break;
        }
        return result;
    }

    /**
     * Enforce the caller has location permission.
     *
     * This API determines if the location mode enabled for the caller and the caller has
     * ACCESS_COARSE_LOCATION permission is targetSDK<29, otherwise, has ACCESS_FINE_LOCATION.
     * SecurityException is thrown if the caller has no permission or the location mode is disabled.
     *
     * @param pkgName package name of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param message A message describing why the permission was checked. Only needed if this is
     *                not inside of a two-way binder call from the data receiver
     */
    public void enforceLocationPermission(String pkgName, @Nullable String featureId, int uid,
            @Nullable String message) throws SecurityException {
        final int result = checkLocationPermissionInternal(pkgName, featureId, uid, message);

        switch (result) {
            case ERROR_LOCATION_MODE_OFF:
                throw new SecurityException("Location mode is disabled for the device");
            case ERROR_LOCATION_PERMISSION_MISSING:
                throw new SecurityException("UID " + uid + " has no location permission");
        }
    }

    private int checkLocationPermissionInternal(String pkgName, @Nullable String featureId,
            int uid, @Nullable String message) {
        checkPackage(uid, pkgName);

        // Apps with NETWORK_SETTINGS, NETWORK_SETUP_WIZARD, NETWORK_STACK & MAINLINE_NETWORK_STACK
        // are granted a bypass.
        if (checkNetworkSettingsPermission(uid) || checkNetworkSetupWizardPermission(uid)
                || checkNetworkStackPermission(uid) || checkMainlineNetworkStackPermission(uid)) {
            return SUCCEEDED;
        }

        // Location mode must be enabled
        if (!isLocationModeEnabled()) {
            return ERROR_LOCATION_MODE_OFF;
        }

        // LocationAccess by App: caller must have Coarse/Fine Location permission to have access to
        // location information.
        if (!checkCallersLocationPermission(pkgName, featureId, uid,
                true /* coarseForTargetSdkLessThanQ */, message)) {
            return ERROR_LOCATION_PERMISSION_MISSING;
        }
        return SUCCEEDED;
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
        if (getUidPermission(permissionType, uid) == PackageManager.PERMISSION_DENIED) {
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
        final LocationManager LocationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        try {
            return LocationManager.isLocationEnabledForUser(UserHandle.of(
                    getCurrentUser()));
        } catch (Exception e) {
            Log.e(TAG, "Failure to get location mode via API, falling back to settings", e);
            return false;
        }
    }

    private boolean isTargetSdkLessThan(String packageName, int versionCode, int callingUid) {
        final long ident = Binder.clearCallingIdentity();
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
        return mAppOpsManager.noteOp(op, uid, pkgName, featureId, message)
                == AppOpsManager.MODE_ALLOWED;
    }

    private void checkPackage(int uid, String pkgName)
            throws SecurityException {
        if (pkgName == null) {
            throw new SecurityException("Checking UID " + uid + " but Package Name is Null");
        }
        mAppOpsManager.checkPackage(uid, pkgName);
    }

    @VisibleForTesting
    protected int getCurrentUser() {
        return ActivityManager.getCurrentUser();
    }

    private int getUidPermission(String permissionType, int uid) {
        // We don't care about pid, pass in -1
        return mContext.checkPermission(permissionType, -1, uid);
    }

    /**
     * Returns true if the |uid| holds NETWORK_SETTINGS permission.
     */
    public boolean checkNetworkSettingsPermission(int uid) {
        return getUidPermission(android.Manifest.permission.NETWORK_SETTINGS, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds NETWORK_SETUP_WIZARD permission.
     */
    public boolean checkNetworkSetupWizardPermission(int uid) {
        return getUidPermission(android.Manifest.permission.NETWORK_SETUP_WIZARD, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds NETWORK_STACK permission.
     */
    public boolean checkNetworkStackPermission(int uid) {
        return getUidPermission(android.Manifest.permission.NETWORK_STACK, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds MAINLINE_NETWORK_STACK permission.
     */
    public boolean checkMainlineNetworkStackPermission(int uid) {
        return getUidPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

}
