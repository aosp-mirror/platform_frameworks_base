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

import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;

import android.content.Intent;
import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Message;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.NetworkInfo.DetailedState;
import android.net.wifi.WifiManager;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.ActivityInstrumentationTestCase2;
import com.android.connectivitymanagertest.ConnectivityManagerTestRunner;
import com.android.connectivitymanagertest.NetworkState;
import android.util.Log;

public class ConnectivityManagerMobileTest
    extends ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {
    private static final String LOG_TAG = "ConnectivityManagerMobileTest";
    private static final String PKG_NAME = "com.android.connectivitymanagertest";
    private static final long STATE_TRANSITION_SHORT_TIMEOUT = 5 * 1000;
    private static final long STATE_TRANSITION_LONG_TIMEOUT = 30 * 1000;

    private String TEST_ACCESS_POINT;
    private ConnectivityManagerTestActivity cmActivity;
    private WakeLock wl;

    public ConnectivityManagerMobileTest() {
        super(PKG_NAME, ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        cmActivity = getActivity();
        ConnectivityManagerTestRunner mRunner =
                (ConnectivityManagerTestRunner)getInstrumentation();
        TEST_ACCESS_POINT = mRunner.TEST_SSID;
        PowerManager pm = (PowerManager)getInstrumentation().
                getContext().getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "CMWakeLock");
        wl.acquire();
        // Each test case will start with cellular connection
        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);
        verifyCellularConnection();
    }

    @Override
    public void tearDown() throws Exception {
        cmActivity.finish();
        Log.v(LOG_TAG, "tear down ConnectivityManagerTestActivity");
        wl.release();
        cmActivity.clearWifi();
        super.tearDown();
    }

    // help function to verify 3G connection
    public void verifyCellularConnection() {
        NetworkInfo extraNetInfo = cmActivity.mNetworkInfo;
        assertEquals("network type is not MOBILE", ConnectivityManager.TYPE_MOBILE,
            extraNetInfo.getType());
        assertTrue("not connected to cellular network", extraNetInfo.isConnected());
        assertTrue("no data connection", cmActivity.mState.equals(State.CONNECTED));
    }

    // Wait for network connectivity state: CONNECTING, CONNECTED, SUSPENDED,
    //                                      DISCONNECTING, DISCONNECTED, UNKNOWN
    private void waitForNetworkState(int networkType, State expectedState, long timeout) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                if (cmActivity.mCM.getNetworkInfo(networkType).getState() != expectedState) {
                    assertFalse("Wait for network state timeout", true);
                } else {
                    // the broadcast has been sent out. the state has been changed.
                    return;
                }
            }
            Log.v(LOG_TAG, "Wait for the connectivity state for network: " + networkType +
                    " to be " + expectedState.toString());
            synchronized (cmActivity.connectivityObject) {
                try {
                    cmActivity.connectivityObject.wait(STATE_TRANSITION_SHORT_TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if ((cmActivity.mNetworkInfo.getType() != networkType) ||
                    (cmActivity.mNetworkInfo.getState() != expectedState)) {
                    Log.v(LOG_TAG, "network state for " + cmActivity.mNetworkInfo.getType() +
                            "is: " + cmActivity.mNetworkInfo.getState());
                    continue;
                }
                break;
            }
        }
    }

    // Wait for Wifi state: WIFI_STATE_DISABLED, WIFI_STATE_DISABLING, WIFI_STATE_ENABLED,
    //                      WIFI_STATE_ENALBING, WIFI_STATE_UNKNOWN
    private void waitForWifiState(int expectedState, long timeout) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                if (cmActivity.mWifiState != expectedState) {
                    assertFalse("Wait for Wifi state timeout", true);
                } else {
                    return;
                }
            }
            Log.v(LOG_TAG, "Wait for wifi state to be: " + expectedState);
            synchronized (cmActivity.wifiObject) {
                try {
                    cmActivity.wifiObject.wait(5*1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (cmActivity.mWifiState != expectedState) {
                    Log.v(LOG_TAG, "Wifi state is: " + cmActivity.mWifiNetworkInfo.getState());
                    continue;
                }
                break;
            }
        }
    }

    // Test case 1: Test enabling Wifi without associating with any AP
    @LargeTest
    public void test3GToWifiNotification() {
        // To avoid UNKNOWN state when device boots up
        cmActivity.enableWifi();
        try {
            Thread.sleep(2 * STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        cmActivity.disableWifi();
        // As Wifi stays in DISCONNECTED, the connectivity manager will not broadcast
        // any network connectivity event for Wifi
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE, networkInfo.getState(),
                NetworkState.DO_NOTHING, State.CONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.DO_NOTHING, State.DISCONNECTED);
        // Eanble Wifi
        cmActivity.enableWifi();
        try {
            Thread.sleep(2 * STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        // validate state and broadcast
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "the state for WIFI is changed");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue("state validation fail", false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            Log.v(LOG_TAG, "the state for MOBILE is changed");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue("state validation fail", false);
        }
        // Verify that the device is still connected to MOBILE
        verifyCellularConnection();
    }

    // Test case 2: test connection to a given AP
    @LargeTest
    public void testConnectToWifi() {
        assertNotNull("SSID is null", TEST_ACCESS_POINT);
        //Prepare for connectivity verification
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE, networkInfo.getState(),
                NetworkState.TO_DISCONNECTION, State.DISCONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.TO_CONNECTION, State.CONNECTED);

        // Enable Wifi and connect to a test access point
        assertTrue("failed to connect to " + TEST_ACCESS_POINT,
                cmActivity.connectToWifi(TEST_ACCESS_POINT));

        waitForWifiState(WifiManager.WIFI_STATE_ENABLED, STATE_TRANSITION_LONG_TIMEOUT);
        Log.v(LOG_TAG, "wifi state is enabled");
        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.DISCONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        // validate states
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "Wifi state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            Log.v(LOG_TAG, "Mobile state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue(false);
        }
    }

    // Test case 3: connect to Wifi with known AP
    @LargeTest
    public void testConnectToWifWithKnownAP() {
        assertNotNull("SSID is null", TEST_ACCESS_POINT);
        // Connect to TEST_ACCESS_POINT
        assertTrue("failed to connect to " + TEST_ACCESS_POINT,
                cmActivity.connectToWifi(TEST_ACCESS_POINT));
        waitForWifiState(WifiManager.WIFI_STATE_ENABLED, STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        try {
            Thread.sleep(STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        // Disable Wifi
        Log.v(LOG_TAG, "Disable Wifi");
        if (!cmActivity.disableWifi()) {
            Log.v(LOG_TAG, "disable Wifi failed");
            return;
        }

        // Wait for the Wifi state to be DISABLED
        waitForWifiState(WifiManager.WIFI_STATE_DISABLED, STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        //Prepare for connectivity state verification
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                              networkInfo.getState(), NetworkState.DO_NOTHING,
                                              State.DISCONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.TO_CONNECTION, State.CONNECTED);

        // Enable Wifi again
        Log.v(LOG_TAG, "Enable Wifi again");
        cmActivity.enableWifi();

        // Wait for Wifi to be connected and mobile to be disconnected
        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.DISCONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        // validate wifi states
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "Wifi state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
    }

    // Test case 4:  test disconnect Wifi
    @LargeTest
    public void testDisconnectWifi() {
        assertNotNull("SSID is null", TEST_ACCESS_POINT);

        // connect to Wifi
        assertTrue("failed to connect to " + TEST_ACCESS_POINT,
                   cmActivity.connectToWifi(TEST_ACCESS_POINT));

        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
            STATE_TRANSITION_LONG_TIMEOUT);

        // Wait for a few seconds to avoid the state that both Mobile and Wifi is connected
        try {
            Thread.sleep(STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                              networkInfo.getState(),
                                              NetworkState.TO_CONNECTION,
                                              State.CONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.TO_DISCONNECTION, State.DISCONNECTED);

        // clear Wifi
        cmActivity.clearWifi();

        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        // validate states
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "Wifi state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            Log.v(LOG_TAG, "Mobile state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue(false);
        }
    }

    // Test case 5: test connectivity from 3G to airplane mode, then to 3G again
    @LargeTest
    public void testDataConnectionWith3GToAmTo3G() {
        //Prepare for state verification
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                              networkInfo.getState(),
                                              NetworkState.TO_DISCONNECTION,
                                              State.DISCONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        assertEquals(State.DISCONNECTED, networkInfo.getState());
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                NetworkState.DO_NOTHING, State.DISCONNECTED);

        // Enable airplane mode
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), true);
        try {
            Thread.sleep(STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        // Validate the state transition
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "Wifi state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            Log.v(LOG_TAG, "Mobile state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
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

        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        // Validate the state transition
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            Log.v(LOG_TAG, "Mobile state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue(false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
          Log.v(LOG_TAG, "Wifi state transition validation failed.");
          Log.v(LOG_TAG, "reason: " +
                  cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
          assertTrue(false);
        }
    }

    // Test case 6: test connectivity with airplane mode Wifi connected
    @LargeTest
    public void testDataConnectionOverAMWithWifi() {
        assertNotNull("SSID is null", TEST_ACCESS_POINT);
        // Eanble airplane mode
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), true);

        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.DISCONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_MOBILE,
                                              networkInfo.getState(),
                                              NetworkState.DO_NOTHING,
                                              State.DISCONNECTED);
        networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI, networkInfo.getState(),
                                              NetworkState.TO_CONNECTION, State.CONNECTED);

        // Connect to Wifi
        assertTrue("failed to connect to " + TEST_ACCESS_POINT,
                   cmActivity.connectToWifi(TEST_ACCESS_POINT));
        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                            STATE_TRANSITION_LONG_TIMEOUT);

        // validate state and broadcast
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "state validate for Wifi failed");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue("State validation failed", false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            Log.v(LOG_TAG, "state validation for Mobile failed");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue("state validation failed", false);
        }
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), false);
    }

    // Test case 7: test connectivity while transit from Wifi->AM->Wifi
    @LargeTest
    public void testDataConnectionWithWifiToAMToWifi () {
        // Connect to TEST_ACCESS_POINT
        assertNotNull("SSID is null", TEST_ACCESS_POINT);
        // Connect to Wifi
        assertTrue("failed to connect to " + TEST_ACCESS_POINT,
                cmActivity.connectToWifi(TEST_ACCESS_POINT));

        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        try {
            Thread.sleep(STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        // Enable airplane mode without clearing Wifi
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), true);

        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        try {
            Thread.sleep(STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        // Prepare for state validation
        NetworkInfo networkInfo = cmActivity.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        assertEquals(State.DISCONNECTED, networkInfo.getState());
        cmActivity.setStateTransitionCriteria(ConnectivityManager.TYPE_WIFI,
                networkInfo.getState(), NetworkState.TO_CONNECTION, State.CONNECTED);

        // Disable airplane mode
        cmActivity.setAirplaneMode(getInstrumentation().getContext(), false);

        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                            STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.DISCONNECTED,
                            STATE_TRANSITION_LONG_TIMEOUT);

        // validate the state transition
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "Wifi state transition validation failed.");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
    }

    // Test case 8: test wifi state change while connecting/disconnecting to/from an AP
    @LargeTest
    public void testWifiStateChange () {
        assertNotNull("SSID is null", TEST_ACCESS_POINT);
        //Connect to TEST_ACCESS_POINT
        assertTrue("failed to connect to " + TEST_ACCESS_POINT,
                   cmActivity.connectToWifi(TEST_ACCESS_POINT));
        waitForWifiState(WifiManager.WIFI_STATE_ENABLED, STATE_TRANSITION_LONG_TIMEOUT);
        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                            STATE_TRANSITION_LONG_TIMEOUT);
        assertNotNull("Not associated with any AP",
                      cmActivity.mWifiManager.getConnectionInfo().getBSSID());

        try {
            Thread.sleep(STATE_TRANSITION_SHORT_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        // Disconnect from the current AP
        Log.v(LOG_TAG, "disconnect from the AP");
        if (!cmActivity.disconnectAP()) {
            Log.v(LOG_TAG, "failed to disconnect from " + TEST_ACCESS_POINT);
        }

        // Verify the connectivity state for Wifi is DISCONNECTED
        waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                STATE_TRANSITION_LONG_TIMEOUT);

        if (!cmActivity.disableWifi()) {
            Log.v(LOG_TAG, "disable Wifi failed");
            return;
        }
        waitForWifiState(WifiManager.WIFI_STATE_DISABLED, STATE_TRANSITION_LONG_TIMEOUT);
    }
}
