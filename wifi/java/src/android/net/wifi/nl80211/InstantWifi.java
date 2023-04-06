/*
 * Copyright (C) 2023 The Android Open Source Project
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
package android.net.wifi.nl80211;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public class InstantWifi {
    private static final String INSTANT_WIFI_TAG = "InstantWifi";
    private static final int OVERRIDED_SCAN_CONNECTION_TIMEOUT_MS = 1000;
    private static final int WIFI_NETWORK_EXPIRED_MS = 7 * 24 * 60 * 60 * 1000; // a week
    private static final String NO_CONNECTION_TIMEOUT_ALARM_TAG =
            INSTANT_WIFI_TAG + " No Connection Timeout";

    private Context mContext;
    private AlarmManager mAlarmManager;
    private Handler mEventHandler;
    private ConnectivityManager mConnectivityManager;
    private WifiManager mWifiManager;
    private PowerManager mPowerManager;
    private long mLastWifiOnSinceBootMs;
    private long mLastScreenOnSinceBootMs;
    private boolean mIsWifiConnected = false;
    private boolean mScreenOn = false;
    private boolean mWifiEnabled = false;
    private boolean mIsNoConnectionAlarmSet = false;
    private ArrayList<WifiNetwork> mConnectedWifiNetworkList = new ArrayList<>();
    private AlarmManager.OnAlarmListener mNoConnectionTimeoutCallback =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    Log.i(INSTANT_WIFI_TAG, "Timed out waiting for wifi connection");
                    mIsNoConnectionAlarmSet = false;
                    mWifiManager.startScan();
                }
            };

    public InstantWifi(Context context, AlarmManager alarmManager, Handler eventHandler) {
        mContext = context;
        mAlarmManager = alarmManager;
        mEventHandler = eventHandler;
        mWifiManager = mContext.getSystemService(WifiManager.class);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mConnectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(), new WifiNetworkCallback());
        // System power service was initialized before wifi nl80211 service.
        mPowerManager = mContext.getSystemService(PowerManager.class);
        IntentFilter screenEventfilter = new IntentFilter();
        screenEventfilter.addAction(Intent.ACTION_SCREEN_ON);
        screenEventfilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(Intent.ACTION_SCREEN_ON)) {
                            if (!mScreenOn) {
                                mLastScreenOnSinceBootMs = getMockableElapsedRealtime();
                            }
                            mScreenOn = true;
                        } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                            mScreenOn = false;
                        }
                        Log.d(INSTANT_WIFI_TAG, "mScreenOn is changed to " + mScreenOn);
                    }
                }, screenEventfilter, null, mEventHandler);
        mScreenOn = mPowerManager.isInteractive();
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                WifiManager.WIFI_STATE_UNKNOWN);
                        mWifiEnabled = state == WifiManager.WIFI_STATE_ENABLED;
                        if (mWifiEnabled) {
                            mLastWifiOnSinceBootMs = getMockableElapsedRealtime();
                        }
                        Log.d(INSTANT_WIFI_TAG, "mWifiEnabled is changed to " + mWifiEnabled);
                    }
                },
                new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                null, mEventHandler);
    }

    @VisibleForTesting
    protected long getMockableElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    private class WifiNetwork {
        private final int mNetId;
        private Set<Integer> mConnectedFrequencies = new HashSet<Integer>();
        private int[] mLastTwoConnectedFrequencies = new int[2];
        private long mLastConnectedTimeMillis;
        WifiNetwork(int netId) {
            mNetId = netId;
        }

        public int getNetId() {
            return mNetId;
        }

        public boolean addConnectedFrequency(int channelFrequency) {
            mLastConnectedTimeMillis = getMockableElapsedRealtime();
            if (mLastTwoConnectedFrequencies[0] != channelFrequency
                    && mLastTwoConnectedFrequencies[1] != channelFrequency) {
                mLastTwoConnectedFrequencies[0] = mLastTwoConnectedFrequencies[1];
                mLastTwoConnectedFrequencies[1] = channelFrequency;
            }
            return mConnectedFrequencies.add(channelFrequency);
        }

        public Set<Integer> getConnectedFrequencies() {
            return mConnectedFrequencies;
        }

        public int[] getLastTwoConnectedFrequencies() {
            if ((getMockableElapsedRealtime() - mLastConnectedTimeMillis)
                    > WIFI_NETWORK_EXPIRED_MS) {
                return new int[0];
            }
            return mLastTwoConnectedFrequencies;
        }

        public long getLastConnectedTimeMillis() {
            return mLastConnectedTimeMillis;
        }
    }

    private class WifiNetworkCallback extends NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
        }

        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            if (networkCapabilities != null && network != null) {
                WifiInfo wifiInfo = (WifiInfo) networkCapabilities.getTransportInfo();
                if (wifiInfo == null || mWifiManager == null) {
                    return;
                }
                WifiConfiguration config = mWifiManager.getPrivilegedConnectedNetwork();
                if (config == null) {
                    return;
                }
                final int currentNetworkId = config.networkId;
                final int connectecFrequency = wifiInfo.getFrequency();
                if (connectecFrequency < 0 || currentNetworkId < 0) {
                    return;
                }
                mIsWifiConnected = true;
                if (mIsNoConnectionAlarmSet) {
                    mAlarmManager.cancel(mNoConnectionTimeoutCallback);
                }
                Log.d(INSTANT_WIFI_TAG, "Receive Wifi is connected, freq =  " + connectecFrequency
                        + " and currentNetworkId : " + currentNetworkId
                        + ", wifiinfo = " + wifiInfo);
                boolean isExist = false;
                for (WifiNetwork wifiNetwork : mConnectedWifiNetworkList) {
                    if (wifiNetwork.getNetId() == currentNetworkId) {
                        if (wifiNetwork.addConnectedFrequency(connectecFrequency)) {
                            Log.d(INSTANT_WIFI_TAG, "Update connected frequency: "
                                    + connectecFrequency + " to Network currentNetworkId : "
                                    + currentNetworkId);
                        }
                        isExist = true;
                    }
                }
                if (!isExist) {
                    WifiNetwork currentNetwork = new WifiNetwork(currentNetworkId);
                    currentNetwork.addConnectedFrequency(connectecFrequency);
                    if (mConnectedWifiNetworkList.size() < 5) {
                        mConnectedWifiNetworkList.add(currentNetwork);
                    } else {
                        ArrayList<WifiNetwork> lastConnectedWifiNetworkList = new ArrayList<>();
                        WifiNetwork legacyNetwork = mConnectedWifiNetworkList.get(0);
                        for (WifiNetwork connectedNetwork : mConnectedWifiNetworkList) {
                            if (connectedNetwork.getNetId() == legacyNetwork.getNetId()) {
                                continue;
                            }
                            // Keep the used recently network in the last connected list
                            if (connectedNetwork.getLastConnectedTimeMillis()
                                    > legacyNetwork.getLastConnectedTimeMillis()) {
                                lastConnectedWifiNetworkList.add(connectedNetwork);
                            } else {
                                lastConnectedWifiNetworkList.add(legacyNetwork);
                                legacyNetwork = connectedNetwork;
                            }
                        }
                        mConnectedWifiNetworkList = lastConnectedWifiNetworkList;
                    }
                }
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            mIsWifiConnected = false;
        }
    }

    /**
     * Returns whether or not the scan freqs should be overrided by using predicted channels.
     */
    public boolean isUsePredictedScanningChannels() {
        if (mIsWifiConnected || mConnectedWifiNetworkList.size() == 0
                || !mWifiManager.isWifiEnabled() || !mPowerManager.isInteractive()) {
            return false;
        }
        if (!mWifiEnabled || !mScreenOn) {
            Log.d(INSTANT_WIFI_TAG, "WiFi/Screen State mis-match, run instant Wifi anyway!");
            return true;
        }
        return (((getMockableElapsedRealtime() - mLastWifiOnSinceBootMs)
                        < OVERRIDED_SCAN_CONNECTION_TIMEOUT_MS)
                || ((getMockableElapsedRealtime() - mLastScreenOnSinceBootMs)
                        < OVERRIDED_SCAN_CONNECTION_TIMEOUT_MS));
    }

    /**
     * Overrides the frequenies in SingleScanSetting
     *
     * @param settings the SingleScanSettings will be overrided.
     * @param freqs new frequencies of SingleScanSettings
     */
    @Nullable
    public void overrideFreqsForSingleScanSettingsIfNecessary(
            @Nullable SingleScanSettings settings, @Nullable Set<Integer> freqs) {
        if (!isUsePredictedScanningChannels() || settings == null || freqs == null
                || freqs.size() == 0) {
            return;
        }
        if (settings.channelSettings == null) {
            settings.channelSettings = new ArrayList<>();
        } else {
            settings.channelSettings.clear();
        }
        for (int freq : freqs) {
            if (freq > 0) {
                ChannelSettings channel = new ChannelSettings();
                channel.frequency = freq;
                settings.channelSettings.add(channel);
            }
        }
        // Monitor connection after last override scan request.
        if (mIsNoConnectionAlarmSet) {
            mAlarmManager.cancel(mNoConnectionTimeoutCallback);
        }
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                getMockableElapsedRealtime() + OVERRIDED_SCAN_CONNECTION_TIMEOUT_MS,
                NO_CONNECTION_TIMEOUT_ALARM_TAG, mNoConnectionTimeoutCallback, mEventHandler);
        mIsNoConnectionAlarmSet = true;
    }

    /**
     * Returns the predicted scanning chcnnels set.
     */
    @NonNull
    public Set<Integer> getPredictedScanningChannels() {
        Set<Integer> predictedScanChannels = new HashSet<>();
        if (!isUsePredictedScanningChannels()) {
            Log.d(INSTANT_WIFI_TAG, "Drop, size: " + mConnectedWifiNetworkList.size());
            return predictedScanChannels;
        }
        for (WifiNetwork network : mConnectedWifiNetworkList) {
            for (int connectedFrequency : network.getLastTwoConnectedFrequencies()) {
                if (connectedFrequency > 0) {
                    predictedScanChannels.add(connectedFrequency);
                    Log.d(INSTANT_WIFI_TAG, "Add channel: " + connectedFrequency
                            + " to predicted channel");
                }
            }
        }
        return predictedScanChannels;
    }
}
