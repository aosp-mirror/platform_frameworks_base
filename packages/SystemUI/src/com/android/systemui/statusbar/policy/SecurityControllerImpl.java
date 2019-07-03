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

import static com.android.systemui.Dependency.BG_HANDLER_NAME;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.KeyChain;
import android.security.KeyChain.KeyChainConnection;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.systemui.R;
import com.android.systemui.settings.CurrentUserTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 */
@Singleton
public class SecurityControllerImpl extends CurrentUserTracker implements SecurityController {

    private static final String TAG = "SecurityController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final NetworkRequest REQUEST = new NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .setUids(null)
            .build();
    private static final int NO_NETWORK = -1;

    private static final String VPN_BRANDED_META_DATA = "com.android.systemui.IS_BRANDED";

    private static final int CA_CERT_LOADING_RETRY_TIME_IN_MS = 30_000;

    private final Context mContext;
    private final ConnectivityManager mConnectivityManager;
    private final IConnectivityManager mConnectivityManagerService;
    private final DevicePolicyManager mDevicePolicyManager;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final Handler mBgHandler;

    @GuardedBy("mCallbacks")
    private final ArrayList<SecurityControllerCallback> mCallbacks = new ArrayList<>();

    private SparseArray<VpnConfig> mCurrentVpns = new SparseArray<>();
    private int mCurrentUserId;
    private int mVpnUserId;

    // Key: userId, Value: whether the user has CACerts installed
    // Needs to be cached here since the query has to be asynchronous
    private ArrayMap<Integer, Boolean> mHasCACerts = new ArrayMap<Integer, Boolean>();

    /**
     */
    @Inject
    public SecurityControllerImpl(Context context, @Named(BG_HANDLER_NAME) Handler bgHandler) {
        this(context, bgHandler, null);
    }

    public SecurityControllerImpl(Context context, Handler bgHandler,
            SecurityControllerCallback callback) {
        super(context);
        mContext = context;
        mBgHandler = bgHandler;
        mDevicePolicyManager = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mConnectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mConnectivityManagerService = IConnectivityManager.Stub.asInterface(
                ServiceManager.getService(Context.CONNECTIVITY_SERVICE));
        mPackageManager = context.getPackageManager();
        mUserManager = (UserManager)
                context.getSystemService(Context.USER_SERVICE);

        addCallback(callback);

        IntentFilter filter = new IntentFilter();
        filter.addAction(KeyChain.ACTION_TRUST_STORE_CHANGED);
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null,
                bgHandler);

        // TODO: re-register network callback on user change.
        mConnectivityManager.registerNetworkCallback(REQUEST, mNetworkCallback);
        onUserSwitched(ActivityManager.getCurrentUser());
        startTracking();
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
    public boolean isDeviceManaged() {
        return mDevicePolicyManager.isDeviceManaged();
    }

    @Override
    public String getDeviceOwnerName() {
        return mDevicePolicyManager.getDeviceOwnerNameOnAnyUser();
    }

    @Override
    public boolean hasProfileOwner() {
        return mDevicePolicyManager.getProfileOwnerAsUser(mCurrentUserId) != null;
    }

