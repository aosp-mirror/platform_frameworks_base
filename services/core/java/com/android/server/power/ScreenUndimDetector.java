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

package com.android.server.power;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;

import android.annotation.NonNull;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Detects when user manually undims the screen (x times) and acquires a wakelock to keep the screen
 * on temporarily (without changing the screen timeout setting).
 */
public class ScreenUndimDetector {
    private static final String TAG = "ScreenUndimDetector";
    private static final boolean DEBUG = false;

    private static final String UNDIM_DETECTOR_WAKE_LOCK = "UndimDetectorWakeLock";

    /** DeviceConfig flag: is keep screen on feature enabled. */
    static final String KEY_KEEP_SCREEN_ON_ENABLED = "keep_screen_on_enabled";
    private static final boolean DEFAULT_KEEP_SCREEN_ON_ENABLED = true;
    private static final int OUTCOME_POWER_BUTTON =
            FrameworkStatsLog.TIMEOUT_AUTO_EXTENDED_REPORTED__OUTCOME__POWER_BUTTON;
    private static final int OUTCOME_TIMEOUT =
            FrameworkStatsLog.TIMEOUT_AUTO_EXTENDED_REPORTED__OUTCOME__TIMEOUT;
    private boolean mKeepScreenOnEnabled;

    /** DeviceConfig flag: how long should we keep the screen on. */
    @VisibleForTesting
    static final String KEY_KEEP_SCREEN_ON_FOR_MILLIS = "keep_screen_on_for_millis";
    @VisibleForTesting
    static final long DEFAULT_KEEP_SCREEN_ON_FOR_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private long mKeepScreenOnForMillis;

    /** DeviceConfig flag: how many user undims required to trigger keeping the screen on. */
    @VisibleForTesting
    static final String KEY_UNDIMS_REQUIRED = "undims_required";
    @VisibleForTesting
    static final int DEFAULT_UNDIMS_REQUIRED = 2;
    private int mUndimsRequired;

    /**
     * DeviceConfig flag: what is the maximum duration between undims to still consider them
     * consecutive.
     */
    @VisibleForTesting
    static final String KEY_MAX_DURATION_BETWEEN_UNDIMS_MILLIS =
            "max_duration_between_undims_millis";
    @VisibleForTesting
    static final long DEFAULT_MAX_DURATION_BETWEEN_UNDIMS_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private long mMaxDurationBetweenUndimsMillis;

    @VisibleForTesting
    PowerManager.WakeLock mWakeLock;

    @VisibleForTesting
    int mCurrentScreenPolicy;
    @VisibleForTesting
    int mUndimCounter = 0;
    @VisibleForTesting
    long mUndimCounterStartedMillis;
    private long mUndimOccurredTime = -1;
    private long mInteractionAfterUndimTime = -1;
    private InternalClock mClock;

    public ScreenUndimDetector() {
        mClock = new InternalClock();
    }

    ScreenUndimDetector(InternalClock clock) {
        mClock = clock;
    }

    static class InternalClock {
        public long getCurrentTime() {
            return SystemClock.elapsedRealtime();
        }
    }

    /** Should be called in parent's systemReady() */
    public void systemReady(Context context) {
        readValuesFromDeviceConfig();
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                context.getMainExecutor(),
                (properties) -> onDeviceConfigChange(properties.getKeyset()));

