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

import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_RCS;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_ANY;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.getMatchCriteriaString;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_SERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_SERIALIZER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnUnderlyingNetworkTemplate.MatchCriteria;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents a configuration for a network template class of underlying cellular
 * networks.
 *
 * <p>See {@link VcnUnderlyingNetworkTemplate}
 */
public final class VcnCellUnderlyingNetworkTemplate extends VcnUnderlyingNetworkTemplate {
    private static final String ALLOWED_NETWORK_PLMN_IDS_KEY = "mAllowedNetworkPlmnIds";
    @NonNull private final Set<String> mAllowedNetworkPlmnIds;
    private static final String ALLOWED_SPECIFIC_CARRIER_IDS_KEY = "mAllowedSpecificCarrierIds";
    @NonNull private final Set<Integer> mAllowedSpecificCarrierIds;

    private static final String ROAMING_MATCH_KEY = "mRoamingMatchCriteria";
    private static final int DEFAULT_ROAMING_MATCH_CRITERIA = MATCH_ANY;
    private final int mRoamingMatchCriteria;

    private static final String OPPORTUNISTIC_MATCH_KEY = "mOpportunisticMatchCriteria";
    private static final int DEFAULT_OPPORTUNISTIC_MATCH_CRITERIA = MATCH_ANY;
    private final int mOpportunisticMatchCriteria;

    private static final String CAPABILITIES_MATCH_CRITERIA_KEY = "mCapabilitiesMatchCriteria";
    @NonNull private final Map<Integer, Integer> mCapabilitiesMatchCriteria;

    private static final Map<Integer, Integer> CAPABILITIES_MATCH_CRITERIA_DEFAULT;

    static {
        Map<Integer, Integer> capsMatchCriteria = new HashMap<>();
        capsMatchCriteria.put(NET_CAPABILITY_CBS, MATCH_ANY);
        capsMatchCriteria.put(NET_CAPABILITY_DUN, MATCH_ANY);
        capsMatchCriteria.put(NET_CAPABILITY_IMS, MATCH_ANY);
        capsMatchCriteria.put(NET_CAPABILITY_INTERNET, MATCH_REQUIRED);
        capsMatchCriteria.put(NET_CAPABILITY_MMS, MATCH_ANY);
        capsMatchCriteria.put(NET_CAPABILITY_RCS, MATCH_ANY);

        CAPABILITIES_MATCH_CRITERIA_DEFAULT = Collections.unmodifiableMap(capsMatchCriteria);
    }

    private VcnCellUnderlyingNetworkTemplate(
            int meteredMatchCriteria,
            int minEntryUpstreamBandwidthKbps,
            int minExitUpstreamBandwidthKbps,
            int minEntryDownstreamBandwidthKbps,
            int minExitDownstreamBandwidthKbps,
            Set<String> allowedNetworkPlmnIds,
            Set<Integer> allowedSpecificCarrierIds,
            int roamingMatchCriteria,
            int opportunisticMatchCriteria,
            Map<Integer, Integer> capabilitiesMatchCriteria) {
        super(
                NETWORK_PRIORITY_TYPE_CELL,
                meteredMatchCriteria,
                minEntryUpstreamBandwidthKbps,
                minExitUpstreamBandwidthKbps,
                minEntryDownstreamBandwidthKbps,
                minExitDownstreamBandwidthKbps);
        mAllowedNetworkPlmnIds = new ArraySet<>(allowedNetworkPlmnIds);
        mAllowedSpecificCarrierIds = new ArraySet<>(allowedSpecificCarrierIds);
        mRoamingMatchCriteria = roamingMatchCriteria;
        mOpportunisticMatchCriteria = opportunisticMatchCriteria;
        mCapabilitiesMatchCriteria = new HashMap<>(capabilitiesMatchCriteria);

        validate();
    }

