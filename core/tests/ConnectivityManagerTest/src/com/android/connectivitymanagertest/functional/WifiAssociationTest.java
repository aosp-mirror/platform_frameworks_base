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

import com.android.connectivitymanagertest.ConnectivityManagerTestBase;
import com.android.connectivitymanagertest.WifiAssociationTestRunner;

import android.content.Context;
import android.os.Bundle;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.GroupCipher;
import android.net.wifi.WifiConfiguration.PairwiseCipher;
import android.net.wifi.WifiConfiguration.Protocol;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.State;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

/**
 * Test Wi-Fi connection with different configuration
 * To run this tests:
 *  * adb shell am instrument -e ssid <ssid> -e password <password> \
 * -e security-type [OPEN|WEP64|WEP128|WPA_TKIP|WPA2_AES] -e frequency-band [2.4|5.0|auto]
 * -w com.android.connectivitymanagertest/.WifiAssociationTestRunner"
 */
public class WifiAssociationTest
        extends ConnectivityManagerTestBase {
    private static final String TAG = "WifiAssociationTest";
    private String mSsid = null;
    private String mPassword = null;
    private String mSecurityType = null;
    private String mFrequencyBand = null;
    private int mBand;

    enum SECURITY_TYPE {
        OPEN, WEP64, WEP128, WPA_TKIP, WPA2_AES
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        WifiAssociationTestRunner mRunner = (WifiAssociationTestRunner)getInstrumentation();
        Bundle arguments = mRunner.getArguments();
        mSecurityType = arguments.getString("security-type");
        mSsid = arguments.getString("ssid");
        mPassword = arguments.getString("password");
        mFrequencyBand = arguments.getString("frequency-band");
        mBand = mRunner.mBand;
        assertNotNull("Security type is empty", mSecurityType);
        assertNotNull("Ssid is empty", mSsid);
        validateFrequencyBand();
        // enable Wifi and verify wpa_supplicant is started
        assertTrue("enable Wifi failed", enableWifi());
        sleep(2 * SHORT_TIMEOUT, "interrupted while waiting for WPA_SUPPLICANT to start");
        WifiInfo mConnection = mWifiManager.getConnectionInfo();
        assertNotNull(mConnection);
        assertTrue("wpa_supplicant is not started ", mWifiManager.pingSupplicant());
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    private void validateFrequencyBand() {
        if (mFrequencyBand != null) {
            int currentFreq = mWifiManager.getFrequencyBand();
            Log.v(TAG, "read frequency band: " + currentFreq);
            assertTrue("device frequency band is not set successfully", (mBand == currentFreq));
         }
    }

    /**
     * Connect to the provided Wi-Fi network
     * @param config is the network configuration
     * @return true if the connection is successful.
     */
    private void connectToWifi(WifiConfiguration config) {
        // step 1: connect to the test access point
        assertTrue("failed to associate with " + config.SSID,
                connectToWifiWithConfiguration(config));

        // step 2: verify Wifi state and network state;
        assertTrue("failed to connect with " + config.SSID,
                waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.CONNECTED, WIFI_CONNECTION_TIMEOUT));

        // step 3: verify the current connected network is the given SSID
        assertNotNull("Wifi connection returns null", mWifiManager.getConnectionInfo());
        assertTrue(config.SSID.contains(mWifiManager.getConnectionInfo().getSSID()));
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
    public void testWifiAssociation() {
        assertNotNull("no test ssid", mSsid);
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = mSsid;
        SECURITY_TYPE security = SECURITY_TYPE.valueOf(mSecurityType);
        log("Security type is " + security.toString());
        switch (security) {
            // set network configurations
            case OPEN:
                config.allowedKeyManagement.set(KeyMgmt.NONE);
                break;
            case WEP64:
                // always use hex pair for WEP-40
                assertTrue("not a WEP64 security type?", mPassword.length() == 10);
                config.allowedKeyManagement.set(KeyMgmt.NONE);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.SHARED);
                config.allowedGroupCiphers.set(GroupCipher.WEP40);
                if (mPassword != null) {
                    int length = mPassword.length();
                    // WEP-40
                    if (mPassword.matches("[0-9A-Fa-f]*")) {
                        config.wepKeys[0] = mPassword;
                    } else {
                        fail("Please type hex pair for the password");
                    }
                }
                break;
            case WEP128:
                assertNotNull("password is empty", mPassword);
                // always use hex pair for WEP-104
                assertTrue("not a WEP128 security type?", mPassword.length() == 26);
                config.allowedKeyManagement.set(KeyMgmt.NONE);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.SHARED);
                config.allowedGroupCiphers.set(GroupCipher.WEP104);
                if (mPassword != null) {
                    int length = mPassword.length();
                    // WEP-40
                    if (mPassword.matches("[0-9A-Fa-f]*")) {
                        config.wepKeys[0] = mPassword;
                    } else {
                        fail("Please type hex pair for the password");
                    }
                }
                break;
            case WPA_TKIP:
                assertNotNull("missing password", mPassword);
                config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedProtocols.set(Protocol.WPA);
                config.allowedPairwiseCiphers.set(PairwiseCipher.TKIP);
                config.allowedGroupCiphers.set(GroupCipher.TKIP);
                if (mPassword.matches("[0-9A-Fa-f]{64}")) {
                    config.preSharedKey = mPassword;
                } else {
                    config.preSharedKey = '"' + mPassword + '"';
                }
                break;
            case WPA2_AES:
                assertNotNull("missing password", mPassword);
                config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedProtocols.set(Protocol.RSN);
                config.allowedPairwiseCiphers.set(PairwiseCipher.CCMP);
                config.allowedGroupCiphers.set(GroupCipher.CCMP);
                config.allowedProtocols.set(Protocol.RSN);
                if (mPassword.matches("[0-9A-Fa-f]{64}")) {
                    config.preSharedKey = mPassword;
                } else {
                    config.preSharedKey = '"' + mPassword + '"';
                }
                break;
            default:
                fail("Not a valid security type: " + mSecurityType);
                break;
        }
        Log.v(TAG, "network config: " + config.toString());
        connectToWifi(config);
    }
}
