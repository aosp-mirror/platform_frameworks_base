/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <stdlib.h>

#include <cpustats/CentralTendencyStatistics.h>

void CentralTendencyStatistics::sample(double x)
{
    // update min and max
    if (x < mMinimum)
        mMinimum = x;
    if (x > mMaximum)
        mMaximum = x;
    // Knuth
    if (mN == 0) {
        mMean = 0;
    }
    ++mN;
    double delta = x - mMean;
    mMean += delta / mN;
    mM2 += delta * (x - mMean);
}

void CentralTendencyStatistics::reset()
{
    mMean = NAN;
    mMedian = NAN;
    mMinimum = INFINITY;
    mMaximum = -INFINITY;
    mN = 0;
    mM2 = 0;
    mVariance = NAN;
    mVarianceKnownForN = 0;
    mStddev = NAN;
    mStddevKnownForN = 0;
}

double CentralTendencyStatistics::variance() const
{
    double variance;
    if (mVarianceKnownForN != mN) {
        if (mN > 1) {
            // double variance_n = M2/n;
            variance = mM2 / (mN - 1);
        } else {
            variance = NAN;
        }
        mVariance = variance;
        mVarianceKnownForN = mN;
    } else {
        variance = mVariance;
    }
    return variance;
}

double CentralTendencyStatistics::stddev() const
{
    double stddev;
    if (mStddevKnownForN != mN) {
        stddev = sqrt(variance());
        mStddev = stddev;
        mStddevKnownForN = mN;
    } else {
        stddev = mStddev;
    }
    return stddev;
}
