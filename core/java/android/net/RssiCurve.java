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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * A curve defining the network score over a range of RSSI values.
 *
 * <p>For each RSSI bucket, the score may be any byte. Scores have no absolute meaning and are only
 * considered relative to other scores assigned by the same scorer. Networks with no score are
 * treated equivalently to a network with score {@link Byte#MIN_VALUE}, and will not be used.
 *
 * <p>For example, consider a curve starting at -110 dBm with a bucket width of 10 and the
 * following buckets: {@code [-20, -10, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120]}.
 * This represents a linear curve between -110 dBm and 30 dBm. It scores progressively higher at
 * stronger signal strengths.
 *
 * <p>A network can be assigned a fixed score independent of RSSI by setting
 * {@link #rssiBuckets} to a one-byte array whose element is the fixed score. {@link #start}
 * should be set to the lowest RSSI value at which this fixed score should apply, and
 * {@link #bucketWidth} should be set such that {@code start + bucketWidth} is equal to the
 * highest RSSI value at which this fixed score should apply.
 *
 * <p>Note that RSSI values below -110 dBm or above 30 dBm are unlikely to cause any difference
 * in connectivity behavior from those endpoints. That is, the connectivity framework will treat
 * a network with a -120 dBm signal exactly as it would treat one with a -110 dBm signal.
 * Therefore, graphs which specify scores outside this range may be truncated to this range by
 * the system.
 *
 * @see ScoredNetwork
 * @deprecated as part of the {@link NetworkScoreManager} deprecation.
 * @hide
 */
@Deprecated
@SystemApi
public class RssiCurve implements Parcelable {
    private static final int DEFAULT_ACTIVE_NETWORK_RSSI_BOOST = 25;

    /** The starting dBm of the curve. */
    public final int start;

    /** The width of each RSSI bucket, in dBm. */
    public final int bucketWidth;

    /** The score for each RSSI bucket. */
    public final byte[] rssiBuckets;

    /**
     * The RSSI boost to give this network when active, in dBm.
     *
     * <p>When the system is connected to this network, it will pretend that the network has this
     * much higher of an RSSI. This is to avoid switching networks when another network has only a
     * slightly higher score.
     */
    public final int activeNetworkRssiBoost;

    /**
     * Construct a new {@link RssiCurve}.
     *
     * @param start the starting dBm of the curve.
     * @param bucketWidth the width of each RSSI bucket, in dBm.
     * @param rssiBuckets the score for each RSSI bucket.
     */
    public RssiCurve(int start, int bucketWidth, byte[] rssiBuckets) {
        this(start, bucketWidth, rssiBuckets, DEFAULT_ACTIVE_NETWORK_RSSI_BOOST);
    }

    /**
     * Construct a new {@link RssiCurve}.
     *
     * @param start the starting dBm of the curve.
     * @param bucketWidth the width of each RSSI bucket, in dBm.
     * @param rssiBuckets the score for each RSSI bucket.
     * @param activeNetworkRssiBoost the RSSI boost to apply when this network is active, in dBm.
     */
    public RssiCurve(int start, int bucketWidth, byte[] rssiBuckets, int activeNetworkRssiBoost) {
        this.start = start;
        this.bucketWidth = bucketWidth;
        if (rssiBuckets == null || rssiBuckets.length == 0) {
            throw new IllegalArgumentException("rssiBuckets must be at least one element large.");
        }
        this.rssiBuckets = rssiBuckets;
        this.activeNetworkRssiBoost = activeNetworkRssiBoost;
    }

    private RssiCurve(Parcel in) {
        start = in.readInt();
        bucketWidth = in.readInt();
        int bucketCount = in.readInt();
        rssiBuckets = new byte[bucketCount];
        in.readByteArray(rssiBuckets);
        activeNetworkRssiBoost = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(start);
        out.writeInt(bucketWidth);
        out.writeInt(rssiBuckets.length);
        out.writeByteArray(rssiBuckets);
        out.writeInt(activeNetworkRssiBoost);
    }

    /**
     * Lookup the score for a given RSSI value.
     *
     * @param rssi The RSSI to lookup. If the RSSI falls below the start of the curve, the score at
     *         the start of the curve will be returned. If it falls after the end of the curve, the
     *         score at the end of the curve will be returned.
     * @return the score for the given RSSI.
     */
    public byte lookupScore(int rssi) {
        return lookupScore(rssi, false /* isActiveNetwork */);
    }

    /**
     * Lookup the score for a given RSSI value.
     *
     * @param rssi The RSSI to lookup. If the RSSI falls below the start of the curve, the score at
     *         the start of the curve will be returned. If it falls after the end of the curve, the
     *         score at the end of the curve will be returned.
     * @param isActiveNetwork Whether this network is currently active.
     * @return the score for the given RSSI.
     */
    public byte lookupScore(int rssi, boolean isActiveNetwork) {
        if (isActiveNetwork) {
            rssi += activeNetworkRssiBoost;
        }

        int index = (rssi - start) / bucketWidth;

        // Snap the index to the closest bucket if it falls outside the curve.
        if (index < 0) {
            index = 0;
        } else if (index > rssiBuckets.length - 1) {
            index = rssiBuckets.length - 1;
        }

        return rssiBuckets[index];
    }

    /**
     * Determine if two RSSI curves are defined in the same way.
     *
     * <p>Note that two curves can be equivalent but defined differently, e.g. if one bucket in one
     * curve is split into two buckets in another. For the purpose of this method, these curves are
     * not considered equal to each other.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RssiCurve rssiCurve = (RssiCurve) o;

        return start == rssiCurve.start &&
                bucketWidth == rssiCurve.bucketWidth &&
                Arrays.equals(rssiBuckets, rssiCurve.rssiBuckets) &&
                activeNetworkRssiBoost == rssiCurve.activeNetworkRssiBoost;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, bucketWidth, activeNetworkRssiBoost) ^ Arrays.hashCode(rssiBuckets);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RssiCurve[start=")
                .append(start)
                .append(",bucketWidth=")
                .append(bucketWidth)
                .append(",activeNetworkRssiBoost=")
                .append(activeNetworkRssiBoost);

        sb.append(",buckets=");
        for (int i = 0; i < rssiBuckets.length; i++) {
            sb.append(rssiBuckets[i]);
            if (i < rssiBuckets.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");

        return sb.toString();
    }

    public static final @android.annotation.NonNull Creator<RssiCurve> CREATOR =
            new Creator<RssiCurve>() {
                @Override
                public RssiCurve createFromParcel(Parcel in) {
                    return new RssiCurve(in);
                }

                @Override
                public RssiCurve[] newArray(int size) {
                    return new RssiCurve[size];
                }
            };
}
