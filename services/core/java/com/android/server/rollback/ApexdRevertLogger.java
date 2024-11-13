/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.rollback;

import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH_DURING_BOOT;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.crashrecovery.proto.CrashRecoveryStatsLog;

import java.util.List;
import java.util.Set;

/**
 * This class handles the logic for logging Apexd-triggered rollback events.
 * TODO: b/354112511 Refactor to have a separate metric for ApexdReverts
 */
public final class ApexdRevertLogger {
    private static final String TAG = "WatchdogRollbackLogger";

    private static final String LOGGING_PARENT_KEY = "android.content.pm.LOGGING_PARENT";

    /**
     * Logs that one or more apexd reverts have occurred, along with the crashing native process
     * that caused apexd to revert during boot.
     *
     * @param context the context to use when determining the log packages
     * @param failedPackageNames a list of names of packages which were reverted
     * @param failingNativeProcess the crashing native process which caused a revert
     */
    public static void logApexdRevert(Context context, @NonNull List<String> failedPackageNames,
            @NonNull String failingNativeProcess) {
        Set<VersionedPackage> logPackages = getLogPackages(context, failedPackageNames);
        for (VersionedPackage logPackage: logPackages) {
            logEvent(logPackage,
                    failingNativeProcess);
        }
    }

    /**
     * Gets the set of parent packages for a given set of failed package names. In the case that
     * multiple sessions have failed, we want to log failure for each of the parent packages.
     * Even if multiple failed packages have the same parent, we only log the parent package once.
     */
    private static Set<VersionedPackage> getLogPackages(Context context,
            @NonNull List<String> failedPackageNames) {
        Set<VersionedPackage> parentPackages = new ArraySet<>();
        for (String failedPackageName: failedPackageNames) {
            parentPackages.add(getLogPackage(context, new VersionedPackage(failedPackageName, 0)));
        }
        return parentPackages;
    }

    /**
     * Returns the logging parent of a given package if it exists, {@code null} otherwise.
     *
     * The logging parent is defined by the {@code android.content.pm.LOGGING_PARENT} field in the
     * metadata of a package's AndroidManifest.xml.
     */
    @VisibleForTesting
    @Nullable
    private static VersionedPackage getLogPackage(Context context,
            @NonNull VersionedPackage failingPackage) {
        String logPackageName;
        VersionedPackage loggingParent;
        logPackageName = getLoggingParentName(context, failingPackage.getPackageName());
        if (logPackageName == null) {
            return null;
        }
        try {
            loggingParent = new VersionedPackage(logPackageName, context.getPackageManager()
                    .getPackageInfo(logPackageName, 0 /* flags */).getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return loggingParent;
    }

    @Nullable
    private static String getLoggingParentName(Context context, @NonNull String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            int flags = PackageManager.MATCH_APEX | PackageManager.GET_META_DATA;
            ApplicationInfo ai = packageManager.getPackageInfo(packageName, flags).applicationInfo;
            if (ai == null || ai.metaData == null) {
                return null;
            }
            return ai.metaData.getString(LOGGING_PARENT_KEY);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to discover logging parent package: " + packageName, e);
            return null;
        }
    }

    /**
     * Log a Apexd rollback event to statsd.
     *
     * @param logPackage         the package to associate the rollback with.
     * @param failingPackageName the failing package or process which triggered the rollback.
     */
    private static void logEvent(@Nullable VersionedPackage logPackage,
            @NonNull String failingPackageName) {
        Slog.i(TAG, "Watchdog event occurred with type: ROLLBACK_SUCCESS"
                + " logPackage: " + logPackage
                + " rollbackReason: REASON_NATIVE_CRASH_DURING_BOOT"
                + " failedPackageName: " + failingPackageName);
        CrashRecoveryStatsLog.write(
                WATCHDOG_ROLLBACK_OCCURRED,
                WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                (logPackage != null) ? logPackage.getPackageName() : "",
                (logPackage != null) ? logPackage.getVersionCode() : 0,
                WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH_DURING_BOOT,
                failingPackageName,
                new byte[]{});

        logTestProperties(logPackage, failingPackageName);
    }

    /**
     * Writes properties which will be used by rollback tests to check if rollback has occurred
     * have occurred.
     *
     * persist.sys.rollbacktest.enabled: true if rollback tests are running
     * persist.sys.rollbacktest.ROLLBACK_SUCCESS.logPackage: the package to associate the rollback
     * persist.sys.rollbacktest.ROLLBACK_SUCCESS.rollbackReason: the reason Apexd triggered it
     * persist.sys.rollbacktest.ROLLBACK_SUCCESS.failedPackageName: the failing package or process
     * which triggered the rollback
     */
    private static void logTestProperties(@Nullable VersionedPackage logPackage,
            @NonNull String failingPackageName) {
        // This property should be on only during the tests
        final String prefix = "persist.sys.rollbacktest.";
        if (!SystemProperties.getBoolean(prefix + "enabled", false)) {
            return;
        }
        String key = prefix +  "ROLLBACK_SUCCESS";
        SystemProperties.set(key, String.valueOf(true));
        SystemProperties.set(key + ".logPackage", logPackage != null ? logPackage.toString() : "");
        SystemProperties.set(key + ".rollbackReason", "REASON_NATIVE_CRASH_DURING_BOOT");
        SystemProperties.set(key + ".failedPackageName", failingPackageName);
    }
}
