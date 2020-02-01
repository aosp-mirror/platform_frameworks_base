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

import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.util.ArraySet;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.PackageWatchdog;

import java.util.ArrayList;
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
            ApplicationInfo ai = packageManager.getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA);
            if (ai.metaData == null) {
                return null;
            }
            return ai.metaData.getString(LOGGING_PARENT_KEY);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to discover logging parent package: " + packageName, e);
            return null;
        }
    }

    @VisibleForTesting
    static VersionedPackage getLogPackage(Context context,
            @NonNull VersionedPackage failingPackage) {
        String logPackageName;
        VersionedPackage loggingParent;
        logPackageName = getLoggingParentName(context, failingPackage.getPackageName());
        if (logPackageName == null) {
            return failingPackage;
        }
        try {
            loggingParent = new VersionedPackage(logPackageName, context.getPackageManager()
                    .getPackageInfo(logPackageName, 0 /* flags */).getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            return failingPackage;
        }
        return loggingParent;
    }

    static void logRollbackStatusOnBoot(Context context, int rollbackId,
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

        // Identify the logging parent for this rollback. When all configurations are correct, each
        // package in the rollback refers to the same logging parent, except for the logging parent
        // itself. If a logging parent is missing for a package, we use the package itself for
        // logging. This might result in over-logging, but we prefer this over no logging.
        final Set<String> loggingPackageNames = new ArraySet<>();
        for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
            final String loggingParentName = getLoggingParentName(context,
                    packageRollback.getPackageName());
            if (loggingParentName != null) {
                loggingPackageNames.add(loggingParentName);
            } else {
                loggingPackageNames.add(packageRollback.getPackageName());
            }
        }

        // Use the version of the logging parent that was installed before
        // we rolled back for logging purposes.
        final List<VersionedPackage> oldLoggingPackages = new ArrayList<>();
        for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
            if (loggingPackageNames.contains(packageRollback.getPackageName())) {
                oldLoggingPackages.add(packageRollback.getVersionRolledBackFrom());
            }
        }

        int sessionId = rollback.getCommittedSessionId();
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            Slog.e(TAG, "On boot completed, could not load session id " + sessionId);
            return;
        }

        for (VersionedPackage oldLoggingPackage : oldLoggingPackages) {
            if (sessionInfo.isStagedSessionApplied()) {
                logEvent(oldLoggingPackage,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                        WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
            } else if (sessionInfo.isStagedSessionFailed()) {
                logEvent(oldLoggingPackage,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                        WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
            }
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
            StatsLog.logWatchdogRollbackOccurred(type, logPackage.getPackageName(),
                    logPackage.getVersionCode(), rollbackReason, failingPackageName);
        }
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
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE:
                return "ROLLBACK_INITIATE";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS:
                return "ROLLBACK_SUCCESS";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE:
                return "ROLLBACK_FAILURE";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED:
                return "ROLLBACK_BOOT_TRIGGERED";
            default:
                return "UNKNOWN";
        }
    }

    private static String rollbackReasonToString(int reason) {
        switch (reason) {
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH:
                return "REASON_NATIVE_CRASH";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK:
                return "REASON_EXPLICIT_HEALTH_CHECK";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH:
                return "REASON_APP_CRASH";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING:
                return "REASON_APP_NOT_RESPONDING";
            default:
                return "UNKNOWN";
        }
    }
}
