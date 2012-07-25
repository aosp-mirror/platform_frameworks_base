/*
 * Copyright (C) 2011, The Android Open Source Project
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

package com.android.bandwidthtest.util;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import com.android.bandwidthtest.NetworkState;
import com.android.bandwidthtest.NetworkState.StateTransitionDirection;
import com.android.internal.util.AsyncChannel;

import junit.framework.Assert;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

/*
 * Utility class used to set the connectivity of the device and to download files.
 */
public class ConnectionUtil {
    private static final String LOG_TAG = "ConnectionUtil";
    private static final String DOWNLOAD_MANAGER_PKG_NAME = "com.android.providers.downloads";
    private static final int WAIT_FOR_SCAN_RESULT = 10 * 1000; // 10 seconds
    private static final int WIFI_SCAN_TIMEOUT = 50 * 1000;
    public static final int SHORT_TIMEOUT = 5 * 1000;
    public static final int LONG_TIMEOUT = 5 * 60 * 1000; // 5 minutes
    private ConnectivityReceiver mConnectivityReceiver = null;
    private WifiReceiver mWifiReceiver = null;
    private DownloadReceiver mDownloadReceiver = null;
    private DownloadManager mDownloadManager;
    private NetworkInfo mNetworkInfo;
    private NetworkInfo mOtherNetworkInfo;
    private boolean mScanResultIsAvailable = false;
    private ConnectivityManager mCM;
    private Object mWifiMonitor = new Object();
    private Object mConnectivityMonitor = new Object();
    private Object mDownloadMonitor = new Object();
    private int mWifiState;
    private NetworkInfo mWifiNetworkInfo;
    private WifiManager mWifiManager;
    private Context mContext;
    // Verify connectivity state
    private static final int NUM_NETWORK_TYPES = ConnectivityManager.MAX_NETWORK_TYPE + 1;
    private NetworkState[] mConnectivityState = new NetworkState[NUM_NETWORK_TYPES];

    public ConnectionUtil(Context context) {
        mContext = context;
    }

    /**
     * Initialize the class. Needs to be called before any other methods in {@link ConnectionUtil}
     *
     * @throws Exception
     */
    public void initialize() throws Exception {
        // Register a connectivity receiver for CONNECTIVITY_ACTION
        mConnectivityReceiver = new ConnectivityReceiver();
        mContext.registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // Register a download receiver for ACTION_DOWNLOAD_COMPLETE
        mDownloadReceiver = new DownloadReceiver();
        mContext.registerReceiver(mDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        // Register a wifi receiver
        mWifiReceiver = new WifiReceiver();
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mWifiReceiver, mIntentFilter);

        // Get an instance of ConnectivityManager
        mCM = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        // Get an instance of WifiManager
        mWifiManager =(WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);

        mDownloadManager = (DownloadManager)mContext.getSystemService(Context.DOWNLOAD_SERVICE);

        initializeNetworkStates();


    }

    /**
     * Additional initialization needed for wifi related tests.
     */
    public void wifiTestInit() {
        mWifiManager.setWifiEnabled(true);
        Log.v(LOG_TAG, "Clear Wifi before we start the test.");
        sleep(SHORT_TIMEOUT);
        removeConfiguredNetworksAndDisableWifi();
    }


