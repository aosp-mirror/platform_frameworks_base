/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017-2019 The LineageOS Project
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
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.UnlockMethodCache;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import lineageos.providers.LineageSettings;
import org.lineageos.internal.logging.LineageMetricsLogger;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

import javax.inject.Inject;

/** Quick settings tile: AdbOverNetwork **/
public class AdbOverNetworkTile extends QSTileImpl<BooleanState> {

    private boolean mListening;
    private final KeyguardMonitor mKeyguardMonitor;
    private final KeyguardMonitorCallback mKeyguardCallback = new KeyguardMonitorCallback();
    private final UnlockMethodCache mUnlockMethodCache;

    private final ConnectivityManager mConnectivityManager;

    private String mNetworkAddress;

    private static final Intent SETTINGS_DEVELOPMENT =
            new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);

    @Inject
    public AdbOverNetworkTile(QSHost host) {
        super(host);
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mUnlockMethodCache = UnlockMethodCache.getInstance(mHost.getContext());
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (mKeyguardMonitor.isSecure() && !mUnlockMethodCache.canSkipBouncer()) {
            Dependency.get(ActivityStarter.class)
                    .postQSRunnableDismissingKeyguard(this::toggleAction);
        } else {
            toggleAction();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return SETTINGS_DEVELOPMENT;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = isAdbEnabled() && isAdbNetworkEnabled();
        state.icon = ResourceIcon.get(R.drawable.ic_qs_network_adb);
        state.label = mContext.getString(R.string.quick_settings_network_adb_label);
        if (state.value) {
            state.secondaryLabel = mNetworkAddress != null ? mNetworkAddress
                    : mContext.getString(R.string.quick_settings_network_adb_no_network);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.secondaryLabel = null;
            state.state = canEnableAdbNetwork() ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_network_adb_label);
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_ADB_OVER_NETWORK;
    }

    private boolean isAdbEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) > 0;
    }

    private boolean isAdbNetworkEnabled() {
        return LineageSettings.Secure.getInt(mContext.getContentResolver(),
                LineageSettings.Secure.ADB_PORT, 0) > 0;
    }

    private boolean canEnableAdbNetwork() {
        return isAdbEnabled() && isNetworkAvailable();
    }

    private boolean isNetworkAvailable() {
        return mNetworkAddress != null;
    }

    private void toggleAction() {
        final boolean active = getState().value;
        // Always allow toggle off if currently on.
        if (!active && !canEnableAdbNetwork()) {
            return;
        }
        LineageSettings.Secure.putIntForUser(mContext.getContentResolver(),
                LineageSettings.Secure.ADB_PORT, active ? -1 : 5555,
                UserHandle.USER_CURRENT);
    }

    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening != listening) {
            mListening = listening;
            if (listening) {
                mContext.getContentResolver().registerContentObserver(
                        LineageSettings.Secure.getUriFor(LineageSettings.Secure.ADB_PORT),
                        false, mObserver);
                mContext.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                        false, mObserver);
                mKeyguardMonitor.addCallback(mKeyguardCallback);
                mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback);
            } else {
                mContext.getContentResolver().unregisterContentObserver(mObserver);
                mKeyguardMonitor.removeCallback(mKeyguardCallback);
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            }
        }
    }

    private class KeyguardMonitorCallback implements KeyguardMonitor.Callback {
        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    }

    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            List<LinkAddress> linkAddresses =
                    mConnectivityManager.getLinkProperties(network).getLinkAddresses();
            // Determine local network address.
            // Use first IPv4 address if available, otherwise use first IPv6.
            String ipv4 = null, ipv6 = null;
            for (LinkAddress la : linkAddresses) {
                final InetAddress addr = la.getAddress();
                if (ipv4 == null && addr instanceof Inet4Address) {
                    ipv4 = addr.getHostAddress();
                    break;
                } else if (ipv6 == null && addr instanceof Inet6Address) {
                    ipv6 = addr.getHostAddress();
                }
            }
            mNetworkAddress = ipv4 != null ? ipv4 : ipv6;
            refreshState();
        }

        @Override
        public void onLost(Network network) {
            mNetworkAddress = null;
            refreshState();
        }
    };
}
