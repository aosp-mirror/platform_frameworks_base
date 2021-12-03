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

import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_ANY;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
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
 * VcnGatewayConnectionConfig.Builder#setVcnUnderlyingNetworkTemplates}
 *
 * @hide
 */
public abstract class VcnUnderlyingNetworkTemplate {
    /** @hide */
    static final int NETWORK_PRIORITY_TYPE_WIFI = 1;
    /** @hide */
    static final int NETWORK_PRIORITY_TYPE_CELL = 2;

    /** Denotes that any network quality is acceptable. @hide */
    public static final int NETWORK_QUALITY_ANY = 0;
    /** Denotes that network quality needs to be OK. @hide */
    public static final int NETWORK_QUALITY_OK = 100000;

    private static final SparseArray<String> NETWORK_QUALITY_TO_STRING_MAP = new SparseArray<>();

    static {
        NETWORK_QUALITY_TO_STRING_MAP.put(NETWORK_QUALITY_ANY, "NETWORK_QUALITY_ANY");
        NETWORK_QUALITY_TO_STRING_MAP.put(NETWORK_QUALITY_OK, "NETWORK_QUALITY_OK");
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NETWORK_QUALITY_OK, NETWORK_QUALITY_ANY})
    public @interface NetworkQuality {}

    /**
     * Used to configure the matching criteria of a network capability (See {@link
     * NetworkCapabilities}). Denotes that networks with or without the capability are both
     * acceptable to match the template.
     */
    public static final int MATCH_ANY = 0;

    /**
     * Used to configure the matching criteria of a network capability (See {@link
     * NetworkCapabilities}). Denotes that only network with the capability can match the template.
     */
    public static final int MATCH_REQUIRED = 1;

    /**
     * Used to configure the matching criteria of a network capability (See {@link
     * NetworkCapabilities}). Denotes that only network without the capability can match the
     * template.
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
    static final String NETWORK_QUALITY_KEY = "mNetworkQuality";

    private final int mNetworkQuality;

    /** @hide */
    static final String METERED_MATCH_KEY = "mMeteredMatchCriteria";

    private final int mMeteredMatchCriteria;

    /** @hide */
    VcnUnderlyingNetworkTemplate(
            int networkPriorityType, int networkQuality, int meteredMatchCriteria) {
        mNetworkPriorityType = networkPriorityType;
        mNetworkQuality = networkQuality;
        mMeteredMatchCriteria = meteredMatchCriteria;
    }

    /** @hide */
    static void validateNetworkQuality(int networkQuality) {
        Preconditions.checkArgument(
                networkQuality == NETWORK_QUALITY_ANY || networkQuality == NETWORK_QUALITY_OK,
                "Invalid networkQuality:" + networkQuality);
    }

    /** @hide */
    static void validateMatchCriteria(int meteredMatchCriteria, String matchingCapability) {
        Preconditions.checkArgument(
                MATCH_CRITERIA_TO_STRING_MAP.contains(meteredMatchCriteria),
                "Invalid matching criteria: "
                        + meteredMatchCriteria
                        + " for "
                        + matchingCapability);
    }

    /** @hide */
    protected void validate() {
        validateNetworkQuality(mNetworkQuality);
        validateMatchCriteria(mMeteredMatchCriteria, "mMeteredMatchCriteria");
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
        result.putInt(NETWORK_QUALITY_KEY, mNetworkQuality);
        result.putInt(METERED_MATCH_KEY, mMeteredMatchCriteria);

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkPriorityType, mNetworkQuality, mMeteredMatchCriteria);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VcnUnderlyingNetworkTemplate)) {
            return false;
        }

        final VcnUnderlyingNetworkTemplate rhs = (VcnUnderlyingNetworkTemplate) other;
        return mNetworkPriorityType == rhs.mNetworkPriorityType
                && mNetworkQuality == rhs.mNetworkQuality
                && mMeteredMatchCriteria == rhs.mMeteredMatchCriteria;
    }

    /** @hide */
    static String getNameString(SparseArray<String> toStringMap, int key) {
        return toStringMap.get(key, "Invalid value " + key);
    }

    /** @hide */
    static String getMatchCriteriaString(int meteredMatchCriteria) {
        return getNameString(MATCH_CRITERIA_TO_STRING_MAP, meteredMatchCriteria);
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

        pw.println(
                "mNetworkQuality: "
                        + getNameString(NETWORK_QUALITY_TO_STRING_MAP, mNetworkQuality));
        pw.println("mMeteredMatchCriteria: " + getMatchCriteriaString(mMeteredMatchCriteria));
        dumpTransportSpecificFields(pw);

        pw.decreaseIndent();
    }

    /**
     * Retrieve the required network quality to match this template.
     *
     * @see Builder#setNetworkQuality(int)
     * @hide
     */
    @NetworkQuality
    public int getNetworkQuality() {
        return mNetworkQuality;
    }

    /**
     * Return the matching criteria for metered networks.
     *
     * @see VcnWifiUnderlyingNetworkTemplate.Builder#setMetered(int)
     * @see VcnCellUnderlyingNetworkTemplate.Builder#setMetered(int)
     */
    @MatchCriteria
    public int getMetered() {
        return mMeteredMatchCriteria;
    }
}
