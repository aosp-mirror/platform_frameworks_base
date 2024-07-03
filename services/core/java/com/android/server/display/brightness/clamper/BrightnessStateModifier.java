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

import com.android.server.display.DisplayBrightnessState;

import java.io.PrintWriter;

public interface BrightnessStateModifier {
    /**
     * Applies the changes to brightness state, by modifying properties of the brightness
     * state builder.
     * @param request
     * @param stateBuilder
     */
    void apply(DisplayManagerInternal.DisplayPowerRequest request,
            DisplayBrightnessState.Builder stateBuilder);

    /**
     * Prints contents of this brightness state modifier
     * @param printWriter
     */
    void dump(PrintWriter printWriter);

    /**
     * Called when stopped. Listeners can be unregistered here.
     */
    void stop();

    /**
     *
     * @return whether the brightness state modifier needs to listen to the ambient lux in order to
     * calculate its bounds.
     */
    boolean shouldListenToLightSensor();

    /**
     * Current ambient lux
     * @param lux - ambient lux
     */
    void setAmbientLux(float lux);
}
