/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.telephony;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parcelable class to store Telephony histogram.
 * @hide
 */
@SystemApi
public final class TelephonyHistogram implements Parcelable {
    // Type of Telephony histogram Eg: RIL histogram will have all timing data associated with
    // RIL calls. Similarly we can have any other Telephony histogram.
    private final int category;

    // Unique Id identifying a sample within particular category of histogram
    private final int id;

    // Min time taken in ms
    private int minTimeMs;

    // Max time taken in ms
    private int maxTimeMs;

    // Average time taken in ms
    private int averageTimeMs;

    // Total count of samples
    private int sampleCount;

    // Array storing time taken for first #RANGE_CALCULATION_COUNT samples of histogram.
    private int[] initialTimings;

    // Total number of time ranges expected (must be greater than 1)
    private final int bucketCount;

    // Array storing endpoints of range buckets. Calculated based on values of minTime & maxTime
    // after totalTimeCount is #RANGE_CALCULATION_COUNT.
    private final int[] bucketEndPoints;

    // Array storing counts for each time range starting from smallest value range
    private final int[] bucketCounters;

    /**
     * Constant for Telephony category
     */
    public static final int TELEPHONY_CATEGORY_RIL = 1;

    // Count of Histogram samples after which time buckets are created.
    private static final int RANGE_CALCULATION_COUNT = 10;


    // Constant used to indicate #initialTimings is null while parceling
    private static final int ABSENT = 0;

    // Constant used to indicate #initialTimings is not null while parceling
    private static final int PRESENT = 1;

    // Throws exception if #totalBuckets is not greater than one.
    public TelephonyHistogram (int category, int id, int bucketCount) {
        if (bucketCount <= 1) {
            throw new IllegalArgumentException("Invalid number of buckets");
        }
        this.category = category;
        this.id = id;
        this.minTimeMs = Integer.MAX_VALUE;
        this.maxTimeMs = 0;
        this.averageTimeMs = 0;
        this.sampleCount = 0;
        initialTimings = new int[RANGE_CALCULATION_COUNT];
        this.bucketCount = bucketCount;
        bucketEndPoints = new int[bucketCount - 1];
        bucketCounters = new int[bucketCount];
    }

    public TelephonyHistogram(TelephonyHistogram th) {
        category = th.getCategory();
        id = th.getId();
        minTimeMs = th.getMinTime();
        maxTimeMs = th.getMaxTime();
        averageTimeMs = th.getAverageTime();
        sampleCount = th.getSampleCount();
        initialTimings = th.getInitialTimings();
        bucketCount = th.getBucketCount();
        bucketEndPoints = th.getBucketEndPoints();
        bucketCounters = th.getBucketCounters();
    }

    public int getCategory() {
        return category;
    }

    public int getId() {
        return id;
    }

    public int getMinTime() {
        return minTimeMs;
    }

    public int getMaxTime() {
        return maxTimeMs;
    }

    public int getAverageTime() {
        return averageTimeMs;
    }

    public int getSampleCount () {
        return sampleCount;
    }

    private int[] getInitialTimings() {
        return initialTimings;
    }

    public int getBucketCount() {
        return bucketCount;
    }

    public int[] getBucketEndPoints() {
        return getDeepCopyOfArray(bucketEndPoints);
    }

    public int[] getBucketCounters() {
        return getDeepCopyOfArray(bucketCounters);
    }

    private int[] getDeepCopyOfArray(int[] array) {
        int[] clone = new int[array.length];
        System.arraycopy(array, 0, clone, 0, array.length);
        return clone;
    }

    private void addToBucketCounter(int time) {
        int i;
        for (i = 0; i < bucketEndPoints.length; i++) {
            if (time <= bucketEndPoints[i]) {
                bucketCounters[i]++;
                return;
            }
        }
        bucketCounters[i]++;
    }

