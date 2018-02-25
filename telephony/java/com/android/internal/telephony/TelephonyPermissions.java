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

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.ITelephony;

/** Utility class for Telephony permission enforcement. */
public final class TelephonyPermissions {
    private static final String LOG_TAG = "TelephonyPermissions";

    private static final boolean DBG = false;

    private TelephonyPermissions() {}

    /**
     * Check whether the caller (or self, if not processing an IPC) can read phone state.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has either the READ_PRIVILEGED_PHONE_STATE permission or the
     *       READ_PHONE_STATE runtime permission.
     *   <li>throw SecurityException: if the caller didn't declare any of these permissions, or, for
     *       apps which support runtime permissions, if the caller does not currently have any of
     *       these permissions.
     *   <li>return false: if the caller lacks all of these permissions and doesn't support runtime
     *       permissions. This implies that the user revoked the ability to read phone state
     *       manually (via AppOps). In this case we can't throw as it would break app compatibility,
     *       so we return false to indicate that the calling function should return dummy data.
     * </ul>
     */
    public static boolean checkCallingOrSelfReadPhoneState(
            Context context, String callingPackage, String message) {
        return checkReadPhoneState(context, Binder.getCallingPid(), Binder.getCallingUid(),
                callingPackage, message);
    }

    /**
     * Check whether the app with the given pid/uid can read phone state.
     *
     * <p>This method behaves in one of the following ways:
     * <ul>
     *   <li>return true: if the caller has either the READ_PRIVILEGED_PHONE_STATE permission or the
     *       READ_PHONE_STATE runtime permission.
     *   <li>throw SecurityException: if the caller didn't declare any of these permissions, or, for
     *       apps which support runtime permissions, if the caller does not currently have any of
     *       these permissions.
     *   <li>return false: if the caller lacks all of these permissions and doesn't support runtime
     *       permissions. This implies that the user revoked the ability to read phone state
     *       manually (via AppOps). In this case we can't throw as it would break app compatibility,
     *       so we return false to indicate that the calling function should return dummy data.
     * </ul>
     */
    public static boolean checkReadPhoneState(
            Context context, int pid, int uid, String callingPackage, String message) {
        try {
            context.enforcePermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, pid, uid, message);

            // SKIP checking for run-time permission since caller has PRIVILEGED permission
            return true;
        } catch (SecurityException privilegedPhoneStateException) {
            context.enforcePermission(
                    android.Manifest.permission.READ_PHONE_STATE, pid, uid, message);
        }

        // We have READ_PHONE_STATE permission, so return true as long as the AppOps bit hasn't been
        // revoked.
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return appOps.noteOp(AppOpsManager.OP_READ_PHONE_STATE, uid, callingPackage) ==
                AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Returns whether the caller can read phone numbers.
     *
     * <p>Besides apps with the ability to read phone state per {@link #checkReadPhoneState}, the
     * default SMS app and apps with READ_SMS or READ_PHONE_NUMBERS can also read phone numbers.
     */
    public static boolean checkCallingOrSelfReadPhoneNumber(
            Context context, String callingPackage, String message) {
        return checkReadPhoneNumber(
                context, Binder.getCallingPid(), Binder.getCallingUid(), callingPackage, message);
    }

    @VisibleForTesting
    public static boolean checkReadPhoneNumber(
            Context context, int pid, int uid, String callingPackage, String message) {
        // Default SMS app can always read it.
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps.noteOp(AppOpsManager.OP_WRITE_SMS, uid, callingPackage) ==
                AppOpsManager.MODE_ALLOWED) {
            return true;
        }

        // NOTE(b/73308711): If an app has one of the following AppOps bits explicitly revoked, they
        // will be denied access, even if they have another permission and AppOps bit if needed.

        // First, check if we can read the phone state.
        try {
            return checkReadPhoneState(context, pid, uid, callingPackage, message);
        } catch (SecurityException readPhoneStateSecurityException) {
        }
        // Can be read with READ_SMS too.
        try {
            context.enforcePermission(android.Manifest.permission.READ_SMS, pid, uid, message);
            int opCode = AppOpsManager.permissionToOpCode(android.Manifest.permission.READ_SMS);
            if (opCode != AppOpsManager.OP_NONE) {
                return appOps.noteOp(opCode, uid, callingPackage) == AppOpsManager.MODE_ALLOWED;
            } else {
                return true;
            }
        } catch (SecurityException readSmsSecurityException) {
        }
        // Can be read with READ_PHONE_NUMBERS too.
        try {
            context.enforcePermission(android.Manifest.permission.READ_PHONE_NUMBERS, pid, uid,
                    message);
            int opCode = AppOpsManager.permissionToOpCode(
                    android.Manifest.permission.READ_PHONE_NUMBERS);
            if (opCode != AppOpsManager.OP_NONE) {
                return appOps.noteOp(opCode, uid, callingPackage) == AppOpsManager.MODE_ALLOWED;
            } else {
                return true;
            }
        } catch (SecurityException readPhoneNumberSecurityException) {
        }

        throw new SecurityException(message + ": Neither user " + uid +
                " nor current process has " + android.Manifest.permission.READ_PHONE_STATE +
                ", " + android.Manifest.permission.READ_SMS + ", or " +
                android.Manifest.permission.READ_PHONE_NUMBERS);
    }

    /**
     * Ensure the caller (or self, if not processing an IPC) has MODIFY_PHONE_STATE (and is thus a
     * privileged app) or carrier privileges.
     *
     * @throws SecurityException if the caller does not have the required permission/privileges
     */
    public static void enforceCallingOrSelfModifyPermissionOrCarrierPrivilege(
            Context context, int subId, String message) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (DBG) Rlog.d(LOG_TAG, "No modify permission, check carrier privilege next.");
        enforceCallingOrSelfCarrierPrivilege(subId, message);
    }

    /**
     * Make sure the caller (or self, if not processing an IPC) has carrier privileges.
     *
     * @throws SecurityException if the caller does not have the required privileges
     */
    public static void enforceCallingOrSelfCarrierPrivilege(int subId, String message) {
        // NOTE: It's critical that we explicitly pass the calling UID here rather than call
        // TelephonyManager#hasCarrierPrivileges directly, as the latter only works when called from
        // the phone process. When called from another process, it will check whether that process
        // has carrier privileges instead.
        enforceCarrierPrivilege(subId, Binder.getCallingUid(), message);
    }

    private static void enforceCarrierPrivilege(int subId, int uid, String message) {
        if (getCarrierPrivilegeStatus(subId, uid) !=
                TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
            if (DBG) Rlog.e(LOG_TAG, "No Carrier Privilege.");
            throw new SecurityException(message);
        }
    }

    private static int getCarrierPrivilegeStatus(int subId, int uid) {
        ITelephony telephony =
                ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE));
        try {
            if (telephony != null) {
                return telephony.getCarrierPrivilegeStatusForUid(subId, uid);
            }
        } catch (RemoteException e) {
            // Fallback below.
        }
        Rlog.e(LOG_TAG, "Phone process is down, cannot check carrier privileges");
        return TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }
}
