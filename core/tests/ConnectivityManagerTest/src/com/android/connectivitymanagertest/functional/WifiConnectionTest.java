/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.connectivitymanagertest.functional;

import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;
import com.android.connectivitymanagertest.NetworkState;

import android.R;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Test Wi-Fi connection with different configuration
 * To run this tests:
 *     adb shell am instrument -e class
 *          com.android.connectivitymanagertest.functional.WifiConnectionTest
 *          -w com.android.connectivitymanagertest/.ConnectivityManagerTestRunner
 */
public class WifiConnectionTest
    extends ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {
    private static final String TAG = "WifiConnectionTest";
    private static final boolean DEBUG = true;
    private static final String PKG_NAME = "com.android.connectivitymanagertests";
    private List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
    private ConnectivityManagerTestActivity mAct;

    public WifiConnectionTest() {
        super(PKG_NAME, ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mAct = getActivity();
        networks = mAct.loadNetworkConfigurations();
        if (DEBUG) {
            printNetworkConfigurations();
        }

        // enable Wifi and verify wpa_supplicant is started
        assertTrue("enable Wifi failed", mAct.enableWifi());
        try {
            Thread.sleep( 2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT);
        } catch (Exception e) {
            fail("interrupted while waiting for WPA_SUPPLICANT to start");
        }
        WifiInfo mConnection = mAct.mWifiManager.getConnectionInfo();
        assertNotNull(mConnection);
        assertTrue("wpa_supplicant is not started ", mAct.mWifiManager.pingSupplicant());
    }

    private void printNetworkConfigurations() {
        Log.v(TAG, "==== print network configurations parsed from XML file ====");
        Log.v(TAG, "number of access points: " + networks.size());
        for (WifiConfiguration config : networks) {
            Log.v(TAG, config.toString());
        }
    }

    @Override
    public void tearDown() throws Exception {
        mAct.removeConfiguredNetworksAndDisableWifi();
        super.tearDown();
    }

    /**
     * Connect to the provided Wi-Fi network
     * @param config is the network configuration
     * @return true if the connection is successful.
     */
    private void connectToWifi(WifiConfiguration config) {
        // step 1: connect to the test access point
        assertTrue("failed to connect to " + config.SSID,
                mAct.connectToWifiWithConfiguration(config));

        // step 2: verify Wifi state and network state;
        assertTrue(mAct.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                ConnectivityManagerTestActivity.SHORT_TIMEOUT));
        assertTrue(mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.CONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));

        // step 3: verify the current connected network is the given SSID
        if (DEBUG) {
            Log.v(TAG, "config.SSID = " + config.SSID);
            Log.v(TAG, "mAct.mWifiManager.getConnectionInfo.getSSID()" +
                    mAct.mWifiManager.getConnectionInfo().getSSID());
        }
        assertTrue(config.SSID.contains(mAct.mWifiManager.getConnectionInfo().getSSID()));

        // Maintain the connection for 50 seconds before switching
        try {
            Thread.sleep(50*1000);
        } catch (Exception e) {
            fail("interrupted while waiting for WPA_SUPPLICANT to start");
        }
    }

    @LargeTest
    public void testWifiConnections() {
        for (int i = 0; i < networks.size(); i++) {
            connectToWifi(networks.get(i));
            mAct.removeConfiguredNetworksAndDisableWifi();
        }
    }
}