        final PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ON_AFTER_RELEASE,
                UNDIM_DETECTOR_WAKE_LOCK);
    }

    /**
     * Launches a message that figures out the screen transitions and detects user undims. Must be
     * called by the parent that is trying to update the screen policy.
     */
    public void recordScreenPolicy(int displayGroupId, int newPolicy) {
        if (displayGroupId != Display.DEFAULT_DISPLAY_GROUP || newPolicy == mCurrentScreenPolicy) {
            return;
        }

        if (DEBUG) {
            Slog.d(TAG,
                    "Screen policy transition: " + mCurrentScreenPolicy + " -> " + newPolicy);
        }

        // update the current policy with the new one immediately so we don't accidentally get
        // into a loop (which is possible if the switch below triggers a new policy).
        final int currentPolicy = mCurrentScreenPolicy;
        mCurrentScreenPolicy = newPolicy;

        if (!mKeepScreenOnEnabled) {
            return;
        }

        switch (currentPolicy) {
            case POLICY_DIM:
                if (newPolicy == POLICY_BRIGHT) {
                    final long now = mClock.getCurrentTime();
                    final long timeElapsedSinceFirstUndim = now - mUndimCounterStartedMillis;
                    if (timeElapsedSinceFirstUndim >= mMaxDurationBetweenUndimsMillis) {
                        reset();
                    }
                    if (mUndimCounter == 0) {
                        mUndimCounterStartedMillis = now;
                    }

                    mUndimCounter++;

                    if (DEBUG) {
                        Slog.d(TAG, "User undim, counter=" + mUndimCounter
                                + " (required=" + mUndimsRequired + ")"
                                + ", timeElapsedSinceFirstUndim=" + timeElapsedSinceFirstUndim
                                + " (max=" + mMaxDurationBetweenUndimsMillis + ")");
                    }
                    if (mUndimCounter >= mUndimsRequired) {
                        reset();
                        if (DEBUG) {
                            Slog.d(TAG, "Acquiring a wake lock for " + mKeepScreenOnForMillis);
                        }
                        if (mWakeLock != null) {
                            mUndimOccurredTime = mClock.getCurrentTime();
                            mWakeLock.acquire(mKeepScreenOnForMillis);
                        }
                    }
                } else {
                    if (newPolicy == POLICY_OFF || newPolicy == POLICY_DOZE) {
                        checkAndLogUndim(OUTCOME_TIMEOUT);
                    }
                    reset();
                }
                break;
            case POLICY_BRIGHT:
                if (newPolicy == POLICY_OFF || newPolicy == POLICY_DOZE) {
                    checkAndLogUndim(OUTCOME_POWER_BUTTON);
                }
                if (newPolicy != POLICY_DIM) {
                    reset();
                }
                break;
        }
    }

    @VisibleForTesting
    void reset() {
        if (DEBUG) {
            Slog.d(TAG, "Resetting the undim detector");
        }
        mUndimCounter = 0;
        mUndimCounterStartedMillis = 0;
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    private boolean readKeepScreenOnNotificationEnabled() {
        return DeviceConfig.getBoolean(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_KEEP_SCREEN_ON_ENABLED,
                DEFAULT_KEEP_SCREEN_ON_ENABLED);
    }

    private long readKeepScreenOnForMillis() {
        return DeviceConfig.getLong(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_KEEP_SCREEN_ON_FOR_MILLIS,
                DEFAULT_KEEP_SCREEN_ON_FOR_MILLIS);
    }

    private int readUndimsRequired() {
        int undimsRequired = DeviceConfig.getInt(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                DEFAULT_UNDIMS_REQUIRED);

        if (undimsRequired < 1 || undimsRequired > 5) {
            Slog.e(TAG, "Provided undimsRequired=" + undimsRequired
                    + " is not allowed [1, 5]; using the default=" + DEFAULT_UNDIMS_REQUIRED);
            return DEFAULT_UNDIMS_REQUIRED;
        }

        return undimsRequired;
    }

    private long readMaxDurationBetweenUndimsMillis() {
        return DeviceConfig.getLong(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_DURATION_BETWEEN_UNDIMS_MILLIS,
                DEFAULT_MAX_DURATION_BETWEEN_UNDIMS_MILLIS);
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        for (String key : keys) {
            Slog.i(TAG, "onDeviceConfigChange; key=" + key);
            switch (key) {
                case KEY_KEEP_SCREEN_ON_ENABLED:
                case KEY_KEEP_SCREEN_ON_FOR_MILLIS:
                case KEY_UNDIMS_REQUIRED:
                case KEY_MAX_DURATION_BETWEEN_UNDIMS_MILLIS:
                    readValuesFromDeviceConfig();
                    return;
                default:
                    Slog.i(TAG, "Ignoring change on " + key);
            }
        }
    }

    @VisibleForTesting
    void readValuesFromDeviceConfig() {
        mKeepScreenOnEnabled = readKeepScreenOnNotificationEnabled();
        mKeepScreenOnForMillis = readKeepScreenOnForMillis();
        mUndimsRequired = readUndimsRequired();
        mMaxDurationBetweenUndimsMillis = readMaxDurationBetweenUndimsMillis();

        Slog.i(TAG, "readValuesFromDeviceConfig():"
                + "\nmKeepScreenOnForMillis=" + mKeepScreenOnForMillis
                + "\nmKeepScreenOnNotificationEnabled=" + mKeepScreenOnEnabled
                + "\nmUndimsRequired=" + mUndimsRequired);

    }

    /**
     * The user interacted with the screen after an undim, indicating the phone is in use.
     * We use this event for logging.
     */
    public void userActivity(int displayGroupId) {
        if (displayGroupId != Display.DEFAULT_DISPLAY) {
            return;
        }
        if (mUndimOccurredTime != 1 && mInteractionAfterUndimTime == -1) {
            mInteractionAfterUndimTime = mClock.getCurrentTime();
        }
    }

    /**
     * Checks and logs if an undim occurred.
     *
     * A log will occur if an undim seems to have resulted in a timeout or a direct screen off such
     * as from a power button. Some outcomes may not be correctly assigned to a
     * TIMEOUT_AUTO_EXTENDED_REPORTED__OUTCOME value.
     */
    private void checkAndLogUndim(int outcome) {
        if (mUndimOccurredTime != -1) {
            long now = mClock.getCurrentTime();
            FrameworkStatsLog.write(FrameworkStatsLog.TIMEOUT_AUTO_EXTENDED_REPORTED,
                    outcome,
                    /* time_to_outcome_millis=*/  now - mUndimOccurredTime,
                    /* time_to_first_interaction_millis= */
                    mInteractionAfterUndimTime != -1 ? now - mInteractionAfterUndimTime : -1
            );
            mUndimOccurredTime = -1;
            mInteractionAfterUndimTime = -1;
        }
    }
}
