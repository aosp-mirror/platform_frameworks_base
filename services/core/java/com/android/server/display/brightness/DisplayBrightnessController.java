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

package com.android.server.display.brightness;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;

import java.io.PrintWriter;

/**
 * Deploys different DozeBrightnessStrategy to choose the current brightness for a specified
 * display. Applies the chosen brightness.
 */
public final class DisplayBrightnessController {
    private final int mDisplayId;
    // Selects an appropriate strategy based on the request provided by the clients.
    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;

    /**
     * The constructor of DisplayBrightnessController.
     */
    public DisplayBrightnessController(Context context, Injector injector, int displayId) {
        if (injector == null) {
            injector = new Injector();
        }
        mDisplayId = displayId;
        mDisplayBrightnessStrategySelector = injector.getDisplayBrightnessStrategySelector(context,
                displayId);
    }

    /**
     * Updates the display brightness. This delegates the responsibility of selecting an appropriate
     * strategy to DisplayBrightnessStrategySelector, which is then applied to evaluate the
     * DisplayBrightnessState. In the future,
     * 1. This will account for clamping the brightness if needed.
     * 2. This will notify the system about the updated brightness
     *
     * @param displayPowerRequest The request to update the brightness
     * @param targetDisplayState  The target display state of the system
     */
    public DisplayBrightnessState updateBrightness(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState) {
        DisplayBrightnessStrategy displayBrightnessStrategy =
                mDisplayBrightnessStrategySelector.selectStrategy(displayPowerRequest,
                        targetDisplayState);
        return displayBrightnessStrategy.updateBrightness(displayPowerRequest);
    }

    /**
     * Returns a boolean flag indicating if the light sensor is to be used to decide the screen
     * brightness when dozing
     */
    public boolean isAllowAutoBrightnessWhileDozingConfig() {
        return mDisplayBrightnessStrategySelector.isAllowAutoBrightnessWhileDozingConfig();
    }

    /**
     * Used to dump the state.
     *
     * @param writer The PrintWriter used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println();
        writer.println("DisplayBrightnessController:");
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, " ");
        mDisplayBrightnessStrategySelector.dump(ipw);
    }

    @VisibleForTesting
    static class Injector {
        DisplayBrightnessStrategySelector getDisplayBrightnessStrategySelector(Context context,
                int displayId) {
            return new DisplayBrightnessStrategySelector(context, /* injector= */ null, displayId);
        }
    }
}
