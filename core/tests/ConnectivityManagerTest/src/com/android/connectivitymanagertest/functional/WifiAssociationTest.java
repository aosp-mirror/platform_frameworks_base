/*
 * Copyright (C) 2013, The Android Open Source Project
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

import android.net.ConnectivityManager;
import android.net.NetworkInfo.State;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.GroupCipher;
import android.net.wifi.WifiConfiguration.PairwiseCipher;
import android.net.wifi.WifiConfiguration.Protocol;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.connectivitymanagertest.ConnectivityManagerTestBase;
import com.android.connectivitymanagertest.WifiAssociationTestRunner;
import com.android.connectivitymanagertest.WifiConfigurationHelper;

/**
 * Test Wi-Fi connection with different configuration
 * To run this tests:
 *  * adb shell am instrument -e ssid <ssid> -e password <password> \
 * -e security-type [OPEN|WEP64|WEP128|WPA_TKIP|WPA2_AES] -e frequency-band [2.4|5.0|auto]
 * -w com.android.connectivitymanagertest/.WifiAssociationTestRunner"
 */
public class WifiAssociationTest extends ConnectivityManagerTestBase {
    private String mSsid = null;
    private String mPassword = null;
    private String mSecurityType = null;
    private String mFrequencyBand = null;
    private int mBand;

    enum SECURITY_TYPE {
        OPEN, WEP64, WEP128, WPA_TKIP, WPA2_AES
    }

    public WifiAssociationTest() {
        super(WifiAssociationTest.class.getSimpleName());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        WifiAssociationTestRunner runner = (WifiAssociationTestRunner)getInstrumentation();
        Bundle arguments = runner.getArguments();
        mSecurityType = arguments.getString("security-type");
        mSsid = arguments.getString("ssid");
        mPassword = arguments.getString("password");
        mFrequencyBand = arguments.getString("frequency-band");
        mBand = runner.mBand;
        assertNotNull("security type is empty", mSecurityType);
        assertNotNull("ssid is empty", mSsid);
        validateFrequencyBand();

        // enable wifi and verify wpa_supplicant is started
        assertTrue("enable Wifi failed", enableWifi());
        assertTrue("wifi not connected", waitForNetworkState(
                ConnectivityManager.TYPE_WIFI, State.CONNECTED, LONG_TIMEOUT));
        WifiInfo wi = mWifiManager.getConnectionInfo();
        assertNotNull("no active wifi info", wi);
        assertTrue("failed to ping wpa_supplicant ", mWifiManager.pingSupplicant());
    }

    private void validateFrequencyBand() {
        if (mFrequencyBand != null) {
            int currentFreq = mWifiManager.getFrequencyBand();
            logv("read frequency band: " + currentFreq);
            assertEquals("specified frequency band does not match operational band of WifiManager",
                    currentFreq, mBand);
         }
    }

    @LargeTest
    public void testWifiAssociation() {
        assertNotNull("no test ssid", mSsid);
        WifiConfiguration config = null;
        SECURITY_TYPE security = SECURITY_TYPE.valueOf(mSecurityType);
        logv("Security type is " + security.toString());
        switch (security) {
            // set network configurations
            case OPEN:
                config = WifiConfigurationHelper.createOpenConfig(mSsid);
                break;
            case WEP64:
                assertNotNull("password is empty", mPassword);
                // always use hex pair for WEP-40
                assertTrue(WifiConfigurationHelper.isHex(mPassword, 10));
                config = WifiConfigurationHelper.createWepConfig(mSsid, mPassword);
                config.allowedGroupCiphers.set(GroupCipher.WEP40);
                break;
            case WEP128:
                assertNotNull("password is empty", mPassword);
                // always use hex pair for WEP-104
                assertTrue(WifiConfigurationHelper.isHex(mPassword, 26));
                config = WifiConfigurationHelper.createWepConfig(mSsid, mPassword);
                config.allowedGroupCiphers.set(GroupCipher.WEP104);
                break;
            case WPA_TKIP:
                assertNotNull("password is empty", mPassword);
                config = WifiConfigurationHelper.createPskConfig(mSsid, mPassword);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedProtocols.set(Protocol.WPA);
                config.allowedPairwiseCiphers.set(PairwiseCipher.TKIP);
                config.allowedGroupCiphers.set(GroupCipher.TKIP);
                break;
            case WPA2_AES:
                assertNotNull("password is empty", mPassword);
                config = WifiConfigurationHelper.createPskConfig(mSsid, mPassword);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedProtocols.set(Protocol.RSN);
                config.allowedPairwiseCiphers.set(PairwiseCipher.CCMP);
                config.allowedGroupCiphers.set(GroupCipher.CCMP);
                break;
            default:
                fail("Not a valid security type: " + mSecurityType);
                break;
        }
        logv("network config: %s", config.toString());
        connectToWifi(config);
        // verify that connection actually works
        assertTrue("no network connectivity at end of test", checkNetworkConnectivity());
    }
}
