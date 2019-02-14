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
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * Helper for performing location access checks.
 * @hide
 */
public final class LocationAccessPolicy {
    private static final String TAG = "LocationAccessPolicy";
    private static final boolean DBG = false;
    public static final int MAX_SDK_FOR_ANY_ENFORCEMENT = Build.VERSION_CODES.P;

    public enum LocationPermissionResult {
        ALLOWED,
        /**
         * Indicates that the denial is due to a transient device state
         * (e.g. app-ops, location master switch)
         */
        DENIED_SOFT,
        /**
         * Indicates that the denial is due to a misconfigured app (e.g. missing entry in manifest)
         */
        DENIED_HARD,
    }

    public static class LocationPermissionQuery {
        public final String callingPackage;
        public final int callingUid;
        public final int callingPid;
        public final int minSdkVersionForCoarse;
        public final int minSdkVersionForFine;
        public final String method;

        private LocationPermissionQuery(String callingPackage, int callingUid, int callingPid,
                int minSdkVersionForCoarse, int minSdkVersionForFine, String method) {
            this.callingPackage = callingPackage;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
            this.minSdkVersionForCoarse = minSdkVersionForCoarse;
            this.minSdkVersionForFine = minSdkVersionForFine;
            this.method = method;
        }

        public static class Builder {
            private String mCallingPackage;
            private int mCallingUid;
            private int mCallingPid;
            private int mMinSdkVersionForCoarse = Integer.MAX_VALUE;
            private int mMinSdkVersionForFine = Integer.MAX_VALUE;
            private String mMethod;

            /**
             * Mandatory parameter, used for performing permission checks.
             */
            public Builder setCallingPackage(String callingPackage) {
                mCallingPackage = callingPackage;
                return this;
            }

            /**
             * Mandatory parameter, used for performing permission checks.
             */
            public Builder setCallingUid(int callingUid) {
                mCallingUid = callingUid;
                return this;
            }

            /**
             * Mandatory parameter, used for performing permission checks.
             */
            public Builder setCallingPid(int callingPid) {
                mCallingPid = callingPid;
                return this;
            }

            /**
             * Apps that target at least this sdk version will be checked for coarse location
             * permission. Defaults to INT_MAX (which means don't check)
             */
            public Builder setMinSdkVersionForCoarse(
                    int minSdkVersionForCoarse) {
                mMinSdkVersionForCoarse = minSdkVersionForCoarse;
                return this;
            }

            /**
             * Apps that target at least this sdk version will be checked for fine location
             * permission. Defaults to INT_MAX (which means don't check)
             */
            public Builder setMinSdkVersionForFine(
                    int minSdkVersionForFine) {
                mMinSdkVersionForFine = minSdkVersionForFine;
                return this;
            }

            /**
             * Optional, for logging purposes only.
             */
            public Builder setMethod(String method) {
                mMethod = method;
                return this;
            }

            public LocationPermissionQuery build() {
                return new LocationPermissionQuery(mCallingPackage, mCallingUid,
                        mCallingPid, mMinSdkVersionForCoarse, mMinSdkVersionForFine, mMethod);
            }
        }
    }

