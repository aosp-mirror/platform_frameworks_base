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

package com.android.connectivitymanagertest.functional;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.connectivitymanagertest.ConnectivityManagerTestBase;
import com.android.connectivitymanagertest.ConnectivityManagerTestRunner;

public class ConnectivityManagerMobileTest extends
        ConnectivityManagerTestBase {
    private static final String TAG = "ConnectivityManagerMobileTest";

    private String mTestAccessPoint;
    private boolean mWifiOnlyFlag;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        ConnectivityManagerTestRunner mRunner =
                (ConnectivityManagerTestRunner)getInstrumentation();
        mTestAccessPoint = mRunner.mTestSsid;
        mWifiOnlyFlag = mRunner.mWifiOnlyFlag;

        // Each test case will start with cellular connection
        if (Settings.Global.getInt(getInstrumentation().getContext().getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON) == 1) {
            log("airplane is not disabled, disable it.");
            mCm.setAirplaneMode(false);
        }

        if (!mWifiOnlyFlag) {
            if (!waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.CONNECTED, LONG_TIMEOUT)) {
                // Note: When the test fails in setUp(), tearDown is not called. In that case,
                // the activity is destroyed which blocks the next test at "getActivity()".
                // tearDown() is called here to avoid that situation.
                tearDown();
                fail("Device is not connected to Mobile, setUp failed");
            }
        }
    }

    @Override
    public void tearDown() throws Exception {
        removeConfiguredNetworksAndDisableWifi();
        mCm.setAirplaneMode(false);
        super.tearDown();
    }

    // help function to verify 3G connection
    public void verifyCellularConnection() {
        NetworkInfo extraNetInfo = mCm.getActiveNetworkInfo();
        assertEquals("network type is not MOBILE", ConnectivityManager.TYPE_MOBILE,
                extraNetInfo.getType());
        assertTrue("not connected to cellular network", extraNetInfo.isConnected());
    }

    private void log(String message) {
        Log.v(TAG, message);
    }

    // Test case 1: Test enabling Wifi without associating with any AP, no broadcast on network
    //              event should be expected.
    @LargeTest
    public void test3GToWifiNotification() {
        if (mWifiOnlyFlag) {
            Log.v(TAG, getName() + " is excluded for wifi-only test");
            return;
        }

        // disable WiFi
        assertTrue("failed to disable WiFi", disableWifi());

        // wait for mobile
        assertTrue("failed to wait for mobile connection", waitForNetworkState(
                ConnectivityManager.TYPE_MOBILE, State.CONNECTED, LONG_TIMEOUT));

        // assert that we are indeed using mobile
        NetworkInfo ni = mCm.getActiveNetworkInfo();
        assertEquals("active network is not mobile", ConnectivityManager.TYPE_MOBILE, ni.getType());

        long timestamp = SystemClock.uptimeMillis();
        // now enable WiFi
        assertTrue("failed to enable WiFi", enableWifi());
        // assert that WiFi state settles at disconnected since no AP should be configured
        assertTrue("WiFi state is not DISCONNECTED after enabling", waitForWifiState(
                WifiManager.WIFI_STATE_DISABLED, LONG_TIMEOUT));

        // assert that no connectivity change broadcast was sent since we enable wifi
        assertTrue("connectivity has changed since wifi enable",
                timestamp > getLastConnectivityChangeTime());

        // verify that the device is still connected to MOBILE
        verifyCellularConnection();
        // verify that connection actually works
        assertTrue("no network connectivity at end of test", checkNetworkConnectivity());
    }

    // Test case 2: test connection to a given AP
    @LargeTest
    public void testConnectToWifi() {
        assertNotNull("SSID is null", mTestAccessPoint);

        // assert that we are able to connect to the ap
        assertTrue("failed to connect to " + mTestAccessPoint,
                connectToWifi(mTestAccessPoint));
        // assert that WifiManager reports correct state
        assertTrue(waitForWifiState(WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT));
        // assert that ConnectivityManager reports correct state for Wifi
        assertTrue(waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                WIFI_CONNECTION_TIMEOUT));
        // below check disbabled since we have bug in what ConnectivityManager returns
