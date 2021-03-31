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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final Set<Integer> mRequiredUnderlyingNetworkCapabilities;
    @NonNull private final UnderlyingNetworkTrackerCallback mCb;
    @NonNull private final Dependencies mDeps;
    @NonNull private final Handler mHandler;
    @NonNull private final ConnectivityManager mConnectivityManager;

    @NonNull private final List<NetworkCallback> mCellBringupCallbacks = new ArrayList<>();
    @Nullable private NetworkCallback mWifiBringupCallback;
    @Nullable private NetworkCallback mRouteSelectionCallback;

    @NonNull private TelephonySubscriptionSnapshot mLastSnapshot;
    private boolean mIsQuitting = false;

    @Nullable private UnderlyingNetworkRecord mCurrentRecord;
    @Nullable private UnderlyingNetworkRecord.Builder mRecordInProgress;

    public UnderlyingNetworkTracker(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull Set<Integer> requiredUnderlyingNetworkCapabilities,
            @NonNull UnderlyingNetworkTrackerCallback cb) {
        this(
                vcnContext,
                subscriptionGroup,
                snapshot,
                requiredUnderlyingNetworkCapabilities,
                cb,
                new Dependencies());
    }

    private UnderlyingNetworkTracker(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot snapshot,
            @NonNull Set<Integer> requiredUnderlyingNetworkCapabilities,
            @NonNull UnderlyingNetworkTrackerCallback cb,
            @NonNull Dependencies deps) {
        mVcnContext = Objects.requireNonNull(vcnContext, "Missing vcnContext");
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mLastSnapshot = Objects.requireNonNull(snapshot, "Missing snapshot");
        mRequiredUnderlyingNetworkCapabilities =
                Objects.requireNonNull(
                        requiredUnderlyingNetworkCapabilities,
                        "Missing requiredUnderlyingNetworkCapabilities");
        mCb = Objects.requireNonNull(cb, "Missing cb");
        mDeps = Objects.requireNonNull(deps, "Missing deps");

        mHandler = new Handler(mVcnContext.getLooper());

        mConnectivityManager = mVcnContext.getContext().getSystemService(ConnectivityManager.class);

        registerOrUpdateNetworkRequests();
    }

    private void registerOrUpdateNetworkRequests() {
        NetworkCallback oldRouteSelectionCallback = mRouteSelectionCallback;
        NetworkCallback oldWifiCallback = mWifiBringupCallback;
        List<NetworkCallback> oldCellCallbacks = new ArrayList<>(mCellBringupCallbacks);
        mCellBringupCallbacks.clear();

        // Register new callbacks. Make-before-break; always register new callbacks before removal
        // of old callbacks
        if (!mIsQuitting) {
            mRouteSelectionCallback = new RouteSelectionCallback();
            mConnectivityManager.requestBackgroundNetwork(
                    getBaseNetworkRequestBuilder().build(), mHandler, mRouteSelectionCallback);

            mWifiBringupCallback = new NetworkBringupCallback();
            mConnectivityManager.requestBackgroundNetwork(
                    getWifiNetworkRequest(), mHandler, mWifiBringupCallback);

            for (final int subId : mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup)) {
                final NetworkBringupCallback cb = new NetworkBringupCallback();
                mCellBringupCallbacks.add(cb);

                mConnectivityManager.requestBackgroundNetwork(
                        getCellNetworkRequestForSubId(subId), mHandler, cb);
            }
        } else {
            mRouteSelectionCallback = null;
            mWifiBringupCallback = null;
            // mCellBringupCallbacks already cleared above.
        }

        // Unregister old callbacks (as necessary)
        if (oldRouteSelectionCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(oldRouteSelectionCallback);
        }
        if (oldWifiCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(oldWifiCallback);
        }
        for (NetworkCallback cellBringupCallback : oldCellCallbacks) {
            mConnectivityManager.unregisterNetworkCallback(cellBringupCallback);
        }
    }

    private NetworkRequest getWifiNetworkRequest() {
        return getBaseNetworkRequestBuilder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
    }

    private NetworkRequest getCellNetworkRequestForSubId(int subId) {
        return getBaseNetworkRequestBuilder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier(subId))
                .build();
    }

    /**
     * Builds and returns a NetworkRequest builder common to all Underlying Network requests
     *
     * <p>This request is guaranteed to select carrier-owned, non-VCN underlying networks by virtue
     * of a populated set of subIds as expressed in NetworkCapabilities#getSubIds(). Only carrier
     * owned networks may be selected, as the request specifies only subIds in the VCN's
     * subscription group, while the VCN networks are excluded by virtue of not having subIds set on
     * the VCN-exposed networks.
     */
    private NetworkRequest.Builder getBaseNetworkRequestBuilder() {
        return new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .setSubIds(mLastSnapshot.getAllSubIdsInGroup(mSubscriptionGroup));
    }

    /**
     * Update this UnderlyingNetworkTracker's TelephonySubscriptionSnapshot.
     *
     * <p>Updating the TelephonySubscriptionSnapshot will cause this UnderlyingNetworkTracker to
     * reevaluate its NetworkBringupCallbacks. This may result in NetworkRequests being registered
     * or unregistered if the subIds mapped to the this Tracker's SubscriptionGroup change.
     */
    public void updateSubscriptionSnapshot(@NonNull TelephonySubscriptionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Missing snapshot");

        mLastSnapshot = snapshot;
        registerOrUpdateNetworkRequests();
    }

    /** Tears down this Tracker, and releases all underlying network requests. */
    public void teardown() {
        mVcnContext.ensureRunningOnLooperThread();
        mIsQuitting = true;

        // Will unregister all existing callbacks, but not register new ones due to quitting flag.
        registerOrUpdateNetworkRequests();
    }

    /** Returns whether the currently selected Network matches the given network. */
    private static boolean isSameNetwork(
            @Nullable UnderlyingNetworkRecord.Builder recordInProgress, @NonNull Network network) {
        return recordInProgress != null && recordInProgress.getNetwork().equals(network);
    }

    /** Notify the Callback if a full UnderlyingNetworkRecord exists. */
    private void maybeNotifyCallback() {
        // Only forward this update if a complete record has been received
        if (!mRecordInProgress.isValid()) {
            return;
        }

        // Only forward this update if the updated record differs form the current record
        UnderlyingNetworkRecord updatedRecord = mRecordInProgress.build();
        if (!updatedRecord.equals(mCurrentRecord)) {
            mCurrentRecord = updatedRecord;

            mCb.onSelectedUnderlyingNetworkChanged(mCurrentRecord);
        }
    }

    private void handleNetworkAvailable(@NonNull Network network) {
        mVcnContext.ensureRunningOnLooperThread();

        mRecordInProgress = new UnderlyingNetworkRecord.Builder(network);
    }

    private void handleNetworkLost(@NonNull Network network) {
        mVcnContext.ensureRunningOnLooperThread();

        if (!isSameNetwork(mRecordInProgress, network)) {
            Slog.wtf(TAG, "Non-underlying Network lost");
            return;
        }

        mRecordInProgress = null;
        mCurrentRecord = null;
        mCb.onSelectedUnderlyingNetworkChanged(null /* underlyingNetworkRecord */);
    }

    private void handleCapabilitiesChanged(
            @NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
        mVcnContext.ensureRunningOnLooperThread();

        if (!isSameNetwork(mRecordInProgress, network)) {
            Slog.wtf(TAG, "Invalid update to NetworkCapabilities");
            return;
        }

        mRecordInProgress.setNetworkCapabilities(networkCapabilities);

        maybeNotifyCallback();
    }

    private void handlePropertiesChanged(
            @NonNull Network network, @NonNull LinkProperties linkProperties) {
        mVcnContext.ensureRunningOnLooperThread();

        if (!isSameNetwork(mRecordInProgress, network)) {
            Slog.wtf(TAG, "Invalid update to LinkProperties");
            return;
        }

        mRecordInProgress.setLinkProperties(linkProperties);

        maybeNotifyCallback();
    }

    private void handleNetworkBlocked(@NonNull Network network, boolean isBlocked) {
        mVcnContext.ensureRunningOnLooperThread();

        if (!isSameNetwork(mRecordInProgress, network)) {
            Slog.wtf(TAG, "Invalid update to isBlocked");
            return;
        }

        mRecordInProgress.setIsBlocked(isBlocked);

        maybeNotifyCallback();
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
    class RouteSelectionCallback extends NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
            handleNetworkAvailable(network);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handleNetworkLost(network);
        }

        @Override
        public void onCapabilitiesChanged(
                @NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            if (networkCapabilities.equals(mRecordInProgress.getNetworkCapabilities())) return;
            handleCapabilitiesChanged(network, networkCapabilities);
        }

        @Override
        public void onLinkPropertiesChanged(
                @NonNull Network network, @NonNull LinkProperties linkProperties) {
            handlePropertiesChanged(network, linkProperties);
        }

        @Override
        public void onBlockedStatusChanged(@NonNull Network network, boolean isBlocked) {
            handleNetworkBlocked(network, isBlocked);
        }
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

        /** Builder to incrementally construct an UnderlyingNetworkRecord. */
        private static class Builder {
            @NonNull private final Network mNetwork;

            @Nullable private NetworkCapabilities mNetworkCapabilities;
            @Nullable private LinkProperties mLinkProperties;
            boolean mIsBlocked;
            boolean mWasIsBlockedSet;

            private Builder(@NonNull Network network) {
                mNetwork = network;
            }

            @NonNull
            private Network getNetwork() {
                return mNetwork;
            }

            private void setNetworkCapabilities(@NonNull NetworkCapabilities networkCapabilities) {
                mNetworkCapabilities = networkCapabilities;
            }

            @Nullable
            private NetworkCapabilities getNetworkCapabilities() {
                return mNetworkCapabilities;
            }

            private void setLinkProperties(@NonNull LinkProperties linkProperties) {
                mLinkProperties = linkProperties;
            }

            private void setIsBlocked(boolean isBlocked) {
                mIsBlocked = isBlocked;
                mWasIsBlockedSet = true;
            }

            private boolean isValid() {
                return mNetworkCapabilities != null && mLinkProperties != null && mWasIsBlockedSet;
            }

            private UnderlyingNetworkRecord build() {
                return new UnderlyingNetworkRecord(
                        mNetwork, mNetworkCapabilities, mLinkProperties, mIsBlocked);
            }
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
