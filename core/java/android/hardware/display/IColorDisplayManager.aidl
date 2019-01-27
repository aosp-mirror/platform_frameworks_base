/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.display;

import android.hardware.display.Time;

/** @hide */
interface IColorDisplayManager {
    boolean isDeviceColorManaged();

    boolean setSaturationLevel(int saturationLevel);
    boolean setAppSaturationLevel(String packageName, int saturationLevel);

    int getTransformCapabilities();

    boolean isNightDisplayActivated();
    boolean setNightDisplayActivated(boolean activated);
    int getNightDisplayColorTemperature();
    boolean setNightDisplayColorTemperature(int temperature);
    int getNightDisplayAutoMode();
    int getNightDisplayAutoModeRaw();
    boolean setNightDisplayAutoMode(int autoMode);
    Time getNightDisplayCustomStartTime();
    boolean setNightDisplayCustomStartTime(in Time time);
    Time getNightDisplayCustomEndTime();
    boolean setNightDisplayCustomEndTime(in Time time);

    int getColorMode();
    void setColorMode(int colorMode);
}