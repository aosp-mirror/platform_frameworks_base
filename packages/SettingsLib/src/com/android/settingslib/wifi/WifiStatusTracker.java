/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.android.settingslib.wifi;

import static android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.NetworkRequest;
import android.net.NetworkScoreManager;
import android.net.ScoredNetwork;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.FeatureFlagUtils;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Track status of Wi-Fi for the Sys UI.
 */
public class WifiStatusTracker {
    private static final int HISTORY_SIZE = 32;
    private static final SimpleDateFormat SSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    private final Context mContext;
    private final WifiNetworkScoreCache mWifiNetworkScoreCache;
    private final WifiManager mWifiManager;
    private final NetworkScoreManager mNetworkScoreManager;
    private final ConnectivityManager mConnectivityManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<Integer> mNetworks = new HashSet<>();
    // Save the previous HISTORY_SIZE states for logging.
    private final String[] mHistory = new String[HISTORY_SIZE];
    // Where to copy the next state into.
    private int mHistoryIndex;
    private final WifiNetworkScoreCache.CacheListener mCacheListener =
            new WifiNetworkScoreCache.CacheListener(mHandler) {
                @Override
                public void networkCacheUpdated(List<ScoredNetwork> updatedNetworks) {
                    updateStatusLabel();
                    mCallback.run();
                }
            };
    private final NetworkRequest mNetworkRequest = new NetworkRequest.Builder()
            .clearCapabilities()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build();
    private final NetworkCallback mNetworkCallback =
            new NetworkCallback(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        // Note: onCapabilitiesChanged is guaranteed to be called "immediately" after onAvailable
        // and onLinkPropertiesChanged.
        @Override
        public void onCapabilitiesChanged(
                Network network, NetworkCapabilities networkCapabilities) {
            if (!mNetworks.contains(network.getNetId())) {
                // New network
                boolean isVcnOverWifi =
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                && (Utils.tryGetWifiInfoForVcn(networkCapabilities) != null);
                boolean isWifi =
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                if (isVcnOverWifi || isWifi) {
                    mNetworks.add(network.getNetId());
                }
            }

            WifiInfo wifiInfo = null;
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                wifiInfo = Utils.tryGetWifiInfoForVcn(networkCapabilities);
            } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiInfo = (WifiInfo) networkCapabilities.getTransportInfo();
            }
            String log = new StringBuilder()
                    .append(SSDF.format(System.currentTimeMillis())).append(",")
                    .append("onCapabilitiesChanged: ")
                    .append("network=").append(network).append(",")
                    .append("networkCapabilities=").append(networkCapabilities)
                    .toString();
            recordLastWifiNetwork(log);
            if (wifiInfo != null) {
                updateWifiInfo(wifiInfo);
                updateStatusLabel();
                mCallback.run();
            }
        }

        @Override
        public void onLost(Network network) {
            String log = new StringBuilder()
                    .append(SSDF.format(System.currentTimeMillis())).append(",")
                    .append("onLost: ")
                    .append("network=").append(network)
                    .toString();
            recordLastWifiNetwork(log);
            if (mNetworks.contains(network.getNetId())) {
                mNetworks.remove(network.getNetId());
                updateWifiInfo(null);
                updateStatusLabel();
                mCallback.run();
            }
        }
    };
    private final NetworkCallback mDefaultNetworkCallback =
            new NetworkCallback(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
            // network is now the default network, and its capabilities are nc.
            // This method will always be called immediately after the network becomes the
            // default, in addition to any time the capabilities change while the network is
            // the default.
            mDefaultNetwork = network;
            mDefaultNetworkCapabilities = nc;
            updateStatusLabel();
            mCallback.run();
        }
        @Override
        public void onLost(Network network) {
            // The system no longer has a default network.
            mDefaultNetwork = null;
            mDefaultNetworkCapabilities = null;
            updateStatusLabel();
            mCallback.run();
        }
    };
    private Network mDefaultNetwork = null;
    private NetworkCapabilities mDefaultNetworkCapabilities = null;
    private final Runnable mCallback;
    private final boolean mProviderModel;

    private WifiInfo mWifiInfo;
    public boolean enabled;
    public boolean isCaptivePortal;
    public boolean isDefaultNetwork;
    public boolean isCarrierMerged;
    public int subId;
    public int state;
    public boolean connected;
    public String ssid;
    public int rssi;
    public int level;
    public String statusLabel;

    public WifiStatusTracker(Context context, WifiManager wifiManager,
            NetworkScoreManager networkScoreManager, ConnectivityManager connectivityManager,
            Runnable callback) {
        mContext = context;
        mWifiManager = wifiManager;
        mWifiNetworkScoreCache = new WifiNetworkScoreCache(context);
        mNetworkScoreManager = networkScoreManager;
        mConnectivityManager = connectivityManager;
        mCallback = callback;
        mProviderModel = FeatureFlagUtils.isEnabled(
                mContext, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }

    public void setListening(boolean listening) {
        if (listening) {
            mNetworkScoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI,
                    mWifiNetworkScoreCache, NetworkScoreManager.SCORE_FILTER_CURRENT_NETWORK);
            mWifiNetworkScoreCache.registerListener(mCacheListener);
            mConnectivityManager.registerNetworkCallback(
                    mNetworkRequest, mNetworkCallback, mHandler);
            mConnectivityManager.registerDefaultNetworkCallback(mDefaultNetworkCallback, mHandler);
        } else {
            mNetworkScoreManager.unregisterNetworkScoreCache(NetworkKey.TYPE_WIFI,
                    mWifiNetworkScoreCache);
            mWifiNetworkScoreCache.unregisterListener();
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mConnectivityManager.unregisterNetworkCallback(mDefaultNetworkCallback);
        }
    }

    /**
     * Fetches initial state as if a WifiManager.NETWORK_STATE_CHANGED_ACTION have been received.
     * This replaces the dependency on the initial sticky broadcast.
     */
    public void fetchInitialState() {
        if (mWifiManager == null) {
            return;
        }
        updateWifiState();
        final NetworkInfo networkInfo =
                mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        connected = networkInfo != null && networkInfo.isConnected();
        mWifiInfo = null;
        ssid = null;
        if (connected) {
            mWifiInfo = mWifiManager.getConnectionInfo();
            if (mWifiInfo != null) {
                if (mWifiInfo.isPasspointAp() || mWifiInfo.isOsuAp()) {
                    ssid = mWifiInfo.getPasspointProviderFriendlyName();
                } else {
                    ssid = getValidSsid(mWifiInfo);
                }
                if (mProviderModel) {
                    isCarrierMerged = mWifiInfo.isCarrierMerged();
                    subId = mWifiInfo.getSubscriptionId();
                }
                updateRssi(mWifiInfo.getRssi());
                maybeRequestNetworkScore();
            }
        }
        updateStatusLabel();
    }

    public void handleBroadcast(Intent intent) {
        if (mWifiManager == null) {
            return;
        }
        String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            updateWifiState();
        }
    }

    private void updateWifiInfo(WifiInfo wifiInfo) {
        updateWifiState();
        connected = wifiInfo != null;
        mWifiInfo = wifiInfo;
        ssid = null;
        if (mWifiInfo != null) {
            if (mWifiInfo.isPasspointAp() || mWifiInfo.isOsuAp()) {
                ssid = mWifiInfo.getPasspointProviderFriendlyName();
            } else {
                ssid = getValidSsid(mWifiInfo);
            }
            if (mProviderModel) {
                isCarrierMerged = mWifiInfo.isCarrierMerged();
                subId = mWifiInfo.getSubscriptionId();
            }
            updateRssi(mWifiInfo.getRssi());
            maybeRequestNetworkScore();
        }
    }

    private void updateWifiState() {
        state = mWifiManager.getWifiState();
        enabled = state == WifiManager.WIFI_STATE_ENABLED;
        isCarrierMerged = false;
        subId = 0;
    }

    private void updateRssi(int newRssi) {
        rssi = newRssi;
        level = mWifiManager.calculateSignalLevel(rssi);
    }

    private void maybeRequestNetworkScore() {
        NetworkKey networkKey = NetworkKey.createFromWifiInfo(mWifiInfo);
        if (mWifiNetworkScoreCache.getScoredNetwork(networkKey) == null) {
            mNetworkScoreManager.requestScores(new NetworkKey[]{ networkKey });
        }
    }

    private void updateStatusLabel() {
        if (mWifiManager == null) {
            return;
        }
        NetworkCapabilities networkCapabilities;
        isDefaultNetwork = false;
        if (mDefaultNetworkCapabilities != null) {
            boolean isWifi = mDefaultNetworkCapabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI);
            boolean isVcnOverWifi = mDefaultNetworkCapabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR)
                            && (Utils.tryGetWifiInfoForVcn(mDefaultNetworkCapabilities) != null);
            if (isWifi || isVcnOverWifi) {
                isDefaultNetwork = true;
            }
        }
        if (isDefaultNetwork) {
            // Wifi is connected and the default network.
            networkCapabilities = mDefaultNetworkCapabilities;
        } else {
            networkCapabilities = mConnectivityManager.getNetworkCapabilities(
                    mWifiManager.getCurrentNetwork());
        }
        isCaptivePortal = false;
        if (networkCapabilities != null) {
            if (networkCapabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)) {
                statusLabel = mContext.getString(R.string.wifi_status_sign_in_required);
                isCaptivePortal = true;
                return;
            } else if (networkCapabilities.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
                statusLabel = mContext.getString(R.string.wifi_limited_connection);
                return;
            } else if (!networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                final String mode = Settings.Global.getString(mContext.getContentResolver(),
                        Settings.Global.PRIVATE_DNS_MODE);
                if (networkCapabilities.isPrivateDnsBroken()) {
                    statusLabel = mContext.getString(R.string.private_dns_broken);
                } else {
                    statusLabel = mContext.getString(R.string.wifi_status_no_internet);
                }
                return;
            } else if (!isDefaultNetwork && mDefaultNetworkCapabilities != null
                    && mDefaultNetworkCapabilities.hasTransport(TRANSPORT_CELLULAR)) {
                statusLabel = mContext.getString(R.string.wifi_connected_low_quality);
                return;
            }
        }

        ScoredNetwork scoredNetwork =
                mWifiNetworkScoreCache.getScoredNetwork(NetworkKey.createFromWifiInfo(mWifiInfo));
        statusLabel = scoredNetwork == null
                ? null : AccessPoint.getSpeedLabel(mContext, scoredNetwork, rssi);
    }

    /** Refresh the status label on Locale changed. */
    public void refreshLocale() {
        updateStatusLabel();
        mCallback.run();
    }

    private String getValidSsid(WifiInfo info) {
        String ssid = info.getSSID();
        if (ssid != null && !WifiManager.UNKNOWN_SSID.equals(ssid)) {
            return ssid;
        }
        return null;
    }

    private void recordLastWifiNetwork(String log) {
        mHistory[mHistoryIndex] = log;
        mHistoryIndex = (mHistoryIndex + 1) % HISTORY_SIZE;
    }

    /** Dump function. */
    public void dump(PrintWriter pw) {
        pw.println("  - WiFi Network History ------");
        int size = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            if (mHistory[i] != null) size++;
        }
        // Print out the previous states in ordered number.
        for (int i = mHistoryIndex + HISTORY_SIZE - 1;
                i >= mHistoryIndex + HISTORY_SIZE - size; i--) {
            pw.println("  Previous WiFiNetwork("
                    + (mHistoryIndex + HISTORY_SIZE - i) + "): "
                    + mHistory[i & (HISTORY_SIZE - 1)]);
        }
    }
}
