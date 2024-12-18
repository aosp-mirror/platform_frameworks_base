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

package android.telephony;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.util.TelephonyUtils;

/**
 * Helper for performing location access checks.
 * @hide
 */
public final class LocationAccessPolicy {
    private static final String TAG = "LocationAccessPolicy";
    private static final boolean DBG = false;
    public static final int MAX_SDK_FOR_ANY_ENFORCEMENT = Build.VERSION_CODES.CUR_DEVELOPMENT;

    public enum LocationPermissionResult {
        ALLOWED,
        /**
         * Indicates that the denial is due to a transient device state
         * (e.g. app-ops, location main switch)
         */
        DENIED_SOFT,
        /**
         * Indicates that the denial is due to a misconfigured app (e.g. missing entry in manifest)
         */
        DENIED_HARD,
    }

    /** Data structure for location permission query */
    public static class LocationPermissionQuery {
        public final String callingPackage;
        public final String callingFeatureId;
        public final int callingUid;
        public final int callingPid;
        public final int minSdkVersionForCoarse;
        public final int minSdkVersionForFine;
        public final boolean logAsInfo;
        public final String method;

        private LocationPermissionQuery(String callingPackage, @Nullable String callingFeatureId,
                int callingUid, int callingPid, int minSdkVersionForCoarse,
                int minSdkVersionForFine, boolean logAsInfo, String method) {
            this.callingPackage = callingPackage;
            this.callingFeatureId = callingFeatureId;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
            this.minSdkVersionForCoarse = minSdkVersionForCoarse;
            this.minSdkVersionForFine = minSdkVersionForFine;
            this.logAsInfo = logAsInfo;
            this.method = method;
        }

