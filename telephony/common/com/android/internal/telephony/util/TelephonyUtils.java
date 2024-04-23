/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.telephony.util;

import static android.telephony.Annotation.DataState;
import static android.telephony.NetworkRegistrationInfo.FIRST_SERVICE_TYPE;
import static android.telephony.NetworkRegistrationInfo.LAST_SERVICE_TYPE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Telephony;
import android.provider.Telephony.Carriers.EditStatus;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.ITelephony;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class provides various util functions
 */
public final class TelephonyUtils {
    private static final String LOG_TAG = "TelephonyUtils";

    public static boolean IS_USER = "user".equals(android.os.Build.TYPE);
    public static boolean IS_DEBUGGABLE = SystemProperties.getInt("ro.debuggable", 0) == 1;

    public static final Executor DIRECT_EXECUTOR = Runnable::run;

    /**
     * Verify that caller holds {@link android.Manifest.permission#DUMP}.
     *
     * @return true if access should be granted.
     */
    public static boolean checkDumpPermission(Context context, String tag, PrintWriter pw) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump " + tag + " from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " due to missing android.permission.DUMP permission");
            return false;
        } else {
            return true;
        }
    }

    /** Returns an empty string if the input is {@code null}. */
    public static String emptyIfNull(@Nullable String str) {
        return str == null ? "" : str;
    }

    /** Returns an empty list if the input is {@code null}. */
    public static @NonNull <T> List<T> emptyIfNull(@Nullable List<T> cur) {
        return cur == null ? Collections.emptyList() : cur;
    }

    /**
     * Returns a {@link ComponentInfo} from the {@link ResolveInfo},
     * or throws an {@link IllegalStateException} if not available.
     */
    public static ComponentInfo getComponentInfo(@NonNull ResolveInfo resolveInfo) {
        if (resolveInfo.activityInfo != null) return resolveInfo.activityInfo;
        if (resolveInfo.serviceInfo != null) return resolveInfo.serviceInfo;
        if (resolveInfo.providerInfo != null) return resolveInfo.providerInfo;
        throw new IllegalStateException("Missing ComponentInfo!");
    }

    /**
     * Convenience method for running the provided action enclosed in
     * {@link Binder#clearCallingIdentity}/{@link Binder#restoreCallingIdentity}
     *
     * Any exception thrown by the given action will need to be handled by caller.
     *
     */
    public static void runWithCleanCallingIdentity(
            @NonNull Runnable action) {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            action.run();
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Convenience method for running the provided action in the provided
     * executor enclosed in
     * {@link Binder#clearCallingIdentity}/{@link Binder#restoreCallingIdentity}
     *
     * Any exception thrown by the given action will need to be handled by caller.
     *
     */
    public static void runWithCleanCallingIdentity(
            @NonNull Runnable action, @NonNull Executor executor) {
        if (action != null) {
            if (executor != null) {
                executor.execute(() -> runWithCleanCallingIdentity(action));
            } else {
                runWithCleanCallingIdentity(action);
            }
        }
    }


    /**
     * Convenience method for running the provided action enclosed in
     * {@link Binder#clearCallingIdentity}/{@link Binder#restoreCallingIdentity} and return
     * the result.
     *
     * Any exception thrown by the given action will need to be handled by caller.
     *
     */
    public static <T> T runWithCleanCallingIdentity(
            @NonNull Supplier<T> action) {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            return action.get();
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Filter values in bundle to only basic types.
     */
    public static Bundle filterValues(Bundle bundle) {
        Bundle ret = new Bundle(bundle);
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if ((value instanceof Integer) || (value instanceof Long)
                    || (value instanceof Double) || (value instanceof String)
                    || (value instanceof int[]) || (value instanceof long[])
                    || (value instanceof double[]) || (value instanceof String[])
                    || (value instanceof PersistableBundle) || (value == null)
                    || (value instanceof Boolean) || (value instanceof boolean[])) {
                continue;
            }
            if (value instanceof Bundle) {
                ret.putBundle(key, filterValues((Bundle) value));
                continue;
            }
            if (value.getClass().getName().startsWith("android.")) {
                continue;
            }
            ret.remove(key);
        }
        return ret;
    }

    /** Wait for latch to trigger */
    public static void waitUntilReady(CountDownLatch latch, long timeoutMs) {
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Convert data state to string
     *
     * @return The data state in string format.
     */
    public static String dataStateToString(@DataState int state) {
        switch (state) {
            case TelephonyManager.DATA_DISCONNECTED: return "DISCONNECTED";
            case TelephonyManager.DATA_CONNECTING: return "CONNECTING";
            case TelephonyManager.DATA_CONNECTED: return "CONNECTED";
            case TelephonyManager.DATA_SUSPENDED: return "SUSPENDED";
            case TelephonyManager.DATA_DISCONNECTING: return "DISCONNECTING";
            case TelephonyManager.DATA_HANDOVER_IN_PROGRESS: return "HANDOVERINPROGRESS";
            case TelephonyManager.DATA_UNKNOWN: return "UNKNOWN";
        }
        // This is the error case. The well-defined value for UNKNOWN is -1.
        return "UNKNOWN(" + state + ")";
    }

    /**
     * Convert mobile data policy to string.
     *
     * @param mobileDataPolicy The mobile data policy.
     * @return The mobile data policy in string format.
     */
    public static @NonNull String mobileDataPolicyToString(
            @TelephonyManager.MobileDataPolicy int mobileDataPolicy) {
        switch (mobileDataPolicy) {
            case TelephonyManager.MOBILE_DATA_POLICY_DATA_ON_NON_DEFAULT_DURING_VOICE_CALL:
                return "DATA_ON_NON_DEFAULT_DURING_VOICE_CALL";
            case TelephonyManager.MOBILE_DATA_POLICY_MMS_ALWAYS_ALLOWED:
                return "MMS_ALWAYS_ALLOWED";
            case TelephonyManager.MOBILE_DATA_POLICY_AUTO_DATA_SWITCH:
                return "AUTO_DATA_SWITCH";
            default:
                return "UNKNOWN(" + mobileDataPolicy + ")";
        }
    }

    /**
     * Convert APN edited status to string.
     *
     * @param apnEditStatus APN edited status.
     * @return APN edited status in string format.
     */
    public static @NonNull String apnEditedStatusToString(@EditStatus int apnEditStatus) {
        return switch (apnEditStatus) {
            case Telephony.Carriers.UNEDITED -> "UNEDITED";
            case Telephony.Carriers.USER_EDITED -> "USER_EDITED";
            case Telephony.Carriers.USER_DELETED -> "USER_DELETED";
            case Telephony.Carriers.CARRIER_EDITED -> "CARRIER_EDITED";
            case Telephony.Carriers.CARRIER_DELETED -> "CARRIER_DELETED";
            default -> "UNKNOWN(" + apnEditStatus + ")";
        };
    }

    /**
     * Utility method to get user handle associated with this subscription.
     *
     * This method should be used internally as it returns null instead of throwing
     * IllegalArgumentException or IllegalStateException.
     *
     * @param context Context object
     * @param subId the subId of the subscription.
     * @return userHandle associated with this subscription
     * or {@code null} if:
     * 1. subscription is not associated with any user
     * 2. subId is invalid.
     * 3. subscription service is not available.
     *
     * @throws SecurityException if the caller doesn't have permissions required.
     */
    @Nullable
    public static UserHandle getSubscriptionUserHandle(Context context, int subId) {
        UserHandle userHandle = null;
        SubscriptionManager subManager =  context.getSystemService(SubscriptionManager.class);
        if ((subManager != null) && (SubscriptionManager.isValidSubscriptionId(subId))) {
            userHandle = subManager.getSubscriptionUserHandle(subId);
        }
        return userHandle;
    }

    /**
     * Show switch to managed profile dialog if subscription is associated with managed profile.
     *
     * @param context Context object
     * @param subId subscription id
     * @param callingUid uid for the calling app
     * @param callingPackage package name of the calling app
     */
    public static void showSwitchToManagedProfileDialogIfAppropriate(Context context,
            int subId, int callingUid, String callingPackage) {
        final long token = Binder.clearCallingIdentity();
        try {
            UserHandle callingUserHandle = UserHandle.getUserHandleForUid(callingUid);
            // We only want to show this dialog, while user actually trying to send the message from
            // a messaging app, in other cases this dialog don't make sense.
            if (!TelephonyUtils.isUidForeground(context, callingUid)
                    || !TelephonyUtils.isPackageSMSRoleHolderForUser(context, callingPackage,
                    callingUserHandle)) {
                return;
            }

            SubscriptionManager subscriptionManager = context.getSystemService(
                    SubscriptionManager.class);
            if (!subscriptionManager.isActiveSubscriptionId(subId)) {
                Log.e(LOG_TAG, "Tried to send message with an inactive subscription " + subId);
                return;
            }
            UserHandle associatedUserHandle = subscriptionManager.getSubscriptionUserHandle(subId);
            UserManager um = context.getSystemService(UserManager.class);

            if (associatedUserHandle != null && um.isManagedProfile(
                    associatedUserHandle.getIdentifier())) {

                ITelephony iTelephony = ITelephony.Stub.asInterface(
                        TelephonyFrameworkInitializer
                                .getTelephonyServiceManager()
                                .getTelephonyServiceRegisterer()
                                .get());
                if (iTelephony != null) {
                    try {
                        iTelephony.showSwitchToManagedProfileDialog();
                    } catch (RemoteException e) {
                        Log.e(LOG_TAG, "Failed to launch switch to managed profile dialog.");
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isUidForeground(Context context, int uid) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        boolean result = am != null && am.getUidImportance(uid)
                == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        return result;
    }

    private static boolean isPackageSMSRoleHolderForUser(Context context, String callingPackage,
            UserHandle user) {
        RoleManager roleManager = context.getSystemService(RoleManager.class);
        final List<String> smsRoleHolder = roleManager.getRoleHoldersAsUser(
                RoleManager.ROLE_SMS, user);

        // ROLE_SMS is an exclusive role per user, so there would just be one entry in the
        // retuned list if not empty
        if (!smsRoleHolder.isEmpty() && callingPackage.equals(smsRoleHolder.get(0))) {
            return true;
        }
        return false;

    }

    /**
     * @param input string that want to be compared.
     * @param regex string that express regular expression
     * @return {@code true} if matched  {@code false} otherwise.
     */
    private static boolean isValidPattern(@Nullable String input, @Nullable String regex) {
        if (TextUtils.isEmpty(input) || TextUtils.isEmpty(regex)) {
            return false;
        }
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);
        if (!matcher.matches()) {
            return false;
        }
        return true;
    }

    /**
     * @param countryCode two letters country code based on the ISO 3166-1.
     * @return {@code true} if the countryCode is valid {@code false} otherwise.
     */
    public static boolean isValidCountryCode(@Nullable String countryCode) {
        return isValidPattern(countryCode, "^[A-Za-z]{2}$");
    }

    /**
     * @param plmn target plmn for validation.
     * @return {@code true} if the target plmn is valid {@code false} otherwise.
     */
    public static boolean isValidPlmn(@Nullable String plmn) {
        return isValidPattern(plmn, "^(?:[0-9]{3})(?:[0-9]{2}|[0-9]{3})$");
    }

    /**
     * @param serviceType target serviceType for validation.
     * @return {@code true} if the target serviceType is valid {@code false} otherwise.
     */
    public static boolean isValidService(int serviceType) {
        if (serviceType < FIRST_SERVICE_TYPE || serviceType > LAST_SERVICE_TYPE) {
            return false;
        }
        return true;
    }
}
