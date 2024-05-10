/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.wifi.dpp;

import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import java.util.List;


/**
 * Wifi dpp intent helper functions to share between the Settings App and SystemUI.
 */
public class WifiDppIntentHelper {
    /**
     * Action added to the intent when app wants to launch the QR code generator with lock screen.
     */
    public static final String ACTION_CONFIGURATOR_AUTH_QR_CODE_GENERATOR =
            "android.settings.WIFI_DPP_CONFIGURATOR_AUTH_QR_CODE_GENERATOR";
    static final String EXTRA_WIFI_SECURITY = "security";

    /** The data corresponding to {@code WifiConfiguration} SSID */
    static final String EXTRA_WIFI_SSID = "ssid";

    /** The data corresponding to {@code WifiConfiguration} preSharedKey */
    static final String EXTRA_WIFI_PRE_SHARED_KEY = "preSharedKey";

    /** The data corresponding to {@code WifiConfiguration} hiddenSSID */
    static final String EXTRA_WIFI_HIDDEN_SSID = "hiddenSsid";
    static final String SECURITY_NO_PASSWORD = "nopass"; //open network or OWE
    static final String SECURITY_WEP = "WEP";
    static final String SECURITY_WPA_PSK = "WPA";
    static final String SECURITY_SAE = "SAE";

    /**
     * Set all extra except {@code EXTRA_WIFI_NETWORK_ID} for the intent to
     * launch configurator activity later.
     *
     * @param intent the target to set extra
     * @param wifiManager an instance of {@code WifiManager}
     * @param wifiConfiguration the Wi-Fi network for launching configurator activity
     */
    public static void setConfiguratorIntentExtra(Intent intent, WifiManager wifiManager,
            WifiConfiguration wifiConfiguration) {
        String ssid = removeFirstAndLastDoubleQuotes(wifiConfiguration.SSID);
        String security = getSecurityString(wifiConfiguration);

        // When the value of this key is read, the actual key is not returned, just a "*".
        // Call privileged system API to obtain actual key.
        String preSharedKey = removeFirstAndLastDoubleQuotes(getPresharedKey(wifiManager,
                wifiConfiguration));

        if (!TextUtils.isEmpty(ssid)) {
            intent.putExtra(EXTRA_WIFI_SSID, ssid);
        }
        if (!TextUtils.isEmpty(security)) {
            intent.putExtra(EXTRA_WIFI_SECURITY, security);
        }
        if (!TextUtils.isEmpty(preSharedKey)) {
            intent.putExtra(EXTRA_WIFI_PRE_SHARED_KEY, preSharedKey);
        }
        intent.putExtra(EXTRA_WIFI_HIDDEN_SSID, wifiConfiguration.hiddenSSID);
    }

    private static String getSecurityString(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            return SECURITY_SAE;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) {
            return SECURITY_NO_PASSWORD;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)) {
            return SECURITY_WPA_PSK;
        }
        return (config.wepKeys[0] == null) ? SECURITY_NO_PASSWORD : SECURITY_WEP;
    }

    private static String removeFirstAndLastDoubleQuotes(String str) {
        if (TextUtils.isEmpty(str)) {
            return str;
        }

        int begin = 0;
        int end = str.length() - 1;
        if (str.charAt(begin) == '\"') {
            begin++;
        }
        if (str.charAt(end) == '\"') {
            end--;
        }
        return str.substring(begin, end + 1);
    }

    private static String getPresharedKey(WifiManager wifiManager,
            WifiConfiguration wifiConfiguration) {
        List<WifiConfiguration> privilegedWifiConfigurations =
                wifiManager.getPrivilegedConfiguredNetworks();

        for (WifiConfiguration privilegedWifiConfiguration : privilegedWifiConfigurations) {
            if (privilegedWifiConfiguration.networkId == wifiConfiguration.networkId) {
                // WEP uses a shared key hence the AuthAlgorithm.SHARED is used
                // to identify it.
                if (wifiConfiguration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                        && wifiConfiguration.allowedAuthAlgorithms.get(
                        WifiConfiguration.AuthAlgorithm.SHARED)) {
                    return privilegedWifiConfiguration
                            .wepKeys[privilegedWifiConfiguration.wepTxKeyIndex];
                } else {
                    return privilegedWifiConfiguration.preSharedKey;
                }
            }
        }
        return wifiConfiguration.preSharedKey;
    }
}
