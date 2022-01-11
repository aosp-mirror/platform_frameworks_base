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
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_SERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_SERIALIZER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

// TODO: Add documents
/** @hide */
public final class VcnCellUnderlyingNetworkTemplate extends VcnUnderlyingNetworkTemplate {
    private static final String ALLOWED_NETWORK_PLMN_IDS_KEY = "mAllowedNetworkPlmnIds";
    @NonNull private final Set<String> mAllowedNetworkPlmnIds;
    private static final String ALLOWED_SPECIFIC_CARRIER_IDS_KEY = "mAllowedSpecificCarrierIds";
    @NonNull private final Set<Integer> mAllowedSpecificCarrierIds;

    private static final String ALLOW_ROAMING_KEY = "mAllowRoaming";
    private final boolean mAllowRoaming;

    private static final String REQUIRE_OPPORTUNISTIC_KEY = "mRequireOpportunistic";
    private final boolean mRequireOpportunistic;

    private VcnCellUnderlyingNetworkTemplate(
            int networkQuality,
            boolean allowMetered,
            Set<String> allowedNetworkPlmnIds,
            Set<Integer> allowedSpecificCarrierIds,
            boolean allowRoaming,
            boolean requireOpportunistic) {
        super(NETWORK_PRIORITY_TYPE_CELL, networkQuality, allowMetered);
        mAllowedNetworkPlmnIds = new ArraySet<>(allowedNetworkPlmnIds);
        mAllowedSpecificCarrierIds = new ArraySet<>(allowedSpecificCarrierIds);
        mAllowRoaming = allowRoaming;
        mRequireOpportunistic = requireOpportunistic;

        validate();
    }

    /** @hide */
    @Override
    protected void validate() {
        super.validate();
        validatePlmnIds(mAllowedNetworkPlmnIds);
        Objects.requireNonNull(mAllowedSpecificCarrierIds, "allowedCarrierIds is null");
    }

    private static void validatePlmnIds(Set<String> allowedNetworkPlmnIds) {
        Objects.requireNonNull(allowedNetworkPlmnIds, "allowedNetworkPlmnIds is null");

        // A valid PLMN is a concatenation of MNC and MCC, and thus consists of 5 or 6 decimal
        // digits.
        for (String id : allowedNetworkPlmnIds) {
            if ((id.length() == 5 || id.length() == 6) && id.matches("[0-9]+")) {
                continue;
            } else {
                throw new IllegalArgumentException("Found invalid PLMN ID: " + id);
            }
        }
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static VcnCellUnderlyingNetworkTemplate fromPersistableBundle(
            @NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle is null");

        final int networkQuality = in.getInt(NETWORK_QUALITY_KEY);
        final boolean allowMetered = in.getBoolean(ALLOW_METERED_KEY);

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

        final boolean allowRoaming = in.getBoolean(ALLOW_ROAMING_KEY);
        final boolean requireOpportunistic = in.getBoolean(REQUIRE_OPPORTUNISTIC_KEY);

        return new VcnCellUnderlyingNetworkTemplate(
                networkQuality,
                allowMetered,
                allowedNetworkPlmnIds,
                allowedSpecificCarrierIds,
                allowRoaming,
                requireOpportunistic);
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

        result.putBoolean(ALLOW_ROAMING_KEY, mAllowRoaming);
        result.putBoolean(REQUIRE_OPPORTUNISTIC_KEY, mRequireOpportunistic);

        return result;
    }

    /** Retrieve the allowed PLMN IDs, or an empty set if any PLMN ID is acceptable. */
    @NonNull
    public Set<String> getAllowedOperatorPlmnIds() {
        return Collections.unmodifiableSet(mAllowedNetworkPlmnIds);
    }

    /**
     * Retrieve the allowed specific carrier IDs, or an empty set if any specific carrier ID is
     * acceptable.
     */
    @NonNull
    public Set<Integer> getAllowedSpecificCarrierIds() {
        return Collections.unmodifiableSet(mAllowedSpecificCarrierIds);
    }

    /** Return if roaming is allowed. */
    public boolean allowRoaming() {
        return mAllowRoaming;
    }

    /** Return if requiring an opportunistic network. */
    public boolean requireOpportunistic() {
        return mRequireOpportunistic;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                mAllowedNetworkPlmnIds,
                mAllowedSpecificCarrierIds,
                mAllowRoaming,
                mRequireOpportunistic);
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
                && mAllowRoaming == rhs.mAllowRoaming
                && mRequireOpportunistic == rhs.mRequireOpportunistic;
    }

