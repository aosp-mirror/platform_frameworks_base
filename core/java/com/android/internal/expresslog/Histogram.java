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

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;

import com.android.internal.util.FrameworkStatsLog;

import java.util.Arrays;

/** Histogram encapsulates StatsD write API calls */
public final class Histogram {

    private final long mMetricIdHash;
    private final BinOptions mBinOptions;

    /**
     * Creates Histogram metric logging wrapper
     *
     * @param metricId   to log, logging will be no-op if metricId is not defined in the TeX catalog
     * @param binOptions to calculate bin index for samples
     * @hide
     */
    public Histogram(@NonNull String metricId, @NonNull BinOptions binOptions) {
        mMetricIdHash = Utils.hashString(metricId);
        mBinOptions = binOptions;
    }

    /**
     * Logs increment sample count for automatically calculated bin
     *
     * @param sample value
     * @hide
     */
    public void logSample(float sample) {
        final int binIndex = mBinOptions.getBinForSample(sample);
        FrameworkStatsLog.write(FrameworkStatsLog.EXPRESS_HISTOGRAM_SAMPLE_REPORTED, mMetricIdHash,
                /*count*/ 1, binIndex);
    }

    /** Used by Histogram to map data sample to corresponding bin */
    public interface BinOptions {
        /**
         * Returns bins count to be used by a histogram
         *
         * @return bins count used to initialize Options, including overflow & underflow bins
         * @hide
         */
        int getBinsCount();

        /**
         * Returns bin index for the input sample value
         * index == 0 stands for underflow
         * index == getBinsCount() - 1 stands for overflow
         *
         * @return zero based index
         * @hide
         */
        int getBinForSample(float sample);
    }

    /** Used by Histogram to map data sample to corresponding bin for uniform bins */
    public static final class UniformOptions implements BinOptions {

        private final int mBinCount;
        private final float mMinValue;
        private final float mExclusiveMaxValue;
        private final float mBinSize;

        /**
         * Creates options for uniform (linear) sized bins
         *
         * @param binCount          amount of histogram bins. 2 bin indexes will be calculated
         *                          automatically to represent underflow & overflow bins
         * @param minValue          is included in the first bin, values less than minValue
         *                          go to underflow bin
         * @param exclusiveMaxValue is included in the overflow bucket. For accurate
         *                          measure up to kMax, then exclusiveMaxValue
         *                          should be set to kMax + 1
         * @hide
         */
        public UniformOptions(@IntRange(from = 1) int binCount, float minValue,
                float exclusiveMaxValue) {
            if (binCount < 1) {
                throw new IllegalArgumentException("Bin count should be positive number");
            }

            if (exclusiveMaxValue <= minValue) {
                throw new IllegalArgumentException("Bins range invalid (maxValue < minValue)");
            }

            mMinValue = minValue;
            mExclusiveMaxValue = exclusiveMaxValue;
            mBinSize = (mExclusiveMaxValue - minValue) / binCount;

            // Implicitly add 2 for the extra underflow & overflow bins
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

    /** Used by Histogram to map data sample to corresponding bin for scaled bins */
    public static final class ScaledRangeOptions implements BinOptions {
        // store minimum value per bin
        final long[] mBins;

        /**
         * Creates options for scaled range bins
         *
         * @param binCount      amount of histogram bins. 2 bin indexes will be calculated
         *                      automatically to represent underflow & overflow bins
         * @param minValue      is included in the first bin, values less than minValue
         *                      go to underflow bin
         * @param firstBinWidth used to represent first bin width and as a reference to calculate
         *                      width for consecutive bins
         * @param scaleFactor   used to calculate width for consecutive bins
         * @hide
         */
        public ScaledRangeOptions(@IntRange(from = 1) int binCount, int minValue,
                @FloatRange(from = 1.f) float firstBinWidth,
                @FloatRange(from = 1.f) float scaleFactor) {
            if (binCount < 1) {
                throw new IllegalArgumentException("Bin count should be positive number");
            }

            if (firstBinWidth < 1.f) {
                throw new IllegalArgumentException(
                        "First bin width invalid (should be 1.f at minimum)");
            }

            if (scaleFactor < 1.f) {
                throw new IllegalArgumentException(
                        "Scaled factor invalid (should be 1.f at minimum)");
            }

            // precalculating bins ranges (no need to create a bin for underflow reference value)
            mBins = initBins(binCount + 1, minValue, firstBinWidth, scaleFactor);
        }

        @Override
        public int getBinsCount() {
            return mBins.length + 1;
        }

        @Override
        public int getBinForSample(float sample) {
            if (sample < mBins[0]) {
                // goes to underflow
                return 0;
            } else if (sample >= mBins[mBins.length - 1]) {
                // goes to overflow
                return mBins.length;
            }

            return lower_bound(mBins, (long) sample) + 1;
        }

        // To find lower bound using binary search implementation of Arrays utility class
        private static int lower_bound(long[] array, long sample) {
            int index = Arrays.binarySearch(array, sample);
            // If key is not present in the array
            if (index < 0) {
                // Index specify the position of the key when inserted in the sorted array
                // so the element currently present at this position will be the lower bound
                return Math.abs(index) - 2;
            }
            return index;
        }

        private static long[] initBins(int count, int minValue, float firstBinWidth,
                float scaleFactor) {
            long[] bins = new long[count];
            bins[0] = minValue;
            double lastWidth = firstBinWidth;
            for (int i = 1; i < count; i++) {
                // current bin minValue = previous bin width * scaleFactor
                double currentBinMinValue = bins[i - 1] + lastWidth;
                if (currentBinMinValue > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                        "Attempted to create a bucket larger than maxint");
                }

                bins[i] = (long) currentBinMinValue;
                lastWidth *= scaleFactor;
            }
            return bins;
        }
    }
}