    /**
     * A wrapper of a broadcast receiver which provides network connectivity information
     * for all kinds of network: wifi, mobile, etc.
     */
    private class ConnectivityReceiver extends BroadcastReceiver {
        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isInitialStickyBroadcast()) {
                Log.d(LOG_TAG, "This is a sticky broadcast don't do anything.");
                return;
            }
            Log.v(LOG_TAG, "ConnectivityReceiver: onReceive() is called with " + intent);
            String action = intent.getAction();
            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.v("ConnectivityReceiver", "onReceive() called with " + intent);
                return;
            }

            final ConnectivityManager connManager = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            mNetworkInfo = connManager.getActiveNetworkInfo();

            if (intent.hasExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO)) {
                mOtherNetworkInfo = (NetworkInfo)
                        intent.getParcelableExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);
            }

            Log.v(LOG_TAG, "mNetworkInfo: " + mNetworkInfo.toString());
            recordNetworkState(mNetworkInfo.getType(), mNetworkInfo.getState());
            if (mOtherNetworkInfo != null) {
                Log.v(LOG_TAG, "mOtherNetworkInfo: " + mOtherNetworkInfo.toString());
                recordNetworkState(mOtherNetworkInfo.getType(), mOtherNetworkInfo.getState());
            }
            notifyNetworkConnectivityChange();
        }
    }

    /**
     * A wrapper of a broadcast receiver which provides wifi information.
     */
    private class WifiReceiver extends BroadcastReceiver {
        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v("WifiReceiver", "onReceive() is calleld with " + intent);
            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                Log.v(LOG_TAG, "Scan results are available");
                notifyScanResult();
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                mWifiNetworkInfo =
                        (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                Log.v(LOG_TAG, "mWifiNetworkInfo: " + mWifiNetworkInfo.toString());
                if (mWifiNetworkInfo.getState() == State.CONNECTED) {
                    intent.getStringExtra(WifiManager.EXTRA_BSSID);
                }
                notifyWifiState();
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                notifyWifiState();
            } else if (action.equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                notifyWifiAPState();
            } else {
                return;
            }
        }
    }

    /**
     * A wrapper of a broadcast receiver which provides download manager information.
     */
    private class DownloadReceiver extends BroadcastReceiver {
        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v("DownloadReceiver", "onReceive() is called with " + intent);
            // Download complete
            if (action.equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                notifiyDownloadState();
            }
        }
    }

    private class WifiServiceHandler extends Handler {
        /**
         * {@inheritDoc}
         */
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        // AsyncChannel in msg.obj
                    } else {
                        Log.v(LOG_TAG, "Failed to establish AsyncChannel connection");
                    }
                    break;
                default:
                    // Ignore
                    break;
            }
        }
    }

    /**
     * Initialize all the network states.
     */
    public void initializeNetworkStates() {
        // For each network type, initialize network states to UNKNOWN, and no verification
        // flag is set.
        for (int networkType = NUM_NETWORK_TYPES - 1; networkType >= 0; networkType--) {
            mConnectivityState[networkType] =  new NetworkState();
            Log.v(LOG_TAG, "Initialize network state for " + networkType + ": " +
                    mConnectivityState[networkType].toString());
        }
    }

    public void recordNetworkState(int networkType, State networkState) {
        // deposit a network state
        Log.v(LOG_TAG, "record network state for network " +  networkType +
                ", state is " + networkState);
        mConnectivityState[networkType].recordState(networkState);
    }

    /**
     * Set the state transition criteria
     *
     * @param networkType
     * @param initState
     * @param transitionDir
     * @param targetState
     */
    public void setStateTransitionCriteria(int networkType, State initState,
            StateTransitionDirection transitionDir, State targetState) {
        mConnectivityState[networkType].setStateTransitionCriteria(
                initState, transitionDir, targetState);
    }

    /**
     * Validate the states recorded.
     * @param networkType
     * @return
     */
    public boolean validateNetworkStates(int networkType) {
        Log.v(LOG_TAG, "validate network state for " + networkType + ": ");
        return mConnectivityState[networkType].validateStateTransition();
    }

    /**
     * Fetch the failure reason for the transition.
     * @param networkType
     * @return result from network state validation
     */
    public String getTransitionFailureReason(int networkType) {
        Log.v(LOG_TAG, "get network state transition failure reason for " + networkType + ": " +
                mConnectivityState[networkType].toString());
        return mConnectivityState[networkType].getFailureReason();
    }

    /**
     * Send a notification via the mConnectivityMonitor when the network connectivity changes.
     */
    private void notifyNetworkConnectivityChange() {
        synchronized(mConnectivityMonitor) {
            Log.v(LOG_TAG, "notify network connectivity changed");
            mConnectivityMonitor.notifyAll();
        }
    }

    /**
     * Send a notification when a scan for the wifi network is done.
     */
    private void notifyScanResult() {
        synchronized (this) {
            Log.v(LOG_TAG, "notify that scan results are available");
            this.notify();
        }
    }

    /**
     * Send a notification via the mWifiMonitor when the wifi state changes.
     */
    private void notifyWifiState() {
        synchronized (mWifiMonitor) {
            Log.v(LOG_TAG, "notify wifi state changed.");
            mWifiMonitor.notify();
        }
    }

    /**
     * Send a notification via the mDownloadMonitor when a download is complete.
     */
    private void notifiyDownloadState() {
        synchronized (mDownloadMonitor) {
            Log.v(LOG_TAG, "notifiy download manager state changed.");
            mDownloadMonitor.notify();
        }
    }

    /**
     * Send a notification when the wifi ap state changes.
     */
    private void notifyWifiAPState() {
        synchronized (this) {
            Log.v(LOG_TAG, "notify wifi AP state changed.");
            this.notify();
        }
    }

    /**
     * Start a download on a given url and wait for completion.
     *
     * @param targetUrl the target to download.x
     * @param timeout to wait for download to finish
     * @return true if we successfully downloaded the requestedUrl, false otherwise.
     */
    public boolean startDownloadAndWait(String targetUrl, long timeout) {
        if (targetUrl.length() == 0 || targetUrl == null) {
            Log.v(LOG_TAG, "Empty or Null target url requested to DownloadManager");
            return true;
        }
        Request request = new Request(Uri.parse(targetUrl));
        long enqueue = mDownloadManager.enqueue(request);
        Log.v(LOG_TAG, "Sending download request of " + targetUrl + " to DownloadManager");
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                Log.v(LOG_TAG, "startDownloadAndWait timed out, failed to fetch " + targetUrl +
                        " within " + timeout);
                return downloadSuccessful(enqueue);
            }
            Log.v(LOG_TAG, "Waiting for the download to finish " + targetUrl);
            synchronized (mDownloadMonitor) {
                try {
                    mDownloadMonitor.wait(SHORT_TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (!downloadSuccessful(enqueue)) {
                    continue;
                }
                return true;
            }
        }
    }

    /**
     * Fetch the Download Manager's UID.
     * @return the Download Manager's UID
     */
    public int downloadManagerUid() {
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(DOWNLOAD_MANAGER_PKG_NAME,
                    PackageManager.GET_META_DATA);
            return appInfo.uid;
        } catch (NameNotFoundException e) {
            Log.d(LOG_TAG, "Did not find the package for the download service.");
            return -1;
        }
    }

    /**
     * Determines if a given download was successful by querying the DownloadManager.
     *
     * @param enqueue the id used to identify/query the DownloadManager with.
     * @return true if download was successful, false otherwise.
     */
    private boolean downloadSuccessful(long enqueue) {
        Query query = new Query();
        query.setFilterById(enqueue);
        Cursor c = mDownloadManager.query(query);
        if (c.moveToFirst()) {
            int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(columnIndex)) {
                Log.v(LOG_TAG, "Successfully downloaded file!");
                return true;
            }
        }
        return false;
    }

    /**
     * Wait for network connectivity state.
     * @param networkType the network to check for
     * @param expectedState the desired state
     * @param timeout in milliseconds
     * @return true if the network connectivity state matched what was expected
     */
    public boolean waitForNetworkState(int networkType, State expectedState, long timeout) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout) {
                Log.v(LOG_TAG, "waitForNetworkState time out, the state of network type " + networkType +
                        " is: " + mCM.getNetworkInfo(networkType).getState());
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
            synchronized (mConnectivityMonitor) {
                try {
                    mConnectivityMonitor.wait(SHORT_TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (mNetworkInfo == null) {
                    Log.v(LOG_TAG, "Do not have networkInfo! Force fetch of network info.");
                    mNetworkInfo = mCM.getActiveNetworkInfo();
                }
                // Still null after force fetch? Maybe the network did not have time to be brought
                // up yet.
                if (mNetworkInfo == null) {
                    Log.v(LOG_TAG, "Failed to force fetch networkInfo. " +
                            "The network is still not ready. Wait for the next broadcast");
                    continue;
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

    /**
     * Wait for a given wifi state to occur within a given timeout.
     * @param expectedState the expected wifi state.
     * @param timeout for the state to be set in milliseconds.
     * @return true if the state was achieved within the timeout, false otherwise.
     */
    public boolean waitForWifiState(int expectedState, long timeout) {
        // Wait for Wifi state: WIFI_STATE_DISABLED, WIFI_STATE_DISABLING, WIFI_STATE_ENABLED,
        //                      WIFI_STATE_ENALBING, WIFI_STATE_UNKNOWN
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
            synchronized (mWifiMonitor) {
                try {
                    mWifiMonitor.wait(SHORT_TIMEOUT);
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

    /**
     * Convenience method to determine if we are connected to a mobile network.
     * @return true if connected to a mobile network, false otherwise.
     */
    public boolean isConnectedToMobile() {
        NetworkInfo networkInfo = mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        return networkInfo.isConnected();
    }

    /**
     * Convenience method to determine if we are connected to wifi.
     * @return true if connected to wifi, false otherwise.
     */
    public boolean isConnectedToWifi() {
        NetworkInfo networkInfo = mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return networkInfo.isConnected();
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
     * Connect to Wi-Fi with the given configuration.
     * @param config
     * @return true if we are connected to a given AP.
     */
    public boolean connectToWifiWithConfiguration(WifiConfiguration config) {
        //  The SSID in the configuration is a pure string, need to convert it to a quoted string.
        String ssid = config.SSID;
        config.SSID = convertToQuotedString(ssid);

        // If wifi is not enabled, enable it
        if (!mWifiManager.isWifiEnabled()) {
            Log.v(LOG_TAG, "Wifi is not enabled, enable it");
            mWifiManager.setWifiEnabled(true);
            // wait for the wifi state change before start scanning.
            if (!waitForWifiState(WifiManager.WIFI_STATE_ENABLED, 2 * SHORT_TIMEOUT)) {
                Log.v(LOG_TAG, "Wait for WIFI_STATE_ENABLED failed");
                return false;
            }
        }

        boolean foundApInScanResults = false;
        for (int retry = 0; retry < 5; retry++) {
            List<ScanResult> netList = mWifiManager.getScanResults();
            if (netList != null) {
                Log.v(LOG_TAG, "size of scan result list: " + netList.size());
                for (int i = 0; i < netList.size(); i++) {
                    ScanResult sr= netList.get(i);
                    if (sr.SSID.equals(ssid)) {
                        Log.v(LOG_TAG, "Found " + ssid + " in the scan result list.");
                        Log.v(LOG_TAG, "Retry: " + retry);
                        foundApInScanResults = true;
                        mWifiManager.connect(config, new WifiManager.ActionListener() {
                                public void onSuccess() {
                                }
                                public void onFailure(int reason) {
                                    Log.e(LOG_TAG, "connect failed " + reason);
                                }
                            });

                        break;
                    }
                }
            }
            if (foundApInScanResults) {
                return true;
            } else {
                // Start an active scan
                mWifiManager.startScanActive();
                mScanResultIsAvailable = false;
                long startTime = System.currentTimeMillis();
                while (!mScanResultIsAvailable) {
                    if ((System.currentTimeMillis() - startTime) > WIFI_SCAN_TIMEOUT) {
                        Log.v(LOG_TAG, "wait for scan results timeout");
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
        }
        return false;
    }

    /*
     * Disconnect from the current AP and remove configured networks.
     */
    public boolean disconnectAP() {
        // remove saved networks
        List<WifiConfiguration> wifiConfigList = mWifiManager.getConfiguredNetworks();
        Log.v(LOG_TAG, "size of wifiConfigList: " + wifiConfigList.size());
        for (WifiConfiguration wifiConfig: wifiConfigList) {
            Log.v(LOG_TAG, "Remove wifi configuration: " + wifiConfig.networkId);
            int netId = wifiConfig.networkId;
            mWifiManager.forget(netId, new WifiManager.ActionListener() {
                    public void onSuccess() {
                    }
                    public void onFailure(int reason) {
                        Log.e(LOG_TAG, "forget failed " + reason);
                    }
                });
        }
        return true;
    }

    /**
     * Enable Wifi
     * @return true if Wifi is enabled successfully
     */
    public boolean enableWifi() {
        return mWifiManager.setWifiEnabled(true);
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
        sleep(SHORT_TIMEOUT);
        if (!mWifiManager.setWifiEnabled(false)) {
            return false;
        }
        sleep(SHORT_TIMEOUT);
        return true;
    }

    /**
     * Make the current thread sleep.
     * @param sleeptime the time to sleep in milliseconds
     */
    private void sleep(long sleeptime) {
        try {
            Thread.sleep(sleeptime);
        } catch (InterruptedException e) {}
    }

    /**
     * Set airplane mode on device, caller is responsible to ensuring correct state.
     * @param context {@link Context}
     * @param enableAM to enable or disable airplane mode.
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

    /**
     * Add quotes around the string.
     * @param string to convert
     * @return string with quotes around it
     */
    protected static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    public void cleanUp() {
        // Unregister receivers if defined.
        if (mConnectivityReceiver != null) {
            mContext.unregisterReceiver(mConnectivityReceiver);
        }
        if (mWifiReceiver != null) {
            mContext.unregisterReceiver(mWifiReceiver);
        }
        if (mDownloadReceiver != null) {
            mContext.unregisterReceiver(mDownloadReceiver);
        }
        Log.v(LOG_TAG, "onDestroy, inst=" + Integer.toHexString(hashCode()));
    }

    /**
     * Helper method used to test data connectivity by pinging a series of popular sites.
     * @return true if device has data connectivity, false otherwise.
     */
    public boolean hasData() {
        String[] hostList = {"www.google.com", "www.yahoo.com",
                "www.bing.com", "www.facebook.com", "www.ask.com"};
        try {
            for (int i = 0; i < hostList.length; ++i) {
                String host = hostList[i];
                Process p = Runtime.getRuntime().exec("ping -c 10 -w 100 " + host);
                int status = p.waitFor();
                if (status == 0) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            Log.e(LOG_TAG, "Ping test Failed: Unknown Host");
        } catch (IOException e) {
            Log.e(LOG_TAG, "Ping test Failed: IOException");
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Ping test Failed: InterruptedException");
        }
        return false;
    }
}
