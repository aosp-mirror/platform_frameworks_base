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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.MobileSignalController.MobileIconGroup;

import java.util.HashMap;
import java.util.Map;

class TelephonyIcons {
    //***** Data connection icons
    static final int FLIGHT_MODE_ICON = R.drawable.stat_sys_airplane_mode;

    static final int ICON_LTE = R.drawable.ic_lte_mobiledata;
    static final int ICON_LTE_PLUS = R.drawable.ic_lte_plus_mobiledata;
    static final int ICON_G = R.drawable.ic_g_mobiledata;
    static final int ICON_E = R.drawable.ic_e_mobiledata;
    static final int ICON_H = R.drawable.ic_h_mobiledata;
    static final int ICON_H_PLUS = R.drawable.ic_h_plus_mobiledata;
    static final int ICON_3G = R.drawable.ic_3g_mobiledata;
    static final int ICON_4G = R.drawable.ic_4g_mobiledata;
    static final int ICON_4G_PLUS = R.drawable.ic_4g_plus_mobiledata;
    static final int ICON_1X = R.drawable.ic_1x_mobiledata;
    static final int ICON_5G = R.drawable.ic_5g_mobiledata;
    static final int ICON_5G_PLUS = R.drawable.ic_5g_plus_mobiledata;

    static final MobileIconGroup CARRIER_NETWORK_CHANGE = new MobileIconGroup(
            "CARRIER_NETWORK_CHANGE",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.carrier_network_change_mode,
            0,
            false);

    static final MobileIconGroup THREE_G = new MobileIconGroup(
            "3G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3g,
            TelephonyIcons.ICON_3G,
            true);

    static final MobileIconGroup WFC = new MobileIconGroup(
            "WFC",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            0, 0, false);

    static final MobileIconGroup UNKNOWN = new MobileIconGroup(
            "Unknown",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            0, 0, false);

    static final MobileIconGroup E = new MobileIconGroup(
            "E",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_edge,
            TelephonyIcons.ICON_E,
            false);

    static final MobileIconGroup ONE_X = new MobileIconGroup(
            "1X",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_cdma,
            TelephonyIcons.ICON_1X,
            true);

    static final MobileIconGroup G = new MobileIconGroup(
            "G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_gprs,
            TelephonyIcons.ICON_G,
            false);

    static final MobileIconGroup H = new MobileIconGroup(
            "H",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3_5g,
            TelephonyIcons.ICON_H,
            false);

    static final MobileIconGroup H_PLUS = new MobileIconGroup(
            "H+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3_5g_plus,
            TelephonyIcons.ICON_H_PLUS,
            false);

    static final MobileIconGroup FOUR_G = new MobileIconGroup(
            "4G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_4g,
            TelephonyIcons.ICON_4G,
            true);

    static final MobileIconGroup FOUR_G_PLUS = new MobileIconGroup(
            "4G+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0,0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_4g_plus,
            TelephonyIcons.ICON_4G_PLUS,
            true);

    static final MobileIconGroup LTE = new MobileIconGroup(
            "LTE",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_lte,
            TelephonyIcons.ICON_LTE,
            true);

    static final MobileIconGroup LTE_PLUS = new MobileIconGroup(
            "LTE+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_lte_plus,
            TelephonyIcons.ICON_LTE_PLUS,
            true);

    static final MobileIconGroup NR_5G = new MobileIconGroup(
            "5G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0,
            0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_5g,
            TelephonyIcons.ICON_5G,
            true);

    static final MobileIconGroup NR_5G_PLUS = new MobileIconGroup(
            "5G_PLUS",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0,
            0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_5g_plus,
            TelephonyIcons.ICON_5G_PLUS,
            true);

    static final MobileIconGroup DATA_DISABLED = new MobileIconGroup(
            "DataDisabled",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.cell_data_off_content_description,
            0,
            false);

    static final MobileIconGroup NOT_DEFAULT_DATA = new MobileIconGroup(
            "NotDefaultData",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.not_default_data_content_description,
            0,
            false);

    // When adding a new MobileIconGround, check if the dataContentDescription has to be filtered
    // in QSCarrier#hasValidTypeContentDescription

    /** Mapping icon name(lower case) to the icon object. */
    static final Map<String, MobileIconGroup> ICON_NAME_TO_ICON;
    static {
        ICON_NAME_TO_ICON = new HashMap<>();
        ICON_NAME_TO_ICON.put("carrier_network_change", CARRIER_NETWORK_CHANGE);
        ICON_NAME_TO_ICON.put("3g", THREE_G);
        ICON_NAME_TO_ICON.put("wfc", WFC);
        ICON_NAME_TO_ICON.put("unknown", UNKNOWN);
        ICON_NAME_TO_ICON.put("e", E);
        ICON_NAME_TO_ICON.put("1x", ONE_X);
        ICON_NAME_TO_ICON.put("g", G);
        ICON_NAME_TO_ICON.put("h", H);
        ICON_NAME_TO_ICON.put("h+", H_PLUS);
        ICON_NAME_TO_ICON.put("4g", FOUR_G);
        ICON_NAME_TO_ICON.put("4g+", FOUR_G_PLUS);
        ICON_NAME_TO_ICON.put("lte", LTE);
        ICON_NAME_TO_ICON.put("lte+", LTE_PLUS);
        ICON_NAME_TO_ICON.put("5g", NR_5G);
        ICON_NAME_TO_ICON.put("5g_plus", NR_5G_PLUS);
        ICON_NAME_TO_ICON.put("datadisable", DATA_DISABLED);
        ICON_NAME_TO_ICON.put("notdefaultdata", NOT_DEFAULT_DATA);
    }
}

