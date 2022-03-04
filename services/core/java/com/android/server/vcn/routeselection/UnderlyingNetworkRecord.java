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

package com.android.server.vcn.routeselection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnUnderlyingNetworkTemplate;
import android.os.ParcelUuid;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A record of a single underlying network, caching relevant fields.
 *
 * @hide
 */
public class UnderlyingNetworkRecord {
    private static final int PRIORITY_CLASS_INVALID = Integer.MAX_VALUE;

    @NonNull public final Network network;
    @NonNull public final NetworkCapabilities networkCapabilities;
    @NonNull public final LinkProperties linkProperties;
    public final boolean isBlocked;

    private int mPriorityClass = PRIORITY_CLASS_INVALID;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public UnderlyingNetworkRecord(
            @NonNull Network network,
            @NonNull NetworkCapabilities networkCapabilities,
            @NonNull LinkProperties linkProperties,
            boolean isBlocked) {
        this.network = network;
        this.networkCapabilities = networkCapabilities;
        this.linkProperties = linkProperties;
        this.isBlocked = isBlocked;
    }

    private int getOrCalculatePriorityClass(
            VcnContext vcnContext,
            List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            ParcelUuid subscriptionGroup,
            TelephonySubscriptionSnapshot snapshot,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        // Never changes after the underlying network record is created.
        if (mPriorityClass == PRIORITY_CLASS_INVALID) {
            mPriorityClass =
                    NetworkPriorityClassifier.calculatePriorityClass(
                            vcnContext,
                            this,
                            underlyingNetworkTemplates,
                            subscriptionGroup,
                            snapshot,
                            currentlySelected,
                            carrierConfig);
        }

        return mPriorityClass;
    }

    // Used in UnderlyingNetworkController
    int getPriorityClass() {
        return mPriorityClass;
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

    static Comparator<UnderlyingNetworkRecord> getComparator(
            VcnContext vcnContext,
            List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            ParcelUuid subscriptionGroup,
            TelephonySubscriptionSnapshot snapshot,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        return (left, right) -> {
            final int leftIndex =
                    left.getOrCalculatePriorityClass(
                            vcnContext,
                            underlyingNetworkTemplates,
                            subscriptionGroup,
                            snapshot,
                            currentlySelected,
                            carrierConfig);
            final int rightIndex =
                    right.getOrCalculatePriorityClass(
                            vcnContext,
                            underlyingNetworkTemplates,
                            subscriptionGroup,
                            snapshot,
                            currentlySelected,
                            carrierConfig);

            // In the case of networks in the same priority class, prioritize based on other
            // criteria (eg. actively selected network, link metrics, etc)
            if (leftIndex == rightIndex) {
                // TODO: Improve the strategy of network selection when both UnderlyingNetworkRecord
                // fall into the same priority class.
                if (isSelected(left, currentlySelected)) {
                    return -1;
                }
                if (isSelected(left, currentlySelected)) {
                    return 1;
                }
            }
            return Integer.compare(leftIndex, rightIndex);
        };
    }

    private static boolean isSelected(
            UnderlyingNetworkRecord recordToCheck, UnderlyingNetworkRecord currentlySelected) {
        if (currentlySelected == null) {
            return false;
        }
        if (currentlySelected.network == recordToCheck.network) {
            return true;
        }
        return false;
    }

    /** Dumps the state of this record for logging and debugging purposes. */
    void dump(
            VcnContext vcnContext,
            IndentingPrintWriter pw,
            List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            ParcelUuid subscriptionGroup,
            TelephonySubscriptionSnapshot snapshot,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        pw.println("UnderlyingNetworkRecord:");
        pw.increaseIndent();

        final int priorityIndex =
                getOrCalculatePriorityClass(
                        vcnContext,
                        underlyingNetworkTemplates,
                        subscriptionGroup,
                        snapshot,
                        currentlySelected,
                        carrierConfig);

        pw.println("Priority index: " + priorityIndex);
        pw.println("mNetwork: " + network);
        pw.println("mNetworkCapabilities: " + networkCapabilities);
        pw.println("mLinkProperties: " + linkProperties);

        pw.decreaseIndent();
    }

    /** Builder to incrementally construct an UnderlyingNetworkRecord. */
    static class Builder {
        @NonNull private final Network mNetwork;

        @Nullable private NetworkCapabilities mNetworkCapabilities;
        @Nullable private LinkProperties mLinkProperties;
        boolean mIsBlocked;
        boolean mWasIsBlockedSet;

        @Nullable private UnderlyingNetworkRecord mCached;

        Builder(@NonNull Network network) {
            mNetwork = network;
        }

        @NonNull
        Network getNetwork() {
            return mNetwork;
        }

        void setNetworkCapabilities(@NonNull NetworkCapabilities networkCapabilities) {
            mNetworkCapabilities = networkCapabilities;
            mCached = null;
        }

        @Nullable
        NetworkCapabilities getNetworkCapabilities() {
            return mNetworkCapabilities;
        }

        void setLinkProperties(@NonNull LinkProperties linkProperties) {
            mLinkProperties = linkProperties;
            mCached = null;
        }

        void setIsBlocked(boolean isBlocked) {
            mIsBlocked = isBlocked;
            mWasIsBlockedSet = true;
            mCached = null;
        }

        boolean isValid() {
            return mNetworkCapabilities != null && mLinkProperties != null && mWasIsBlockedSet;
        }

        UnderlyingNetworkRecord build() {
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
