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
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.settingslib.wifi.WifiStatusTracker;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import com.android.systemui.R;

import java.util.Objects;


public class WifiSignalController extends
        SignalController<WifiSignalController.WifiState, SignalController.IconGroup> {
    private final WifiManager mWifiManager;
    private final AsyncChannel mWifiChannel;
    private final boolean mHasMobileData;
    private final WifiStatusTracker mWifiTracker;

    public WifiSignalController(Context context, boolean hasMobileData,
            CallbackHandler callbackHandler, NetworkControllerImpl networkController) {
        super("WifiSignalController", context, NetworkCapabilities.TRANSPORT_WIFI,
                callbackHandler, networkController);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mWifiTracker = new WifiStatusTracker(mWifiManager);
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
    public void notifyListeners(SignalCallback callback) {
        // only show wifi in the cluster if connected or if wifi-only
        boolean wifiVisible = mCurrentState.enabled
                && (mCurrentState.connected || !mHasMobileData);
        String wifiDesc = wifiVisible ? mCurrentState.ssid : null;
        boolean ssidPresent = wifiVisible && mCurrentState.ssid != null;
        String contentDescription = getStringIfExists(getContentDescription());
        if (mCurrentState.inetCondition == 0) {
            contentDescription +=
                    ("," + mContext.getString(R.string.accessibility_quick_settings_no_internet));
        }

        IconState statusIcon = new IconState(wifiVisible, getCurrentIconId(), contentDescription);
        IconState qsIcon = new IconState(mCurrentState.connected, getQsCurrentIconId(),
                contentDescription);
        callback.setWifiIndicators(mCurrentState.enabled, statusIcon, qsIcon,
                ssidPresent && mCurrentState.activityIn, ssidPresent && mCurrentState.activityOut,
                wifiDesc);
    }

    /**
     * Extract wifi state directly from broadcasts about changes in wifi state.
     */
    public void handleBroadcast(Intent intent) {
        mWifiTracker.handleBroadcast(intent);
        mCurrentState.enabled = mWifiTracker.enabled;
        mCurrentState.connected = mWifiTracker.connected;
        mCurrentState.ssid = mWifiTracker.ssid;
        mCurrentState.rssi = mWifiTracker.rssi;
        mCurrentState.level = mWifiTracker.level;
        notifyListenersIfNecessary();
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
