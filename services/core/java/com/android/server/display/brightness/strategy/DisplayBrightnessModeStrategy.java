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

import com.android.server.display.DisplayBrightnessState;

import java.io.PrintWriter;

/**
 * An interface to define the general skeleton of how a BrightnessModeStrategy should look like
 * This is responsible for deciding the DisplayBrightnessState that the display should change to,
 * not taking into account clamping that might be needed
 */
public interface DisplayBrightnessModeStrategy {
    /**
     * Decides the DisplayBrightnessState that the system should change to.
     *
     * @param displayPowerRequest           The request to evaluate the updated brightness
     * @param displayState                  The target displayState to which the system should
     *                                      change to after processing the request
     * @param displayBrightnessStateBuilder The DisplayBrightnessStateBuilder, consisting of
     *                                      DisplayBrightnessState that have been constructed so far
     */
    DisplayBrightnessState.Builder updateBrightness(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest, int displayState,
            DisplayBrightnessState.Builder displayBrightnessStateBuilder);

    /**
     * Used to dump the state.
     *
     * @param writer The PrintWriter used to dump the state.
     */
    void dump(PrintWriter writer);
}
