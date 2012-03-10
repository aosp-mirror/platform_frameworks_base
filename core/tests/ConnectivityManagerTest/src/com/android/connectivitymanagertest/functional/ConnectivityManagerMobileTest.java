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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;
import com.android.connectivitymanagertest.ConnectivityManagerTestRunner;
import com.android.connectivitymanagertest.NetworkState;

public class ConnectivityManagerMobileTest extends
        ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {
    private static final String LOG_TAG = "ConnectivityManagerMobileTest";

    private String mTestAccessPoint;
    private ConnectivityManagerTestActivity cmActivity;
    private WakeLock wl;
    private boolean mWifiOnlyFlag;

    public ConnectivityManagerMobileTest() {
        super(ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        cmActivity = getActivity();
        ConnectivityManagerTestRunner mRunner =
                (ConnectivityManagerTestRunner)getInstrumentation();
        mTestAccessPoint = mRunner.mTestSsid;
        mWifiOnlyFlag = mRunner.mWifiOnlyFlag;

        PowerManager pm = (PowerManager)getInstrumentation().
                getContext().getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "CMWakeLock");
        wl.acquire();
        // Each test case will start with cellular connection
        if (Settings.System.getInt(getInstrumentation().getContext().getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON) == 1) {
            log("airplane is not disabled, disable it.");
            cmActivity.setAirplaneMode(getInstrumentation().getContext(), false);
        }

        if (!mWifiOnlyFlag) {
            if (!cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                    ConnectivityManagerTestActivity.LONG_TIMEOUT)) {
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
        cmActivity.finish();
        log("tear down ConnectivityManagerTestActivity");
        wl.release();
        cmActivity.removeConfiguredNetworksAndDisableWifi();
        // if airplane mode is set, disable it.
        if (Settings.System.getInt(getInstrumentation().getContext().getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON) == 1) {
            log("disable airplane mode if it is enabled");
            cmActivity.setAirplaneMode(getInstrumentation().getContext(), false);
        }
        super.tearDown();
    }

    // help function to verify 3G connection
    public void verifyCellularConnection() {
        NetworkInfo extraNetInfo = cmActivity.mCM.getActiveNetworkInfo();
        assertEquals("network type is not MOBILE", ConnectivityManager.TYPE_MOBILE,
                extraNetInfo.getType());
        assertTrue("not connected to cellular network", extraNetInfo.isConnected());
    }

    private void log(String message) {
        Log.v(LOG_TAG, message);
    }

    private void sleep(long sleeptime) {
        try {
            Thread.sleep(sleeptime);
        } catch (InterruptedException e) {}
    }

    // Test case 1: Test enabling Wifi without associating with any AP, no broadcast on network
    //              event should be expected.
    @LargeTest
    public void test3GToWifiNotification() {
        if (mWifiOnlyFlag) {
            Log.v(LOG_TAG, this.getName() + " is excluded for wifi-only test");
            return;
        }
        // Enable Wi-Fi to avoid initial UNKNOWN state
        cmActivity.enableWifi();
        sleep(2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT);

        // Wi-Fi is disabled
        cmActivity.disableWifi();

        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.DISCONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                State.CONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        // Wait for 10 seconds for broadcasts to be sent out
        sleep(10 * 1000);

        // As Wifi stays in DISCONNETED, Mobile statys in CONNECTED,
        // the connectivity manager will not broadcast any network connectivity event for Wifi
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                networkInfo.getState(), NetworkState.DO_NOTHING, State.CONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.DO_NOTHING, State.DISCONNECTED);
        // Eanble Wifi without associating with any AP
        cmActivity.enableWifi();
        sleep(2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT);

        // validate state and broadcast
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            log("the state for WIFI is changed");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue("state validation fail", false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            log("the state for MOBILE is changed");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue("state validation fail", false);
        }
        // Verify that the device is still connected to MOBILE
        verifyCellularConnection();
    }

    // Test case 2: test connection to a given AP
    @LargeTest
    public void testConnectToWifi() {
        assertNotNull("SSID is null", mTestAccessPoint);
        NetworkInfo networkInfo;
        if (!mWifiOnlyFlag) {
            //Prepare for connectivity verification
            networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                    networkInfo.getState(), NetworkState.TO_DISCONNECTION, State.DISCONNECTED);
        }
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.TO_CONNECTION, State.CONNECTED);

        // Enable Wifi and connect to a test access point
        assertTrue("failed to connect to " + mTestAccessPoint,
                cmActivity.connectToWifi(mTestAccessPoint));

        assertTrue(cmActivity.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        log("wifi state is enabled");
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        if (!mWifiOnlyFlag) {
            assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.DISCONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        }

        // validate states
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            log("Wifi state transition validation failed.");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
        if (!mWifiOnlyFlag) {
            if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
                log("Mobile state transition validation failed.");
                log("reason: " +
                        cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
                assertTrue(false);
            }
        }
    }

    // Test case 3: connect to Wifi with known AP
    @LargeTest
    public void testConnectToWifWithKnownAP() {
        assertNotNull("SSID is null", mTestAccessPoint);
        // Connect to mTestAccessPoint
        assertTrue("failed to connect to " + mTestAccessPoint,
                cmActivity.connectToWifi(mTestAccessPoint));
        assertTrue(cmActivity.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));

        sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT);
        // Disable Wifi
        log("Disable Wifi");
        if (!cmActivity.disableWifi()) {
            log("disable Wifi failed");
            return;
        }

        // Wait for the Wifi state to be DISABLED
        assertTrue(cmActivity.waitForWifiState(WifiManager.WIFI_STATE_DISABLED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.DISCONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        if (!mWifiOnlyFlag) {
            assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.CONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        }

        NetworkInfo networkInfo;
        if (!mWifiOnlyFlag) {
            //Prepare for connectivity state verification
            networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                                  networkInfo.getState(), NetworkState.DO_NOTHING,
                                                  State.DISCONNECTED);
        }
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.TO_CONNECTION, State.CONNECTED);

        // wait for 2 minutes before restart wifi
        sleep(ConnectivityManagerTestActivity.WIFI_STOP_START_INTERVAL);
        // Enable Wifi again
        log("Enable Wifi again");
        cmActivity.enableWifi();

        // Wait for Wifi to be connected and mobile to be disconnected
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        if (!mWifiOnlyFlag) {
            assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.DISCONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        }

        // validate wifi states
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            log("Wifi state transition validation failed.");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
    }

    // Test case 4:  test disconnect Wifi
    @LargeTest
    public void testDisconnectWifi() {
        assertNotNull("SSID is null", mTestAccessPoint);

        // connect to Wifi
        assertTrue("failed to connect to " + mTestAccessPoint,
                   cmActivity.connectToWifi(mTestAccessPoint));

        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
            ConnectivityManagerTestActivity.LONG_TIMEOUT));

        // Wait for a few seconds to avoid the state that both Mobile and Wifi is connected
        sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT);

        NetworkInfo networkInfo;
        if (!mWifiOnlyFlag) {
            networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                                  networkInfo.getState(),
                                                  NetworkState.TO_CONNECTION,
                                                  State.CONNECTED);
        }
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.TO_DISCONNECTION, State.DISCONNECTED);

        // clear Wifi
        cmActivity.removeConfiguredNetworksAndDisableWifi();

        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        if (!mWifiOnlyFlag) {
            assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.CONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        }

        // validate states
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            log("Wifi state transition validation failed.");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
        if (!mWifiOnlyFlag) {
            if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
                log("Mobile state transition validation failed.");
                log("reason: " +
                        cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
                assertTrue(false);
            }
        }
    }

    // Test case 5: test connectivity from 3G to airplane mode, then to 3G again
    @LargeTest
    public void testDataConnectionWith3GToAmTo3G() {
        if (mWifiOnlyFlag) {
            Log.v(LOG_TAG, this.getName() + " is excluded for wifi-only test");
            return;
        }
        //Prepare for state verification
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                              networkInfo.getState(),
                                              NetworkState.TO_DISCONNECTION,
                                              State.DISCONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        assertEquals(State.DISCONNECTED, networkInfo.getState());

        // Enable airplane mode
        log("Enable airplane mode");
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), true);
        sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT);

        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        assertEquals(State.DISCONNECTED, networkInfo.getState());
        // wait until mobile is turn off
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                State.DISCONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            log("Mobile state transition validation failed.");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue(false);
        }

        // reset state recorder
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                              networkInfo.getState(),
                                              NetworkState.TO_CONNECTION,
                                              State.CONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.DO_NOTHING, State.DISCONNECTED);

        // disable airplane mode
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), false);

        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));

        // Validate the state transition
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            log("Mobile state transition validation failed.");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue(false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
          log("Wifi state transition validation failed.");
          log("reason: " +
                  cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
          assertTrue(false);
        }
    }

    // Test case 6: test connectivity with airplane mode Wifi connected
    @LargeTest
    public void testDataConnectionOverAMWithWifi() {
        if (mWifiOnlyFlag) {
            Log.v(LOG_TAG, this.getName() + " is excluded for wifi-only test");
            return;
        }
        assertNotNull("SSID is null", mTestAccessPoint);
        // Eanble airplane mode
        log("Enable airplane mode");
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), true);

        NetworkInfo networkInfo;
        if (!mWifiOnlyFlag) {
            assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.DISCONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
            networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                                  networkInfo.getState(),
                                                  NetworkState.DO_NOTHING,
                                                  State.DISCONNECTED);
        }
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                                              NetworkState.TO_CONNECTION, State.CONNECTED);

        // Connect to Wifi
        assertTrue("failed to connect to " + mTestAccessPoint,
                   cmActivity.connectToWifi(mTestAccessPoint));
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                            ConnectivityManagerTestActivity.LONG_TIMEOUT));

        // validate state and broadcast
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            log("state validate for Wifi failed");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue("State validation failed", false);
        }
        if (!mWifiOnlyFlag) {
            if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
                log("state validation for Mobile failed");
                log("reason: " +
                        cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
                assertTrue("state validation failed", false);
            }
        }
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), false);
    }

    // Test case 7: test connectivity while transit from Wifi->AM->Wifi
    @LargeTest
    public void testDataConnectionWithWifiToAMToWifi () {
        if (mWifiOnlyFlag) {
            Log.v(LOG_TAG, this.getName() + " is excluded for wifi-only test");
            return;
        }
        // Connect to mTestAccessPoint
        assertNotNull("SSID is null", mTestAccessPoint);
        // Connect to Wifi
        assertTrue("failed to connect to " + mTestAccessPoint,
                cmActivity.connectToWifi(mTestAccessPoint));

        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));

        try {
            Thread.sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT);
        } catch (Exception e) {
            log("exception: " + e.toString());
        }

        // Enable airplane mode without clearing Wifi
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), true);

        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));

        try {
            Thread.sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT);
        } catch (Exception e) {
            log("exception: " + e.toString());
        }

        // Prepare for state validation
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        assertEquals(State.DISCONNECTED, networkInfo.getState());
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI,
                networkInfo.getState(), NetworkState.TO_CONNECTION, State.CONNECTED);

        // Disable airplane mode
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), false);

        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                            ConnectivityManagerTestActivity.LONG_TIMEOUT));
        if (!mWifiOnlyFlag) {
            assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                    State.DISCONNECTED, ConnectivityManagerTestActivity.LONG_TIMEOUT));
        }

        // validate the state transition
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            log("Wifi state transition validation failed.");
            log("reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
    }

    // Test case 8: test wifi state change while connecting/disconnecting to/from an AP
    @LargeTest
    public void testWifiStateChange () {
        assertNotNull("SSID is null", mTestAccessPoint);
        //Connect to mTestAccessPoint
        assertTrue("failed to connect to " + mTestAccessPoint,
                   cmActivity.connectToWifi(mTestAccessPoint));
        assertTrue(cmActivity.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                            ConnectivityManagerTestActivity.LONG_TIMEOUT));
        assertNotNull("Not associated with any AP",
                      cmActivity.mWifiManager.getConnectionInfo().getBSSID());

        try {
            Thread.sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT);
        } catch (Exception e) {
            log("exception: " + e.toString());
        }

        // Disconnect from the current AP
        log("disconnect from the AP");
        if (!cmActivity.disconnectAP()) {
            log("failed to disconnect from " + mTestAccessPoint);
        }

        // Verify the connectivity state for Wifi is DISCONNECTED
        assertTrue(cmActivity.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));

        if (!cmActivity.disableWifi()) {
            log("disable Wifi failed");
            return;
        }
        assertTrue(cmActivity.waitForWifiState(WifiManager.WIFI_STATE_DISABLED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
    }
}
