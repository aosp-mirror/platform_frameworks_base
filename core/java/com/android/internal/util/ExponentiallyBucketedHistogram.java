/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.util;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import java.util.Arrays;

/**
 * A histogram for positive integers where each bucket is twice the size of the previous one.
 */
public class ExponentiallyBucketedHistogram {
    @NonNull
    private final int[] mData;

    /**
     * Create a new histogram.
     *
     * @param numBuckets The number of buckets. The highest bucket is for all value >=
     *                   2<sup>numBuckets - 1</sup>
     */
    public ExponentiallyBucketedHistogram(@IntRange(from = 1, to = 31) int numBuckets) {
        numBuckets = Preconditions.checkArgumentInRange(numBuckets, 1, 31, "numBuckets");

        mData = new int[numBuckets];
    }

    /**
     * Add a new value to the histogram.
     *
     * All values <= 0 are in the first bucket. The last bucket contains all values >=
     * 2<sup>numBuckets - 1</sup>
     *
     * @param value The value to add
     */
    public void add(int value) {
        if (value <= 0) {
            mData[0]++;
        } else {
            mData[Math.min(mData.length - 1, 32 - Integer.numberOfLeadingZeros(value))]++;
        }
    }

    /**
     * Clear all data from the histogram
     */
    public void reset() {
        Arrays.fill(mData, 0);
    }

    /**
     * Write the histogram to the log.
     *
     * @param tag    The tag to use when logging
     * @param prefix A custom prefix that is printed in front of the histogram
     */
    public void log(@NonNull String tag, @Nullable CharSequence prefix) {
        StringBuilder builder = new StringBuilder(prefix);
        builder.append('[');

        for (int i = 0; i < mData.length; i++) {
            if (i != 0) {
                builder.append(", ");
            }

            if (i < mData.length - 1) {
                builder.append("<");
                builder.append(1 << i);
            } else {
                builder.append(">=");
                builder.append(1 << (i - 1));
            }

            builder.append(": ");
            builder.append(mData[i]);
        }
        builder.append("]");

        Log.d(tag, builder.toString());
    }
}
