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
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.AndroidTestCase;

import java.util.ArrayList;

import android.util.Log;

/**
 * Test Wifi soft AP configuration
 */
public class WifiSoftAPTest extends AndroidTestCase {

    private WifiManager mWifiManager;
    private WifiConfiguration mWifiConfig = null;
    private final String TAG = "WifiSoftAPTest";
    private final int DURATION = 10000;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mWifiManager = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
        assertNotNull(mWifiManager);
        assertTrue(mWifiManager.setWifiApEnabled(null, true));
        mWifiConfig = mWifiManager.getWifiApConfiguration();
        if (mWifiConfig != null) {
            Log.v(TAG, "mWifiConfig is " + mWifiConfig.toString());
        } else {
            Log.v(TAG, "mWifiConfig is null.");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        Log.v(TAG, "turn off wifi tethering");
        mWifiManager.setWifiApEnabled(null, false);
        super.tearDown();
    }

    // Test case 1: Test the soft AP SSID with letters
    @LargeTest
    public void testApSsidWithAlphabet() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "abcdefghijklmnopqrstuvwxyz";
        config.allowedKeyManagement.set(KeyMgmt.NONE);
        mWifiConfig = config;
        assertTrue(mWifiManager.setWifiApEnabled(mWifiConfig, true));
        try {
            Thread.sleep(DURATION);
        } catch (InterruptedException e) {
            Log.v(TAG, "exception " + e.getStackTrace());
            assertFalse(true);
        }
        assertNotNull(mWifiManager.getWifiApConfiguration());
        assertEquals("wifi AP state is not enabled", WifiManager.WIFI_AP_STATE_ENABLED,
                     mWifiManager.getWifiApState());
    }
}
