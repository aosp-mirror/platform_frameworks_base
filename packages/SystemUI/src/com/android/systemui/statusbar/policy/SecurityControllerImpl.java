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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IConnectivityManager;
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
    private final Context mContext;
    private final ConnectivityManager mConnectivityManager;
    private final IConnectivityManager mConnectivityService = IConnectivityManager.Stub.asInterface(
                ServiceManager.getService(Context.CONNECTIVITY_SERVICE));
    private final DevicePolicyManager mDevicePolicyManager;
    private final ArrayList<VpnCallback> mCallbacks = new ArrayList<VpnCallback>();

    private boolean mIsVpnEnabled;
    private VpnConfig mVpnConfig;
    private String mVpnName;

    public SecurityControllerImpl(Context context) {
        mContext = context;
        mDevicePolicyManager = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mConnectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // TODO: re-register network callback on user change.
        mConnectivityManager.registerNetworkCallback(REQUEST, mNetworkCallback);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SecurityController state:");
        pw.print("  mIsVpnEnabled="); pw.println(mIsVpnEnabled);
        pw.print("  mVpnConfig="); pw.println(mVpnConfig);
        pw.print("  mVpnName="); pw.println(mVpnName);
    }

    @Override
    public boolean hasDeviceOwner() {
        return !TextUtils.isEmpty(mDevicePolicyManager.getDeviceOwner());
    }

    @Override
    public String getDeviceOwnerName() {
        return mDevicePolicyManager.getDeviceOwnerName();
    }

    @Override
    public boolean isVpnEnabled() {
        // TODO: Remove once using NetworkCallback for updates.
        updateState();

        return mIsVpnEnabled;
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
    public void addCallback(VpnCallback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        mCallbacks.remove(callback);
    }

    @Override
    public void removeCallback(VpnCallback callback) {
        if (callback == null || mCallbacks.contains(callback)) return;
        if (DEBUG) Log.d(TAG, "addCallback " + callback);
        mCallbacks.add(callback);
    }

    private void fireCallbacks() {
        for (VpnCallback callback : mCallbacks) {
            callback.onVpnStateChanged();
        }
    }

    private void updateState() {
        try {
            mVpnConfig = mConnectivityService.getVpnConfig();

            // TODO: Remove once using NetworkCallback for updates.
            mIsVpnEnabled = mVpnConfig != null;

            if (mVpnConfig != null && !mVpnConfig.legacy) {
                mVpnName = VpnConfig.getVpnLabel(mContext, mVpnConfig.user).toString();
            }
        } catch (RemoteException | NameNotFoundException e) {
            Log.w(TAG, "Unable to get current VPN", e);
        }
    }

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        public void onCapabilitiesChanged(android.net.Network network,
                android.net.NetworkCapabilities networkCapabilities) {
            if (DEBUG) Log.d(TAG, "onCapabilitiesChanged " + networkCapabilities);
            mIsVpnEnabled = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            updateState();
            fireCallbacks();
        }
    };

}
