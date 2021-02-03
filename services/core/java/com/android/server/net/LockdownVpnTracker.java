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

import static android.net.ConnectivityManager.TYPE_NONE;
import static android.provider.Settings.ACTION_VPN_SETTINGS;

import static com.android.server.connectivity.NetworkNotificationManager.NOTIFICATION_CHANNEL_VPN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.os.Handler;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.server.ConnectivityService;
import com.android.server.EventLogTags;
import com.android.server.connectivity.Vpn;

import java.util.List;
import java.util.Objects;

/**
 * State tracker for lockdown mode. Watches for normal {@link NetworkInfo} to be
 * connected and kicks off VPN connection, managing any required {@code netd}
 * firewall rules.
 */
public class LockdownVpnTracker {
    private static final String TAG = "LockdownVpnTracker";

    /** Number of VPN attempts before waiting for user intervention. */
    private static final int MAX_ERROR_COUNT = 4;

    public static final String ACTION_LOCKDOWN_RESET = "com.android.server.action.LOCKDOWN_RESET";

    @NonNull private final Context mContext;
    @NonNull private final ConnectivityService mConnService;
    @NonNull private final NotificationManager mNotificationManager;
    @NonNull private final Handler mHandler;
    @NonNull private final Vpn mVpn;
    @NonNull private final VpnProfile mProfile;
    @NonNull private final KeyStore mKeyStore;

    @NonNull private final Object mStateLock = new Object();

    @NonNull private final PendingIntent mConfigIntent;
    @NonNull private final PendingIntent mResetIntent;

    @Nullable
    private String mAcceptedEgressIface;

    private int mErrorCount;

    public LockdownVpnTracker(@NonNull Context context,
            @NonNull ConnectivityService connService,
            @NonNull Handler handler,
            @NonNull KeyStore keyStore,
            @NonNull Vpn vpn,
            @NonNull VpnProfile profile) {
        mContext = Objects.requireNonNull(context);
        mConnService = Objects.requireNonNull(connService);
        mHandler = Objects.requireNonNull(handler);
        mVpn = Objects.requireNonNull(vpn);
        mProfile = Objects.requireNonNull(profile);
        mKeyStore = Objects.requireNonNull(keyStore);
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

        final NetworkInfo egressInfo = mConnService.getActiveNetworkInfoUnfiltered();
        final LinkProperties egressProp = mConnService.getActiveLinkProperties();

        final NetworkInfo vpnInfo = mVpn.getNetworkInfo();
        final VpnConfig vpnConfig = mVpn.getLegacyVpnConfig();

        // Restart VPN when egress network disconnected or changed
        final boolean egressDisconnected = egressInfo == null
                || State.DISCONNECTED.equals(egressInfo.getState());
        final boolean egressChanged = egressProp == null
                || !TextUtils.equals(mAcceptedEgressIface, egressProp.getInterfaceName());

        final int egressType = (egressInfo == null) ? TYPE_NONE : egressInfo.getType();
        final String egressIface = (egressProp == null) ?
                null : egressProp.getInterfaceName();
        Log.d(TAG, "handleStateChanged: egress=" + egressType
                + " " + mAcceptedEgressIface + "->" + egressIface);

        if (egressDisconnected || egressChanged) {
            mAcceptedEgressIface = null;
            mVpn.stopVpnRunnerPrivileged();
        }
        if (egressDisconnected) {
            hideNotification();
            return;
        }

        if (vpnInfo.getDetailedState() == DetailedState.FAILED) {
            EventLogTags.writeLockdownVpnError(egressType);
        }

        if (mErrorCount > MAX_ERROR_COUNT) {
            showNotification(R.string.vpn_lockdown_error, R.drawable.vpn_disconnected);

        } else if (egressInfo.isConnected() && !vpnInfo.isConnectedOrConnecting()) {
            if (mProfile.isValidLockdownProfile()) {
                Log.d(TAG, "Active network connected; starting VPN");
                EventLogTags.writeLockdownVpnConnecting(egressType);
                showNotification(R.string.vpn_lockdown_connecting, R.drawable.vpn_disconnected);

                mAcceptedEgressIface = egressProp.getInterfaceName();
                try {
                    // Use the privileged method because Lockdown VPN is initiated by the system, so
                    // no additional permission checks are necessary.
                    mVpn.startLegacyVpnPrivileged(mProfile, mKeyStore, null, egressProp);
                } catch (IllegalStateException e) {
                    mAcceptedEgressIface = null;
                    Log.e(TAG, "Failed to start VPN", e);
                    showNotification(R.string.vpn_lockdown_error, R.drawable.vpn_disconnected);
                }
            } else {
                Log.e(TAG, "Invalid VPN profile; requires IP-based server and DNS");
                showNotification(R.string.vpn_lockdown_error, R.drawable.vpn_disconnected);
            }

        } else if (vpnInfo.isConnected() && vpnConfig != null) {
            final String iface = vpnConfig.interfaze;
            final List<LinkAddress> sourceAddrs = vpnConfig.addresses;

            Log.d(TAG, "VPN connected using iface=" + iface
                    + ", sourceAddr=" + sourceAddrs.toString());
            EventLogTags.writeLockdownVpnConnected(egressType);
            showNotification(R.string.vpn_lockdown_connected, R.drawable.vpn_connected);

            final NetworkInfo clone = new NetworkInfo(egressInfo);
            augmentNetworkInfo(clone);
            mConnService.sendConnectedBroadcast(clone);
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
        handleStateChangedLocked();
    }

    public void shutdown() {
        synchronized (mStateLock) {
            shutdownLocked();
        }
    }

    private void shutdownLocked() {
        Log.d(TAG, "shutdownLocked()");

        mAcceptedEgressIface = null;
        mErrorCount = 0;

        mVpn.stopVpnRunnerPrivileged();
        mVpn.setLockdown(false);
        hideNotification();

        mVpn.setEnableTeardown(true);
    }

    /**
     * Reset VPN lockdown tracker. Called by ConnectivityService when receiving
     * {@link #ACTION_LOCKDOWN_RESET} pending intent.
     */
    @GuardedBy("mConnService.mVpns")
    public void reset() {
        Log.d(TAG, "reset()");
        synchronized (mStateLock) {
            // cycle tracker, reset error count, and trigger retry
            shutdownLocked();
            initLocked();
            handleStateChangedLocked();
        }
    }

    public void onNetworkInfoChanged() {
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

    public void augmentNetworkInfo(NetworkInfo info) {
        if (info.isConnected()) {
            final NetworkInfo vpnInfo = mVpn.getNetworkInfo();
            info.setDetailedState(vpnInfo.getDetailedState(), vpnInfo.getReason(), null);
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
