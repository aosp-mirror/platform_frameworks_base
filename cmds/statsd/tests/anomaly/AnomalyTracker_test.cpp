// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "src/anomaly/DiscreteAnomalyTracker.h"

#include <gtest/gtest.h>
#include <stdio.h>
#include <vector>

using namespace testing;
using android::sp;
using std::set;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

void AddValueToBucket(const std::vector<std::pair<string, long>>& key_value_pair_list,
                      std::shared_ptr<DimToValMap> bucket) {
    for (auto itr = key_value_pair_list.begin(); itr != key_value_pair_list.end(); itr++) {
        (*bucket)[itr->first] += itr->second;
    }
}

std::shared_ptr<DimToValMap> MockeBucket(
        const std::vector<std::pair<string, long>>& key_value_pair_list) {
    std::shared_ptr<DimToValMap> bucket = std::make_shared<DimToValMap>();
    AddValueToBucket(key_value_pair_list, bucket);
    return bucket;
}

TEST(AnomalyTrackerTest, TestConsecutiveBuckets) {
    Alert alert;
    alert.set_number_of_buckets(3);
    alert.set_refractory_period_in_buckets(3);
    alert.set_trigger_if_sum_gt(2);

    DiscreteAnomalyTracker anomaly_tracker(alert);

    std::shared_ptr<DimToValMap> bucket0 = MockeBucket({{"a", 1}, {"b", 2}, {"c", 1}});
    // Adds bucket #0
    anomaly_tracker.addOrUpdateBucket(bucket0, 0);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 1);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 2);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    EXPECT_FALSE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 0L);

    // Adds bucket #0 again. The sum does not change.
    anomaly_tracker.addOrUpdateBucket(bucket0, 0);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 0L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 1);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 2);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    EXPECT_FALSE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 0L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, -1L);

    // Adds bucket #1.
    std::shared_ptr<DimToValMap> bucket1 = MockeBucket({{"b", 2}});
    anomaly_tracker.addOrUpdateBucket(bucket1, 1);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 1L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 1);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 4);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    // Alarm.
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 1L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 1L);

    // Adds bucket #1 again. The sum does not change.
    anomaly_tracker.addOrUpdateBucket(bucket1, 1);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 1L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 1);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 4);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    // Alarm.
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 1L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 1L);

    // Adds bucket #2.
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"a", 1}}), 2);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 2L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 2);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 4);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    // Within refractory period.
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 1L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 1L);

    // Adds bucket #3.
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"a", 1}}), 3);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 3L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 2);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 2);
    EXPECT_FALSE(anomaly_tracker.detectAnomaly());

    // Adds bucket #3.
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"a", 2}}), 4);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 4L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 4);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    // Within refractory period.
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 1L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 1L);

    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"a", 1}}), 5);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 5L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 4);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    // Within refractory period.
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 2L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 5L);
}

TEST(AnomalyTrackerTest, TestSparseBuckets) {
    Alert alert;
    alert.set_number_of_buckets(3);
    alert.set_refractory_period_in_buckets(3);
    alert.set_trigger_if_sum_gt(2);

    DiscreteAnomalyTracker anomaly_tracker(alert);

    // Add bucket #9
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"a", 1}, {"b", 2}, {"c", 1}}), 9);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 9L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("a")->second, 1);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 2);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    EXPECT_FALSE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 0L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, -1L);

    // Add bucket #16
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"b", 4}}), 16);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 16L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 4);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 1L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 16L);

    // Add bucket #18
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"b", 1}, {"c", 1}}), 18);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 18L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 5);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    // Within refractory period.
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 1L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 16L);

    // Add bucket #18 again.
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"b", 1}, {"c", 1}}), 18);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 18L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 5);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 1L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 16L);

    // Add bucket #20
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"b", 3}, {"d", 1}}), 20);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 20L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("b")->second, 4);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("c")->second, 1);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("d")->second, 1);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 2L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 20L);

    // Add bucket #25
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"d", 1}}), 25);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 25L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("d")->second, 1L);
    EXPECT_FALSE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 2L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 20L);

    // Add bucket #28
    anomaly_tracker.addOrUpdateBucket(MockeBucket({{"e", 5}}), 28);
    EXPECT_EQ(anomaly_tracker.mCurrentBucketIndex, 28L);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomaly_tracker.mSumOverPastBuckets.find("e")->second, 5L);
    EXPECT_TRUE(anomaly_tracker.detectAnomaly());
    anomaly_tracker.declareAndDeclareAnomaly();
    EXPECT_EQ(anomaly_tracker.mAnomalyDeclared, 3L);
    EXPECT_EQ(anomaly_tracker.mLastAlarmAtBucketIndex, 28L);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
