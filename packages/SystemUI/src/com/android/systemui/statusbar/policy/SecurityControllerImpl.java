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

import android.annotation.Nullable;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManager.DeviceOwnerType;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.VpnManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.KeyChain;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class SecurityControllerImpl implements SecurityController {

    private static final String TAG = "SecurityController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final NetworkRequest REQUEST =
            new NetworkRequest.Builder().clearCapabilities().build();
    private static final int NO_NETWORK = -1;

    private static final String VPN_BRANDED_META_DATA = "com.android.systemui.IS_BRANDED";

    private static final int CA_CERT_LOADING_RETRY_TIME_IN_MS = 30_000;

    private final Context mContext;
    private final UserTracker mUserTracker;
    private final ConnectivityManager mConnectivityManager;
    private final VpnManager mVpnManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;

    @GuardedBy("mCallbacks")
    private final ArrayList<SecurityControllerCallback> mCallbacks = new ArrayList<>();

    private SparseArray<VpnConfig> mCurrentVpns = new SparseArray<>();
    private int mCurrentUserId;
    private int mVpnUserId;

    // Key: userId, Value: whether the user has CACerts installed
    // Needs to be cached here since the query has to be asynchronous
    private ArrayMap<Integer, Boolean> mHasCACerts = new ArrayMap<Integer, Boolean>();

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    onUserSwitched(newUser);
                }
            };

    /**
     */
    @Inject
    public SecurityControllerImpl(
            Context context,
            UserTracker userTracker,
            @Background Handler bgHandler,
            BroadcastDispatcher broadcastDispatcher,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            DumpManager dumpManager
    ) {
        mContext = context;
        mUserTracker = userTracker;
        mDevicePolicyManager = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mConnectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mVpnManager = context.getSystemService(VpnManager.class);
        mPackageManager = context.getPackageManager();
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;

        dumpManager.registerDumpable(getClass().getSimpleName(), this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(KeyChain.ACTION_TRUST_STORE_CHANGED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        broadcastDispatcher.registerReceiverWithHandler(mBroadcastReceiver, filter, bgHandler,
                UserHandle.ALL);

        // TODO: re-register network callback on user change.
        mConnectivityManager.registerNetworkCallback(REQUEST, mNetworkCallback);
        onUserSwitched(mUserTracker.getUserId());
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);
    }

    public void dump(PrintWriter pw, String[] args) {
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
    public boolean isWorkProfileOn() {
        final UserHandle userHandle = UserHandle.of(getWorkProfileUserId(mCurrentUserId));
        return userHandle != null && !mUserManager.isQuietModeEnabled(userHandle);
    }

    @Override
    public boolean isProfileOwnerOfOrganizationOwnedDevice() {
        return mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile();
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
    @Nullable
    public ComponentName getDeviceOwnerComponentOnAnyUser() {
        return mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser();
    }

    @Override
    @DeviceOwnerType
    public int getDeviceOwnerType(@NonNull ComponentName admin) {
        return mDevicePolicyManager.getDeviceOwnerType(admin);
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
    public void removeCallback(@NonNull SecurityControllerCallback callback) {
        synchronized (mCallbacks) {
            if (callback == null) return;
            if (DEBUG) Log.d(TAG, "removeCallback " + callback);
            mCallbacks.remove(callback);
        }
    }

    @Override
    public void addCallback(@NonNull SecurityControllerCallback callback) {
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
        fireCallbacks();
    }

    @Override
    public boolean isParentalControlsEnabled() {
        return getProfileOwnerOrDeviceOwnerSupervisionComponent() != null;
    }

    @Override
    public DeviceAdminInfo getDeviceAdminInfo() {
        return getDeviceAdminInfo(getProfileOwnerOrDeviceOwnerComponent());
    }

    @Override
    public Drawable getIcon(DeviceAdminInfo info) {
        return (info == null) ? null : info.loadIcon(mPackageManager);
    }

    @Override
    public CharSequence getLabel(DeviceAdminInfo info) {
        return (info == null) ? null : info.loadLabel(mPackageManager);
    }

    private ComponentName getProfileOwnerOrDeviceOwnerSupervisionComponent() {
        UserHandle currentUser = new UserHandle(mCurrentUserId);
        return mDevicePolicyManager
               .getProfileOwnerOrDeviceOwnerSupervisionComponent(currentUser);
    }

    // Returns the ComponentName of the current DO/PO. Right now it only checks the supervision
    // component but can be changed to check for other DO/POs. This change would make getIcon()
    // and getLabel() work for all admins.
    private ComponentName getProfileOwnerOrDeviceOwnerComponent() {
        return getProfileOwnerOrDeviceOwnerSupervisionComponent();
    }

    private DeviceAdminInfo getDeviceAdminInfo(ComponentName componentName) {
        try {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = mPackageManager.getReceiverInfo(componentName,
                    PackageManager.GET_META_DATA);
            return new DeviceAdminInfo(mContext, resolveInfo);
        } catch (NameNotFoundException | XmlPullParserException | IOException e) {
            return null;
        }
    }

    private void refreshCACerts(int userId) {
        mBgExecutor.execute(() -> {
            Pair<Integer, Boolean> idWithCert = null;
            try (KeyChain.KeyChainConnection conn = KeyChain.bindAsUser(mContext,
                    UserHandle.of(userId))) {
                boolean hasCACerts = !(conn.getService().getUserCaAliases().getList().isEmpty());
                idWithCert = new Pair<Integer, Boolean>(userId, hasCACerts);
            } catch (RemoteException | InterruptedException | AssertionError e) {
                Log.i(TAG, "failed to get CA certs", e);
                idWithCert = new Pair<Integer, Boolean>(userId, null);
            } finally {
                if (DEBUG) Log.d(TAG, "Refreshing CA Certs " + idWithCert);
                if (idWithCert != null && idWithCert.second != null) {
                    mHasCACerts.put(idWithCert.first, idWithCert.second);
                    fireCallbacks();
                }
            }
        });
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
        synchronized (mCallbacks) {
            for (SecurityControllerCallback callback : mCallbacks) {
                callback.onStateChanged();
            }
        }
    }

    private void updateState() {
        // Find all users with an active VPN
        SparseArray<VpnConfig> vpns = new SparseArray<>();
        for (UserInfo user : mUserManager.getUsers()) {
            VpnConfig cfg = mVpnManager.getVpnConfig(user.id);
            if (cfg == null) {
                continue;
            } else if (cfg.legacy) {
                // Legacy VPNs should do nothing if the network is disconnected. Third-party
                // VPN warnings need to continue as traffic can still go to the app.
                LegacyVpnInfo legacyVpn = mVpnManager.getLegacyVpnInfo(user.id);
                if (legacyVpn == null || legacyVpn.state != LegacyVpnInfo.STATE_CONNECTED) {
                    continue;
                }
            }
            vpns.put(user.id, cfg);
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
            if (DEBUG) Log.d(TAG, "onAvailable " + network.getNetId());
            updateState();
            fireCallbacks();
        };

        // TODO Find another way to receive VPN lost.  This may be delayed depending on
        // how long the VPN connection is held on to.
        @Override
        public void onLost(Network network) {
            if (DEBUG) Log.d(TAG, "onLost " + network.getNetId());
            updateState();
            fireCallbacks();
        };
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (KeyChain.ACTION_TRUST_STORE_CHANGED.equals(intent.getAction())) {
                refreshCACerts(getSendingUserId());
            } else if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId != UserHandle.USER_NULL) refreshCACerts(userId);
            }
        }
    };
}
