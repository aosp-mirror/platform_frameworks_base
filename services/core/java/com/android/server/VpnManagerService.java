/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.NETWORK_STACK;

import static com.android.net.module.util.PermissionUtils.enforceAnyPermissionOf;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.IVpnManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkStack;
import android.net.UnderlyingNetworkInfo;
import android.net.Uri;
import android.net.VpnManager;
import android.net.VpnService;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.Credentials;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.connectivity.Vpn;
import com.android.server.connectivity.VpnProfileStore;
import com.android.server.net.LockdownVpnTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Service that tracks and manages VPNs, and backs the VpnService and VpnManager APIs.
 * @hide
 */
public class VpnManagerService extends IVpnManager.Stub {
    private static final String TAG = VpnManagerService.class.getSimpleName();

    @VisibleForTesting
    protected final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final Context mContext;
    private final Context mUserAllContext;

    private final Dependencies mDeps;

    private final ConnectivityManager mCm;
    private final VpnProfileStore mVpnProfileStore;
    private final INetworkManagementService mNMS;
    private final INetd mNetd;
    private final UserManager mUserManager;

    @VisibleForTesting
    @GuardedBy("mVpns")
    protected final SparseArray<Vpn> mVpns = new SparseArray<>();

    // TODO: investigate if mLockdownEnabled can be removed and replaced everywhere by
    // a direct call to LockdownVpnTracker.isEnabled().
    @GuardedBy("mVpns")
    private boolean mLockdownEnabled;
    @GuardedBy("mVpns")
    private LockdownVpnTracker mLockdownTracker;

    /**
     * Dependencies of VpnManager, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** Returns the calling UID of an IPC. */
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        /** Creates a HandlerThread to be used by this class. */
        public HandlerThread makeHandlerThread() {
            return new HandlerThread("VpnManagerService");
        }

        /** Return the VpnProfileStore to be used by this class */
        public VpnProfileStore getVpnProfileStore() {
            return new VpnProfileStore();
        }

        public INetd getNetd() {
            return NetdService.getInstance();
        }

        public INetworkManagementService getINetworkManagementService() {
            return INetworkManagementService.Stub.asInterface(
                    ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        }
    }