    /** @hide */
    @Override
    protected void validate() {
        super.validate();
        validatePlmnIds(mAllowedNetworkPlmnIds);
        validateCapabilitiesMatchCriteria(mCapabilitiesMatchCriteria);
        Objects.requireNonNull(mAllowedSpecificCarrierIds, "matchingCarrierIds is null");
        validateMatchCriteria(mRoamingMatchCriteria, "mRoamingMatchCriteria");
        validateMatchCriteria(mOpportunisticMatchCriteria, "mOpportunisticMatchCriteria");
    }

    private static void validatePlmnIds(Set<String> matchingOperatorPlmnIds) {
        Objects.requireNonNull(matchingOperatorPlmnIds, "matchingOperatorPlmnIds is null");

        // A valid PLMN is a concatenation of MNC and MCC, and thus consists of 5 or 6 decimal
        // digits.
        for (String id : matchingOperatorPlmnIds) {
            if ((id.length() == 5 || id.length() == 6) && id.matches("[0-9]+")) {
                continue;
            } else {
                throw new IllegalArgumentException("Found invalid PLMN ID: " + id);
            }
        }
    }

    private static void validateCapabilitiesMatchCriteria(
            Map<Integer, Integer> capabilitiesMatchCriteria) {
        Objects.requireNonNull(capabilitiesMatchCriteria, "capabilitiesMatchCriteria is null");

        boolean requiredCapabilityFound = false;
        for (Map.Entry<Integer, Integer> entry : capabilitiesMatchCriteria.entrySet()) {
            final int capability = entry.getKey();
            final int matchCriteria = entry.getValue();

            Preconditions.checkArgument(
                    CAPABILITIES_MATCH_CRITERIA_DEFAULT.containsKey(capability),
                    "NetworkCapability " + capability + "out of range");
            validateMatchCriteria(matchCriteria, "capability " + capability);

            requiredCapabilityFound |= (matchCriteria == MATCH_REQUIRED);
        }

        if (!requiredCapabilityFound) {
            throw new IllegalArgumentException("No required capabilities found");
        }
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static VcnCellUnderlyingNetworkTemplate fromPersistableBundle(
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

        final PersistableBundle plmnIdsBundle =
                in.getPersistableBundle(ALLOWED_NETWORK_PLMN_IDS_KEY);
        Objects.requireNonNull(plmnIdsBundle, "plmnIdsBundle is null");
        final Set<String> allowedNetworkPlmnIds =
                new ArraySet<String>(
                        PersistableBundleUtils.toList(plmnIdsBundle, STRING_DESERIALIZER));

        final PersistableBundle specificCarrierIdsBundle =
                in.getPersistableBundle(ALLOWED_SPECIFIC_CARRIER_IDS_KEY);
        Objects.requireNonNull(specificCarrierIdsBundle, "specificCarrierIdsBundle is null");
        final Set<Integer> allowedSpecificCarrierIds =
                new ArraySet<Integer>(
                        PersistableBundleUtils.toList(
                                specificCarrierIdsBundle, INTEGER_DESERIALIZER));

        final PersistableBundle capabilitiesMatchCriteriaBundle =
                in.getPersistableBundle(CAPABILITIES_MATCH_CRITERIA_KEY);
        final Map<Integer, Integer> capabilitiesMatchCriteria;
        if (capabilitiesMatchCriteriaBundle == null) {
            capabilitiesMatchCriteria = CAPABILITIES_MATCH_CRITERIA_DEFAULT;
        } else {
            capabilitiesMatchCriteria =
                    PersistableBundleUtils.toMap(
                            capabilitiesMatchCriteriaBundle,
                            INTEGER_DESERIALIZER,
                            INTEGER_DESERIALIZER);
        }

        final int roamingMatchCriteria = in.getInt(ROAMING_MATCH_KEY);
        final int opportunisticMatchCriteria = in.getInt(OPPORTUNISTIC_MATCH_KEY);

        return new VcnCellUnderlyingNetworkTemplate(
                meteredMatchCriteria,
                minEntryUpstreamBandwidthKbps,
                minExitUpstreamBandwidthKbps,
                minEntryDownstreamBandwidthKbps,
                minExitDownstreamBandwidthKbps,
                allowedNetworkPlmnIds,
                allowedSpecificCarrierIds,
                roamingMatchCriteria,
                opportunisticMatchCriteria,
                capabilitiesMatchCriteria);
    }

    /** @hide */
    @Override
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = super.toPersistableBundle();

        final PersistableBundle plmnIdsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mAllowedNetworkPlmnIds), STRING_SERIALIZER);
        result.putPersistableBundle(ALLOWED_NETWORK_PLMN_IDS_KEY, plmnIdsBundle);

        final PersistableBundle specificCarrierIdsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mAllowedSpecificCarrierIds), INTEGER_SERIALIZER);
        result.putPersistableBundle(ALLOWED_SPECIFIC_CARRIER_IDS_KEY, specificCarrierIdsBundle);

        final PersistableBundle capabilitiesMatchCriteriaBundle =
                PersistableBundleUtils.fromMap(
                        mCapabilitiesMatchCriteria, INTEGER_SERIALIZER, INTEGER_SERIALIZER);
        result.putPersistableBundle(
                CAPABILITIES_MATCH_CRITERIA_KEY, capabilitiesMatchCriteriaBundle);

        result.putInt(ROAMING_MATCH_KEY, mRoamingMatchCriteria);
        result.putInt(OPPORTUNISTIC_MATCH_KEY, mOpportunisticMatchCriteria);

        return result;
    }

    /**
     * Retrieve the matching operator PLMN IDs, or an empty set if any PLMN ID is acceptable.
     *
     * @see Builder#setOperatorPlmnIds(Set)
     */
    @NonNull
    public Set<String> getOperatorPlmnIds() {
        return Collections.unmodifiableSet(mAllowedNetworkPlmnIds);
    }

    /**
     * Retrieve the matching sim specific carrier IDs, or an empty set if any sim specific carrier
     * ID is acceptable.
     *
     * @see Builder#setSimSpecificCarrierIds(Set)
     */
    @NonNull
    public Set<Integer> getSimSpecificCarrierIds() {
        return Collections.unmodifiableSet(mAllowedSpecificCarrierIds);
    }

    /**
     * Return the matching criteria for roaming networks.
     *
     * @see Builder#setRoaming(int)
     */
    @MatchCriteria
    public int getRoaming() {
        return mRoamingMatchCriteria;
    }

    /**
     * Return the matching criteria for opportunistic cellular subscriptions.
     *
     * @see Builder#setOpportunistic(int)
     */
    @MatchCriteria
    public int getOpportunistic() {
        return mOpportunisticMatchCriteria;
    }

    /**
     * Returns the matching criteria for CBS networks.
     *
     * @see Builder#setCbs(int)
     */
    @MatchCriteria
    public int getCbs() {
        return mCapabilitiesMatchCriteria.get(NET_CAPABILITY_CBS);
    }

    /**
     * Returns the matching criteria for DUN networks.
     *
     * @see Builder#setDun(int)
     */
    @MatchCriteria
    public int getDun() {
        return mCapabilitiesMatchCriteria.get(NET_CAPABILITY_DUN);
    }
    /**
     * Returns the matching criteria for IMS networks.
     *
     * @see Builder#setIms(int)
     */
    @MatchCriteria
    public int getIms() {
        return mCapabilitiesMatchCriteria.get(NET_CAPABILITY_IMS);
    }
    /**
     * Returns the matching criteria for INTERNET networks.
     *
     * @see Builder#setInternet(int)
     */
    @MatchCriteria
    public int getInternet() {
        return mCapabilitiesMatchCriteria.get(NET_CAPABILITY_INTERNET);
    }
    /**
     * Returns the matching criteria for MMS networks.
     *
     * @see Builder#setMms(int)
     */
    @MatchCriteria
    public int getMms() {
        return mCapabilitiesMatchCriteria.get(NET_CAPABILITY_MMS);
    }

    /**
     * Returns the matching criteria for RCS networks.
     *
     * @see Builder#setRcs(int)
     */
    @MatchCriteria
    public int getRcs() {
        return mCapabilitiesMatchCriteria.get(NET_CAPABILITY_RCS);
    }

    /** @hide */
    @Override
    public Map<Integer, Integer> getCapabilitiesMatchCriteria() {
        return Collections.unmodifiableMap(new HashMap<>(mCapabilitiesMatchCriteria));
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                mAllowedNetworkPlmnIds,
                mAllowedSpecificCarrierIds,
                mCapabilitiesMatchCriteria,
                mRoamingMatchCriteria,
                mOpportunisticMatchCriteria);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!super.equals(other)) {
            return false;
        }

        if (!(other instanceof VcnCellUnderlyingNetworkTemplate)) {
            return false;
        }

        final VcnCellUnderlyingNetworkTemplate rhs = (VcnCellUnderlyingNetworkTemplate) other;
        return Objects.equals(mAllowedNetworkPlmnIds, rhs.mAllowedNetworkPlmnIds)
                && Objects.equals(mAllowedSpecificCarrierIds, rhs.mAllowedSpecificCarrierIds)
                && Objects.equals(mCapabilitiesMatchCriteria, rhs.mCapabilitiesMatchCriteria)
                && mRoamingMatchCriteria == rhs.mRoamingMatchCriteria
                && mOpportunisticMatchCriteria == rhs.mOpportunisticMatchCriteria;
    }

    /** @hide */
    @Override
    void dumpTransportSpecificFields(IndentingPrintWriter pw) {
        if (!mAllowedNetworkPlmnIds.isEmpty()) {
            pw.println("mAllowedNetworkPlmnIds: " + mAllowedNetworkPlmnIds);
        }
        if (!mAllowedNetworkPlmnIds.isEmpty()) {
            pw.println("mAllowedSpecificCarrierIds: " + mAllowedSpecificCarrierIds);
        }
        pw.println("mCapabilitiesMatchCriteria: " + mCapabilitiesMatchCriteria);
        if (mRoamingMatchCriteria != DEFAULT_ROAMING_MATCH_CRITERIA) {
            pw.println("mRoamingMatchCriteria: " + getMatchCriteriaString(mRoamingMatchCriteria));
        }
        if (mOpportunisticMatchCriteria != DEFAULT_OPPORTUNISTIC_MATCH_CRITERIA) {
            pw.println(
                    "mOpportunisticMatchCriteria: "
                            + getMatchCriteriaString(mOpportunisticMatchCriteria));
        }
    }

    /** This class is used to incrementally build VcnCellUnderlyingNetworkTemplate objects. */
    public static final class Builder {
        private int mMeteredMatchCriteria = DEFAULT_METERED_MATCH_CRITERIA;

        @NonNull private final Set<String> mAllowedNetworkPlmnIds = new ArraySet<>();
        @NonNull private final Set<Integer> mAllowedSpecificCarrierIds = new ArraySet<>();
        @NonNull private final Map<Integer, Integer> mCapabilitiesMatchCriteria = new HashMap<>();

        private int mRoamingMatchCriteria = DEFAULT_ROAMING_MATCH_CRITERIA;
        private int mOpportunisticMatchCriteria = DEFAULT_OPPORTUNISTIC_MATCH_CRITERIA;

        private int mMinEntryUpstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;
        private int mMinExitUpstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;
        private int mMinEntryDownstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;
        private int mMinExitDownstreamBandwidthKbps = DEFAULT_MIN_BANDWIDTH_KBPS;

        /** Construct a Builder object. */
        public Builder() {
            mCapabilitiesMatchCriteria.putAll(CAPABILITIES_MATCH_CRITERIA_DEFAULT);
        }

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
         * Set operator PLMN IDs with which a network can match this template.
         *
         * <p>This is used to distinguish cases where roaming agreements may dictate a different
         * priority from a partner's networks.
         *
         * @param operatorPlmnIds the matching operator PLMN IDs in String. Network with one of the
         *     matching PLMN IDs can match this template. If the set is empty, any PLMN ID will
         *     match. The default is an empty set. A valid PLMN is a concatenation of MNC and MCC,
         *     and thus consists of 5 or 6 decimal digits.
         * @see SubscriptionInfo#getMccString()
         * @see SubscriptionInfo#getMncString()
         */
        @NonNull
        public Builder setOperatorPlmnIds(@NonNull Set<String> operatorPlmnIds) {
            validatePlmnIds(operatorPlmnIds);

            mAllowedNetworkPlmnIds.clear();
            mAllowedNetworkPlmnIds.addAll(operatorPlmnIds);
            return this;
        }

        /**
         * Set sim specific carrier IDs with which a network can match this template.
         *
         * @param simSpecificCarrierIds the matching sim specific carrier IDs. Network with one of
         *     the sim specific carrier IDs can match this template. If the set is empty, any
         *     carrier ID will match. The default is an empty set.
         * @see TelephonyManager#getSimSpecificCarrierId()
         */
        @NonNull
        public Builder setSimSpecificCarrierIds(@NonNull Set<Integer> simSpecificCarrierIds) {
            Objects.requireNonNull(simSpecificCarrierIds, "simSpecificCarrierIds is null");

            mAllowedSpecificCarrierIds.clear();
            mAllowedSpecificCarrierIds.addAll(simSpecificCarrierIds);
            return this;
        }

        /**
         * Set the matching criteria for roaming networks.
         *
         * <p>A template where setRoaming(MATCH_REQUIRED) will only match roaming networks (one
         * without NET_CAPABILITY_NOT_ROAMING). A template where setRoaming(MATCH_FORBIDDEN) will
         * only match a network that is not roaming (one with NET_CAPABILITY_NOT_ROAMING).
         *
         * @param matchCriteria the matching criteria for roaming networks. Defaults to {@link
         *     #MATCH_ANY}.
         * @see NetworkCapabilities#NET_CAPABILITY_NOT_ROAMING
         */
        @NonNull
        public Builder setRoaming(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setRoaming");

            mRoamingMatchCriteria = matchCriteria;
            return this;
        }

        /**
         * Set the matching criteria for opportunistic cellular subscriptions.
         *
         * @param matchCriteria the matching criteria for opportunistic cellular subscriptions.
         *     Defaults to {@link #MATCH_ANY}.
         * @see SubscriptionManager#setOpportunistic(boolean, int)
         */
        @NonNull
        public Builder setOpportunistic(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setOpportunistic");

            mOpportunisticMatchCriteria = matchCriteria;
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

        /**
         * Sets the matching criteria for CBS networks.
         *
         * <p>A template where {@code setCbs(MATCH_REQUIRED)} is called will only match CBS networks
         * (ones with NET_CAPABILITY_CBS). A template where {@code setCbs(MATCH_FORBIDDEN)} is
         * called will only match networks that do not support CBS (ones without
         * NET_CAPABILITY_CBS).
         *
         * @param matchCriteria the matching criteria for CBS networks. Defaults to {@link
         *     #MATCH_ANY}.
         * @see NetworkCapabilities#NET_CAPABILITY_CBS
         */
        @NonNull
        public Builder setCbs(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setCbs");

            mCapabilitiesMatchCriteria.put(NET_CAPABILITY_CBS, matchCriteria);
            return this;
        }

        /**
         * Sets the matching criteria for DUN networks.
         *
         * <p>A template where {@code setDun(MATCH_REQUIRED)} is called will only match DUN networks
         * (ones with NET_CAPABILITY_DUN). A template where {@code setDun(MATCH_FORBIDDEN)} is
         * called will only match networks that do not support DUN (ones without
         * NET_CAPABILITY_DUN).
         *
         * @param matchCriteria the matching criteria for DUN networks. Defaults to {@link
         *     #MATCH_ANY}.
         * @see NetworkCapabilities#NET_CAPABILITY_DUN
         */
        @NonNull
        public Builder setDun(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setDun");

            mCapabilitiesMatchCriteria.put(NET_CAPABILITY_DUN, matchCriteria);
            return this;
        }

        /**
         * Sets the matching criteria for IMS networks.
         *
         * <p>A template where {@code setIms(MATCH_REQUIRED)} is called will only match IMS networks
         * (ones with NET_CAPABILITY_IMS). A template where {@code setIms(MATCH_FORBIDDEN)} is
         * called will only match networks that do not support IMS (ones without
         * NET_CAPABILITY_IMS).
         *
         * @param matchCriteria the matching criteria for IMS networks. Defaults to {@link
         *     #MATCH_ANY}.
         * @see NetworkCapabilities#NET_CAPABILITY_IMS
         */
        @NonNull
        public Builder setIms(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setIms");

            mCapabilitiesMatchCriteria.put(NET_CAPABILITY_IMS, matchCriteria);
            return this;
        }

        /**
         * Sets the matching criteria for INTERNET networks.
         *
         * <p>A template where {@code setInternet(MATCH_REQUIRED)} is called will only match
         * INTERNET networks (ones with NET_CAPABILITY_INTERNET). A template where {@code
         * setInternet(MATCH_FORBIDDEN)} is called will only match networks that do not support
         * INTERNET (ones without NET_CAPABILITY_INTERNET).
         *
         * @param matchCriteria the matching criteria for INTERNET networks. Defaults to {@link
         *     #MATCH_REQUIRED}.
         * @see NetworkCapabilities#NET_CAPABILITY_INTERNET
         */
        @NonNull
        public Builder setInternet(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setInternet");

            mCapabilitiesMatchCriteria.put(NET_CAPABILITY_INTERNET, matchCriteria);
            return this;
        }

        /**
         * Sets the matching criteria for MMS networks.
         *
         * <p>A template where {@code setMms(MATCH_REQUIRED)} is called will only match MMS networks
         * (ones with NET_CAPABILITY_MMS). A template where {@code setMms(MATCH_FORBIDDEN)} is
         * called will only match networks that do not support MMS (ones without
         * NET_CAPABILITY_MMS).
         *
         * @param matchCriteria the matching criteria for MMS networks. Defaults to {@link
         *     #MATCH_ANY}.
         * @see NetworkCapabilities#NET_CAPABILITY_MMS
         */
        @NonNull
        public Builder setMms(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setMms");

            mCapabilitiesMatchCriteria.put(NET_CAPABILITY_MMS, matchCriteria);
            return this;
        }

        /**
         * Sets the matching criteria for RCS networks.
         *
         * <p>A template where {@code setRcs(MATCH_REQUIRED)} is called will only match RCS networks
         * (ones with NET_CAPABILITY_RCS). A template where {@code setRcs(MATCH_FORBIDDEN)} is
         * called will only match networks that do not support RCS (ones without
         * NET_CAPABILITY_RCS).
         *
         * @param matchCriteria the matching criteria for RCS networks. Defaults to {@link
         *     #MATCH_ANY}.
         * @see NetworkCapabilities#NET_CAPABILITY_RCS
         */
        @NonNull
        public Builder setRcs(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setRcs");

            mCapabilitiesMatchCriteria.put(NET_CAPABILITY_RCS, matchCriteria);
            return this;
        }

        /** Build the VcnCellUnderlyingNetworkTemplate. */
        @NonNull
        public VcnCellUnderlyingNetworkTemplate build() {
            return new VcnCellUnderlyingNetworkTemplate(
                    mMeteredMatchCriteria,
                    mMinEntryUpstreamBandwidthKbps,
                    mMinExitUpstreamBandwidthKbps,
                    mMinEntryDownstreamBandwidthKbps,
                    mMinExitDownstreamBandwidthKbps,
                    mAllowedNetworkPlmnIds,
                    mAllowedSpecificCarrierIds,
                    mRoamingMatchCriteria,
                    mOpportunisticMatchCriteria,
                    mCapabilitiesMatchCriteria);
        }
    }
}
