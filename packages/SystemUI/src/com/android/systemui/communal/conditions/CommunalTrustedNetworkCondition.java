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

import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.settings.SecureSettings;

import java.util.Arrays;
import java.util.HashSet;

import javax.inject.Inject;

/**
 * Monitors Wi-Fi connections and triggers callback, if any, when the device is connected to and
 * disconnected from a trusted network.
 */
public class CommunalTrustedNetworkCondition extends CommunalCondition {
    private final String mTag = getClass().getSimpleName();
    private final ConnectivityManager mConnectivityManager;
    private final ContentObserver mTrustedNetworksObserver;
    private final SecureSettings mSecureSettings;

    // The SSID of the connected Wi-Fi network. Null if not connected to Wi-Fi.
    private String mWifiSSID;

    // Set of SSIDs of trusted networks.
    private final HashSet<String> mTrustedNetworks = new HashSet<>();

    /**
     * The deliminator used to separate trusted network keys saved as a string in secure settings.
     */
    public static final String SETTINGS_STRING_DELIMINATOR = ",/";

    @Inject
    public CommunalTrustedNetworkCondition(@Main Handler handler,
            ConnectivityManager connectivityManager, SecureSettings secureSettings) {
        mConnectivityManager = connectivityManager;
        mSecureSettings = secureSettings;

        mTrustedNetworksObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                fetchTrustedNetworks();
            }
        };
    }

    /**
     * Starts monitoring for trusted network connection. Ignores if already started.
     */
    @Override
    protected void start() {
        if (shouldLog()) Log.d(mTag, "start listening for wifi connections");

        fetchTrustedNetworks();

        final NetworkRequest wifiNetworkRequest = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        mConnectivityManager.registerNetworkCallback(wifiNetworkRequest, mNetworkCallback);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.COMMUNAL_MODE_TRUSTED_NETWORKS, false, mTrustedNetworksObserver,
                UserHandle.USER_SYSTEM);
    }

    /**
     * Stops monitoring for trusted network connection.
     */
    @Override
    protected void stop() {
        if (shouldLog()) Log.d(mTag, "stop listening for wifi connections");

        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mSecureSettings.unregisterContentObserver(mTrustedNetworksObserver);
    }

    private void updateWifiInfo(WifiInfo wifiInfo) {
        if (wifiInfo == null) {
            mWifiSSID = null;
        } else {
            // Remove the wrapping quotes around the SSID.
            mWifiSSID = wifiInfo.getSSID().replace("\"", "");
        }

        checkIfConnectedToTrustedNetwork();
    }

    private void fetchTrustedNetworks() {
        final String trustedNetworksString = mSecureSettings.getStringForUser(
                Settings.Secure.COMMUNAL_MODE_TRUSTED_NETWORKS, UserHandle.USER_SYSTEM);
        mTrustedNetworks.clear();

        if (shouldLog()) Log.d(mTag, "fetched trusted networks: " + trustedNetworksString);

        if (TextUtils.isEmpty(trustedNetworksString)) {
            return;
        }

        mTrustedNetworks.addAll(
                Arrays.asList(trustedNetworksString.split(SETTINGS_STRING_DELIMINATOR)));

        checkIfConnectedToTrustedNetwork();
    }

    private void checkIfConnectedToTrustedNetwork() {
        final boolean connectedToTrustedNetwork = mWifiSSID != null && mTrustedNetworks.contains(
                mWifiSSID);

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
