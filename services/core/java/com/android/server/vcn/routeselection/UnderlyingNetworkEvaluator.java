/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnUnderlyingNetworkTemplate;
import android.os.ParcelUuid;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * UnderlyingNetworkEvaluator evaluates the quality and priority class of a network candidate for
 * route selection.
 *
 * @hide
 */
public class UnderlyingNetworkEvaluator {
    private static final String TAG = UnderlyingNetworkEvaluator.class.getSimpleName();

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final UnderlyingNetworkRecord.Builder mNetworkRecordBuilder;

    private boolean mIsSelected;
    private int mPriorityClass = NetworkPriorityClassifier.PRIORITY_INVALID;

    public UnderlyingNetworkEvaluator(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mVcnContext = Objects.requireNonNull(vcnContext, "Missing vcnContext");

        Objects.requireNonNull(underlyingNetworkTemplates, "Missing underlyingNetworkTemplates");
        Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        Objects.requireNonNull(lastSnapshot, "Missing lastSnapshot");

        mNetworkRecordBuilder =
                new UnderlyingNetworkRecord.Builder(
                        Objects.requireNonNull(network, "Missing network"));
        mIsSelected = false;

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    private void updatePriorityClass(
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        if (mNetworkRecordBuilder.isValid()) {
            mPriorityClass =
                    NetworkPriorityClassifier.calculatePriorityClass(
                            mVcnContext,
                            mNetworkRecordBuilder.build(),
                            underlyingNetworkTemplates,
                            subscriptionGroup,
                            lastSnapshot,
                            mIsSelected,
                            carrierConfig);
        } else {
            mPriorityClass = NetworkPriorityClassifier.PRIORITY_INVALID;
        }
    }

    public static Comparator<UnderlyingNetworkEvaluator> getComparator() {
        return (left, right) -> {
            final int leftIndex = left.mPriorityClass;
            final int rightIndex = right.mPriorityClass;

            // In the case of networks in the same priority class, prioritize based on other
            // criteria (eg. actively selected network, link metrics, etc)
            if (leftIndex == rightIndex) {
                // TODO: Improve the strategy of network selection when both UnderlyingNetworkRecord
                // fall into the same priority class.
                if (left.mIsSelected) {
                    return -1;
                }
                if (right.mIsSelected) {
                    return 1;
                }
            }
            return Integer.compare(leftIndex, rightIndex);
        };
    }

    /** Set the NetworkCapabilities */
    public void setNetworkCapabilities(
            @NonNull NetworkCapabilities nc,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mNetworkRecordBuilder.setNetworkCapabilities(nc);

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /** Set the LinkProperties */
    public void setLinkProperties(
            @NonNull LinkProperties lp,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mNetworkRecordBuilder.setLinkProperties(lp);

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /** Set whether the network is blocked */
    public void setIsBlocked(
            boolean isBlocked,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mNetworkRecordBuilder.setIsBlocked(isBlocked);

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /** Set whether the network is selected as VCN's underlying network */
    public void setIsSelected(
            boolean isSelected,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mIsSelected = isSelected;

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /**
     * Update the last TelephonySubscriptionSnapshot and carrier config to reevaluate the network
     */
    public void reevaluate(
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /** Return whether this network evaluator is valid */
    public boolean isValid() {
        return mNetworkRecordBuilder.isValid();
    }

    /** Return the network */
    public Network getNetwork() {
        return mNetworkRecordBuilder.getNetwork();
    }

    /** Return the network record */
    public UnderlyingNetworkRecord getNetworkRecord() {
        return mNetworkRecordBuilder.build();
    }

    /** Return the priority class for network selection */
    public int getPriorityClass() {
        return mPriorityClass;
    }

    /** Dump the information of this instance */
    public void dump(IndentingPrintWriter pw) {
        pw.println("UnderlyingNetworkEvaluator:");
        pw.increaseIndent();

        if (mNetworkRecordBuilder.isValid()) {
            getNetworkRecord().dump(pw);
        } else {
            pw.println(
                    "UnderlyingNetworkRecord incomplete: mNetwork: "
                            + mNetworkRecordBuilder.getNetwork());
        }

        pw.println("mIsSelected: " + mIsSelected);
        pw.println("mPriorityClass: " + mPriorityClass);

        pw.decreaseIndent();
    }
}
