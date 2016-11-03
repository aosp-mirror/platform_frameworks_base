/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.android.settingslib.wifi;

import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.text.TextUtils;

import java.util.List;

public class WifiStatusTracker {

    private final WifiManager mWifiManager;
    public boolean enabled;
    public boolean connected;
    public String ssid;
    public int rssi;
    public int level;

    public WifiStatusTracker(WifiManager wifiManager) {
        mWifiManager = wifiManager;
    }

    public void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            final NetworkInfo networkInfo = (NetworkInfo)
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            connected = networkInfo != null && networkInfo.isConnected();
            // If Connected grab the signal strength and ssid.
            if (connected) {
                // try getting it out of the intent first
                WifiInfo info = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO) != null
                        ? (WifiInfo) intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO)
                        : mWifiManager.getConnectionInfo();
                if (info != null) {
                    ssid = getSsid(info);
                } else {
                    ssid = null;
                }
            } else if (!connected) {
                ssid = null;
            }
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            // Default to -200 as its below WifiManager.MIN_RSSI.
            rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            level = WifiManager.calculateSignalLevel(rssi, 5);
        }
    }

    private String getSsid(WifiInfo info) {
        WifiSsid ssid = info.getWifiSsid();
        if (ssid != null) {
            String ssidString = ssid.toString();
            return TextUtils.isEmpty(ssidString) ? ssid.getHexString() : ssidString;
        }
        // OK, it's not in the connectionInfo; we have to go hunting for it
        List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        int length = networks.size();
        for (int i = 0; i < length; i++) {
            if (networks.get(i).networkId == info.getNetworkId()) {
                return networks.get(i).SSID;
            }
        }
        return null;
    }
}
