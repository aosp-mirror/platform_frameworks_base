/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.AlphaControlledSignalTileView;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.WifiIcons;
import com.android.systemui.statusbar.connectivity.WifiIndicators;

import javax.inject.Inject;

/** Quick settings tile: Wifi **/
public class WifiTile extends QSTileImpl<SignalState> {
    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);

    protected final NetworkController mController;
    private final AccessPointController mWifiController;
    private final QSTile.SignalState mStateBeforeClick = newTileState();

    protected final WifiSignalCallback mSignalCallback = new WifiSignalCallback();
    private boolean mExpectDisabled;

    @Inject
    public WifiTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            NetworkController networkController,
            AccessPointController accessPointController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = networkController;
        mWifiController = accessPointController;
        mController.observe(getLifecycle(), mSignalCallback);
        mStateBeforeClick.spec = "wifi";
    }

    @Override
    public SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public QSIconView createTileView(Context context) {
        return new AlphaControlledSignalTileView(context);
    }

    @Override
    public Intent getLongClickIntent() {
        return WIFI_SETTINGS;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        // Secondary clicks are header clicks, just toggle.
        mState.copyTo(mStateBeforeClick);
        boolean wifiEnabled = mState.value;
        // Immediately enter transient state when turning on wifi.
        refreshState(wifiEnabled ? null : ARG_SHOW_TRANSIENT_ENABLING);
        mController.setWifiEnabled(!wifiEnabled);
        mExpectDisabled = wifiEnabled;
        if (mExpectDisabled) {
            mHandler.postDelayed(() -> {
                if (mExpectDisabled) {
                    mExpectDisabled = false;
                    refreshState();
                }
            }, QSIconViewImpl.QS_ANIM_LENGTH);
        }
    }

    @Override
    protected void handleSecondaryClick(@Nullable View view) {
        if (!mWifiController.canConfigWifi()) {
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Settings.ACTION_WIFI_SETTINGS), 0);
            return;
        }
        if (!mState.value) {
            mController.setWifiEnabled(true);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_wifi_label);
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        if (DEBUG) Log.d(TAG, "handleUpdateState arg=" + arg);
        final CallbackInfo cb = mSignalCallback.mInfo;
        if (mExpectDisabled) {
            if (cb.enabled) {
                return; // Ignore updates until disabled event occurs.
            } else {
                mExpectDisabled = false;
            }
        }
        boolean transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING;
        boolean wifiConnected = cb.enabled && (cb.wifiSignalIconId > 0)
                && (cb.ssid != null || cb.wifiSignalIconId != WifiIcons.QS_WIFI_NO_NETWORK);
        boolean wifiNotConnected = (cb.ssid == null)
                && (cb.wifiSignalIconId == WifiIcons.QS_WIFI_NO_NETWORK);
        if (state.slash == null) {
            state.slash = new SlashState();
            state.slash.rotation = 6;
        }
        state.slash.isSlashed = false;
        boolean isTransient = transientEnabling || cb.isTransient;
        state.secondaryLabel = getSecondaryLabel(isTransient, cb.statusLabel);
        state.state = Tile.STATE_ACTIVE;
        state.dualTarget = true;
        state.value = transientEnabling || cb.enabled;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;
        final StringBuffer minimalContentDescription = new StringBuffer();
        final StringBuffer minimalStateDescription = new StringBuffer();
        final Resources r = mContext.getResources();
        if (isTransient) {
            state.icon = ResourceIcon.get(
                    com.android.internal.R.drawable.ic_signal_wifi_transient_animation);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else if (!state.value) {
            state.slash.isSlashed = true;
            state.state = Tile.STATE_INACTIVE;
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_DISABLED);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else if (wifiConnected) {
            state.icon = ResourceIcon.get(cb.wifiSignalIconId);
            state.label = cb.ssid != null ? removeDoubleQuotes(cb.ssid) : getTileLabel();
        } else if (wifiNotConnected) {
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_NO_NETWORK);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else {
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_NO_NETWORK);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        }
        minimalContentDescription.append(
                mContext.getString(R.string.quick_settings_wifi_label)).append(",");
        if (state.value) {
            if (wifiConnected) {
                minimalStateDescription.append(cb.wifiSignalContentDescription);
                minimalContentDescription.append(removeDoubleQuotes(cb.ssid));
                if (!TextUtils.isEmpty(state.secondaryLabel)) {
                    minimalContentDescription.append(",").append(state.secondaryLabel);
                }
            }
        }
        state.stateDescription = minimalStateDescription.toString();
        state.contentDescription = minimalContentDescription.toString();
        state.dualLabelContentDescription = r.getString(
                R.string.accessibility_quick_settings_open_settings, getTileLabel());
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    private CharSequence getSecondaryLabel(boolean isTransient, String statusLabel) {
        return isTransient
                ? mContext.getString(R.string.quick_settings_wifi_secondary_label_transient)
                : statusLabel;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_WIFI;
    }

    @Override
    public boolean isAvailable() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
    }

    @Nullable
    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    protected static final class CallbackInfo {
        boolean enabled;
        boolean connected;
        int wifiSignalIconId;
        @Nullable
        String ssid;
        boolean activityIn;
        boolean activityOut;
        @Nullable
        String wifiSignalContentDescription;
        boolean isTransient;
        @Nullable
        public String statusLabel;

        @Override
        public String toString() {
            return new StringBuilder("CallbackInfo[")
                    .append("enabled=").append(enabled)
                    .append(",connected=").append(connected)
                    .append(",wifiSignalIconId=").append(wifiSignalIconId)
                    .append(",ssid=").append(ssid)
                    .append(",activityIn=").append(activityIn)
                    .append(",activityOut=").append(activityOut)
                    .append(",wifiSignalContentDescription=").append(wifiSignalContentDescription)
                    .append(",isTransient=").append(isTransient)
                    .append(']').toString();
        }
    }

    protected final class WifiSignalCallback implements SignalCallback {
        final CallbackInfo mInfo = new CallbackInfo();

        @Override
        public void setWifiIndicators(@NonNull WifiIndicators indicators) {
            if (DEBUG) Log.d(TAG, "onWifiSignalChanged enabled=" + indicators.enabled);
            if (indicators.qsIcon == null) {
                return;
            }
            mInfo.enabled = indicators.enabled;
            mInfo.connected = indicators.qsIcon.visible;
            mInfo.wifiSignalIconId = indicators.qsIcon.icon;
            mInfo.ssid = indicators.description;
            mInfo.activityIn = indicators.activityIn;
            mInfo.activityOut = indicators.activityOut;
            mInfo.wifiSignalContentDescription = indicators.qsIcon.contentDescription;
            mInfo.isTransient = indicators.isTransient;
            mInfo.statusLabel = indicators.statusLabel;
            refreshState();
        }
    }
}
