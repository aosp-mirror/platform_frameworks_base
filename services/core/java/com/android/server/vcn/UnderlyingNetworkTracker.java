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

package com.android.server.vcn;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;

import static com.android.server.VcnManagementService.LOCAL_LOG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tracks a set of Networks underpinning a VcnGatewayConnection.
 *
 * <p>A single UnderlyingNetworkTracker is built to serve a SINGLE VCN Gateway Connection, and MUST
 * be torn down with the VcnGatewayConnection in order to ensure underlying networks are allowed to
 * be reaped.
 *
 * @hide
 */
public class UnderlyingNetworkTracker {
    @NonNull private static final String TAG = UnderlyingNetworkTracker.class.getSimpleName();

    /**
     * Minimum signal strength for a WiFi network to be eligible for switching to
     *
     * <p>A network that satisfies this is eligible to become the selected underlying network with
     * no additional conditions
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT = -70;

    /**
     * Minimum signal strength to continue using a WiFi network
     *
     * <p>A network that satisfies the conditions may ONLY continue to be used if it is already
     * selected as the underlying network. A WiFi network satisfying this condition, but NOT the
     * prospective-network RSSI threshold CANNOT be switched to.
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int WIFI_EXIT_RSSI_THRESHOLD_DEFAULT = -74;

    /** Priority for any cellular network for which the subscription is listed as opportunistic */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_OPPORTUNISTIC_CELLULAR = 0;

    /** Priority for any WiFi network which is in use, and satisfies the in-use RSSI threshold */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_WIFI_IN_USE = 1;

    /** Priority for any WiFi network which satisfies the prospective-network RSSI threshold */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_WIFI_PROSPECTIVE = 2;

    /** Priority for any standard macro cellular network */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_MACRO_CELLULAR = 3;

    /** Priority for any other networks (including unvalidated, etc) */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_ANY = Integer.MAX_VALUE;

    private static final SparseArray<String> PRIORITY_TO_STRING_MAP = new SparseArray<>();