        /** Builder for LocationPermissionQuery */
        public static class Builder {
            private String mCallingPackage;
            private String mCallingFeatureId;
            private int mCallingUid;
            private int mCallingPid;
            private int mMinSdkVersionForCoarse = -1;
            private int mMinSdkVersionForFine = -1;
            private int mMinSdkVersionForEnforcement = -1;
            private boolean mLogAsInfo = false;
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
            public Builder setCallingFeatureId(@Nullable String callingFeatureId) {
                mCallingFeatureId = callingFeatureId;
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
             * permission. This method MUST be called before calling {@link #build()}. Otherwise, an
             * {@link IllegalArgumentException} will be thrown.
             *
             * Additionally, if both the argument to this method and
             * {@link #setMinSdkVersionForFine} are greater than {@link Build.VERSION_CODES#BASE},
             * you must call {@link #setMinSdkVersionForEnforcement} with the min of the two to
             * affirm that you do not want any location checks below a certain SDK version.
             * Otherwise, {@link #build} will throw an {@link IllegalArgumentException}.
             */
            public Builder setMinSdkVersionForCoarse(
                    int minSdkVersionForCoarse) {
                mMinSdkVersionForCoarse = minSdkVersionForCoarse;
                return this;
            }

            /**
             * Apps that target at least this sdk version will be checked for fine location
             * permission.  This method MUST be called before calling {@link #build()}.
             * Otherwise, an {@link IllegalArgumentException} will be thrown.
             *
             * Additionally, if both the argument to this method and
             * {@link #setMinSdkVersionForCoarse} are greater than {@link Build.VERSION_CODES#BASE},
             * you must call {@link #setMinSdkVersionForEnforcement} with the min of the two to
             * affirm that you do not want any location checks below a certain SDK version.
             * Otherwise, {@link #build} will throw an {@link IllegalArgumentException}.
             */
            public Builder setMinSdkVersionForFine(
                    int minSdkVersionForFine) {
                mMinSdkVersionForFine = minSdkVersionForFine;
                return this;
            }

            /**
             * If both the argument to {@link #setMinSdkVersionForFine} and
             * {@link #setMinSdkVersionForCoarse} are greater than {@link Build.VERSION_CODES#BASE},
             * this method must be called with the min of the two to
             * affirm that you do not want any location checks below a certain SDK version.
             */
            public Builder setMinSdkVersionForEnforcement(int minSdkVersionForEnforcement) {
                mMinSdkVersionForEnforcement = minSdkVersionForEnforcement;
                return this;
            }

            /**
             * Optional, for logging purposes only.
             */
            public Builder setMethod(String method) {
                mMethod = method;
                return this;
            }

            /**
             * If called with {@code true}, log messages will only be printed at the info level.
             */
            public Builder setLogAsInfo(boolean logAsInfo) {
                mLogAsInfo = logAsInfo;
                return this;
            }

            /** build LocationPermissionQuery */
            public LocationPermissionQuery build() {
                if (mMinSdkVersionForCoarse < 0 || mMinSdkVersionForFine < 0) {
                    throw new IllegalArgumentException("Must specify min sdk versions for"
                            + " enforcement for both coarse and fine permissions");
                }
                if (mMinSdkVersionForFine > Build.VERSION_CODES.BASE
                        && mMinSdkVersionForCoarse > Build.VERSION_CODES.BASE) {
                    if (mMinSdkVersionForEnforcement != Math.min(
                            mMinSdkVersionForCoarse, mMinSdkVersionForFine)) {
                        throw new IllegalArgumentException("setMinSdkVersionForEnforcement must be"
                                + " called.");
                    }
                }

                if (mMinSdkVersionForFine < mMinSdkVersionForCoarse) {
                    throw new IllegalArgumentException("Since fine location permission includes"
                            + " access to coarse location, the min sdk level for enforcement of"
                            + " the fine location permission must not be less than the min sdk"
                            + " level for enforcement of the coarse location permission.");
                }

                return new LocationPermissionQuery(mCallingPackage, mCallingFeatureId,
                        mCallingUid, mCallingPid, mMinSdkVersionForCoarse, mMinSdkVersionForFine,
                        mLogAsInfo, mMethod);
            }
        }
    }

    private static void logError(Context context, LocationPermissionQuery query, String errorMsg) {
        if (query.logAsInfo) {
            Log.i(TAG, errorMsg);
            return;
        }
        Log.e(TAG, errorMsg);
        try {
            if (TelephonyUtils.IS_DEBUGGABLE) {
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

    private static String getAppOpsString(String manifestPermission) {
        switch (manifestPermission) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                return AppOpsManager.OPSTR_FINE_LOCATION;
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return AppOpsManager.OPSTR_COARSE_LOCATION;
            default:
                return null;
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

        if (hasManifestPermission) {
            // Only check the app op if the app has the permission.
            int appOpMode = context.getSystemService(AppOpsManager.class)
                    .noteOpNoThrow(getAppOpsString(permissionToCheck), query.callingUid,
                            query.callingPackage, query.callingFeatureId, null);
            if (appOpMode == AppOpsManager.MODE_ALLOWED) {
                // If the app did everything right, return without logging.
                return LocationPermissionResult.ALLOWED;
            } else {
                // If the app has the manifest permission but not the app-op permission, it means
                // that it's aware of the requirement and the user denied permission explicitly.
                // If we see this, don't let any of the overrides happen.
                Log.i(TAG, query.callingPackage + " is aware of " + locationTypeForLog + " but the"
                        + " app-ops permission is specifically denied.");
                return appOpsModeToPermissionResult(appOpMode);
            }
        }

        int minSdkVersion = Manifest.permission.ACCESS_FINE_LOCATION.equals(permissionToCheck)
                ? query.minSdkVersionForFine : query.minSdkVersionForCoarse;

        UserHandle callingUserHandle = UserHandle.getUserHandleForUid(query.callingUid);

        // If the app fails for some reason, see if it should be allowed to proceed.
        if (minSdkVersion > MAX_SDK_FOR_ANY_ENFORCEMENT) {
            String errorMsg = "Allowing " + query.callingPackage + " " + locationTypeForLog
                    + " because we're not enforcing API " + minSdkVersion + " yet."
                    + " Please fix this app because it will break in the future. Called from "
                    + query.method;
            logError(context, query, errorMsg);
            return null;
        } else if (!isAppAtLeastSdkVersion(context, callingUserHandle, query.callingPackage,
                minSdkVersion)) {
            String errorMsg = "Allowing " + query.callingPackage + " " + locationTypeForLog
                    + " because it doesn't target API " + minSdkVersion + " yet."
                    + " Please fix this app. Called from " + query.method;
            logError(context, query, errorMsg);
            return null;
        } else {
            // If we're not allowing it due to the above two conditions, this means that the app
            // did not declare the permission in their manifest.
            return LocationPermissionResult.DENIED_HARD;
        }
    }

    /** Check if location permissions have been granted */
    public static LocationPermissionResult checkLocationPermission(
            Context context, LocationPermissionQuery query) {
        // Always allow the phone process, system server, and network stack to access location.
        // This avoid breaking legacy code that rely on public-facing APIs to access cell location,
        // and it doesn't create an info leak risk because the cell location is stored in the phone
        // process anyway, and the system server already has location access.
        if (TelephonyPermissions.isSystemOrPhone(query.callingUid)
                || UserHandle.isSameApp(query.callingUid, Process.NETWORK_STACK_UID)
                || UserHandle.isSameApp(query.callingUid, Process.ROOT_UID)) {
            return LocationPermissionResult.ALLOWED;
        }

        // Check the system-wide requirements. If the location main switch is off and the caller is
        // not in the allowlist of apps that always have loation access or the app's profile
        // isn't in the foreground, return a soft denial.
        if (!checkSystemLocationAccess(context, query.callingUid, query.callingPid,
                query.callingPackage)) {
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
        // ones due to the SDK backwards compatibility schemes, allow it access.
        return LocationPermissionResult.ALLOWED;
    }

    private static boolean checkManifestPermission(Context context, int pid, int uid,
            String permissionToCheck) {
        return context.checkPermission(permissionToCheck, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean checkSystemLocationAccess(@NonNull Context context, int uid, int pid,
            @NonNull String callingPackage) {
        if (!isLocationModeEnabled(context, UserHandle.getUserHandleForUid(uid).getIdentifier())
                && !isLocationBypassAllowed(context, callingPackage)) {
            if (DBG) Log.w(TAG, "Location disabled, failed, (" + uid + ")");
            return false;
        }
        // If the user or profile is current, permission is granted.
        // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission.
        return isCurrentProfile(context, uid) || checkInteractAcrossUsersFull(context, pid, uid);
    }

    /**
     * @return Whether location is enabled for the given user.
     */
    public static boolean isLocationModeEnabled(@NonNull Context context, @UserIdInt int userId) {
        LocationManager locationManager = context.getSystemService(LocationManager.class);
        if (locationManager == null) {
            Log.w(TAG, "Couldn't get location manager, denying location access");
            return false;
        }
        return locationManager.isLocationEnabledForUser(UserHandle.of(userId));
    }

    private static boolean isLocationBypassAllowed(@NonNull Context context,
            @NonNull String callingPackage) {
        for (String bypassPackage : getLocationBypassPackages(context)) {
            if (callingPackage.equals(bypassPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return An array of packages that are always allowed to access location.
     */
    public static @NonNull String[] getLocationBypassPackages(@NonNull Context context) {
        return context.getResources().getStringArray(
                com.android.internal.R.array.config_serviceStateLocationAllowedPackages);
    }

    private static boolean checkInteractAcrossUsersFull(
            @NonNull Context context, int pid, int uid) {
        return checkManifestPermission(context, pid, uid,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    private static boolean isCurrentProfile(@NonNull Context context, int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (UserHandle.getUserHandleForUid(uid).getIdentifier()
                    == ActivityManager.getCurrentUser()) {
                return true;
            }
            ActivityManager activityManager = context.getSystemService(ActivityManager.class);
            if (activityManager != null) {
                return activityManager.isProfileForeground(
                        UserHandle.getUserHandleForUid(ActivityManager.getCurrentUser()));
            } else {
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isAppAtLeastSdkVersion(Context context,
            @NonNull UserHandle callingUserHandle, String pkgName, int sdkVersion) {
        try {
            if (Flags.hsumPackageManager()) {
                if (context.getPackageManager().getApplicationInfoAsUser(
                        pkgName, 0, callingUserHandle).targetSdkVersion >= sdkVersion) {
                    return true;
                }
            } else {
                if (context.getPackageManager().getApplicationInfo(pkgName, 0).targetSdkVersion
                        >= sdkVersion) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume known app (more strict checking)
            // Note: This case will never happen since checkPackage is
            // called to verify validity before checking app's version.
        }
        return false;
    }
}