    /** @hide */
    @Override
    void dumpTransportSpecificFields(IndentingPrintWriter pw) {
        pw.println("mAllowedNetworkPlmnIds: " + mAllowedNetworkPlmnIds.toString());
        pw.println("mAllowedSpecificCarrierIds: " + mAllowedSpecificCarrierIds.toString());
        pw.println("mAllowRoaming: " + mAllowRoaming);
        pw.println("mRequireOpportunistic: " + mRequireOpportunistic);
    }

    /** This class is used to incrementally build WifiNetworkPriority objects. */
    public static final class Builder extends VcnUnderlyingNetworkTemplate.Builder<Builder> {
        @NonNull private final Set<String> mAllowedNetworkPlmnIds = new ArraySet<>();
        @NonNull private final Set<Integer> mAllowedSpecificCarrierIds = new ArraySet<>();

        private boolean mAllowRoaming = false;
        private boolean mRequireOpportunistic = false;

        /** Construct a Builder object. */
        public Builder() {}

        /**
         * Set allowed operator PLMN IDs.
         *
         * <p>This is used to distinguish cases where roaming agreements may dictate a different
         * priority from a partner's networks.
         *
         * @param allowedNetworkPlmnIds the allowed operator PLMN IDs in String. Defaults to an
         *     empty set, allowing ANY PLMN ID. A valid PLMN is a concatenation of MNC and MCC, and
         *     thus consists of 5 or 6 decimal digits. See {@link SubscriptionInfo#getMccString()}
         *     and {@link SubscriptionInfo#getMncString()}.
         */
        @NonNull
        public Builder setAllowedOperatorPlmnIds(@NonNull Set<String> allowedNetworkPlmnIds) {
            validatePlmnIds(allowedNetworkPlmnIds);

            mAllowedNetworkPlmnIds.clear();
            mAllowedNetworkPlmnIds.addAll(allowedNetworkPlmnIds);
            return this;
        }

        /**
         * Set allowed specific carrier IDs.
         *
         * @param allowedSpecificCarrierIds the allowed specific carrier IDs. Defaults to an empty
         *     set, allowing ANY carrier ID. See {@link TelephonyManager#getSimSpecificCarrierId()}.
         */
        @NonNull
        public Builder setAllowedSpecificCarrierIds(
                @NonNull Set<Integer> allowedSpecificCarrierIds) {
            Objects.requireNonNull(allowedSpecificCarrierIds, "allowedCarrierIds is null");
            mAllowedSpecificCarrierIds.clear();
            mAllowedSpecificCarrierIds.addAll(allowedSpecificCarrierIds);
            return this;
        }

        /**
         * Set if roaming is allowed.
         *
         * @param allowRoaming the flag to indicate if roaming is allowed. Defaults to {@code
         *     false}.
         */
        @NonNull
        public Builder setAllowRoaming(boolean allowRoaming) {
            mAllowRoaming = allowRoaming;
            return this;
        }

        /**
         * Set if requiring an opportunistic network.
         *
         * @param requireOpportunistic the flag to indicate if caller requires an opportunistic
         *     network. Defaults to {@code false}.
         */
        @NonNull
        public Builder setRequireOpportunistic(boolean requireOpportunistic) {
            mRequireOpportunistic = requireOpportunistic;
            return this;
        }

        /** Build the VcnCellUnderlyingNetworkTemplate. */
        @NonNull
        public VcnCellUnderlyingNetworkTemplate build() {
            return new VcnCellUnderlyingNetworkTemplate(
                    mNetworkQuality,
                    mAllowMetered,
                    mAllowedNetworkPlmnIds,
                    mAllowedSpecificCarrierIds,
                    mAllowRoaming,
                    mRequireOpportunistic);
        }

        /** @hide */
        @Override
        Builder self() {
            return this;
        }
    }
}
