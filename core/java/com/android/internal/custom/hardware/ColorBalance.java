/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (C) 2018 The LineageOS Project
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

package com.android.internal.custom.hardware;

/**
 * Color balance support
 *
 * Color balance controls allow direct adjustment of display color temperature
 * using a range of values. A zero implies no adjustment, negative values
 * move towards warmer temperatures, and positive values move towards
 * cool temperatures.
 */
public class ColorBalance {

    /**
     * Whether device supports color balance control
     *
     * @return boolean Supported devices must return always true
     */
    public static boolean isSupported() {
        return false;
    }

    /**
     * This method returns the current color balance value
     *
     * @return int Zero when no adjustment is made, negative values move
     * towards warmer temperatures, positive values move towards cooler temperatures.
     */
    public static int getValue() {
        return 0;
    }

    /**
     * This method allows to set the display color  balance
     *
     * @param value
     * @return boolean Must be false if feature is not supported or the operation
     * failed; true in any other case.
     */
    public static boolean setValue(int value) {
        return false;
    }

    /**
     * Get the minimum allowed color adjustment value
     * @return int
     */
    public static int getMinValue() {
        return 0;
    }

    /**
     * Get the maximum allowed color adjustment value
     * @return int
     */
    public static int getMaxValue() {
        return 0;
    }
}
