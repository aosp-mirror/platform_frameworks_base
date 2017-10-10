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

#define LOG_TAG "CountAnomaly"
#define DEBUG true  // STOPSHIP if true
#define VLOG(...) \
    if (DEBUG) ALOGD(__VA_ARGS__);

#include "CountAnomalyTracker.h"

#include <cutils/log.h>

namespace android {
namespace os {
namespace statsd {

CountAnomalyTracker::CountAnomalyTracker(size_t numBuckets, int thresholdGt)
    : mNumPastBuckets(numBuckets > 0 ? numBuckets - 1 : 0),
      mPastBuckets(mNumPastBuckets > 0 ? (new int[mNumPastBuckets]) : nullptr),
      mThresholdGt(thresholdGt) {

    VLOG("CountAnomalyTracker() called");
    if (numBuckets < 1) {
        ALOGE("Cannot create CountAnomalyTracker with %zu buckets", numBuckets);
    }
    reset(); // initialization
}

CountAnomalyTracker::~CountAnomalyTracker() {
    VLOG("~CountAnomalyTracker() called");
}

void CountAnomalyTracker::addPastBucket(int pastBucketCount,
                                        time_t numberOfBucketsAgo) {
    VLOG("addPastBucket() called.");
    if (numberOfBucketsAgo < 1) {
        ALOGE("Cannot add a past bucket %ld units in past", numberOfBucketsAgo);
        return;
    }
    // If past bucket was ancient, just empty out all past info.
    // This always applies if mNumPastBuckets == 0 (i.e. store no past buckets).
    if (numberOfBucketsAgo > (time_t) mNumPastBuckets) {
        reset();
        return;
    }

    // Empty out old mPastBuckets[i] values and update mSumPastCounters.
    for (size_t i = mOldestBucketIndex;
                        i < mOldestBucketIndex + numberOfBucketsAgo; i++) {
        mSumPastCounters -= mPastBuckets[index(i)];
        mPastBuckets[index(i)] = 0;
    }

    // Replace the oldest bucket with the new bucket we are adding.
    mPastBuckets[mOldestBucketIndex] = pastBucketCount;
    mSumPastCounters += pastBucketCount;

    // Advance the oldest bucket index by numberOfBucketsAgo units.
    mOldestBucketIndex = index(mOldestBucketIndex + numberOfBucketsAgo);

    // TODO: Once dimensions are added to mSumPastCounters:
    // iterate through mSumPastCounters and remove any entries that are 0.
}

void CountAnomalyTracker::reset() {
    VLOG("reset() called.");
    for (size_t i = 0; i < mNumPastBuckets; i++) {
        mPastBuckets[i] = 0;
    }
    mSumPastCounters = 0;
    mOldestBucketIndex = 0;
}

void CountAnomalyTracker::checkAnomaly(int currentCount) {
    // Note that this works even if mNumPastBuckets < 1 (since then
    // mSumPastCounters = 0 so the comparison is based only on currentCount).

    // TODO: Remove these extremely verbose debugging log.
    VLOG("Checking whether %d + %d > %d",
         mSumPastCounters, currentCount, mThresholdGt);

    if (mSumPastCounters + currentCount > mThresholdGt) {
        declareAnomaly();
    }
}

void CountAnomalyTracker::declareAnomaly() {
    // TODO: check that not in refractory period.
    // TODO: Do something.
    ALOGI("An anomaly has occurred!");
}

}  // namespace statsd
}  // namespace os
}  // namespace android
