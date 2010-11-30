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

import com.android.connectivitymanagertest.R;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import android.widget.LinearLayout;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;

import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration.KeyMgmt;

/**
 * An activity registered with connectivity manager broadcast
 * provides network connectivity information and
 * can be used to set device states: Cellular, Wifi, Airplane mode.
 */
public class ConnectivityManagerTestActivity extends Activity {

    public static final String LOG_TAG = "ConnectivityManagerTestActivity";
    public static final int WAIT_FOR_SCAN_RESULT = 10 * 1000; //10 seconds
    public static final int WIFI_SCAN_TIMEOUT = 20 * 1000;
    public static final int SHORT_TIMEOUT = 5 * 1000;
    public static final long LONG_TIMEOUT = 50 * 1000;
    public static final int SUCCESS = 0;  // for Wifi tethering state change
    public static final int FAILURE = 1;
    public static final int INIT = -1;
    private static final String ACCESS_POINT_FILE = "accesspoints.xml";
    public ConnectivityReceiver mConnectivityReceiver = null;
    public WifiReceiver mWifiReceiver = null;
    private AccessPointParserHelper mParseHelper = null;
    /*
     * Track network connectivity information
     */
    public State mState;
    public NetworkInfo mNetworkInfo;
    public NetworkInfo mOtherNetworkInfo;
    public boolean mIsFailOver;
    public String mReason;
    public boolean mScanResultIsAvailable = false;
    public ConnectivityManager mCM;
    public Object wifiObject = new Object();
    public Object connectivityObject = new Object();
    public int mWifiState;
    public NetworkInfo mWifiNetworkInfo;
    public String mBssid;
    public String mPowerSsid = "GoogleGuest"; //Default power SSID
    private Context mContext;

    /*
     * Control Wifi States
     */
    public WifiManager mWifiManager;

    /*
     * Verify connectivity state
     */
    public static final int NUM_NETWORK_TYPES = ConnectivityManager.MAX_NETWORK_TYPE + 1;
    NetworkState[] connectivityState = new NetworkState[NUM_NETWORK_TYPES];

    // For wifi tethering tests
    private String[] mWifiRegexs;
    public int mWifiTetherResult = INIT;    // -1 is initialization state

