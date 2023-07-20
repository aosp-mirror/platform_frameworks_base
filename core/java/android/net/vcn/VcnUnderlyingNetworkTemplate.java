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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * This class represents a template containing set of underlying network requirements for doing
 * route selection.
 *
 * <p>Apps provisioning a VCN can configure the underlying network priority for each Gateway
 * Connection by setting a list (in priority order, most to least preferred) of the appropriate
 * subclasses in the VcnGatewayConnectionConfig. See {@link
 * VcnGatewayConnectionConfig.Builder#setVcnUnderlyingNetworkPriorities}
 */
public abstract class VcnUnderlyingNetworkTemplate {
    /** @hide */
    static final int NETWORK_PRIORITY_TYPE_WIFI = 1;
    /** @hide */
    static final int NETWORK_PRIORITY_TYPE_CELL = 2;

    /**
     * Used to configure the matching criteria of a network characteristic. This may include network
     * capabilities, or cellular subscription information. Denotes that networks with or without the
     * characteristic are both acceptable to match this template.
     */
    public static final int MATCH_ANY = 0;

    /**
     * Used to configure the matching criteria of a network characteristic. This may include network
     * capabilities, or cellular subscription information. Denotes that a network MUST have the
     * capability in order to match this template.
     */
    public static final int MATCH_REQUIRED = 1;

    /**
     * Used to configure the matching criteria of a network characteristic. This may include network
     * capabilities, or cellular subscription information. Denotes that a network MUST NOT have the
     * capability in order to match this template.
     */
    public static final int MATCH_FORBIDDEN = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MATCH_ANY, MATCH_REQUIRED, MATCH_FORBIDDEN})
    public @interface MatchCriteria {}

    private static final SparseArray<String> MATCH_CRITERIA_TO_STRING_MAP = new SparseArray<>();

    static {
        MATCH_CRITERIA_TO_STRING_MAP.put(MATCH_ANY, "MATCH_ANY");
        MATCH_CRITERIA_TO_STRING_MAP.put(MATCH_REQUIRED, "MATCH_REQUIRED");
        MATCH_CRITERIA_TO_STRING_MAP.put(MATCH_FORBIDDEN, "MATCH_FORBIDDEN");
    }

    private static final String NETWORK_PRIORITY_TYPE_KEY = "mNetworkPriorityType";
    private final int mNetworkPriorityType;

    /** @hide */
    static final String METERED_MATCH_KEY = "mMeteredMatchCriteria";

    /** @hide */
    static final int DEFAULT_METERED_MATCH_CRITERIA = MATCH_ANY;

    private final int mMeteredMatchCriteria;

    /** @hide */
    public static final int DEFAULT_MIN_BANDWIDTH_KBPS = 0;

    /** @hide */
    static final String MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS_KEY = "mMinEntryUpstreamBandwidthKbps";

    private final int mMinEntryUpstreamBandwidthKbps;

    /** @hide */
    static final String MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS_KEY = "mMinExitUpstreamBandwidthKbps";

    private final int mMinExitUpstreamBandwidthKbps;

    /** @hide */
    static final String MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS_KEY =
            "mMinEntryDownstreamBandwidthKbps";

    private final int mMinEntryDownstreamBandwidthKbps;

    /** @hide */
    static final String MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS_KEY = "mMinExitDownstreamBandwidthKbps";

    private final int mMinExitDownstreamBandwidthKbps;

    /** @hide */
    VcnUnderlyingNetworkTemplate(
            int networkPriorityType,
            int meteredMatchCriteria,
            int minEntryUpstreamBandwidthKbps,
            int minExitUpstreamBandwidthKbps,
            int minEntryDownstreamBandwidthKbps,
            int minExitDownstreamBandwidthKbps) {
        mNetworkPriorityType = networkPriorityType;
        mMeteredMatchCriteria = meteredMatchCriteria;
        mMinEntryUpstreamBandwidthKbps = minEntryUpstreamBandwidthKbps;
        mMinExitUpstreamBandwidthKbps = minExitUpstreamBandwidthKbps;
        mMinEntryDownstreamBandwidthKbps = minEntryDownstreamBandwidthKbps;
        mMinExitDownstreamBandwidthKbps = minExitDownstreamBandwidthKbps;
    }

    /** @hide */
    static void validateMatchCriteria(int matchCriteria, String matchingCapability) {
        Preconditions.checkArgument(
                MATCH_CRITERIA_TO_STRING_MAP.contains(matchCriteria),
                "Invalid matching criteria: " + matchCriteria + " for " + matchingCapability);
    }

    /** @hide */
    static void validateMinBandwidthKbps(int minEntryBandwidth, int minExitBandwidth) {
        Preconditions.checkArgument(
                minEntryBandwidth >= 0, "Invalid minEntryBandwidth, must be >= 0");
        Preconditions.checkArgument(
                minExitBandwidth >= 0, "Invalid minExitBandwidth, must be >= 0");
        Preconditions.checkArgument(
                minEntryBandwidth >= minExitBandwidth,
                "Minimum entry bandwidth must be >= exit bandwidth");
    }

    /** @hide */
    protected void validate() {
        validateMatchCriteria(mMeteredMatchCriteria, "mMeteredMatchCriteria");
        validateMinBandwidthKbps(mMinEntryUpstreamBandwidthKbps, mMinExitUpstreamBandwidthKbps);
        validateMinBandwidthKbps(mMinEntryDownstreamBandwidthKbps, mMinExitDownstreamBandwidthKbps);
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static VcnUnderlyingNetworkTemplate fromPersistableBundle(
            @NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle is null");

        final int networkPriorityType = in.getInt(NETWORK_PRIORITY_TYPE_KEY);
        switch (networkPriorityType) {
            case NETWORK_PRIORITY_TYPE_WIFI:
                return VcnWifiUnderlyingNetworkTemplate.fromPersistableBundle(in);
            case NETWORK_PRIORITY_TYPE_CELL:
                return VcnCellUnderlyingNetworkTemplate.fromPersistableBundle(in);
            default:
                throw new IllegalArgumentException(
                        "Invalid networkPriorityType:" + networkPriorityType);
        }
    }

    /** @hide */
    @NonNull
    PersistableBundle toPersistableBundle() {
        final PersistableBundle result = new PersistableBundle();

        result.putInt(NETWORK_PRIORITY_TYPE_KEY, mNetworkPriorityType);
        result.putInt(METERED_MATCH_KEY, mMeteredMatchCriteria);
        result.putInt(MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS_KEY, mMinEntryUpstreamBandwidthKbps);
        result.putInt(MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS_KEY, mMinExitUpstreamBandwidthKbps);
        result.putInt(MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS_KEY, mMinEntryDownstreamBandwidthKbps);
        result.putInt(MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS_KEY, mMinExitDownstreamBandwidthKbps);

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mNetworkPriorityType,
                mMeteredMatchCriteria,
                mMinEntryUpstreamBandwidthKbps,
                mMinExitUpstreamBandwidthKbps,
                mMinEntryDownstreamBandwidthKbps,
                mMinExitDownstreamBandwidthKbps);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VcnUnderlyingNetworkTemplate)) {
            return false;
        }

        final VcnUnderlyingNetworkTemplate rhs = (VcnUnderlyingNetworkTemplate) other;
        return mNetworkPriorityType == rhs.mNetworkPriorityType
                && mMeteredMatchCriteria == rhs.mMeteredMatchCriteria
                && mMinEntryUpstreamBandwidthKbps == rhs.mMinEntryUpstreamBandwidthKbps
                && mMinExitUpstreamBandwidthKbps == rhs.mMinExitUpstreamBandwidthKbps
                && mMinEntryDownstreamBandwidthKbps == rhs.mMinEntryDownstreamBandwidthKbps
                && mMinExitDownstreamBandwidthKbps == rhs.mMinExitDownstreamBandwidthKbps;
    }

    /** @hide */
    static String getNameString(SparseArray<String> toStringMap, int key) {
        return toStringMap.get(key, "Invalid value " + key);
    }

    /** @hide */
    static String getMatchCriteriaString(int matchCriteria) {
        return getNameString(MATCH_CRITERIA_TO_STRING_MAP, matchCriteria);
    }

    /** @hide */
    abstract void dumpTransportSpecificFields(IndentingPrintWriter pw);

    /**
     * Dumps the state of this record for logging and debugging purposes.
     *
     * @hide
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println(this.getClass().getSimpleName() + ":");
        pw.increaseIndent();

        if (mMeteredMatchCriteria != DEFAULT_METERED_MATCH_CRITERIA) {
            pw.println("mMeteredMatchCriteria: " + getMatchCriteriaString(mMeteredMatchCriteria));
        }
        if (mMinEntryUpstreamBandwidthKbps != DEFAULT_MIN_BANDWIDTH_KBPS) {
            pw.println("mMinEntryUpstreamBandwidthKbps: " + mMinEntryUpstreamBandwidthKbps);
        }
        if (mMinExitUpstreamBandwidthKbps != DEFAULT_MIN_BANDWIDTH_KBPS) {
            pw.println("mMinExitUpstreamBandwidthKbps: " + mMinExitUpstreamBandwidthKbps);
        }
        if (mMinEntryDownstreamBandwidthKbps != DEFAULT_MIN_BANDWIDTH_KBPS) {
            pw.println("mMinEntryDownstreamBandwidthKbps: " + mMinEntryDownstreamBandwidthKbps);
        }
        if (mMinExitDownstreamBandwidthKbps != DEFAULT_MIN_BANDWIDTH_KBPS) {
            pw.println("mMinExitDownstreamBandwidthKbps: " + mMinExitDownstreamBandwidthKbps);
        }
        dumpTransportSpecificFields(pw);

        pw.decreaseIndent();
    }

    /**
     * Return the matching criteria for metered networks.
     *
     * @see VcnWifiUnderlyingNetworkTemplate.Builder#setMetered(int)
     * @see VcnCellUnderlyingNetworkTemplate.Builder#setMetered(int)
     */
    public int getMetered() {
        return mMeteredMatchCriteria;
    }

    /**
     * Returns the minimum entry upstream bandwidth allowed by this template.
     *
     * @see VcnWifiUnderlyingNetworkTemplate.Builder#setMinUpstreamBandwidthKbps(int, int)
     * @see VcnCellUnderlyingNetworkTemplate.Builder#setMinUpstreamBandwidthKbps(int, int)
     */
    public int getMinEntryUpstreamBandwidthKbps() {
        return mMinEntryUpstreamBandwidthKbps;
    }

    /**
     * Returns the minimum exit upstream bandwidth allowed by this template.
     *
     * @see VcnWifiUnderlyingNetworkTemplate.Builder#setMinUpstreamBandwidthKbps(int, int)
     * @see VcnCellUnderlyingNetworkTemplate.Builder#setMinUpstreamBandwidthKbps(int, int)
     */
    public int getMinExitUpstreamBandwidthKbps() {
        return mMinExitUpstreamBandwidthKbps;
    }

    /**
     * Returns the minimum entry downstream bandwidth allowed by this template.
     *
     * @see VcnWifiUnderlyingNetworkTemplate.Builder#setMinDownstreamBandwidthKbps(int, int)
     * @see VcnCellUnderlyingNetworkTemplate.Builder#setMinDownstreamBandwidthKbps(int, int)
     */
    public int getMinEntryDownstreamBandwidthKbps() {
        return mMinEntryDownstreamBandwidthKbps;
    }

    /**
     * Returns the minimum exit downstream bandwidth allowed by this template.
     *
     * @see VcnWifiUnderlyingNetworkTemplate.Builder#setMinDownstreamBandwidthKbps(int, int)
     * @see VcnCellUnderlyingNetworkTemplate.Builder#setMinDownstreamBandwidthKbps(int, int)
     */
    public int getMinExitDownstreamBandwidthKbps() {
        return mMinExitDownstreamBandwidthKbps;
    }
}
