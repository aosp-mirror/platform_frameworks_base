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
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * A curve defining the network score over a range of RSSI values.
 *
 * <p>For each RSSI bucket, the score may be any byte. Scores have no absolute meaning and are only
 * considered relative to other scores assigned by the same scorer. Networks with no score are all
 * considered equivalent and ranked below any network with a score.
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
 * @hide
 */
@SystemApi
public class RssiCurve implements Parcelable {

    /** The starting dBm of the curve. */
    public final int start;

    /** The width of each RSSI bucket, in dBm. */
    public final int bucketWidth;

    /** The score for each RSSI bucket. */
    public final byte[] rssiBuckets;

    /**
     * Construct a new {@link RssiCurve}.
     *
     * @param start the starting dBm of the curve.
     * @param bucketWidth the width of each RSSI bucket, in dBm.
     * @param rssiBuckets the score for each RSSI bucket.
     */
    public RssiCurve(int start, int bucketWidth, byte[] rssiBuckets) {
        this.start = start;
        this.bucketWidth = bucketWidth;
        if (rssiBuckets == null || rssiBuckets.length == 0) {
            throw new IllegalArgumentException("rssiBuckets must be at least one element large.");
        }
        this.rssiBuckets = rssiBuckets;
    }

    private RssiCurve(Parcel in) {
        start = in.readInt();
        bucketWidth = in.readInt();
        int bucketCount = in.readInt();
        rssiBuckets = new byte[bucketCount];
        in.readByteArray(rssiBuckets);
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
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RssiCurve rssiCurve = (RssiCurve) o;

        return start == rssiCurve.start &&
                bucketWidth == rssiCurve.bucketWidth &&
                Arrays.equals(rssiBuckets, rssiCurve.rssiBuckets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, bucketWidth, rssiBuckets);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RssiCurve[start=")
                .append(start)
                .append(",bucketWidth=")
                .append(bucketWidth);

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

    public static final Creator<RssiCurve> CREATOR =
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
