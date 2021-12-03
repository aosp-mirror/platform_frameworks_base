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
 *
 * @hide
 */
public final class VcnWifiUnderlyingNetworkTemplate extends VcnUnderlyingNetworkTemplate {
    private static final String SSIDS_KEY = "mSsids";
    @Nullable private final Set<String> mSsids;

    private VcnWifiUnderlyingNetworkTemplate(
            int networkQuality, int meteredMatchCriteria, Set<String> ssids) {
        super(NETWORK_PRIORITY_TYPE_WIFI, networkQuality, meteredMatchCriteria);
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

        final int networkQuality = in.getInt(NETWORK_QUALITY_KEY);
        final int meteredMatchCriteria = in.getInt(METERED_MATCH_KEY);

        final PersistableBundle ssidsBundle = in.getPersistableBundle(SSIDS_KEY);
        Objects.requireNonNull(ssidsBundle, "ssidsBundle is null");
        final Set<String> ssids =
                new ArraySet<String>(
                        PersistableBundleUtils.toList(ssidsBundle, STRING_DESERIALIZER));
        return new VcnWifiUnderlyingNetworkTemplate(networkQuality, meteredMatchCriteria, ssids);
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
        pw.println("mSsids: " + mSsids);
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
        private int mNetworkQuality = NETWORK_QUALITY_ANY;
        private int mMeteredMatchCriteria = MATCH_ANY;
        @NonNull private final Set<String> mSsids = new ArraySet<>();

        /** Construct a Builder object. */
        public Builder() {}

        /**
         * Set the required network quality to match this template.
         *
         * <p>Network quality is a aggregation of multiple signals that reflect the network link
         * metrics. For example, the network validation bit (see {@link
         * NetworkCapabilities#NET_CAPABILITY_VALIDATED}), estimated first hop transport bandwidth
         * and signal strength.
         *
         * @param networkQuality the required network quality. Defaults to NETWORK_QUALITY_ANY
         * @hide
         */
        @NonNull
        public Builder setNetworkQuality(@NetworkQuality int networkQuality) {
            validateNetworkQuality(networkQuality);

            mNetworkQuality = networkQuality;
            return this;
        }

        /**
         * Set the matching criteria for metered networks.
         *
         * @param matchCriteria the matching criteria for metered networks. Defaults to {@link
         *     #MATCH_ANY}. See {@link NetworkCapabilities#NET_CAPABILITY_NOT_METERED}
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
         *     priority rule. Defaults to an empty set, allowing ANY SSID.
         */
        @NonNull
        public Builder setSsids(@NonNull Set<String> ssids) {
            validateSsids(ssids);

            mSsids.clear();
            mSsids.addAll(ssids);
            return this;
        }

        /** Build the VcnWifiUnderlyingNetworkTemplate. */
        @NonNull
        public VcnWifiUnderlyingNetworkTemplate build() {
            return new VcnWifiUnderlyingNetworkTemplate(
                    mNetworkQuality, mMeteredMatchCriteria, mSsids);
        }
    }
}
