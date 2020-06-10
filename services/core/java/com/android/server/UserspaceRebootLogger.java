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

package com.android.server;

import static com.android.internal.util.FrameworkStatsLog.USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_SHUTDOWN_SEQUENCE_ABORTED;
import static com.android.internal.util.FrameworkStatsLog.USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_USERDATA_REMOUNT;
import static com.android.internal.util.FrameworkStatsLog.USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_USERSPACE_REBOOT_WATCHDOG_TRIGGERED;
import static com.android.internal.util.FrameworkStatsLog.USERSPACE_REBOOT_REPORTED__OUTCOME__OUTCOME_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.USERSPACE_REBOOT_REPORTED__OUTCOME__SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.USERSPACE_REBOOT_REPORTED__USER_ENCRYPTION_STATE__LOCKED;
import static com.android.internal.util.FrameworkStatsLog.USERSPACE_REBOOT_REPORTED__USER_ENCRYPTION_STATE__UNLOCKED;

import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.util.concurrent.Executor;

/**
 * Utility class to help abstract logging {@code UserspaceRebootReported} atom.
 */
public final class UserspaceRebootLogger {

    private static final String TAG = "UserspaceRebootLogger";

    private static final String USERSPACE_REBOOT_SHOULD_LOG_PROPERTY =
            "persist.sys.userspace_reboot.log.should_log";
    private static final String USERSPACE_REBOOT_LAST_STARTED_PROPERTY =
            "sys.userspace_reboot.log.last_started";
    private static final String USERSPACE_REBOOT_LAST_FINISHED_PROPERTY =
            "sys.userspace_reboot.log.last_finished";
    private static final String LAST_BOOT_REASON_PROPERTY = "sys.boot.reason.last";

    private UserspaceRebootLogger() {}

    /**
     * Modifies internal state to note that {@code UserspaceRebootReported} atom needs to be
     * logged on the next successful boot.
     *
     * <p>This call should only be made on devices supporting userspace reboot.
     */
    public static void noteUserspaceRebootWasRequested() {
        if (!PowerManager.isRebootingUserspaceSupportedImpl()) {
            Slog.wtf(TAG, "Userspace reboot is not supported.");
            return;
        }

        SystemProperties.set(USERSPACE_REBOOT_SHOULD_LOG_PROPERTY, "1");
        SystemProperties.set(USERSPACE_REBOOT_LAST_STARTED_PROPERTY,
                String.valueOf(SystemClock.elapsedRealtime()));
    }

    /**
     * Updates internal state on boot after successful userspace reboot.
     *
     * <p>Should be called right before framework sets {@code sys.boot_completed} property.
     *
     * <p>This call should only be made on devices supporting userspace reboot.
     */
    public static void noteUserspaceRebootSuccess() {
        if (!PowerManager.isRebootingUserspaceSupportedImpl()) {
            Slog.wtf(TAG, "Userspace reboot is not supported.");
            return;
        }

        SystemProperties.set(USERSPACE_REBOOT_LAST_FINISHED_PROPERTY,
                String.valueOf(SystemClock.elapsedRealtime()));
    }

    /**
     * Returns {@code true} if {@code UserspaceRebootReported} atom should be logged.
     *
     * <p>This call should only be made on devices supporting userspace reboot.
     */
    public static boolean shouldLogUserspaceRebootEvent() {
        if (!PowerManager.isRebootingUserspaceSupportedImpl()) {
            Slog.wtf(TAG, "Userspace reboot is not supported.");
            return false;
        }

        return SystemProperties.getBoolean(USERSPACE_REBOOT_SHOULD_LOG_PROPERTY, false);
    }

    /**
     * Asynchronously logs {@code UserspaceRebootReported} on the given {@code executor}.
     *
     * <p>Should be called in the end of {@link
     * com.android.server.am.ActivityManagerService#finishBooting()} method, after framework have
     * tried to proactivelly unlock storage of the primary user.
     *
     * <p>This call should only be made on devices supporting userspace reboot.
     */
    public static void logEventAsync(boolean userUnlocked, Executor executor) {
        if (!PowerManager.isRebootingUserspaceSupportedImpl()) {
            Slog.wtf(TAG, "Userspace reboot is not supported.");
            return;
        }

        final int outcome = computeOutcome();
        final long durationMillis;
        if (outcome == USERSPACE_REBOOT_REPORTED__OUTCOME__SUCCESS) {
            durationMillis = SystemProperties.getLong(USERSPACE_REBOOT_LAST_FINISHED_PROPERTY, 0)
                    - SystemProperties.getLong(USERSPACE_REBOOT_LAST_STARTED_PROPERTY, 0);
        } else {
            durationMillis = 0;
        }
        final int encryptionState =
                userUnlocked
                    ? USERSPACE_REBOOT_REPORTED__USER_ENCRYPTION_STATE__UNLOCKED
                    : USERSPACE_REBOOT_REPORTED__USER_ENCRYPTION_STATE__LOCKED;
        executor.execute(
                () -> {
                    Slog.i(TAG, "Logging UserspaceRebootReported atom: { outcome: " + outcome
                            + " durationMillis: " + durationMillis + " encryptionState: "
                            + encryptionState + " }");
                    FrameworkStatsLog.write(FrameworkStatsLog.USERSPACE_REBOOT_REPORTED, outcome,
                            durationMillis, encryptionState);
                    SystemProperties.set(USERSPACE_REBOOT_SHOULD_LOG_PROPERTY, "");
                });
    }

    private static int computeOutcome() {
        if (SystemProperties.getLong(USERSPACE_REBOOT_LAST_STARTED_PROPERTY, -1) != -1) {
            return USERSPACE_REBOOT_REPORTED__OUTCOME__SUCCESS;
        }
        String reason = TextUtils.emptyIfNull(SystemProperties.get(LAST_BOOT_REASON_PROPERTY, ""));
        if (reason.startsWith("reboot,")) {
            reason = reason.substring("reboot".length());
        }
        if (reason.startsWith("userspace_failed,watchdog_fork")) {
            return USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_SHUTDOWN_SEQUENCE_ABORTED;
        }
        if (reason.startsWith("userspace_failed,shutdown_aborted")) {
            return USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_SHUTDOWN_SEQUENCE_ABORTED;
        }
        if (reason.startsWith("mount_userdata_failed")) {
            return USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_USERDATA_REMOUNT;
        }
        if (reason.startsWith("userspace_failed,init_user0")) {
            return USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_USERDATA_REMOUNT;
        }
        if (reason.startsWith("userspace_failed,enablefilecrypto")) {
            return USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_USERDATA_REMOUNT;
        }
        if (reason.startsWith("userspace_failed,watchdog_triggered")) {
            return USERSPACE_REBOOT_REPORTED__OUTCOME__FAILED_USERSPACE_REBOOT_WATCHDOG_TRIGGERED;
        }
        return USERSPACE_REBOOT_REPORTED__OUTCOME__OUTCOME_UNKNOWN;
    }
}
