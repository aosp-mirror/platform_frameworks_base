/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.net;

import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.VpnManager.NOTIFICATION_CHANNEL_VPN;
import static android.provider.Settings.ACTION_VPN_SETTINGS;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.server.connectivity.Vpn;

import java.util.List;
import java.util.Objects;

/**
 * State tracker for legacy lockdown VPN. Watches for physical networks to be
 * connected and kicks off VPN connection.
 */
public class LockdownVpnTracker {
    private static final String TAG = "LockdownVpnTracker";

    public static final String ACTION_LOCKDOWN_RESET = "com.android.server.action.LOCKDOWN_RESET";

    @NonNull private final Context mContext;
    @NonNull private final ConnectivityManager mCm;
    @NonNull private final NotificationManager mNotificationManager;
    @NonNull private final Handler mHandler;
    @NonNull private final Vpn mVpn;
    @NonNull private final VpnProfile mProfile;

    @NonNull private final Object mStateLock = new Object();

    @NonNull private final PendingIntent mConfigIntent;
    @NonNull private final PendingIntent mResetIntent;

    @NonNull private final NetworkCallback mDefaultNetworkCallback = new NetworkCallback();
    @NonNull private final VpnNetworkCallback mVpnNetworkCallback = new VpnNetworkCallback();

    private class NetworkCallback extends ConnectivityManager.NetworkCallback {
        private Network mNetwork = null;
        private LinkProperties mLinkProperties = null;

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
            boolean networkChanged = false;
            if (!network.equals(mNetwork)) {
                // The default network just changed.
                mNetwork = network;
                networkChanged = true;
            }
            mLinkProperties = lp;
            // Backwards compatibility: previously, LockdownVpnTracker only responded to connects
            // and disconnects, not LinkProperties changes on existing networks.
            if (networkChanged) {
                synchronized (mStateLock) {
                    handleStateChangedLocked();
                }
            }
        }

        @Override
        public void onLost(Network network) {
            // The default network has gone down.
            mNetwork = null;
            mLinkProperties = null;
            synchronized (mStateLock) {
                handleStateChangedLocked();
            }
        }

        public Network getNetwork() {
            return mNetwork;
        }

