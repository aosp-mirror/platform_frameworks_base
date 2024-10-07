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

import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_ANY;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_FORBIDDEN;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_REQUIRED;
import static android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;

import static com.android.server.VcnManagementService.LOCAL_LOG;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.getWifiEntryRssiThreshold;
import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.getWifiExitRssiThreshold;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IpSecTransform;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnCellUnderlyingNetworkTemplate;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnUnderlyingNetworkTemplate;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ParcelUuid;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.routeselection.UnderlyingNetworkEvaluator.NetworkEvaluatorCallback;
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

    private final Map<Network, UnderlyingNetworkEvaluator> mUnderlyingNetworkRecords =
            new ArrayMap<>();

    @NonNull private final List<NetworkCallback> mCellBringupCallbacks = new ArrayList<>();
    @Nullable private NetworkCallback mWifiBringupCallback;
    @Nullable private NetworkCallback mWifiEntryRssiThresholdCallback;
    @Nullable private NetworkCallback mWifiExitRssiThresholdCallback;
    @Nullable private UnderlyingNetworkListener mRouteSelectionCallback;

    @NonNull private TelephonySubscriptionSnapshot mLastSnapshot;
    @Nullable private PersistableBundleWrapper mCarrierConfig;
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

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    UnderlyingNetworkController(
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

        mCarrierConfig = mLastSnapshot.getCarrierConfigForSubGrp(mSubscriptionGroup);

        registerOrUpdateNetworkRequests();
    }

    private static class CapabilityMatchCriteria {
        public final int capability;
        public final int matchCriteria;

        CapabilityMatchCriteria(int capability, int matchCriteria) {
            this.capability = capability;
            this.matchCriteria = matchCriteria;
        }

        @Override
        public int hashCode() {
            return Objects.hash(capability, matchCriteria);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof CapabilityMatchCriteria)) {
                return false;
            }

            final CapabilityMatchCriteria rhs = (CapabilityMatchCriteria) other;
            return capability == rhs.capability && matchCriteria == rhs.matchCriteria;
        }
    }

    private static Set<Set<CapabilityMatchCriteria>> dedupAndGetCapRequirementsForCell(
            VcnGatewayConnectionConfig connectionConfig) {
        final Set<Set<CapabilityMatchCriteria>> dedupedCapsMatchSets = new ArraySet<>();

        for (VcnUnderlyingNetworkTemplate template :
                connectionConfig.getVcnUnderlyingNetworkPriorities()) {
            if (template instanceof VcnCellUnderlyingNetworkTemplate) {
                final Set<CapabilityMatchCriteria> capsMatchSet = new ArraySet<>();

                for (Map.Entry<Integer, Integer> entry :
                        ((VcnCellUnderlyingNetworkTemplate) template)
                                .getCapabilitiesMatchCriteria()
                                .entrySet()) {

                    final int capability = entry.getKey();
                    final int matchCriteria = entry.getValue();
                    if (matchCriteria != MATCH_ANY) {
                        capsMatchSet.add(new CapabilityMatchCriteria(capability, matchCriteria));
                    }
                }

                dedupedCapsMatchSets.add(capsMatchSet);
            }
        }

        dedupedCapsMatchSets.add(
                Collections.singleton(
                        new CapabilityMatchCriteria(
                                NetworkCapabilities.NET_CAPABILITY_INTERNET, MATCH_REQUIRED)));
        return dedupedCapsMatchSets;
    }

    private void registerOrUpdateNetworkRequests() {
        NetworkCallback oldRouteSelectionCallback = mRouteSelectionCallback;
        NetworkCallback oldWifiCallback = mWifiBringupCallback;
        NetworkCallback oldWifiEntryRssiThresholdCallback = mWifiEntryRssiThresholdCallback;
        NetworkCallback oldWifiExitRssiThresholdCallback = mWifiExitRssiThresholdCallback;
        List<NetworkCallback> oldCellCallbacks = new ArrayList<>(mCellBringupCallbacks);
        mCellBringupCallbacks.clear();

        if (mVcnContext.isFlagNetworkMetricMonitorEnabled()
                && mVcnContext.isFlagIpSecTransformStateEnabled()) {
            for (UnderlyingNetworkEvaluator evaluator : mUnderlyingNetworkRecords.values()) {
                evaluator.close();
            }
        }

        mUnderlyingNetworkRecords.clear();

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
                for (Set<CapabilityMatchCriteria> capsMatchCriteria :
                        dedupAndGetCapRequirementsForCell(mConnectionConfig)) {
                    final NetworkBringupCallback cb = new NetworkBringupCallback();
                    mCellBringupCallbacks.add(cb);

                    mConnectivityManager.requestBackgroundNetwork(
                            getCellNetworkRequestForSubId(subId, capsMatchCriteria), cb, mHandler);
                }
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

    private NetworkRequest.Builder getBaseWifiNetworkRequestBuilder() {
        return getBaseNetworkRequestBuilder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setSubscriptionIds(mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup));
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
        return getBaseWifiNetworkRequestBuilder().build();
    }

    /**
     * Builds the WiFi entry threshold signal strength request
     *
     * <p>This request ensures that WiFi reports the crossing of the wifi entry RSSI threshold.
     * Without this request, WiFi rate-limits, and reports signal strength changes at too slow a
     * pace to effectively select a short-lived WiFi offload network.
     */
    private NetworkRequest getWifiEntryRssiThresholdNetworkRequest() {
        return getBaseWifiNetworkRequestBuilder()
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
        return getBaseWifiNetworkRequestBuilder()
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
    private NetworkRequest getCellNetworkRequestForSubId(
            int subId, Set<CapabilityMatchCriteria> capsMatchCriteria) {
        final NetworkRequest.Builder nrBuilder =
                getBaseNetworkRequestBuilder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .setNetworkSpecifier(
                                new TelephonyNetworkSpecifier.Builder()
                                        .setSubscriptionId(subId)
                                        .build());

        for (CapabilityMatchCriteria capMatchCriteria : capsMatchCriteria) {
            final int cap = capMatchCriteria.capability;
            final int matchCriteria = capMatchCriteria.matchCriteria;

            if (matchCriteria == MATCH_REQUIRED) {
                nrBuilder.addCapability(cap);
            } else if (matchCriteria == MATCH_FORBIDDEN) {
                nrBuilder.addForbiddenCapability(cap);
            }
        }

        return nrBuilder.build();
    }

    /**
     * Builds and returns a NetworkRequest builder common to all Underlying Network requests
     */
    private NetworkRequest.Builder getBaseNetworkRequestBuilder() {
        return new NetworkRequest.Builder()
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

        // Update carrier config
        mCarrierConfig = mLastSnapshot.getCarrierConfigForSubGrp(mSubscriptionGroup);

        // Make sure all evaluators use the same updated TelephonySubscriptionSnapshot and carrier
        // config to calculate their cached priority classes. For simplicity, the
        // UnderlyingNetworkController does not listen for changes in VCN-related carrier config
        // keys, and changes are applied at restart of the VcnGatewayConnection
        for (UnderlyingNetworkEvaluator evaluator : mUnderlyingNetworkRecords.values()) {
            evaluator.reevaluate(
                    mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                    mSubscriptionGroup,
                    mLastSnapshot,
                    mCarrierConfig);
        }

        // Only trigger re-registration if subIds in this group have changed
        if (oldSnapshot
                .getAllSubIdsInGroup(mSubscriptionGroup)
                .equals(newSnapshot.getAllSubIdsInGroup(mSubscriptionGroup))) {

            if (mVcnContext.isFlagNetworkMetricMonitorEnabled()
                    && mVcnContext.isFlagIpSecTransformStateEnabled()) {
                reevaluateNetworks();
            }
            return;
        }
        registerOrUpdateNetworkRequests();
    }

    /**
     * Pass the IpSecTransform of the VCN to UnderlyingNetworkController for metric monitoring
     *
     * <p>Caller MUST call it when IpSecTransforms have been created for VCN creation or migration
     */
    public void updateInboundTransform(
            @NonNull UnderlyingNetworkRecord currentNetwork, @NonNull IpSecTransform transform) {
        if (!mVcnContext.isFlagNetworkMetricMonitorEnabled()
                || !mVcnContext.isFlagIpSecTransformStateEnabled()) {
            logWtf("#updateInboundTransform: unexpected call; flags missing");
            return;
        }

        Objects.requireNonNull(currentNetwork, "currentNetwork is null");
        Objects.requireNonNull(transform, "transform is null");

        if (mCurrentRecord == null
                || mRouteSelectionCallback == null
                || !Objects.equals(currentNetwork.network, mCurrentRecord.network)) {
            // The caller (VcnGatewayConnection) is out-of-dated. Ignore this call.
            return;
        }

        mUnderlyingNetworkRecords.get(mCurrentRecord.network).setInboundTransform(transform);
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

    private TreeSet<UnderlyingNetworkEvaluator> getSortedUnderlyingNetworks() {
        TreeSet<UnderlyingNetworkEvaluator> sorted =
                new TreeSet<>(UnderlyingNetworkEvaluator.getComparator(mVcnContext));

        for (UnderlyingNetworkEvaluator evaluator : mUnderlyingNetworkRecords.values()) {
            if (evaluator.getPriorityClass() != NetworkPriorityClassifier.PRIORITY_INVALID) {
                sorted.add(evaluator);
            }
        }

        return sorted;
    }

    private void reevaluateNetworks() {
        if (mIsQuitting || mRouteSelectionCallback == null) {
            return; // UnderlyingNetworkController has quit.
        }

        TreeSet<UnderlyingNetworkEvaluator> sorted = getSortedUnderlyingNetworks();

        UnderlyingNetworkEvaluator candidateEvaluator = sorted.isEmpty() ? null : sorted.first();
        UnderlyingNetworkRecord candidate =
                candidateEvaluator == null ? null : candidateEvaluator.getNetworkRecord();
        if (Objects.equals(mCurrentRecord, candidate)) {
            return;
        }

        String allNetworkPriorities = "";
        for (UnderlyingNetworkEvaluator recordEvaluator : sorted) {
            if (!allNetworkPriorities.isEmpty()) {
                allNetworkPriorities += ", ";
            }
            allNetworkPriorities +=
                    recordEvaluator.getNetwork() + ": " + recordEvaluator.getPriorityClass();
        }

        if (!UnderlyingNetworkRecord.isSameNetwork(mCurrentRecord, candidate)) {
            logInfo(
                    "Selected network changed to "
                            + (candidate == null ? null : candidate.network)
                            + ", selected from list: "
                            + allNetworkPriorities);
        }

        mCurrentRecord = candidate;
        mCb.onSelectedUnderlyingNetworkChanged(mCurrentRecord);

        // Need to update all evaluators to ensure the previously selected one is unselected
        for (UnderlyingNetworkEvaluator evaluator : mUnderlyingNetworkRecords.values()) {
            evaluator.setIsSelected(
                    candidateEvaluator == evaluator,
                    mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                    mSubscriptionGroup,
                    mLastSnapshot,
                    mCarrierConfig);
        }
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
        UnderlyingNetworkListener() {
            super(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO);
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            mUnderlyingNetworkRecords.put(
                    network,
                    mDeps.newUnderlyingNetworkEvaluator(
                            mVcnContext,
                            network,
                            mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                            mSubscriptionGroup,
                            mLastSnapshot,
                            mCarrierConfig,
                            new NetworkEvaluatorCallbackImpl()));
        }

        @Override
        public void onLost(@NonNull Network network) {
            if (mVcnContext.isFlagNetworkMetricMonitorEnabled()
                    && mVcnContext.isFlagIpSecTransformStateEnabled()) {
                mUnderlyingNetworkRecords.get(network).close();
            }

            mUnderlyingNetworkRecords.remove(network);

            reevaluateNetworks();
        }

        @Override
        public void onCapabilitiesChanged(
                @NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            final UnderlyingNetworkEvaluator evaluator = mUnderlyingNetworkRecords.get(network);
            if (evaluator == null) {
                logWtf("Got capabilities change for unknown key: " + network);
                return;
            }

            evaluator.setNetworkCapabilities(
                    networkCapabilities,
                    mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                    mSubscriptionGroup,
                    mLastSnapshot,
                    mCarrierConfig);

            if (evaluator.isValid()) {
                reevaluateNetworks();
            }
        }

        @Override
        public void onLinkPropertiesChanged(
                @NonNull Network network, @NonNull LinkProperties linkProperties) {
            final UnderlyingNetworkEvaluator evaluator = mUnderlyingNetworkRecords.get(network);
            if (evaluator == null) {
                logWtf("Got link properties change for unknown key: " + network);
                return;
            }

            evaluator.setLinkProperties(
                    linkProperties,
                    mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                    mSubscriptionGroup,
                    mLastSnapshot,
                    mCarrierConfig);

            if (evaluator.isValid()) {
                reevaluateNetworks();
            }
        }

        @Override
        public void onBlockedStatusChanged(@NonNull Network network, boolean isBlocked) {
            final UnderlyingNetworkEvaluator evaluator = mUnderlyingNetworkRecords.get(network);
            if (evaluator == null) {
                logWtf("Got blocked status change for unknown key: " + network);
                return;
            }

            evaluator.setIsBlocked(
                    isBlocked,
                    mConnectionConfig.getVcnUnderlyingNetworkPriorities(),
                    mSubscriptionGroup,
                    mLastSnapshot,
                    mCarrierConfig);

            if (evaluator.isValid()) {
                reevaluateNetworks();
            }
        }
    }

    @VisibleForTesting
    class NetworkEvaluatorCallbackImpl implements NetworkEvaluatorCallback {
        @Override
        public void onEvaluationResultChanged() {
            if (!mVcnContext.isFlagNetworkMetricMonitorEnabled()
                    || !mVcnContext.isFlagIpSecTransformStateEnabled()) {
                logWtf("#onEvaluationResultChanged: unexpected call; flags missing");
                return;
            }

            mVcnContext.ensureRunningOnLooperThread();
            reevaluateNetworks();
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
            for (UnderlyingNetworkEvaluator recordEvaluator : getSortedUnderlyingNetworks()) {
                recordEvaluator.dump(pw);
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

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        public UnderlyingNetworkEvaluator newUnderlyingNetworkEvaluator(
                @NonNull VcnContext vcnContext,
                @NonNull Network network,
                @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
                @NonNull ParcelUuid subscriptionGroup,
                @NonNull TelephonySubscriptionSnapshot lastSnapshot,
                @Nullable PersistableBundleWrapper carrierConfig,
                @NonNull NetworkEvaluatorCallback evaluatorCallback) {
            return new UnderlyingNetworkEvaluator(
                    vcnContext,
                    network,
                    underlyingNetworkTemplates,
                    subscriptionGroup,
                    lastSnapshot,
                    carrierConfig,
                    evaluatorCallback);
        }
    }
}