    static {
        PRIORITY_TO_STRING_MAP.put(
                PRIORITY_OPPORTUNISTIC_CELLULAR, "PRIORITY_OPPORTUNISTIC_CELLULAR");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_WIFI_IN_USE, "PRIORITY_WIFI_IN_USE");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_WIFI_PROSPECTIVE, "PRIORITY_WIFI_PROSPECTIVE");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_MACRO_CELLULAR, "PRIORITY_MACRO_CELLULAR");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_ANY, "PRIORITY_ANY");
    }

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final UnderlyingNetworkTrackerCallback mCb;
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

    public UnderlyingNetworkTracker(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull UnderlyingNetworkTrackerCallback cb) {
        this(
                vcnContext,
                subscriptionGroup,
                snapshot,
                cb,
                new Dependencies());
    }

    private UnderlyingNetworkTracker(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull UnderlyingNetworkTrackerCallback cb,
            @NonNull Dependencies deps) {
        mVcnContext = Objects.requireNonNull(vcnContext, "Missing vcnContext");
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
     * <p>If the VCN that this UnderlyingNetworkTracker belongs to is in test-mode, this will return
     * a NetworkRequest that only matches Test Networks.
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
     * Update this UnderlyingNetworkTracker's TelephonySubscriptionSnapshot.
     *
     * <p>Updating the TelephonySubscriptionSnapshot will cause this UnderlyingNetworkTracker to
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
        if (mRouteSelectionCallback == null) {
            return; // UnderlyingNetworkTracker has quit.
        }

        TreeSet<UnderlyingNetworkRecord> sorted =
                mRouteSelectionCallback.getSortedUnderlyingNetworks();
        UnderlyingNetworkRecord candidate = sorted.isEmpty() ? null : sorted.first();
        if (Objects.equals(mCurrentRecord, candidate)) {
            return;
        }

        mCurrentRecord = candidate;
        mCb.onSelectedUnderlyingNetworkChanged(mCurrentRecord);
    }

    private static boolean isOpportunistic(
            @NonNull TelephonySubscriptionSnapshot snapshot, Set<Integer> subIds) {
        if (snapshot == null) {
            logWtf("Got null snapshot");
            return false;
        }

        for (int subId : subIds) {
            if (snapshot.isOpportunistic(subId)) {
                return true;
            }
        }

        return false;
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

    private static int getWifiEntryRssiThreshold(@Nullable PersistableBundle carrierConfig) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(
                    VcnManager.VCN_NETWORK_SELECTION_WIFI_ENTRY_RSSI_THRESHOLD_KEY,
                    WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT);
        }

        return WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT;
    }

    private static int getWifiExitRssiThreshold(@Nullable PersistableBundle carrierConfig) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(
                    VcnManager.VCN_NETWORK_SELECTION_WIFI_EXIT_RSSI_THRESHOLD_KEY,
                    WIFI_EXIT_RSSI_THRESHOLD_DEFAULT);
        }

        return WIFI_EXIT_RSSI_THRESHOLD_DEFAULT;
    }

    /** A record of a single underlying network, caching relevant fields. */
    public static class UnderlyingNetworkRecord {
        @NonNull public final Network network;
        @NonNull public final NetworkCapabilities networkCapabilities;
        @NonNull public final LinkProperties linkProperties;
        public final boolean isBlocked;

        @VisibleForTesting(visibility = Visibility.PRIVATE)
        UnderlyingNetworkRecord(
                @NonNull Network network,
                @NonNull NetworkCapabilities networkCapabilities,
                @NonNull LinkProperties linkProperties,
                boolean isBlocked) {
            this.network = network;
            this.networkCapabilities = networkCapabilities;
            this.linkProperties = linkProperties;
            this.isBlocked = isBlocked;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UnderlyingNetworkRecord)) return false;
            final UnderlyingNetworkRecord that = (UnderlyingNetworkRecord) o;

            return network.equals(that.network)
                    && networkCapabilities.equals(that.networkCapabilities)
                    && linkProperties.equals(that.linkProperties)
                    && isBlocked == that.isBlocked;
        }

        @Override
        public int hashCode() {
            return Objects.hash(network, networkCapabilities, linkProperties, isBlocked);
        }

        /**
         * Gives networks a priority class, based on the following priorities:
         *
         * <ol>
         *   <li>Opportunistic cellular
         *   <li>Carrier WiFi, signal strength >= WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT
         *   <li>Carrier WiFi, active network + signal strength >= WIFI_EXIT_RSSI_THRESHOLD_DEFAULT
         *   <li>Macro cellular
         *   <li>Any others
         * </ol>
         */
        private int calculatePriorityClass(
                ParcelUuid subscriptionGroup,
                TelephonySubscriptionSnapshot snapshot,
                UnderlyingNetworkRecord currentlySelected,
                PersistableBundle carrierConfig) {
            final NetworkCapabilities caps = networkCapabilities;

            // mRouteSelectionNetworkRequest requires a network be both VALIDATED and NOT_SUSPENDED

            if (isBlocked) {
                logWtf("Network blocked for System Server: " + network);
                return PRIORITY_ANY;
            }

            if (caps.hasTransport(TRANSPORT_CELLULAR)
                    && isOpportunistic(snapshot, caps.getSubscriptionIds())) {
                // If this carrier is the active data provider, ensure that opportunistic is only
                // ever prioritized if it is also the active data subscription. This ensures that
                // if an opportunistic subscription is still in the process of being switched to,
                // or switched away from, the VCN does not attempt to continue using it against the
                // decision made at the telephony layer. Failure to do so may result in the modem
                // switching back and forth.
                //
                // Allow the following two cases:
                // 1. Active subId is NOT in the group that this VCN is supporting
                // 2. This opportunistic subscription is for the active subId
                if (!snapshot.getAllSubIdsInGroup(subscriptionGroup)
                                .contains(SubscriptionManager.getActiveDataSubscriptionId())
                        || caps.getSubscriptionIds()
                                .contains(SubscriptionManager.getActiveDataSubscriptionId())) {
                    return PRIORITY_OPPORTUNISTIC_CELLULAR;
                }
            }

            if (caps.hasTransport(TRANSPORT_WIFI)) {
                if (caps.getSignalStrength() >= getWifiExitRssiThreshold(carrierConfig)
                        && currentlySelected != null
                        && network.equals(currentlySelected.network)) {
                    return PRIORITY_WIFI_IN_USE;
                }

                if (caps.getSignalStrength() >= getWifiEntryRssiThreshold(carrierConfig)) {
                    return PRIORITY_WIFI_PROSPECTIVE;
                }
            }

            // Disallow opportunistic subscriptions from matching PRIORITY_MACRO_CELLULAR, as might
            // be the case when Default Data SubId (CBRS) != Active Data SubId (MACRO), as might be
            // the case if the Default Data SubId does not support certain services (eg voice
            // calling)
            if (caps.hasTransport(TRANSPORT_CELLULAR)
                    && !isOpportunistic(snapshot, caps.getSubscriptionIds())) {
                return PRIORITY_MACRO_CELLULAR;
            }

            return PRIORITY_ANY;
        }

        private static Comparator<UnderlyingNetworkRecord> getComparator(
                ParcelUuid subscriptionGroup,
                TelephonySubscriptionSnapshot snapshot,
                UnderlyingNetworkRecord currentlySelected,
                PersistableBundle carrierConfig) {
            return (left, right) -> {
                return Integer.compare(
                        left.calculatePriorityClass(
                                subscriptionGroup, snapshot, currentlySelected, carrierConfig),
                        right.calculatePriorityClass(
                                subscriptionGroup, snapshot, currentlySelected, carrierConfig));
            };
        }

        /** Dumps the state of this record for logging and debugging purposes. */
        private void dump(
                IndentingPrintWriter pw,
                ParcelUuid subscriptionGroup,
                TelephonySubscriptionSnapshot snapshot,
                UnderlyingNetworkRecord currentlySelected,
                PersistableBundle carrierConfig) {
            pw.println("UnderlyingNetworkRecord:");
            pw.increaseIndent();

            final int priorityClass =
                    calculatePriorityClass(
                            subscriptionGroup, snapshot, currentlySelected, carrierConfig);
            pw.println(
                    "Priority class: " + PRIORITY_TO_STRING_MAP.get(priorityClass) + " ("
                            + priorityClass + ")");
            pw.println("mNetwork: " + network);
            pw.println("mNetworkCapabilities: " + networkCapabilities);
            pw.println("mLinkProperties: " + linkProperties);

            pw.decreaseIndent();
        }

        /** Builder to incrementally construct an UnderlyingNetworkRecord. */
        private static class Builder {
            @NonNull private final Network mNetwork;

            @Nullable private NetworkCapabilities mNetworkCapabilities;
            @Nullable private LinkProperties mLinkProperties;
            boolean mIsBlocked;
            boolean mWasIsBlockedSet;

            @Nullable private UnderlyingNetworkRecord mCached;

            private Builder(@NonNull Network network) {
                mNetwork = network;
            }

            @NonNull
            private Network getNetwork() {
                return mNetwork;
            }

            private void setNetworkCapabilities(@NonNull NetworkCapabilities networkCapabilities) {
                mNetworkCapabilities = networkCapabilities;
                mCached = null;
            }

            @Nullable
            private NetworkCapabilities getNetworkCapabilities() {
                return mNetworkCapabilities;
            }

            private void setLinkProperties(@NonNull LinkProperties linkProperties) {
                mLinkProperties = linkProperties;
                mCached = null;
            }

            private void setIsBlocked(boolean isBlocked) {
                mIsBlocked = isBlocked;
                mWasIsBlockedSet = true;
                mCached = null;
            }

            private boolean isValid() {
                return mNetworkCapabilities != null && mLinkProperties != null && mWasIsBlockedSet;
            }

            private UnderlyingNetworkRecord build() {
                if (!isValid()) {
                    throw new IllegalArgumentException(
                            "Called build before UnderlyingNetworkRecord was valid");
                }

                if (mCached == null) {
                    mCached =
                            new UnderlyingNetworkRecord(
                                    mNetwork, mNetworkCapabilities, mLinkProperties, mIsBlocked);
                }

                return mCached;
            }
        }
    }

    private static void logWtf(String msg) {
        Slog.wtf(TAG, msg);
        LOCAL_LOG.log(TAG + " WTF: " + msg);
    }

    private static void logWtf(String msg, Throwable tr) {
        Slog.wtf(TAG, msg, tr);
        LOCAL_LOG.log(TAG + " WTF: " + msg + tr);
    }

    /** Dumps the state of this record for logging and debugging purposes. */
    public void dump(IndentingPrintWriter pw) {
        pw.println("UnderlyingNetworkTracker:");
        pw.increaseIndent();

        pw.println("Carrier WiFi Entry Threshold: " + getWifiEntryRssiThreshold(mCarrierConfig));
        pw.println("Carrier WiFi Exit Threshold: " + getWifiExitRssiThreshold(mCarrierConfig));
        pw.println(
                "Currently selected: " + (mCurrentRecord == null ? null : mCurrentRecord.network));

        pw.println("Underlying networks:");
        pw.increaseIndent();
        if (mRouteSelectionCallback != null) {
            for (UnderlyingNetworkRecord record :
                    mRouteSelectionCallback.getSortedUnderlyingNetworks()) {
                record.dump(pw, mSubscriptionGroup, mLastSnapshot, mCurrentRecord, mCarrierConfig);
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
    public interface UnderlyingNetworkTrackerCallback {
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
