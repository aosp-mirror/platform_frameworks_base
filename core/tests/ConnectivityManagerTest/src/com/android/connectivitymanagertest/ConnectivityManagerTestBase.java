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
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Log;
import android.view.KeyEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;


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

    private static final String LOG_TAG = "ConnectivityManagerTestBase";
    private static final String ACCESS_POINT_FILE = "accesspoints.xml";
    private static final String PING_IP_ADDR = "8.8.8.8";

    protected static final int WAIT_FOR_SCAN_RESULT = 10 * 1000; //10 seconds
    protected static final int WIFI_SCAN_TIMEOUT = 50 * 1000; // 50 seconds
    protected static final int SHORT_TIMEOUT = 5 * 1000; // 5 seconds
    protected static final long LONG_TIMEOUT = 50 * 1000;  // 50 seconds
    protected static final long WIFI_CONNECTION_TIMEOUT = 5 * 60 * 1000; // 5 minutes
    // 2 minutes timer between wifi stop and start
    protected static final long  WIFI_STOP_START_INTERVAL = 2 * 60 * 1000; // 2 minutes
    // Set ping test timer to be 3 minutes
    protected static final long PING_TIMER = 3 * 60 *1000; // 3 minutes
    protected static final int SUCCESS = 0;  // for Wifi tethering state change
    protected static final int FAILURE = 1;
    protected static final int INIT = -1;

    private ConnectivityReceiver mConnectivityReceiver = null;
    private WifiReceiver mWifiReceiver = null;
    private AccessPointParserHelper mParseHelper = null;

    private long mLastConnectivityChangeTime = -1;
    protected ConnectivityManager mCm;
    private Context mContext;
    protected List<ScanResult> mLastScanResult;
    protected Object mWifiScanResultLock = new Object();

    /* Control Wifi States */
    public WifiManager mWifiManager;

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
            log("ConnectivityReceiver: " + intent);
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
                log("scan results are available");
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

        if (mWifiManager.isWifiApEnabled()) {
            // if soft AP is enabled, disable it
            mWifiManager.setWifiApEnabled(null, false);
            log("Disable soft ap");
        }

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
        mIntentFilter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        mContext.registerReceiver(mWifiReceiver, mIntentFilter);

        log("Clear Wifi before we start the test.");
        removeConfiguredNetworksAndDisableWifi();
     }

    protected List<WifiConfiguration> loadNetworkConfigurations() throws Exception {
        InputStream in = mContext.getAssets().open(ACCESS_POINT_FILE);
        mParseHelper = new AccessPointParserHelper(in);
        return mParseHelper.getNetworkConfigurations();
    }

    // wait for network connectivity state: CONNECTING, CONNECTED, SUSPENDED, DISCONNECTING,
    //                                      DISCONNECTED, UNKNOWN
    protected boolean waitForNetworkState(int networkType, State expectedState, long timeout) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            NetworkInfo ni = mCm.getNetworkInfo(networkType);
            String niString = ni == null ? "null" : ni.toString();
            if (ni != null && expectedState.equals(ni.getState())) {
                log("waitForNetworkState success: " + niString);
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                log("waitForNetworkState timeout: " + niString);
                return false;
            }
            log("waitForNetworkState interim: " + niString);
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
                log("waitForWifiState success: state=" + state);
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                log(String.format("waitForWifiState timeout: expected=%d, actual=%d",
                        expectedState, state));
                return false;
            }
            log(String.format("waitForWifiState interim: expected=%d, actual=%d",
                    expectedState, state));
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
                log("waitForWifiAPState success: state=" + state);
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                log(String.format("waitForWifiAPState timeout: expected=%d, actual=%d",
                        expectedState, state));
                return false;
            }
            log(String.format("waitForWifiAPState interim: expected=%d, actual=%d",
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
        log("Turn screen off");
        PowerManager pm =
            (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis());
    }

    // Turn screen on
    protected void turnScreenOn() {
        log("Turn screen on");
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
     * @param pingServerList a list of servers that can be used for ping test, can be null
     * @return true if the ping test is successful, false otherwise.
     */
    protected boolean pingTest(String[] pingServerList) {
        String[] hostList = {"www.google.com", "www.yahoo.com",
                "www.bing.com", "www.facebook.com", "www.ask.com"};
        if (pingServerList != null) {
            hostList = pingServerList;
        }

        long startTime = System.currentTimeMillis();
        while ((System.currentTimeMillis() - startTime) < PING_TIMER) {
            try {
                // assume the chance that all servers are down is very small
                for (int i = 0; i < hostList.length; i++ ) {
                    String host = hostList[i];
                    log("Start ping test, ping " + host);
                    Process p = Runtime.getRuntime().exec("ping -c 10 -w 100 " + host);
                    int status = p.waitFor();
                    if (status == 0) {
                        // if any of the ping test is successful, return true
                        return true;
                    }
                }
            } catch (UnknownHostException e) {
                log("Ping test Fail: Unknown Host");
            } catch (IOException e) {
                log("Ping test Fail:  IOException");
            } catch (InterruptedException e) {
                log("Ping test Fail: InterruptedException");
            }
        }
        // ping test timeout
        return false;
    }

    /**
     * Associate the device to given SSID
     * If the device is already associated with a WiFi, disconnect and forget it,
     * We don't verify whether the connection is successful or not, leave this to the test
     */
    protected boolean connectToWifi(String knownSSID) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = knownSSID;
        config.allowedKeyManagement.set(KeyMgmt.NONE);
        return connectToWifiWithConfiguration(config);
    }

    /**
     * Connect to Wi-Fi with the given configuration. Note the SSID in the configuration
     * is pure string, we need to convert it to quoted string.
     * @param config
     * @return
     */
    protected boolean connectToWifiWithConfiguration(WifiConfiguration config) {
        String ssid = config.SSID;
        config.SSID = convertToQuotedString(ssid);

        // If Wifi is not enabled, enable it
        if (!mWifiManager.isWifiEnabled()) {
            log("Wifi is not enabled, enable it");
            mWifiManager.setWifiEnabled(true);
            // wait for the wifi state change before start scanning.
            if (!waitForWifiState(WifiManager.WIFI_STATE_ENABLED, LONG_TIMEOUT)) {
                log("wait for WIFI_STATE_ENABLED failed");
                return false;
            }
        }

        // Save network configuration and connect to network without scanning
        mWifiManager.connect(config,
            new WifiManager.ActionListener() {
                public void onSuccess() {
                }
                public void onFailure(int reason) {
                    log("connect failure " + reason);
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
            log("Enabled wifi before remove configured networks");
            mWifiManager.setWifiEnabled(true);
            SystemClock.sleep(SHORT_TIMEOUT);
        }

        List<WifiConfiguration> wifiConfigList = mWifiManager.getConfiguredNetworks();
        if (wifiConfigList == null) {
            log("no configuration list is null");
            return true;
        }
        log("size of wifiConfigList: " + wifiConfigList.size());
        for (WifiConfiguration wifiConfig: wifiConfigList) {
            log("remove wifi configuration: " + wifiConfig.networkId);
            int netId = wifiConfig.networkId;
            mWifiManager.forget(netId, new WifiManager.ActionListener() {
                    public void onSuccess() {
                    }
                    public void onFailure(int reason) {
                        log("Failed to forget " + reason);
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
            String niString = ni == null ? "null" : ni.toString();
            if (ni != null && ni.isConnected()) {
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                log("waitForActiveNetworkConnection timeout: " + niString);
                return false;
            }
            log("waitForActiveNetworkConnection interim: " + niString);
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
            String niString = ni.toString();
            if ((SystemClock.uptimeMillis() - startTime) > timeout) {
                log("waitForActiveNetworkConnection timeout: " + niString);
                return false;
            }
            log("waitForActiveNetworkConnection interim: " + niString);
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    // use ping request against Google public DNS to verify connectivity
    protected boolean checkNetworkConnectivity() {
        assertTrue("no active network connection", waitForActiveNetworkConnection(LONG_TIMEOUT));
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/ping", "-W", "30", "-c", "1", PING_IP_ADDR});
            int exitCode = proc.waitFor();
            return exitCode == 0;
        } catch (InterruptedException ie) {
            Log.e(LOG_TAG, "InterruptedException while waiting for ping");
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "IOException during ping", ioe);
        }
        return false;
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

    private void log(String message) {
        Log.v(LOG_TAG, message);
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
                State.CONNECTED, LONG_TIMEOUT));

        // step 3: verify the current connected network is the given SSID
        assertNotNull("no active wifi info", mWifiManager.getConnectionInfo());
        assertEquals("SSID mismatch", config.SSID, mWifiManager.getConnectionInfo().getSSID());
    }

    /**
     * checks if the input is a hexadecimal string of given length
     *
     * @param input string to be checked
     * @param length required length of the string
     * @return
     */
    protected static boolean isHex(String input, int length) {
        Pattern p = Pattern.compile(String.format("[0-9A-Fa-f]{%d}", length));
        return p.matcher(input).matches();
    }
}
