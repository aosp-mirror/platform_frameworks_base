/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.telephony;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.permission.LegacyPermissionManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Utility class for Telephony permission enforcement. */
public final class TelephonyPermissions {
    private static final String LOG_TAG = "TelephonyPermissions";

    private static final boolean DBG = false;

    /**
     * Whether to disable the new device identifier access restrictions.
     */
    private static final String PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED =
            "device_identifier_access_restrictions_disabled";

    // Contains a mapping of packages that did not meet the new requirements to access device
    // identifiers and the methods they were attempting to invoke; used to prevent duplicate
    // reporting of packages / methods.
    private static final Map<String, Set<String>> sReportedDeviceIDPackages;
    static {
        sReportedDeviceIDPackages = new HashMap<>();
    }

    private TelephonyPermissions() {}

    /**
     * Check whether the caller (or self, if not processing an IPC) can read phone state.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has the READ_PRIVILEGED_PHONE_STATE permission, the
     *       READ_PHONE_STATE runtime permission, or carrier privileges on the given subId.
     *   <li>throw SecurityException: if the caller didn't declare any of these permissions, or, for
     *       apps which support runtime permissions, if the caller does not currently have any of
     *       these permissions.
     *   <li>return false: if the caller lacks all of these permissions and doesn't support runtime
     *       permissions. This implies that the user revoked the ability to read phone state
     *       manually (via AppOps). In this case we can't throw as it would break app compatibility,
     *       so we return false to indicate that the calling function should return placeholder
     *       data.
     * </ul>
     *
     * <p>Note: for simplicity, this method always returns false for callers using legacy
     * permissions and who have had READ_PHONE_STATE revoked, even if they are carrier-privileged.
     * Such apps should migrate to runtime permissions or stop requiring READ_PHONE_STATE on P+
     * devices.
     *
     * @param subId the subId of the relevant subscription; used to check carrier privileges. May be
     *              {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} to skip this check for cases
     *              where it isn't relevant (hidden APIs, or APIs which are otherwise okay to leave
     *              inaccesible to carrier-privileged apps).
     */
    public static boolean checkCallingOrSelfReadPhoneState(
            Context context, int subId, String callingPackage, @Nullable String callingFeatureId,
            String message) {
        return checkReadPhoneState(context, subId, Binder.getCallingPid(), Binder.getCallingUid(),
                callingPackage, callingFeatureId, message);
    }

    /** Identical to checkCallingOrSelfReadPhoneState but never throws SecurityException */
    public static boolean checkCallingOrSelfReadPhoneStateNoThrow(
            Context context, int subId, String callingPackage, @Nullable String callingFeatureId,
            String message) {
        try {
            return checkCallingOrSelfReadPhoneState(context, subId, callingPackage,
                    callingFeatureId, message);
        } catch (SecurityException se) {
            return false;
        }
    }

    /**
     * Check whether the caller (or self, if not processing an IPC) has internet permission.
     * @param context app context
     * @param message detail message
     * @return true if permission is granted, else false
     */
    public static boolean checkInternetPermissionNoThrow(Context context, String message) {
        try {
            context.enforcePermission(Manifest.permission.INTERNET,
                    Binder.getCallingPid(), Binder.getCallingUid(), message);
            return true;
        } catch (SecurityException se) {
            return false;
        }
    }

    /**
     * Check whether the caller (or self, if not processing an IPC) has non dangerous
     * read phone state permission.
     * @param context app context
     * @param message detail message
     * @return true if permission is granted, else false
     */
    public static boolean checkCallingOrSelfReadNonDangerousPhoneStateNoThrow(
            Context context, String message) {
        try {
            context.enforcePermission(
                    Manifest.permission.READ_BASIC_PHONE_STATE,
                    Binder.getCallingPid(), Binder.getCallingUid(), message);
            return true;
        } catch (SecurityException se) {
            return false;
        }
    }

