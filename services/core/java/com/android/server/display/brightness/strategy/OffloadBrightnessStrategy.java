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

package com.android.server.display.brightness.strategy;

import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;

import java.io.PrintWriter;

/**
 * Manages the brightness of the display when auto-brightness is on, the screen has just turned on
 * and there is no available lux reading yet. The brightness value is read from the offload chip.
 */
public class OffloadBrightnessStrategy implements DisplayBrightnessStrategy {

    private float mOffloadScreenBrightness;

    public OffloadBrightnessStrategy() {
        mOffloadScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
    }

    @Override
    public DisplayBrightnessState updateBrightness(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest) {
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_OFFLOAD);
        return new DisplayBrightnessState.Builder()
                .setBrightness(mOffloadScreenBrightness)
                .setSdrBrightness(mOffloadScreenBrightness)
                .setBrightnessReason(brightnessReason)
                .setDisplayBrightnessStrategyName(getName())
                .setIsSlowChange(false)
                .setShouldUpdateScreenBrightnessSetting(true)
                .build();
    }

    @Override
    public String getName() {
        return "OffloadBrightnessStrategy";
    }

    public float getOffloadScreenBrightness() {
        return mOffloadScreenBrightness;
    }

    public void setOffloadScreenBrightness(float offloadScreenBrightness) {
        mOffloadScreenBrightness = offloadScreenBrightness;
    }

    /**
     * Dumps the state of this class.
     */
    public void dump(PrintWriter writer) {
        writer.println("OffloadBrightnessStrategy:");
        writer.println("  mOffloadScreenBrightness:" + mOffloadScreenBrightness);
    }
}
