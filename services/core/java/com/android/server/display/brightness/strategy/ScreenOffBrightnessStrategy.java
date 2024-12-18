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

import android.os.PowerManager;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;
import com.android.server.display.brightness.StrategyExecutionRequest;
import com.android.server.display.brightness.StrategySelectionNotifyRequest;

import java.io.PrintWriter;

/**
 * Manages the brightness of the display when the system is in the ScreenOff state.
 */
public class ScreenOffBrightnessStrategy implements DisplayBrightnessStrategy {
    @Override
    public DisplayBrightnessState updateBrightness(
            StrategyExecutionRequest strategyExecutionRequest) {
        // Todo(b/241308599): Introduce a validator class and add validations before setting
        // the brightness
        return BrightnessUtils.constructDisplayBrightnessState(BrightnessReason.REASON_SCREEN_OFF,
                PowerManager.BRIGHTNESS_OFF_FLOAT, getName());
    }

    @Override
    public String getName() {
        return "ScreenOffBrightnessStrategy";
    }

    @Override
    public void dump(PrintWriter writer) {}

    @Override
    public void strategySelectionPostProcessor(
            StrategySelectionNotifyRequest strategySelectionNotifyRequest) {
        // DO NOTHING
    }

    @Override
    public int getReason() {
        return BrightnessReason.REASON_SCREEN_OFF;
    }
}
