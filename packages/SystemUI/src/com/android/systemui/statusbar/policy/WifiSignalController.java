/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.content.Intent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.systemui.statusbar.policy.NetworkController.IconState;

import java.util.List;
import java.util.Objects;


public class WifiSignalController extends
        SignalController<WifiSignalController.WifiState, SignalController.IconGroup> {
    private final WifiManager mWifiManager;
    private final AsyncChannel mWifiChannel;
    private final boolean mHasMobileData;

    public WifiSignalController(Context context, boolean hasMobileData,
            CallbackHandler callbackHandler, NetworkControllerImpl networkController) {
        super("WifiSignalController", context, NetworkCapabilities.TRANSPORT_WIFI,
                callbackHandler, networkController);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mHasMobileData = hasMobileData;
        Handler handler = new WifiHandler();
        mWifiChannel = new AsyncChannel();
        Messenger wifiMessenger = mWifiManager.getWifiServiceMessenger();
        if (wifiMessenger != null) {
            mWifiChannel.connect(context, handler, wifiMessenger);
        }
        // WiFi only has one state.
        mCurrentState.iconGroup = mLastState.iconGroup = new IconGroup(
                "Wi-Fi Icons",
                WifiIcons.WIFI_SIGNAL_STRENGTH,
                WifiIcons.QS_WIFI_SIGNAL_STRENGTH,
                AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH,
                WifiIcons.WIFI_NO_NETWORK,
                WifiIcons.QS_WIFI_NO_NETWORK,
                WifiIcons.WIFI_NO_NETWORK,
                WifiIcons.QS_WIFI_NO_NETWORK,
                AccessibilityContentDescriptions.WIFI_NO_CONNECTION
                );
    }

    @Override
    protected WifiState cleanState() {
        return new WifiState();
    }

    @Override
    public void notifyListeners() {
        // only show wifi in the cluster if connected or if wifi-only
        boolean wifiVisible = mCurrentState.enabled
                && (mCurrentState.connected || !mHasMobileData);
        String wifiDesc = wifiVisible ? mCurrentState.ssid : null;
        boolean ssidPresent = wifiVisible && mCurrentState.ssid != null;
        String contentDescription = getStringIfExists(getContentDescription());

        IconState statusIcon = new IconState(wifiVisible, getCurrentIconId(), contentDescription);
        IconState qsIcon = new IconState(mCurrentState.connected, getQsCurrentIconId(),
                contentDescription);
        mCallbackHandler.setWifiIndicators(mCurrentState.enabled, statusIcon, qsIcon,
                ssidPresent && mCurrentState.activityIn, ssidPresent && mCurrentState.activityOut,
                wifiDesc);
    }

    /**
     * Extract wifi state directly from broadcasts about changes in wifi state.
     */
    public void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            mCurrentState.enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            final NetworkInfo networkInfo = (NetworkInfo)
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            mCurrentState.connected = networkInfo != null && networkInfo.isConnected();
            // If Connected grab the signal strength and ssid.
            if (mCurrentState.connected) {
                // try getting it out of the intent first
                WifiInfo info = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO) != null
                        ? (WifiInfo) intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO)
                        : mWifiManager.getConnectionInfo();
                if (info != null) {
                    mCurrentState.ssid = getSsid(info);
                } else {
                    mCurrentState.ssid = null;
                }
            } else if (!mCurrentState.connected) {
                mCurrentState.ssid = null;
            }
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            // Default to -200 as its below WifiManager.MIN_RSSI.
            mCurrentState.rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            mCurrentState.level = WifiManager.calculateSignalLevel(
                    mCurrentState.rssi, WifiIcons.WIFI_LEVEL_COUNT);
        }

        notifyListenersIfNecessary();
    }

    private String getSsid(WifiInfo info) {
        String ssid = info.getSSID();
        if (ssid != null) {
            return ssid;
        }
        // OK, it's not in the connectionInfo; we have to go hunting for it
        List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        int length = networks.size();
        for (int i = 0; i < length; i++) {
            if (networks.get(i).networkId == info.getNetworkId()) {
                return networks.get(i).SSID;
            }
        }
        return null;
    }

    @VisibleForTesting
    void setActivity(int wifiActivity) {
        mCurrentState.activityIn = wifiActivity == WifiManager.DATA_ACTIVITY_INOUT
                || wifiActivity == WifiManager.DATA_ACTIVITY_IN;
        mCurrentState.activityOut = wifiActivity == WifiManager.DATA_ACTIVITY_INOUT
                || wifiActivity == WifiManager.DATA_ACTIVITY_OUT;
        notifyListenersIfNecessary();
    }

    /**
     * Handler to receive the data activity on wifi.
     */
    private class WifiHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiChannel.sendMessage(Message.obtain(this,
                                AsyncChannel.CMD_CHANNEL_FULL_CONNECTION));
                    } else {
                        Log.e(mTag, "Failed to connect to wifi");
                    }
                    break;
                case WifiManager.DATA_ACTIVITY_NOTIFICATION:
                    setActivity(msg.arg1);
                    break;
                default:
                    // Ignore
                    break;
            }
        }
    }

    static class WifiState extends SignalController.State {
        String ssid;

        @Override
        public void copyFrom(State s) {
            super.copyFrom(s);
            WifiState state = (WifiState) s;
            ssid = state.ssid;
        }

        @Override
        protected void toString(StringBuilder builder) {
            super.toString(builder);
            builder.append(',').append("ssid=").append(ssid);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o)
                    && Objects.equals(((WifiState) o).ssid, ssid);
        }
    }
}
