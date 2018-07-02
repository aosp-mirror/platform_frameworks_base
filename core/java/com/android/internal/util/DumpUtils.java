/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Slog;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Helper functions for dumping the state of system services.
 * Test:
 atest FrameworksCoreTests:DumpUtilsTest
 */
public final class DumpUtils {

    /**
     * List of component names that should be dumped in the bug report critical section.
     *
     * @hide
     */
    public static final ComponentName[] CRITICAL_SECTION_COMPONENTS = {
            new ComponentName("com.android.systemui", "com.android.systemui.SystemUIService")
    };
    private static final String TAG = "DumpUtils";
    private static final boolean DEBUG = false;

    private DumpUtils() {
    }

    /**
     * Helper for dumping state owned by a handler thread.
     *
     * Because the caller might be holding an important lock that the handler is
     * trying to acquire, we use a short timeout to avoid deadlocks.  The process
     * is inelegant but this function is only used for debugging purposes.
     */
    public static void dumpAsync(Handler handler, final Dump dump, PrintWriter pw,
            final String prefix, long timeout) {
        final StringWriter sw = new StringWriter();
        if (handler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                PrintWriter lpw = new FastPrintWriter(sw);
                dump.dump(lpw, prefix);
                lpw.close();
            }
        }, timeout)) {
            pw.print(sw.toString());
        } else {
            pw.println("... timed out");
        }
    }

    public interface Dump {
        void dump(PrintWriter pw, String prefix);
    }

    private static void logMessage(PrintWriter pw, String msg) {
        if (DEBUG) Slog.v(TAG, msg);
        pw.println(msg);
    }

    /**
     * Verify that caller holds {@link android.Manifest.permission#DUMP}.
     *
     * @return true if access should be granted.
     * @hide
     */
    public static boolean checkDumpPermission(Context context, String tag, PrintWriter pw) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            logMessage(pw, "Permission Denial: can't dump " + tag + " from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " due to missing android.permission.DUMP permission");
            return false;
        } else {
            return true;
        }
    }

    /**
     * Verify that caller holds
     * {@link android.Manifest.permission#PACKAGE_USAGE_STATS} and that they
     * have {@link AppOpsManager#OP_GET_USAGE_STATS} access.
     *
     * @return true if access should be granted.
     * @hide
     */
    public static boolean checkUsageStatsPermission(Context context, String tag, PrintWriter pw) {
        // System internals always get access
        final int uid = Binder.getCallingUid();
        switch (uid) {
            case android.os.Process.ROOT_UID:
            case android.os.Process.SYSTEM_UID:
            case android.os.Process.SHELL_UID:
            case android.os.Process.INCIDENTD_UID:
                return true;
        }

        // Caller always needs to hold permission
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                != PackageManager.PERMISSION_GRANTED) {
            logMessage(pw, "Permission Denial: can't dump " + tag + " from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " due to missing android.permission.PACKAGE_USAGE_STATS permission");
            return false;
        }

        // And finally, caller needs to have appops access; this is totally
        // hacky, but it's the easiest way to wire this up without retrofitting
        // Binder.dump() to pass through package names.
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final String[] pkgs = context.getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) {
            for (String pkg : pkgs) {
                switch (appOps.noteOpNoThrow(AppOpsManager.OP_GET_USAGE_STATS, uid, pkg)) {
                    case AppOpsManager.MODE_ALLOWED:
                        if (DEBUG) Slog.v(TAG, "Found package " + pkg + " with "
                                + "android:get_usage_stats allowed");
                        return true;
                    case AppOpsManager.MODE_DEFAULT:
                        if (DEBUG) Slog.v(TAG, "Found package " + pkg + " with "
                                + "android:get_usage_stats default");
                        return true;
                }
            }
        }

        logMessage(pw, "Permission Denial: can't dump " + tag + " from from pid="
                + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                + " due to android:get_usage_stats app-op not allowed");
        return false;
    }

    /**
     * Verify that caller holds both {@link android.Manifest.permission#DUMP}
     * and {@link android.Manifest.permission#PACKAGE_USAGE_STATS}, and that
     * they have {@link AppOpsManager#OP_GET_USAGE_STATS} access.
     *
     * @return true if access should be granted.
     * @hide
     */
    public static boolean checkDumpAndUsageStatsPermission(Context context, String tag,
            PrintWriter pw) {
        return checkDumpPermission(context, tag, pw) && checkUsageStatsPermission(context, tag, pw);
    }

    /**
     * Return whether a package name is considered to be part of the platform.
     * @hide
     */
    public static boolean isPlatformPackage(@Nullable String packageName) {
        return (packageName != null)
                && (packageName.equals("android")
                    || packageName.startsWith("android.")
                    || packageName.startsWith("com.android."));
    }

    /**
     * Return whether a package name is considered to be part of the platform.
     * @hide
     */
    public static boolean isPlatformPackage(@Nullable ComponentName cname) {
        return (cname != null) && isPlatformPackage(cname.getPackageName());
    }

    /**
     * Return whether a package name is considered to be part of the platform.
     * @hide
     */
    public static boolean isPlatformPackage(@Nullable ComponentName.WithComponentName wcn) {
        return (wcn != null) && isPlatformPackage(wcn.getComponentName());
    }

    /**
     * Return whether a package name is NOT considered to be part of the platform.
     * @hide
     */
    public static boolean isNonPlatformPackage(@Nullable String packageName) {
        return (packageName != null) && !isPlatformPackage(packageName);
    }

    /**
     * Return whether a package name is NOT considered to be part of the platform.
     * @hide
     */
    public static boolean isNonPlatformPackage(@Nullable ComponentName cname) {
        return (cname != null) && isNonPlatformPackage(cname.getPackageName());
    }

    /**
     * Return whether a package name is NOT considered to be part of the platform.
     * @hide
     */
    public static boolean isNonPlatformPackage(@Nullable ComponentName.WithComponentName wcn) {
        return (wcn != null) && !isPlatformPackage(wcn.getComponentName());
    }

    /**
     * Return whether a package should be dumped in the critical section.
     */
    private static boolean isCriticalPackage(@Nullable ComponentName cname) {
        if (cname == null) {
            return false;
        }

        for (int i = 0; i < CRITICAL_SECTION_COMPONENTS.length; i++) {
            if (cname.equals(CRITICAL_SECTION_COMPONENTS[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return whether a package name is considered to be part of the platform and in the critical
     * section.
     *
     * @hide
     */
    public static boolean isPlatformCriticalPackage(@Nullable ComponentName.WithComponentName wcn) {
        return (wcn != null) && isPlatformPackage(wcn.getComponentName()) &&
                isCriticalPackage(wcn.getComponentName());
    }

    /**
     * Return whether a package name is considered to be part of the platform but not in the the
     * critical section.
     *
     * @hide
     */
    public static boolean isPlatformNonCriticalPackage(
            @Nullable ComponentName.WithComponentName wcn) {
        return (wcn != null) && isPlatformPackage(wcn.getComponentName()) &&
                !isCriticalPackage(wcn.getComponentName());
    }

    /**
     * Used for dumping providers and services. Return a predicate for a given filter string.
     * @hide
     */
    public static <TRec extends ComponentName.WithComponentName> Predicate<TRec> filterRecord(
            @Nullable String filterString) {

        if (TextUtils.isEmpty(filterString)) {
            return rec -> false;
        }

        // Dump all?
        if ("all".equals(filterString)) {
            return Objects::nonNull;
        }

        // Dump all platform?
        if ("all-platform".equals(filterString)) {
            return DumpUtils::isPlatformPackage;
        }

        // Dump all non-platform?
        if ("all-non-platform".equals(filterString)) {
            return DumpUtils::isNonPlatformPackage;
        }

        // Dump all platform-critical?
        if ("all-platform-critical".equals(filterString)) {
            return DumpUtils::isPlatformCriticalPackage;
        }

        // Dump all platform-non-critical?
        if ("all-platform-non-critical".equals(filterString)) {
            return DumpUtils::isPlatformNonCriticalPackage;
        }

        // Is the filter a component name? If so, do an exact match.
        final ComponentName filterCname = ComponentName.unflattenFromString(filterString);
        if (filterCname != null) {
            // Do exact component name check.
            return rec -> (rec != null) && filterCname.equals(rec.getComponentName());
        }

        // Otherwise, do a partial match against the component name.
        // Also if the filter is a hex-decimal string, do the object ID match too.
        final int id = ParseUtils.parseIntWithBase(filterString, 16, -1);
        return rec -> {
            final ComponentName cn = rec.getComponentName();
            return ((id != -1) && (System.identityHashCode(rec) == id))
                    || cn.flattenToString().toLowerCase().contains(filterString.toLowerCase());
        };
    }
}

