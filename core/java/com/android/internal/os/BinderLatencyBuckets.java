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

package com.android.internal.os;

import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * Generates the bucket thresholds (with a custom logarithmic scale) for a histogram to store
 * latency samples in.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class BinderLatencyBuckets {
    private static final String TAG = "BinderLatencyBuckets";
    private final int[] mBuckets;

    /**
     * @param bucketCount      the number of buckets the histogram should have
     * @param firstBucketSize  the size of the first bucket (used to avoid excessive small buckets)
     * @param scaleFactor      the rate in which each consecutive bucket increases (before rounding)
     */
    public BinderLatencyBuckets(int bucketCount, int firstBucketSize, float scaleFactor) {
        int[] buffer = new int[bucketCount - 1];
        buffer[0] = firstBucketSize;

        // Last value and the target are disjoint as we never want to create buckets smaller than 1.
        double lastTarget = firstBucketSize;

        // First bucket is already created and the last bucket is anything greater than the final
        // bucket in the list, so create 'bucketCount' - 2 buckets.
        for (int i = 1; i < bucketCount - 1; i++) {
            // Increase the target bucket limit value by the scale factor.
            double nextTarget = lastTarget * scaleFactor;

            if (nextTarget > Integer.MAX_VALUE) {
                // Do not throw an exception here as this should not affect binder calls.
                Slog.w(TAG, "Attempted to create a bucket larger than maxint");
                mBuckets = Arrays.copyOfRange(buffer, 0, i);
                return;
            }

            if ((int) nextTarget > buffer[i - 1]) {
                // Convert the target bucket limit value to an integer.
                buffer[i] = (int) nextTarget;
            } else {
                // Avoid creating redundant buckets, so bucket size should be 1 at a minimum.
                buffer[i] = buffer[i - 1] + 1;
            }
            lastTarget = nextTarget;
        }
        mBuckets = buffer;
    }

    /** Gets the bucket index to insert the provided sample in. */
    public int sampleToBucket(int sample) {
        if (sample >= mBuckets[mBuckets.length - 1]) {
            return mBuckets.length;
        }

        // Binary search returns the element index if it is contained in the list - in this case the
        // correct bucket is the index after as we use [minValue, maxValue) for bucket boundaries.
        // Otherwise, it returns (-(insertion point) - 1), where insertion point is the point where
        // to insert the element so that the array remains sorted - in this case the bucket index
        // is the insertion point.
        int searchResult = Arrays.binarySearch(mBuckets, sample);
        return searchResult < 0 ? -(1 + searchResult) : searchResult + 1;
    }

    @VisibleForTesting
    public int[] getBuckets() {
        return mBuckets;
    }
}
