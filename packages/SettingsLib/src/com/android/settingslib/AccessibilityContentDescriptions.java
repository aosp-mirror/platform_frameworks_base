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

    public static final int NO_CALLING = R.string.accessibility_no_calling;

    public static final int[] ETHERNET_CONNECTION_VALUES = {
        R.string.accessibility_ethernet_disconnected,
        R.string.accessibility_ethernet_connected,
    };
}
