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

package com.android.connectivitymanagertest.stress;


import com.android.connectivitymanagertest.ConnectivityManagerStressTestRunner;
import com.android.connectivitymanagertest.ConnectivityManagerTestBase;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

/**
 * Stress test setting up device as wifi hotspot
 */
public class WifiApStress
    extends ConnectivityManagerTestBase {
    private final static String TAG = "WifiApStress";
    private static String NETWORK_ID = "AndroidAPTest";
    private static String PASSWD = "androidwifi";
    private final static String OUTPUT_FILE = "WifiStressTestOutput.txt";
    private int mTotalIterations;
    private BufferedWriter mOutputWriter = null;
    private int mLastIteration = 0;
    private boolean mWifiOnlyFlag;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        ConnectivityManagerStressTestRunner mRunner =
            (ConnectivityManagerStressTestRunner)getInstrumentation();
        mTotalIterations = mRunner.getSoftApInterations();
        mWifiOnlyFlag = mRunner.isWifiOnly();
        turnScreenOn();
    }

    @Override
    protected void tearDown() throws Exception {
        // write the total number of iterations into output file
        mOutputWriter = new BufferedWriter(new FileWriter(new File(
                Environment.getExternalStorageDirectory(), OUTPUT_FILE)));
        mOutputWriter.write(String.format("iteration %d out of %d\n",
                mLastIteration + 1, mTotalIterations));
        mOutputWriter.flush();
        mOutputWriter.close();
        super.tearDown();
    }

    @LargeTest
    public void testWifiHotSpot() {
        if (mWifiOnlyFlag) {
            Log.v(TAG, this.getName() + " is excluded for wi-fi only test");
            return;
        }
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = NETWORK_ID;
        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
        config.preSharedKey = PASSWD;

        // if wifiap enabled, disable it
        assertTrue("failed to disable wifi hotspot",
                mWifiManager.setWifiApEnabled(config, false));
        assertTrue("wifi hotspot not enabled", waitForWifiApState(
                WifiManager.WIFI_AP_STATE_DISABLED, 2 * LONG_TIMEOUT));

        // if Wifi is enabled, disable it
        if (mWifiManager.isWifiEnabled()) {
            assertTrue("failed to disable wifi", disableWifi());
            // wait for the wifi state to be DISABLED
            assertTrue("wifi state not disabled", waitForWifiState(
                    WifiManager.WIFI_STATE_DISABLED, LONG_TIMEOUT));
        }
        int i;
        for (i = 0; i < mTotalIterations; i++) {
            Log.v(TAG, "iteration: " + i);
            mLastIteration = i;
            // enable Wifi tethering
            assertTrue("failed to enable wifi hotspot",
                    mWifiManager.setWifiApEnabled(config, true));
            // wait for wifi ap state to be ENABLED
            assertTrue("wifi hotspot not enabled", waitForWifiApState(
                    WifiManager.WIFI_AP_STATE_ENABLED, 2 * LONG_TIMEOUT));
            // wait for wifi tethering result
            assertTrue("tether state not changed", waitForTetherStateChange(LONG_TIMEOUT));
            // allow the wifi tethering to be enabled for 10 seconds
            try {
                Thread.sleep(2 * SHORT_TIMEOUT);
            } catch (Exception e) {
                // ignore
            }
            assertTrue("no uplink data connection after Wi-Fi tethering", pingTest(null));
            // disable wifi hotspot
            assertTrue("failed to disable wifi hotspot",
                    mWifiManager.setWifiApEnabled(config, false));
            assertTrue("wifi hotspot not enabled", waitForWifiApState(
                    WifiManager.WIFI_AP_STATE_DISABLED, 2 * LONG_TIMEOUT));
            assertFalse("wifi hotspot still enabled", mWifiManager.isWifiApEnabled());
        }
    }

}
