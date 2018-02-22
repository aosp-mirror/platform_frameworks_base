/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.perftests.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Stats {
    private long mMedian, mMin, mMax, mPercentile90, mPercentile95;
    private double mMean, mStandardDeviation;

    /* Calculate stats in constructor. */
    public Stats(List<Long> values) {
        // make a copy since we're modifying it
        values = new ArrayList<>(values);
        final int size = values.size();
        if (size < 2) {
            throw new IllegalArgumentException("At least two results are necessary.");
        }

        Collections.sort(values);

        mMin = values.get(0);
        mMax = values.get(values.size() - 1);

        mMedian = size % 2 == 0 ? (values.get(size / 2) + values.get(size / 2 - 1)) / 2 :
                values.get(size / 2);
        mPercentile90 = getPercentile(values, 90);
        mPercentile95 = getPercentile(values, 95);

        for (int i = 0; i < size; ++i) {
            long result = values.get(i);
            mMean += result;
        }
        mMean /= (double) size;

        for (int i = 0; i < size; ++i) {
            final double tmp = values.get(i) - mMean;
            mStandardDeviation += tmp * tmp;
        }
        mStandardDeviation = Math.sqrt(mStandardDeviation / (double) (size - 1));
    }

    public double getMean() {
        return mMean;
    }

    public long getMedian() {
        return mMedian;
    }

    public long getMax() {
        return mMax;
    }

    public long getMin() {
        return mMin;
    }

    public double getStandardDeviation() {
        return mStandardDeviation;
    }

    public long getPercentile90() {
        return mPercentile90;
    }

    public long getPercentile95() {
        return mPercentile95;
    }

    private static long getPercentile(List<Long> values, int percentile) {
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException(
                    "invalid percentile " + percentile + ", should be 0-100");
        }
        int idx = (values.size() - 1) * percentile / 100;
        return values.get(idx);
    }
}
