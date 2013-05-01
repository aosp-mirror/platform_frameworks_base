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

import static android.Manifest.permission.CONNECTIVITY_INTERNAL;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.security.Credentials;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.Preconditions;
import com.android.server.ConnectivityService;
import com.android.server.EventLogTags;
import com.android.server.connectivity.Vpn;

/**
 * State tracker for lockdown mode. Watches for normal {@link NetworkInfo} to be
 * connected and kicks off VPN connection, managing any required {@code netd}
 * firewall rules.
 */
public class LockdownVpnTracker {
    private static final String TAG = "LockdownVpnTracker";

    /** Number of VPN attempts before waiting for user intervention. */
    private static final int MAX_ERROR_COUNT = 4;

    private static final String ACTION_LOCKDOWN_RESET = "com.android.server.action.LOCKDOWN_RESET";

    private static final String ACTION_VPN_SETTINGS = "android.net.vpn.SETTINGS";
    private static final String EXTRA_PICK_LOCKDOWN = "android.net.vpn.PICK_LOCKDOWN";

    private final Context mContext;
    private final INetworkManagementService mNetService;
    private final ConnectivityService mConnService;
    private final Vpn mVpn;
    private final VpnProfile mProfile;

    private final Object mStateLock = new Object();

    private final PendingIntent mConfigIntent;
    private final PendingIntent mResetIntent;

    private String mAcceptedEgressIface;
    private String mAcceptedIface;
    private String mAcceptedSourceAddr;

    private int mErrorCount;

    public static boolean isEnabled() {
        return KeyStore.getInstance().contains(Credentials.LOCKDOWN_VPN);
    }

    public LockdownVpnTracker(Context context, INetworkManagementService netService,
            ConnectivityService connService, Vpn vpn, VpnProfile profile) {
        mContext = Preconditions.checkNotNull(context);
        mNetService = Preconditions.checkNotNull(netService);
        mConnService = Preconditions.checkNotNull(connService);
        mVpn = Preconditions.checkNotNull(vpn);
        mProfile = Preconditions.checkNotNull(profile);

        final Intent configIntent = new Intent(ACTION_VPN_SETTINGS);
        configIntent.putExtra(EXTRA_PICK_LOCKDOWN, true);
        mConfigIntent = PendingIntent.getActivity(mContext, 0, configIntent, 0);

        final Intent resetIntent = new Intent(ACTION_LOCKDOWN_RESET);
        resetIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mResetIntent = PendingIntent.getBroadcast(mContext, 0, resetIntent, 0);
    }

