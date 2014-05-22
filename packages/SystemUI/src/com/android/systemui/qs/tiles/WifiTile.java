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

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;

/** Quick settings tile: Wifi **/
public class WifiTile extends QSTile<QSTile.SignalState> {
    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);

    private final NetworkController mController;

    public WifiTile(Host host) {
        super(host);
        mController = host.getNetworkController();
    }

    @Override
    public boolean supportsDualTargets() {
        return true;
    }

    @Override
    protected SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addNetworkSignalChangedCallback(mCallback);
        } else {
            mController.removeNetworkSignalChangedCallback(mCallback);
        }
    }

    @Override
    public QSTileView createTileView(Context context) {
        return new SignalTileView(context);
    }

    @Override
    protected void handleClick() {
        mController.setWifiEnabled(!mState.enabled);
    }

    @Override
    protected void handleSecondaryClick() {
        mHost.startSettingsActivity(WIFI_SETTINGS);
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        state.visible = true;
        if (DEBUG) Log.d(TAG, "handleUpdateState arg=" + arg);
        if (arg == null) return;
        CallbackInfo cb = (CallbackInfo) arg;

        boolean wifiConnected = cb.enabled && (cb.wifiSignalIconId > 0) && (cb.enabledDesc != null);
        boolean wifiNotConnected = (cb.wifiSignalIconId > 0) && (cb.enabledDesc == null);
        state.enabled = cb.enabled;
        state.connected = wifiConnected;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;
        final String signalContentDescription;
        final Resources r = mContext.getResources();
        if (wifiConnected) {
            state.iconId = cb.wifiSignalIconId;
            state.label = removeDoubleQuotes(cb.enabledDesc);
            signalContentDescription = cb.wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            state.iconId = R.drawable.ic_qs_wifi_0;
            state.label = r.getString(R.string.quick_settings_wifi_label);
            signalContentDescription = r.getString(R.string.accessibility_no_wifi);
        } else {
            state.iconId = R.drawable.ic_qs_wifi_no_network;
            state.label = r.getString(R.string.quick_settings_wifi_off_label);
            signalContentDescription = r.getString(R.string.accessibility_wifi_off);
        }
        state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_wifi,
                signalContentDescription,
                state.connected ? state.label : "");
    }

    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private static final class CallbackInfo {
        boolean enabled;
        int wifiSignalIconId;
        String enabledDesc;
        boolean activityIn;
        boolean activityOut;
        String wifiSignalContentDescription;

        @Override
        public String toString() {
            return new StringBuilder("CallbackInfo[")
                .append("enabled=").append(enabled)
                .append(",wifiSignalIconId=").append(wifiSignalIconId)
                .append(",enabledDesc=").append(enabledDesc)
                .append(",activityIn=").append(activityIn)
                .append(",activityOut=").append(activityOut)
                .append(",wifiSignalContentDescription=").append(wifiSignalContentDescription)
                .append(']').toString();
        }
    }

    private final NetworkSignalChangedCallback mCallback = new NetworkSignalChangedCallback() {
        @Override
        public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
                boolean activityIn, boolean activityOut,
                String wifiSignalContentDescriptionId, String description) {
            if (DEBUG) Log.d(TAG, "onWifiSignalChanged enabled=" + enabled);
            final CallbackInfo info = new CallbackInfo();
            info.enabled = enabled;
            info.wifiSignalIconId = wifiSignalIconId;
            info.enabledDesc = description;
            info.activityIn = activityIn;
            info.activityOut = activityOut;
            info.wifiSignalContentDescription = wifiSignalContentDescriptionId;
            refreshState(info);
        }

        @Override
        public void onMobileDataSignalChanged(boolean enabled,
                int mobileSignalIconId,
                String mobileSignalContentDescriptionId, int dataTypeIconId,
                boolean activityIn, boolean activityOut,
                String dataTypeContentDescriptionId, String description) {
            // noop
        }

        @Override
        public void onAirplaneModeChanged(boolean enabled) {
            // noop
        }
    };
}
