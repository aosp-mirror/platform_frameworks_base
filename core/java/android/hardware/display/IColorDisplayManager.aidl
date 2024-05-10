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
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setAppSaturationLevel(String packageName, int saturationLevel);
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean isSaturationActivated();

    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    int getTransformCapabilities();

    boolean isNightDisplayActivated();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setNightDisplayActivated(boolean activated);
    int getNightDisplayColorTemperature();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setNightDisplayColorTemperature(int temperature);
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    int getNightDisplayAutoMode();
    int getNightDisplayAutoModeRaw();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setNightDisplayAutoMode(int autoMode);
    Time getNightDisplayCustomStartTime();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setNightDisplayCustomStartTime(in Time time);
    Time getNightDisplayCustomEndTime();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setNightDisplayCustomEndTime(in Time time);

    int getColorMode();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    void setColorMode(int colorMode);

    boolean isDisplayWhiteBalanceEnabled();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setDisplayWhiteBalanceEnabled(boolean enabled);

    boolean isReduceBrightColorsActivated();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setReduceBrightColorsActivated(boolean activated);
    int getReduceBrightColorsStrength();
    @EnforcePermission("CONTROL_DISPLAY_COLOR_TRANSFORMS")
    boolean setReduceBrightColorsStrength(int strength);
    float getReduceBrightColorsOffsetFactor();
}