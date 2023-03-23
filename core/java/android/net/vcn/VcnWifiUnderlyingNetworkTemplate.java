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
package android.net.vcn;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_SERIALIZER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.net.NetworkCapabilities;
import android.os.PersistableBundle;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents a configuration for a network template class of underlying Carrier WiFi
 * networks.
 *
 * <p>See {@link VcnUnderlyingNetworkTemplate}
 */
public final class VcnWifiUnderlyingNetworkTemplate extends VcnUnderlyingNetworkTemplate {
    private static final String SSIDS_KEY = "mSsids";
    @Nullable private final Set<String> mSsids;

    private VcnWifiUnderlyingNetworkTemplate(
            int meteredMatchCriteria,
            int minEntryUpstreamBandwidthKbps,
            int minExitUpstreamBandwidthKbps,
            int minEntryDownstreamBandwidthKbps,
            int minExitDownstreamBandwidthKbps,
            Set<String> ssids) {
        super(
                NETWORK_PRIORITY_TYPE_WIFI,
                meteredMatchCriteria,
                minEntryUpstreamBandwidthKbps,
                minExitUpstreamBandwidthKbps,
                minEntryDownstreamBandwidthKbps,
                minExitDownstreamBandwidthKbps);
        mSsids = new ArraySet<>(ssids);

        validate();
    }

    /** @hide */
    @Override
    protected void validate() {
        super.validate();
        validateSsids(mSsids);
    }

