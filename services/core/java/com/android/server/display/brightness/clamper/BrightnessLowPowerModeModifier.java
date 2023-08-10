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
import com.android.server.display.brightness.BrightnessReason;

import java.io.PrintWriter;

class BrightnessLowPowerModeModifier implements BrightnessModifier {

    private boolean mAppliedLowPower = false;

    @Override
    public void apply(DisplayManagerInternal.DisplayPowerRequest request,
            DisplayBrightnessState.Builder stateBuilder) {
        // If low power mode is enabled, scale brightness by screenLowPowerBrightnessFactor
        // as long as it is above the minimum threshold.
        if (request.lowPowerMode) {
            float value = stateBuilder.getBrightness();
            if (value > PowerManager.BRIGHTNESS_MIN) {
                final float brightnessFactor =
                        Math.min(request.screenLowPowerBrightnessFactor, 1);
                final float lowPowerBrightnessFloat = Math.max((value * brightnessFactor),
                        PowerManager.BRIGHTNESS_MIN);
                stateBuilder.setBrightness(lowPowerBrightnessFloat);
                stateBuilder.getBrightnessReason().addModifier(BrightnessReason.MODIFIER_LOW_POWER);
            }
            if (!mAppliedLowPower) {
                stateBuilder.setIsSlowChange(false);
            }
            mAppliedLowPower = true;
        } else if (mAppliedLowPower) {
            stateBuilder.setIsSlowChange(false);
            mAppliedLowPower = false;
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("BrightnessLowPowerModeModifier:");
        pw.println("  mAppliedLowPower=" + mAppliedLowPower);
    }
}