    // Add new value of time taken
    // This function updates minTime, maxTime, averageTime & totalTimeCount every time it is
    // called. initialTimings[] is updated if totalTimeCount <= #RANGE_CALCULATION_COUNT. When
    // totalTimeCount = RANGE_CALCULATION_COUNT, based on the min, max time & the number of buckets
    // expected, bucketEndPoints[] would be calculated. Then bucketCounters[] would be filled up
    // using values stored in initialTimings[]. Thereafter bucketCounters[] will always be updated.
    public void addTimeTaken(int time) {
        // Initialize all fields if its first entry or if integer overflow is going to occur while
        // trying to calculate averageTime
        if (sampleCount == 0 || (sampleCount == Integer.MAX_VALUE)) {
            if (sampleCount == 0) {
                minTimeMs = time;
                maxTimeMs = time;
                averageTimeMs = time;
            } else {
                initialTimings = new int[RANGE_CALCULATION_COUNT];
            }
            sampleCount = 1;
            Arrays.fill(initialTimings, 0);
            initialTimings[0] = time;
            Arrays.fill(bucketEndPoints, 0);
            Arrays.fill(bucketCounters, 0);
        } else {
            if (time < minTimeMs) {
                minTimeMs = time;
            }
            if (time > maxTimeMs) {
                maxTimeMs = time;
            }
            long totalTime = ((long)averageTimeMs) * sampleCount + time;
            averageTimeMs = (int)(totalTime/++sampleCount);

            if (sampleCount < RANGE_CALCULATION_COUNT) {
                initialTimings[sampleCount - 1] = time;
            } else if (sampleCount == RANGE_CALCULATION_COUNT) {
                initialTimings[sampleCount - 1] = time;

                // Calculate bucket endpoints based on bucketCount expected
                for (int i = 1; i < bucketCount; i++) {
                    int endPt = minTimeMs + (i * (maxTimeMs - minTimeMs)) / bucketCount;
                    bucketEndPoints[i - 1] = endPt;
                }

                // Use values stored in initialTimings[] to update bucketCounters
                for (int j = 0; j < RANGE_CALCULATION_COUNT; j++) {
                    addToBucketCounter(initialTimings[j]);
                }
                initialTimings = null;
            } else {
                addToBucketCounter(time);
            }

        }
    }

    public String toString() {
        String basic = " Histogram id = " + id + " Time(ms): min = " + minTimeMs + " max = "
                + maxTimeMs + " avg = " + averageTimeMs + " Count = " + sampleCount;
        if (sampleCount < RANGE_CALCULATION_COUNT) {
            return basic;
        } else {
            StringBuffer intervals = new StringBuffer(" Interval Endpoints:");
            for (int i = 0; i < bucketEndPoints.length; i++) {
                intervals.append(" " + bucketEndPoints[i]);
            }
            intervals.append(" Interval counters:");
            for (int i = 0; i < bucketCounters.length; i++) {
                intervals.append(" " + bucketCounters[i]);
            }
            return basic + intervals;
        }
    }

    public static final Parcelable.Creator<TelephonyHistogram> CREATOR =
            new Parcelable.Creator<TelephonyHistogram> () {

                @Override
                public TelephonyHistogram createFromParcel(Parcel in) {
                    return new TelephonyHistogram(in);
                }

                @Override
                public TelephonyHistogram[] newArray(int size) {
                    return new TelephonyHistogram[size];
                }
            };

    public TelephonyHistogram(Parcel in) {
        category = in.readInt();
        id = in.readInt();
        minTimeMs = in.readInt();
        maxTimeMs = in.readInt();
        averageTimeMs = in.readInt();
        sampleCount = in.readInt();
        if (in.readInt() == PRESENT) {
            initialTimings = new int[RANGE_CALCULATION_COUNT];
            in.readIntArray(initialTimings);
        }
        bucketCount = in.readInt();
        bucketEndPoints = new int[bucketCount - 1];
        in.readIntArray(bucketEndPoints);
        bucketCounters = new int[bucketCount];
        in.readIntArray(bucketCounters);
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(category);
        out.writeInt(id);
        out.writeInt(minTimeMs);
        out.writeInt(maxTimeMs);
        out.writeInt(averageTimeMs);
        out.writeLong(sampleCount);
        if (initialTimings == null) {
            out.writeInt(ABSENT);
        } else {
            out.writeInt(PRESENT);
            out.writeIntArray(initialTimings);
        }
        out.writeInt(bucketCount);
        out.writeIntArray(bucketEndPoints);
        out.writeIntArray(bucketCounters);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
