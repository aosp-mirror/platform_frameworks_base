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
    private final int mCategory;

    // Unique Id identifying a sample within particular category of histogram
    private final int mId;

    // Min time taken in ms
    private int mMinTimeMs;

    // Max time taken in ms
    private int mMaxTimeMs;

    // Average time taken in ms
    private int mAverageTimeMs;

    // Total count of samples
    private int mSampleCount;

    // Array storing time taken for first #RANGE_CALCULATION_COUNT samples of histogram.
    private int[] mInitialTimings;

    // Total number of time ranges expected (must be greater than 1)
    private final int mBucketCount;

    // Array storing endpoints of range buckets. Calculated based on values of minTime & maxTime
    // after totalTimeCount is #RANGE_CALCULATION_COUNT.
    private final int[] mBucketEndPoints;

    // Array storing counts for each time range starting from smallest value range
    private final int[] mBucketCounters;

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
        mCategory = category;
        mId = id;
        mMinTimeMs = Integer.MAX_VALUE;
        mMaxTimeMs = 0;
        mAverageTimeMs = 0;
        mSampleCount = 0;
        mInitialTimings = new int[RANGE_CALCULATION_COUNT];
        mBucketCount = bucketCount;
        mBucketEndPoints = new int[bucketCount - 1];
        mBucketCounters = new int[bucketCount];
    }

    public TelephonyHistogram(TelephonyHistogram th) {
        mCategory = th.getCategory();
        mId = th.getId();
        mMinTimeMs = th.getMinTime();
        mMaxTimeMs = th.getMaxTime();
        mAverageTimeMs = th.getAverageTime();
        mSampleCount = th.getSampleCount();
        mInitialTimings = th.getInitialTimings();
        mBucketCount = th.getBucketCount();
        mBucketEndPoints = th.getBucketEndPoints();
        mBucketCounters = th.getBucketCounters();
    }

    public int getCategory() {
        return mCategory;
    }

    public int getId() {
        return mId;
    }

    public int getMinTime() {
        return mMinTimeMs;
    }

    public int getMaxTime() {
        return mMaxTimeMs;
    }

    public int getAverageTime() {
        return mAverageTimeMs;
    }

    public int getSampleCount () {
        return mSampleCount;
    }

    private int[] getInitialTimings() {
        return mInitialTimings;
    }

    public int getBucketCount() {
        return mBucketCount;
    }

    public int[] getBucketEndPoints() {
        if (mSampleCount > 1 && mSampleCount < 10) {
            int[] tempEndPoints = new int[mBucketCount - 1];
            calculateBucketEndPoints(tempEndPoints);
            return tempEndPoints;
        } else {
            return getDeepCopyOfArray(mBucketEndPoints);
        }
    }

    public int[] getBucketCounters() {
        if (mSampleCount > 1 && mSampleCount < 10) {
            int[] tempEndPoints = new int[mBucketCount - 1];
            int[] tempBucketCounters = new int[mBucketCount];
            calculateBucketEndPoints(tempEndPoints);
            for (int j = 0; j < mSampleCount; j++) {
                addToBucketCounter(tempEndPoints, tempBucketCounters, mInitialTimings[j]);
            }
            return tempBucketCounters;
        } else {
            return getDeepCopyOfArray(mBucketCounters);
        }
    }

    private int[] getDeepCopyOfArray(int[] array) {
        int[] clone = new int[array.length];
        System.arraycopy(array, 0, clone, 0, array.length);
        return clone;
    }

    private void addToBucketCounter(int[] bucketEndPoints, int[] bucketCounters, int time) {
        int i;
        for (i = 0; i < bucketEndPoints.length; i++) {
            if (time <= bucketEndPoints[i]) {
                bucketCounters[i]++;
                return;
            }
        }
        bucketCounters[i]++;
    }

    private void calculateBucketEndPoints(int[] bucketEndPoints) {
        for (int i = 1; i < mBucketCount; i++) {
            int endPt = mMinTimeMs + (i * (mMaxTimeMs - mMinTimeMs)) / mBucketCount;
            bucketEndPoints[i - 1] = endPt;
        }
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
        if (mSampleCount == 0 || (mSampleCount == Integer.MAX_VALUE)) {
            if (mSampleCount == 0) {
                mMinTimeMs = time;
                mMaxTimeMs = time;
                mAverageTimeMs = time;
            } else {
                mInitialTimings = new int[RANGE_CALCULATION_COUNT];
            }
            mSampleCount = 1;
            Arrays.fill(mInitialTimings, 0);
            mInitialTimings[0] = time;
            Arrays.fill(mBucketEndPoints, 0);
            Arrays.fill(mBucketCounters, 0);
        } else {
            if (time < mMinTimeMs) {
                mMinTimeMs = time;
            }
            if (time > mMaxTimeMs) {
                mMaxTimeMs = time;
            }
            long totalTime = ((long)mAverageTimeMs) * mSampleCount + time;
            mAverageTimeMs = (int)(totalTime/++mSampleCount);

            if (mSampleCount < RANGE_CALCULATION_COUNT) {
                mInitialTimings[mSampleCount - 1] = time;
            } else if (mSampleCount == RANGE_CALCULATION_COUNT) {
                mInitialTimings[mSampleCount - 1] = time;

                // Calculate bucket endpoints based on bucketCount expected
                calculateBucketEndPoints(mBucketEndPoints);

                // Use values stored in initialTimings[] to update bucketCounters
                for (int j = 0; j < RANGE_CALCULATION_COUNT; j++) {
                    addToBucketCounter(mBucketEndPoints, mBucketCounters, mInitialTimings[j]);
                }
                mInitialTimings = null;
            } else {
                addToBucketCounter(mBucketEndPoints, mBucketCounters, time);
            }

        }
    }

    public String toString() {
        String basic = " Histogram id = " + mId + " Time(ms): min = " + mMinTimeMs + " max = "
                + mMaxTimeMs + " avg = " + mAverageTimeMs + " Count = " + mSampleCount;
        if (mSampleCount < RANGE_CALCULATION_COUNT) {
            return basic;
        } else {
            StringBuffer intervals = new StringBuffer(" Interval Endpoints:");
            for (int i = 0; i < mBucketEndPoints.length; i++) {
                intervals.append(" " + mBucketEndPoints[i]);
            }
            intervals.append(" Interval counters:");
            for (int i = 0; i < mBucketCounters.length; i++) {
                intervals.append(" " + mBucketCounters[i]);
            }
            return basic + intervals;
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TelephonyHistogram> CREATOR =
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
        mCategory = in.readInt();
        mId = in.readInt();
        mMinTimeMs = in.readInt();
        mMaxTimeMs = in.readInt();
        mAverageTimeMs = in.readInt();
        mSampleCount = in.readInt();
        if (in.readInt() == PRESENT) {
            mInitialTimings = new int[RANGE_CALCULATION_COUNT];
            in.readIntArray(mInitialTimings);
        }
        mBucketCount = in.readInt();
        mBucketEndPoints = new int[mBucketCount - 1];
        in.readIntArray(mBucketEndPoints);
        mBucketCounters = new int[mBucketCount];
        in.readIntArray(mBucketCounters);
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCategory);
        out.writeInt(mId);
        out.writeInt(mMinTimeMs);
        out.writeInt(mMaxTimeMs);
        out.writeInt(mAverageTimeMs);
        out.writeInt(mSampleCount);
        if (mInitialTimings == null) {
            out.writeInt(ABSENT);
        } else {
            out.writeInt(PRESENT);
            out.writeIntArray(mInitialTimings);
        }
        out.writeInt(mBucketCount);
        out.writeIntArray(mBucketEndPoints);
        out.writeIntArray(mBucketCounters);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
