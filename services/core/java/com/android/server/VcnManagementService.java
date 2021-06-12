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

import static android.Manifest.permission.DUMP;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_ACTIVE;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_INACTIVE;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_NOT_CONFIGURED;
import static android.net.vcn.VcnManager.VCN_STATUS_CODE_SAFE_MODE;

import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import static com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionTrackerCallback;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.vcn.IVcnManagementService;
import android.net.vcn.IVcnStatusCallback;
import android.net.vcn.IVcnUnderlyingNetworkPolicyListener;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnManager.VcnErrorCode;
import android.net.vcn.VcnManager.VcnStatusCode;
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
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.LocationPermissionChecker;
import com.android.net.module.util.PermissionUtils;
import com.android.server.vcn.TelephonySubscriptionTracker;
import com.android.server.vcn.Vcn;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.VcnNetworkProvider;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
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
// TODO(b/180451994): ensure all incoming + outgoing calls have a cleared calling identity
public class VcnManagementService extends IVcnManagementService.Stub {
    @NonNull private static final String TAG = VcnManagementService.class.getSimpleName();
    private static final long DUMP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final int LOCAL_LOG_LINE_COUNT = 128;

    // Public for use in all other VCN classes
    @NonNull public static final LocalLog LOCAL_LOG = new LocalLog(LOCAL_LOG_LINE_COUNT);

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
    @NonNull private final BroadcastReceiver mPkgChangeReceiver;

    @NonNull
    private final TrackingNetworkCallback mTrackingNetworkCallback = new TrackingNetworkCallback();

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

    @GuardedBy("mLock")
    @NonNull
    private final Map<IBinder, VcnStatusCallbackInfo> mRegisteredStatusCallbacks = new ArrayMap<>();

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

        mPkgChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                        || Intent.ACTION_PACKAGE_REPLACED.equals(action)
                        || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                    mTelephonySubscriptionTracker.handleSubscriptionsChanged();
                } else {
                    Log.wtf(TAG, "received unexpected intent: " + action);
                }
            }
        };

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiver(
                mPkgChangeReceiver, intentFilter, null /* broadcastPermission */, mHandler);

        // Run on handler to ensure I/O does not block system server startup
        mHandler.post(() -> {
            PersistableBundle configBundle = null;
            try {
                configBundle = mConfigDiskRwHelper.readFromDisk();
            } catch (IOException e1) {
                logErr("Failed to read configs from disk; retrying", e1);

                // Retry immediately. The IOException may have been transient.
                try {
                    configBundle = mConfigDiskRwHelper.readFromDisk();
                } catch (IOException e2) {
                    logWtf("Failed to read configs from disk", e2);
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
                @NonNull VcnNetworkProvider vcnNetworkProvider,
                boolean isInTestMode) {
            return new VcnContext(context, looper, vcnNetworkProvider, isInTestMode);
        }

        /** Creates a new Vcn instance using the provided configuration */
        public Vcn newVcn(
                @NonNull VcnContext vcnContext,
                @NonNull ParcelUuid subscriptionGroup,
                @NonNull VcnConfig config,
                @NonNull TelephonySubscriptionSnapshot snapshot,
                @NonNull VcnCallback vcnCallback) {
            return new Vcn(vcnContext, subscriptionGroup, config, snapshot, vcnCallback);
        }

        /** Gets the subId indicated by the given {@link WifiInfo}. */
        public int getSubIdForWifiInfo(@NonNull WifiInfo wifiInfo) {
            return wifiInfo.getSubscriptionId();
        }

        /** Creates a new LocationPermissionChecker for the provided Context. */
        public LocationPermissionChecker newLocationPermissionChecker(@NonNull Context context) {
            return new LocationPermissionChecker(context);
        }
    }

    /** Notifies the VcnManagementService that external dependencies can be set up. */
    public void systemReady() {
        mNetworkProvider.register();
        mContext.getSystemService(ConnectivityManager.class)
                .registerNetworkCallback(
                        new NetworkRequest.Builder().clearCapabilities().build(),
                        mTrackingNetworkCallback);
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

    private void enforceCallingUserAndCarrierPrivilege(
            ParcelUuid subscriptionGroup, String pkgName) {
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

        for (SubscriptionInfo info : subscriptionInfos) {
            final TelephonyManager telMgr = mContext.getSystemService(TelephonyManager.class)
                    .createForSubscriptionId(info.getSubscriptionId());

            // Check subscription is active first; much cheaper/faster check, and an app (currently)
            // cannot be carrier privileged for inactive subscriptions.
            if (subMgr.isValidSlotIndex(info.getSimSlotIndex())
                    && telMgr.checkCarrierPrivilegesForPackage(pkgName)
                            == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                // TODO (b/173717728): Allow configuration for inactive, but manageable
                // subscriptions.
                // TODO (b/173718661): Check for whole subscription groups at a time.
                return;
            }
        }

        throw new SecurityException(
                "Carrier privilege required for subscription group to set VCN Config");
    }

    private void enforceManageTestNetworksForTestMode(@NonNull VcnConfig vcnConfig) {
        if (vcnConfig.isTestModeProfile()) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.MANAGE_TEST_NETWORKS,
                    "Test-mode require the MANAGE_TEST_NETWORKS permission");
        }
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
                final TelephonySubscriptionSnapshot oldSnapshot = mLastSnapshot;
                mLastSnapshot = snapshot;
                logDbg("new snapshot: " + mLastSnapshot);

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
                                    stopVcnLocked(uuidToTeardown);

                                    // TODO(b/181789060): invoke asynchronously after Vcn notifies
                                    // through VcnCallback
                                    notifyAllPermissionedStatusCallbacksLocked(
                                            uuidToTeardown, VCN_STATUS_CODE_INACTIVE);
                                }
                            }
                        }, instanceToTeardown, CARRIER_PRIVILEGES_LOST_TEARDOWN_DELAY_MS);
                    } else {
                        // If this VCN's status has not changed, update it with the new snapshot
                        entry.getValue().updateSubscriptionSnapshot(mLastSnapshot);
                    }
                }

                final Map<ParcelUuid, Set<Integer>> oldSubGrpMappings =
                        getSubGroupToSubIdMappings(oldSnapshot);
                final Map<ParcelUuid, Set<Integer>> currSubGrpMappings =
                        getSubGroupToSubIdMappings(mLastSnapshot);
                if (!currSubGrpMappings.equals(oldSubGrpMappings)) {
                    notifyAllPolicyListenersLocked();
                }
            }
        }
    }

    @GuardedBy("mLock")
    private Map<ParcelUuid, Set<Integer>> getSubGroupToSubIdMappings(
            @NonNull TelephonySubscriptionSnapshot snapshot) {
        final Map<ParcelUuid, Set<Integer>> subGrpMappings = new ArrayMap<>();
        for (ParcelUuid subGrp : mVcns.keySet()) {
            subGrpMappings.put(subGrp, snapshot.getAllSubIdsInGroup(subGrp));
        }
        return subGrpMappings;
    }

    @GuardedBy("mLock")
    private void stopVcnLocked(@NonNull ParcelUuid uuidToTeardown) {
        final Vcn vcnToTeardown = mVcns.remove(uuidToTeardown);
        if (vcnToTeardown == null) {
            return;
        }

        vcnToTeardown.teardownAsynchronously();

        // Now that the VCN is removed, notify all registered listeners to refresh their
        // UnderlyingNetworkPolicy.
        notifyAllPolicyListenersLocked();
    }

    @GuardedBy("mLock")
    private void notifyAllPolicyListenersLocked() {
        for (final PolicyListenerBinderDeath policyListener : mRegisteredPolicyListeners.values()) {
            Binder.withCleanCallingIdentity(() -> policyListener.mListener.onPolicyChanged());
        }
    }

    @GuardedBy("mLock")
    private void notifyAllPermissionedStatusCallbacksLocked(
            @NonNull ParcelUuid subGroup, @VcnStatusCode int statusCode) {
        for (final VcnStatusCallbackInfo cbInfo : mRegisteredStatusCallbacks.values()) {
            if (isCallbackPermissioned(cbInfo, subGroup)) {
                Binder.withCleanCallingIdentity(
                        () -> cbInfo.mCallback.onVcnStatusChanged(statusCode));
            }
        }
    }

    @GuardedBy("mLock")
    private void startVcnLocked(@NonNull ParcelUuid subscriptionGroup, @NonNull VcnConfig config) {
        logDbg("Starting VCN config for subGrp: " + subscriptionGroup);

        // TODO(b/176939047): Support multiple VCNs active at the same time, or limit to one active
        //                    VCN.

        final VcnCallbackImpl vcnCallback = new VcnCallbackImpl(subscriptionGroup);

        final VcnContext vcnContext =
                mDeps.newVcnContext(
                        mContext, mLooper, mNetworkProvider, config.isTestModeProfile());
        final Vcn newInstance =
                mDeps.newVcn(vcnContext, subscriptionGroup, config, mLastSnapshot, vcnCallback);
        mVcns.put(subscriptionGroup, newInstance);

        // Now that a new VCN has started, notify all registered listeners to refresh their
        // UnderlyingNetworkPolicy.
        notifyAllPolicyListenersLocked();

        // TODO(b/181789060): invoke asynchronously after Vcn notifies through VcnCallback
        notifyAllPermissionedStatusCallbacksLocked(subscriptionGroup, VCN_STATUS_CODE_ACTIVE);
    }

    @GuardedBy("mLock")
    private void startOrUpdateVcnLocked(
            @NonNull ParcelUuid subscriptionGroup, @NonNull VcnConfig config) {
        logDbg("Starting or updating VCN config for subGrp: " + subscriptionGroup);

        if (mVcns.containsKey(subscriptionGroup)) {
            final Vcn vcn = mVcns.get(subscriptionGroup);
            vcn.updateConfig(config);
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
        logDbg("VCN config updated for subGrp: " + subscriptionGroup);

        mContext.getSystemService(AppOpsManager.class)
                .checkPackage(mDeps.getBinderCallingUid(), config.getProvisioningPackageName());
        enforceManageTestNetworksForTestMode(config);
        enforceCallingUserAndCarrierPrivilege(subscriptionGroup, opPkgName);

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
    public void clearVcnConfig(@NonNull ParcelUuid subscriptionGroup, @NonNull String opPkgName) {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");
        requireNonNull(opPkgName, "opPkgName was null");
        logDbg("VCN config cleared for subGrp: " + subscriptionGroup);

        mContext.getSystemService(AppOpsManager.class)
                .checkPackage(mDeps.getBinderCallingUid(), opPkgName);
        enforceCallingUserAndCarrierPrivilege(subscriptionGroup, opPkgName);

        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                mConfigs.remove(subscriptionGroup);
                final boolean vcnExists = mVcns.containsKey(subscriptionGroup);

                stopVcnLocked(subscriptionGroup);

                if (vcnExists) {
                    // TODO(b/181789060): invoke asynchronously after Vcn notifies through
                    // VcnCallback
                    notifyAllPermissionedStatusCallbacksLocked(
                            subscriptionGroup, VCN_STATUS_CODE_NOT_CONFIGURED);
                }

                writeConfigsToDiskLocked();
            }
        });
    }

    /**
     * Retrieves the list of subscription groups with configured VcnConfigs
     *
     * <p>Limited to subscription groups for which the caller is carrier privileged.
     *
     * <p>Implements the IVcnManagementService Binder interface.
     */
    @Override
    @NonNull
    public List<ParcelUuid> getConfiguredSubscriptionGroups(@NonNull String opPkgName) {
        requireNonNull(opPkgName, "opPkgName was null");

        mContext.getSystemService(AppOpsManager.class)
                .checkPackage(mDeps.getBinderCallingUid(), opPkgName);
        enforcePrimaryUser();

        final List<ParcelUuid> result = new ArrayList<>();
        synchronized (mLock) {
            for (ParcelUuid subGrp : mConfigs.keySet()) {
                if (mLastSnapshot.packageHasPermissionsForSubscriptionGroup(subGrp, opPkgName)) {
                    result.add(subGrp);
                }
            }
        }

        return result;
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
            logErr("Failed to save configs to disk", e);
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

    /** Get current VCNs for testing purposes */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public Map<ParcelUuid, Vcn> getAllVcns() {
        synchronized (mLock) {
            return Collections.unmodifiableMap(mVcns);
        }
    }

    /** Get current VcnStatusCallbacks for testing purposes. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public Map<IBinder, VcnStatusCallbackInfo> getAllStatusCallbacks() {
        synchronized (mLock) {
            return Collections.unmodifiableMap(mRegisteredStatusCallbacks);
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

        PermissionUtils.enforceAnyPermissionOf(
                mContext,
                android.Manifest.permission.NETWORK_FACTORY,
                android.Manifest.permission.MANAGE_TEST_NETWORKS);

        Binder.withCleanCallingIdentity(() -> {
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
        });
    }

    /** Removes the provided listener from receiving VcnUnderlyingNetworkPolicy updates. */
    @GuardedBy("mLock")
    @Override
    public void removeVcnUnderlyingNetworkPolicyListener(
            @NonNull IVcnUnderlyingNetworkPolicyListener listener) {
        requireNonNull(listener, "listener was null");

        PermissionUtils.enforceAnyPermissionOf(
                mContext,
                android.Manifest.permission.NETWORK_FACTORY,
                android.Manifest.permission.MANAGE_TEST_NETWORKS);

        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                PolicyListenerBinderDeath listenerBinderDeath =
                        mRegisteredPolicyListeners.remove(listener.asBinder());

                if (listenerBinderDeath != null) {
                    listener.asBinder().unlinkToDeath(listenerBinderDeath, 0 /* flags */);
                }
            }
        });
    }

    private ParcelUuid getSubGroupForNetworkCapabilities(
            @NonNull NetworkCapabilities networkCapabilities) {
        ParcelUuid subGrp = null;
        final TelephonySubscriptionSnapshot snapshot;

        // Always access mLastSnapshot under lock. Technically this can be treated as a volatile
        // but for consistency and safety, always access under lock.
        synchronized (mLock) {
            snapshot = mLastSnapshot;
        }

        // If multiple subscription IDs exist, they MUST all point to the same subscription
        // group. Otherwise undefined behavior may occur.
        for (int subId : networkCapabilities.getSubscriptionIds()) {
            // Verify that all subscriptions point to the same group
            if (subGrp != null && !subGrp.equals(snapshot.getGroupForSubId(subId))) {
                logWtf("Got multiple subscription groups for a single network");
            }

            subGrp = snapshot.getGroupForSubId(subId);
        }

        return subGrp;
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

        PermissionUtils.enforceAnyPermissionOf(
                mContext,
                android.Manifest.permission.NETWORK_FACTORY,
                android.Manifest.permission.MANAGE_TEST_NETWORKS);

        final boolean isUsingManageTestNetworks =
                mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_FACTORY)
                        != PackageManager.PERMISSION_GRANTED;

        if (isUsingManageTestNetworks && !networkCapabilities.hasTransport(TRANSPORT_TEST)) {
            throw new IllegalStateException(
                    "NetworkCapabilities must be for Test Network if using permission"
                            + " MANAGE_TEST_NETWORKS");
        }

        return Binder.withCleanCallingIdentity(() -> {
            // Defensive copy in case this call is in-process and the given NetworkCapabilities
            // mutates
            final NetworkCapabilities ncCopy = new NetworkCapabilities(networkCapabilities);

            final ParcelUuid subGrp = getSubGroupForNetworkCapabilities(ncCopy);
            boolean isVcnManagedNetwork = false;
            boolean isRestrictedCarrierWifi = false;
            synchronized (mLock) {
                final Vcn vcn = mVcns.get(subGrp);
                if (vcn != null) {
                    if (vcn.getStatus() == VCN_STATUS_CODE_ACTIVE) {
                        isVcnManagedNetwork = true;
                    }

                    if (ncCopy.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        // Carrier WiFi always restricted if VCN exists (even in safe mode).
                        isRestrictedCarrierWifi = true;
                    }
                }
            }

            final NetworkCapabilities.Builder ncBuilder = new NetworkCapabilities.Builder(ncCopy);

            if (isVcnManagedNetwork) {
                ncBuilder.removeCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
            } else {
                ncBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
            }

            if (isRestrictedCarrierWifi) {
                ncBuilder.removeCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            }

            final NetworkCapabilities result = ncBuilder.build();
            final VcnUnderlyingNetworkPolicy policy = new VcnUnderlyingNetworkPolicy(
                    mTrackingNetworkCallback.requiresRestartForCarrierWifi(result), result);

            logVdbg("getUnderlyingNetworkPolicy() called for caps: " + networkCapabilities
                        + "; and lp: " + linkProperties + "; result = " + policy);
            return policy;
        });
    }

    /** Binder death recipient used to remove registered VcnStatusCallbacks. */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    class VcnStatusCallbackInfo implements Binder.DeathRecipient {
        @NonNull final ParcelUuid mSubGroup;
        @NonNull final IVcnStatusCallback mCallback;
        @NonNull final String mPkgName;
        final int mUid;

        private VcnStatusCallbackInfo(
                @NonNull ParcelUuid subGroup,
                @NonNull IVcnStatusCallback callback,
                @NonNull String pkgName,
                int uid) {
            mSubGroup = subGroup;
            mCallback = callback;
            mPkgName = pkgName;
            mUid = uid;
        }

        @Override
        public void binderDied() {
            Log.e(TAG, "app died without unregistering VcnStatusCallback");
            unregisterVcnStatusCallback(mCallback);
        }
    }

    private boolean isCallbackPermissioned(
            @NonNull VcnStatusCallbackInfo cbInfo, @NonNull ParcelUuid subgroup) {
        if (!subgroup.equals(cbInfo.mSubGroup)) {
            return false;
        }

        if (!mLastSnapshot.packageHasPermissionsForSubscriptionGroup(subgroup, cbInfo.mPkgName)) {
            return false;
        }

        return true;
    }

    /** Registers the provided callback for receiving VCN status updates. */
    @Override
    public void registerVcnStatusCallback(
            @NonNull ParcelUuid subGroup,
            @NonNull IVcnStatusCallback callback,
            @NonNull String opPkgName) {
        final int callingUid = mDeps.getBinderCallingUid();
        final long identity = Binder.clearCallingIdentity();
        try {
            requireNonNull(subGroup, "subGroup must not be null");
            requireNonNull(callback, "callback must not be null");
            requireNonNull(opPkgName, "opPkgName must not be null");

            mContext.getSystemService(AppOpsManager.class).checkPackage(callingUid, opPkgName);

            final IBinder cbBinder = callback.asBinder();
            final VcnStatusCallbackInfo cbInfo =
                    new VcnStatusCallbackInfo(subGroup, callback, opPkgName, callingUid);

            try {
                cbBinder.linkToDeath(cbInfo, 0 /* flags */);
            } catch (RemoteException e) {
                // Remote binder already died - don't add to mRegisteredStatusCallbacks and exit
                return;
            }

            synchronized (mLock) {
                if (mRegisteredStatusCallbacks.containsKey(cbBinder)) {
                    throw new IllegalStateException(
                            "Attempting to register a callback that is already in use");
                }

                mRegisteredStatusCallbacks.put(cbBinder, cbInfo);

                // now that callback is registered, send it the VCN's current status
                final VcnConfig vcnConfig = mConfigs.get(subGroup);
                final Vcn vcn = mVcns.get(subGroup);
                final int vcnStatus =
                        vcn == null ? VCN_STATUS_CODE_NOT_CONFIGURED : vcn.getStatus();
                final int resultStatus;
                if (vcnConfig == null || !isCallbackPermissioned(cbInfo, subGroup)) {
                    resultStatus = VCN_STATUS_CODE_NOT_CONFIGURED;
                } else if (vcn == null) {
                    resultStatus = VCN_STATUS_CODE_INACTIVE;
                } else if (vcnStatus == VCN_STATUS_CODE_ACTIVE
                        || vcnStatus == VCN_STATUS_CODE_SAFE_MODE) {
                    resultStatus = vcnStatus;
                } else {
                    logWtf("Unknown VCN status: " + vcnStatus);
                    resultStatus = VCN_STATUS_CODE_NOT_CONFIGURED;
                }

                try {
                    cbInfo.mCallback.onVcnStatusChanged(resultStatus);
                } catch (RemoteException e) {
                    logDbg("VcnStatusCallback threw on VCN status change", e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** Unregisters the provided callback from receiving future VCN status updates. */
    @Override
    public void unregisterVcnStatusCallback(@NonNull IVcnStatusCallback callback) {
        final long identity = Binder.clearCallingIdentity();
        try {
            requireNonNull(callback, "callback must not be null");

            final IBinder cbBinder = callback.asBinder();
            synchronized (mLock) {
                VcnStatusCallbackInfo cbInfo = mRegisteredStatusCallbacks.remove(cbBinder);

                if (cbInfo != null) {
                    cbBinder.unlinkToDeath(cbInfo, 0 /* flags */);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void logVdbg(String msg) {
        if (VDBG) {
            Slog.v(TAG, msg);
            LOCAL_LOG.log(TAG + " VDBG: " + msg);
        }
    }

    private void logDbg(String msg) {
        Slog.d(TAG, msg);
        LOCAL_LOG.log(TAG + " DBG: " + msg);
    }

    private void logDbg(String msg, Throwable tr) {
        Slog.d(TAG, msg, tr);
        LOCAL_LOG.log(TAG + " DBG: " + msg + tr);
    }

    private void logErr(String msg) {
        Slog.e(TAG, msg);
        LOCAL_LOG.log(TAG + " ERR: " + msg);
    }

    private void logErr(String msg, Throwable tr) {
        Slog.e(TAG, msg, tr);
        LOCAL_LOG.log(TAG + " ERR: " + msg + tr);
    }

    private void logWtf(String msg) {
        Slog.wtf(TAG, msg);
        LOCAL_LOG.log(TAG + " WTF: " + msg);
    }

    private void logWtf(String msg, Throwable tr) {
        Slog.wtf(TAG, msg, tr);
        LOCAL_LOG.log(TAG + " WTF: " + msg + tr);
    }

    /**
     * Dumps the state of the VcnManagementService for logging and debugging purposes.
     *
     * <p>PII and credentials MUST NEVER be dumped here.
     */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "| ");

        // Post to handler thread to prevent ConcurrentModificationExceptions, and avoid lock-hell.
        mHandler.runWithScissors(() -> {
            mNetworkProvider.dump(pw);
            pw.println();

            mTrackingNetworkCallback.dump(pw);
            pw.println();

            synchronized (mLock) {
                mLastSnapshot.dump(pw);
                pw.println();

                pw.println("mConfigs:");
                pw.increaseIndent();
                for (Entry<ParcelUuid, VcnConfig> entry : mConfigs.entrySet()) {
                    pw.println(entry.getKey() + ": "
                            + entry.getValue().getProvisioningPackageName());
                }
                pw.decreaseIndent();
                pw.println();

                pw.println("mVcns:");
                pw.increaseIndent();
                for (Vcn vcn : mVcns.values()) {
                    vcn.dump(pw);
                }
                pw.decreaseIndent();
                pw.println();
            }

            pw.println("Local log:");
            pw.increaseIndent();
            LOCAL_LOG.dump(pw);
            pw.decreaseIndent();
            pw.println();
        }, DUMP_TIMEOUT_MILLIS);
    }

    // TODO(b/180452282): Make name more generic and implement directly with VcnManagementService
    /** Callback for Vcn signals sent up to VcnManagementService. */
    public interface VcnCallback {
        /** Called by a Vcn to signal that its safe mode status has changed. */
        void onSafeModeStatusChanged(boolean isInSafeMode);

        /** Called by a Vcn to signal that an error occurred. */
        void onGatewayConnectionError(
                @NonNull String gatewayConnectionName,
                @VcnErrorCode int errorCode,
                @Nullable String exceptionClass,
                @Nullable String exceptionMessage);
    }

    /**
     * TrackingNetworkCallback tracks all active networks
     *
     * <p>This is used to ensure that no underlying networks have immutable capabilities changed
     * without requiring a Network restart.
     */
    private class TrackingNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final Map<Network, NetworkCapabilities> mCaps = new ArrayMap<>();

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            synchronized (mCaps) {
                mCaps.put(network, caps);
            }
        }

        @Override
        public void onLost(Network network) {
            synchronized (mCaps) {
                mCaps.remove(network);
            }
        }

        private boolean requiresRestartForCarrierWifi(NetworkCapabilities caps) {
            if (!caps.hasTransport(TRANSPORT_WIFI) || caps.getSubscriptionIds() == null) {
                return false;
            }

            synchronized (mCaps) {
                for (NetworkCapabilities existing : mCaps.values()) {
                    if (existing.hasTransport(TRANSPORT_WIFI)
                            && caps.getSubscriptionIds().equals(existing.getSubscriptionIds())) {
                        // Restart if any immutable capabilities have changed
                        return existing.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)
                                != caps.hasCapability(NET_CAPABILITY_NOT_RESTRICTED);
                    }
                }
            }

            return false;
        }

        /** Dumps the state of this snapshot for logging and debugging purposes. */
        public void dump(IndentingPrintWriter pw) {
            pw.println("TrackingNetworkCallback:");
            pw.increaseIndent();

            pw.println("mCaps:");
            pw.increaseIndent();
            synchronized (mCaps) {
                for (Entry<Network, NetworkCapabilities> entry : mCaps.entrySet()) {
                    pw.println(entry.getKey() + ": " + entry.getValue());
                }
            }
            pw.decreaseIndent();
            pw.println();

            pw.decreaseIndent();
        }
    }

    /** VcnCallbackImpl for Vcn signals sent up to VcnManagementService. */
    private class VcnCallbackImpl implements VcnCallback {
        @NonNull private final ParcelUuid mSubGroup;

        private VcnCallbackImpl(@NonNull final ParcelUuid subGroup) {
            mSubGroup = Objects.requireNonNull(subGroup, "Missing subGroup");
        }

        @Override
        public void onSafeModeStatusChanged(boolean isInSafeMode) {
            synchronized (mLock) {
                // Ignore if this subscription group doesn't exist anymore
                if (!mVcns.containsKey(mSubGroup)) {
                    return;
                }

                final int status =
                        isInSafeMode ? VCN_STATUS_CODE_SAFE_MODE : VCN_STATUS_CODE_ACTIVE;

                notifyAllPolicyListenersLocked();
                notifyAllPermissionedStatusCallbacksLocked(mSubGroup, status);
            }
        }

        @Override
        public void onGatewayConnectionError(
                @NonNull String gatewayConnectionName,
                @VcnErrorCode int errorCode,
                @Nullable String exceptionClass,
                @Nullable String exceptionMessage) {
            synchronized (mLock) {
                // Ignore if this subscription group doesn't exist anymore
                if (!mVcns.containsKey(mSubGroup)) {
                    return;
                }

                // Notify all registered StatusCallbacks for this subGroup
                for (VcnStatusCallbackInfo cbInfo : mRegisteredStatusCallbacks.values()) {
                    if (isCallbackPermissioned(cbInfo, mSubGroup)) {
                        Binder.withCleanCallingIdentity(
                                () ->
                                        cbInfo.mCallback.onGatewayConnectionError(
                                                gatewayConnectionName,
                                                errorCode,
                                                exceptionClass,
                                                exceptionMessage));
                    }
                }
            }
        }
    }
}
