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

package com.android.server.power;

import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;

import static com.android.server.power.PowerManagerService.WAKE_LOCK_BUTTON_BRIGHT;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_SCREEN_BRIGHT;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_SCREEN_DIM;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_SCREEN_TIMEOUT_OVERRIDE;

import android.annotation.IntDef;
import android.content.Context;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
  * Policy that handle the screen timeout override wake lock behavior.
  */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
final class ScreenTimeoutOverridePolicy {
    private static final String TAG = "ScreenTimeoutOverridePolicy";

    /**
     * Release reason code: The wake lock is never acquired.
     */
    public static final int RELEASE_REASON_UNKNOWN = -1;

    /**
     * Release reason code: The wake lock can't be acquired because of screen off.
     */
    public static final int RELEASE_REASON_NON_INTERACTIVE = 1;

    /**
     * Release reason code: Release because a screen lock is acquired.
     */
    public static final int RELEASE_REASON_SCREEN_LOCK = 2;

    /**
     * Release reason code: Release because user activity attention occurs.
     */
    public static final int RELEASE_REASON_USER_ACTIVITY_ATTENTION = 3;

    /**
     * Release reason code: Release because user activity other occurs.
     */
    public static final int RELEASE_REASON_USER_ACTIVITY_OTHER = 4;

    /**
     * Release reason code: Release because user activity button occurs.
     */
    public static final int RELEASE_REASON_USER_ACTIVITY_BUTTON = 5;

    /**
     * Release reason code: Release because user activity touch occurs.
     */
    public static final int RELEASE_REASON_USER_ACTIVITY_TOUCH = 6;

    /**
     * Release reason code: Release because user activity accessibility occurs.
     */
    public static final int RELEASE_REASON_USER_ACTIVITY_ACCESSIBILITY = 7;

    /**
     * Release reason code: Release because wakelock dies.
     */
    public static final int RELEASE_REASON_WAKE_LOCK_DEATH = 8;

    /**
     * @hide
     */
    @IntDef(prefix = { "RELEASE_REASON_" }, value = {
            RELEASE_REASON_UNKNOWN,
            RELEASE_REASON_NON_INTERACTIVE,
            RELEASE_REASON_SCREEN_LOCK,
            RELEASE_REASON_USER_ACTIVITY_ATTENTION,
            RELEASE_REASON_USER_ACTIVITY_OTHER,
            RELEASE_REASON_USER_ACTIVITY_BUTTON,
            RELEASE_REASON_USER_ACTIVITY_TOUCH,
            RELEASE_REASON_USER_ACTIVITY_ACCESSIBILITY,
            RELEASE_REASON_WAKE_LOCK_DEATH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReleaseReason{}

    // The screen timeout override config in milliseconds.
    private long mScreenTimeoutOverrideConfig;

    // The last reason that wake locks had been released by service.
    private @ReleaseReason int mLastAutoReleaseReason = RELEASE_REASON_UNKNOWN;

    interface PolicyCallback {
        /**
         * Notify {@link PowerManagerService} to release all override wake locks.
         */
        void releaseAllScreenTimeoutOverrideWakelocks(@ReleaseReason int reason);
    }
    private PolicyCallback mPolicyCallback;

    ScreenTimeoutOverridePolicy(Context context, long minimumScreenOffTimeoutConfig,
            PolicyCallback callback) {
        mScreenTimeoutOverrideConfig = context.getResources().getInteger(
                com.android.internal.R.integer.config_screenTimeoutOverride);
        if (mScreenTimeoutOverrideConfig < minimumScreenOffTimeoutConfig) {
            Slog.w(TAG, "Screen timeout override is smaller than the minimum timeout : "
                    + mScreenTimeoutOverrideConfig);
            mScreenTimeoutOverrideConfig = -1;
        }
        mPolicyCallback = callback;
    }

    /**
     * Return the valid screen timeout override value.
     */
    long getScreenTimeoutOverrideLocked(int wakeLockSummary, long screenOffTimeout) {
        if (!isWakeLockAcquired(wakeLockSummary)) {
            return screenOffTimeout;
        }

        if (mScreenTimeoutOverrideConfig < 0) {
            return screenOffTimeout;
        }

        // If screen timeout overlay wake lock is acquired, return the policy timeout.
        return Math.min(mScreenTimeoutOverrideConfig, screenOffTimeout);
    }

    /**
     * Called when the policy have to release all wake lock when user activity occurred.
     */
    void onUserActivity(int wakeLockSummary, @PowerManager.UserActivityEvent int event) {
        if (!isWakeLockAcquired(wakeLockSummary)) {
            return;
        }

        switch (event) {
            case PowerManager.USER_ACTIVITY_EVENT_ATTENTION:
                releaseAllWakeLocks(RELEASE_REASON_USER_ACTIVITY_ATTENTION);
                return;
            case PowerManager.USER_ACTIVITY_EVENT_OTHER:
                releaseAllWakeLocks(RELEASE_REASON_USER_ACTIVITY_OTHER);
                return;
            case PowerManager.USER_ACTIVITY_EVENT_BUTTON:
                releaseAllWakeLocks(RELEASE_REASON_USER_ACTIVITY_BUTTON);
                return;
            case PowerManager.USER_ACTIVITY_EVENT_TOUCH:
                releaseAllWakeLocks(RELEASE_REASON_USER_ACTIVITY_TOUCH);
                return;
            case PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY:
                releaseAllWakeLocks(RELEASE_REASON_USER_ACTIVITY_ACCESSIBILITY);
                return;
        }
    }

    /**
     * Check the summary whether a screen wake lock acquired .
     */
    void checkScreenWakeLock(int wakeLockSummary) {
        if (!isWakeLockAcquired(wakeLockSummary)) {
            return;
        }

        if ((wakeLockSummary & (WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_SCREEN_DIM
                | WAKE_LOCK_BUTTON_BRIGHT)) != 0) {
            releaseAllWakeLocks(RELEASE_REASON_SCREEN_LOCK);
        }
    }

    /**
     * Check the device is in non-interactive
     */
    void onWakefulnessChange(int wakeLockSummary, int globalWakefulness) {
        if (!isWakeLockAcquired(wakeLockSummary)) {
            return;
        }

        if (globalWakefulness != WAKEFULNESS_AWAKE) {
            releaseAllWakeLocks(RELEASE_REASON_NON_INTERACTIVE);
        }
    }

    private boolean isWakeLockAcquired(int wakeLockSummary) {
        return (wakeLockSummary & WAKE_LOCK_SCREEN_TIMEOUT_OVERRIDE) != 0;
    }

    private void logReleaseReason() {
        Slog.i(TAG, "Releasing all screen timeout override wake lock."
                + " (reason=" + mLastAutoReleaseReason + ")");
    }

    private void releaseAllWakeLocks(@ReleaseReason int reason) {
        mPolicyCallback.releaseAllScreenTimeoutOverrideWakelocks(reason);
        mLastAutoReleaseReason = reason;
        logReleaseReason();
    }

    void dump(PrintWriter pw) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        ipw.println();
        ipw.println("ScreenTimeoutOverridePolicy:");
        ipw.increaseIndent();

        ipw.println("mScreenTimeoutOverrideConfig=" + mScreenTimeoutOverrideConfig);
        ipw.println("mLastAutoReleaseReason=" + mLastAutoReleaseReason);
    }
}
