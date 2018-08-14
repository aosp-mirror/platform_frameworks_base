/*
 * Copyright 2018 The Android Open Source Project
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

package android.hardware.display;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * AmbientBrightnessDayStats stores and manipulates brightness stats over a single day.
 * {@see DisplayManager.getAmbientBrightnessStats()}
 *
 * @hide
 */
@SystemApi
@TestApi
public final class AmbientBrightnessDayStats implements Parcelable {

    /** The localdate for which brightness stats are being tracked */
    private final LocalDate mLocalDate;

    /** Ambient brightness values for creating bucket boundaries from */
    private final float[] mBucketBoundaries;

    /** Stats of how much time (in seconds) was spent in each of the buckets */
    private final float[] mStats;

    /**
     * Initialize day stats from the given state. The time spent in each of the bucket is
     * initialized to 0.
     *
     * @param localDate        The date for which stats are being tracked
     * @param bucketBoundaries Bucket boundaries used from creating the buckets from
     * @hide
     */
    public AmbientBrightnessDayStats(@NonNull LocalDate localDate,
            @NonNull float[] bucketBoundaries) {
        this(localDate, bucketBoundaries, null);
    }

    /**
     * Initialize day stats from the given state
     *
     * @param localDate        The date for which stats are being tracked
     * @param bucketBoundaries Bucket boundaries used from creating the buckets from
     * @param stats            Time spent in each of the buckets (in seconds)
     * @hide
     */
    public AmbientBrightnessDayStats(@NonNull LocalDate localDate,
            @NonNull float[] bucketBoundaries, float[] stats) {
        Preconditions.checkNotNull(localDate);
        Preconditions.checkNotNull(bucketBoundaries);
        Preconditions.checkArrayElementsInRange(bucketBoundaries, 0, Float.MAX_VALUE,
                "bucketBoundaries");
        if (bucketBoundaries.length < 1) {
            throw new IllegalArgumentException("Bucket boundaries must contain at least 1 value");
        }
        checkSorted(bucketBoundaries);
        if (stats == null) {
            stats = new float[bucketBoundaries.length];
        } else {
            Preconditions.checkArrayElementsInRange(stats, 0, Float.MAX_VALUE, "stats");
            if (bucketBoundaries.length != stats.length) {
                throw new IllegalArgumentException(
                        "Bucket boundaries and stats must be of same size.");
            }
        }
        mLocalDate = localDate;
        mBucketBoundaries = bucketBoundaries;
        mStats = stats;
    }

    /**
     * @return The {@link LocalDate} for which brightness stats are being tracked.
     */
    public LocalDate getLocalDate() {
        return mLocalDate;
    }

    /**
     * @return Aggregated stats of time spent (in seconds) in various buckets.
     */
    public float[] getStats() {
        return mStats;
    }

    /**
     * Returns the bucket boundaries (in lux) used for creating buckets. For eg., if the bucket
     * boundaries array is {b1, b2, b3}, the buckets will be [b1, b2), [b2, b3), [b3, inf).
     *
     * @return The list of bucket boundaries.
     */
    public float[] getBucketBoundaries() {
        return mBucketBoundaries;
    }

    private AmbientBrightnessDayStats(Parcel source) {
        mLocalDate = LocalDate.parse(source.readString());
        mBucketBoundaries = source.createFloatArray();
        mStats = source.createFloatArray();
    }

    public static final Creator<AmbientBrightnessDayStats> CREATOR =
            new Creator<AmbientBrightnessDayStats>() {

                @Override
                public AmbientBrightnessDayStats createFromParcel(Parcel source) {
                    return new AmbientBrightnessDayStats(source);
                }

                @Override
                public AmbientBrightnessDayStats[] newArray(int size) {
                    return new AmbientBrightnessDayStats[size];
                }
            };

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AmbientBrightnessDayStats other = (AmbientBrightnessDayStats) obj;
        return mLocalDate.equals(other.mLocalDate) && Arrays.equals(mBucketBoundaries,
                other.mBucketBoundaries) && Arrays.equals(mStats, other.mStats);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = result * prime + mLocalDate.hashCode();
        result = result * prime + Arrays.hashCode(mBucketBoundaries);
        result = result * prime + Arrays.hashCode(mStats);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder bucketBoundariesString = new StringBuilder();
        StringBuilder statsString = new StringBuilder();
        for (int i = 0; i < mBucketBoundaries.length; i++) {
            if (i != 0) {
                bucketBoundariesString.append(", ");
                statsString.append(", ");
            }
            bucketBoundariesString.append(mBucketBoundaries[i]);
            statsString.append(mStats[i]);
        }
        return new StringBuilder()
                .append(mLocalDate).append(" ")
                .append("{").append(bucketBoundariesString).append("} ")
                .append("{").append(statsString).append("}").toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mLocalDate.toString());
        dest.writeFloatArray(mBucketBoundaries);
        dest.writeFloatArray(mStats);
    }

    /**
     * Updates the stats by incrementing the time spent for the appropriate bucket based on ambient
     * brightness reading.
     *
     * @param ambientBrightness Ambient brightness reading (in lux)
     * @param durationSec       Time spent with the given reading (in seconds)
     * @hide
     */
    public void log(float ambientBrightness, float durationSec) {
        int bucketIndex = getBucketIndex(ambientBrightness);
        if (bucketIndex >= 0) {
            mStats[bucketIndex] += durationSec;
        }
    }

    private int getBucketIndex(float ambientBrightness) {
        if (ambientBrightness < mBucketBoundaries[0]) {
            return -1;
        }
        int low = 0;
        int high = mBucketBoundaries.length - 1;
        while (low < high) {
            int mid = (low + high) / 2;
            if (mBucketBoundaries[mid] <= ambientBrightness
                    && ambientBrightness < mBucketBoundaries[mid + 1]) {
                return mid;
            } else if (mBucketBoundaries[mid] < ambientBrightness) {
                low = mid + 1;
            } else if (mBucketBoundaries[mid] > ambientBrightness) {
                high = mid - 1;
            }
        }
        return low;
    }

    private static void checkSorted(float[] values) {
        if (values.length <= 1) {
            return;
        }
        float prevValue = values[0];
        for (int i = 1; i < values.length; i++) {
            Preconditions.checkState(prevValue < values[i]);
            prevValue = values[i];
        }
        return;
    }
}
