/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.lights;

public abstract class Light {
    public static final int LIGHT_FLASH_NONE = 0;
    public static final int LIGHT_FLASH_TIMED = 1;
    public static final int LIGHT_FLASH_HARDWARE = 2;

    /**
     * Light brightness is managed by a user setting.
     */
    public static final int BRIGHTNESS_MODE_USER = 0;

    /**
     * Light brightness is managed by a light sensor.
     */
    public static final int BRIGHTNESS_MODE_SENSOR = 1;

    /**
     * Low-persistence light mode.
     */
    public static final int BRIGHTNESS_MODE_LOW_PERSISTENCE = 2;

    public abstract void setBrightness(int brightness);
    public abstract void setBrightness(int brightness, int brightnessMode);
    public abstract void setColor(int color);
    public abstract void setFlashing(int color, int mode, int onMS, int offMS);
    public abstract void pulse();
    public abstract void pulse(int color, int onMS);
    public abstract void turnOff();
}
