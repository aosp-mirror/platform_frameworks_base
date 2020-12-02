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

import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT;
import static android.net.wifi.WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.wifi.WifiStatusTracker;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import java.util.Objects;

public class WifiSignalController extends
        SignalController<WifiSignalController.WifiState, SignalController.IconGroup> {
    private final boolean mHasMobileDataFeature;
    private final WifiStatusTracker mWifiTracker;

    public WifiSignalController(Context context, boolean hasMobileDataFeature,
            CallbackHandler callbackHandler, NetworkControllerImpl networkController,
            WifiManager wifiManager, ConnectivityManager connectivityManager,
            NetworkScoreManager networkScoreManager) {
        super("WifiSignalController", context, NetworkCapabilities.TRANSPORT_WIFI,
                callbackHandler, networkController);
        mWifiTracker = new WifiStatusTracker(mContext, wifiManager, networkScoreManager,
                connectivityManager, this::handleStatusUpdated);
        mWifiTracker.setListening(true);
        mHasMobileDataFeature = hasMobileDataFeature;
        if (wifiManager != null) {
            wifiManager.registerTrafficStateCallback(context.getMainExecutor(),
                    new WifiTrafficStateCallback());
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

    void refreshLocale() {
        mWifiTracker.refreshLocale();
    }

    @Override
    public void notifyListeners(SignalCallback callback) {
        // only show wifi in the cluster if connected or if wifi-only
        boolean visibleWhenEnabled = mContext.getResources().getBoolean(
                R.bool.config_showWifiIndicatorWhenEnabled);
        boolean wifiVisible = mCurrentState.enabled && (
                (mCurrentState.connected && mCurrentState.inetCondition == 1)
                        || !mHasMobileDataFeature || mCurrentState.isDefault
                        || visibleWhenEnabled);
        String wifiDesc = mCurrentState.connected ? mCurrentState.ssid : null;
        boolean ssidPresent = wifiVisible && mCurrentState.ssid != null;
        String contentDescription = getTextIfExists(getContentDescription()).toString();
        if (mCurrentState.inetCondition == 0) {
            contentDescription += ("," + mContext.getString(R.string.data_connection_no_internet));
        }
        IconState statusIcon = new IconState(wifiVisible, getCurrentIconId(), contentDescription);
        IconState qsIcon = new IconState(mCurrentState.connected,
                mWifiTracker.isCaptivePortal ? R.drawable.ic_qs_wifi_disconnected
                        : getQsCurrentIconId(), contentDescription);
        callback.setWifiIndicators(mCurrentState.enabled, statusIcon, qsIcon,
                ssidPresent && mCurrentState.activityIn, ssidPresent && mCurrentState.activityOut,
                wifiDesc, mCurrentState.isTransient, mCurrentState.statusLabel);
    }

    /**
     * Fetches wifi initial state replacing the initial sticky broadcast.
     */
    public void fetchInitialState() {
        mWifiTracker.fetchInitialState();
        mCurrentState.enabled = mWifiTracker.enabled;
        mCurrentState.isDefault = mWifiTracker.isDefaultNetwork;
        mCurrentState.connected = mWifiTracker.connected;
        mCurrentState.ssid = mWifiTracker.ssid;
        mCurrentState.rssi = mWifiTracker.rssi;
        mCurrentState.level = mWifiTracker.level;
        mCurrentState.statusLabel = mWifiTracker.statusLabel;
        notifyListenersIfNecessary();
    }

    /**
     * Extract wifi state directly from broadcasts about changes in wifi state.
     */
    public void handleBroadcast(Intent intent) {
        mWifiTracker.handleBroadcast(intent);
        mCurrentState.enabled = mWifiTracker.enabled;
        mCurrentState.isDefault = mWifiTracker.isDefaultNetwork;
        mCurrentState.connected = mWifiTracker.connected;
        mCurrentState.ssid = mWifiTracker.ssid;
        mCurrentState.rssi = mWifiTracker.rssi;
        mCurrentState.level = mWifiTracker.level;
        mCurrentState.statusLabel = mWifiTracker.statusLabel;
        notifyListenersIfNecessary();
    }

    private void handleStatusUpdated() {
        mCurrentState.statusLabel = mWifiTracker.statusLabel;
        mCurrentState.isDefault = mWifiTracker.isDefaultNetwork;
        notifyListenersIfNecessary();
    }

    @VisibleForTesting
    void setActivity(int wifiActivity) {
        mCurrentState.activityIn = wifiActivity == DATA_ACTIVITY_INOUT
                || wifiActivity == DATA_ACTIVITY_IN;
        mCurrentState.activityOut = wifiActivity == DATA_ACTIVITY_INOUT
                || wifiActivity == DATA_ACTIVITY_OUT;
        notifyListenersIfNecessary();
    }

    /**
     * Handler to receive the data activity on wifi.
     */
    private class WifiTrafficStateCallback implements WifiManager.TrafficStateCallback {
        @Override
        public void onStateChanged(int state) {
            setActivity(state);
        }
    }

    static class WifiState extends SignalController.State {
        String ssid;
        boolean isTransient;
        boolean isDefault;
        String statusLabel;

        @Override
        public void copyFrom(State s) {
            super.copyFrom(s);
            WifiState state = (WifiState) s;
            ssid = state.ssid;
            isTransient = state.isTransient;
            isDefault = state.isDefault;
            statusLabel = state.statusLabel;
        }

        @Override
        protected void toString(StringBuilder builder) {
            super.toString(builder);
            builder.append(",ssid=").append(ssid)
                .append(",isTransient=").append(isTransient)
                .append(",isDefault=").append(isDefault)
                .append(",statusLabel=").append(statusLabel);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }
            WifiState other = (WifiState) o;
            return Objects.equals(other.ssid, ssid)
                    && other.isTransient == isTransient
                    && other.isDefault == isDefault
                    && TextUtils.equals(other.statusLabel, statusLabel);
        }
    }
}
