/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.PowerManager;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;
import com.android.server.display.brightness.StrategyExecutionRequest;
import com.android.server.display.brightness.StrategySelectionNotifyRequest;

import java.io.PrintWriter;

/**
 * Manages the brightness of the display when the system brightness is overridden
 */
public class OverrideBrightnessStrategy implements DisplayBrightnessStrategy {

    private float mWindowManagerBrightnessOverride = PowerManager.BRIGHTNESS_INVALID_FLOAT;
    private CharSequence mWindowManagerBrightnessOverrideTag = null;

    @Override
    public DisplayBrightnessState updateBrightness(
            StrategyExecutionRequest strategyExecutionRequest) {
        // Todo(b/241308599): Introduce a validator class and add validations before setting
        // the brightness
        DisplayPowerRequest dpr = strategyExecutionRequest.getDisplayPowerRequest();
        BrightnessReason reason = new BrightnessReason(BrightnessReason.REASON_OVERRIDE);

        float brightness = dpr.screenBrightnessOverride;
        if (BrightnessUtils.isValidBrightnessValue(dpr.screenBrightnessOverride)) {
            brightness = dpr.screenBrightnessOverride;
            reason.setTag(dpr.screenBrightnessOverrideTag);
        } else if (BrightnessUtils.isValidBrightnessValue(mWindowManagerBrightnessOverride)) {
            brightness = mWindowManagerBrightnessOverride;
            reason.setTag(mWindowManagerBrightnessOverrideTag);
        }

        return new DisplayBrightnessState.Builder()
                .setBrightness(brightness)
                .setBrightnessReason(reason)
                .setDisplayBrightnessStrategyName(getName())
                .build();
    }

    @Override
    public String getName() {
        return "OverrideBrightnessStrategy";
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("OverrideBrightnessStrategy:");
        writer.println("  mWindowManagerBrightnessOverride=" + mWindowManagerBrightnessOverride);
        writer.println("  mWindowManagerBrightnessOverrideTag="
                + mWindowManagerBrightnessOverrideTag);
    }

    @Override
    public void strategySelectionPostProcessor(
            StrategySelectionNotifyRequest strategySelectionNotifyRequest) {
        // DO NOTHING
    }

    /**
     * Updates the brightness override from WindowManager.
     *
     * @param request The request to override the brightness
     * @return whether this request will result in a change of the brightness
     */
    public boolean updateWindowManagerBrightnessOverride(
            DisplayManagerInternal.DisplayBrightnessOverrideRequest request) {
        float newBrightness = request == null
                ? PowerManager.BRIGHTNESS_INVALID_FLOAT : request.brightness;
        mWindowManagerBrightnessOverrideTag = request == null ? null : request.tag;

        if (floatEquals(newBrightness, mWindowManagerBrightnessOverride)) {
            return false;
        }

        mWindowManagerBrightnessOverride = newBrightness;
        return true;
    }

    /**
     * Returns the current brightness override from WindowManager.
     */
    public float getWindowManagerBrightnessOverride() {
        return mWindowManagerBrightnessOverride;
    }

    private boolean floatEquals(float f1, float f2) {
        return f1 == f2 || (Float.isNaN(f1) && Float.isNaN(f2));
    }

    @Override
    public int getReason() {
        return BrightnessReason.REASON_OVERRIDE;
    }
}
