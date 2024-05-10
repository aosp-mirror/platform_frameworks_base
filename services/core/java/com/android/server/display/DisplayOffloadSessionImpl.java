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

import android.annotation.Nullable;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;
import android.os.Trace;

/**
 * An implementation of the offload session that keeps track of whether the session is active.
 * An offload session is used to control the display's brightness using the offload chip.
 */
public class DisplayOffloadSessionImpl implements DisplayManagerInternal.DisplayOffloadSession {

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
        mDisplayPowerController.overrideDozeScreenState(displayState);
    }

    @Override
    public boolean isActive() {
        return mIsActive;
    }

    @Override
    public void updateBrightness(float brightness) {
        if (mIsActive) {
            mDisplayPowerController.setBrightnessFromOffload(brightness);
        }
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
            return mIsActive = mDisplayOffloader.startOffload();
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
            mDisplayPowerController.setBrightnessFromOffload(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }
}
