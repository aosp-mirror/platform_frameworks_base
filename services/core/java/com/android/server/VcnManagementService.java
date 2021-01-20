/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionTrackerCallback;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.IVcnManagementService;
import android.net.vcn.IVcnUnderlyingNetworkPolicyListener;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnUnderlyingNetworkPolicy;
import android.net.wifi.WifiInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.TelephonySubscriptionTracker;
import com.android.server.vcn.Vcn;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.VcnNetworkProvider;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * VcnManagementService manages Virtual Carrier Network profiles and lifecycles.
 *
 * <pre>The internal structure of the VCN Management subsystem is as follows:
 *
 * +-------------------------+ 1:1                                +--------------------------------+
 * |  VcnManagementService   | ------------ Creates ------------> |  TelephonySubscriptionManager  |
 * |                         |                                    |                                |
 * |   Manages configs and   |                                    | Tracks subscriptions, carrier  |
 * | Vcn instance lifecycles | <--- Notifies of subscription & -- | privilege changes, caches maps |
 * +-------------------------+      carrier privilege changes     +--------------------------------+
 *      | 1:N          ^
 *      |              |
 *      |              +-------------------------------+
 *      +---------------+                              |
 *                      |                              |
 *         Creates when config present,                |
 *        subscription group active, and               |
 *      providing app is carrier privileged     Notifies of safe
 *                      |                      mode state changes
 *                      v                              |
 * +-----------------------------------------------------------------------+
 * |                                  Vcn                                  |
 * |                                                                       |
 * |       Manages GatewayConnection lifecycles based on fulfillable       |
 * |                NetworkRequest(s) and overall safe-mode                |
 * +-----------------------------------------------------------------------+
 *                      | 1:N                          ^
 *              Creates to fulfill                     |
 *           NetworkRequest(s), tears   Notifies of VcnGatewayConnection
 *          down when no longer needed   teardown (e.g. Network reaped)
 *                      |                 and safe-mode timer changes
 *                      v                              |
 * +-----------------------------------------------------------------------+
 * |                          VcnGatewayConnection                         |
 * |                                                                       |
 * |       Manages a single (IKEv2) tunnel session and NetworkAgent,       |
 * |  handles mobility events, (IPsec) Tunnel setup and safe-mode timers   |
 * +-----------------------------------------------------------------------+
 *                      | 1:1                          ^
 *                      |                              |
 *          Creates upon instantiation      Notifies of changes in
 *                      |                 selected underlying network
 *                      |                     or its properties
 *                      v                              |
 * +-----------------------------------------------------------------------+
 * |                       UnderlyingNetworkTracker                        |
 * |                                                                       |
 * | Manages lifecycle of underlying physical networks, filing requests to |
 * | bring them up, and releasing them as they become no longer necessary  |
 * +-----------------------------------------------------------------------+
 * </pre>
 *
 * @hide
 */
public class VcnManagementService extends IVcnManagementService.Stub {
    @NonNull private static final String TAG = VcnManagementService.class.getSimpleName();

    public static final boolean VDBG = false; // STOPSHIP: if true

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final String VCN_CONFIG_FILE = "/data/system/vcn/configs.xml";

    // TODO(b/176956496): Directly use CarrierServiceBindHelper.UNBIND_DELAY_MILLIS
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final long CARRIER_PRIVILEGES_LOST_TEARDOWN_DELAY_MS = TimeUnit.SECONDS.toMillis(30);

    /* Binder context for this service */
    @NonNull private final Context mContext;
    @NonNull private final Dependencies mDeps;

    @NonNull private final Looper mLooper;
    @NonNull private final Handler mHandler;
    @NonNull private final VcnNetworkProvider mNetworkProvider;
    @NonNull private final TelephonySubscriptionTrackerCallback mTelephonySubscriptionTrackerCb;
    @NonNull private final TelephonySubscriptionTracker mTelephonySubscriptionTracker;
    @NonNull private final VcnContext mVcnContext;

    @GuardedBy("mLock")
    @NonNull
    private final Map<ParcelUuid, VcnConfig> mConfigs = new ArrayMap<>();

    @GuardedBy("mLock")
    @NonNull
    private final Map<ParcelUuid, Vcn> mVcns = new ArrayMap<>();

    @GuardedBy("mLock")
    @NonNull
    private TelephonySubscriptionSnapshot mLastSnapshot =
            TelephonySubscriptionSnapshot.EMPTY_SNAPSHOT;

