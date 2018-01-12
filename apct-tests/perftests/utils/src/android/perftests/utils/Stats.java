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
    private long mMedian, mMin, mMax;
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

        mMedian = size % 2 == 0 ? (values.get(size / 2) + values.get(size / 2 - 1)) / 2 :
                values.get(size / 2);

        mMin = values.get(0);
        mMax = values.get(values.size() - 1);

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
}
