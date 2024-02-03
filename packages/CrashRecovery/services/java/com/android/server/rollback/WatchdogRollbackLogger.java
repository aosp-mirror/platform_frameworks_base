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

package com.android.server.rollback;

import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH_DURING_BOOT;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE;
import static com.android.server.crashrecovery.proto.CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.PackageWatchdog;
import com.android.server.crashrecovery.proto.CrashRecoveryStatsLog;

import java.util.List;
import java.util.Set;

/**
 * This class handles the logic for logging Watchdog-triggered rollback events.
 */
public final class WatchdogRollbackLogger {
    private static final String TAG = "WatchdogRollbackLogger";

    private static final String LOGGING_PARENT_KEY = "android.content.pm.LOGGING_PARENT";

    private WatchdogRollbackLogger() {
    }

    @Nullable
    private static String getLoggingParentName(Context context, @NonNull String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            int flags = PackageManager.MATCH_APEX | PackageManager.GET_META_DATA;
            ApplicationInfo ai = packageManager.getPackageInfo(packageName, flags).applicationInfo;
            if (ai.metaData == null) {
                return null;
            }
            return ai.metaData.getString(LOGGING_PARENT_KEY);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to discover logging parent package: " + packageName, e);
            return null;
        }
    }

    /**
     * Returns the logging parent of a given package if it exists, {@code null} otherwise.
     *
     * The logging parent is defined by the {@code android.content.pm.LOGGING_PARENT} field in the
     * metadata of a package's AndroidManifest.xml.
     */
    @VisibleForTesting
    @Nullable
    static VersionedPackage getLogPackage(Context context,
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


    static void logRollbackStatusOnBoot(Context context, int rollbackId, String logPackageName,
            List<RollbackInfo> recentlyCommittedRollbacks) {
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

        RollbackInfo rollback = null;
        for (RollbackInfo info : recentlyCommittedRollbacks) {
            if (rollbackId == info.getRollbackId()) {
                rollback = info;
                break;
            }
        }

        if (rollback == null) {
            Slog.e(TAG, "rollback info not found for last staged rollback: " + rollbackId);
            return;
        }

        // Use the version of the logging parent that was installed before
        // we rolled back for logging purposes.
        VersionedPackage oldLoggingPackage = null;
        if (!TextUtils.isEmpty(logPackageName)) {
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                if (logPackageName.equals(packageRollback.getPackageName())) {
                    oldLoggingPackage = packageRollback.getVersionRolledBackFrom();
                    break;
                }
            }
        }

        int sessionId = rollback.getCommittedSessionId();
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            Slog.e(TAG, "On boot completed, could not load session id " + sessionId);
            return;
        }

        if (sessionInfo.isStagedSessionApplied()) {
            logEvent(oldLoggingPackage,
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
        } else if (sessionInfo.isStagedSessionFailed()) {
            logEvent(oldLoggingPackage,
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
        }
    }

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
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH_DURING_BOOT,
                    failingNativeProcess);
        }
    }

    /**
     * Log a Watchdog rollback event to statsd.
     *
     * @param logPackage the package to associate the rollback with.
     * @param type the state of the rollback.
     * @param rollbackReason the reason Watchdog triggered a rollback, if known.
     * @param failingPackageName the failing package or process which triggered the rollback.
     */
    public static void logEvent(@Nullable VersionedPackage logPackage, int type,
            int rollbackReason, @NonNull String failingPackageName) {
        Slog.i(TAG, "Watchdog event occurred with type: " + rollbackTypeToString(type)
                + " logPackage: " + logPackage
                + " rollbackReason: " + rollbackReasonToString(rollbackReason)
                + " failedPackageName: " + failingPackageName);
        if (logPackage != null) {
            CrashRecoveryStatsLog.write(
                    CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                    type,
                    logPackage.getPackageName(),
                    logPackage.getVersionCode(),
                    rollbackReason,
                    failingPackageName,
                    new byte[]{});
        } else {
            // In the case that the log package is null, still log an empty string as an
            // indication that retrieving the logging parent failed.
            CrashRecoveryStatsLog.write(
                    CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                    type,
                    "",
                    0,
                    rollbackReason,
                    failingPackageName,
                    new byte[]{});
        }

        logTestProperties(logPackage, type, rollbackReason, failingPackageName);
    }

    /**
     * Writes properties which will be used by rollback tests to check if particular rollback
     * events have occurred.
     *
     * persist.sys.rollbacktest.enabled: true if rollback tests are running
     * persist.sys.rollbacktest.EVENT_TYPE: true if a particular rollback event has occurred
     *   ex: persist.sys.rollbacktest.ROLLBACK_INITIATE is true if ROLLBACK_INITIATE has happened
     * persist.sys.rollbacktest.EVENT_TYPE.logPackage: the package to associate the rollback with
     * persist.sys.rollbacktest.EVENT_TYPE.rollbackReason: the reason Watchdog triggered a rollback
     * persist.sys.rollbacktest.EVENT_TYPE.failedPackageName: the failing package or process which
     *   triggered the rollback
     */
    private static void logTestProperties(@Nullable VersionedPackage logPackage, int type,
            int rollbackReason, @NonNull String failingPackageName) {
        // This property should be on only during the tests
        final String prefix = "persist.sys.rollbacktest.";
        if (!SystemProperties.getBoolean(prefix + "enabled", false)) {
            return;
        }
        String key = prefix + rollbackTypeToString(type);
        SystemProperties.set(key, String.valueOf(true));
        SystemProperties.set(key + ".logPackage", logPackage != null ? logPackage.toString() : "");
        SystemProperties.set(key + ".rollbackReason", rollbackReasonToString(rollbackReason));
        SystemProperties.set(key + ".failedPackageName", failingPackageName);
    }

    @VisibleForTesting
    static int mapFailureReasonToMetric(@PackageWatchdog.FailureReasons int failureReason) {
        switch (failureReason) {
            case PackageWatchdog.FAILURE_REASON_NATIVE_CRASH:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH;
            case PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK;
            case PackageWatchdog.FAILURE_REASON_APP_CRASH:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH;
            case PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING;
            default:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN;
        }
    }

    private static String rollbackTypeToString(int type) {
        switch (type) {
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE:
                return "ROLLBACK_INITIATE";
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS:
                return "ROLLBACK_SUCCESS";
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE:
                return "ROLLBACK_FAILURE";
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED:
                return "ROLLBACK_BOOT_TRIGGERED";
            default:
                return "UNKNOWN";
        }
    }

    private static String rollbackReasonToString(int reason) {
        switch (reason) {
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH:
                return "REASON_NATIVE_CRASH";
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK:
                return "REASON_EXPLICIT_HEALTH_CHECK";
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH:
                return "REASON_APP_CRASH";
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING:
                return "REASON_APP_NOT_RESPONDING";
            case WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH_DURING_BOOT:
                return "REASON_NATIVE_CRASH_DURING_BOOT";
            default:
                return "UNKNOWN";
        }
    }
}
