/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.policy;

import static android.os.PowerManager.WAKE_REASON_CAMERA_LAUNCH;
import static android.os.PowerManager.WAKE_REASON_GESTURE;
import static android.os.PowerManager.WAKE_REASON_LID;
import static android.os.PowerManager.WAKE_REASON_POWER_BUTTON;
import static android.os.PowerManager.WAKE_REASON_WAKE_KEY;
import static android.os.PowerManager.WAKE_REASON_WAKE_MOTION;
import static android.view.KeyEvent.KEYCODE_POWER;

import android.content.Context;
import android.content.res.Resources;
import android.os.PowerManager;
import android.os.PowerManager.WakeReason;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;


/** Policy controlling the decision and execution of window-related wake ups. */
class WindowWakeUpPolicy {
    private static final String TAG = "WindowWakeUpPolicy";

    private static final boolean DEBUG = false;

    private final Context mContext;
    private final PowerManager mPowerManager;

    private final boolean mAllowTheaterModeWakeFromKey;
    private final boolean mAllowTheaterModeWakeFromPowerKey;
    private final boolean mAllowTheaterModeWakeFromMotion;
    private final boolean mAllowTheaterModeWakeFromMotionWhenNotDreaming;
    private final boolean mAllowTheaterModeWakeFromCameraLens;
    private final boolean mAllowTheaterModeWakeFromLidSwitch;
    private final boolean mAllowTheaterModeWakeFromWakeGesture;

    WindowWakeUpPolicy(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);

        final Resources res = context.getResources();
        mAllowTheaterModeWakeFromKey = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromKey);
        mAllowTheaterModeWakeFromPowerKey = mAllowTheaterModeWakeFromKey
                || res.getBoolean(
                    com.android.internal.R.bool.config_allowTheaterModeWakeFromPowerKey);
        mAllowTheaterModeWakeFromMotion = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromMotion);
        mAllowTheaterModeWakeFromMotionWhenNotDreaming = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromMotionWhenNotDreaming);
        mAllowTheaterModeWakeFromCameraLens = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromCameraLens);
        mAllowTheaterModeWakeFromLidSwitch = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromLidSwitch);
        mAllowTheaterModeWakeFromWakeGesture = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromGesture);
    }

    /**
     * Wakes up from a key event.
     *
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @param keyCode the {@link android.view.KeyEvent} key code of the key event.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromKey(long eventTime, int keyCode) {
        final boolean wakeAllowedDuringTheaterMode =
                keyCode == KEYCODE_POWER
                        ? mAllowTheaterModeWakeFromPowerKey
                        : mAllowTheaterModeWakeFromKey;
        return wakeUp(
                eventTime,
                wakeAllowedDuringTheaterMode,
                keyCode == KEYCODE_POWER ? WAKE_REASON_POWER_BUTTON : WAKE_REASON_WAKE_KEY,
                keyCode == KEYCODE_POWER ? "POWER" : "KEY");
    }

    /**
     * Wakes up from a motion event.
     *
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromMotion(long eventTime) {
        return wakeUp(
                eventTime, mAllowTheaterModeWakeFromMotion, WAKE_REASON_WAKE_MOTION, "MOTION");
    }

    /**
     * Wakes up due to an opened camera cover.
     *
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromCameraCover(long eventTime) {
        return wakeUp(
                eventTime,
                mAllowTheaterModeWakeFromCameraLens,
                WAKE_REASON_CAMERA_LAUNCH,
                "CAMERA_COVER");
    }

    /**
     * Wakes up due to an opened lid.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromLid() {
        return wakeUp(
                SystemClock.uptimeMillis(),
                mAllowTheaterModeWakeFromLidSwitch,
                WAKE_REASON_LID,
                "LID");
    }

    /**
     * Wakes up to prevent sleeping when opening camera through power button.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromPowerKeyCameraGesture() {
        return wakeUp(
                SystemClock.uptimeMillis(),
                mAllowTheaterModeWakeFromPowerKey,
                WAKE_REASON_CAMERA_LAUNCH,
                "CAMERA_GESTURE_PREVENT_LOCK");
    }

    /**
     * Wake up from a wake gesture.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromWakeGesture() {
        return wakeUp(
                SystemClock.uptimeMillis(),
                mAllowTheaterModeWakeFromWakeGesture,
                WAKE_REASON_GESTURE,
                "GESTURE");
    }

    private boolean wakeUp(
            long wakeTime, boolean wakeInTheaterMode, @WakeReason int reason, String details) {
        final boolean isTheaterModeEnabled =
                Settings.Global.getInt(
                        mContext.getContentResolver(), Settings.Global.THEATER_MODE_ON, 0) == 1;
        if (!wakeInTheaterMode && isTheaterModeEnabled) {
            if (DEBUG) Slog.d(TAG, "Unable to wake up from " + details);
            return false;
        }
        mPowerManager.wakeUp(wakeTime, reason, "android.policy:" + details);
        return true;
    }
}
