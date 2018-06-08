/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;
import android.text.TextUtils;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;

/**
 * Preference controller for WIFI MAC address
 */
public abstract class AbstractWifiMacAddressPreferenceController
        extends AbstractConnectivityPreferenceController {

    @VisibleForTesting
    static final String KEY_WIFI_MAC_ADDRESS = "wifi_mac_address";

    private static final String[] CONNECTIVITY_INTENTS = {
            ConnectivityManager.CONNECTIVITY_ACTION,
            WifiManager.LINK_CONFIGURATION_CHANGED_ACTION,
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
    };

    private Preference mWifiMacAddress;
    private final WifiManager mWifiManager;

    public AbstractWifiMacAddressPreferenceController(Context context, Lifecycle lifecycle) {
        super(context, lifecycle);
        mWifiManager = context.getSystemService(WifiManager.class);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getPreferenceKey() {
        return KEY_WIFI_MAC_ADDRESS;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mWifiMacAddress = screen.findPreference(KEY_WIFI_MAC_ADDRESS);
        updateConnectivity();
    }

    @Override
    protected String[] getConnectivityIntents() {
        return CONNECTIVITY_INTENTS;
    }

    @SuppressLint("HardwareIds")
    @Override
    protected void updateConnectivity() {
        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        final int macRandomizationMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED, 0);
        final String macAddress = wifiInfo == null ? null : wifiInfo.getMacAddress();

        if (TextUtils.isEmpty(macAddress)) {
            mWifiMacAddress.setSummary(R.string.status_unavailable);
        } else if (macRandomizationMode == 1 && WifiInfo.DEFAULT_MAC_ADDRESS.equals(macAddress)) {
            mWifiMacAddress.setSummary(R.string.wifi_status_mac_randomized);
        } else {
            mWifiMacAddress.setSummary(macAddress);
        }
    }
}
