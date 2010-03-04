package com.android.connectivitymanagertest.functional;

import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;

import android.content.Intent;
import android.content.Context;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Message;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.NetworkInfo.DetailedState;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.ActivityInstrumentationTestCase2;
import com.android.connectivitymanagertest.ConnectivityManagerTestRunner;
import com.android.connectivitymanagertest.NetworkState;
import android.util.Log;
import junit.framework.*;

public class ConnectivityManagerMobileTest
    extends ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {

    private static final String LOG_TAG = "ConnectivityManagerMobileTest";
    private static final String PKG_NAME = "com.android.connectivitymanagertest";
    private static final long WIFI_CONNECTION_TIMEOUT = 30 * 1000;
    private static final long WIFI_NOTIFICATION_TIMEOUT = 10 * 1000;
    private String TEST_ACCESS_POINT;
    private ConnectivityManagerTestActivity cmActivity;

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
        // Each test case will start with cellular connection
        verifyCellularConnection();
    }

    @Override
    public void tearDown() throws Exception {
        // clear Wifi after each test case
        cmActivity.clearWifi();
        cmActivity.finish();
        Log.v(LOG_TAG, "tear down ConnectivityManager test activity");
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

    // Test case 1: Test enabling Wifi without associating with any AP
    @LargeTest
    public void test3GToWifiNotification() {
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
            Thread.sleep(WIFI_NOTIFICATION_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

        // validate state and broadcast
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_WIFI)) {
            Log.v(LOG_TAG, "the state for WIFI is changed");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_WIFI));
            assertTrue(false);
        }
        if (!cmActivity.validateNetworkStates(ConnectivityManager.TYPE_MOBILE)) {
            Log.v(LOG_TAG, "the state for MOBILE is changed");
            Log.v(LOG_TAG, "reason: " +
                    cmActivity.getTransitionFailureReason(ConnectivityManager.TYPE_MOBILE));
            assertTrue(false);
        }
        // Verify that the device is still connected to MOBILE
        verifyCellularConnection();
    }

    // Test case 2: test connection to a given AP
    @LargeTest
    public void testConnectToWifi() {
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
        try {
            Thread.sleep(WIFI_CONNECTION_TIMEOUT);
        } catch (Exception e) {
            Log.v(LOG_TAG, "exception: " + e.toString());
        }

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
}
