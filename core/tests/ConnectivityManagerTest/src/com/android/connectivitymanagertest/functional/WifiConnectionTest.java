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
import com.android.connectivitymanagertest.ConnectivityManagerTestRunner;

import android.R;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.Status;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import com.android.internal.util.AsyncChannel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final boolean DEBUG = false;
    private List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
    private ConnectivityManagerTestActivity mAct;
    private ConnectivityManagerTestRunner mRunner;
    private WifiManager mWifiManager = null;
    private WifiManager.Channel mChannel;
    private Set<WifiConfiguration> enabledNetworks = null;

    public WifiConnectionTest() {
        super(ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mRunner = ((ConnectivityManagerTestRunner)getInstrumentation());
        mWifiManager = (WifiManager) mRunner.getContext().getSystemService(Context.WIFI_SERVICE);

        mAct = getActivity();
        mChannel = mWifiManager.initialize(mAct, mAct.getMainLooper(), null);

        networks = mAct.loadNetworkConfigurations();
        if (DEBUG) {
            printNetworkConfigurations();
        }

        // enable Wifi and verify wpa_supplicant is started
        assertTrue("enable Wifi failed", mAct.enableWifi());
        sleep(2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT,
                "interrupted while waiting for WPA_SUPPLICANT to start");
        WifiInfo mConnection = mAct.mWifiManager.getConnectionInfo();
        assertNotNull(mConnection);
        assertTrue("wpa_supplicant is not started ", mAct.mWifiManager.pingSupplicant());
    }

    private class WifiServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        //AsyncChannel in msg.obj
                    } else {
                        log("Failed to establish AsyncChannel connection");
                    }
                    break;
                default:
                    //Ignore
                    break;
            }
        }
    }

    private void printNetworkConfigurations() {
        log("==== print network configurations parsed from XML file ====");
        log("number of access points: " + networks.size());
        for (WifiConfiguration config : networks) {
            log(config.toString());
        }
    }

    @Override
    public void tearDown() throws Exception {
        log("tearDown()");
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
        assertTrue(mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.CONNECTED, 6 * ConnectivityManagerTestActivity.LONG_TIMEOUT));

        // step 3: verify the current connected network is the given SSID
        assertNotNull("Wifi connection returns null", mAct.mWifiManager.getConnectionInfo());
        if (DEBUG) {
            log("config.SSID = " + config.SSID);
            log("mAct.mWifiManager.getConnectionInfo.getSSID()" +
                    mAct.mWifiManager.getConnectionInfo().getSSID());
        }
        assertTrue(config.SSID.contains(mAct.mWifiManager.getConnectionInfo().getSSID()));
    }

    private void sleep(long sometime, String errorMsg) {
        try {
            Thread.sleep(sometime);
        } catch (InterruptedException e) {
            fail(errorMsg);
        }
    }

    private void log(String message) {
        Log.v(TAG, message);
    }

    @LargeTest
    public void testWifiConnections() {
        for (int i = 0; i < networks.size(); i++) {
            String ssid = networks.get(i).SSID;
            log("-- START Wi-Fi connection test to : " + ssid + " --");
            connectToWifi(networks.get(i));
            // wait for 2 minutes between wifi stop and start
            sleep(ConnectivityManagerTestActivity.WIFI_STOP_START_INTERVAL,
                  "interruped while connected to wifi");
            log("-- END Wi-Fi connection test to " + ssid + " -- ");
        }
    }
}
