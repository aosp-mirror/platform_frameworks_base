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

package com.android.server.display;

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_MAX;

import android.annotation.Nullable;
import android.hardware.display.DisplayManagerInternal;
import android.os.Trace;
import android.util.Slog;
import android.view.Display;

import com.android.server.display.utils.DebugUtils;

/**
 * An implementation of the offload session that keeps track of whether the session is active.
 * An offload session is used to control the display's brightness using the offload chip.
 */
public class DisplayOffloadSessionImpl implements DisplayManagerInternal.DisplayOffloadSession {
    private static final String TAG = "DisplayOffloadSessionImpl";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayOffloadSessionImpl DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    @Nullable
    private final DisplayManagerInternal.DisplayOffloader mDisplayOffloader;
    private final DisplayPowerControllerInterface mDisplayPowerController;
    private boolean mIsActive;

    public DisplayOffloadSessionImpl(
            @Nullable DisplayManagerInternal.DisplayOffloader displayOffloader,
            DisplayPowerControllerInterface displayPowerController) {
        mDisplayOffloader = displayOffloader;
        mDisplayPowerController = displayPowerController;
    }

    @Override
    public void setDozeStateOverride(int displayState) {
        mDisplayPowerController.overrideDozeScreenState(displayState, Display.STATE_REASON_OFFLOAD);
    }

    @Override
    public boolean isActive() {
        return mIsActive;
    }

    @Override
    public boolean allowAutoBrightnessInDoze() {
        if (mDisplayOffloader == null) {
            return false;
        }
        return mDisplayOffloader.allowAutoBrightnessInDoze();
    }

    @Override
    public void updateBrightness(float brightness) {
        if (mIsActive) {
            mDisplayPowerController.setBrightnessFromOffload(brightness);
        }
    }

    @Override
    public boolean blockScreenOn(Runnable unblocker) {
        if (mDisplayOffloader == null) {
            return false;
        }
        mDisplayOffloader.onBlockingScreenOn(unblocker);
        return true;
    }

    @Override
    public void cancelBlockScreenOn() {
        if (mDisplayOffloader == null) {
            return;
        }
        mDisplayOffloader.cancelBlockScreenOn();
    }

    @Override
    public float[] getAutoBrightnessLevels(int mode) {
        if (mode < 0 || mode > AUTO_BRIGHTNESS_MODE_MAX) {
            throw new IllegalArgumentException("Unknown auto-brightness mode: " + mode);
        }
        return mDisplayPowerController.getAutoBrightnessLevels(mode);
    }

    @Override
    public float[] getAutoBrightnessLuxLevels(int mode) {
        if (mode < 0 || mode > AUTO_BRIGHTNESS_MODE_MAX) {
            throw new IllegalArgumentException("Unknown auto-brightness mode: " + mode);
        }
        return mDisplayPowerController.getAutoBrightnessLuxLevels(mode);
    }

    /**
     * Start the offload session. The method returns if the session is already active.
     * @return Whether the session was started successfully
     */
    public boolean startOffload() {
        if (mDisplayOffloader == null || mIsActive) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "DisplayOffloader#startOffload");
        try {
            mIsActive = mDisplayOffloader.startOffload();
            if (DEBUG) {
                Slog.d(TAG, "startOffload = " + mIsActive);
            }
            return mIsActive;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    /**
     * Stop the offload session. The method returns if the session is not active.
     */
    public void stopOffload() {
        if (mDisplayOffloader == null || !mIsActive) {
            return;
        }
        Trace.traceBegin(Trace.TRACE_TAG_POWER, "DisplayOffloader#stopOffload");
        try {
            mDisplayOffloader.stopOffload();
            mIsActive = false;
            if (DEBUG) {
                Slog.i(TAG, "stopOffload");
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    @Override
    public float getBrightness() {
        return mDisplayPowerController.getScreenBrightnessSetting();
    }

    @Override
    public float getDozeBrightness() {
        return mDisplayPowerController.getDozeBrightnessForOffload();
    }
}
