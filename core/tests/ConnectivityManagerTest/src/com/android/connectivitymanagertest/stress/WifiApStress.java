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
import com.android.connectivitymanagertest.ConnectivityManagerTestRunner;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

/**
 * Stress the wifi driver as access point.
 */
public class WifiApStress
    extends ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {
    private final static String TAG = "WifiApStress";
    private ConnectivityManagerTestActivity mAct;
    private static String NETWORK_ID = "AndroidAPTest";
    private static String PASSWD = "androidwifi";
    private int max_num;

    public WifiApStress() {
        super(ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mAct = getActivity();
        max_num = ((ConnectivityManagerStressTestRunner)getInstrumentation()).numStress;
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @LargeTest
    public void testWifiHotSpot() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = NETWORK_ID;
        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
        config.preSharedKey = PASSWD;

        // If Wifi is enabled, disable it
        if (mAct.mWifiManager.isWifiEnabled()) {
            mAct.disableWifi();
        }
        for (int i = 0; i < max_num; i++) {
            Log.v(TAG, "iteration: " + i);
            // enable Wifi tethering
            assertTrue(mAct.mWifiManager.setWifiApEnabled(config, true));
            // Wait for wifi ap state to be ENABLED
            assertTrue(mAct.waitForWifiAPState(mAct.mWifiManager.WIFI_AP_STATE_ENABLED,
                    mAct.LONG_TIMEOUT));
            // Wait for wifi tethering result
            assertEquals(mAct.SUCCESS, mAct.waitForTetherStateChange(2*mAct.SHORT_TIMEOUT));
            // Allow the wifi tethering to be enabled for 10 seconds
            try {
                Thread.sleep(2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT);
            } catch (Exception e) {
                fail("thread in sleep is interrupted");
            }
            assertTrue(mAct.mWifiManager.setWifiApEnabled(config, false));
        }
    }

}
