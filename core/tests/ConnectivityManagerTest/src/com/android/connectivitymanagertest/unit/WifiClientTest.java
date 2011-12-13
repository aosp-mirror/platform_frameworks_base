/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.connectivitymanagertest.unit;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Message;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.Status;
import android.net.wifi.SupplicantState;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.AndroidTestCase;

import java.util.ArrayList;
import java.util.List;

import android.util.Log;

/**
 * Test wifi client
 */
public class WifiClientTest extends AndroidTestCase {

    private WifiManager mWifiManager;
    private final String TAG = "WifiClientTest";

    //10s delay for turning on wifi
    private static final int DELAY = 10000;
    private WifiStateListener mWifiStateListener;
    int mWifiState;
    int mDisableBroadcastCounter = 0;
    int mEnableBroadcastCounter = 0;
    NetworkInfo mNetworkInfo;
    boolean mSupplicantConnection;
    SupplicantState mSupplicantState;

    private class WifiStateListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                switch (mWifiState) {
                    case WifiManager.WIFI_STATE_DISABLING:
                        if (mDisableBroadcastCounter == 0) mDisableBroadcastCounter++;
                        break;
                    case WifiManager.WIFI_STATE_DISABLED:
                        if (mDisableBroadcastCounter == 1) mDisableBroadcastCounter++;
                        break;
                    case WifiManager.WIFI_STATE_ENABLING:
                        if (mEnableBroadcastCounter == 0) mEnableBroadcastCounter++;
                        break;
                    case WifiManager.WIFI_STATE_ENABLED:
                        if (mEnableBroadcastCounter == 1) mEnableBroadcastCounter++;
                        break;
                }
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                mNetworkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            } else if (action.equals(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)) {
                mSupplicantState = (SupplicantState)
                        intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
            } else if (action.equals(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)) {
                mSupplicantConnection =
                        intent.getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, false);
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // register a connectivity receiver for CONNECTIVITY_ACTION;

        mWifiStateListener = new WifiStateListener();
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        getContext().registerReceiver(mWifiStateListener, mIntentFilter);

        mWifiManager = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
        mWifiManager.setWifiEnabled(true);
        assertNotNull(mWifiManager);
    }

    private void sleepAfterWifiEnable() {
        try {
            Thread.sleep(DELAY);
        } catch (Exception e) {
            fail("Sleep timeout " + e);
        }
    }

    // Test case 1: add/remove a open network
    @LargeTest
    public void testAddRemoveNetwork() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"TestSSID1\"";
        config.allowedKeyManagement.set(KeyMgmt.NONE);

        //add
        int netId = mWifiManager.addNetwork(config);
        assertTrue(netId != -1);

        //check config list
        List<WifiConfiguration> configList = mWifiManager.getConfiguredNetworks();
        boolean found = false;
        for (WifiConfiguration c : configList) {
            if (c.networkId == netId && c.SSID.equals(config.SSID)) {
                found = true;
            }
        }
        assertTrue(found);

        //remove
        boolean ret = mWifiManager.removeNetwork(netId);
        assertTrue(ret);

        //check config list
        configList = mWifiManager.getConfiguredNetworks();
        found = false;
        for (WifiConfiguration c : configList) {
            if (c.networkId == netId) {
                found = true;
            }
        }

        assertFalse(found);
    }

    // Test case 2: enable/disable a open network
    @LargeTest
    public void testEnableDisableNetwork() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"TestSSID2\"";
        config.allowedKeyManagement.set(KeyMgmt.NONE);

        //add
        int netId = mWifiManager.addNetwork(config);
        assertTrue(netId != -1);

        //enable network and disable others
        boolean ret = mWifiManager.enableNetwork(netId, true);
        assertTrue(ret);

        //check config list
        List<WifiConfiguration> configList = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration c : configList) {
            if (c.networkId == netId) {
                assertTrue(c.status == Status.ENABLED);
            } else {
                assertFalse(c.status == Status.ENABLED);
            }
        }

        //disable network
        ret = mWifiManager.disableNetwork(netId);
        assertTrue(ret);

        //check config list
        configList = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration c : configList) {
            if (c.networkId == netId) {
                assertTrue(c.status == Status.DISABLED);
            }
        }
    }

    // Test case 3: ping supplicant
    @LargeTest
    public void testPingSupplicant() {
        assertTrue(mWifiManager.pingSupplicant());
        mWifiManager.setWifiEnabled(false);
        sleepAfterWifiEnable();

        assertFalse(mWifiManager.pingSupplicant());
        mWifiManager.setWifiEnabled(true);
        sleepAfterWifiEnable();
    }

    // Test case 4: save config
    @LargeTest
    public void testSaveConfig() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"TestSSID3\"";
        config.allowedKeyManagement.set(KeyMgmt.NONE);

        //add
        int netId = mWifiManager.addNetwork(config);
        assertTrue(netId != -1);

        mWifiManager.saveConfiguration();

        //restart wifi
        mWifiManager.setWifiEnabled(false);
        mWifiManager.setWifiEnabled(true);

        sleepAfterWifiEnable();

        //check config list
        List<WifiConfiguration> configList = mWifiManager.getConfiguredNetworks();
        boolean found = false;
        for (WifiConfiguration c : configList) {
            if (c.SSID.equals("TestSSID3")) {
                found = true;
            }
        }
        assertTrue(found);

        //restore config
        boolean ret = mWifiManager.removeNetwork(netId);
        assertTrue(ret);
        mWifiManager.saveConfiguration();
    }

    // Test case 5: test wifi state change broadcasts
    @LargeTest
    public void testWifiBroadcasts() {

        /* Initialize */
        mWifiManager.setWifiEnabled(false);
        sleepAfterWifiEnable();
        mDisableBroadcastCounter = 0;
        mEnableBroadcastCounter = 0;
        mSupplicantConnection = false;
        mNetworkInfo = null;
        mSupplicantState = null;

        /* Enable wifi */
        mWifiManager.setWifiEnabled(true);
        sleepAfterWifiEnable();
        assertTrue(mEnableBroadcastCounter == 2);
        assertTrue(mSupplicantConnection == true);
        assertTrue(mNetworkInfo.isConnected());
        assertTrue(mSupplicantState == SupplicantState.COMPLETED);


        /* Disable wifi */
        mWifiManager.setWifiEnabled(false);
        sleepAfterWifiEnable();
        assertTrue(mDisableBroadcastCounter == 2);
        assertTrue(mSupplicantConnection == false);
        assertTrue(!mNetworkInfo.isConnected());
        assertTrue(mSupplicantState != SupplicantState.COMPLETED);

    }

    // Test case 6: test configured network status
    @LargeTest
    public void testWifiConfiguredNetworkStatus() {

        /* Initialize */
        mWifiManager.setWifiEnabled(false);
        sleepAfterWifiEnable();

        /* Ensure no network is CURRENT */
        List<WifiConfiguration> configList = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration c : configList) {
            assertTrue(c.status != WifiConfiguration.Status.CURRENT);
        }

        /* Enable wifi */
        mWifiManager.setWifiEnabled(true);
        sleepAfterWifiEnable();

        /* Ensure connected network is CURRENT */
        String connectedSSID = mWifiManager.getConnectionInfo().getSSID();
        configList = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration c : configList) {
            if (c.SSID.contains(connectedSSID)) {
                assertTrue(c.status == WifiConfiguration.Status.CURRENT);
            } else {
                assertTrue(c.status != WifiConfiguration.Status.CURRENT);
            }
        }

        /* Disable wifi */
        mWifiManager.setWifiEnabled(false);
        sleepAfterWifiEnable();

        /* Ensure no network is CURRENT */
        configList = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration c : configList) {
            assertTrue(c.status != WifiConfiguration.Status.CURRENT);
        }
    }



}
