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

package com.android.connectivitymanagertest;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.TetheringManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Log;
import android.view.KeyEvent;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;


/**
 * Base InstrumentationTestCase for Connectivity Manager (CM) test suite
 *
 * It registers connectivity manager broadcast and WiFi broadcast to provide
 * network connectivity information, also provides a set of utility functions
 * to modify and verify connectivity states.
 *
 * A CM test case should extend this base class.
 */
public class ConnectivityManagerTestBase extends InstrumentationTestCase {

    private static final String[] PING_HOST_LIST = {
        "www.google.com", "www.yahoo.com", "www.bing.com", "www.facebook.com", "www.ask.com"};

    protected static final int WAIT_FOR_SCAN_RESULT = 10 * 1000; //10 seconds
    protected static final int WIFI_SCAN_TIMEOUT = 50 * 1000; // 50 seconds
    protected static final int SHORT_TIMEOUT = 5 * 1000; // 5 seconds
    protected static final long LONG_TIMEOUT = 2 * 60 * 1000;  // 2 minutes
    protected static final long WIFI_CONNECTION_TIMEOUT = 5 * 60 * 1000; // 5 minutes
    // 2 minutes timer between wifi stop and start
    protected static final long  WIFI_STOP_START_INTERVAL = 2 * 60 * 1000; // 2 minutes
    // Set ping test timer to be 3 minutes
    protected static final long PING_TIMER = 3 * 60 *1000; // 3 minutes
    protected static final int SUCCESS = 0;  // for Wifi tethering state change
    protected static final int FAILURE = 1;
    protected static final int INIT = -1;

    protected final String mLogTag;

    private ConnectivityReceiver mConnectivityReceiver = null;
    private WifiReceiver mWifiReceiver = null;

    private long mLastConnectivityChangeTime = -1;
    protected ConnectivityManager mCm;
    private Context mContext;
    protected List<ScanResult> mLastScanResult;
    protected Object mWifiScanResultLock = new Object();

    /* Control Wifi States */
    public WifiManager mWifiManager;

    public ConnectivityManagerTestBase(String logTag) {
        super();
        mLogTag = logTag;
    }

    protected long getLastConnectivityChangeTime() {
        return mLastConnectivityChangeTime;
    }

