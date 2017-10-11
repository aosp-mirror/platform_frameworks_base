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
import android.database.ContentObserver;
import android.net.NetworkBadging;
import android.net.NetworkCapabilities;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.ScoredNetwork;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiNetworkScoreCache.CacheListener;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.settingslib.Utils;
import com.android.settingslib.wifi.WifiStatusTracker;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import com.android.systemui.R;

import java.util.Objects;
import java.util.List;


public class WifiSignalController extends
        SignalController<WifiSignalController.WifiState, SignalController.IconGroup> {

    private final WifiManager mWifiManager;
    private final AsyncChannel mWifiChannel;
    private final boolean mHasMobileData;
    private final NetworkScoreManager mNetworkScoreManager;
    private final WifiNetworkScoreCache mScoreCache;
    private final WifiStatusTracker mWifiTracker;

    private boolean mScoringUiEnabled = false;

    public WifiSignalController(Context context, boolean hasMobileData,
            CallbackHandler callbackHandler, NetworkControllerImpl networkController,
            NetworkScoreManager networkScoreManager) {
        super("WifiSignalController", context, NetworkCapabilities.TRANSPORT_WIFI,
                callbackHandler, networkController);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mWifiTracker = new WifiStatusTracker(mWifiManager);
        mHasMobileData = hasMobileData;
        Handler handler = new WifiHandler(Looper.getMainLooper());
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

        mScoreCache = new WifiNetworkScoreCache(context, new CacheListener(handler) {
            @Override
            public void networkCacheUpdated(List<ScoredNetwork> networks) {
                mCurrentState.badgeEnum = getWifiBadgeEnum();
                notifyListenersIfNecessary();
            }
        });

        // Setup scoring
        mNetworkScoreManager = networkScoreManager;
        configureScoringGating();
        registerScoreCache();
    }

    private void configureScoringGating() {
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                mScoringUiEnabled =
                        Settings.Global.getInt(
                                mContext.getContentResolver(),
                                Settings.Global.NETWORK_SCORING_UI_ENABLED, 0) == 1;
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.NETWORK_SCORING_UI_ENABLED),
                false /* notifyForDescendants */,
                observer);

        observer.onChange(false /* selfChange */); // Set the initial values
    }

    private void registerScoreCache() {
        Log.d(mTag, "Registered score cache");
        mNetworkScoreManager.registerNetworkScoreCache(
                NetworkKey.TYPE_WIFI,
                mScoreCache,
                NetworkScoreManager.CACHE_FILTER_CURRENT_NETWORK);
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

        IconState statusIcon = new IconState(wifiVisible, getCurrentIconId(),
                Utils.getWifiBadgeResource(mCurrentState.badgeEnum), contentDescription);
        IconState qsIcon = new IconState(
                mCurrentState.connected, getQsCurrentIconId(),
                Utils.getWifiBadgeResource(mCurrentState.badgeEnum), contentDescription);
        callback.setWifiIndicators(mCurrentState.enabled, statusIcon, qsIcon,
                ssidPresent && mCurrentState.activityIn, ssidPresent && mCurrentState.activityOut,
                wifiDesc, mCurrentState.isTransient);
    }

    @Override
    public int getCurrentIconId() {
        if (mCurrentState.badgeEnum != NetworkBadging.BADGING_NONE) {
            return Utils.WIFI_PIE_FOR_BADGING[mCurrentState.level];
        }
        return super.getCurrentIconId();
    }

    /**
     * Extract wifi state directly from broadcasts about changes in wifi state.
     */
    public void handleBroadcast(Intent intent) {
        // Update the WifiStatusTracker with the new information and update the score cache.
        NetworkKey previousNetworkKey = mWifiTracker.networkKey;
        mWifiTracker.handleBroadcast(intent);
        updateScoreCacheIfNecessary(previousNetworkKey);

        mCurrentState.isTransient = mWifiTracker.state == WifiManager.WIFI_STATE_ENABLING
                || mWifiTracker.state == WifiManager.WIFI_AP_STATE_DISABLING
                || mWifiTracker.connecting;
        mCurrentState.enabled = mWifiTracker.enabled;
        mCurrentState.connected = mWifiTracker.connected;
        mCurrentState.ssid = mWifiTracker.ssid;
        mCurrentState.rssi = mWifiTracker.rssi;
        mCurrentState.level = mWifiTracker.level;
        mCurrentState.badgeEnum = getWifiBadgeEnum();
        notifyListenersIfNecessary();
    }

    /**
     * Clears old scores out of the cache and requests new scores if the network key has changed.
     *
     * <p>New scores are requested asynchronously.
     */
    private void updateScoreCacheIfNecessary(NetworkKey previousNetworkKey) {
        if (mWifiTracker.networkKey == null) {
            return;
        }
        if ((previousNetworkKey == null) || !mWifiTracker.networkKey.equals(previousNetworkKey)) {
            mScoreCache.clearScores();
            mNetworkScoreManager.requestScores(new NetworkKey[]{mWifiTracker.networkKey});
        }
    }

    /**
     * Returns the wifi badge enum for the current {@link #mWifiTracker} state.
     *
     * <p>{@link #updateScoreCacheIfNecessary} should be called prior to this method.
     */
    private int getWifiBadgeEnum() {
        if (!mScoringUiEnabled || mWifiTracker.networkKey == null) {
            return NetworkBadging.BADGING_NONE;
        }
        ScoredNetwork score = mScoreCache.getScoredNetwork(mWifiTracker.networkKey);

        if (score != null) {
            return score.calculateBadge(mWifiTracker.rssi);
        }
        return NetworkBadging.BADGING_NONE;
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
        WifiHandler(Looper looper) {
            super(looper);
        }

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
        int badgeEnum;
        boolean isTransient;

        @Override
        public void copyFrom(State s) {
            super.copyFrom(s);
            WifiState state = (WifiState) s;
            ssid = state.ssid;
            badgeEnum = state.badgeEnum;
            isTransient = state.isTransient;
        }

        @Override
        protected void toString(StringBuilder builder) {
            super.toString(builder);
            builder.append(',').append("ssid=").append(ssid);
            builder.append(',').append("badgeEnum=").append(badgeEnum);
            builder.append(',').append("isTransient=").append(isTransient);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o)
                    && Objects.equals(((WifiState) o).ssid, ssid)
                    && (((WifiState) o).badgeEnum == badgeEnum)
                    && (((WifiState) o).isTransient == isTransient);
        }
    }
}
