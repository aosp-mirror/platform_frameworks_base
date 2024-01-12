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
import android.text.TextUtils;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;

/**
 * Preference controller for WIFI MAC address
 */
public abstract class AbstractWifiMacAddressPreferenceController
        extends AbstractConnectivityPreferenceController {

    @VisibleForTesting
    static final String KEY_WIFI_MAC_ADDRESS = "wifi_mac_address";
    @VisibleForTesting
    static final int OFF = 0;
    @VisibleForTesting
    static final int ON = 1;

    private static final String[] CONNECTIVITY_INTENTS = {
            ConnectivityManager.CONNECTIVITY_ACTION,
            WifiManager.ACTION_LINK_CONFIGURATION_CHANGED,
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
        return mWifiManager != null;
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
        if (mWifiManager == null || mWifiMacAddress == null) {
            return;
        }

        final String[] macAddresses = mWifiManager.getFactoryMacAddresses();
        String macAddress = null;
        if (macAddresses != null && macAddresses.length > 0) {
            macAddress = macAddresses[0];
        }

        if (TextUtils.isEmpty(macAddress) || macAddress.equals(WifiInfo.DEFAULT_MAC_ADDRESS)) {
            mWifiMacAddress.setSummary(R.string.status_unavailable);
        } else {
            mWifiMacAddress.setSummary(macAddress);
        }
    }
}