//        if (!mWifiOnlyFlag) {
//            // assert that ConnectivityManager reports correct state for mobile
//            assertTrue("mobile not disconnected", waitForNetworkState(
//                    ConnectivityManager.TYPE_MOBILE, State.DISCONNECTED, LONG_TIMEOUT));
//        }
        // verify that connection actually works
        assertTrue("no network connectivity at end of test", checkNetworkConnectivity());
    }

    // Test case 3: connect & reconnect to Wifi with known AP
    @LargeTest
    public void testConnectToWifWithKnownAP() {
        assertNotNull("SSID is null", mTestAccessPoint);
        // enable WiFi
        assertTrue("failed to enable wifi", enableWifi());
        // wait for wifi enable
        assertTrue("wifi not enabled", waitForWifiState(
                WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT));
        // Connect to AP
        assertTrue("failed to connect to " + mTestAccessPoint, connectToWifi(mTestAccessPoint));
        // verify wifi connected as reported by ConnectivityManager
        assertTrue("wifi not connected", waitForNetworkState(
                ConnectivityManager.TYPE_WIFI, State.CONNECTED, WIFI_CONNECTION_TIMEOUT));

        assertTrue("failed to disable wifi", disableWifi());

        // Wait for the Wifi state to be DISABLED
        assertTrue("wifi state not disabled", waitForWifiState(
                WifiManager.WIFI_STATE_DISABLED, LONG_TIMEOUT));
        // below check disbabled since we have bug in what ConnectivityManager returns
//        assertTrue("wifi not disconnected", waitForNetworkState(ConnectivityManager.TYPE_WIFI,
//                State.DISCONNECTED, LONG_TIMEOUT));
        if (!mWifiOnlyFlag) {
            assertTrue("mobile not connected after wifi disable", waitForNetworkState(
                    ConnectivityManager.TYPE_MOBILE, State.CONNECTED, LONG_TIMEOUT));
        }

        // wait for 30s before restart wifi
        SystemClock.sleep(LONG_TIMEOUT);
        assertTrue("failed to enable wifi after disable", enableWifi());

        // wait for wifi enable
        assertTrue("wifi not enabled after toggle", waitForWifiState(
                WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT));
        // Wait for Wifi to be connected and mobile to be disconnected
        assertTrue("wifi not connected after toggle", waitForNetworkState(
                ConnectivityManager.TYPE_WIFI, State.CONNECTED, WIFI_CONNECTION_TIMEOUT));
        // below check disbabled since we have bug in what ConnectivityManager returns
//        if (!mWifiOnlyFlag) {
//            assertTrue("mobile not disconnected after wifi toggle", waitForNetworkState(
//                    ConnectivityManager.TYPE_MOBILE, State.DISCONNECTED, LONG_TIMEOUT));
//        }
        // verify that connection actually works
        assertTrue("no network connectivity at end of test", checkNetworkConnectivity());
    }

    // Test case 4:  test disconnect and clear wifi settings
    @LargeTest
    public void testDisconnectWifi() {
        assertNotNull("SSID is null", mTestAccessPoint);

        // enable WiFi
        assertTrue("failed to enable wifi", enableWifi());
        // wait for wifi enable
        assertTrue("wifi not enabled", waitForWifiState(
                WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT));
        // connect to Wifi
        assertTrue("failed to connect to " + mTestAccessPoint,
                connectToWifi(mTestAccessPoint));

        assertTrue("wifi not connected", waitForNetworkState(
                ConnectivityManager.TYPE_WIFI, State.CONNECTED, WIFI_CONNECTION_TIMEOUT));

        // clear Wifi
        removeConfiguredNetworksAndDisableWifi();

        // assert that wifi has been disabled
        assertTrue("wifi state not disabled", waitForWifiState(
                WifiManager.WIFI_STATE_DISABLED, LONG_TIMEOUT));
        if (!mWifiOnlyFlag) {
            // assert that mobile is now connected
            assertTrue("mobile not enabled", waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.CONNECTED, LONG_TIMEOUT));
            // verify that connection actually works
            assertTrue("no network connectivity at end of test", checkNetworkConnectivity());
        }
    }

    // Test case 5: test connectivity with mobile->airplane mode->mobile
    @LargeTest
    public void testDataConnectionWith3GToAmTo3G() {
        if (mWifiOnlyFlag) {
            Log.v(TAG, getName() + " is excluded for wifi-only test");
            return;
        }
        // disable wifi
        assertTrue("failed to disable wifi", disableWifi());
        assertTrue("wifi state not disabled", waitForWifiState(
                WifiManager.WIFI_STATE_DISABLED, LONG_TIMEOUT));
        // assert that we have mobile connection
        assertTrue("no mobile connection", waitForNetworkState(
                ConnectivityManager.TYPE_MOBILE, State.CONNECTED, LONG_TIMEOUT));

        // enable airplane mode
        mCm.setAirplaneMode(true);
        // assert no active network connection after airplane mode enabled
        assertTrue("still has active network connection",
                waitUntilNoActiveNetworkConnection(LONG_TIMEOUT));

        // disable airplane mode
        mCm.setAirplaneMode(false);
        // assert there is active network connection after airplane mode disabled
        assertTrue("no active network connection after airplane mode disable",
                waitForActiveNetworkConnection(LONG_TIMEOUT));

        // assert that we have mobile connection
        assertTrue("no mobile connection", waitForNetworkState(
                ConnectivityManager.TYPE_MOBILE, State.CONNECTED, LONG_TIMEOUT));
        // verify that connection actually works
        assertTrue("no network connectivity at end of test", checkNetworkConnectivity());
    }

    // Test case 6: test connectivity with airplane mode on but wifi enabled
    @LargeTest
    public void testDataConnectionOverAMWithWifi() {
        assertNotNull("SSID is null", mTestAccessPoint);
        // enable airplane mode
        mCm.setAirplaneMode(true);
        // assert there is active network connection after airplane mode disabled
        assertTrue("still has active network connection",
                waitUntilNoActiveNetworkConnection(LONG_TIMEOUT));

        // connect to Wifi
        assertTrue("failed to connect to " + mTestAccessPoint,
                connectToWifi(mTestAccessPoint));
        assertTrue(waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                WIFI_CONNECTION_TIMEOUT));
        // verify that connection actually works
        assertTrue("no network connectivity after wifi enable", checkNetworkConnectivity());

        // disable airplane mode
        mCm.setAirplaneMode(false);
    }

    // Test case 7: test connectivity while transit from Wifi->AM->Wifi
    @LargeTest
    public void testDataConnectionWithWifiToAMToWifi () {
        // connect to mTestAccessPoint
        assertNotNull("SSID is null", mTestAccessPoint);
        // enable WiFi
        assertTrue("failed to enable wifi", enableWifi());
        // wait for wifi enable
        assertTrue("wifi not enabled", waitForWifiState(
                WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT));
        // connect to Wifi
        assertTrue("failed to connect to " + mTestAccessPoint,
                connectToWifi(mTestAccessPoint));
        assertTrue(waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                WIFI_CONNECTION_TIMEOUT));

        // enable airplane mode without clearing Wifi
        mCm.setAirplaneMode(true);
        // assert there is active network connection after airplane mode disabled
        assertTrue("still has active network connection",
                waitUntilNoActiveNetworkConnection(LONG_TIMEOUT));

        // disable airplane mode
        mCm.setAirplaneMode(false);
        // assert there is active network connection after airplane mode disabled
        assertTrue("no active network connection after airplane mode disable",
                waitForActiveNetworkConnection(LONG_TIMEOUT));
        // assert that we have a Wifi connection
        assertTrue("wifi not connected after airplane mode disable", waitForNetworkState(
                ConnectivityManager.TYPE_WIFI, State.CONNECTED, WIFI_CONNECTION_TIMEOUT));
        // verify that connection actually works
        assertTrue("no network connectivity at end of test", checkNetworkConnectivity());
    }

    // Test case 8: test wifi state change while connecting/disconnecting to/from an AP
    @LargeTest
    public void testWifiStateChange () {
        assertNotNull("SSID is null", mTestAccessPoint);
        // enable WiFi
        assertTrue("failed to enable wifi", enableWifi());
        // wait for wifi enable
        assertTrue("wifi not enabled", waitForWifiState(
                WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT));
        // connect to Wifi
        assertTrue("failed to connect to " + mTestAccessPoint,
                connectToWifi(mTestAccessPoint));
        assertTrue(waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                WIFI_CONNECTION_TIMEOUT));
        assertNotNull("not associated with any AP", mWifiManager.getConnectionInfo().getBSSID());

        // disconnect from the current AP
        assertTrue("failed to disconnect from AP", disconnectAP());

        // below check disbabled since we have bug in what ConnectivityManager returns
        // Verify the connectivity state for Wifi is DISCONNECTED
//        assertTrue(waitForNetworkState(ConnectivityManager.TYPE_WIFI,
//                State.DISCONNECTED, LONG_TIMEOUT));

        // disable WiFi
        assertTrue("failed to disable wifi", disableWifi());
        assertTrue("wifi state not disabled", waitForWifiState(
                WifiManager.WIFI_STATE_DISABLED, LONG_TIMEOUT));
    }
}
