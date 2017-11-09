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

#pragma once

#include <gtest/gtest_prod.h>
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h" // Alert
#include "stats_util.h" // HashableDimensionKey and DimToValMap

#include <memory> // unique_ptr
#include <stdlib.h>

namespace android {
namespace os {
namespace statsd {

using std::unordered_map;
using std::shared_ptr;

// This anomaly track assmues that all values are non-negative.
class DiscreteAnomalyTracker {
 public:
    DiscreteAnomalyTracker(const Alert& alert);

    virtual ~DiscreteAnomalyTracker();

    // Adds a new bucket or updates an existing bucket.
    // Bucket index starts from 0.
    void addOrUpdateBucket(std::shared_ptr<const DimToValMap> BucketValues, int64_t bucketIndex);

    // Returns true if detected anomaly for the existing buckets on one or more dimension keys.
    bool detectAnomaly();

    // Informs incidentd about the detected alert.
    void declareAnomaly();

    // Detects the alert and informs the incidentd when applicable.
    void declareAndDeclareAnomaly();

private:
    // statsd_config.proto Alert message that defines this tracker.
    const Alert mAlert;

    // The exisiting bucket list.
    std::vector<shared_ptr<const DimToValMap>> mPastBuckets;

    // Sum over all existing buckets cached in mPastBuckets.
    DimToValMap mSumOverPastBuckets;

    // Current bucket index of the current anomaly detection window. Bucket index starts from 0.
    int64_t mCurrentBucketIndex = -1;

    // The bucket index when the last anomaly was declared.
    int64_t mLastAlarmAtBucketIndex = -1;

    // The total number of declared anomalies.
    int64_t mAnomalyDeclared = 0;

    // Add the information in the given bucket to mSumOverPastBuckets.
    void addBucketToSum(const shared_ptr<const DimToValMap>& bucket);

    // Subtract the information in the given bucket from mSumOverPastBuckets
    // and remove any items with value 0.
    void subtractBucketFromSum(const shared_ptr<const DimToValMap>& bucket);

    // Calculates the corresponding bucket index within the circular array.
    size_t index(int64_t bucketNum);

    // Resets all data. For use when all the data gets stale.
    void reset();

    FRIEND_TEST(AnomalyTrackerTest, TestConsecutiveBuckets);
    FRIEND_TEST(AnomalyTrackerTest, TestSparseBuckets);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
