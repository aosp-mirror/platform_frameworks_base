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

package com.android.server.vcn.routeselection;

import static android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;

import static com.android.server.VcnManagementService.LOCAL_LOG;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.getWifiEntryRssiThreshold;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.getWifiExitRssiThreshold;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.isOpportunistic;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnUnderlyingNetworkTemplate;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.util.LogUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tracks a set of Networks underpinning a VcnGatewayConnection.
 *
 * <p>A single UnderlyingNetworkController is built to serve a SINGLE VCN Gateway Connection, and
 * MUST be torn down with the VcnGatewayConnection in order to ensure underlying networks are
 * allowed to be reaped.
 *
 * @hide
 */
public class UnderlyingNetworkController {
    @NonNull private static final String TAG = UnderlyingNetworkController.class.getSimpleName();

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final VcnGatewayConnectionConfig mConnectionConfig;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final UnderlyingNetworkControllerCallback mCb;
    @NonNull private final Dependencies mDeps;
    @NonNull private final Handler mHandler;
    @NonNull private final ConnectivityManager mConnectivityManager;
    @NonNull private final TelephonyCallback mActiveDataSubIdListener =
            new VcnActiveDataSubscriptionIdListener();

    @NonNull private final List<NetworkCallback> mCellBringupCallbacks = new ArrayList<>();
    @Nullable private NetworkCallback mWifiBringupCallback;
    @Nullable private NetworkCallback mWifiEntryRssiThresholdCallback;
    @Nullable private NetworkCallback mWifiExitRssiThresholdCallback;
    @Nullable private UnderlyingNetworkListener mRouteSelectionCallback;

    @NonNull private TelephonySubscriptionSnapshot mLastSnapshot;
    @Nullable private PersistableBundle mCarrierConfig;
    private boolean mIsQuitting = false;

    @Nullable private UnderlyingNetworkRecord mCurrentRecord;
    @Nullable private UnderlyingNetworkRecord.Builder mRecordInProgress;

    public UnderlyingNetworkController(
            @NonNull VcnContext vcnContext,
            @NonNull VcnGatewayConnectionConfig connectionConfig,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull UnderlyingNetworkControllerCallback cb) {
        this(vcnContext, connectionConfig, subscriptionGroup, snapshot, cb, new Dependencies());
    }

    private UnderlyingNetworkController(
            @NonNull VcnContext vcnContext,
            @NonNull VcnGatewayConnectionConfig connectionConfig,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull UnderlyingNetworkControllerCallback cb,
            @NonNull Dependencies deps) {
        mVcnContext = Objects.requireNonNull(vcnContext, "Missing vcnContext");
        mConnectionConfig = Objects.requireNonNull(connectionConfig, "Missing connectionConfig");
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mLastSnapshot = Objects.requireNonNull(snapshot, "Missing snapshot");
        mCb = Objects.requireNonNull(cb, "Missing cb");
        mDeps = Objects.requireNonNull(deps, "Missing deps");

        mHandler = new Handler(mVcnContext.getLooper());

        mConnectivityManager = mVcnContext.getContext().getSystemService(ConnectivityManager.class);
        mVcnContext
                .getContext()
                .getSystemService(TelephonyManager.class)
                .registerTelephonyCallback(new HandlerExecutor(mHandler), mActiveDataSubIdListener);

        // TODO: Listen for changes in carrier config that affect this.
        for (int subId : mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup)) {
            PersistableBundle config =
                    mVcnContext
                            .getContext()
                            .getSystemService(CarrierConfigManager.class)
                            .getConfigForSubId(subId);

            if (config != null) {
                mCarrierConfig = config;

                // Attempt to use (any) non-opportunistic subscription. If this subscription is
                // opportunistic, continue and try to find a non-opportunistic subscription, using
                // the opportunistic ones as a last resort.
                if (!isOpportunistic(mLastSnapshot, Collections.singleton(subId))) {
                    break;
                }
            }
        }

