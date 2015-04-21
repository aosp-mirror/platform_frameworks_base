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
import android.net.NetworkRequest;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;

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

    private SparseArray<Boolean> mCurrentVpnUsers = new SparseArray<>();
    private int mCurrentUserId;

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
        mCurrentUserId = ActivityManager.getCurrentUser();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SecurityController state:");
        pw.print("  mCurrentVpnUsers=" + mCurrentVpnUsers);
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
        boolean result = false;
        for (UserInfo profile : mUserManager.getProfiles(mCurrentUserId)) {
            result |= (mDevicePolicyManager.getProfileOwnerAsUser(profile.id) != null);
        }
        return result;
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
    public boolean isVpnEnabled() {
        return mCurrentVpnUsers.get(mCurrentUserId) != null;
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

    private void fireCallbacks() {
        for (SecurityControllerCallback callback : mCallbacks) {
            callback.onStateChanged();
        }
    }

    private void updateState() {
        // Find all users with an active VPN
        SparseArray<Boolean> vpnUsers = new SparseArray<>();
        try {
            for (VpnInfo vpn : mConnectivityManagerService.getAllVpnInfo()) {
                UserInfo user = mUserManager.getUserInfo(UserHandle.getUserId(vpn.ownerUid));
                int groupId = (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID ?
                        user.profileGroupId : user.id);

                vpnUsers.put(groupId, Boolean.TRUE);
            }
        } catch (RemoteException rme) {
            // Roll back to previous state
            Log.e(TAG, "Unable to list active VPNs", rme);
            return;
        }
        mCurrentVpnUsers = vpnUsers;
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
