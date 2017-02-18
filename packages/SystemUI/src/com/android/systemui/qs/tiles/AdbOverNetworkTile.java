/*
 * Copyright (C) 2015 The CyanogenMod Project
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
import android.database.ContentObserver;
import android.net.NetworkUtils;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import java.net.InetAddress;
import javax.inject.Inject;

public class AdbOverNetworkTile extends QSTileImpl<BooleanState> {

    private boolean mActive = false;
    private boolean mListening;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_network_adb);

    @Inject
    public AdbOverNetworkTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (!isAdbEnabled()) {
           Toast.makeText(mContext, mContext.getString(
                    R.string.quick_settings_network_adb_toast), Toast.LENGTH_LONG).show();
        } else {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.ADB_PORT, isAdbNetworkEnabled() ? 0 : 5555);
        }
        refreshState();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_network_adb_label);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        mActive = isAdbEnabled();
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.icon = mIcon;
        state.slash.isSlashed = !mActive;

        if (!mActive) {
            state.label = mContext.getString(R.string.quick_settings_network_adb_label);
            state.state = Tile.STATE_INACTIVE;
            return;
        }
        mActive = isAdbNetworkEnabled();
        state.slash.isSlashed = !mActive;

        if (mActive) {
            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();

            if (wifiInfo != null) {
                // if wifiInfo is not null, set the label to "hostAddress"
                InetAddress address = NetworkUtils.intToInetAddress(wifiInfo.getIpAddress());
                state.label = address.getHostAddress();
            } else {
                // if wifiInfo is null, set the label without host address
                state.label = mContext.getString(R.string.quick_settings_network_adb_label);
            }
            state.value = true;
            state.state = Tile.STATE_ACTIVE;
        } else {
            // Otherwise set the disabled label and icon
            state.label = mContext.getString(R.string.quick_settings_network_adb_label);
            state.value = false;
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.MOONSHINE;
    }

    private boolean isAdbEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) > 0;
    }

    private boolean isAdbNetworkEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ADB_PORT, 0) > 0;
    }

    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    @Override
    public void handleSetListening(boolean listening) {
        if (mObserver == null) {
            return;
        }
        if (mListening != listening) {
            mListening = listening;
            if (listening) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ADB_PORT),
                        false, mObserver);
                mContext.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                        false, mObserver);
            } else {
                mContext.getContentResolver().unregisterContentObserver(mObserver);
            }
        }
    }
}
