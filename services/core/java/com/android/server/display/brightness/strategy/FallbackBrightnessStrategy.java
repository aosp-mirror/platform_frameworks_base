/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.NonNull;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.StrategyExecutionRequest;
import com.android.server.display.brightness.StrategySelectionNotifyRequest;

import java.io.PrintWriter;

/**
 * Manages the brightness of the associated display when no other strategy qualifies for
 * setting up the brightness state. This strategy is also being used for evaluating the
 * display brightness state when we have a manually set brightness. This is a temporary state, and
 * the logic for evaluating the manual brightness will be moved to a separate strategy
 */
public class FallbackBrightnessStrategy implements DisplayBrightnessStrategy{
    @Override
    public DisplayBrightnessState updateBrightness(
            StrategyExecutionRequest strategyExecutionRequest) {
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_MANUAL);
        return new DisplayBrightnessState.Builder()
                .setBrightness(strategyExecutionRequest.getCurrentScreenBrightness())
                .setSdrBrightness(strategyExecutionRequest.getCurrentScreenBrightness())
                .setBrightnessReason(brightnessReason)
                .setDisplayBrightnessStrategyName(getName())
                // The fallback brightness might change due to clamping. Make sure we tell the rest
                // of the system by updating the setting
                .setShouldUpdateScreenBrightnessSetting(true)
                .setIsUserInitiatedChange(strategyExecutionRequest.isUserSetBrightnessChanged())
                .build();
    }

    @NonNull
    @Override
    public String getName() {
        return DisplayBrightnessStrategyConstants.FALLBACK_BRIGHTNESS_STRATEGY_NAME;
    }

    @Override
    public int getReason() {
        return BrightnessReason.REASON_MANUAL;
    }

    @Override
    public void dump(PrintWriter writer) {

    }

    @Override
    public void strategySelectionPostProcessor(
            StrategySelectionNotifyRequest strategySelectionNotifyRequest) {

    }
}