        public LinkProperties getLinkProperties() {
            return mLinkProperties;
        }
    }

    private class VpnNetworkCallback extends NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            synchronized (mStateLock) {
                handleStateChangedLocked();
            }
        }
        @Override
        public void onLost(Network network) {
            onAvailable(network);
        }
    }

    @Nullable
    private String mAcceptedEgressIface;

    public LockdownVpnTracker(@NonNull Context context,
            @NonNull Handler handler,
            @NonNull Vpn vpn,
            @NonNull VpnProfile profile) {
        mContext = Objects.requireNonNull(context);
        mCm = mContext.getSystemService(ConnectivityManager.class);
        mHandler = Objects.requireNonNull(handler);
        mVpn = Objects.requireNonNull(vpn);
        mProfile = Objects.requireNonNull(profile);
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        final Intent configIntent = new Intent(ACTION_VPN_SETTINGS);
        mConfigIntent = PendingIntent.getActivity(mContext, 0 /* requestCode */, configIntent,
                PendingIntent.FLAG_IMMUTABLE);

        final Intent resetIntent = new Intent(ACTION_LOCKDOWN_RESET);
        resetIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mResetIntent = PendingIntent.getBroadcast(mContext, 0 /* requestCode */, resetIntent,
                PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Watch for state changes to both active egress network, kicking off a VPN
     * connection when ready, or setting firewall rules once VPN is connected.
     */
    private void handleStateChangedLocked() {
        final Network network = mDefaultNetworkCallback.getNetwork();
        final LinkProperties egressProp = mDefaultNetworkCallback.getLinkProperties();

        final NetworkInfo vpnInfo = mVpn.getNetworkInfo();
        final VpnConfig vpnConfig = mVpn.getLegacyVpnConfig();

        // Restart VPN when egress network disconnected or changed
        final boolean egressDisconnected = (network == null);
        final boolean egressChanged = egressProp == null
                || !TextUtils.equals(mAcceptedEgressIface, egressProp.getInterfaceName());

        final String egressIface = (egressProp == null) ?
                null : egressProp.getInterfaceName();
        Log.d(TAG, "handleStateChanged: egress=" + mAcceptedEgressIface + "->" + egressIface);

        if (egressDisconnected || egressChanged) {
            mAcceptedEgressIface = null;
            mVpn.stopVpnRunnerPrivileged();
        }
        if (egressDisconnected) {
            hideNotification();
            return;
        }

        // At this point, |network| is known to be non-null.
        if (!vpnInfo.isConnectedOrConnecting()) {
            if (!mProfile.isValidLockdownProfile()) {
                Log.e(TAG, "Invalid VPN profile; requires IP-based server and DNS");
                showNotification(R.string.vpn_lockdown_error, R.drawable.vpn_disconnected);
                return;
            }

            Log.d(TAG, "Active network connected; starting VPN");
            showNotification(R.string.vpn_lockdown_connecting, R.drawable.vpn_disconnected);

            mAcceptedEgressIface = egressIface;
            try {
                // Use the privileged method because Lockdown VPN is initiated by the system, so
                // no additional permission checks are necessary.
                //
                // Pass in the underlying network here because the legacy VPN is, in fact, tightly
                // coupled to a given underlying network and cannot provide mobility. This makes
                // things marginally more correct in two ways:
                //
                // 1. When the legacy lockdown VPN connects, LegacyTypeTracker broadcasts an extra
                //    CONNECTED broadcast for the underlying network type. The underlying type comes
                //    from here. LTT *could* assume that the underlying network is the default
                //    network, but that might introduce a race condition if, say, the VPN starts
                //    connecting on cell, but when the connection succeeds and the agent is
                //    registered, the default network is now wifi.
                // 2. If no underlying network is passed in, then CS will assume the underlying
                //    network is the system default. So, if the VPN  is up and underlying network
                //    (e.g., wifi) disconnects, CS will inform apps that the VPN's capabilities have
                //    changed to match the new default network (e.g., cell).
                mVpn.startLegacyVpnPrivileged(mProfile, network, egressProp);
            } catch (IllegalStateException e) {
                mAcceptedEgressIface = null;
                Log.e(TAG, "Failed to start VPN", e);
                showNotification(R.string.vpn_lockdown_error, R.drawable.vpn_disconnected);
            }
        } else if (vpnInfo.isConnected() && vpnConfig != null) {
            final String iface = vpnConfig.interfaze;
            final List<LinkAddress> sourceAddrs = vpnConfig.addresses;

            Log.d(TAG, "VPN connected using iface=" + iface
                    + ", sourceAddr=" + sourceAddrs.toString());
            showNotification(R.string.vpn_lockdown_connected, R.drawable.vpn_connected);
        }
    }

    public void init() {
        synchronized (mStateLock) {
            initLocked();
        }
    }

    private void initLocked() {
        Log.d(TAG, "initLocked()");

        mVpn.setEnableTeardown(false);
        mVpn.setLockdown(true);
        mCm.setLegacyLockdownVpnEnabled(true);
        handleStateChangedLocked();

        mCm.registerSystemDefaultNetworkCallback(mDefaultNetworkCallback, mHandler);
        final NetworkRequest vpnRequest = new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_VPN)
                .build();
        mCm.registerNetworkCallback(vpnRequest, mVpnNetworkCallback, mHandler);
    }

    public void shutdown() {
        synchronized (mStateLock) {
            shutdownLocked();
        }
    }

    private void shutdownLocked() {
        Log.d(TAG, "shutdownLocked()");

        mAcceptedEgressIface = null;

        mVpn.stopVpnRunnerPrivileged();
        mVpn.setLockdown(false);
        mCm.setLegacyLockdownVpnEnabled(false);
        hideNotification();

        mVpn.setEnableTeardown(true);
        mCm.unregisterNetworkCallback(mDefaultNetworkCallback);
        mCm.unregisterNetworkCallback(mVpnNetworkCallback);
    }

    /**
     * Reset VPN lockdown tracker. Called by ConnectivityService when receiving
     * {@link #ACTION_LOCKDOWN_RESET} pending intent.
     */
    public void reset() {
        Log.d(TAG, "reset()");
        synchronized (mStateLock) {
            // cycle tracker, reset error count, and trigger retry
            shutdownLocked();
            initLocked();
            handleStateChangedLocked();
        }
    }

    private void showNotification(int titleRes, int iconRes) {
        final Notification.Builder builder =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_VPN)
                        .setWhen(0)
                        .setSmallIcon(iconRes)
                        .setContentTitle(mContext.getString(titleRes))
                        .setContentText(mContext.getString(R.string.vpn_lockdown_config))
                        .setContentIntent(mConfigIntent)
                        .setOngoing(true)
                        .addAction(R.drawable.ic_menu_refresh, mContext.getString(R.string.reset),
                                mResetIntent)
                        .setColor(mContext.getColor(
                                com.android.internal.R.color.system_notification_accent_color));

        mNotificationManager.notify(null /* tag */, SystemMessage.NOTE_VPN_STATUS,
                builder.build());
    }

    private void hideNotification() {
        mNotificationManager.cancel(null, SystemMessage.NOTE_VPN_STATUS);
    }
}
