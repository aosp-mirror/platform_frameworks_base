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
import android.net.NetworkKey;
import android.net.WifiKey;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

public class WifiStatusTracker {
    private static final String TAG = "WifiStatusTracker";

    private final WifiManager mWifiManager;
    public boolean enabled;
    public int state;
    public boolean connected;
    public boolean connecting;
    public String ssid;
    public int rssi;
    public int level;
    public NetworkKey networkKey;

    public WifiStatusTracker(WifiManager wifiManager) {
        mWifiManager = wifiManager;
    }

    public void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN);
            enabled = state == WifiManager.WIFI_STATE_ENABLED;


            enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            final NetworkInfo networkInfo = (NetworkInfo)
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            connecting = networkInfo != null && !networkInfo.isConnected()
                    && networkInfo.isConnectedOrConnecting();
            connected = networkInfo != null && networkInfo.isConnected();
            WifiInfo info = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO) != null
                    ? (WifiInfo) intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO)
                    : mWifiManager.getConnectionInfo();

            // If Connected grab the signal strength and ssid.
            if (connected && info != null) {
                ssid = getSsid(info);
                String bssid = info.getBSSID();
                if ((ssid != null) && (bssid != null)) {
                    // Reuse existing network key object if possible.
                    if ((networkKey == null)
                            || !networkKey.wifiKey.ssid.equals(ssid)
                            || !networkKey.wifiKey.bssid.equals(bssid)) {
                        try {
                            networkKey = new NetworkKey(
                                    new WifiKey(ssid, bssid));
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Cannot create NetworkKey", e);
                        }
                    }
                } else {
                    networkKey = null;
                }
            } else {
                ssid = null;
                networkKey = null;
            }
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            // Default to -200 as its below WifiManager.MIN_RSSI.
            rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            level = WifiManager.calculateSignalLevel(rssi, 5);
        }
    }

    private String getSsid(WifiInfo info) {
        String ssid = info.getSSID();
        if (ssid != null) {
            return ssid;
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
