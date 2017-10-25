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

#ifndef COUNT_ANOMALY_TRACKER_H
#define COUNT_ANOMALY_TRACKER_H

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h" // Alert

#include <memory> // unique_ptr
#include <stdlib.h>

namespace android {
namespace os {
namespace statsd {

// TODO: Can probably be used for Count, Value, and Gauge. If so, rename to ValueAnomalyTracker.
// (caveat: currently, the value cannot be negative. Probably fine for P.)
class CountAnomalyTracker {
public:
    CountAnomalyTracker(const Alert& alert);

    virtual ~CountAnomalyTracker();


    // Adds a new past bucket, holding pastBucketCount, and then advances the
    // present by numberOfBucketsAgo buckets (filling any intervening buckets
    // with 0s).
    // Thus, the newly added bucket (which holds pastBucketCount) is stored
    // numberOfBucketsAgo buckets ago.
    void addPastBucket(int pastBucketCount, time_t numberOfBucketsAgo);

    // Informs the anomaly tracker of the current bucket's count, so that it can
    // determine whether an anomaly has occurred. This value is not stored.
    void checkAnomaly(int currentCount);

private:
    // statsd_config.proto Alert message that defines this tracker.
    const Alert mAlert;

    // Number of past buckets. One less than the total number of buckets needed
    // for the anomaly detection (since the current bucket is not in the past).
    const size_t mNumPastBuckets;

    // Count values for each of the past mNumPastBuckets buckets.
    // TODO: Add dimensions. This parallels the type of CountMetricProducer.mCounter.
    std::unique_ptr<int[]> mPastBuckets;

    // Sum over all of mPastBuckets (cached).
    // TODO: Add dimensions. This parallels the type of CountMetricProducer.mCounter.
    //       At that point, mSumPastCounters must never contain entries of 0.
    int mSumPastCounters;

    // Index of the oldest bucket (i.e. the next bucket to be overwritten).
    size_t mOldestBucketIndex = 0;

    // Timestamp that the refractory period (if this anomaly was declared) ends, in seconds.
    // If an anomaly was never declared, set to 0.
    time_t mRefractoryPeriodEndsSec = 0;

    void declareAnomaly();

    // Calculates the corresponding index within the circular array.
    size_t index(size_t unsafeIndex) {
        return unsafeIndex % mNumPastBuckets;
    }

    // Resets all data. For use when all the data gets stale.
    void reset();
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COUNT_ANOMALY_TRACKER_H