    /**
     * A wrapper of a broadcast receiver which provides network connectivity information
     * for all kinds of network: wifi, mobile, etc.
     */
    private class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(LOG_TAG, "ConnectivityReceiver: onReceive() is called with " + intent);
            String action = intent.getAction();
            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.v("ConnectivityReceiver", "onReceive() called with " + intent);
                return;
            }

            boolean noConnectivity =
                intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);

            if (noConnectivity) {
                mState = State.DISCONNECTED;
            } else {
                mState = State.CONNECTED;
            }

            mNetworkInfo = (NetworkInfo)
                intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);

            mOtherNetworkInfo = (NetworkInfo)
                intent.getParcelableExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);

            mReason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
            mIsFailOver = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);

            Log.v(LOG_TAG, "mNetworkInfo: " + mNetworkInfo.toString());
            if (mOtherNetworkInfo != null) {
                Log.v(LOG_TAG, "mOtherNetworkInfo: " + mOtherNetworkInfo.toString());
            }
            recordNetworkState(mNetworkInfo.getType(), mNetworkInfo.getState());
            if (mOtherNetworkInfo != null) {
                recordNetworkState(mOtherNetworkInfo.getType(), mOtherNetworkInfo.getState());
            }
            notifyNetworkConnectivityChange();
        }
    }

    private class WifiReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v("WifiReceiver", "onReceive() is calleld with " + intent);
            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                notifyScanResult();
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                mWifiNetworkInfo =
                    (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                Log.v(LOG_TAG, "mWifiNetworkInfo: " + mWifiNetworkInfo.toString());
                if (mWifiNetworkInfo.getState() == State.CONNECTED) {
                    mBssid = intent.getStringExtra(WifiManager.EXTRA_BSSID);
                }
                notifyWifiState();
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                                WifiManager.WIFI_STATE_UNKNOWN);
                Log.v(LOG_TAG, "mWifiState: " + mWifiState);
                notifyWifiState();
            } else if (action.equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                notifyWifiAPState();
            } else if (action.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)) {
                ArrayList<String> available = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                ArrayList<String> active = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_ACTIVE_TETHER);
                ArrayList<String> errored = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_ERRORED_TETHER);
                updateTetherState(available.toArray(), active.toArray(), errored.toArray());
            }
            else {
                return;
            }
        }
    }

    public ConnectivityManagerTestActivity() {
        mState = State.UNKNOWN;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(LOG_TAG, "onCreate, inst=" + Integer.toHexString(hashCode()));

        // Create a simple layout
        LinearLayout contentView = new LinearLayout(this);
        contentView.setOrientation(LinearLayout.VERTICAL);
        setContentView(contentView);
        setTitle("ConnectivityManagerTestActivity");


        // register a connectivity receiver for CONNECTIVITY_ACTION;
        mConnectivityReceiver = new ConnectivityReceiver();
        registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        mWifiReceiver = new WifiReceiver();
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        registerReceiver(mWifiReceiver, mIntentFilter);

        // Get an instance of ConnectivityManager
        mCM = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        // Get an instance of WifiManager
        mWifiManager =(WifiManager)getSystemService(Context.WIFI_SERVICE);
        initializeNetworkStates();

        if (mWifiManager.isWifiEnabled()) {
            Log.v(LOG_TAG, "Clear Wifi before we start the test.");
            removeConfiguredNetworksAndDisableWifi();
        }
        mWifiRegexs = mCM.getTetherableWifiRegexs();
     }

    public List<WifiConfiguration> loadNetworkConfigurations() throws Exception {
        InputStream in = getAssets().open(ACCESS_POINT_FILE);
        mParseHelper = new AccessPointParserHelper(in);
        return mParseHelper.getNetworkConfigurations();
    }

    public HashMap<String, DhcpInfo> getDhcpInfo() throws Exception{
        if (mParseHelper == null) {
            InputStream in = getAssets().open(ACCESS_POINT_FILE);
            mParseHelper = new AccessPointParserHelper(in);
        }
        return mParseHelper.getSsidToDhcpInfoHashMap();
    }


    private void printNetConfig(String[] configuration) {
        for (int i = 0; i < configuration.length; i++) {
            if (i == 0) {
                Log.v(LOG_TAG, "SSID: " + configuration[0]);
            } else {
                Log.v(LOG_TAG, "      " + configuration[i]);
            }
        }
    }

    // for each network type, initialize network states to UNKNOWN, and no verification flag is set
    public void initializeNetworkStates() {
        for (int networkType = NUM_NETWORK_TYPES - 1; networkType >=0; networkType--) {
            connectivityState[networkType] =  new NetworkState();
            Log.v(LOG_TAG, "Initialize network state for " + networkType + ": " +
                    connectivityState[networkType].toString());
        }
    }

    // deposit a network state
    public void recordNetworkState(int networkType, State networkState) {
        Log.v(LOG_TAG, "record network state for network " +  networkType +
                ", state is " + networkState);
        connectivityState[networkType].recordState(networkState);
    }

    // set the state transition criteria
    public void setStateTransitionCriteria(int networkType, State initState,
            int transitionDir, State targetState) {
        connectivityState[networkType].setStateTransitionCriteria(
                initState, transitionDir, targetState);
    }

    // Validate the states recorded
    public boolean validateNetworkStates(int networkType) {
        Log.v(LOG_TAG, "validate network state for " + networkType + ": ");
        return connectivityState[networkType].validateStateTransition();
    }

    // return result from network state validation
    public String getTransitionFailureReason(int networkType) {
        Log.v(LOG_TAG, "get network state transition failure reason for " + networkType + ": " +
                connectivityState[networkType].toString());
        return connectivityState[networkType].getReason();
    }

    private void notifyNetworkConnectivityChange() {
        synchronized(connectivityObject) {
            Log.v(LOG_TAG, "notify network connectivity changed");
            connectivityObject.notifyAll();
        }
    }
    private void notifyScanResult() {
        synchronized (this) {
            Log.v(LOG_TAG, "notify that scan results are available");
            this.notify();
        }
    }

    private void notifyWifiState() {
        synchronized (wifiObject) {
            Log.v(LOG_TAG, "notify wifi state changed");
            wifiObject.notify();
        }
    }

    private void notifyWifiAPState() {
        synchronized (this) {
            Log.v(LOG_TAG, "notify wifi AP state changed");
            this.notify();
        }
    }

    // Update wifi tethering state
    private void updateTetherState(Object[] available, Object[] tethered, Object[] errored) {
        boolean wifiTethered = false;
        boolean wifiErrored = false;

        synchronized (this) {
            for (Object obj: tethered) {
                String str = (String)obj;
                for (String tethRex: mWifiRegexs) {
                    Log.v(LOG_TAG, "str: " + str +"tethRex: " + tethRex);
                    if (str.matches(tethRex)) {
                        wifiTethered = true;
                    }
                }
            }

            for (Object obj: errored) {
                String str = (String)obj;
                for (String tethRex: mWifiRegexs) {
                    Log.v(LOG_TAG, "error: str: " + str +"tethRex: " + tethRex);
                    if (str.matches(tethRex)) {
                        wifiErrored = true;
                    }
                }
            }

            if (wifiTethered) {
                mWifiTetherResult = SUCCESS;   // wifi tethering is successful
            } else if (wifiErrored) {
                mWifiTetherResult = FAILURE;   // wifi tethering failed
            }
            Log.v(LOG_TAG, "mWifiTetherResult: " + mWifiTetherResult);
            this.notify();
        }
    }


    // Wait for network connectivity state: CONNECTING, CONNECTED, SUSPENDED,
    //                                      DISCONNECTING, DISCONNECTED, UNKNOWN
    public boolean waitForNetworkState(int networkType, State expectedState, long timeout) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                if (mCM.getNetworkInfo(networkType).getState() != expectedState) {
                    return false;
                } else {
                    // the broadcast has been sent out. the state has been changed.
                    Log.v(LOG_TAG, "networktype: " + networkType + " state: " +
                            mCM.getNetworkInfo(networkType));
                    return true;
                }
            }
            Log.v(LOG_TAG, "Wait for the connectivity state for network: " + networkType +
                    " to be " + expectedState.toString());
            synchronized (connectivityObject) {
                try {
                    connectivityObject.wait(SHORT_TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if ((mNetworkInfo.getType() != networkType) ||
                    (mNetworkInfo.getState() != expectedState)) {
                    Log.v(LOG_TAG, "network state for " + mNetworkInfo.getType() +
                            "is: " + mNetworkInfo.getState());
                    continue;
                }
                return true;
            }
        }
    }

    // Wait for Wifi state: WIFI_STATE_DISABLED, WIFI_STATE_DISABLING, WIFI_STATE_ENABLED,
    //                      WIFI_STATE_ENALBING, WIFI_STATE_UNKNOWN
    public boolean waitForWifiState(int expectedState, long timeout) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                if (mWifiState != expectedState) {
                    return false;
                } else {
                    return true;
                }
            }
            Log.v(LOG_TAG, "Wait for wifi state to be: " + expectedState);
            synchronized (wifiObject) {
                try {
                    wifiObject.wait(SHORT_TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (mWifiState != expectedState) {
                    Log.v(LOG_TAG, "Wifi state is: " + mWifiState);
                    continue;
                }
                return true;
            }
        }
    }

    // Wait for Wifi AP state: WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING,
    //                         WIFI_AP_STATE_ENABLED, WIFI_STATE_ENALBING, WIFI_STATE_UNKNOWN
    public boolean waitForWifiAPState(int expectedState, long timeout) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                if (mWifiManager.getWifiApState() != expectedState) {
                    return false;
                } else {
                    return true;
                }
            }
            Log.v(LOG_TAG, "Wait for wifi AP state to be: " + expectedState);
            synchronized (wifiObject) {
                try {
                    wifiObject.wait(SHORT_TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (mWifiManager.getWifiApState() != expectedState) {
                    Log.v(LOG_TAG, "Wifi state is: " + mWifiManager.getWifiApState());
                    continue;
                }
                return true;
            }
        }
    }

    /**
     * Wait for the wifi tethering result:
     * @param timeout is the maximum waiting time
     * @return SUCCESS if tethering result is successful
     *         FAILURE if tethering result returns error.
     */
    public int waitForTetherStateChange(long timeout) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                return mWifiTetherResult;
            }
            Log.v(LOG_TAG, "Wait for wifi tethering result.");
            synchronized (this) {
                try {
                    this.wait(SHORT_TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (mWifiTetherResult == INIT ) {
                    continue;
                } else {
                    return mWifiTetherResult;
                }
            }
        }
    }

    // Return true if device is currently connected to mobile network
    public boolean isConnectedToMobile() {
        return (mNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE);
    }

    // Return true if device is currently connected to Wifi
    public boolean isConnectedToWifi() {
        return (mNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI);
    }

    public boolean enableWifi() {
        return mWifiManager.setWifiEnabled(true);
    }

    /**
     * Associate the device to given SSID
     * If the device is already associated with a WiFi, disconnect and forget it,
     * We don't verify whether the connection is successful or not, leave this to the test
     */
    public boolean connectToWifi(String knownSSID) {
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
    public boolean connectToWifiWithConfiguration(WifiConfiguration config) {
        String ssid = config.SSID;
        config.SSID = convertToQuotedString(ssid);

        //If Wifi is not enabled, enable it
        if (!mWifiManager.isWifiEnabled()) {
            Log.v(LOG_TAG, "Wifi is not enabled, enable it");
            mWifiManager.setWifiEnabled(true);
        }

        List<ScanResult> netList = mWifiManager.getScanResults();
        if (netList == null) {
            Log.v(LOG_TAG, "scan results are null");
            // if no scan results are available, start active scan
            mWifiManager.startScanActive();
            mScanResultIsAvailable = false;
            long startTime = System.currentTimeMillis();
            while (!mScanResultIsAvailable) {
                if ((System.currentTimeMillis() - startTime) > WIFI_SCAN_TIMEOUT) {
                    return false;
                }
                // wait for the scan results to be available
                synchronized (this) {
                    // wait for the scan result to be available
                    try {
                        this.wait(WAIT_FOR_SCAN_RESULT);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if ((mWifiManager.getScanResults() == null) ||
                            (mWifiManager.getScanResults().size() <= 0)) {
                        continue;
                    }
                    mScanResultIsAvailable = true;
                }
            }
        }

        netList = mWifiManager.getScanResults();

        for (int i = 0; i < netList.size(); i++) {
            ScanResult sr= netList.get(i);
            if (sr.SSID.equals(ssid)) {
                Log.v(LOG_TAG, "found " + ssid + " in the scan result list");
                int networkId = mWifiManager.addNetwork(config);
                // Connect to network by disabling others.
                mWifiManager.enableNetwork(networkId, true);
                mWifiManager.saveConfiguration();
                mWifiManager.reconnect();
                break;
           }
        }

        List<WifiConfiguration> netConfList = mWifiManager.getConfiguredNetworks();
        if (netConfList.size() <= 0) {
            Log.v(LOG_TAG, ssid + " is not available");
            return false;
        }
        return true;
    }

    /*
     * Disconnect from the current AP and remove configured networks.
     */
    public boolean disconnectAP() {
        if (mWifiManager.isWifiEnabled()) {
            //remove the current network Id
            WifiInfo curWifi = mWifiManager.getConnectionInfo();
            if (curWifi == null) {
                return false;
            }
            int curNetworkId = curWifi.getNetworkId();
            mWifiManager.removeNetwork(curNetworkId);
            mWifiManager.saveConfiguration();

            // remove other saved networks
            List<WifiConfiguration> netConfList = mWifiManager.getConfiguredNetworks();
            if (netConfList != null) {
                Log.v(LOG_TAG, "remove configured network ids");
                for (int i = 0; i < netConfList.size(); i++) {
                    WifiConfiguration conf = new WifiConfiguration();
                    conf = netConfList.get(i);
                    mWifiManager.removeNetwork(conf.networkId);
                }
            }
        }
        mWifiManager.saveConfiguration();
        return true;
    }
    /**
     * Disable Wifi
     * @return true if Wifi is disabled successfully
     */
    public boolean disableWifi() {
        return mWifiManager.setWifiEnabled(false);
    }

    /**
     * Remove configured networks and disable wifi
     */
    public boolean removeConfiguredNetworksAndDisableWifi() {
            if (!disconnectAP()) {
                return false;
            }
            // Disable Wifi
            if (!mWifiManager.setWifiEnabled(false)) {
                return false;
            }
            // Wait for the actions to be completed
            try {
                Thread.sleep(5*1000);
            } catch (InterruptedException e) {}
        return true;
    }

    /**
     * Set airplane mode
     */
    public void setAirplaneMode(Context context, boolean enableAM) {
        //set the airplane mode
        Settings.System.putInt(context.getContentResolver(), Settings.System.AIRPLANE_MODE_ON,
                enableAM ? 1 : 0);
        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enableAM);
        context.sendBroadcast(intent);
    }

    protected static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        //Unregister receiver
        if (mConnectivityReceiver != null) {
            unregisterReceiver(mConnectivityReceiver);
        }
        if (mWifiReceiver != null) {
            unregisterReceiver(mWifiReceiver);
        }
        Log.v(LOG_TAG, "onDestroy, inst=" + Integer.toHexString(hashCode()));
    }

    @Override
    public void onStart() {
        super.onStart();
        mContext = this;
        Bundle bundle = this.getIntent().getExtras();
        if (bundle != null){
            mPowerSsid = bundle.getString("power_ssid");
        }
    }
    //A thread to set the device into airplane mode then turn on wifi.
    Thread setDeviceWifiAndAirplaneThread = new Thread(new Runnable() {
        public void run() {
            setAirplaneMode(mContext, true);
            connectToWifi(mPowerSsid);
        }
    });

    //A thread to set the device into wifi
    Thread setDeviceInWifiOnlyThread = new Thread(new Runnable() {
        public void run() {
            connectToWifi(mPowerSsid);
        }
    });

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            //This is a tricky way for the scripted monkey to
            //set the device in wifi and wifi in airplane mode.
            case KeyEvent.KEYCODE_1:
                setDeviceWifiAndAirplaneThread.start();
                break;

            case KeyEvent.KEYCODE_2:
                setDeviceInWifiOnlyThread.start();
                break;
        }
        return super.onKeyDown(keyCode, event);
    }
}

