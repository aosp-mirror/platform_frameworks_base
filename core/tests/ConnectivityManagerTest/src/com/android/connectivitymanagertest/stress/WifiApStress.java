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
import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

/**
 * Stress the wifi driver as access point.
 */
public class WifiApStress
    extends ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {
    private final static String TAG = "WifiApStress";
    private static String NETWORK_ID = "AndroidAPTest";
    private static String PASSWD = "androidwifi";
    private final static String OUTPUT_FILE = "WifiStressTestOutput.txt";
    private ConnectivityManagerTestActivity mAct;
    private int iterations;
    private BufferedWriter mOutputWriter = null;
    private int mLastIteration = 0;
    private boolean mWifiOnlyFlag;

    public WifiApStress() {
        super(ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mAct = getActivity();
        ConnectivityManagerStressTestRunner mRunner =
            (ConnectivityManagerStressTestRunner)getInstrumentation();
        iterations = mRunner.mSoftapIterations;
        mWifiOnlyFlag = mRunner.mWifiOnlyFlag;
        mAct.turnScreenOn();
    }

    @Override
    public void tearDown() throws Exception {
        // write the total number of iterations into output file
        mOutputWriter = new BufferedWriter(new FileWriter(new File(
                Environment.getExternalStorageDirectory(), OUTPUT_FILE)));
        mOutputWriter.write(String.format("iteration %d out of %d\n", mLastIteration, iterations));
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

        // If Wifi is enabled, disable it
        if (mAct.mWifiManager.isWifiEnabled()) {
            mAct.disableWifi();
        }
        int i;
        for (i = 0; i < iterations; i++) {
            Log.v(TAG, "iteration: " + i);
            mLastIteration = i;
            // enable Wifi tethering
            assertTrue(mAct.mWifiManager.setWifiApEnabled(config, true));
            // Wait for wifi ap state to be ENABLED
            assertTrue(mAct.waitForWifiAPState(WifiManager.WIFI_AP_STATE_ENABLED,
                    2 * ConnectivityManagerTestActivity.LONG_TIMEOUT));
            // Wait for wifi tethering result
            assertEquals(ConnectivityManagerTestActivity.SUCCESS,
                    mAct.waitForTetherStateChange(2*ConnectivityManagerTestActivity.SHORT_TIMEOUT));
            // Allow the wifi tethering to be enabled for 10 seconds
            try {
                Thread.sleep(2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT);
            } catch (Exception e) {
                fail("thread in sleep is interrupted");
            }
            assertTrue("no uplink data connection after Wi-Fi tethering", mAct.pingTest(null));
            // Disable soft AP
            assertTrue(mAct.mWifiManager.setWifiApEnabled(config, false));
            // Wait for 30 seconds until Wi-Fi tethering is stopped
            try {
                Thread.sleep(30 * 1000);
                Log.v(TAG, "wait for Wi-Fi tethering to be disabled.");
            } catch (Exception e) {
                fail("thread in sleep is interrupted");
            }
            assertFalse("Wi-Fi AP disable failed", mAct.mWifiManager.isWifiApEnabled());
        }
        if (i == iterations) {
            mLastIteration = iterations;
        }
    }

}
