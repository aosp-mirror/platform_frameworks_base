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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * This class provides various util functions
 */
public final class TelephonyUtils {
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
     * Convert display name source to string.
     *
     * @param source The display name source.
     * @return The display name source in string format.
     */
    @NonNull
    public static String displayNameSourceToString(
            @SubscriptionManager.SimDisplayNameSource int source) {
        switch (source) {
            case SubscriptionManager.NAME_SOURCE_UNKNOWN: return "UNKNOWN";
            case SubscriptionManager.NAME_SOURCE_CARRIER_ID: return "CARRIER_ID";
            case SubscriptionManager.NAME_SOURCE_SIM_SPN: return "SIM_SPN";
            case SubscriptionManager.NAME_SOURCE_USER_INPUT: return "USER_INPUT";
            case SubscriptionManager.NAME_SOURCE_CARRIER: return "CARRIER";
            case SubscriptionManager.NAME_SOURCE_SIM_PNN: return "SIM_PNN";
            default:
                return "UNKNOWN(" + source + ")";
        }
    }

    /**
     * Convert subscription type to string.
     *
     * @param type The subscription type.
     * @return The subscription type in string format.
     */
    @NonNull
    public static String subscriptionTypeToString(@SubscriptionManager.SubscriptionType int type) {
        switch (type) {
            case SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM: return "LOCAL_SIM";
            case SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM: return "REMOTE_SIM";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }

    /**
     * Convert usage setting to string.
     *
     * @param usageSetting Usage setting.
     * @return The usage setting in string format.
     */
    @NonNull
    public static String usageSettingToString(@SubscriptionManager.UsageSetting int usageSetting) {
        switch (usageSetting) {
            case SubscriptionManager.USAGE_SETTING_UNKNOWN: return "UNKNOWN";
            case SubscriptionManager.USAGE_SETTING_DEFAULT: return "DEFAULT";
            case SubscriptionManager.USAGE_SETTING_VOICE_CENTRIC: return "VOICE_CENTRIC";
            case SubscriptionManager.USAGE_SETTING_DATA_CENTRIC: return "DATA_CENTRIC";
            default:
                return "UNKNOWN(" + usageSetting + ")";
        }
    }
}