    private BroadcastReceiver mResetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reset();
        }
    };

    /**
     * Watch for state changes to both active egress network, kicking off a VPN
     * connection when ready, or setting firewall rules once VPN is connected.
     */
    private void handleStateChangedLocked() {
        Slog.d(TAG, "handleStateChanged()");

        final NetworkInfo egressInfo = mConnService.getActiveNetworkInfoUnfiltered();
        final LinkProperties egressProp = mConnService.getActiveLinkProperties();

        final NetworkInfo vpnInfo = mVpn.getNetworkInfo();
        final VpnConfig vpnConfig = mVpn.getLegacyVpnConfig();

        // Restart VPN when egress network disconnected or changed
        final boolean egressDisconnected = egressInfo == null
                || State.DISCONNECTED.equals(egressInfo.getState());
        final boolean egressChanged = egressProp == null
                || !TextUtils.equals(mAcceptedEgressIface, egressProp.getInterfaceName());
        if (egressDisconnected || egressChanged) {
            clearSourceRulesLocked();
            mAcceptedEgressIface = null;
            mVpn.stopLegacyVpn();
        }
        if (egressDisconnected) {
            hideNotification();
            return;
        }

        final int egressType = egressInfo.getType();
        if (vpnInfo.getDetailedState() == DetailedState.FAILED) {
            EventLogTags.writeLockdownVpnError(egressType);
        }

        if (mErrorCount > MAX_ERROR_COUNT) {
            showNotification(R.string.vpn_lockdown_error, R.drawable.vpn_disconnected);

        } else if (egressInfo.isConnected() && !vpnInfo.isConnectedOrConnecting()) {
            if (mProfile.isValidLockdownProfile()) {
                Slog.d(TAG, "Active network connected; starting VPN");
                EventLogTags.writeLockdownVpnConnecting(egressType);
                showNotification(R.string.vpn_lockdown_connecting, R.drawable.vpn_disconnected);

                mAcceptedEgressIface = egressProp.getInterfaceName();
                mVpn.startLegacyVpn(mProfile, KeyStore.getInstance(), egressProp);

            } else {
                Slog.e(TAG, "Invalid VPN profile; requires IP-based server and DNS");
                showNotification(R.string.vpn_lockdown_error, R.drawable.vpn_disconnected);
            }

        } else if (vpnInfo.isConnected() && vpnConfig != null) {
            final String iface = vpnConfig.interfaze;
            final String sourceAddr = vpnConfig.addresses;

            if (TextUtils.equals(iface, mAcceptedIface)
                    && TextUtils.equals(sourceAddr, mAcceptedSourceAddr)) {
                return;
            }

            Slog.d(TAG, "VPN connected using iface=" + iface + ", sourceAddr=" + sourceAddr);
            EventLogTags.writeLockdownVpnConnected(egressType);
            showNotification(R.string.vpn_lockdown_connected, R.drawable.vpn_connected);

            try {
                clearSourceRulesLocked();

                mNetService.setFirewallInterfaceRule(iface, true);
                mNetService.setFirewallEgressSourceRule(sourceAddr, true);

                mErrorCount = 0;
                mAcceptedIface = iface;
                mAcceptedSourceAddr = sourceAddr;
            } catch (RemoteException e) {
                throw new RuntimeException("Problem setting firewall rules", e);
            }

            mConnService.sendConnectedBroadcast(augmentNetworkInfo(egressInfo));
        }
    }

    public void init() {
        synchronized (mStateLock) {
            initLocked();
        }
    }

    private void initLocked() {
        Slog.d(TAG, "initLocked()");

        mVpn.setEnableNotifications(false);
        mVpn.setEnableTeardown(false);

        final IntentFilter resetFilter = new IntentFilter(ACTION_LOCKDOWN_RESET);
        mContext.registerReceiver(mResetReceiver, resetFilter, CONNECTIVITY_INTERNAL, null);

        try {
            // TODO: support non-standard port numbers
            mNetService.setFirewallEgressDestRule(mProfile.server, 500, true);
            mNetService.setFirewallEgressDestRule(mProfile.server, 4500, true);
            mNetService.setFirewallEgressDestRule(mProfile.server, 1701, true);
        } catch (RemoteException e) {
            throw new RuntimeException("Problem setting firewall rules", e);
        }

        synchronized (mStateLock) {
            handleStateChangedLocked();
        }
    }

    public void shutdown() {
        synchronized (mStateLock) {
            shutdownLocked();
        }
    }

    private void shutdownLocked() {
        Slog.d(TAG, "shutdownLocked()");

        mAcceptedEgressIface = null;
        mErrorCount = 0;

        mVpn.stopLegacyVpn();
        try {
            mNetService.setFirewallEgressDestRule(mProfile.server, 500, false);
            mNetService.setFirewallEgressDestRule(mProfile.server, 4500, false);
            mNetService.setFirewallEgressDestRule(mProfile.server, 1701, false);
        } catch (RemoteException e) {
            throw new RuntimeException("Problem setting firewall rules", e);
        }
        clearSourceRulesLocked();
        hideNotification();

        mContext.unregisterReceiver(mResetReceiver);
        mVpn.setEnableNotifications(true);
        mVpn.setEnableTeardown(true);
    }

    public void reset() {
        synchronized (mStateLock) {
            // cycle tracker, reset error count, and trigger retry
            shutdownLocked();
            initLocked();
            handleStateChangedLocked();
        }
    }

    private void clearSourceRulesLocked() {
        try {
            if (mAcceptedIface != null) {
                mNetService.setFirewallInterfaceRule(mAcceptedIface, false);
                mAcceptedIface = null;
            }
            if (mAcceptedSourceAddr != null) {
                mNetService.setFirewallEgressSourceRule(mAcceptedSourceAddr, false);
                mAcceptedSourceAddr = null;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Problem setting firewall rules", e);
        }
    }

    public void onNetworkInfoChanged(NetworkInfo info) {
        synchronized (mStateLock) {
            handleStateChangedLocked();
        }
    }

    public void onVpnStateChanged(NetworkInfo info) {
        if (info.getDetailedState() == DetailedState.FAILED) {
            mErrorCount++;
        }
        synchronized (mStateLock) {
            handleStateChangedLocked();
        }
    }

    public NetworkInfo augmentNetworkInfo(NetworkInfo info) {
        if (info.isConnected()) {
            final NetworkInfo vpnInfo = mVpn.getNetworkInfo();
            info = new NetworkInfo(info);
            info.setDetailedState(vpnInfo.getDetailedState(), vpnInfo.getReason(), null);
        }
        return info;
    }

    private void showNotification(int titleRes, int iconRes) {
        final Notification.Builder builder = new Notification.Builder(mContext);
        builder.setWhen(0);
        builder.setSmallIcon(iconRes);
        builder.setContentTitle(mContext.getString(titleRes));
        builder.setContentText(mContext.getString(R.string.vpn_lockdown_config));
        builder.setContentIntent(mConfigIntent);
        builder.setPriority(Notification.PRIORITY_LOW);
        builder.setOngoing(true);
        builder.addAction(
                R.drawable.ic_menu_refresh, mContext.getString(R.string.reset), mResetIntent);

        NotificationManager.from(mContext).notify(TAG, 0, builder.build());
    }

    private void hideNotification() {
        NotificationManager.from(mContext).cancel(TAG, 0);
    }
}
