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
package com.android.settingslib.mobile;

import android.telephony.Annotation;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

/**
 * Holds the utility functions to create the RAT to MobileIconGroup mappings.
 */
public class MobileMappings {

    /**
     * Generates the RAT key from the TelephonyDisplayInfo.
     */
    public static String getIconKey(TelephonyDisplayInfo telephonyDisplayInfo) {
        if (telephonyDisplayInfo.getOverrideNetworkType()
                == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE) {
            return toIconKey(telephonyDisplayInfo.getNetworkType());
        } else {
            return toDisplayIconKey(telephonyDisplayInfo.getOverrideNetworkType());
        }
    }

    /**
     * Converts the networkType into the RAT key.
     */
    public static String toIconKey(@Annotation.NetworkType int networkType) {
        return Integer.toString(networkType);
    }

    /**
     * Converts the displayNetworkType into the RAT key.
     */
    public static String toDisplayIconKey(@Annotation.OverrideNetworkType int displayNetworkType) {
        switch (displayNetworkType) {
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA:
                return toIconKey(TelephonyManager.NETWORK_TYPE_LTE) + "_CA";
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO:
                return toIconKey(TelephonyManager.NETWORK_TYPE_LTE) + "_CA_Plus";
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA:
                return toIconKey(TelephonyManager.NETWORK_TYPE_NR);
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE:
                return toIconKey(TelephonyManager.NETWORK_TYPE_NR) + "_Plus";
            default:
                return "unsupported";
        }
    }
}
