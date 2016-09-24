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

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.GroupCipher;
import android.net.wifi.WifiConfiguration.PairwiseCipher;
import android.net.wifi.WifiConfiguration.Protocol;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
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
    private enum SecurityType {
        OPEN, WEP64, WEP128, WPA_TKIP, WPA2_AES
    }

    public WifiAssociationTest() {
        super(WifiAssociationTest.class.getSimpleName());
    }

    /**
     * Test that the wifi can associate with a given access point.
     */
    @LargeTest
    public void testWifiAssociation() {
        WifiAssociationTestRunner runner = (WifiAssociationTestRunner) getInstrumentation();
        Bundle arguments = runner.getArguments();

        String ssid = arguments.getString("ssid");
        assertNotNull("ssid is empty", ssid);

        String securityTypeStr = arguments.getString("security-type");
        assertNotNull("security-type is empty", securityTypeStr);
        SecurityType securityType = SecurityType.valueOf(securityTypeStr);

        String password = arguments.getString("password");

        assertTrue("enable Wifi failed", enableWifi());
        WifiInfo wi = mWifiManager.getConnectionInfo();
        logv("%s", wi);
        assertNotNull("no active wifi info", wi);

        WifiConfiguration config = getConfig(ssid, securityType, password);

        logv("Network config: %s", config.toString());
        connectToWifi(config);
    }

    /**
     * Get the {@link WifiConfiguration} based on ssid, security, and password.
     */
    private WifiConfiguration getConfig(String ssid, SecurityType securityType, String password) {
        logv("Security type is %s", securityType.toString());

        WifiConfiguration config = null;
        switch (securityType) {
            case OPEN:
                config = WifiConfigurationHelper.createOpenConfig(ssid);
                break;
            case WEP64:
                assertNotNull("password is empty", password);
                // always use hex pair for WEP-40
                assertTrue(WifiConfigurationHelper.isHex(password, 10));
                config = WifiConfigurationHelper.createWepConfig(ssid, password);
                config.allowedGroupCiphers.set(GroupCipher.WEP40);
                break;
            case WEP128:
                assertNotNull("password is empty", password);
                // always use hex pair for WEP-104
                assertTrue(WifiConfigurationHelper.isHex(password, 26));
                config = WifiConfigurationHelper.createWepConfig(ssid, password);
                config.allowedGroupCiphers.set(GroupCipher.WEP104);
                break;
            case WPA_TKIP:
                assertNotNull("password is empty", password);
                config = WifiConfigurationHelper.createPskConfig(ssid, password);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedProtocols.set(Protocol.WPA);
                config.allowedPairwiseCiphers.set(PairwiseCipher.TKIP);
                config.allowedGroupCiphers.set(GroupCipher.TKIP);
                break;
            case WPA2_AES:
                assertNotNull("password is empty", password);
                config = WifiConfigurationHelper.createPskConfig(ssid, password);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedProtocols.set(Protocol.RSN);
                config.allowedPairwiseCiphers.set(PairwiseCipher.CCMP);
                config.allowedGroupCiphers.set(GroupCipher.CCMP);
                break;
            default:
                fail("Not a valid security type: " + securityType);
                break;
        }
        return config;
    }
}
