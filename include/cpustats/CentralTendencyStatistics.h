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

#ifndef _CENTRAL_TENDENCY_STATISTICS_H
#define _CENTRAL_TENDENCY_STATISTICS_H

#include <math.h>

// Not multithread safe
class CentralTendencyStatistics {

public:

    CentralTendencyStatistics() :
            mMean(NAN), mMedian(NAN), mMinimum(INFINITY), mMaximum(-INFINITY), mN(0), mM2(0),
            mVariance(NAN), mVarianceKnownForN(0), mStddev(NAN), mStddevKnownForN(0) { }

    ~CentralTendencyStatistics() { }

    // add x to the set of samples
    void sample(double x);

    // return the arithmetic mean of all samples so far
    double mean() const { return mMean; }

    // return the minimum of all samples so far
    double minimum() const { return mMinimum; }

    // return the maximum of all samples so far
    double maximum() const { return mMaximum; }

    // return the variance of all samples so far
    double variance() const;

    // return the standard deviation of all samples so far
    double stddev() const;

    // return the number of samples added so far
    unsigned n() const { return mN; }

    // reset the set of samples to be empty
    void reset();

private:
    double mMean;
    double mMedian;
    double mMinimum;
    double mMaximum;
    unsigned mN;    // number of samples so far
    double mM2;

    // cached variance, and n at time of caching
    mutable double mVariance;
    mutable unsigned mVarianceKnownForN;

    // cached standard deviation, and n at time of caching
    mutable double mStddev;
    mutable unsigned mStddevKnownForN;

};

#endif // _CENTRAL_TENDENCY_STATISTICS_H