    private static void validateSsids(Set<String> ssids) {
        Objects.requireNonNull(ssids, "ssids is null");

        for (String ssid : ssids) {
            Objects.requireNonNull(ssid, "found null value ssid");
        }
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static VcnWifiUnderlyingNetworkTemplate fromPersistableBundle(
            @NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle is null");

        final int meteredMatchCriteria = in.getInt(METERED_MATCH_KEY);

        final int minEntryUpstreamBandwidthKbps =
                in.getInt(MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS_KEY, DEFAULT_MIN_BANDWIDTH_KBPS);
        final int minExitUpstreamBandwidthKbps =
                in.getInt(MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS_KEY, DEFAULT_MIN_BANDWIDTH_KBPS);
        final int minEntryDownstreamBandwidthKbps =
                in.getInt(MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS_KEY, DEFAULT_MIN_BANDWIDTH_KBPS);
        final int minExitDownstreamBandwidthKbps =
                in.getInt(MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS_KEY, DEFAULT_MIN_BANDWIDTH_KBPS);

        final PersistableBundle ssidsBundle = in.getPersistableBundle(SSIDS_KEY);
        Objects.requireNonNull(ssidsBundle, "ssidsBundle is null");
        final Set<String> ssids =
                new ArraySet<String>(
                        PersistableBundleUtils.toList(ssidsBundle, STRING_DESERIALIZER));
        return new VcnWifiUnderlyingNetworkTemplate(
                meteredMatchCriteria,
                minEntryUpstreamBandwidthKbps,
                minExitUpstreamBandwidthKbps,
                minEntryDownstreamBandwidthKbps,
                minExitDownstreamBandwidthKbps,
                ssids);
    }

    /** @hide */
    @Override
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = super.toPersistableBundle();

        final PersistableBundle ssidsBundle =
                PersistableBundleUtils.fromList(new ArrayList<>(mSsids), STRING_SERIALIZER);
        result.putPersistableBundle(SSIDS_KEY, ssidsBundle);

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSsids);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!super.equals(other)) {
            return false;
        }

        if (!(other instanceof VcnWifiUnderlyingNetworkTemplate)) {
            return false;
        }

        final VcnWifiUnderlyingNetworkTemplate rhs = (VcnWifiUnderlyingNetworkTemplate) other;
        return mSsids.equals(rhs.mSsids);
    }

    /** @hide */
    @Override
    void dumpTransportSpecificFields(IndentingPrintWriter pw) {
        if (!mSsids.isEmpty()) {
            pw.println("mSsids: " + mSsids);
        }
    }

    /**
     * Retrieve the matching SSIDs, or an empty set if any SSID is acceptable.
     *
     * @see Builder#setSsids(Set)
     */
    @NonNull
    public Set<String> getSsids() {
        return Collections.unmodifiableSet(mSsids);
    }

    /** This class is used to incrementally build VcnWifiUnderlyingNetworkTemplate objects. */
    public static final class Builder {
        private int mMeteredMatchCriteria = MATCH_ANY;
        @NonNull private final Set<String> mSsids = new ArraySet<>();

        private int mMinEntryUpstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;
        private int mMinExitUpstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;
        private int mMinEntryDownstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;
        private int mMinExitDownstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;

        /** Construct a Builder object. */
        public Builder() {}

        /**
         * Set the matching criteria for metered networks.
         *
         * <p>A template where setMetered(MATCH_REQUIRED) will only match metered networks (one
         * without NET_CAPABILITY_NOT_METERED). A template where setMetered(MATCH_FORBIDDEN) will
         * only match a network that is not metered (one with NET_CAPABILITY_NOT_METERED).
         *
         * @param matchCriteria the matching criteria for metered networks. Defaults to {@link
         *     #MATCH_ANY}.
         * @see NetworkCapabilities#NET_CAPABILITY_NOT_METERED
         */
        // The matching getter is defined in the super class. Please see {@link
        // VcnUnderlyingNetworkTemplate#getMetered()}
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setMetered(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setMetered");

            mMeteredMatchCriteria = matchCriteria;
            return this;
        }

        /**
         * Set the SSIDs with which a network can match this priority rule.
         *
         * @param ssids the matching SSIDs. Network with one of the matching SSIDs can match this
         *     priority rule. If the set is empty, any SSID will match. The default is an empty set.
         */
        @NonNull
        public Builder setSsids(@NonNull Set<String> ssids) {
            validateSsids(ssids);

            mSsids.clear();
            mSsids.addAll(ssids);
            return this;
        }

        /**
         * Set the minimum upstream bandwidths that this template will match.
         *
         * <p>This template will not match a network that does not provide at least the bandwidth
         * passed as the entry bandwidth, except in the case that the network is selected as the VCN
         * Gateway Connection's underlying network, where it will continue to match until the
         * bandwidth drops under the exit bandwidth.
         *
         * <p>The entry criteria MUST be greater than, or equal to the exit criteria to avoid the
         * invalid case where a network fulfills the entry criteria, but at the same time fails the
         * exit criteria.
         *
         * <p>Estimated bandwidth of a network is provided by the transport layer, and reported in
         * {@link NetworkCapabilities}. The provided estimates will be used without modification.
         *
         * @param minEntryUpstreamBandwidthKbps the minimum accepted upstream bandwidth for networks
         *     that ARE NOT the already-selected underlying network, or {@code 0} to disable this
         *     requirement. Disabled by default.
         * @param minExitUpstreamBandwidthKbps the minimum accepted upstream bandwidth for a network
         *     that IS the already-selected underlying network, or {@code 0} to disable this
         *     requirement. Disabled by default.
         * @return this {@link Builder} instance, for chaining
         */
        @NonNull
        // The getter for the two integers are separated, and in the superclass. Please see {@link
        // VcnUnderlyingNetworkTemplate#getMinEntryUpstreamBandwidthKbps()} and {@link
        // VcnUnderlyingNetworkTemplate#getMinExitUpstreamBandwidthKbps()}
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setMinUpstreamBandwidthKbps(
                int minEntryUpstreamBandwidthKbps, int minExitUpstreamBandwidthKbps) {
            validateMinBandwidthKbps(minEntryUpstreamBandwidthKbps, minExitUpstreamBandwidthKbps);

            mMinEntryUpstreamBandwidthKbps = minEntryUpstreamBandwidthKbps;
            mMinExitUpstreamBandwidthKbps = minExitUpstreamBandwidthKbps;

            return this;
        }

        /**
         * Set the minimum upstream bandwidths that this template will match.
         *
         * <p>This template will not match a network that does not provide at least the bandwidth
         * passed as the entry bandwidth, except in the case that the network is selected as the VCN
         * Gateway Connection's underlying network, where it will continue to match until the
         * bandwidth drops under the exit bandwidth.
         *
         * <p>The entry criteria MUST be greater than, or equal to the exit criteria to avoid the
         * invalid case where a network fulfills the entry criteria, but at the same time fails the
         * exit criteria.
         *
         * <p>Estimated bandwidth of a network is provided by the transport layer, and reported in
         * {@link NetworkCapabilities}. The provided estimates will be used without modification.
         *
         * @param minEntryDownstreamBandwidthKbps the minimum accepted downstream bandwidth for
         *     networks that ARE NOT the already-selected underlying network, or {@code 0} to
         *     disable this requirement. Disabled by default.
         * @param minExitDownstreamBandwidthKbps the minimum accepted downstream bandwidth for a
         *     network that IS the already-selected underlying network, or {@code 0} to disable this
         *     requirement. Disabled by default.
         * @return this {@link Builder} instance, for chaining
         */
        @NonNull
        // The getter for the two integers are separated, and in the superclass. Please see {@link
        // VcnUnderlyingNetworkTemplate#getMinEntryDownstreamBandwidthKbps()} and {@link
        // VcnUnderlyingNetworkTemplate#getMinExitDownstreamBandwidthKbps()}
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setMinDownstreamBandwidthKbps(
                int minEntryDownstreamBandwidthKbps, int minExitDownstreamBandwidthKbps) {
            validateMinBandwidthKbps(
                    minEntryDownstreamBandwidthKbps, minExitDownstreamBandwidthKbps);

            mMinEntryDownstreamBandwidthKbps = minEntryDownstreamBandwidthKbps;
            mMinExitDownstreamBandwidthKbps = minExitDownstreamBandwidthKbps;

            return this;
        }

        /** Build the VcnWifiUnderlyingNetworkTemplate. */
        @NonNull
        public VcnWifiUnderlyingNetworkTemplate build() {
            return new VcnWifiUnderlyingNetworkTemplate(
                    mMeteredMatchCriteria,
                    mMinEntryUpstreamBandwidthKbps,
                    mMinExitUpstreamBandwidthKbps,
                    mMinEntryDownstreamBandwidthKbps,
                    mMinExitDownstreamBandwidthKbps,
                    mSsids);
        }
    }
}
