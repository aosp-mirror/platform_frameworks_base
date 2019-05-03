/**
 * Copyright (c) 2016, The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.custom.hardware;

import com.android.internal.custom.hardware.HSIC;
import com.android.internal.custom.hardware.LiveDisplayConfig;

/** @hide */
interface ILiveDisplayService {
    LiveDisplayConfig getConfig();

    int getMode();
    boolean setMode(int mode);

    float[] getColorAdjustment();
    boolean setColorAdjustment(in float[] adj);

    boolean isAutoContrastEnabled();
    boolean setAutoContrastEnabled(boolean enabled);

    boolean isCABCEnabled();
    boolean setCABCEnabled(boolean enabled);

    boolean isColorEnhancementEnabled();
    boolean setColorEnhancementEnabled(boolean enabled);

    int getDayColorTemperature();
    boolean setDayColorTemperature(int temperature);

    int getNightColorTemperature();
    boolean setNightColorTemperature(int temperature);

    int getColorTemperature();

    boolean isAutomaticOutdoorModeEnabled();
    boolean setAutomaticOutdoorModeEnabled(boolean enabled);

    HSIC getPictureAdjustment();
    HSIC getDefaultPictureAdjustment();
    boolean setPictureAdjustment(in HSIC adj);
    boolean isNight();
}
