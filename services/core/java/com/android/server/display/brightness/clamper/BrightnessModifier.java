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

package com.android.server.display.brightness.clamper;

import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;

import com.android.server.display.DisplayBrightnessState;

import java.io.PrintWriter;

/**
 * Modifies current brightness based on request
 */
abstract class BrightnessModifier implements BrightnessStateModifier {

    private boolean mApplied = false;

    abstract boolean shouldApply(DisplayManagerInternal.DisplayPowerRequest request);

    abstract float getBrightnessAdjusted(float currentBrightness,
            DisplayManagerInternal.DisplayPowerRequest request);

    abstract int getModifier();

    @Override
    public void apply(DisplayManagerInternal.DisplayPowerRequest request,
            DisplayBrightnessState.Builder stateBuilder) {
        // If low power mode is enabled, scale brightness by screenLowPowerBrightnessFactor
        // as long as it is above the minimum threshold.
        if (shouldApply(request)) {
            float value = stateBuilder.getBrightness();
            if (value > PowerManager.BRIGHTNESS_MIN) {
                stateBuilder.setBrightness(getBrightnessAdjusted(value, request));
                stateBuilder.getBrightnessReason().addModifier(getModifier());
            }
            if (!mApplied) {
                stateBuilder.setIsSlowChange(false);
            }
            mApplied = true;
        } else if (mApplied) {
            stateBuilder.setIsSlowChange(false);
            mApplied = false;
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("BrightnessModifier:");
        pw.println("  mApplied=" + mApplied);
    }

    @Override
    public void stop() {
        // do nothing
    }

    @Override
    public void onAmbientLuxChange(float ambientLux) {
        // do nothing
    }

    @Override
    public void setAutoBrightnessState(int state) {
        // do nothing
    }
}
