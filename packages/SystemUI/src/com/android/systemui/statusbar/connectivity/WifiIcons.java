/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity;

import com.android.settingslib.AccessibilityContentDescriptions;
import com.android.settingslib.R;
import com.android.settingslib.SignalIcon.IconGroup;

/** */
public class WifiIcons {

    public static final int[] WIFI_FULL_ICONS = {
            com.android.internal.R.drawable.ic_wifi_signal_0,
            com.android.internal.R.drawable.ic_wifi_signal_1,
            com.android.internal.R.drawable.ic_wifi_signal_2,
            com.android.internal.R.drawable.ic_wifi_signal_3,
            com.android.internal.R.drawable.ic_wifi_signal_4
    };

    public static final int[] WIFI_NO_INTERNET_ICONS = {
            R.drawable.ic_no_internet_wifi_signal_0,
            R.drawable.ic_no_internet_wifi_signal_1,
            R.drawable.ic_no_internet_wifi_signal_2,
            R.drawable.ic_no_internet_wifi_signal_3,
            R.drawable.ic_no_internet_wifi_signal_4
    };

    public static final int[][] QS_WIFI_SIGNAL_STRENGTH = {
            WIFI_NO_INTERNET_ICONS,
            WIFI_FULL_ICONS
    };

    static final int[][] WIFI_SIGNAL_STRENGTH = QS_WIFI_SIGNAL_STRENGTH;

    public static final int QS_WIFI_DISABLED = com.android.internal.R.drawable.ic_wifi_signal_0;
    public static final int QS_WIFI_NO_NETWORK = com.android.internal.R.drawable.ic_wifi_signal_0;
    public static final int WIFI_NO_NETWORK = QS_WIFI_NO_NETWORK;

    static final int WIFI_LEVEL_COUNT = WIFI_SIGNAL_STRENGTH[0].length;

    public static final IconGroup UNMERGED_WIFI = new IconGroup(
            "Wi-Fi Icons",
            WifiIcons.WIFI_SIGNAL_STRENGTH,
            WifiIcons.QS_WIFI_SIGNAL_STRENGTH,
            AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH,
            WifiIcons.WIFI_NO_NETWORK,
            WifiIcons.QS_WIFI_NO_NETWORK,
            WifiIcons.WIFI_NO_NETWORK,
            WifiIcons.QS_WIFI_NO_NETWORK,
            AccessibilityContentDescriptions.WIFI_NO_CONNECTION
    );
}
