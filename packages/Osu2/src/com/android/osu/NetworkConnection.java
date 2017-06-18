/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.osu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;

/**
 * Responsible for setup/monitor on a Wi-Fi connection.
 */
public class NetworkConnection {
    private static final String TAG = "OSU_NetworkConnection";

    private final WifiManager mWifiManager;
    private final Callbacks mCallbacks;
    private final int mNetworkId;
    private boolean mConnected = false;

    /**
     * Callbacks on Wi-Fi connection state changes.
     */
    public interface Callbacks {
        /**
         * Invoked when network connection is established with IP connectivity.
         *
         * @param network {@link Network} associated with the connected network.
         */
        public void onConnected(Network network);

        /**
         * Invoked when the targeted network is disconnected.
         */
        public void onDisconnected();

        /**
         * Invoked when network connection is not established within the pre-defined timeout.
         */
        public void onTimeout();
    }

    /**
     * Create an instance of {@link NetworkConnection} for the specified Wi-Fi network.
     * The Wi-Fi network (specified by its SSID) will be added/enabled as part of this object
     * creation.
     *
     * {@link #teardown} will need to be invoked once you're done with this connection,
     * to remove the given Wi-Fi network from the framework.
     *
     * @param context The application context
     * @param handler The handler to dispatch the processing of received broadcast intents
     * @param ssid The SSID to connect to
     * @param nai The network access identifier associated with the AP
     * @param callbacks The callbacks to be invoked on network change events
     * @throws IOException when failed to add/enable the specified Wi-Fi network
     */
    public NetworkConnection(Context context, Handler handler, WifiSsid ssid, String nai,
            Callbacks callbacks) throws IOException {
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mCallbacks = callbacks;
        mNetworkId = connect(ssid, nai);

        // TODO(zqiu): setup alarm to timed out the connection attempt.

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    handleNetworkStateChanged(
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO),
                            intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO));
                }
            }
        };
        // Provide a Handler so that the onReceive call will be run on the specified handler
        // thread instead of the main thread.
        context.registerReceiver(receiver, filter, null, handler);
    }

    /**
     * Teardown the network connection by removing the network.
     */
    public void teardown() {
        mWifiManager.removeNetwork(mNetworkId);
    }

    /**
     * Connect to a OSU Wi-Fi network specified by the given SSID. The security type of the Wi-Fi
     * network is either open or OSEN (OSU Server-only authenticated layer 2 Encryption Network).
     * When network access identifier is provided, OSEN is used.
     *
     * @param ssid The SSID to connect to
     * @param nai Network access identifier of the network
     *
     * @return unique ID associated with the network
     * @throws IOException
     */
    private int connect(WifiSsid ssid, String nai) throws IOException {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid.toString() + "\"";
        if (TextUtils.isEmpty(nai)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            // TODO(zqiu): configuration setup for OSEN.
        }
        int networkId = mWifiManager.addNetwork(config);
        if (networkId < 0) {
            throw new IOException("Failed to add OSU network");
        }
        if (!mWifiManager.enableNetwork(networkId, true)) {
            throw new IOException("Failed to enable OSU network");
        }
        return networkId;
    }

    /**
     * Handle network state changed events.
     *
     * @param networkInfo {@link NetworkInfo} indicating the current network state
     * @param wifiInfo {@link WifiInfo} associated with the current network when connected
     */
    private void handleNetworkStateChanged(NetworkInfo networkInfo, WifiInfo wifiInfo) {
        if (networkInfo == null) {
            Log.e(TAG, "NetworkInfo not provided for network state changed event");
            return;
        }
        switch (networkInfo.getDetailedState()) {
            case CONNECTED:
                handleConnectedEvent(wifiInfo);
                break;
            case DISCONNECTED:
                handleDisconnectedEvent();
                break;
            default:
                Log.d(TAG, "Ignore uninterested state: " + networkInfo.getDetailedState());
                break;
        }
    }

    /**
     * Handle network connected event.
     *
     * @param wifiInfo {@link WifiInfo} associated with the current connection
     */
    private void handleConnectedEvent(WifiInfo wifiInfo) {
        if (mConnected) {
            // No-op if already connected.
            return;
        }
        if (wifiInfo == null) {
            Log.e(TAG, "WifiInfo not provided for connected event");
            return;
        }
        if (wifiInfo.getNetworkId() != mNetworkId) {
            return;
        }
        Network network = mWifiManager.getCurrentNetwork();
        if (network == null) {
            Log.e(TAG, "Current network is not set");
            return;
        }
        mConnected = true;
        mCallbacks.onConnected(network);
    }

    /**
     * Handle network disconnected event.
     */
    private void handleDisconnectedEvent() {
        if (!mConnected) {
            // No-op if not connected, most likely a disconnect event for a different network.
            return;
        }
        mConnected = false;
        mCallbacks.onDisconnected();
    }
}
