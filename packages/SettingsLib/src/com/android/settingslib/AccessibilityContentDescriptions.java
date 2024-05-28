/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settingslib;

/**
 * Content descriptions for accessibility support.
 */
public class AccessibilityContentDescriptions {

    private AccessibilityContentDescriptions() {}

    public static final int PHONE_SIGNAL_STRENGTH_NONE = R.string.accessibility_no_phone;

    public static final int[] PHONE_SIGNAL_STRENGTH = {
        PHONE_SIGNAL_STRENGTH_NONE,
        R.string.accessibility_phone_one_bar,
        R.string.accessibility_phone_two_bars,
        R.string.accessibility_phone_three_bars,
        R.string.accessibility_phone_signal_full
    };

    /**
     * @param level int in range [0-4] that describes the signal level
     * @return the appropriate content description for that signal strength, or 0 if the param is
     *         invalid
     */
    public static int getDescriptionForLevel(int level) {
        if (level > 4 || level < 0) {
            return 0;
        }

        return PHONE_SIGNAL_STRENGTH[level];
    }

    public static final int[] PHONE_SIGNAL_STRENGTH_INFLATED = {
            PHONE_SIGNAL_STRENGTH_NONE,
            R.string.accessibility_phone_one_bar,
            R.string.accessibility_phone_two_bars,
            R.string.accessibility_phone_three_bars,
            R.string.accessibility_phone_four_bars,
            R.string.accessibility_phone_signal_full
    };

    /**
     * @param level int in range [0-5] that describes the inflated signal level
     * @return the appropriate content description for that signal strength, or 0 if the param is
     *         invalid
     */
    public static int getDescriptionForInflatedLevel(int level) {
        if (level > 5 || level < 0) {
            return 0;
        }

        return PHONE_SIGNAL_STRENGTH_INFLATED[level];
    }

    /**
     * @param level int in range [0-5] that describes the inflated signal level
     * @param numberOfLevels one of (4, 5) that describes the default number of levels, or the
     *                       inflated number of levels. The level param should be relative to the
     *                       number of levels. This won't do any inflation.
     * @return the appropriate content description for that signal strength, or 0 if the param is
     *         invalid
     */
    public static int getDescriptionForLevel(int level, int numberOfLevels) {
        if (numberOfLevels == 5) {
            return getDescriptionForLevel(level);
        } else if (numberOfLevels == 6) {
            return getDescriptionForInflatedLevel(level);
        } else {
            return 0;
        }
    }

    public static final int[] DATA_CONNECTION_STRENGTH = {
        R.string.accessibility_no_data,
        R.string.accessibility_data_one_bar,
        R.string.accessibility_data_two_bars,
        R.string.accessibility_data_three_bars,
        R.string.accessibility_data_signal_full
    };

    public static final int[] WIFI_CONNECTION_STRENGTH = {
        R.string.accessibility_no_wifi,
        R.string.accessibility_wifi_one_bar,
        R.string.accessibility_wifi_two_bars,
        R.string.accessibility_wifi_three_bars,
        R.string.accessibility_wifi_signal_full
    };

    public static final int WIFI_NO_CONNECTION = R.string.accessibility_no_wifi;
    public static final int WIFI_OTHER_DEVICE_CONNECTION = R.string.accessibility_wifi_other_device;

    public static final int NO_CALLING = R.string.accessibility_no_calling;

    public static final int[] ETHERNET_CONNECTION_VALUES = {
        R.string.accessibility_ethernet_disconnected,
        R.string.accessibility_ethernet_connected,
    };
}
