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

import static com.android.server.policy.Flags.supportInputWakeupDelegate;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.os.PowerManager;
import android.os.PowerManager.WakeReason;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.server.LocalServices;

/** Policy controlling the decision and execution of window-related wake ups. */
class WindowWakeUpPolicy {
    private static final String TAG = "WindowWakeUpPolicy";

    private static final boolean DEBUG = false;

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final WindowManager mWindowManager;
    private final Clock mClock;

    private final boolean mAllowTheaterModeWakeFromKey;
    private final boolean mAllowTheaterModeWakeFromPowerKey;
    private final boolean mAllowTheaterModeWakeFromMotion;
    private final boolean mAllowTheaterModeWakeFromCameraLens;
    private final boolean mAllowTheaterModeWakeFromLidSwitch;
    private final boolean mAllowTheaterModeWakeFromWakeGesture;

    // The policy will handle input-based wake ups if this delegate is null.
    @Nullable private WindowWakeUpPolicyInternal.InputWakeUpDelegate mInputWakeUpDelegate;

    WindowWakeUpPolicy(Context context) {
        this(context, Clock.SYSTEM_CLOCK);
    }

    @VisibleForTesting
    WindowWakeUpPolicy(Context context, Clock clock) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mWindowManager = context.getSystemService(WindowManager.class);
        mClock = clock;

        final Resources res = context.getResources();
        mAllowTheaterModeWakeFromKey = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromKey);
        mAllowTheaterModeWakeFromPowerKey = mAllowTheaterModeWakeFromKey
                || res.getBoolean(
                    com.android.internal.R.bool.config_allowTheaterModeWakeFromPowerKey);
        mAllowTheaterModeWakeFromMotion = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromMotion);
        mAllowTheaterModeWakeFromCameraLens = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromCameraLens);
        mAllowTheaterModeWakeFromLidSwitch = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromLidSwitch);
        mAllowTheaterModeWakeFromWakeGesture = res.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromGesture);
        if (supportInputWakeupDelegate()) {
            LocalServices.addService(WindowWakeUpPolicyInternal.class, new LocalService());
        }
    }

    private final class LocalService implements WindowWakeUpPolicyInternal {
        @Override
        public void setInputWakeUpDelegate(@Nullable InputWakeUpDelegate delegate) {
            if (!supportInputWakeupDelegate()) {
                Slog.w(TAG, "Input wake up delegates not supported.");
                return;
            }
            mInputWakeUpDelegate = delegate;
        }
    }

    /**
     * Wakes up from a key event.
     *
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @param keyCode the {@link android.view.KeyEvent} key code of the key event.
     * @param isDown {@code true} if the event's action is {@link KeyEvent#ACTION_DOWN}.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromKey(long eventTime, int keyCode, boolean isDown) {
        final boolean wakeAllowedDuringTheaterMode =
                keyCode == KEYCODE_POWER
                        ? mAllowTheaterModeWakeFromPowerKey
                        : mAllowTheaterModeWakeFromKey;
        if (!canWakeUp(wakeAllowedDuringTheaterMode)) {
            if (DEBUG) Slog.d(TAG, "Unable to wake up from " + KeyEvent.keyCodeToString(keyCode));
            return false;
        }
        if (mInputWakeUpDelegate != null
                && mInputWakeUpDelegate.wakeUpFromKey(eventTime, keyCode, isDown)) {
            return true;
        }
        wakeUp(
                eventTime,
                keyCode == KEYCODE_POWER ? WAKE_REASON_POWER_BUTTON : WAKE_REASON_WAKE_KEY,
                keyCode == KEYCODE_POWER ? "POWER" : "KEY");
        return true;
    }

    /**
     * Wakes up from a motion event.
     *
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @param isDown {@code true} if the event's action is {@link MotionEvent#ACTION_DOWN}.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromMotion(long eventTime, int source, boolean isDown) {
        if (!canWakeUp(mAllowTheaterModeWakeFromMotion)) {
            if (DEBUG) Slog.d(TAG, "Unable to wake up from motion.");
            return false;
        }
        if (mInputWakeUpDelegate != null
                && mInputWakeUpDelegate.wakeUpFromMotion(eventTime, source, isDown)) {
            return true;
        }
        wakeUp(eventTime, WAKE_REASON_WAKE_MOTION, "MOTION");
        return true;
    }

    /**
     * Wakes up due to an opened camera cover.
     *
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromCameraCover(long eventTime) {
        if (!canWakeUp(mAllowTheaterModeWakeFromCameraLens)) {
            if (DEBUG) Slog.d(TAG, "Unable to wake up from camera cover.");
            return false;
        }
        wakeUp(eventTime, WAKE_REASON_CAMERA_LAUNCH, "CAMERA_COVER");
        return true;
    }

    /**
     * Wakes up due to an opened lid.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromLid() {
        if (!canWakeUp(mAllowTheaterModeWakeFromLidSwitch)) {
            if (DEBUG) Slog.d(TAG, "Unable to wake up from lid.");
            return false;
        }
        wakeUp(mClock.uptimeMillis(), WAKE_REASON_LID, "LID");
        return true;
    }

    /**
     * Wakes up to prevent sleeping when opening camera through power button.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromPowerKeyCameraGesture() {
        if (!canWakeUp(mAllowTheaterModeWakeFromPowerKey)) {
            if (DEBUG) Slog.d(TAG, "Unable to wake up from power key camera gesture.");
            return false;
        }
        wakeUp(mClock.uptimeMillis(), WAKE_REASON_CAMERA_LAUNCH, "CAMERA_GESTURE_PREVENT_LOCK");
        return true;
    }

    /**
     * Wake up from a wake gesture.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromWakeGesture() {
        if (!canWakeUp(mAllowTheaterModeWakeFromWakeGesture)) {
            if (DEBUG) Slog.d(TAG, "Unable to wake up from gesture.");
            return false;
        }
        wakeUp(mClock.uptimeMillis(), WAKE_REASON_GESTURE, "GESTURE");
        return true;
    }

    private boolean canWakeUp(boolean wakeInTheaterMode) {
        if (supportInputWakeupDelegate() && isDefaultDisplayOn()) {
            // If the default display is on, theater mode should not influence whether or not
            // waking up is allowed. This is because the theater mode checks are there to block
            // the display from being on in situations where the user may not want it to be
            // on (so if the display is already on, no need to check for theater mode at all).
            return true;
        }
        final boolean isTheaterModeEnabled =
                Settings.Global.getInt(
                        mContext.getContentResolver(), Settings.Global.THEATER_MODE_ON, 0) == 1;
        return wakeInTheaterMode || !isTheaterModeEnabled;
    }

    private boolean isDefaultDisplayOn() {
        return Display.isOnState(mWindowManager.getDefaultDisplay().getState());
    }

    /** Wakes up {@link PowerManager}. */
    private void wakeUp(long wakeTime, @WakeReason int reason, String details) {
        mPowerManager.wakeUp(wakeTime, reason, "android.policy:" + details);
    }
}
