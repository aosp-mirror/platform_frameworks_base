/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.net;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;
import java.util.Set;

/**
 * A network identifier along with a score for the quality of that network.
 *
 * @hide
 */
@SystemApi
public class ScoredNetwork implements Parcelable {

  /**
     * Key used with the {@link #attributes} bundle to define the badging curve.
     *
     * <p>The badging curve is a {@link RssiCurve} used to map different RSSI values to {@link
     * NetworkBadging.Badging} enums.
     */
    public static final String ATTRIBUTES_KEY_BADGING_CURVE =
            "android.net.attributes.key.BADGING_CURVE";
    /**
     * Extra used with {@link #attributes} to specify whether the
     * network is believed to have a captive portal.
     * <p>
     * This data may be used, for example, to display a visual indicator
     * in a network selection list.
     * <p>
     * Note that the this extra conveys the possible presence of a
     * captive portal, not its state or the user's ability to open
     * the portal.
     * <p>
     * If no value is associated with this key then it's unknown.
     */
    public static final String ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL =
            "android.net.attributes.key.HAS_CAPTIVE_PORTAL";

    /**
     * Key used with the {@link #attributes} bundle to define the rankingScoreOffset int value.
     *
     * <p>The rankingScoreOffset is used when calculating the ranking score used to rank networks
     * against one another. See {@link #calculateRankingScore} for more information.
     */
    public static final String ATTRIBUTES_KEY_RANKING_SCORE_OFFSET =
            "android.net.attributes.key.RANKING_SCORE_OFFSET";

    /** A {@link NetworkKey} uniquely identifying this network. */
    public final NetworkKey networkKey;

    /**
     * The {@link RssiCurve} representing the scores for this network based on the RSSI.
     *
     * <p>This field is optional and may be set to null to indicate that no score is available for
     * this network at this time. Such networks, along with networks for which the scorer has not
     * responded, are always prioritized below scored networks, regardless of the score.
     */
    public final RssiCurve rssiCurve;

    /**
     * A boolean value that indicates whether or not the network is believed to be metered.
     *
     * <p>A network can be classified as metered if the user would be
     * sensitive to heavy data usage on that connection due to monetary costs,
     * data limitations or battery/performance issues. A typical example would
     * be a wifi connection where the user would be charged for usage.
     */
    public final boolean meteredHint;

    /**
     * An additional collection of optional attributes set by
     * the Network Recommendation Provider.
     *
     * @see #ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL
     * @see #ATTRIBUTES_KEY_RANKING_SCORE_OFFSET
     */
    @Nullable
    public final Bundle attributes;

    /**
     * Construct a new {@link ScoredNetwork}.
     *
     * @param networkKey the {@link NetworkKey} uniquely identifying this network.
     * @param rssiCurve the {@link RssiCurve} representing the scores for this network based on the
     *     RSSI. This field is optional, and may be skipped to represent a network which the scorer
     *     has opted not to score at this time. Passing a null value here is strongly preferred to
     *     not returning any {@link ScoredNetwork} for a given {@link NetworkKey} because it
     *     indicates to the system not to request scores for this network in the future, although
     *     the scorer may choose to issue an out-of-band update at any time.
     */
    public ScoredNetwork(NetworkKey networkKey, RssiCurve rssiCurve) {
        this(networkKey, rssiCurve, false /* meteredHint */);
    }

    /**
     * Construct a new {@link ScoredNetwork}.
     *
     * @param networkKey the {@link NetworkKey} uniquely identifying this network.
     * @param rssiCurve the {@link RssiCurve} representing the scores for this network based on the
     *     RSSI. This field is optional, and may be skipped to represent a network which the scorer
     *     has opted not to score at this time. Passing a null value here is strongly preferred to
     *     not returning any {@link ScoredNetwork} for a given {@link NetworkKey} because it
     *     indicates to the system not to request scores for this network in the future, although
     *     the scorer may choose to issue an out-of-band update at any time.
     * @param meteredHint A boolean value indicating whether or not the network is believed to be
     *     metered.
     */
    public ScoredNetwork(NetworkKey networkKey, RssiCurve rssiCurve, boolean meteredHint) {
        this(networkKey, rssiCurve, meteredHint, null /* attributes */);
    }

    /**
     * Construct a new {@link ScoredNetwork}.
     *
     * @param networkKey the {@link NetworkKey} uniquely identifying this network
     * @param rssiCurve the {@link RssiCurve} representing the scores for this network based on the
     *     RSSI. This field is optional, and may be skipped to represent a network which the scorer
     *     has opted not to score at this time. Passing a null value here is strongly preferred to
     *     not returning any {@link ScoredNetwork} for a given {@link NetworkKey} because it
     *     indicates to the system not to request scores for this network in the future, although
     *     the scorer may choose to issue an out-of-band update at any time.
     * @param meteredHint a boolean value indicating whether or not the network is believed to be
     *                    metered
     * @param attributes optional provider specific attributes
     */
    public ScoredNetwork(NetworkKey networkKey, RssiCurve rssiCurve, boolean meteredHint,
            @Nullable Bundle attributes) {
        this.networkKey = networkKey;
        this.rssiCurve = rssiCurve;
        this.meteredHint = meteredHint;
        this.attributes = attributes;
    }

