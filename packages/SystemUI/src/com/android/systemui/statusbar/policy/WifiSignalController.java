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
import android.text.Html;
import android.text.TextUtils;
import android.util.FeatureFlagUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.SignalIcon.IconGroup;
import com.android.settingslib.SignalIcon.MobileIconGroup;
import com.android.settingslib.SignalIcon.State;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.wifi.WifiStatusTracker;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.MobileDataIndicators;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NetworkController.WifiIndicators;

import java.io.PrintWriter;
import java.util.Objects;

public class WifiSignalController extends
        SignalController<WifiSignalController.WifiState, IconGroup> {
    private final boolean mHasMobileDataFeature;
    private final WifiStatusTracker mWifiTracker;
    private final IconGroup mUnmergedWifiIconGroup = WifiIcons.UNMERGED_WIFI;
    private final MobileIconGroup mCarrierMergedWifiIconGroup = TelephonyIcons.CARRIER_MERGED_WIFI;
    private final WifiManager mWifiManager;
    private final boolean mProviderModel;

    public WifiSignalController(Context context, boolean hasMobileDataFeature,
            CallbackHandler callbackHandler, NetworkControllerImpl networkController,
            WifiManager wifiManager, ConnectivityManager connectivityManager,
            NetworkScoreManager networkScoreManager) {
        super("WifiSignalController", context, NetworkCapabilities.TRANSPORT_WIFI,
                callbackHandler, networkController);
        mWifiManager = wifiManager;
        mWifiTracker = new WifiStatusTracker(mContext, wifiManager, networkScoreManager,
                connectivityManager, this::handleStatusUpdated);
        mWifiTracker.setListening(true);
        mHasMobileDataFeature = hasMobileDataFeature;
        if (wifiManager != null) {
            wifiManager.registerTrafficStateCallback(context.getMainExecutor(),
                    new WifiTrafficStateCallback());
        }
        mCurrentState.iconGroup = mLastState.iconGroup = mUnmergedWifiIconGroup;
        mProviderModel = FeatureFlagUtils.isEnabled(
                mContext, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
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
        if (mCurrentState.isCarrierMerged) {
            notifyListenersForCarrierWifi(callback);
        } else {
            notifyListenersForNonCarrierWifi(callback);
        }
    }

    private void notifyListenersForNonCarrierWifi(SignalCallback callback) {
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
        if (mProviderModel) {
            // WiFi icon will only be shown in the statusbar in 2 scenarios
            // 1. WiFi is the default network, and it is validated
            // 2. WiFi is the default network, it is not validated and there is no other
            // non-Carrier WiFi networks available.
            boolean maybeShowIcons = (mCurrentState.inetCondition == 1)
                    || (mCurrentState.inetCondition == 0
                            && !mNetworkController.isNonCarrierWifiNetworkAvailable());
            IconState statusIcon = new IconState(
                    wifiVisible && maybeShowIcons, getCurrentIconId(), contentDescription);
            IconState qsIcon = null;
            if ((mCurrentState.isDefault && maybeShowIcons) || (!mNetworkController.isRadioOn()
                    && !mNetworkController.isEthernetDefault())) {
                qsIcon = new IconState(mCurrentState.connected,
                        mWifiTracker.isCaptivePortal ? R.drawable.ic_qs_wifi_disconnected
                                : getQsCurrentIconId(), contentDescription);
            }
            WifiIndicators wifiIndicators = new WifiIndicators(
                    mCurrentState.enabled, statusIcon, qsIcon,
                    ssidPresent && mCurrentState.activityIn,
                    ssidPresent && mCurrentState.activityOut,
                    wifiDesc, mCurrentState.isTransient, mCurrentState.statusLabel
            );
            callback.setWifiIndicators(wifiIndicators);
        } else {
            IconState statusIcon = new IconState(
                    wifiVisible, getCurrentIconId(), contentDescription);
            IconState qsIcon = new IconState(mCurrentState.connected,
                    mWifiTracker.isCaptivePortal ? R.drawable.ic_qs_wifi_disconnected
                            : getQsCurrentIconId(), contentDescription);
            WifiIndicators wifiIndicators = new WifiIndicators(
                    mCurrentState.enabled, statusIcon, qsIcon,
                    ssidPresent && mCurrentState.activityIn,
                    ssidPresent && mCurrentState.activityOut,
                    wifiDesc, mCurrentState.isTransient, mCurrentState.statusLabel
            );
            callback.setWifiIndicators(wifiIndicators);
        }
    }

    private void notifyListenersForCarrierWifi(SignalCallback callback) {
        MobileIconGroup icons = mCarrierMergedWifiIconGroup;
        String contentDescription = getTextIfExists(getContentDescription()).toString();
        CharSequence dataContentDescriptionHtml = getTextIfExists(icons.dataContentDescription);

        CharSequence dataContentDescription = Html.fromHtml(
                dataContentDescriptionHtml.toString(), 0).toString();
        if (mCurrentState.inetCondition == 0) {
            dataContentDescription = mContext.getString(R.string.data_connection_no_internet);
        }
        // Mobile icon will only be shown in the statusbar in 2 scenarios
        // 1. Mobile is the default network, and it is validated
        // 2. Mobile is the default network, it is not validated and there is no other
        // non-Carrier WiFi networks available.
        boolean maybeShowIcons = (mCurrentState.inetCondition == 1)
                || (mCurrentState.inetCondition == 0
                        && !mNetworkController.isNonCarrierWifiNetworkAvailable());
        boolean sbVisible = mCurrentState.enabled && mCurrentState.connected
                && maybeShowIcons && mCurrentState.isDefault;
        IconState statusIcon =
                new IconState(sbVisible, getCurrentIconIdForCarrierWifi(), contentDescription);
        int typeIcon = sbVisible ? icons.dataType : 0;
        int qsTypeIcon = 0;
        IconState qsIcon = null;
        if (sbVisible) {
            qsTypeIcon = icons.qsDataType;
            qsIcon = new IconState(mCurrentState.connected, getQsCurrentIconIdForCarrierWifi(),
                    contentDescription);
        }
        CharSequence description =
                mNetworkController.getNetworkNameForCarrierWiFi(mCurrentState.subId);
        MobileDataIndicators mobileDataIndicators = new MobileDataIndicators(
                statusIcon, qsIcon, typeIcon, qsTypeIcon,
                mCurrentState.activityIn, mCurrentState.activityOut, dataContentDescription,
                dataContentDescriptionHtml, description, icons.isWide,
                mCurrentState.subId, /* roaming= */ false, /* showTriangle= */ true
        );
        callback.setMobileDataIndicators(mobileDataIndicators);
    }

    private int getCurrentIconIdForCarrierWifi() {
        int level = mCurrentState.level;
        // The WiFi signal level returned by WifiManager#calculateSignalLevel start from 0, so
        // WifiManager#getMaxSignalLevel + 1 represents the total level buckets count.
        int totalLevel = mWifiManager.getMaxSignalLevel() + 1;
        boolean noInternet = mCurrentState.inetCondition == 0;
        if (mCurrentState.connected) {
            return SignalDrawable.getState(level, totalLevel, noInternet);
        } else if (mCurrentState.enabled) {
            return SignalDrawable.getEmptyState(totalLevel);
        } else {
            return 0;
        }
    }

    private int getQsCurrentIconIdForCarrierWifi() {
        return getCurrentIconIdForCarrierWifi();
    }

    /**
     * Fetches wifi initial state replacing the initial sticky broadcast.
     */
    public void fetchInitialState() {
        mWifiTracker.fetchInitialState();
        copyWifiStates();
        notifyListenersIfNecessary();
    }

    /**
     * Extract wifi state directly from broadcasts about changes in wifi state.
     */
    public void handleBroadcast(Intent intent) {
        mWifiTracker.handleBroadcast(intent);
        copyWifiStates();
        notifyListenersIfNecessary();
    }

    private void handleStatusUpdated() {
        copyWifiStates();
        notifyListenersIfNecessary();
    }

    private void copyWifiStates() {
        mCurrentState.enabled = mWifiTracker.enabled;
        mCurrentState.isDefault = mWifiTracker.isDefaultNetwork;
        mCurrentState.connected = mWifiTracker.connected;
        mCurrentState.ssid = mWifiTracker.ssid;
        mCurrentState.rssi = mWifiTracker.rssi;
        notifyWifiLevelChangeIfNecessary(mWifiTracker.level);
        mCurrentState.level = mWifiTracker.level;
        mCurrentState.statusLabel = mWifiTracker.statusLabel;
        mCurrentState.isCarrierMerged = mWifiTracker.isCarrierMerged;
        mCurrentState.subId = mWifiTracker.subId;
        mCurrentState.iconGroup =
                mCurrentState.isCarrierMerged ? mCarrierMergedWifiIconGroup
                        : mUnmergedWifiIconGroup;
    }

    void notifyWifiLevelChangeIfNecessary(int level) {
        if (level != mCurrentState.level) {
            mNetworkController.notifyWifiLevelChange(level);
        }
    }

    boolean isCarrierMergedWifi(int subId) {
        return mCurrentState.isDefault
                && mCurrentState.isCarrierMerged && (mCurrentState.subId == subId);
    }

    @VisibleForTesting
    void setActivity(int wifiActivity) {
        mCurrentState.activityIn = wifiActivity == DATA_ACTIVITY_INOUT
                || wifiActivity == DATA_ACTIVITY_IN;
        mCurrentState.activityOut = wifiActivity == DATA_ACTIVITY_INOUT
                || wifiActivity == DATA_ACTIVITY_OUT;
        notifyListenersIfNecessary();
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        mWifiTracker.dump(pw);
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

    static class WifiState extends State {
        public String ssid;
        public boolean isTransient;
        public boolean isDefault;
        public String statusLabel;
        public boolean isCarrierMerged;
        public int subId;

        @Override
        public void copyFrom(State s) {
            super.copyFrom(s);
            WifiState state = (WifiState) s;
            ssid = state.ssid;
            isTransient = state.isTransient;
            isDefault = state.isDefault;
            statusLabel = state.statusLabel;
            isCarrierMerged = state.isCarrierMerged;
            subId = state.subId;
        }

        @Override
        protected void toString(StringBuilder builder) {
            super.toString(builder);
            builder.append(",ssid=").append(ssid)
                .append(",isTransient=").append(isTransient)
                .append(",isDefault=").append(isDefault)
                .append(",statusLabel=").append(statusLabel)
                .append(",isCarrierMerged=").append(isCarrierMerged)
                .append(",subId=").append(subId);
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
                    && TextUtils.equals(other.statusLabel, statusLabel)
                    && other.isCarrierMerged == isCarrierMerged
                    && other.subId == subId;
        }
    }
}