    @NonNull private final Object mLock = new Object();

    @NonNull private final PersistableBundleUtils.LockingReadWriteHelper mConfigDiskRwHelper;

    @GuardedBy("mLock")
    @NonNull
    private final Map<IBinder, PolicyListenerBinderDeath> mRegisteredPolicyListeners =
            new ArrayMap<>();

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    VcnManagementService(@NonNull Context context, @NonNull Dependencies deps) {
        mContext = requireNonNull(context, "Missing context");
        mDeps = requireNonNull(deps, "Missing dependencies");

        mLooper = mDeps.getLooper();
        mHandler = new Handler(mLooper);
        mNetworkProvider = new VcnNetworkProvider(mContext, mLooper);
        mTelephonySubscriptionTrackerCb = new VcnSubscriptionTrackerCallback();
        mTelephonySubscriptionTracker = mDeps.newTelephonySubscriptionTracker(
                mContext, mLooper, mTelephonySubscriptionTrackerCb);

        mConfigDiskRwHelper = mDeps.newPersistableBundleLockingReadWriteHelper(VCN_CONFIG_FILE);
        mVcnContext = mDeps.newVcnContext(mContext, mLooper, mNetworkProvider);

        // Run on handler to ensure I/O does not block system server startup
        mHandler.post(() -> {
            PersistableBundle configBundle = null;
            try {
                configBundle = mConfigDiskRwHelper.readFromDisk();
            } catch (IOException e1) {
                Slog.e(TAG, "Failed to read configs from disk; retrying", e1);

                // Retry immediately. The IOException may have been transient.
                try {
                    configBundle = mConfigDiskRwHelper.readFromDisk();
                } catch (IOException e2) {
                    Slog.wtf(TAG, "Failed to read configs from disk", e2);
                    return;
                }
            }

            if (configBundle != null) {
                final Map<ParcelUuid, VcnConfig> configs =
                        PersistableBundleUtils.toMap(
                                configBundle,
                                PersistableBundleUtils::toParcelUuid,
                                VcnConfig::new);

                synchronized (mLock) {
                    for (Entry<ParcelUuid, VcnConfig> entry : configs.entrySet()) {
                        // Ensure no new configs are overwritten; a carrier app may have added a new
                        // config.
                        if (!mConfigs.containsKey(entry.getKey())) {
                            mConfigs.put(entry.getKey(), entry.getValue());
                        }
                    }

                    // Re-evaluate subscriptions, and start/stop VCNs. This starts with an empty
                    // snapshot, and therefore safe even before telephony subscriptions are loaded.
                    mTelephonySubscriptionTrackerCb.onNewSnapshot(mLastSnapshot);
                }
            }
        });
    }

    // Package-visibility for SystemServer to create instances.
    static VcnManagementService create(@NonNull Context context) {
        return new VcnManagementService(context, new Dependencies());
    }

    /** External dependencies used by VcnManagementService, for injection in tests */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        private HandlerThread mHandlerThread;

        /** Retrieves a looper for the VcnManagementService */
        public Looper getLooper() {
            if (mHandlerThread == null) {
                synchronized (this) {
                    if (mHandlerThread == null) {
                        mHandlerThread = new HandlerThread(TAG);
                        mHandlerThread.start();
                    }
                }
            }
            return mHandlerThread.getLooper();
        }

        /** Creates a new VcnInstance using the provided configuration */
        public TelephonySubscriptionTracker newTelephonySubscriptionTracker(
                @NonNull Context context,
                @NonNull Looper looper,
                @NonNull TelephonySubscriptionTrackerCallback callback) {
            return new TelephonySubscriptionTracker(context, new Handler(looper), callback);
        }

        /**
         * Retrieves the caller's UID
         *
         * <p>This call MUST be made before calling {@link Binder#clearCallingIdentity}, otherwise
         * this will not work properly.
         *
         * @return
         */
        public int getBinderCallingUid() {
            return Binder.getCallingUid();
        }

        /**
         * Creates and returns a new {@link PersistableBundle.LockingReadWriteHelper}
         *
         * @param path the file path to read/write from/to.
         * @return the {@link PersistableBundleUtils.LockingReadWriteHelper} instance
         */
        public PersistableBundleUtils.LockingReadWriteHelper
                newPersistableBundleLockingReadWriteHelper(@NonNull String path) {
            return new PersistableBundleUtils.LockingReadWriteHelper(path);
        }

