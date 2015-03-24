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
package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.net.VpnConfig;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class SecurityControllerImpl implements SecurityController {

    private static final String TAG = "SecurityController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final NetworkRequest REQUEST = new NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .build();
    private static final int NO_NETWORK = -1;

    private final Context mContext;
    private final ConnectivityManager mConnectivityManager;
    private final IConnectivityManager mConnectivityService = IConnectivityManager.Stub.asInterface(
                ServiceManager.getService(Context.CONNECTIVITY_SERVICE));
    private final DevicePolicyManager mDevicePolicyManager;
    private final ArrayList<SecurityControllerCallback> mCallbacks
            = new ArrayList<SecurityControllerCallback>();

    private VpnConfig mVpnConfig;
    private String mVpnName;
    private int mCurrentVpnNetworkId = NO_NETWORK;
    private int mCurrentUserId;

    public SecurityControllerImpl(Context context) {
        mContext = context;
        mDevicePolicyManager = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mConnectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // TODO: re-register network callback on user change.
        mConnectivityManager.registerNetworkCallback(REQUEST, mNetworkCallback);
        mCurrentUserId = ActivityManager.getCurrentUser();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SecurityController state:");
        pw.print("  mCurrentVpnNetworkId="); pw.println(mCurrentVpnNetworkId);
        pw.print("  mVpnConfig="); pw.println(mVpnConfig);
        pw.print("  mVpnName="); pw.println(mVpnName);
    }

    @Override
    public boolean hasDeviceOwner() {
        return !TextUtils.isEmpty(mDevicePolicyManager.getDeviceOwner());
    }

    @Override
    public boolean hasProfileOwner() {
        return !TextUtils.isEmpty(mDevicePolicyManager.getProfileOwnerNameAsUser(mCurrentUserId));
    }

    @Override
    public String getDeviceOwnerName() {
        return mDevicePolicyManager.getDeviceOwnerName();
    }

    @Override
    public String getProfileOwnerName() {
        return mDevicePolicyManager.getProfileOwnerNameAsUser(mCurrentUserId);
    }


    @Override
    public boolean isVpnEnabled() {
        return mCurrentVpnNetworkId != NO_NETWORK;
    }

    @Override
    public boolean isLegacyVpn() {
        return mVpnConfig.legacy;
    }

    @Override
    public String getVpnApp() {
        return mVpnName;
    }

    @Override
    public String getLegacyVpnName() {
        return mVpnConfig.session;
    }

    @Override
    public void disconnectFromVpn() {
        try {
            if (isLegacyVpn()) {
                mConnectivityService.prepareVpn(VpnConfig.LEGACY_VPN, VpnConfig.LEGACY_VPN);
            } else {
                // Prevent this app from initiating VPN connections in the future without user
                // intervention.
                mConnectivityService.setVpnPackageAuthorization(false);

                mConnectivityService.prepareVpn(mVpnConfig.user, VpnConfig.LEGACY_VPN);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to disconnect from VPN", e);
        }
    }

    @Override
    public void removeCallback(SecurityControllerCallback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        mCallbacks.remove(callback);
    }

    @Override
    public void addCallback(SecurityControllerCallback callback) {
        if (callback == null || mCallbacks.contains(callback)) return;
        if (DEBUG) Log.d(TAG, "addCallback " + callback);
        mCallbacks.add(callback);
    }

    @Override
    public void onUserSwitched(int newUserId) {
        mCurrentUserId = newUserId;
        fireCallbacks();
    }

    private void setCurrentNetid(int netId) {
        if (netId != mCurrentVpnNetworkId) {
            mCurrentVpnNetworkId = netId;
            updateState();
            fireCallbacks();
        }
    }

    private void fireCallbacks() {
        for (SecurityControllerCallback callback : mCallbacks) {
            callback.onStateChanged();
        }
    }

    private void updateState() {
        try {
            mVpnConfig = mConnectivityService.getVpnConfig();

            if (mVpnConfig != null && !mVpnConfig.legacy) {
                mVpnName = VpnConfig.getVpnLabel(mContext, mVpnConfig.user).toString();
            }
        } catch (RemoteException | NameNotFoundException e) {
            Log.w(TAG, "Unable to get current VPN", e);
        }
    }

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            NetworkCapabilities networkCapabilities =
                    mConnectivityManager.getNetworkCapabilities(network);
            if (DEBUG) Log.d(TAG, "onAvailable " + network.netId + " : " + networkCapabilities);
            if (networkCapabilities != null &&
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                setCurrentNetid(network.netId);
            }
        };

        // TODO Find another way to receive VPN lost.  This may be delayed depending on
        // how long the VPN connection is held on to.
        @Override
        public void onLost(Network network) {
            if (DEBUG) Log.d(TAG, "onLost " + network.netId);
            if (mCurrentVpnNetworkId == network.netId) {
                setCurrentNetid(NO_NETWORK);
            }
        };
    };

}
