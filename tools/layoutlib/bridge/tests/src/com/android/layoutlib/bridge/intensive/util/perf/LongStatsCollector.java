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

package com.android.layoutlib.bridge.intensive.util.perf;

import android.annotation.NonNull;
import android.util.LongArray;

import java.util.Arrays;
import java.util.function.LongConsumer;

/**
 * Class that collect a series of longs and produces the median, min and max values.
 */
public class LongStatsCollector implements LongConsumer {
    private final LongArray mAllValues;
    private long mMin = Long.MAX_VALUE;
    private long mMax = Long.MIN_VALUE;
    public LongStatsCollector(int estimatedRuns) {
        mAllValues = new LongArray(estimatedRuns);
    }

    public int size() {
        return mAllValues.size();
    }

    @NonNull
    public Stats getStats() {
        if (mAllValues.size() == 0) {
            throw new IndexOutOfBoundsException("No data");
        }

        double median;
        int size = mAllValues.size();
        long[] buffer = new long[size];
        for (int i = 0; i < size; i++) {
            buffer[i] = mAllValues.get(i);
        }

        Arrays.sort(buffer);

        int midPoint = size / 2;
        median = (size % 2 == 0) ? (buffer[midPoint - 1] + buffer[midPoint]) / 2 : buffer[midPoint];

        return new Stats(mAllValues.size(), mMin, mMax, median);
    }

    @Override
    public void accept(long value) {
        mMin = Math.min(mMin, value);
        mMax = Math.max(mMax, value);
        mAllValues.add(value);
    }

    public static class Stats {
        private final int mSamples;
        private final long mMin;
        private final long mMax;
        private final double mMedian;

        private Stats(int samples, long min, long max, double median) {
            mSamples = samples;
            mMin = min;
            mMax = max;
            mMedian = median;
        }

        public int getSampleCount() {
            return mSamples;
        }

        public long getMin() {
            return mMin;
        }

        public long getMax() {
            return mMax;
        }

        public double getMedian() {
            return mMedian;
        }
    }
}
