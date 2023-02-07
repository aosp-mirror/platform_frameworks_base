/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.expresslog;

import android.annotation.NonNull;

import com.android.internal.util.FrameworkStatsLog;

/** CounterHistogram encapsulates StatsD write API calls */
public final class Histogram {

    private final long mMetricIdHash;
    private final BinOptions mBinOptions;

    public Histogram(@NonNull String metricId, @NonNull BinOptions binOptions) {
        mMetricIdHash = Utils.hashString(metricId);
        mBinOptions = binOptions;
    }

    /**
     * Logs increment sample count for automatically calculated bin
     *
     * @hide
     */
    public void logSample(float sample) {
        final int binIndex = mBinOptions.getBinForSample(sample);
        FrameworkStatsLog.write(FrameworkStatsLog.EXPRESS_HISTOGRAM_SAMPLE_REPORTED, mMetricIdHash,
                /*count*/ 1, binIndex);
    }

    /** Used by CounterHistogram to map data sample to corresponding bin */
    public interface BinOptions {
        /**
         * Returns bins count to be used by counter histogram
         *
         * @return bins count used to initialize Options, including overflow & underflow bins
         * @hide
         */
        int getBinsCount();

        /**
         * @return zero based index
         * Calculates bin index for the input sample value
         * index == 0 stands for underflow
         * index == getBinsCount() - 1 stands for overflow
         * @hide
         */
        int getBinForSample(float sample);
    }

    /** Used by CounterHistogram to map data sample to corresponding bin for on uniform bins */
    public static final class UniformOptions implements BinOptions {

        private final int mBinCount;
        private final float mMinValue;
        private final float mExclusiveMaxValue;
        private final float mBinSize;

        public UniformOptions(int binCount, float minValue, float exclusiveMaxValue) {
            if (binCount < 1) {
                throw new IllegalArgumentException("Bin count should be positive number");
            }

            if (exclusiveMaxValue <= minValue) {
                throw new IllegalArgumentException("Bins range invalid (maxValue < minValue)");
            }

            mMinValue = minValue;
            mExclusiveMaxValue = exclusiveMaxValue;
            mBinSize = (mExclusiveMaxValue - minValue) / binCount;

            // Implicitly add 2 for the extra undeflow & overflow bins
            mBinCount = binCount + 2;
        }

        @Override
        public int getBinsCount() {
            return mBinCount;
        }

        @Override
        public int getBinForSample(float sample) {
            if (sample < mMinValue) {
                // goes to underflow
                return 0;
            } else if (sample >= mExclusiveMaxValue) {
                // goes to overflow
                return mBinCount - 1;
            }
            return (int) ((sample - mMinValue) / mBinSize + 1);
        }
    }
}
