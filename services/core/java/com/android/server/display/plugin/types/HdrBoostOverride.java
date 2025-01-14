/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.display.plugin.types;

import android.annotation.FloatRange;
import android.os.PowerManager;

import com.android.server.display.DisplayBrightnessState;

/**
 * HDR boost override value.
 * @param sdrHdrRatio - HDR to SDR multiplier, if < 1 HDR boost is off.
 * @param maxHdrBrightness - Brightness max when boosted. Value in range from BRIGHTNESS_MIN to
 *                         BRIGHTNESS_MAX. If not used should be set to PowerManager.BRIGHTNESS_MAX
 * @param customTransitionRate - Custom transition rate for transitioning to new HDR brightness.
 *                             If not used should be set to
 *                             DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET
 */
public record HdrBoostOverride(
        @FloatRange(from = 0)
        float sdrHdrRatio,
        @FloatRange(from = PowerManager.BRIGHTNESS_MIN, to = PowerManager.BRIGHTNESS_MAX)
        float maxHdrBrightness,
        float customTransitionRate) {
    /**
     * Constant for HDR boost off. Plugins should use this constant instead of creating new objects
     */
    private static final HdrBoostOverride HDR_OFF = new HdrBoostOverride(0,
            PowerManager.BRIGHTNESS_MAX, DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET);

    /**
     * Create HdrBoostOverride for HDR boost off
     */
    public static HdrBoostOverride forHdrOff() {
        return HDR_OFF;
    }

    /**
     * Create HdrBoostOverride for sdr-hdr ration override
     */
    public static HdrBoostOverride forSdrHdrRatio(float sdrHdrRatio) {
        return new HdrBoostOverride(sdrHdrRatio,
                PowerManager.BRIGHTNESS_MAX, DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET);
    }
}