    public VpnManagerService(Context context, Dependencies deps) {
        mContext = context;
        mDeps = deps;
        mHandlerThread = mDeps.makeHandlerThread();
        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
        mVpnProfileStore = mDeps.getVpnProfileStore();
        mUserAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);
        mCm = mContext.getSystemService(ConnectivityManager.class);
        mNMS = mDeps.getINetworkManagementService();
        mNetd = mDeps.getNetd();
        mUserManager = mContext.getSystemService(UserManager.class);
        registerReceivers();
        log("VpnManagerService starting up");
    }

    /** Creates a new VpnManagerService */
    public static VpnManagerService create(Context context) {
        return new VpnManagerService(context, new Dependencies());
    }

    /** Informs the service that the system is ready. */
    public void systemReady() {
        // Try bringing up tracker, but KeyStore won't be ready yet for secondary users so wait
        // for user to unlock device too.
        updateLockdownVpn();
    }

    @Override
    /** Dumps service state. */
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println("VPNs:");
        pw.increaseIndent();
        synchronized (mVpns) {
            for (int i = 0; i < mVpns.size(); i++) {
                pw.println(mVpns.keyAt(i) + ": " + mVpns.valueAt(i).getPackage());
            }
            pw.decreaseIndent();
        }
    }

    /**
     * Prepare for a VPN application.
     * VPN permissions are checked in the {@link Vpn} class. If the caller is not {@code userId},
     * {@link android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * @param oldPackage Package name of the application which currently controls VPN, which will
     *                   be replaced. If there is no such application, this should should either be
     *                   {@code null} or {@link VpnConfig.LEGACY_VPN}.
     * @param newPackage Package name of the application which should gain control of VPN, or
     *                   {@code null} to disable.
     * @param userId User for whom to prepare the new VPN.
     *
     * @hide
     */
    @Override
    public boolean prepareVpn(@Nullable String oldPackage, @Nullable String newPackage,
            int userId) {
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            throwIfLockdownEnabled();
            Vpn vpn = mVpns.get(userId);
            if (vpn != null) {
                return vpn.prepare(oldPackage, newPackage, VpnManager.TYPE_VPN_SERVICE);
            } else {
                return false;
            }
        }
    }

    /**
     * Set whether the VPN package has the ability to launch VPNs without user intervention. This
     * method is used by system-privileged apps. VPN permissions are checked in the {@link Vpn}
     * class. If the caller is not {@code userId}, {@link
     * android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * @param packageName The package for which authorization state should change.
     * @param userId User for whom {@code packageName} is installed.
     * @param vpnType The {@link VpnManager.VpnType} constant representing what class of VPN
     *     permissions should be granted. When unauthorizing an app, {@link
     *     VpnManager.TYPE_VPN_NONE} should be used.
     * @hide
     */
    @Override
    public void setVpnPackageAuthorization(
            String packageName, int userId, @VpnManager.VpnType int vpnType) {
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn != null) {
                vpn.setPackageAuthorization(packageName, vpnType);
            }
        }
    }

    /**
     * Configure a TUN interface and return its file descriptor. Parameters
     * are encoded and opaque to this class. This method is used by VpnBuilder
     * and not available in VpnManager. Permissions are checked in
     * Vpn class.
     * @hide
     */
    @Override
    public ParcelFileDescriptor establishVpn(VpnConfig config) {
        int user = UserHandle.getUserId(mDeps.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            return mVpns.get(user).establish(config);
        }
    }

    @Override
    public boolean addVpnAddress(String address, int prefixLength) {
        int user = UserHandle.getUserId(mDeps.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            return mVpns.get(user).addAddress(address, prefixLength);
        }
    }

    @Override
    public boolean removeVpnAddress(String address, int prefixLength) {
        int user = UserHandle.getUserId(mDeps.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            return mVpns.get(user).removeAddress(address, prefixLength);
        }
    }

    @Override
    public boolean setUnderlyingNetworksForVpn(Network[] networks) {
        int user = UserHandle.getUserId(mDeps.getCallingUid());
        final boolean success;
        synchronized (mVpns) {
            success = mVpns.get(user).setUnderlyingNetworks(networks);
        }
        return success;
    }

    /**
     * Stores the given VPN profile based on the provisioning package name.
     *
     * <p>If there is already a VPN profile stored for the provisioning package, this call will
     * overwrite the profile.
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @return {@code true} if user consent has already been granted, {@code false} otherwise.
     * @hide
     */
    @Override
    public boolean provisionVpnProfile(@NonNull VpnProfile profile, @NonNull String packageName) {
        final int user = UserHandle.getUserId(mDeps.getCallingUid());
        synchronized (mVpns) {
            return mVpns.get(user).provisionVpnProfile(packageName, profile);
        }
    }

    /**
     * Deletes the stored VPN profile for the provisioning package
     *
     * <p>If there are no profiles for the given package, this method will silently succeed.
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @hide
     */
    @Override
    public void deleteVpnProfile(@NonNull String packageName) {
        final int user = UserHandle.getUserId(mDeps.getCallingUid());
        synchronized (mVpns) {
            mVpns.get(user).deleteVpnProfile(packageName);
        }
    }

    /**
     * Starts the VPN based on the stored profile for the given package
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @throws IllegalArgumentException if no profile was found for the given package name.
     * @hide
     */
    @Override
    public void startVpnProfile(@NonNull String packageName) {
        final int user = UserHandle.getUserId(mDeps.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            mVpns.get(user).startVpnProfile(packageName);
        }
    }

    /**
     * Stops the Platform VPN if the provided package is running one.
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @hide
     */
    @Override
    public void stopVpnProfile(@NonNull String packageName) {
        final int user = UserHandle.getUserId(mDeps.getCallingUid());
        synchronized (mVpns) {
            mVpns.get(user).stopVpnProfile(packageName);
        }
    }

    /**
     * Start legacy VPN, controlling native daemons as needed. Creates a
     * secondary thread to perform connection work, returning quickly.
     */
    @Override
    public void startLegacyVpn(VpnProfile profile) {
        int user = UserHandle.getUserId(mDeps.getCallingUid());
        // Note that if the caller is not system (uid >= Process.FIRST_APPLICATION_UID),
        // the code might not work well since getActiveNetwork might return null if the uid is
        // blocked by NetworkPolicyManagerService.
        final LinkProperties egress = mCm.getLinkProperties(mCm.getActiveNetwork());
        if (egress == null) {
            throw new IllegalStateException("Missing active network connection");
        }
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            mVpns.get(user).startLegacyVpn(profile, null /* underlying */, egress);
        }
    }

    /**
     * Return the information of the ongoing legacy VPN. This method is used
     * by VpnSettings and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     */
    @Override
    public LegacyVpnInfo getLegacyVpnInfo(int userId) {
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            return mVpns.get(userId).getLegacyVpnInfo();
        }
    }

    /**
     * Returns the information of the ongoing VPN for {@code userId}. This method is used by
     * VpnDialogs and not available in ConnectivityManager.
     * Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public VpnConfig getVpnConfig(int userId) {
        enforceCrossUserPermission(userId);
        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn != null) {
                return vpn.getVpnConfig();
            } else {
                return null;
            }
        }
    }

    private boolean isLockdownVpnEnabled() {
        return mVpnProfileStore.get(Credentials.LOCKDOWN_VPN) != null;
    }

    @Override
    public boolean updateLockdownVpn() {
        // Allow the system UID for the system server and for Settings.
        // Also, for unit tests, allow the process that ConnectivityService is running in.
        if (mDeps.getCallingUid() != Process.SYSTEM_UID
                && Binder.getCallingPid() != Process.myPid()) {
            logw("Lockdown VPN only available to system process or AID_SYSTEM");
            return false;
        }

        synchronized (mVpns) {
            // Tear down existing lockdown if profile was removed
            mLockdownEnabled = isLockdownVpnEnabled();
            if (!mLockdownEnabled) {
                setLockdownTracker(null);
                return true;
            }

            byte[] profileTag = mVpnProfileStore.get(Credentials.LOCKDOWN_VPN);
            if (profileTag == null) {
                loge("Lockdown VPN configured but cannot be read from keystore");
                return false;
            }
            String profileName = new String(profileTag);
            final VpnProfile profile = VpnProfile.decode(
                    profileName, mVpnProfileStore.get(Credentials.VPN + profileName));
            if (profile == null) {
                loge("Lockdown VPN configured invalid profile " + profileName);
                setLockdownTracker(null);
                return true;
            }
            int user = UserHandle.getUserId(mDeps.getCallingUid());
            Vpn vpn = mVpns.get(user);
            if (vpn == null) {
                logw("VPN for user " + user + " not ready yet. Skipping lockdown");
                return false;
            }
            setLockdownTracker(
                    new LockdownVpnTracker(mContext, mHandler, vpn,  profile));
        }

        return true;
    }

    /**
     * Internally set new {@link LockdownVpnTracker}, shutting down any existing
     * {@link LockdownVpnTracker}. Can be {@code null} to disable lockdown.
     */
    @GuardedBy("mVpns")
    private void setLockdownTracker(LockdownVpnTracker tracker) {
        // Shutdown any existing tracker
        final LockdownVpnTracker existing = mLockdownTracker;
        // TODO: Add a trigger when the always-on VPN enable/disable to reevaluate and send the
        // necessary onBlockedStatusChanged callbacks.
        mLockdownTracker = null;
        if (existing != null) {
            existing.shutdown();
        }

        if (tracker != null) {
            mLockdownTracker = tracker;
            mLockdownTracker.init();
        }
    }

    /**
     * Throws if there is any currently running, always-on Legacy VPN.
     *
     * <p>The LockdownVpnTracker and mLockdownEnabled both track whether an always-on Legacy VPN is
     * running across the entire system. Tracking for app-based VPNs is done on a per-user,
     * per-package basis in Vpn.java
     */
    @GuardedBy("mVpns")
    private void throwIfLockdownEnabled() {
        if (mLockdownEnabled) {
            throw new IllegalStateException("Unavailable in lockdown mode");
        }
    }

    /**
     * Starts the always-on VPN {@link VpnService} for user {@param userId}, which should perform
     * some setup and then call {@code establish()} to connect.
     *
     * @return {@code true} if the service was started, the service was already connected, or there
     *         was no always-on VPN to start. {@code false} otherwise.
     */
    private boolean startAlwaysOnVpn(int userId) {
        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                // Shouldn't happen as all code paths that point here should have checked the Vpn
                // exists already.
                Log.wtf(TAG, "User " + userId + " has no Vpn configuration");
                return false;
            }

            return vpn.startAlwaysOnVpn();
        }
    }

    @Override
    public boolean isAlwaysOnVpnPackageSupported(int userId, String packageName) {
        enforceSettingsPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                logw("User " + userId + " has no Vpn configuration");
                return false;
            }
            return vpn.isAlwaysOnPackageSupported(packageName);
        }
    }

    @Override
    public boolean setAlwaysOnVpnPackage(
            int userId, String packageName, boolean lockdown, List<String> lockdownAllowlist) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            // Can't set always-on VPN if legacy VPN is already in lockdown mode.
            if (isLockdownVpnEnabled()) {
                return false;
            }

            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                logw("User " + userId + " has no Vpn configuration");
                return false;
            }
            if (!vpn.setAlwaysOnPackage(packageName, lockdown, lockdownAllowlist)) {
                return false;
            }
            if (!startAlwaysOnVpn(userId)) {
                vpn.setAlwaysOnPackage(null, false, null);
                return false;
            }
        }
        return true;
    }

    @Override
    public String getAlwaysOnVpnPackage(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                logw("User " + userId + " has no Vpn configuration");
                return null;
            }
            return vpn.getAlwaysOnPackage();
        }
    }

    @Override
    public boolean isVpnLockdownEnabled(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                logw("User " + userId + " has no Vpn configuration");
                return false;
            }
            return vpn.getLockdown();
        }
    }

    @Override
    public List<String> getVpnLockdownAllowlist(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                logw("User " + userId + " has no Vpn configuration");
                return null;
            }
            return vpn.getLockdownAllowlist();
        }
    }

    @GuardedBy("mVpns")
    private Vpn getVpnIfOwner() {
        return getVpnIfOwner(mDeps.getCallingUid());
    }

    // TODO: stop calling into Vpn.java and get this information from data in this class.
    @GuardedBy("mVpns")
    private Vpn getVpnIfOwner(int uid) {
        final int user = UserHandle.getUserId(uid);

        final Vpn vpn = mVpns.get(user);
        if (vpn == null) {
            return null;
        } else {
            final UnderlyingNetworkInfo info = vpn.getUnderlyingNetworkInfo();
            return (info == null || info.ownerUid != uid) ? null : vpn;
        }
    }

    private void registerReceivers() {
        // Set up the listener for user state for creating user VPNs.
        // Should run on mHandler to avoid any races.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_STARTED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        intentFilter.addAction(Intent.ACTION_USER_ADDED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);

        mUserAllContext.registerReceiver(
                mIntentReceiver,
                intentFilter,
                null /* broadcastPermission */,
                mHandler);
        mContext.createContextAsUser(UserHandle.SYSTEM, 0 /* flags */).registerReceiver(
                mUserPresentReceiver,
                new IntentFilter(Intent.ACTION_USER_PRESENT),
                null /* broadcastPermission */,
                mHandler /* scheduler */);

        // Listen to package add and removal events for all users.
        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mUserAllContext.registerReceiver(
                mIntentReceiver,
                intentFilter,
                null /* broadcastPermission */,
                mHandler);

        // Listen to lockdown VPN reset.
        intentFilter = new IntentFilter();
        intentFilter.addAction(LockdownVpnTracker.ACTION_LOCKDOWN_RESET);
        mUserAllContext.registerReceiver(
                mIntentReceiver, intentFilter, NETWORK_STACK, mHandler);
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ensureRunningOnHandlerThread();
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            final Uri packageData = intent.getData();
            final String packageName =
                    packageData != null ? packageData.getSchemeSpecificPart() : null;

            if (LockdownVpnTracker.ACTION_LOCKDOWN_RESET.equals(action)) {
                onVpnLockdownReset();
            }

            // UserId should be filled for below intents, check the existence.
            if (userId == UserHandle.USER_NULL) return;

            if (Intent.ACTION_USER_STARTED.equals(action)) {
                onUserStarted(userId);
            } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                onUserStopped(userId);
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                onUserAdded(userId);
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                onUserRemoved(userId);
            } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                onUserUnlocked(userId);
            } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                onPackageReplaced(packageName, uid);
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                final boolean isReplacing = intent.getBooleanExtra(
                        Intent.EXTRA_REPLACING, false);
                onPackageRemoved(packageName, uid, isReplacing);
            } else {
                Log.wtf(TAG, "received unexpected intent: " + action);
            }
        }
    };

    private BroadcastReceiver mUserPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ensureRunningOnHandlerThread();
            // Try creating lockdown tracker, since user present usually means
            // unlocked keystore.
            updateLockdownVpn();
            // Use the same context that registered receiver before to unregister it. Because use
            // different context to unregister receiver will cause exception.
            context.unregisterReceiver(this);
        }
    };

    private void onUserStarted(int userId) {
        synchronized (mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn != null) {
                loge("Starting user already has a VPN");
                return;
            }
            userVpn = new Vpn(mHandler.getLooper(), mContext, mNMS, mNetd, userId,
                    new VpnProfileStore());
            mVpns.put(userId, userVpn);
            if (mUserManager.getUserInfo(userId).isPrimary() && isLockdownVpnEnabled()) {
                updateLockdownVpn();
            }
        }
    }

    private void onUserStopped(int userId) {
        synchronized (mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn == null) {
                loge("Stopped user has no VPN");
                return;
            }
            userVpn.onUserStopped();
            mVpns.delete(userId);
        }
    }

    @Override
    public boolean isCallerCurrentAlwaysOnVpnApp() {
        synchronized (mVpns) {
            Vpn vpn = getVpnIfOwner();
            return vpn != null && vpn.getAlwaysOn();
        }
    }

    @Override
    public boolean isCallerCurrentAlwaysOnVpnLockdownApp() {
        synchronized (mVpns) {
            Vpn vpn = getVpnIfOwner();
            return vpn != null && vpn.getLockdown();
        }
    }


    private void onUserAdded(int userId) {
        synchronized (mVpns) {
            final int vpnsSize = mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = mVpns.valueAt(i);
                vpn.onUserAdded(userId);
            }
        }
    }

    private void onUserRemoved(int userId) {
        synchronized (mVpns) {
            final int vpnsSize = mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = mVpns.valueAt(i);
                vpn.onUserRemoved(userId);
            }
        }
    }

    private void onPackageReplaced(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            Log.wtf(TAG, "Invalid package in onPackageReplaced: " + packageName + " | " + uid);
            return;
        }
        final int userId = UserHandle.getUserId(uid);
        synchronized (mVpns) {
            final Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                return;
            }
            // Legacy always-on VPN won't be affected since the package name is not set.
            if (TextUtils.equals(vpn.getAlwaysOnPackage(), packageName)) {
                log("Restarting always-on VPN package " + packageName + " for user "
                        + userId);
                vpn.startAlwaysOnVpn();
            }
        }
    }

    private void onPackageRemoved(String packageName, int uid, boolean isReplacing) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            Log.wtf(TAG, "Invalid package in onPackageRemoved: " + packageName + " | " + uid);
            return;
        }

        final int userId = UserHandle.getUserId(uid);
        synchronized (mVpns) {
            final Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                return;
            }
            // Legacy always-on VPN won't be affected since the package name is not set.
            if (TextUtils.equals(vpn.getAlwaysOnPackage(), packageName) && !isReplacing) {
                log("Removing always-on VPN package " + packageName + " for user "
                        + userId);
                vpn.setAlwaysOnPackage(null, false, null);
            }
        }
    }

    private void onUserUnlocked(int userId) {
        synchronized (mVpns) {
            // User present may be sent because of an unlock, which might mean an unlocked keystore.
            if (mUserManager.getUserInfo(userId).isPrimary() && isLockdownVpnEnabled()) {
                updateLockdownVpn();
            } else {
                startAlwaysOnVpn(userId);
            }
        }
    }

    private void onVpnLockdownReset() {
        synchronized (mVpns) {
            if (mLockdownTracker != null) mLockdownTracker.reset();
        }
    }


    @Override
    public void factoryReset() {
        enforceSettingsPermission();

        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_NETWORK_RESET)
                || mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
            return;
        }

        // Remove always-on package
        final int userId = UserHandle.getCallingUserId();
        synchronized (mVpns) {
            final String alwaysOnPackage = getAlwaysOnVpnPackage(userId);
            if (alwaysOnPackage != null) {
                setAlwaysOnVpnPackage(userId, null, false, null);
                setVpnPackageAuthorization(alwaysOnPackage, userId, VpnManager.TYPE_VPN_NONE);
            }

            // Turn Always-on VPN off
            if (mLockdownEnabled && userId == UserHandle.USER_SYSTEM) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mVpnProfileStore.remove(Credentials.LOCKDOWN_VPN);
                    mLockdownEnabled = false;
                    setLockdownTracker(null);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            // Turn VPN off
            VpnConfig vpnConfig = getVpnConfig(userId);
            if (vpnConfig != null) {
                if (vpnConfig.legacy) {
                    prepareVpn(VpnConfig.LEGACY_VPN, VpnConfig.LEGACY_VPN, userId);
                } else {
                    // Prevent this app (packagename = vpnConfig.user) from initiating
                    // VPN connections in the future without user intervention.
                    setVpnPackageAuthorization(
                            vpnConfig.user, userId, VpnManager.TYPE_VPN_NONE);

                    prepareVpn(null, VpnConfig.LEGACY_VPN, userId);
                }
            }
        }
    }

    private void ensureRunningOnHandlerThread() {
        if (mHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on VpnManagerService thread: "
                            + Thread.currentThread().getName());
        }
    }

    private void enforceControlAlwaysOnVpnPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_ALWAYS_ON_VPN,
                "VpnManagerService");
    }

    /**
     * Require that the caller is either in the same user or has appropriate permission to interact
     * across users.
     *
     * @param userId Target user for whatever operation the current IPC is supposed to perform.
     */
    private void enforceCrossUserPermission(int userId) {
        if (userId == UserHandle.getCallingUserId()) {
            // Not a cross-user call.
            return;
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                "VpnManagerService");
    }

    private void enforceSettingsPermission() {
        enforceAnyPermissionOf(mContext,
                android.Manifest.permission.NETWORK_SETTINGS,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void logw(String s) {
        Log.w(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