    @Override
    public String getProfileOwnerName() {
        for (int profileId : mUserManager.getProfileIdsWithDisabled(mCurrentUserId)) {
            String name = mDevicePolicyManager.getProfileOwnerNameAsUser(profileId);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    @Override
    public CharSequence getDeviceOwnerOrganizationName() {
        return mDevicePolicyManager.getDeviceOwnerOrganizationName();
    }

    @Override
    public CharSequence getWorkProfileOrganizationName() {
        final int profileId = getWorkProfileUserId(mCurrentUserId);
        if (profileId == UserHandle.USER_NULL) return null;
        return mDevicePolicyManager.getOrganizationNameForUser(profileId);
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
    public List<VpnProfile> getConfiguredLegacyVpns() {
        try {
            return Arrays.asList(mConnectivityManagerService.getAllLegacyVpns());
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to connect", e);
            return new ArrayList<VpnProfile>();
        }
    }

    @Override
    public List<String> getVpnAppPackageNames() {
        List<String> result = new ArrayList<>();
        AppOpsManager aom = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        List<AppOpsManager.PackageOps> apps = aom.getPackagesForOps(
                new int[] {AppOpsManager.OP_ACTIVATE_VPN});
        if (apps != null) {
            for (AppOpsManager.PackageOps pkg : apps) {
                if (mVpnUserId != UserHandle.getUserId(pkg.getUid())) {
                    continue;
                }
                // Look for a MODE_ALLOWED permission to activate VPN.
                boolean allowed = false;
                for (AppOpsManager.OpEntry op : pkg.getOps()) {
                    if (op.getOp() == AppOpsManager.OP_ACTIVATE_VPN &&
                            op.getMode() == AppOpsManager.MODE_ALLOWED) {
                        allowed = true;
                        break;
                    }
                }
                if (allowed) {
                    result.add(pkg.getPackageName());
                }
            }
        }

        return result;
    }

    @Override
    public void connectLegacyVpn(VpnProfile profile) {
        try {
            mConnectivityManagerService.startLegacyVpn(profile);
        } catch (IllegalStateException | RemoteException e) {
            Log.e(TAG, "Failed to connect", e);
        }
    }

    @Override
    public void launchVpnApp(String packageName) {
        try {
            UserHandle user = UserHandle.of(mCurrentUserId);
            Context userContext = mContext.createPackageContextAsUser(
                    mContext.getPackageName(), 0 /* flags */, user);
            PackageManager pm = userContext.getPackageManager();
            Intent appIntent = pm.getLaunchIntentForPackage(packageName);
            if (appIntent != null) {
                userContext.startActivityAsUser(appIntent, user);
            }
        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.w(TAG, "VPN provider does not exist: " + packageName, nnfe);
        }
    }

    @Override
    public void disconnectPrimaryVpn() {
        VpnConfig cfg = mCurrentVpns.get(mVpnUserId);
        if (cfg != null) {
            final String user = cfg.legacy ? VpnConfig.LEGACY_VPN : cfg.user;
            try {
                mConnectivityManagerService.prepareVpn(user, VpnConfig.LEGACY_VPN, mVpnUserId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to disconnect", e);
            }
        }
    }

    private int getWorkProfileUserId(int userId) {
        for (final UserInfo userInfo : mUserManager.getProfiles(userId)) {
            if (userInfo.isManagedProfile()) {
                return userInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    @Override
    public boolean hasWorkProfile() {
        return getWorkProfileUserId(mCurrentUserId) != UserHandle.USER_NULL;
    }

    @Override
    public String getWorkProfileVpnName() {
        final int profileId = getWorkProfileUserId(mVpnUserId);
        if (profileId == UserHandle.USER_NULL) return null;
        VpnConfig cfg = mCurrentVpns.get(profileId);
        if (cfg != null) {
            return getNameForVpnConfig(cfg, UserHandle.of(profileId));
        }
        return null;
    }

    @Override
    public boolean isNetworkLoggingEnabled() {
        return mDevicePolicyManager.isNetworkLoggingEnabled(null);
    }

    @Override
    public boolean isVpnEnabled() {
        for (int profileId : mUserManager.getProfileIdsWithDisabled(mVpnUserId)) {
            if (mCurrentVpns.get(profileId) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isVpnRestricted() {
        UserHandle currentUser = new UserHandle(mCurrentUserId);
        return mUserManager.getUserInfo(mCurrentUserId).isRestricted()
                || mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN, currentUser);
    }

    @Override
    public boolean isVpnBranded() {
        VpnConfig cfg = mCurrentVpns.get(mVpnUserId);
        if (cfg == null) {
            return false;
        }

        String packageName = getPackageNameForVpnConfig(cfg);
        if (packageName == null) {
            return false;
        }

        return isVpnPackageBranded(packageName);
    }

    @Override
    public boolean hasCACertInCurrentUser() {
        Boolean hasCACerts = mHasCACerts.get(mCurrentUserId);
        return hasCACerts != null && hasCACerts.booleanValue();
    }

    @Override
    public boolean hasCACertInWorkProfile() {
        int userId = getWorkProfileUserId(mCurrentUserId);
        if (userId == UserHandle.USER_NULL) return false;
        Boolean hasCACerts = mHasCACerts.get(userId);
        return hasCACerts != null && hasCACerts.booleanValue();
    }

    @Override
    public void removeCallback(SecurityControllerCallback callback) {
        synchronized (mCallbacks) {
            if (callback == null) return;
            if (DEBUG) Log.d(TAG, "removeCallback " + callback);
            mCallbacks.remove(callback);
        }
    }

    @Override
    public void addCallback(SecurityControllerCallback callback) {
        synchronized (mCallbacks) {
            if (callback == null || mCallbacks.contains(callback)) return;
            if (DEBUG) Log.d(TAG, "addCallback " + callback);
            mCallbacks.add(callback);
        }
    }

    @Override
    public void onUserSwitched(int newUserId) {
        mCurrentUserId = newUserId;
        final UserInfo newUserInfo = mUserManager.getUserInfo(newUserId);
        if (newUserInfo.isRestricted()) {
            // VPN for a restricted profile is routed through its owner user
            mVpnUserId = newUserInfo.restrictedProfileParentId;
        } else {
            mVpnUserId = mCurrentUserId;
        }
        refreshCACerts();
        fireCallbacks();
    }

    private void refreshCACerts() {
        new CACertLoader().execute(mCurrentUserId);
        int workProfileId = getWorkProfileUserId(mCurrentUserId);
        if (workProfileId != UserHandle.USER_NULL) new CACertLoader().execute(workProfileId);
    }

    private String getNameForVpnConfig(VpnConfig cfg, UserHandle user) {
        if (cfg.legacy) {
            return cfg.session != null
                    ? cfg.session : mContext.getString(R.string.legacy_vpn_name);
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
        synchronized (mCallbacks) {
            for (SecurityControllerCallback callback : mCallbacks) {
                callback.onStateChanged();
            }
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

    private String getPackageNameForVpnConfig(VpnConfig cfg) {
        if (cfg.legacy) {
            return null;
        }
        return cfg.user;
    }

    private boolean isVpnPackageBranded(String packageName) {
        boolean isBranded;
        try {
            ApplicationInfo info = mPackageManager.getApplicationInfo(packageName,
                PackageManager.GET_META_DATA);
            if (info == null || info.metaData == null || !info.isSystemApp()) {
                return false;
            }
            isBranded = info.metaData.getBoolean(VPN_BRANDED_META_DATA, false);
        } catch (NameNotFoundException e) {
            return false;
        }
        return isBranded;
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

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (KeyChain.ACTION_TRUST_STORE_CHANGED.equals(intent.getAction())) {
                refreshCACerts();
            }
        }
    };

    protected class CACertLoader extends AsyncTask<Integer, Void, Pair<Integer, Boolean> > {

        @Override
        protected Pair<Integer, Boolean> doInBackground(Integer... userId) {
            try (KeyChainConnection conn = KeyChain.bindAsUser(mContext,
                                                               UserHandle.of(userId[0]))) {
                boolean hasCACerts = !(conn.getService().getUserCaAliases().getList().isEmpty());
                return new Pair<Integer, Boolean>(userId[0], hasCACerts);
            } catch (RemoteException | InterruptedException | AssertionError e) {
                Log.i(TAG, "failed to get CA certs", e);
                mBgHandler.postDelayed(
                        () -> new CACertLoader().execute(userId[0]),
                        CA_CERT_LOADING_RETRY_TIME_IN_MS);
                return new Pair<Integer, Boolean>(userId[0], null);
            }
        }

        @Override
        protected void onPostExecute(Pair<Integer, Boolean> result) {
            if (DEBUG) Log.d(TAG, "onPostExecute " + result);
            if (result.second != null) {
                mHasCACerts.put(result.first, result.second);
                fireCallbacks();
            }
        }
    }
}
