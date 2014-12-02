// Copyright 2011 Google Inc. All Rights Reserved.

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;

/**
 * Content descriptions for accessibility support.
 */
public class AccessibilityContentDescriptions {

    private AccessibilityContentDescriptions() {}
    static final int[] PHONE_SIGNAL_STRENGTH = {
        R.string.accessibility_no_phone,
        R.string.accessibility_phone_one_bar,
        R.string.accessibility_phone_two_bars,
        R.string.accessibility_phone_three_bars,
        R.string.accessibility_phone_signal_full
    };

    static final int[] DATA_CONNECTION_STRENGTH = {
        R.string.accessibility_no_data,
        R.string.accessibility_data_one_bar,
        R.string.accessibility_data_two_bars,
        R.string.accessibility_data_three_bars,
        R.string.accessibility_data_signal_full
    };

    static final int[] WIFI_CONNECTION_STRENGTH = {
        R.string.accessibility_no_wifi,
        R.string.accessibility_wifi_one_bar,
        R.string.accessibility_wifi_two_bars,
        R.string.accessibility_wifi_three_bars,
        R.string.accessibility_wifi_signal_full
    };

    static final int WIFI_NO_CONNECTION = R.string.accessibility_no_wifi;
}
