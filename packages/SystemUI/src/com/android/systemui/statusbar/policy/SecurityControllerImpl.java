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
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.systemui.R;

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
    private final IConnectivityManager mConnectivityManagerService;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UserManager mUserManager;
    private final ArrayList<SecurityControllerCallback> mCallbacks
            = new ArrayList<SecurityControllerCallback>();

    private SparseArray<VpnConfig> mCurrentVpns = new SparseArray<>();
    private int mCurrentUserId;
    private int mVpnUserId;

    public SecurityControllerImpl(Context context) {
        mContext = context;
        mDevicePolicyManager = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mConnectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mConnectivityManagerService = IConnectivityManager.Stub.asInterface(
                ServiceManager.getService(Context.CONNECTIVITY_SERVICE));
        mUserManager = (UserManager)
                context.getSystemService(Context.USER_SERVICE);

        // TODO: re-register network callback on user change.
        mConnectivityManager.registerNetworkCallback(REQUEST, mNetworkCallback);
        onUserSwitched(ActivityManager.getCurrentUser());
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SecurityController state:");
        pw.print("  mCurrentVpns={");
        for (int i = 0 ; i < mCurrentVpns.size(); i++) {
            if (i > 0) {
                pw.print(", ");
            }
            pw.print(mCurrentVpns.keyAt(i));
            pw.print('=');
            pw.print(mCurrentVpns.valueAt(i).user);
        }
        pw.println("}");
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
    public boolean hasProfileOwner() {
        return mDevicePolicyManager.getProfileOwnerAsUser(mCurrentUserId) != null;
    }

    @Override
    public String getProfileOwnerName() {
        for (UserInfo profile : mUserManager.getProfiles(mCurrentUserId)) {
            String name = mDevicePolicyManager.getProfileOwnerNameAsUser(profile.id);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    @Override
    public String getPrimaryVpnName() {
        VpnConfig cfg = mCurrentVpns.get(mVpnUserId);
        if (cfg != null) {
            return getNameForVpnConfig(cfg, new UserHandle(mVpnUserId));
        } else {
            return null;
        }
    }

    @Override
    public String getProfileVpnName() {
        for (UserInfo profile : mUserManager.getProfiles(mVpnUserId)) {
            if (profile.id == mVpnUserId) {
                continue;
            }
            VpnConfig cfg = mCurrentVpns.get(profile.id);
            if (cfg != null) {
                return getNameForVpnConfig(cfg, profile.getUserHandle());
            }
        }
        return null;
    }

    @Override
    public boolean isVpnEnabled() {
        for (UserInfo profile : mUserManager.getProfiles(mVpnUserId)) {
            if (mCurrentVpns.get(profile.id) != null) {
                return true;
            }
        }
        return false;
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
        if (mUserManager.getUserInfo(newUserId).isRestricted()) {
            // VPN for a restricted profile is routed through its owner user
            mVpnUserId = UserHandle.USER_OWNER;
        } else {
            mVpnUserId = mCurrentUserId;
        }
        fireCallbacks();
    }

    private String getNameForVpnConfig(VpnConfig cfg, UserHandle user) {
        if (cfg.legacy) {
            return mContext.getString(R.string.legacy_vpn_name);
        }
        // The package name for an active VPN is stored in the 'user' field of its VpnConfig
        final String vpnPackage = cfg.user;
        try {
            Context userContext = mContext.createPackageContextAsUser(mContext.getPackageName(),
                    0 /* flags */, user);
            return VpnConfig.getVpnLabel(userContext, vpnPackage).toString();
        } catch (NameNotFoundException nnfe) {
            Log.e(TAG, "Package " + vpnPackage + " is not present", nnfe);
            return null;
        }
    }

    private void fireCallbacks() {
        for (SecurityControllerCallback callback : mCallbacks) {
            callback.onStateChanged();
        }
    }

    private void updateState() {
        // Find all users with an active VPN
        SparseArray<VpnConfig> vpns = new SparseArray<>();
        try {
            for (UserInfo user : mUserManager.getUsers()) {
                VpnConfig cfg = mConnectivityManagerService.getVpnConfig(user.id);
                if (cfg == null) {
                    continue;
                } else if (cfg.legacy) {
                    // Legacy VPNs should do nothing if the network is disconnected. Third-party
                    // VPN warnings need to continue as traffic can still go to the app.
                    LegacyVpnInfo legacyVpn = mConnectivityManagerService.getLegacyVpnInfo(user.id);
                    if (legacyVpn == null || legacyVpn.state != LegacyVpnInfo.STATE_CONNECTED) {
                        continue;
                    }
                }
                vpns.put(user.id, cfg);
            }
        } catch (RemoteException rme) {
            // Roll back to previous state
            Log.e(TAG, "Unable to list active VPNs", rme);
            return;
        }
        mCurrentVpns = vpns;
    }

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            if (DEBUG) Log.d(TAG, "onAvailable " + network.netId);
            updateState();
            fireCallbacks();
        };

        // TODO Find another way to receive VPN lost.  This may be delayed depending on
        // how long the VPN connection is held on to.
        @Override
        public void onLost(Network network) {
            if (DEBUG) Log.d(TAG, "onLost " + network.netId);
            updateState();
            fireCallbacks();
        };
    };
}
