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
import android.content.Context;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Message;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.Status;

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

    @Override
    protected void setUp() throws Exception {
        super.setUp();
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
}