    /**
     * A wrapper of a broadcast receiver which provides network connectivity information
     * for all kinds of network: wifi, mobile, etc.
     */
    private class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            mLastConnectivityChangeTime = SystemClock.uptimeMillis();
            logv("ConnectivityReceiver: " + intent);
            String action = intent.getAction();
            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.v("ConnectivityReceiver", "onReceive() called with " + intent);
            }
        }
    }

    private class WifiReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v("WifiReceiver", "onReceive() is calleld with " + intent);
            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                logv("scan results are available");
                synchronized (mWifiScanResultLock) {
                    mLastScanResult = mWifiManager.getScanResults();
                    mWifiScanResultLock.notifyAll();
                }
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        mLastScanResult = null;
        mContext = getInstrumentation().getContext();

        // Get an instance of ConnectivityManager
        mCm = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        // Get an instance of WifiManager
        mWifiManager =(WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);

        // register a connectivity receiver for CONNECTIVITY_ACTION;
        mConnectivityReceiver = new ConnectivityReceiver();
        mContext.registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        mWifiReceiver = new WifiReceiver();
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(TetheringManager.ACTION_TETHER_STATE_CHANGED);
        mContext.registerReceiver(mWifiReceiver, mIntentFilter);

        logv("Clear Wifi before we start the test.");
        removeConfiguredNetworksAndDisableWifi();
     }

    // wait for network connectivity state: CONNECTING, CONNECTED, SUSPENDED, DISCONNECTING,
    //                                      DISCONNECTED, UNKNOWN
    protected boolean waitForNetworkState(int networkType, State expectedState, long timeout) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            NetworkInfo ni = mCm.getNetworkInfo(networkType);
            if (ni != null && expectedState.equals(ni.getState())) {
                logv("waitForNetworkState success: %s", ni);
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                logv("waitForNetworkState timeout: %s", ni);
                return false;
            }
            logv("waitForNetworkState interim: %s", ni);
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    // wait for Wifi state: WIFI_STATE_DISABLED, WIFI_STATE_DISABLING, WIFI_STATE_ENABLED,
    //                      WIFI_STATE_ENALBING, WIFI_STATE_UNKNOWN
    protected boolean waitForWifiState(int expectedState, long timeout) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            int state = mWifiManager.getWifiState();
            if (state == expectedState) {
                logv("waitForWifiState success: state=" + state);
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                logv("waitForWifiState timeout: expected=%d, actual=%d", expectedState, state);
                return false;
            }
            logv("waitForWifiState interim: expected=%d, actual=%d", expectedState, state);
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    // Wait for Wifi AP state: WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING,
    //                         WIFI_AP_STATE_ENABLED, WIFI_STATE_ENALBING, WIFI_STATE_UNKNOWN
    protected boolean waitForWifiApState(int expectedState, long timeout) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            int state = mWifiManager.getWifiApState();
            if (state == expectedState) {
                logv("waitForWifiAPState success: state=" + state);
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                logv(String.format("waitForWifiAPState timeout: expected=%d, actual=%d",
                        expectedState, state));
                return false;
            }
            logv(String.format("waitForWifiAPState interim: expected=%d, actual=%d",
                    expectedState, state));
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    /**
     * Wait for the wifi tethering result:
     * @param timeout is the maximum waiting time
     * @return SUCCESS if tethering result is successful
     *         FAILURE if tethering result returns error.
     */
    protected boolean waitForTetherStateChange(long timeout) {
        long startTime = SystemClock.uptimeMillis();
        String[] wifiRegexes = mCm.getTetherableWifiRegexs();
        while (true) {
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                return false;
            }
            String[] active = mCm.getTetheredIfaces();
            String[] error = mCm.getTetheringErroredIfaces();
            for (String iface: active) {
                for (String regex: wifiRegexes) {
                    if (iface.matches(regex)) {
                        return true;
                    }
                }
            }
            for (String iface: error) {
                for (String regex: wifiRegexes) {
                    if (iface.matches(regex)) {
                        return false;
                    }
                }
            }
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    // Return true if device is currently connected to mobile network
    protected boolean isConnectedToMobile() {
        return (mCm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE);
    }

    // Return true if device is currently connected to Wifi
    protected boolean isConnectedToWifi() {
        return (mCm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI);
    }

    protected boolean enableWifi() {
        return mWifiManager.setWifiEnabled(true);
    }

    // Turn screen off
    protected void turnScreenOff() {
        logv("Turn screen off");
        PowerManager pm =
            (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis());
    }

    // Turn screen on
    protected void turnScreenOn() {
        logv("Turn screen on");
        PowerManager pm =
                (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        pm.wakeUp(SystemClock.uptimeMillis());
        // disable lock screen
        KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (km.inKeyguardRestrictedInputMode()) {
            sendKeys(KeyEvent.KEYCODE_MENU);
        }
    }

    /**
     * @return true if the ping test is successful, false otherwise.
     */
    protected boolean pingTest() {
        long startTime = System.currentTimeMillis();
        while ((System.currentTimeMillis() - startTime) < PING_TIMER) {
            try {
                // assume the chance that all servers are down is very small
                for (String host : PING_HOST_LIST) {
                    logv("Start ping test, ping " + host);
                    Process p = Runtime.getRuntime().exec("ping -c 10 -w 100 " + host);
                    int status = p.waitFor();
                    if (status == 0) {
                        // if any of the ping test is successful, return true
                        return true;
                    }
                }
            } catch (UnknownHostException e) {
                logv("Ping test Fail: Unknown Host");
            } catch (IOException e) {
                logv("Ping test Fail:  IOException");
            } catch (InterruptedException e) {
                logv("Ping test Fail: InterruptedException");
            }
            SystemClock.sleep(SHORT_TIMEOUT);
        }
        // ping test timeout
        return false;
    }

    /**
     * Associate the device to given SSID
     * If the device is already associated with a WiFi, disconnect and forget it,
     * We don't verify whether the connection is successful or not, leave this to the test
     */
    protected boolean connectToWifi(String ssid, String password) {
        WifiConfiguration config;
        if (password == null) {
            config = WifiConfigurationHelper.createOpenConfig(ssid);
        } else {
            config = WifiConfigurationHelper.createPskConfig(ssid, password);
        }
        return connectToWifiWithConfiguration(config);
    }

    /**
     * Connect to Wi-Fi with the given configuration. Note the SSID in the configuration
     * is pure string, we need to convert it to quoted string.
     */
    protected boolean connectToWifiWithConfiguration(WifiConfiguration config) {
        // If Wifi is not enabled, enable it
        if (!mWifiManager.isWifiEnabled()) {
            logv("Wifi is not enabled, enable it");
            mWifiManager.setWifiEnabled(true);
            // wait for the wifi state change before start scanning.
            if (!waitForWifiState(WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT)) {
                logv("wait for WIFI_STATE_ENABLED failed");
                return false;
            }
        }

        // Save network configuration and connect to network without scanning
        mWifiManager.connect(config,
            new WifiManager.ActionListener() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onFailure(int reason) {
                    logv("connect failure " + reason);
                }
            });
        return true;
    }

    /*
     * Disconnect from the current AP and remove configured networks.
     */
    protected boolean disconnectAP() {
        // remove saved networks
        if (!mWifiManager.isWifiEnabled()) {
            logv("Enabled wifi before remove configured networks");
            mWifiManager.setWifiEnabled(true);
            SystemClock.sleep(SHORT_TIMEOUT);
        }

        List<WifiConfiguration> wifiConfigList = mWifiManager.getConfiguredNetworks();
        if (wifiConfigList == null) {
            logv("no configuration list is null");
            return true;
        }
        logv("size of wifiConfigList: " + wifiConfigList.size());
        for (WifiConfiguration wifiConfig: wifiConfigList) {
            logv("remove wifi configuration: " + wifiConfig.networkId);
            int netId = wifiConfig.networkId;
            mWifiManager.forget(netId, new WifiManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onFailure(int reason) {
                        logv("Failed to forget " + reason);
                    }
                });
        }
        return true;
    }
    /**
     * Disable Wifi
     * @return true if Wifi is disabled successfully
     */
    protected boolean disableWifi() {
        return mWifiManager.setWifiEnabled(false);
    }

    /**
     * Remove configured networks and disable wifi
     */
    protected boolean removeConfiguredNetworksAndDisableWifi() {
        if (!disconnectAP()) {
           return false;
        }
        SystemClock.sleep(SHORT_TIMEOUT);
        if (!mWifiManager.setWifiEnabled(false)) {
            return false;
        }
        SystemClock.sleep(SHORT_TIMEOUT);
        return true;
    }

    protected static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    protected boolean waitForActiveNetworkConnection(long timeout) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            NetworkInfo ni = mCm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                logv("waitForActiveNetworkConnection timeout: %s", ni);
                return false;
            }
            logv("waitForActiveNetworkConnection interim: %s", ni);
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    protected boolean waitUntilNoActiveNetworkConnection(long timeout) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            NetworkInfo ni = mCm.getActiveNetworkInfo();
            if (ni == null) {
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                logv("waitForActiveNetworkConnection timeout: %s", ni);
                return false;
            }
            logv("waitForActiveNetworkConnection interim: %s", ni);
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    // use ping request against Google public DNS to verify connectivity
    protected boolean checkNetworkConnectivity() {
        assertTrue("no active network connection", waitForActiveNetworkConnection(LONG_TIMEOUT));
        return pingTest();
    }

    @Override
    protected void tearDown() throws Exception{
        //Unregister receiver
        if (mConnectivityReceiver != null) {
          mContext.unregisterReceiver(mConnectivityReceiver);
        }
        if (mWifiReceiver != null) {
          mContext.unregisterReceiver(mWifiReceiver);
        }
        super.tearDown();
    }

    protected void logv(String format, Object... args) {
        Log.v(mLogTag, String.format(format, args));
    }

    /**
     * Connect to the provided Wi-Fi network
     * @param config is the network configuration
     * @throws AssertionError if fails to associate and connect to wifi ap
     */
    protected void connectToWifi(WifiConfiguration config) {
        // step 1: connect to the test access point
        assertTrue("failed to associate with " + config.SSID,
                connectToWifiWithConfiguration(config));

        // step 2: verify Wifi state and network state;
        assertTrue("wifi state not connected with " + config.SSID,
                waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.CONNECTED, WIFI_CONNECTION_TIMEOUT));

        // step 3: verify the current connected network is the given SSID
        assertNotNull("no active wifi info", mWifiManager.getConnectionInfo());
        assertEquals("SSID mismatch", config.SSID, mWifiManager.getConnectionInfo().getSSID());
    }
}