        /** Creates a new VcnContext */
        public VcnContext newVcnContext(
                @NonNull Context context,
                @NonNull Looper looper,
                @NonNull VcnNetworkProvider vcnNetworkProvider) {
            return new VcnContext(context, looper, vcnNetworkProvider);
        }

        /** Creates a new Vcn instance using the provided configuration */
        public Vcn newVcn(
                @NonNull VcnContext vcnContext,
                @NonNull ParcelUuid subscriptionGroup,
                @NonNull VcnConfig config,
                @NonNull TelephonySubscriptionSnapshot snapshot) {
            return new Vcn(vcnContext, subscriptionGroup, config, snapshot);
        }

        /** Gets the subId indicated by the given {@link WifiInfo}. */
        public int getSubIdForWifiInfo(@NonNull WifiInfo wifiInfo) {
            // TODO(b/178501049): use the subId indicated by WifiInfo#getSubscriptionId
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    /** Notifies the VcnManagementService that external dependencies can be set up. */
    public void systemReady() {
        mContext.getSystemService(ConnectivityManager.class)
                .registerNetworkProvider(mNetworkProvider);
        mTelephonySubscriptionTracker.register();
    }

    private void enforcePrimaryUser() {
        final int uid = mDeps.getBinderCallingUid();
        if (uid == Process.SYSTEM_UID) {
            throw new IllegalStateException(
                    "Calling identity was System Server. Was Binder calling identity cleared?");
        }

        if (!UserHandle.getUserHandleForUid(uid).isSystem()) {
            throw new SecurityException(
                    "VcnManagementService can only be used by callers running as the primary user");
        }
    }

    private void enforceCallingUserAndCarrierPrivilege(ParcelUuid subscriptionGroup) {
        // Only apps running in the primary (system) user are allowed to configure the VCN. This is
        // in line with Telephony's behavior with regards to binding to a Carrier App provided
        // CarrierConfigService.
        enforcePrimaryUser();

        // TODO (b/172619301): Check based on events propagated from CarrierPrivilegesTracker
        final SubscriptionManager subMgr = mContext.getSystemService(SubscriptionManager.class);
        final List<SubscriptionInfo> subscriptionInfos = new ArrayList<>();
        Binder.withCleanCallingIdentity(
                () -> {
                    subscriptionInfos.addAll(subMgr.getSubscriptionsInGroup(subscriptionGroup));
                });

        final TelephonyManager telMgr = mContext.getSystemService(TelephonyManager.class);
        for (SubscriptionInfo info : subscriptionInfos) {
            // Check subscription is active first; much cheaper/faster check, and an app (currently)
            // cannot be carrier privileged for inactive subscriptions.
            if (subMgr.isValidSlotIndex(info.getSimSlotIndex())
                    && telMgr.hasCarrierPrivileges(info.getSubscriptionId())) {
                // TODO (b/173717728): Allow configuration for inactive, but manageable
                // subscriptions.
                // TODO (b/173718661): Check for whole subscription groups at a time.
                return;
            }
        }

        throw new SecurityException(
                "Carrier privilege required for subscription group to set VCN Config");
    }

    private class VcnSubscriptionTrackerCallback implements TelephonySubscriptionTrackerCallback {
        /**
         * Handles subscription group changes, as notified by {@link TelephonySubscriptionTracker}
         *
         * <p>Start any unstarted VCN instances
         *
         * @hide
         */
        public void onNewSnapshot(@NonNull TelephonySubscriptionSnapshot snapshot) {
            // Startup VCN instances
            synchronized (mLock) {
                mLastSnapshot = snapshot;

                // Start any VCN instances as necessary
                for (Entry<ParcelUuid, VcnConfig> entry : mConfigs.entrySet()) {
                    if (snapshot.packageHasPermissionsForSubscriptionGroup(
                            entry.getKey(), entry.getValue().getProvisioningPackageName())) {
                        if (!mVcns.containsKey(entry.getKey())) {
                            startVcnLocked(entry.getKey(), entry.getValue());
                        }

                        // Cancel any scheduled teardowns for active subscriptions
                        mHandler.removeCallbacksAndMessages(mVcns.get(entry.getKey()));
                    }
                }

                // Schedule teardown of any VCN instances that have lost carrier privileges (after a
                // delay)
                for (Entry<ParcelUuid, Vcn> entry : mVcns.entrySet()) {
                    final VcnConfig config = mConfigs.get(entry.getKey());

                    if (config == null
                            || !snapshot.packageHasPermissionsForSubscriptionGroup(
                                    entry.getKey(), config.getProvisioningPackageName())) {
                        final ParcelUuid uuidToTeardown = entry.getKey();
                        final Vcn instanceToTeardown = entry.getValue();

                        mHandler.postDelayed(() -> {
                            synchronized (mLock) {
                                // Guard against case where this is run after a old instance was
                                // torn down, and a new instance was started. Verify to ensure
                                // correct instance is torn down. This could happen as a result of a
                                // Carrier App manually removing/adding a VcnConfig.
                                if (mVcns.get(uuidToTeardown) == instanceToTeardown) {
                                    mVcns.remove(uuidToTeardown).teardownAsynchronously();
                                }
                            }
                        }, instanceToTeardown, CARRIER_PRIVILEGES_LOST_TEARDOWN_DELAY_MS);
                    } else {
                        // If this VCN's status has not changed, update it with the new snapshot
                        entry.getValue().updateSubscriptionSnapshot(mLastSnapshot);
                    }
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void startVcnLocked(@NonNull ParcelUuid subscriptionGroup, @NonNull VcnConfig config) {
        Slog.v(TAG, "Starting VCN config for subGrp: " + subscriptionGroup);

        // TODO(b/176939047): Support multiple VCNs active at the same time, or limit to one active
        //                    VCN.

        final Vcn newInstance = mDeps.newVcn(mVcnContext, subscriptionGroup, config, mLastSnapshot);
        mVcns.put(subscriptionGroup, newInstance);
    }

    @GuardedBy("mLock")
    private void startOrUpdateVcnLocked(
            @NonNull ParcelUuid subscriptionGroup, @NonNull VcnConfig config) {
        Slog.v(TAG, "Starting or updating VCN config for subGrp: " + subscriptionGroup);

        if (mVcns.containsKey(subscriptionGroup)) {
            mVcns.get(subscriptionGroup).updateConfig(config);
        } else {
            startVcnLocked(subscriptionGroup, config);
        }
    }

    /**
     * Sets a VCN config for a given subscription group.
     *
     * <p>Implements the IVcnManagementService Binder interface.
     */
    @Override
    public void setVcnConfig(
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnConfig config,
            @NonNull String opPkgName) {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");
        requireNonNull(config, "config was null");
        requireNonNull(opPkgName, "opPkgName was null");
        if (!config.getProvisioningPackageName().equals(opPkgName)) {
            throw new IllegalArgumentException("Mismatched caller and VcnConfig creator");
        }
        Slog.v(TAG, "VCN config updated for subGrp: " + subscriptionGroup);

        mContext.getSystemService(AppOpsManager.class)
                .checkPackage(mDeps.getBinderCallingUid(), config.getProvisioningPackageName());
        enforceCallingUserAndCarrierPrivilege(subscriptionGroup);

        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                mConfigs.put(subscriptionGroup, config);
                startOrUpdateVcnLocked(subscriptionGroup, config);

                writeConfigsToDiskLocked();
            }
        });
    }

    /**
     * Clears the VcnManagementService for a given subscription group.
     *
     * <p>Implements the IVcnManagementService Binder interface.
     */
    @Override
    public void clearVcnConfig(@NonNull ParcelUuid subscriptionGroup) {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");
        Slog.v(TAG, "VCN config cleared for subGrp: " + subscriptionGroup);

        enforceCallingUserAndCarrierPrivilege(subscriptionGroup);

        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                mConfigs.remove(subscriptionGroup);

                if (mVcns.containsKey(subscriptionGroup)) {
                    mVcns.remove(subscriptionGroup).teardownAsynchronously();
                }

                writeConfigsToDiskLocked();
            }
        });
    }

    @GuardedBy("mLock")
    private void writeConfigsToDiskLocked() {
        try {
            PersistableBundle bundle =
                    PersistableBundleUtils.fromMap(
                            mConfigs,
                            PersistableBundleUtils::fromParcelUuid,
                            VcnConfig::toPersistableBundle);
            mConfigDiskRwHelper.writeToDisk(bundle);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save configs to disk", e);
            throw new ServiceSpecificException(0, "Failed to save configs");
        }
    }

    /** Get current configuration list for testing purposes */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    Map<ParcelUuid, VcnConfig> getConfigs() {
        synchronized (mLock) {
            return Collections.unmodifiableMap(mConfigs);
        }
    }

    /** Get current configuration list for testing purposes */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public Map<ParcelUuid, Vcn> getAllVcns() {
        synchronized (mLock) {
            return Collections.unmodifiableMap(mVcns);
        }
    }

    /** Binder death recipient used to remove a registered policy listener. */
    private class PolicyListenerBinderDeath implements Binder.DeathRecipient {
        @NonNull private final IVcnUnderlyingNetworkPolicyListener mListener;

        PolicyListenerBinderDeath(@NonNull IVcnUnderlyingNetworkPolicyListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            Log.e(TAG, "app died without removing VcnUnderlyingNetworkPolicyListener");
            removeVcnUnderlyingNetworkPolicyListener(mListener);
        }
    }

    /** Adds the provided listener for receiving VcnUnderlyingNetworkPolicy updates. */
    @GuardedBy("mLock")
    @Override
    public void addVcnUnderlyingNetworkPolicyListener(
            @NonNull IVcnUnderlyingNetworkPolicyListener listener) {
        requireNonNull(listener, "listener was null");

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_FACTORY,
                "Must have permission NETWORK_FACTORY to register a policy listener");

        PolicyListenerBinderDeath listenerBinderDeath = new PolicyListenerBinderDeath(listener);

        synchronized (mLock) {
            mRegisteredPolicyListeners.put(listener.asBinder(), listenerBinderDeath);

            try {
                listener.asBinder().linkToDeath(listenerBinderDeath, 0 /* flags */);
            } catch (RemoteException e) {
                // Remote binder already died - cleanup registered Listener
                listenerBinderDeath.binderDied();
            }
        }
    }

    /** Removes the provided listener from receiving VcnUnderlyingNetworkPolicy updates. */
    @GuardedBy("mLock")
    @Override
    public void removeVcnUnderlyingNetworkPolicyListener(
            @NonNull IVcnUnderlyingNetworkPolicyListener listener) {
        requireNonNull(listener, "listener was null");

        synchronized (mLock) {
            PolicyListenerBinderDeath listenerBinderDeath =
                    mRegisteredPolicyListeners.remove(listener.asBinder());

            if (listenerBinderDeath != null) {
                listener.asBinder().unlinkToDeath(listenerBinderDeath, 0 /* flags */);
            }
        }
    }

    /**
     * Gets the UnderlyingNetworkPolicy as determined by the provided NetworkCapabilities and
     * LinkProperties.
     */
    @NonNull
    @Override
    public VcnUnderlyingNetworkPolicy getUnderlyingNetworkPolicy(
            @NonNull NetworkCapabilities networkCapabilities,
            @NonNull LinkProperties linkProperties) {
        requireNonNull(networkCapabilities, "networkCapabilities was null");
        requireNonNull(linkProperties, "linkProperties was null");

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_FACTORY,
                "Must have permission NETWORK_FACTORY or be the SystemServer to get underlying"
                        + " Network policies");

        // Defensive copy in case this call is in-process and the given NetworkCapabilities mutates
        networkCapabilities = new NetworkCapabilities(networkCapabilities);

        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && networkCapabilities.getNetworkSpecifier() instanceof TelephonyNetworkSpecifier) {
            TelephonyNetworkSpecifier telephonyNetworkSpecifier =
                    (TelephonyNetworkSpecifier) networkCapabilities.getNetworkSpecifier();
            subId = telephonyNetworkSpecifier.getSubscriptionId();
        } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && networkCapabilities.getTransportInfo() instanceof WifiInfo) {
            WifiInfo wifiInfo = (WifiInfo) networkCapabilities.getTransportInfo();
            subId = mDeps.getSubIdForWifiInfo(wifiInfo);
        }

        boolean isVcnManagedNetwork = false;
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            synchronized (mLock) {
                ParcelUuid subGroup = mLastSnapshot.getGroupForSubId(subId);

                // TODO(b/178140910): only mark the Network as VCN-managed if not in safe mode
                if (mVcns.containsKey(subGroup)) {
                    isVcnManagedNetwork = true;
                }
            }
        }
        if (isVcnManagedNetwork) {
            networkCapabilities.removeCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
        }

        return new VcnUnderlyingNetworkPolicy(false /* isTearDownRequested */, networkCapabilities);
    }
}
