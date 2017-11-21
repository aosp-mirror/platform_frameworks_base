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

#include "src/anomaly/AnomalyTracker.h"

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

std::shared_ptr<DimToValMap> MockBucket(
        const std::vector<std::pair<string, long>>& key_value_pair_list) {
    std::shared_ptr<DimToValMap> bucket = std::make_shared<DimToValMap>();
    AddValueToBucket(key_value_pair_list, bucket);
    return bucket;
}

TEST(AnomalyTrackerTest, TestConsecutiveBuckets) {
    const int64_t bucketSizeNs = 30 * NS_PER_SEC;
    Alert alert;
    alert.set_number_of_buckets(3);
    alert.set_refractory_period_secs(2 * bucketSizeNs / NS_PER_SEC);
    alert.set_trigger_if_sum_gt(2);

    AnomalyTracker anomalyTracker(alert, bucketSizeNs);

    std::shared_ptr<DimToValMap> bucket0 = MockBucket({{"a", 1}, {"b", 2}, {"c", 1}});
    int64_t eventTimestamp0 = 10;
    std::shared_ptr<DimToValMap> bucket1 = MockBucket({{"a", 1}});
    int64_t eventTimestamp1 = bucketSizeNs + 11;
    std::shared_ptr<DimToValMap> bucket2 = MockBucket({{"b", 1}});
    int64_t eventTimestamp2 = 2 * bucketSizeNs + 12;
    std::shared_ptr<DimToValMap> bucket3 = MockBucket({{"a", 2}});
    int64_t eventTimestamp3 = 3 * bucketSizeNs + 13;
    std::shared_ptr<DimToValMap> bucket4 = MockBucket({{"b", 1}});
    int64_t eventTimestamp4 = 4 * bucketSizeNs + 14;
    std::shared_ptr<DimToValMap> bucket5 = MockBucket({{"a", 2}});
    int64_t eventTimestamp5 = 5 * bucketSizeNs + 15;
    std::shared_ptr<DimToValMap> bucket6 = MockBucket({{"a", 2}});
    int64_t eventTimestamp6 = 6 * bucketSizeNs + 16;

    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0u);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, -1LL);
    EXPECT_FALSE(anomalyTracker.detectAnomaly(0, *bucket0));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp0, 0, *bucket0);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, -1L);

    // Adds past bucket #0
    anomalyTracker.addPastBucket(bucket0, 0);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3u);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 0LL);
    EXPECT_FALSE(anomalyTracker.detectAnomaly(1, *bucket1));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp1, 1, *bucket1);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, -1L);

    // Adds past bucket #0 again. The sum does not change.
    anomalyTracker.addPastBucket(bucket0, 0);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3u);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 0LL);
    EXPECT_FALSE(anomalyTracker.detectAnomaly(1, *bucket1));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp1 + 1, 1, *bucket1);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, -1L);

    // Adds past bucket #1.
    anomalyTracker.addPastBucket(bucket1, 1);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 1L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(2, *bucket2));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp2, 2, *bucket2);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp2);

    // Adds past bucket #1 again. Nothing changes.
    anomalyTracker.addPastBucket(bucket1, 1);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 1L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(2, *bucket2));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp2 + 1, 2, *bucket2);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp2);

    // Adds past bucket #2.
    anomalyTracker.addPastBucket(bucket2, 2);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 2L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(3, *bucket3));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp3, 3, *bucket3);
    // Within refractory period.
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp2);

    // Adds bucket #3.
    anomalyTracker.addPastBucket(bucket3, 3L);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 3L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_FALSE(anomalyTracker.detectAnomaly(4, *bucket4));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp4, 4, *bucket4);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp2);

    // Adds bucket #4.
    anomalyTracker.addPastBucket(bucket4, 4);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 4L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(5, *bucket5));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp5, 5, *bucket5);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp5);

    // Adds bucket #5.
    anomalyTracker.addPastBucket(bucket5, 5);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 5L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(6, *bucket6));
    // Within refractory period.
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp6, 6, *bucket6);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp5);
}

TEST(AnomalyTrackerTest, TestSparseBuckets) {
    const int64_t bucketSizeNs = 30 * NS_PER_SEC;
    Alert alert;
    alert.set_number_of_buckets(3);
    alert.set_refractory_period_secs(2 * bucketSizeNs / NS_PER_SEC);
    alert.set_trigger_if_sum_gt(2);

    AnomalyTracker anomalyTracker(alert, bucketSizeNs);

    std::shared_ptr<DimToValMap> bucket9 = MockBucket({{"a", 1}, {"b", 2}, {"c", 1}});
    std::shared_ptr<DimToValMap> bucket16 = MockBucket({{"b", 4}});
    std::shared_ptr<DimToValMap> bucket18 = MockBucket({{"b", 1}, {"c", 1}});
    std::shared_ptr<DimToValMap> bucket20 = MockBucket({{"b", 3}, {"c", 1}});
    std::shared_ptr<DimToValMap> bucket25 = MockBucket({{"d", 1}});
    std::shared_ptr<DimToValMap> bucket28 = MockBucket({{"e", 2}});

    int64_t eventTimestamp1 = bucketSizeNs * 8 + 1;
    int64_t eventTimestamp2 = bucketSizeNs * 15 + 11;
    int64_t eventTimestamp3 = bucketSizeNs * 17 + 1;
    int64_t eventTimestamp4 = bucketSizeNs * 19 + 2;
    int64_t eventTimestamp5 = bucketSizeNs * 24 + 3;
    int64_t eventTimestamp6 = bucketSizeNs * 27 + 3;

    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, -1LL);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_FALSE(anomalyTracker.detectAnomaly(9, *bucket9));
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp1, 9, *bucket9);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, -1);

    // Add past bucket #9
    anomalyTracker.addPastBucket(bucket9, 9);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 9L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("a"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(16, *bucket16));
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 15L);
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp2, 16, *bucket16);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp2);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 15L);

    // Add past bucket #16
    anomalyTracker.addPastBucket(bucket16, 16);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 16L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 4LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(18, *bucket18));
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 4LL);
    // Within refractory period.
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp3, 18, *bucket18);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp2);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 4LL);

    // Add past bucket #18
    anomalyTracker.addPastBucket(bucket18, 18);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 18L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(20, *bucket20));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 19L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp4, 20, *bucket20);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp4);

    // Add bucket #18 again. Nothing changes.
    anomalyTracker.addPastBucket(bucket18, 18);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 19L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_TRUE(anomalyTracker.detectAnomaly(20, *bucket20));
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp4 + 1, 20, *bucket20);
    // Within refractory period.
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp4);

    // Add past bucket #20
    anomalyTracker.addPastBucket(bucket20, 20);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 20L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("b"), 3LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("c"), 1LL);
    EXPECT_FALSE(anomalyTracker.detectAnomaly(25, *bucket25));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 24L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp5, 25, *bucket25);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp4);

    // Add past bucket #25
    anomalyTracker.addPastBucket(bucket25, 25);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 25L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets("d"), 1LL);
    EXPECT_FALSE(anomalyTracker.detectAnomaly(28, *bucket28));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 27L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp6, 28, *bucket28);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp4);

    // Updates current bucket #28.
    (*bucket28)["e"] = 5;
    EXPECT_TRUE(anomalyTracker.detectAnomaly(28, *bucket28));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 27L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    anomalyTracker.detectAndDeclareAnomaly(eventTimestamp6 + 7, 28, *bucket28);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_EQ(anomalyTracker.mLastAlarmTimestampNs, eventTimestamp6 + 7);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
