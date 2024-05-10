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
import android.util.IndentingPrintWriter;

import com.android.server.display.brightness.BrightnessReason;

import java.io.PrintWriter;

class BrightnessLowPowerModeModifier extends BrightnessModifier {

    @Override
    boolean shouldApply(DisplayManagerInternal.DisplayPowerRequest request) {
        return request.lowPowerMode;
    }


    @Override
    float getBrightnessAdjusted(float currentBrightness,
            DisplayManagerInternal.DisplayPowerRequest request) {
        final float brightnessFactor =
                Math.min(request.screenLowPowerBrightnessFactor, 1);
        return Math.max((currentBrightness * brightnessFactor), PowerManager.BRIGHTNESS_MIN);
    }

    @Override
    int getModifier() {
        return BrightnessReason.MODIFIER_LOW_POWER;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("BrightnessLowPowerModeModifier:");
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
        super.dump(ipw);
    }
}