    private static void logError(Context context, String errorMsg) {
        Log.e(TAG, errorMsg);
        try {
            if (Build.IS_DEBUGGABLE) {
                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            // whatever, not important
        }
    }

    private static LocationPermissionResult appOpsModeToPermissionResult(int appOpsMode) {
        switch (appOpsMode) {
            case AppOpsManager.MODE_ALLOWED:
                return LocationPermissionResult.ALLOWED;
            case AppOpsManager.MODE_ERRORED:
                return LocationPermissionResult.DENIED_HARD;
            default:
                return LocationPermissionResult.DENIED_SOFT;
        }
    }

    private static LocationPermissionResult checkAppLocationPermissionHelper(Context context,
            LocationPermissionQuery query, String permissionToCheck) {
        String locationTypeForLog =
                Manifest.permission.ACCESS_FINE_LOCATION.equals(permissionToCheck)
                        ? "fine" : "coarse";

        // Do the app-ops and the manifest check without any of the allow-overrides first.
        boolean hasManifestPermission = checkManifestPermission(context, query.callingPid,
                query.callingUid, permissionToCheck);

        int appOpMode = context.getSystemService(AppOpsManager.class)
                .noteOpNoThrow(AppOpsManager.permissionToOpCode(permissionToCheck),
                        query.callingUid, query.callingPackage);

        if (hasManifestPermission && appOpMode == AppOpsManager.MODE_ALLOWED) {
            // If the app did everything right, return without logging.
            return LocationPermissionResult.ALLOWED;
        }

        // If the app has the manifest permission but not the app-op permission, it means that
        // it's aware of the requirement and the user denied permission explicitly. If we see
        // this, don't let any of the overrides happen.
        if (hasManifestPermission) {
            Log.i(TAG, query.callingPackage + " is aware of " + locationTypeForLog + " but the"
                    + " app-ops permission is specifically denied.");
            return appOpsModeToPermissionResult(appOpMode);
        }

        int minSdkVersion = Manifest.permission.ACCESS_FINE_LOCATION.equals(permissionToCheck)
                ? query.minSdkVersionForFine : query.minSdkVersionForCoarse;

        // If the app fails for some reason, see if it should be allowed to proceed.
        if (minSdkVersion > MAX_SDK_FOR_ANY_ENFORCEMENT) {
            String errorMsg = "Allowing " + query.callingPackage + " " + locationTypeForLog
                    + " because we're not enforcing API " + query.minSdkVersionForFine + " yet."
                    + " Please fix this app because it will break in the future. Called from "
                    + query.method;
            logError(context, errorMsg);
            return null;
        } else if (!isAppAtLeastSdkVersion(context, query.callingPackage, minSdkVersion)) {
            String errorMsg = "Allowing " + query.callingPackage + " " + locationTypeForLog
                    + " because it doesn't target API " + query.minSdkVersionForFine + " yet."
                    + " Please fix this app. Called from " + query.method;
            logError(context, errorMsg);
            return null;
        } else {
            // If we're not allowing it due to the above two conditions, this means that the app
            // did not declare the permission in their manifest.
            return LocationPermissionResult.DENIED_HARD;
        }
    }

    public static LocationPermissionResult checkLocationPermission(
            Context context, LocationPermissionQuery query) {
        // Always allow the phone process and system server to access location. This avoid
        // breaking legacy code that rely on public-facing APIs to access cell location, and
        // it doesn't create an info leak risk because the cell location is stored in the phone
        // process anyway, and the system server already has location access.
        if (query.callingUid == Process.PHONE_UID || query.callingUid == Process.SYSTEM_UID
                || query.callingUid == Process.ROOT_UID) {
            return LocationPermissionResult.ALLOWED;
        }

        // Check the system-wide requirements. If the location master switch is off or
        // the app's profile isn't in foreground, return a soft denial.
        if (!checkSystemLocationAccess(context, query.callingUid, query.callingPid)) {
            return LocationPermissionResult.DENIED_SOFT;
        }

        // Do the check for fine, then for coarse.
        if (query.minSdkVersionForFine < Integer.MAX_VALUE) {
            LocationPermissionResult resultForFine = checkAppLocationPermissionHelper(
                    context, query, Manifest.permission.ACCESS_FINE_LOCATION);
            if (resultForFine != null) {
                return resultForFine;
            }
        }

        if (query.minSdkVersionForCoarse < Integer.MAX_VALUE) {
            LocationPermissionResult resultForCoarse = checkAppLocationPermissionHelper(
                    context, query, Manifest.permission.ACCESS_COARSE_LOCATION);
            if (resultForCoarse != null) {
                return resultForCoarse;
            }
        }

        // At this point, we're out of location checks to do. If the app bypassed all the previous
        // ones due to the SDK grandfathering schemes, allow it access.
        return LocationPermissionResult.ALLOWED;
    }


    private static boolean checkManifestPermission(Context context, int pid, int uid,
            String permissionToCheck) {
        return context.checkPermission(permissionToCheck, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean checkSystemLocationAccess(@NonNull Context context, int uid, int pid) {
        if (!isLocationModeEnabled(context, UserHandle.getUserId(uid))) {
            if (DBG) Log.w(TAG, "Location disabled, failed, (" + uid + ")");
            return false;
        }
        // If the user or profile is current, permission is granted.
        // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission.
        return isCurrentProfile(context, uid) || checkInteractAcrossUsersFull(context, uid, pid);
    }

    private static boolean isLocationModeEnabled(@NonNull Context context, @UserIdInt int userId) {
        LocationManager locationManager = context.getSystemService(LocationManager.class);
        if (locationManager == null) {
            Log.w(TAG, "Couldn't get location manager, denying location access");
            return false;
        }
        return locationManager.isLocationEnabledForUser(UserHandle.of(userId));
    }

    private static boolean checkInteractAcrossUsersFull(
            @NonNull Context context, int pid, int uid) {
        return checkManifestPermission(context, pid, uid,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
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

    private static boolean isAppAtLeastSdkVersion(Context context, String pkgName, int sdkVersion) {
        try {
            if (context.getPackageManager().getApplicationInfo(pkgName, 0).targetSdkVersion
                    >= sdkVersion) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume known app (more strict checking)
            // Note: This case will never happen since checkPackage is
            // called to verify validity before checking app's version.
        }
        return false;
    }
}