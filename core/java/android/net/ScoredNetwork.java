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

import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A network identifier along with a score for the quality of that network.
 *
 * @hide
 */
@SystemApi
public class ScoredNetwork implements Parcelable {
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
    public static final String EXTRA_HAS_CAPTIVE_PORTAL = "android.net.extra.HAS_CAPTIVE_PORTAL";

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
     * @see #EXTRA_HAS_CAPTIVE_PORTAL
     */
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
        this(networkKey, rssiCurve, false /* meteredHint */, null /* attributes */);
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
            Bundle attributes) {
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
        meteredHint = in.readByte() != 0;
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
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkKey, rssiCurve, meteredHint, attributes);
    }

    @Override
    public String toString() {
        return "ScoredNetwork{" +
                "networkKey=" + networkKey +
                ", rssiCurve=" + rssiCurve +
                ", meteredHint=" + meteredHint +
                ", attributes=" + attributes +
                '}';
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
