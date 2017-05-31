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

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.util.Slog;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Helper functions for dumping the state of system services.
 */
public final class DumpUtils {
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
                switch (appOps.checkOpNoThrow(AppOpsManager.OP_GET_USAGE_STATS, uid, pkg)) {
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
}
