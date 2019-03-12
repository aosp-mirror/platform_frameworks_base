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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.ParcelUuid;

import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Simple Surface for Telephony to notify a loosely-coupled debugger of particular issues.
 *
 * AnomalyReporter allows an optional external logging component to receive events detected by
 * the framework and take action. This log surface is designed to provide maximium flexibility
 * to the receiver of these events. Envisioned use cases of this include notifying a vendor
 * component of: an event that necessitates (timely) log collection on non-AOSP components;
 * notifying a vendor component of a rare event that should prompt further action such as a
 * bug report or user intervention for debug purposes.
 *
 * <p>This surface is not intended to enable a diagnostic monitor, nor is it intended to support
 * streaming logs.
 *
 * @hide
 */
public final class AnomalyReporter {
    private static final String TAG = "AnomalyReporter";

    private static Context sContext = null;

    private static Map<UUID, Integer> sEvents = new ConcurrentHashMap<>();

    /*
     * Because this is only supporting system packages, once we find a package, it will be the
     * same package until the next system upgrade. Thus, to save time in processing debug events
     * we can cache this info and skip the resolution process after it's done the first time.
     */
    private static String sDebugPackageName = null;

    private AnomalyReporter() {};

    /**
     * If enabled, build and send an intent to a Debug Service for logging.
     *
     * This method sends the {@link TelephonyManager#ACTION_ANOMALY_REPORTED} broadcast, which is
     * system protected. Invoking this method unless you are the system will result in an error.
     *
     * @param eventId a fixed event ID that will be sent for each instance of the same event. This
     *        ID should be generated randomly.
     * @param description an optional description, that if included will be used as the subject for
     *        identification and discussion of this event. This description should ideally be
     *        static and must not contain any sensitive information (especially PII).
     */
    public static void reportAnomaly(@NonNull UUID eventId, String description) {
        if (sContext == null) {
            Rlog.w(TAG, "AnomalyReporter not yet initialized, dropping event=" + eventId);
            return;
        }

        // If this event has already occurred, skip sending intents for it; regardless log its
        // invocation here.
        Integer count = sEvents.containsKey(eventId) ? sEvents.get(eventId) + 1 : 1;
        sEvents.put(eventId, count);
        if (count > 1) return;

        // Even if we are initialized, that doesn't mean that a package name has been found.
        // This is normal in many cases, such as when no debug package is installed on the system,
        // so drop these events silently.
        if (sDebugPackageName == null) return;

        Intent dbgIntent = new Intent(TelephonyManager.ACTION_ANOMALY_REPORTED);
        dbgIntent.putExtra(TelephonyManager.EXTRA_ANOMALY_ID, new ParcelUuid(eventId));
        if (description != null) {
            dbgIntent.putExtra(TelephonyManager.EXTRA_ANOMALY_DESCRIPTION, description);
        }
        dbgIntent.setPackage(sDebugPackageName);
        sContext.sendBroadcast(dbgIntent, android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
    }

    /**
     * Initialize the AnomalyReporter with the current context.
     *
     * This method must be invoked before any calls to reportAnomaly() will succeed. This method
     * should only be invoked at most once.
     *
     * @param context a Context object used to initialize this singleton AnomalyReporter in
     *        the current process.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public static void initialize(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("AnomalyReporter needs a non-null context.");
        }

        // Ensure that this context has sufficient permissions to send debug events.
        context.enforceCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE,
                "This app does not have privileges to send debug events");

        sContext = context;

        // Check to see if there is a valid debug package; if there are multiple, that's a config
        // error, so just take the first one.
        PackageManager pm = sContext.getPackageManager();
        if (pm == null) return;
        List<ResolveInfo> packages = pm.queryBroadcastReceivers(
                new Intent(TelephonyManager.ACTION_ANOMALY_REPORTED),
                PackageManager.MATCH_SYSTEM_ONLY
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if (packages == null || packages.isEmpty()) return;
        if (packages.size() > 1) {
            Rlog.e(TAG, "Multiple Anomaly Receivers installed.");
        }

        for (ResolveInfo r : packages) {
            if (r.activityInfo == null
                    || pm.checkPermission(
                            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                            r.activityInfo.packageName)
                    != PackageManager.PERMISSION_GRANTED) {
                Rlog.w(TAG,
                        "Found package without proper permissions or no activity"
                                + r.activityInfo.packageName);
                continue;
            }
            Rlog.d(TAG, "Found a valid package " + r.activityInfo.packageName);
            sDebugPackageName = r.activityInfo.packageName;
            break;
        }
        // Initialization may only be performed once.
    }

    /** Dump the contents of the AnomalyReporter */
    public static void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        if (sContext == null) return;
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        sContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, "Requires DUMP");
        pw.println("Initialized=" + (sContext != null ? "Yes" : "No"));
        pw.println("Debug Package=" + sDebugPackageName);
        pw.println("Anomaly Counts:");
        pw.increaseIndent();
        for (UUID event : sEvents.keySet()) {
            pw.println(event + ": " + sEvents.get(event));
        }
        pw.decreaseIndent();
        pw.flush();
    }
}