        registerOrUpdateNetworkRequests();
    }

    private void registerOrUpdateNetworkRequests() {
        NetworkCallback oldRouteSelectionCallback = mRouteSelectionCallback;
        NetworkCallback oldWifiCallback = mWifiBringupCallback;
        NetworkCallback oldWifiEntryRssiThresholdCallback = mWifiEntryRssiThresholdCallback;
        NetworkCallback oldWifiExitRssiThresholdCallback = mWifiExitRssiThresholdCallback;
        List<NetworkCallback> oldCellCallbacks = new ArrayList<>(mCellBringupCallbacks);
        mCellBringupCallbacks.clear();

        // Register new callbacks. Make-before-break; always register new callbacks before removal
        // of old callbacks
        if (!mIsQuitting) {
            mRouteSelectionCallback = new UnderlyingNetworkListener();
            mConnectivityManager.registerNetworkCallback(
                    getRouteSelectionRequest(), mRouteSelectionCallback, mHandler);

            mWifiEntryRssiThresholdCallback = new NetworkBringupCallback();
            mConnectivityManager.registerNetworkCallback(
                    getWifiEntryRssiThresholdNetworkRequest(),
                    mWifiEntryRssiThresholdCallback,
                    mHandler);

            mWifiExitRssiThresholdCallback = new NetworkBringupCallback();
            mConnectivityManager.registerNetworkCallback(
                    getWifiExitRssiThresholdNetworkRequest(),
                    mWifiExitRssiThresholdCallback,
                    mHandler);

            mWifiBringupCallback = new NetworkBringupCallback();
            mConnectivityManager.requestBackgroundNetwork(
                    getWifiNetworkRequest(), mWifiBringupCallback, mHandler);

            for (final int subId : mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup)) {
                final NetworkBringupCallback cb = new NetworkBringupCallback();
                mCellBringupCallbacks.add(cb);

                mConnectivityManager.requestBackgroundNetwork(
                        getCellNetworkRequestForSubId(subId), cb, mHandler);
            }
        } else {
            mRouteSelectionCallback = null;
            mWifiBringupCallback = null;
            mWifiEntryRssiThresholdCallback = null;
            mWifiExitRssiThresholdCallback = null;
            // mCellBringupCallbacks already cleared above.
        }

        // Unregister old callbacks (as necessary)
        if (oldRouteSelectionCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(oldRouteSelectionCallback);
        }
        if (oldWifiCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(oldWifiCallback);
        }
        if (oldWifiEntryRssiThresholdCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(oldWifiEntryRssiThresholdCallback);
        }
        if (oldWifiExitRssiThresholdCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(oldWifiExitRssiThresholdCallback);
        }
        for (NetworkCallback cellBringupCallback : oldCellCallbacks) {
            mConnectivityManager.unregisterNetworkCallback(cellBringupCallback);
        }
    }

    /**
     * Builds the Route selection request
     *
     * <p>This request is guaranteed to select carrier-owned, non-VCN underlying networks by virtue
     * of a populated set of subIds as expressed in NetworkCapabilities#getSubscriptionIds(). Only
     * carrier owned networks may be selected, as the request specifies only subIds in the VCN's
     * subscription group, while the VCN networks are excluded by virtue of not having subIds set on
     * the VCN-exposed networks.
     *
     * <p>If the VCN that this UnderlyingNetworkController belongs to is in test-mode, this will
     * return a NetworkRequest that only matches Test Networks.
     */
    private NetworkRequest getRouteSelectionRequest() {
        if (mVcnContext.isInTestMode()) {
            return getTestNetworkRequest(mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup));
        }

        return getBaseNetworkRequestBuilder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .setSubscriptionIds(mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup))
                .build();
    }

    /**
     * Builds the WiFi bringup request
     *
     * <p>This request is built specifically to match only carrier-owned WiFi networks, but is also
     * built to ONLY keep Carrier WiFi Networks alive (but never bring them up). This is a result of
     * the WifiNetworkFactory not advertising a list of subIds, and therefore not accepting this
     * request. As such, it will bind to a Carrier WiFi Network that has already been brought up,
     * but will NEVER bring up a Carrier WiFi network itself.
     */
    private NetworkRequest getWifiNetworkRequest() {
        return getBaseNetworkRequestBuilder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setSubscriptionIds(mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup))
                .build();
    }

    /**
     * Builds the WiFi entry threshold signal strength request
     *
     * <p>This request ensures that WiFi reports the crossing of the wifi entry RSSI threshold.
     * Without this request, WiFi rate-limits, and reports signal strength changes at too slow a
     * pace to effectively select a short-lived WiFi offload network.
     */
    private NetworkRequest getWifiEntryRssiThresholdNetworkRequest() {
        return getBaseNetworkRequestBuilder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setSubscriptionIds(mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup))
                // Ensure wifi updates signal strengths when crossing this threshold.
                .setSignalStrength(getWifiEntryRssiThreshold(mCarrierConfig))
                .build();
    }

    /**
     * Builds the WiFi exit threshold signal strength request
     *
     * <p>This request ensures that WiFi reports the crossing of the wifi exit RSSI threshold.
     * Without this request, WiFi rate-limits, and reports signal strength changes at too slow a
     * pace to effectively select away from a failing WiFi network.
     */
    private NetworkRequest getWifiExitRssiThresholdNetworkRequest() {
        return getBaseNetworkRequestBuilder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setSubscriptionIds(mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup))
                // Ensure wifi updates signal strengths when crossing this threshold.
                .setSignalStrength(getWifiExitRssiThreshold(mCarrierConfig))
                .build();
    }

    /**
     * Builds a Cellular bringup request for a given subId
     *
     * <p>This request is filed in order to ensure that the Telephony stack always has a
     * NetworkRequest to bring up a VCN underlying cellular network. It is required in order to
     * ensure that even when a VCN (appears as Cellular) satisfies the default request, Telephony
     * will bring up additional underlying Cellular networks.
     *
     * <p>Since this request MUST make it to the TelephonyNetworkFactory, subIds are not specified
     * in the NetworkCapabilities, but rather in the TelephonyNetworkSpecifier.
     */
    private NetworkRequest getCellNetworkRequestForSubId(int subId) {
        return getBaseNetworkRequestBuilder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier(subId))
                .build();
    }

    /**
     * Builds and returns a NetworkRequest builder common to all Underlying Network requests
     */
    private NetworkRequest.Builder getBaseNetworkRequestBuilder() {
        return new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
    }

    /** Builds and returns a NetworkRequest for the given subIds to match Test Networks. */
    private NetworkRequest getTestNetworkRequest(@NonNull Set<Integer> subIds) {
        return new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .setSubscriptionIds(subIds)
                .build();
    }

    /**
     * Update this UnderlyingNetworkController's TelephonySubscriptionSnapshot.
     *
     * <p>Updating the TelephonySubscriptionSnapshot will cause this UnderlyingNetworkController to
     * reevaluate its NetworkBringupCallbacks. This may result in NetworkRequests being registered
     * or unregistered if the subIds mapped to the this Tracker's SubscriptionGroup change.
     */
    public void updateSubscriptionSnapshot(@NonNull TelephonySubscriptionSnapshot newSnapshot) {
        Objects.requireNonNull(newSnapshot, "Missing newSnapshot");

        final TelephonySubscriptionSnapshot oldSnapshot = mLastSnapshot;
        mLastSnapshot = newSnapshot;

        // Only trigger re-registration if subIds in this group have changed
        if (oldSnapshot
                .getAllSubIdsInGroup(mSubscriptionGroup)
                .equals(newSnapshot.getAllSubIdsInGroup(mSubscriptionGroup))) {
            return;
        }
        registerOrUpdateNetworkRequests();
    }

    /** Tears down this Tracker, and releases all underlying network requests. */
    public void teardown() {
        mVcnContext.ensureRunningOnLooperThread();
        mIsQuitting = true;

        // Will unregister all existing callbacks, but not register new ones due to quitting flag.
        registerOrUpdateNetworkRequests();

        mVcnContext
                .getContext()
                .getSystemService(TelephonyManager.class)
                .unregisterTelephonyCallback(mActiveDataSubIdListener);
    }

    private void reevaluateNetworks() {
        if (mIsQuitting || mRouteSelectionCallback == null) {
            return; // UnderlyingNetworkController has quit.
        }

        TreeSet<UnderlyingNetworkRecord> sorted =
                mRouteSelectionCallback.getSortedUnderlyingNetworks();
        UnderlyingNetworkRecord candidate = sorted.isEmpty() ? null : sorted.first();
        if (Objects.equals(mCurrentRecord, candidate)) {
            return;
        }

        String allNetworkPriorities = "";
        for (UnderlyingNetworkRecord record : sorted) {
            if (!allNetworkPriorities.isEmpty()) {
                allNetworkPriorities += ", ";
            }
            allNetworkPriorities += record.network + ": " + record.getPriorityClass();
        }
        logInfo(
                "Selected network changed to "
                        + (candidate == null ? null : candidate.network)
                        + ", selected from list: "
                        + allNetworkPriorities);
        mCurrentRecord = candidate;
        mCb.onSelectedUnderlyingNetworkChanged(mCurrentRecord);
    }

    /**
     * NetworkBringupCallback is used to keep background, VCN-managed Networks from being reaped.
     *
     * <p>NetworkBringupCallback only exists to prevent matching (VCN-managed) Networks from being
     * reaped, and no action is taken on any events firing.
     */
    @VisibleForTesting
    class NetworkBringupCallback extends NetworkCallback {}

    /**
     * RouteSelectionCallback is used to select the "best" underlying Network.
     *
     * <p>The "best" network is determined by ConnectivityService, which is treated as a source of
     * truth.
     */
    @VisibleForTesting
    class UnderlyingNetworkListener extends NetworkCallback {
        private final Map<Network, UnderlyingNetworkRecord.Builder>
                mUnderlyingNetworkRecordBuilders = new ArrayMap<>();

        UnderlyingNetworkListener() {
            super(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO);
        }

        private TreeSet<UnderlyingNetworkRecord> getSortedUnderlyingNetworks() {
            TreeSet<UnderlyingNetworkRecord> sorted =
                    new TreeSet<>(
                            UnderlyingNetworkRecord.getComparator(
                                    mVcnContext,
                                    mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                                    mSubscriptionGroup,
                                    mLastSnapshot,
                                    mCurrentRecord,
                                    mCarrierConfig));

            for (UnderlyingNetworkRecord.Builder builder :
                    mUnderlyingNetworkRecordBuilders.values()) {
                if (builder.isValid()) {
                    sorted.add(builder.build());
                }
            }

            return sorted;
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            mUnderlyingNetworkRecordBuilders.put(
                    network, new UnderlyingNetworkRecord.Builder(network));
        }

        @Override
        public void onLost(@NonNull Network network) {
            mUnderlyingNetworkRecordBuilders.remove(network);

            reevaluateNetworks();
        }

        @Override
        public void onCapabilitiesChanged(
                @NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            final UnderlyingNetworkRecord.Builder builder =
                    mUnderlyingNetworkRecordBuilders.get(network);
            if (builder == null) {
                logWtf("Got capabilities change for unknown key: " + network);
                return;
            }

            builder.setNetworkCapabilities(networkCapabilities);
            if (builder.isValid()) {
                reevaluateNetworks();
            }
        }

        @Override
        public void onLinkPropertiesChanged(
                @NonNull Network network, @NonNull LinkProperties linkProperties) {
            final UnderlyingNetworkRecord.Builder builder =
                    mUnderlyingNetworkRecordBuilders.get(network);
            if (builder == null) {
                logWtf("Got link properties change for unknown key: " + network);
                return;
            }

            builder.setLinkProperties(linkProperties);
            if (builder.isValid()) {
                reevaluateNetworks();
            }
        }

        @Override
        public void onBlockedStatusChanged(@NonNull Network network, boolean isBlocked) {
            final UnderlyingNetworkRecord.Builder builder =
                    mUnderlyingNetworkRecordBuilders.get(network);
            if (builder == null) {
                logWtf("Got blocked status change for unknown key: " + network);
                return;
            }

            builder.setIsBlocked(isBlocked);
            if (builder.isValid()) {
                reevaluateNetworks();
            }
        }
    }

    private String getLogPrefix() {
        return "("
                + LogUtils.getHashedSubscriptionGroup(mSubscriptionGroup)
                + "-"
                + mConnectionConfig.getGatewayConnectionName()
                + "-"
                + System.identityHashCode(this)
                + ") ";
    }

    private String getTagLogPrefix() {
        return "[ " + TAG + " " + getLogPrefix() + "]";
    }

    private void logInfo(String msg) {
        Slog.i(TAG, getLogPrefix() + msg);
        LOCAL_LOG.log("[INFO] " + getTagLogPrefix() + msg);
    }

    private void logInfo(String msg, Throwable tr) {
        Slog.i(TAG, getLogPrefix() + msg, tr);
        LOCAL_LOG.log("[INFO] " + getTagLogPrefix() + msg + tr);
    }

    private void logWtf(String msg) {
        Slog.wtf(TAG, msg);
        LOCAL_LOG.log(TAG + "[WTF ] " + getTagLogPrefix() + msg);
    }

    private void logWtf(String msg, Throwable tr) {
        Slog.wtf(TAG, msg, tr);
        LOCAL_LOG.log(TAG + "[WTF ] " + getTagLogPrefix() + msg + tr);
    }

    /** Dumps the state of this record for logging and debugging purposes. */
    public void dump(IndentingPrintWriter pw) {
        pw.println("UnderlyingNetworkController:");
        pw.increaseIndent();

        pw.println("Carrier WiFi Entry Threshold: " + getWifiEntryRssiThreshold(mCarrierConfig));
        pw.println("Carrier WiFi Exit Threshold: " + getWifiExitRssiThreshold(mCarrierConfig));
        pw.println(
                "Currently selected: " + (mCurrentRecord == null ? null : mCurrentRecord.network));

        pw.println("VcnUnderlyingNetworkTemplate list:");
        pw.increaseIndent();
        int index = 0;
        for (VcnUnderlyingNetworkTemplate priority :
                mConnectionConfig.getVcnUnderlyingNetworkPriorities()) {
            pw.println("Priority index: " + index);
            priority.dump(pw);
            index++;
        }
        pw.decreaseIndent();
        pw.println();

        pw.println("Underlying networks:");
        pw.increaseIndent();
        if (mRouteSelectionCallback != null) {
            for (UnderlyingNetworkRecord record :
                    mRouteSelectionCallback.getSortedUnderlyingNetworks()) {
                record.dump(
                        mVcnContext,
                        pw,
                        mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                        mSubscriptionGroup,
                        mLastSnapshot,
                        mCurrentRecord,
                        mCarrierConfig);
            }
        }
        pw.decreaseIndent();
        pw.println();

        pw.decreaseIndent();
    }

    private class VcnActiveDataSubscriptionIdListener extends TelephonyCallback
            implements ActiveDataSubscriptionIdListener {
        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            reevaluateNetworks();
        }
    }

    /** Callbacks for being notified of the changes in, or to the selected underlying network. */
    public interface UnderlyingNetworkControllerCallback {
        /**
         * Fired when a new underlying network is selected, or properties have changed.
         *
         * <p>This callback does NOT signal a mobility event.
         *
         * @param underlyingNetworkRecord The details of the new underlying network
         */
        void onSelectedUnderlyingNetworkChanged(
                @Nullable UnderlyingNetworkRecord underlyingNetworkRecord);
    }

    private static class Dependencies {}
}