    /**
     * Check whether the app with the given pid/uid can read phone state.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has the READ_PRIVILEGED_PHONE_STATE permission, the
     *       READ_PHONE_STATE runtime permission, or carrier privileges on the given subId.
     *   <li>throw SecurityException: if the caller didn't declare any of these permissions, or, for
     *       apps which support runtime permissions, if the caller does not currently have any of
     *       these permissions.
     *   <li>return false: if the caller lacks all of these permissions and doesn't support runtime
     *       permissions. This implies that the user revoked the ability to read phone state
     *       manually (via AppOps). In this case we can't throw as it would break app compatibility,
     *       so we return false to indicate that the calling function should return placeholder
     *       data.
     * </ul>
     *
     * <p>Note: for simplicity, this method always returns false for callers using legacy
     * permissions and who have had READ_PHONE_STATE revoked, even if they are carrier-privileged.
     * Such apps should migrate to runtime permissions or stop requiring READ_PHONE_STATE on P+
     * devices.
     */
    public static boolean checkReadPhoneState(
            Context context, int subId, int pid, int uid, String callingPackage,
            @Nullable  String callingFeatureId, String message) {
        try {
            context.enforcePermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, pid, uid, message);

            // SKIP checking for run-time permission since caller has PRIVILEGED permission
            return true;
        } catch (SecurityException privilegedPhoneStateException) {
            try {
                context.enforcePermission(
                        android.Manifest.permission.READ_PHONE_STATE, pid, uid, message);
            } catch (SecurityException phoneStateException) {
                // If we don't have the runtime permission, but do have carrier privileges, that
                // suffices for reading phone state.
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    enforceCarrierPrivilege(context, subId, uid, message);
                    return true;
                }
                throw phoneStateException;
            }
        }

        // We have READ_PHONE_STATE permission, so return true as long as the AppOps bit hasn't been
        // revoked.
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return appOps.noteOpNoThrow(AppOpsManager.OPSTR_READ_PHONE_STATE, uid, callingPackage,
                callingFeatureId, null) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Check whether the calling packages has carrier privileges for the passing subscription.
     * @return {@code true} if the caller has carrier privileges, {@false} otherwise.
     */
    public static boolean checkCarrierPrivilegeForSubId(Context context, int subId) {
        if (SubscriptionManager.isValidSubscriptionId(subId)
                && getCarrierPrivilegeStatus(context, subId, Binder.getCallingUid())
                == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
            return true;
        }
        return false;
    }

    /**
     * Check whether the app with the given pid/uid can read phone state, or has carrier
     * privileges on any active subscription.
     *
     * <p>If the app does not have carrier privilege, this method will return {@code false} instead
     * of throwing a SecurityException. Therefore, the callers cannot tell the difference
     * between M+ apps which declare the runtime permission but do not have it, and pre-M apps
     * which declare the static permission but had access revoked via AppOps. Apps in the former
     * category expect SecurityExceptions; apps in the latter don't. So this method is suitable for
     * use only if the behavior in both scenarios is meant to be identical.
     *
     * @return {@code true} if the app can read phone state or has carrier privilege;
     *         {@code false} otherwise.
     */
    public static boolean checkReadPhoneStateOnAnyActiveSub(Context context, int pid, int uid,
            String callingPackage, @Nullable String callingFeatureId, String message) {
        try {
            context.enforcePermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, pid, uid, message);

            // SKIP checking for run-time permission since caller has PRIVILEGED permission
            return true;
        } catch (SecurityException privilegedPhoneStateException) {
            try {
                context.enforcePermission(
                        android.Manifest.permission.READ_PHONE_STATE, pid, uid, message);
            } catch (SecurityException phoneStateException) {
                // If we don't have the runtime permission, but do have carrier privileges, that
                // suffices for reading phone state.
                return checkCarrierPrivilegeForAnySubId(context, uid);
            }
        }

        // We have READ_PHONE_STATE permission, so return true as long as the AppOps bit hasn't been
        // revoked.
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return appOps.noteOpNoThrow(AppOpsManager.OPSTR_READ_PHONE_STATE, uid, callingPackage,
                callingFeatureId, null) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Check whether the caller (or self, if not processing an IPC) can read device identifiers.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has the READ_PRIVILEGED_PHONE_STATE permission, the calling
     *       package passes a DevicePolicyManager Device Owner / Profile Owner device identifier
     *       access check, or the calling package has carrier privileges on any active subscription.
    *   <li>throw SecurityException: if the caller does not meet any of the requirements and is
     *       targeting Q or is targeting pre-Q and does not have the READ_PHONE_STATE permission
     *       or carrier privileges of any active subscription.
     *   <li>return false: if the caller is targeting pre-Q and does have the READ_PHONE_STATE
     *       permission. In this case the caller would expect to have access to the device
     *       identifiers so false is returned instead of throwing a SecurityException to indicate
     *       the calling function should return placeholder data.
     * </ul>
     */
    public static boolean checkCallingOrSelfReadDeviceIdentifiers(Context context,
            String callingPackage, @Nullable String callingFeatureId, String message) {
        return checkCallingOrSelfReadDeviceIdentifiers(context,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID, callingPackage, callingFeatureId,
                message);
    }

    /**
     * Check whether the caller (or self, if not processing an IPC) can read device identifiers.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has the READ_PRIVILEGED_PHONE_STATE permission, the calling
     *       package passes a DevicePolicyManager Device Owner / Profile Owner device identifier
     *       access check, or the calling package has carrier privileges on any active
     *       subscription, or the calling package has the {@link
     *       Manifest.permission#USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER} appop permission.
     *   <li>throw SecurityException: if the caller does not meet any of the requirements and is
     *       targeting Q or is targeting pre-Q and does not have the READ_PHONE_STATE permission
     *       or carrier privileges of any active subscription.
     *   <li>return false: if the caller is targeting pre-Q and does have the READ_PHONE_STATE
     *       permission or carrier privileges. In this case the caller would expect to have access
     *       to the device identifiers so false is returned instead of throwing a SecurityException
     *       to indicate the calling function should return placeholder data.
     * </ul>
     */
    public static boolean checkCallingOrSelfReadDeviceIdentifiers(Context context, int subId,
            String callingPackage, @Nullable String callingFeatureId, String message) {
        if (checkCallingOrSelfUseIccAuthWithDeviceIdentifier(context, callingPackage,
                callingFeatureId, message)) {
            return true;
        }
        return checkPrivilegedReadPermissionOrCarrierPrivilegePermission(
                context, subId, callingPackage, callingFeatureId, message, true, true);
    }

    /**
     * Check whether the caller (or self, if not processing an IPC) can read subscriber identifiers.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has the READ_PRIVILEGED_PHONE_STATE permission, the calling
     *       package passes a DevicePolicyManager Device Owner / Profile Owner device identifier
     *       access check, or the calling package has carrier privileges on specified subscription,
     *       or the calling package has the {@link
     *       Manifest.permission#USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER} appop permission.
     *   <li>throw SecurityException: if the caller does not meet any of the requirements and is
     *       targeting Q or is targeting pre-Q and does not have the READ_PHONE_STATE permission.
     *   <li>return false: if the caller is targeting pre-Q and does have the READ_PHONE_STATE
     *       permission. In this case the caller would expect to have access to the device
     *       identifiers so false is returned instead of throwing a SecurityException to indicate
     *       the calling function should return placeholder data.
     * </ul>
     */
    public static boolean checkCallingOrSelfReadSubscriberIdentifiers(Context context, int subId,
            String callingPackage, @Nullable String callingFeatureId, String message) {
        return checkCallingOrSelfReadSubscriberIdentifiers(context, subId, callingPackage,
                callingFeatureId, message, true);
    }

    /**
     * Same as {@link #checkCallingOrSelfReadSubscriberIdentifiers(Context, int, String, String,
     * String)} except this allows an additional parameter reportFailure. Caller may not want to
     * report a failure when this is an internal/intermediate check, for example,
     * SubscriptionManagerService calls this with an INVALID_SUBID to check if caller has the
     * required permissions to bypass carrier privilege checks.
     * @param reportFailure Indicates if failure should be reported.
     */
    public static boolean checkCallingOrSelfReadSubscriberIdentifiers(Context context, int subId,
            String callingPackage, @Nullable String callingFeatureId, String message,
            boolean reportFailure) {
        if (checkCallingOrSelfUseIccAuthWithDeviceIdentifier(context, callingPackage,
                callingFeatureId, message)) {
            return true;
        }
        return checkPrivilegedReadPermissionOrCarrierPrivilegePermission(
                context, subId, callingPackage, callingFeatureId, message, false, reportFailure);
    }

    private static void throwSecurityExceptionAsUidDoesNotHaveAccess(String message, int uid) {
        throw new SecurityException(message + ": The uid " + uid
                + " does not meet the requirements to access device identifiers.");
    }

    /**
     * Checks whether the app with the given pid/uid can read device identifiers.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has the READ_PRIVILEGED_PHONE_STATE permission, the calling
     *       package passes a DevicePolicyManager Device Owner / Profile Owner device identifier
     *       access check; or the calling package has carrier privileges on the specified
     *       subscription; or allowCarrierPrivilegeOnAnySub is true and has carrier privilege on
     *       any active subscription.
     *   <li>throw SecurityException: if the caller does not meet any of the requirements and is
     *       targeting Q or is targeting pre-Q and does not have the READ_PHONE_STATE permission.
     *   <li>return false: if the caller is targeting pre-Q and does have the READ_PHONE_STATE
     *       permission. In this case the caller would expect to have access to the device
     *       identifiers so false is returned instead of throwing a SecurityException to indicate
     *       the calling function should return placeholder data.
     * </ul>
     */
    private static boolean checkPrivilegedReadPermissionOrCarrierPrivilegePermission(
            Context context, int subId, String callingPackage, @Nullable String callingFeatureId,
            String message, boolean allowCarrierPrivilegeOnAnySub, boolean reportFailure) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();

        // If the calling package has carrier privileges for specified sub, then allow access.
        if (checkCarrierPrivilegeForSubId(context, subId)) return true;

        // If the calling package has carrier privileges for any subscription
        // and allowCarrierPrivilegeOnAnySub is set true, then allow access.
        if (allowCarrierPrivilegeOnAnySub && checkCarrierPrivilegeForAnySubId(context, uid)) {
            return true;
        }

        LegacyPermissionManager permissionManager = (LegacyPermissionManager)
                context.getSystemService(Context.LEGACY_PERMISSION_SERVICE);
        try {
            if (permissionManager.checkDeviceIdentifierAccess(callingPackage, message,
                    callingFeatureId,
                    pid, uid) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        } catch (SecurityException se) {
            throwSecurityExceptionAsUidDoesNotHaveAccess(message, uid);
        }

        if (reportFailure) {
            return reportAccessDeniedToReadIdentifiers(context, subId, pid, uid, callingPackage,
                    message);
        } else {
            return false;
        }
    }

    /**
     * Reports a failure when the app with the given pid/uid cannot access the requested identifier.
     *
     * @returns false if the caller is targeting pre-Q and does have the READ_PHONE_STATE
     * permission or carrier privileges.
     * @throws SecurityException if the caller does not meet any of the requirements for the
     *                           requested identifier and is targeting Q or is targeting pre-Q
     *                           and does not have the READ_PHONE_STATE permission or carrier
     *                           privileges.
     */
    private static boolean reportAccessDeniedToReadIdentifiers(Context context, int subId, int pid,
            int uid, String callingPackage, String message) {
        ApplicationInfo callingPackageInfo = null;
        try {
            callingPackageInfo = context.getPackageManager().getApplicationInfoAsUser(
                    callingPackage, 0, UserHandle.getUserHandleForUid(uid));
        } catch (PackageManager.NameNotFoundException e) {
            // If the application info for the calling package could not be found then assume the
            // calling app is a non-preinstalled app to detect any issues with the check
            Log.e(LOG_TAG, "Exception caught obtaining package info for package " + callingPackage,
                    e);
        }
        // The current package should only be reported in StatsLog if it has not previously been
        // reported for the currently invoked device identifier method.
        boolean packageReported = sReportedDeviceIDPackages.containsKey(callingPackage);
        if (!packageReported || !sReportedDeviceIDPackages.get(callingPackage).contains(
                message)) {
            Set invokedMethods;
            if (!packageReported) {
                invokedMethods = new HashSet<String>();
                sReportedDeviceIDPackages.put(callingPackage, invokedMethods);
            } else {
                invokedMethods = sReportedDeviceIDPackages.get(callingPackage);
            }
            invokedMethods.add(message);
            TelephonyCommonStatsLog.write(TelephonyCommonStatsLog.DEVICE_IDENTIFIER_ACCESS_DENIED,
                    callingPackage, message, /* isPreinstalled= */ false, false);
        }
        Log.w(LOG_TAG, "reportAccessDeniedToReadIdentifiers:" + callingPackage + ":" + message + ":"
                + subId);
        // if the target SDK is pre-Q then check if the calling package would have previously
        // had access to device identifiers.
        if (callingPackageInfo != null && (
                callingPackageInfo.targetSdkVersion < Build.VERSION_CODES.Q)) {
            if (context.checkPermission(
                    android.Manifest.permission.READ_PHONE_STATE,
                    pid,
                    uid) == PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (checkCarrierPrivilegeForSubId(context, subId)) {
                return false;
            }
        }
        throwSecurityExceptionAsUidDoesNotHaveAccess(message, uid);
        return true;
    }

    /**
     * Check whether the caller (or self, if not processing an IPC) has {@link
     * Manifest.permission#USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER} AppOp permission.
     *
     * <p>With the permission, the caller can access device/subscriber identifiers and use ICC
     * authentication like EAP-AKA.
     */
    public static boolean checkCallingOrSelfUseIccAuthWithDeviceIdentifier(Context context,
            String callingPackage, String callingFeatureId, String message) {
        // The implementation follows PermissionChecker.checkAppOpPermission, but it cannot be
        // used directly: because it uses noteProxyOpNoThrow which requires the phone process
        // having the permission, which doesn't make sense since phone process is the ower of
        // data/action.
        // Cannot perform appop check if the calling package is null
        if (callingPackage == null) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int opMode = appOps.noteOpNoThrow(AppOpsManager.OPSTR_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER,
                callingUid, callingPackage, callingFeatureId, message);
        switch (opMode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_FOREGROUND:
                return true;
            case AppOpsManager.MODE_DEFAULT:
                return context.checkCallingOrSelfPermission(
                            Manifest.permission.USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER)
                        == PERMISSION_GRANTED;
            default:
                return false;
        }
    }

    /**
     * Check whether the app with the given pid/uid can read the call log.
     * @return {@code true} if the specified app has the read call log permission and AppOpp granted
     *      to it, {@code false} otherwise.
     */
    public static boolean checkReadCallLog(
            Context context, int subId, int pid, int uid, String callingPackage,
            @Nullable String callingPackageName) {
        if (context.checkPermission(Manifest.permission.READ_CALL_LOG, pid, uid)
                != PERMISSION_GRANTED) {
            // If we don't have the runtime permission, but do have carrier privileges, that
            // suffices for being able to see the call phone numbers.
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                enforceCarrierPrivilege(context, subId, uid, "readCallLog");
                return true;
            }
            return false;
        }

        // We have READ_CALL_LOG permission, so return true as long as the AppOps bit hasn't been
        // revoked.
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return appOps.noteOpNoThrow(AppOpsManager.OPSTR_READ_CALL_LOG, uid, callingPackage,
                callingPackageName, null) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Returns whether the caller can read phone numbers.
     *
     * <p>Besides apps with the ability to read phone state per {@link #checkReadPhoneState}
     * (only prior to R), the default SMS app and apps with READ_SMS or READ_PHONE_NUMBERS
     * can also read phone numbers.
     */
    public static boolean checkCallingOrSelfReadPhoneNumber(
            Context context, int subId, String callingPackage, @Nullable String callingFeatureId,
            String message) {
        return checkReadPhoneNumber(
                context, subId, Binder.getCallingPid(), Binder.getCallingUid(),
                callingPackage, callingFeatureId, message);
    }

    /**
     * Returns whether the caller can read phone numbers.
     *
     * <p>Besides apps with the ability to read phone state per {@link #checkReadPhoneState}
     * (only prior to R), the default SMS app and apps with READ_SMS or READ_PHONE_NUMBERS
     * can also read phone numbers.
     */
    @VisibleForTesting
    public static boolean checkReadPhoneNumber(
            Context context, int subId, int pid, int uid,
            String callingPackage, @Nullable String callingFeatureId, String message) {
        LegacyPermissionManager permissionManager = (LegacyPermissionManager)
                context.getSystemService(Context.LEGACY_PERMISSION_SERVICE);
        // Apps with target SDK version < R can have the READ_PHONE_STATE permission granted with
        // the appop denied. If PERMISSION_GRANTED is not received then check if the caller has
        // carrier privileges; if not and the permission result is MODE_IGNORED then return false
        // to return null data to the caller.
        int permissionResult = permissionManager.checkPhoneNumberAccess(callingPackage, message,
                callingFeatureId, pid, uid);
        if (permissionResult == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            if (TelephonyPermissions.getCarrierPrivilegeStatus(context, subId, uid)
                    == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return true;
            }
        }
        if (permissionResult == AppOpsManager.MODE_IGNORED) {
            return false;
        }

        throw new SecurityException(message + ": Neither user " + uid
                + " nor current process has " + android.Manifest.permission.READ_PHONE_STATE
                + ", " + android.Manifest.permission.READ_SMS + ", or "
                + android.Manifest.permission.READ_PHONE_NUMBERS);
    }

    /**
     * Ensure the caller (or self, if not processing an IPC) has MODIFY_PHONE_STATE (and is thus a
     * privileged app) or carrier privileges.
     *
     * @throws SecurityException if the caller does not have the required permission/privileges
     */
    public static void enforceCallingOrSelfModifyPermissionOrCarrierPrivilege(
            Context context, int subId, String message) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                == PERMISSION_GRANTED) {
            return;
        }

        if (DBG) Log.d(LOG_TAG, "No modify permission, check carrier privilege next.");
        enforceCallingOrSelfCarrierPrivilege(context, subId, message);
    }

    /**
     * Check if the caller (or self, if not processing an IPC) has ACCESS_LAST_KNOWN_CELL_ID
     * permission
     *
     * @return true if caller has ACCESS_LAST_KNOWN_CELL_ID permission else false.
     */
    @RequiresPermission(Manifest.permission.ACCESS_LAST_KNOWN_CELL_ID)
    public static boolean checkLastKnownCellIdAccessPermission(Context context) {
        return context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_LAST_KNOWN_CELL_ID)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Ensure the caller (or self, if not processing an IPC) has
     * {@link android.Manifest.permission#READ_PHONE_STATE} or carrier privileges.
     *
     * @throws SecurityException if the caller does not have the required permission/privileges
     */
    public static void enforceCallingOrSelfReadPhoneStatePermissionOrCarrierPrivilege(
            Context context, int subId, String message) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                == PERMISSION_GRANTED) {
            return;
        }

        if (DBG) {
            Log.d(LOG_TAG, "No READ_PHONE_STATE permission, check carrier privilege next.");
        }

        enforceCallingOrSelfCarrierPrivilege(context, subId, message);
    }

    /**
     * Ensure the caller (or self, if not processing an IPC) has
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE} or carrier privileges.
     *
     * @throws SecurityException if the caller does not have the required permission/privileges
     */
    public static void enforceCallingOrSelfReadPrivilegedPhoneStatePermissionOrCarrierPrivilege(
            Context context, int subId, String message) {
        if (context.checkCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
                == PERMISSION_GRANTED) {
            return;
        }

        if (DBG) {
            Log.d(LOG_TAG, "No READ_PRIVILEGED_PHONE_STATE permission, "
                    + "check carrier privilege next.");
        }

        enforceCallingOrSelfCarrierPrivilege(context, subId, message);
    }

    /**
     * Ensure the caller (or self, if not processing an IPC) has
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE} or
     * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE} or carrier privileges.
     *
     * @throws SecurityException if the caller does not have the required permission/privileges
     */
    public static void enforceCallingOrSelfReadPrecisePhoneStatePermissionOrCarrierPrivilege(
            Context context, int subId, String message) {
        if (context.checkCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
                == PERMISSION_GRANTED) {
            return;
        }

        if (context.checkCallingOrSelfPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
                == PERMISSION_GRANTED) {
            return;
        }

        if (DBG) {
            Log.d(LOG_TAG, "No READ_PRIVILEGED_PHONE_STATE nor READ_PRECISE_PHONE_STATE permission"
                    + ", check carrier privilege next.");
        }

        enforceCallingOrSelfCarrierPrivilege(context, subId, message);
    }

    /**
     * Make sure the caller (or self, if not processing an IPC) has carrier privileges.
     *
     * @throws SecurityException if the caller does not have the required privileges
     */
    public static void enforceCallingOrSelfCarrierPrivilege(
            Context context, int subId, String message) {
        // NOTE: It's critical that we explicitly pass the calling UID here rather than call
        // TelephonyManager#hasCarrierPrivileges directly, as the latter only works when called from
        // the phone process. When called from another process, it will check whether that process
        // has carrier privileges instead.
        enforceCarrierPrivilege(context, subId, Binder.getCallingUid(), message);
    }

    private static void enforceCarrierPrivilege(
            Context context, int subId, int uid, String message) {
        if (getCarrierPrivilegeStatus(context, subId, uid)
                != TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
            if (DBG) Log.e(LOG_TAG, "No Carrier Privilege.");
            throw new SecurityException(message);
        }
    }

    /** Returns whether the provided uid has carrier privileges for any active subscription ID. */
    private static boolean checkCarrierPrivilegeForAnySubId(Context context, int uid) {
        SubscriptionManager sm = (SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        int[] activeSubIds;
        final long identity = Binder.clearCallingIdentity();
        try {
            activeSubIds = sm.getCompleteActiveSubscriptionIdList();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        for (int activeSubId : activeSubIds) {
            if (getCarrierPrivilegeStatus(context, activeSubId, uid)
                    == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return true;
            }
        }
        return false;
    }

    private static int getCarrierPrivilegeStatus(Context context, int subId, int uid) {
        if (uid == Process.SYSTEM_UID || uid == Process.PHONE_UID) {
            // Skip the check if it's one of these special uids
            return TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
            return telephonyManager.createForSubscriptionId(subId).getCarrierPrivilegeStatus(uid);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Given a list of permissions, check to see if the caller has at least one of them. If the
     * caller has none of these permissions, throw a SecurityException.
     */
    public static void enforceAnyPermissionGranted(Context context, int uid, String message,
            String... permissions) {
        if (permissions.length == 0) return;
        boolean isGranted = false;
        for (String perm : permissions) {
            if (context.checkCallingOrSelfPermission(perm) == PERMISSION_GRANTED) {
                isGranted = true;
                break;
            }
        }

        if (isGranted) return;

        StringBuilder b = new StringBuilder(message);
        b.append(": Neither user ");
        b.append(uid);
        b.append(" nor current process has ");
        b.append(permissions[0]);
        for (int i = 1; i < permissions.length; i++) {
            b.append(" or ");
            b.append(permissions[i]);
        }
        throw new SecurityException(b.toString());
    }

    /**
     * Given a list of permissions, check to see if the caller has at least one of them granted. If
     * not, check to see if the caller has carrier privileges. If the caller does not have any of
     * these permissions, throw a SecurityException.
     */
    public static void enforceAnyPermissionGrantedOrCarrierPrivileges(Context context, int subId,
            int uid, String message, String... permissions) {
        enforceAnyPermissionGrantedOrCarrierPrivileges(
                context, subId, uid, false, message, permissions);
    }

    /**
     * Given a list of permissions, check to see if the caller has at least one of them granted. If
     * not, check to see if the caller has carrier privileges on the specified subscription (or any
     * subscription if {@code allowCarrierPrivilegeOnAnySub} is {@code true}. If the caller does not
     * have any of these permissions, throw a {@link SecurityException}.
     */
    public static void enforceAnyPermissionGrantedOrCarrierPrivileges(Context context, int subId,
            int uid, boolean allowCarrierPrivilegeOnAnySub, String message, String... permissions) {
        if (permissions.length == 0) return;
        boolean isGranted = false;
        for (String perm : permissions) {
            if (context.checkCallingOrSelfPermission(perm) == PERMISSION_GRANTED) {
                isGranted = true;
                break;
            }
        }

        if (isGranted) return;

        if (allowCarrierPrivilegeOnAnySub) {
            if (checkCarrierPrivilegeForAnySubId(context, uid)) return;
        } else {
            if (checkCarrierPrivilegeForSubId(context, subId)) return;
        }

        StringBuilder b = new StringBuilder(message);
        b.append(": Neither user ");
        b.append(uid);
        b.append(" nor current process has ");
        b.append(permissions[0]);
        for (int i = 1; i < permissions.length; i++) {
            b.append(" or ");
            b.append(permissions[i]);
        }
        b.append(" or carrier privileges. subId=" + subId + ", allowCarrierPrivilegeOnAnySub="
                + allowCarrierPrivilegeOnAnySub);
        throw new SecurityException(b.toString());
    }

    /**
     * Throws if the caller is not of a shell (or root) UID.
     *
     * @param callingUid pass Binder.callingUid().
     */
    public static void enforceShellOnly(int callingUid, String message) {
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            return; // okay
        }

        throw new SecurityException(message + ": Only shell user can call it");
    }

    /**
     * Returns the target SDK version number for a given package name.
     *
     * This call MUST be invoked before clearing the calling UID.
     *
     * @return target SDK if the package is found or INT_MAX.
     */
    public static int getTargetSdk(Context c, String packageName) {
        try {
            final ApplicationInfo ai = c.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0, UserHandle.getUserHandleForUid(Binder.getCallingUid()));
            if (ai != null) return ai.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException unexpected) {
            Log.e(LOG_TAG, "Failed to get package info for pkg="
                    + packageName + ", uid=" + Binder.getCallingUid());
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Check if calling user is associated with the given subscription.
     * Subscription-user association check is skipped if destination address is an emergency number.
     *
     * @param context Context
     * @param subId subscription ID
     * @param callerUserHandle caller user handle
     * @param destAddr destination address of the message
     * @return  true if destAddr is an emergency number
     * and return false if user is not associated with the subscription.
     */
    public static boolean checkSubscriptionAssociatedWithUser(@NonNull Context context, int subId,
            @NonNull UserHandle callerUserHandle, @NonNull String destAddr) {
        // Skip subscription-user association check for emergency numbers
        TelephonyManager tm = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        final long token = Binder.clearCallingIdentity();
        try {
            if (tm != null && tm.isEmergencyNumber(destAddr)) {
                Log.d(LOG_TAG, "checkSubscriptionAssociatedWithUser:"
                        + " destAddr is emergency number");
                return true;
            }
        } catch(Exception e) {
            Log.e(LOG_TAG, "Cannot verify if destAddr is an emergency number: " + e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return checkSubscriptionAssociatedWithUser(context, subId, callerUserHandle);
    }

    /**
     * Check if calling user is associated with the given subscription.
     * @param context Context
     * @param subId subscription ID
     * @param callerUserHandle caller user handle
     * @return  false if user is not associated with the subscription, or no record found of this
     * subscription.
     */
    public static boolean checkSubscriptionAssociatedWithUser(@NonNull Context context, int subId,
            @NonNull UserHandle callerUserHandle) {
        SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        final long token = Binder.clearCallingIdentity();
        try {
            if ((subManager != null) &&
                    (!subManager.isSubscriptionAssociatedWithUser(subId, callerUserHandle))) {
                // If subId is not associated with calling user, return false.
                Log.e(LOG_TAG, "User[User ID:" + callerUserHandle.getIdentifier()
                        + "] is not associated with Subscription ID:" + subId);
                return false;
            }
        } catch (IllegalArgumentException e) {
            // Found no record of this sub Id.
            Log.e(LOG_TAG, "Subscription[Subscription ID:" + subId + "] has no records on device");
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return true;
    }

    /**
     * Ensure the caller (or self, if not processing an IPC) has
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE} or
     * {@link android.Manifest.permission#READ_PHONE_NUMBERS}.
     *
     * @throws SecurityException if the caller does not have the required permission/privileges
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE
    })
    public static boolean checkCallingOrSelfReadPrivilegedPhoneStatePermissionOrReadPhoneNumber(
            Context context, int subId, String callingPackage, @Nullable String callingFeatureId,
            String message) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return false;
        }
        return (context.checkCallingOrSelfPermission(
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE) == PERMISSION_GRANTED
                || checkCallingOrSelfReadPhoneNumber(context, subId, callingPackage,
                callingFeatureId, message));
    }
}
