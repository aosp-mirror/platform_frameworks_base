/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal.conditions;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import javax.inject.Inject;

/**
 * Monitors Wi-Fi connections and triggers callback, if any, when the device is connected to and
 * disconnected from a trusted network.
 */
public class CommunalTrustedNetworkCondition extends CommunalCondition {
    private final String mTag = getClass().getSimpleName();
    private final ConnectivityManager mConnectivityManager;

    @Inject
    public CommunalTrustedNetworkCondition(ConnectivityManager connectivityManager) {
        mConnectivityManager = connectivityManager;
    }

    /**
     * Starts monitoring for trusted network connection. Ignores if already started.
     */
    @Override
    protected void start() {
        if (shouldLog()) Log.d(mTag, "start listening for wifi connections");

        final NetworkRequest wifiNetworkRequest = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        mConnectivityManager.registerNetworkCallback(wifiNetworkRequest, mNetworkCallback);
    }

    /**
     * Stops monitoring for trusted network connection.
     */
    @Override
    protected void stop() {
        if (shouldLog()) Log.d(mTag, "stop listening for wifi connections");

        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
    }

    private void updateWifiInfo(WifiInfo wifiInfo) {
        // TODO(b/202778351): check whether wifi is trusted.
        final boolean connectedToTrustedNetwork = wifiInfo != null;

        if (shouldLog()) {
            Log.d(mTag, (connectedToTrustedNetwork ? "connected to" : "disconnected from")
                    + " a trusted network");
        }

        updateCondition(connectedToTrustedNetwork);
    }

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                private boolean mIsConnected = false;
                private WifiInfo mWifiInfo;

                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);

                    if (shouldLog()) Log.d(mTag, "connected to wifi");

                    mIsConnected = true;
                    if (mWifiInfo != null) {
                        updateWifiInfo(mWifiInfo);
                    }
                }

                @Override
                public void onLost(@NonNull Network network) {
                    super.onLost(network);

                    if (shouldLog()) Log.d(mTag, "disconnected from wifi");

                    mIsConnected = false;
                    mWifiInfo = null;
                    updateWifiInfo(null);
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                        @NonNull NetworkCapabilities networkCapabilities) {
                    super.onCapabilitiesChanged(network, networkCapabilities);

                    mWifiInfo = (WifiInfo) networkCapabilities.getTransportInfo();

                    if (mIsConnected) {
                        updateWifiInfo(mWifiInfo);
                    }
                }
            };

    private boolean shouldLog() {
        return Log.isLoggable(mTag, Log.DEBUG);
    }
}