    private ScoredNetwork(Parcel in) {
        networkKey = NetworkKey.CREATOR.createFromParcel(in);
        if (in.readByte() == 1) {
            rssiCurve = RssiCurve.CREATOR.createFromParcel(in);
        } else {
            rssiCurve = null;
        }
        meteredHint = (in.readByte() == 1);
        attributes = in.readBundle();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        networkKey.writeToParcel(out, flags);
        if (rssiCurve != null) {
            out.writeByte((byte) 1);
            rssiCurve.writeToParcel(out, flags);
        } else {
            out.writeByte((byte) 0);
        }
        out.writeByte((byte) (meteredHint ? 1 : 0));
        out.writeBundle(attributes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ScoredNetwork that = (ScoredNetwork) o;

        return Objects.equals(networkKey, that.networkKey)
                && Objects.equals(rssiCurve, that.rssiCurve)
                && Objects.equals(meteredHint, that.meteredHint)
                && bundleEquals(attributes, that.attributes);
    }

    private boolean bundleEquals(Bundle bundle1, Bundle bundle2) {
        if (bundle1 == bundle2) {
            return true;
        }
        if (bundle1 == null || bundle2 == null) {
            return false;
        }
        if (bundle1.size() != bundle2.size()) {
            return false;
        }
        Set<String> keys = bundle1.keySet();
        for (String key : keys) {
            Object value1 = bundle1.get(key);
            Object value2 = bundle2.get(key);
            if (!Objects.equals(value1, value2)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkKey, rssiCurve, meteredHint, attributes);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(
                "ScoredNetwork{" +
                "networkKey=" + networkKey +
                ", rssiCurve=" + rssiCurve +
                ", meteredHint=" + meteredHint);
        // calling isEmpty will unparcel the bundle so its contents can be converted to a string
        if (attributes != null && !attributes.isEmpty()) {
            out.append(", attributes=" + attributes);
        }
        out.append('}');
        return out.toString();
    }

    /**
     * Returns true if a ranking score can be calculated for this network.
     *
     * @hide
     */
    public boolean hasRankingScore() {
        return (rssiCurve != null)
                || (attributes != null
                        && attributes.containsKey(ATTRIBUTES_KEY_RANKING_SCORE_OFFSET));
    }

    /**
     * Returns a ranking score for a given RSSI which can be used to comparatively
     * rank networks.
     *
     * <p>The score obtained by the rssiCurve is bitshifted left by 8 bits to expand it to an
     * integer and then the offset is added. If the addition operation overflows or underflows,
     * Integer.MAX_VALUE and Integer.MIN_VALUE will be returned respectively.
     *
     * <p>{@link #hasRankingScore} should be called first to ensure this network is capable
     * of returning a ranking score.
     *
     * @throws UnsupportedOperationException if there is no RssiCurve and no rankingScoreOffset
     * for this network (hasRankingScore returns false).
     *
     * @hide
     */
    public int calculateRankingScore(int rssi) throws UnsupportedOperationException {
        if (!hasRankingScore()) {
            throw new UnsupportedOperationException(
                    "Either rssiCurve or rankingScoreOffset is required to calculate the "
                            + "ranking score");
        }

        int offset = 0;
        if (attributes != null) {
             offset += attributes.getInt(ATTRIBUTES_KEY_RANKING_SCORE_OFFSET, 0 /* default */);
        }

        int score = (rssiCurve == null) ? 0 : rssiCurve.lookupScore(rssi) << Byte.SIZE;

        try {
            return Math.addExact(score, offset);
        } catch (ArithmeticException e) {
            return (score < 0) ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
    }

    /**
     * Return the {@link NetworkBadging.Badging} enum for this network for the given RSSI, derived from the
     * badging curve.
     *
     * <p>If no badging curve is present, {@link #BADGE_NONE} will be returned.
     *
     * @param rssi The rssi level for which the badge should be calculated
     */
    @NetworkBadging.Badging
    public int calculateBadge(int rssi) {
        if (attributes != null && attributes.containsKey(ATTRIBUTES_KEY_BADGING_CURVE)) {
            RssiCurve badgingCurve =
                    attributes.getParcelable(ATTRIBUTES_KEY_BADGING_CURVE);
            return badgingCurve.lookupScore(rssi);
        }

        return NetworkBadging.BADGING_NONE;
    }

    public static final Parcelable.Creator<ScoredNetwork> CREATOR =
            new Parcelable.Creator<ScoredNetwork>() {
                @Override
                public ScoredNetwork createFromParcel(Parcel in) {
                    return new ScoredNetwork(in);
                }

                @Override
                public ScoredNetwork[] newArray(int size) {
                    return new ScoredNetwork[size];
                }
            };
}
