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

import android.annotation.NonNull;
import android.hardware.display.DisplayManagerInternal;

import com.android.server.display.DisplayBrightnessState;

import java.io.PrintWriter;

/**
 * Decides the DisplayBrighntessState that the display should change to based on strategy-specific
 * logic within each implementation. Clamping should be done outside of DisplayBrightnessStrategy if
 * not an integral part of the strategy.
 */
public interface DisplayBrightnessStrategy {
    /**
     * Decides the DisplayBrightnessState that the system should change to.
     *
     * @param displayPowerRequest The request to evaluate the updated brightness
     */
    DisplayBrightnessState updateBrightness(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest);

    /**
     * Returns the name of the Strategy
     */
    @NonNull
    String getName();

    /**
     * Dumps the state of the Strategy
     * @param writer
     */
    void dump(PrintWriter writer);
}
