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
#include "../metrics/metrics_test_helper.h"

#include <gtest/gtest.h>
#include <math.h>
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

const ConfigKey kConfigKey(0, 12345);

MetricDimensionKey getMockMetricDimensionKey(int key, string value) {
    int pos[] = {key, 0, 0};
    HashableDimensionKey dim;
    dim.addValue(FieldValue(Field(1, pos, 0), Value(value)));
    return MetricDimensionKey(dim, DEFAULT_DIMENSION_KEY);
}

void AddValueToBucket(const std::vector<std::pair<MetricDimensionKey, long>>& key_value_pair_list,
                      std::shared_ptr<DimToValMap> bucket) {
    for (auto itr = key_value_pair_list.begin(); itr != key_value_pair_list.end(); itr++) {
        (*bucket)[itr->first] += itr->second;
    }
}

std::shared_ptr<DimToValMap> MockBucket(
        const std::vector<std::pair<MetricDimensionKey, long>>& key_value_pair_list) {
    std::shared_ptr<DimToValMap> bucket = std::make_shared<DimToValMap>();
    AddValueToBucket(key_value_pair_list, bucket);
    return bucket;
}

// Returns the value, for the given key, in that bucket, or 0 if not present.
int64_t getBucketValue(const std::shared_ptr<DimToValMap>& bucket,
                       const MetricDimensionKey& key) {
    const auto& itr = bucket->find(key);
    if (itr != bucket->end()) {
        return itr->second;
    }
    return 0;
}

// Returns true if keys in trueList are detected as anomalies and keys in falseList are not.
bool detectAnomaliesPass(AnomalyTracker& tracker,
                         const int64_t& bucketNum,
                         const std::shared_ptr<DimToValMap>& currentBucket,
                         const std::set<const MetricDimensionKey>& trueList,
                         const std::set<const MetricDimensionKey>& falseList) {
    for (MetricDimensionKey key : trueList) {
        if (!tracker.detectAnomaly(bucketNum, key, getBucketValue(currentBucket, key))) {
            return false;
        }
    }
    for (MetricDimensionKey key : falseList) {
        if (tracker.detectAnomaly(bucketNum, key, getBucketValue(currentBucket, key))) {
            return false;
        }
    }
    return true;
}

// Calls tracker.detectAndDeclareAnomaly on each key in the bucket.
void detectAndDeclareAnomalies(AnomalyTracker& tracker,
                               const int64_t& bucketNum,
                               const std::shared_ptr<DimToValMap>& bucket,
                               const int64_t& eventTimestamp) {
    for (const auto& kv : *bucket) {
        tracker.detectAndDeclareAnomaly(eventTimestamp, bucketNum, kv.first, kv.second);
    }
}

// Asserts that the refractory time for each key in timestamps is the corresponding
// timestamp (in ns) + refractoryPeriodSec.
// If a timestamp value is negative, instead asserts that the refractory period is inapplicable
// (either non-existant or already past).
void checkRefractoryTimes(AnomalyTracker& tracker,
                          const int64_t& currTimestampNs,
                          const int32_t& refractoryPeriodSec,
                          const std::unordered_map<MetricDimensionKey, int64_t>& timestamps) {
    for (const auto& kv : timestamps) {
        if (kv.second < 0) {
            // Make sure that, if there is a refractory period, it is already past.
            EXPECT_LT(tracker.getRefractoryPeriodEndsSec(kv.first) * NS_PER_SEC,
                    (uint64_t)currTimestampNs)
                    << "Failure was at currTimestampNs " << currTimestampNs;
        } else {
            EXPECT_EQ(tracker.getRefractoryPeriodEndsSec(kv.first),
                      std::ceil(1.0 * kv.second / NS_PER_SEC) + refractoryPeriodSec)
                      << "Failure was at currTimestampNs " << currTimestampNs;
        }
    }
}

TEST(AnomalyTrackerTest, TestConsecutiveBuckets) {
    const int64_t bucketSizeNs = 30 * NS_PER_SEC;
    const int32_t refractoryPeriodSec = 2 * bucketSizeNs / NS_PER_SEC;
    Alert alert;
    alert.set_num_buckets(3);
    alert.set_refractory_period_secs(refractoryPeriodSec);
    alert.set_trigger_if_sum_gt(2);

    AnomalyTracker anomalyTracker(alert, kConfigKey);
    MetricDimensionKey keyA = getMockMetricDimensionKey(1, "a");
    MetricDimensionKey keyB = getMockMetricDimensionKey(1, "b");
    MetricDimensionKey keyC = getMockMetricDimensionKey(1, "c");

    int64_t eventTimestamp0 = 10 * NS_PER_SEC;
    int64_t eventTimestamp1 = bucketSizeNs + 11 * NS_PER_SEC;
    int64_t eventTimestamp2 = 2 * bucketSizeNs + 12 * NS_PER_SEC;
    int64_t eventTimestamp3 = 3 * bucketSizeNs + 13 * NS_PER_SEC;
    int64_t eventTimestamp4 = 4 * bucketSizeNs + 14 * NS_PER_SEC;
    int64_t eventTimestamp5 = 5 * bucketSizeNs + 5 * NS_PER_SEC;
    int64_t eventTimestamp6 = 6 * bucketSizeNs + 16 * NS_PER_SEC;

    std::shared_ptr<DimToValMap> bucket0 = MockBucket({{keyA, 1}, {keyB, 2}, {keyC, 1}});
    std::shared_ptr<DimToValMap> bucket1 = MockBucket({{keyA, 1}});
    std::shared_ptr<DimToValMap> bucket2 = MockBucket({{keyB, 1}});
    std::shared_ptr<DimToValMap> bucket3 = MockBucket({{keyA, 2}});
    std::shared_ptr<DimToValMap> bucket4 = MockBucket({{keyB, 5}});
    std::shared_ptr<DimToValMap> bucket5 = MockBucket({{keyA, 2}});
    std::shared_ptr<DimToValMap> bucket6 = MockBucket({{keyA, 2}});

    // Start time with no events.
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0u);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, -1LL);

    // Event from bucket #0 occurs.
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 0, bucket0, {}, {keyA, keyB, keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 0, bucket0, eventTimestamp1);
    checkRefractoryTimes(anomalyTracker, eventTimestamp0, refractoryPeriodSec,
            {{keyA, -1}, {keyB, -1}, {keyC, -1}});

    // Adds past bucket #0
    anomalyTracker.addPastBucket(bucket0, 0);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3u);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 0LL);

    // Event from bucket #1 occurs.
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 1, bucket1, {}, {keyA, keyB, keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 1, bucket1, eventTimestamp1);
    checkRefractoryTimes(anomalyTracker, eventTimestamp1, refractoryPeriodSec,
            {{keyA, -1}, {keyB, -1}, {keyC, -1}});

    // Adds past bucket #0 again. The sum does not change.
    anomalyTracker.addPastBucket(bucket0, 0);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3u);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 0LL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 1, bucket1, {}, {keyA, keyB, keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 1, bucket1, eventTimestamp1 + 1);
    checkRefractoryTimes(anomalyTracker, eventTimestamp1, refractoryPeriodSec,
            {{keyA, -1}, {keyB, -1}, {keyC, -1}});

    // Adds past bucket #1.
    anomalyTracker.addPastBucket(bucket1, 1);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 1L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);

    // Event from bucket #2 occurs. New anomaly on keyB.
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 2, bucket2, {keyB}, {keyA, keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 2, bucket2, eventTimestamp2);
    checkRefractoryTimes(anomalyTracker, eventTimestamp2, refractoryPeriodSec,
            {{keyA, -1}, {keyB, eventTimestamp2}, {keyC, -1}});

    // Adds past bucket #1 again. Nothing changes.
    anomalyTracker.addPastBucket(bucket1, 1);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 1L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    // Event from bucket #2 occurs (again).
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 2, bucket2, {keyB}, {keyA, keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 2, bucket2, eventTimestamp2 + 1);
    checkRefractoryTimes(anomalyTracker, eventTimestamp2, refractoryPeriodSec,
            {{keyA, -1}, {keyB, eventTimestamp2}, {keyC, -1}});

    // Adds past bucket #2.
    anomalyTracker.addPastBucket(bucket2, 2);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 2L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 1LL);

    // Event from bucket #3 occurs. New anomaly on keyA.
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 3, bucket3, {keyA}, {keyB, keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 3, bucket3, eventTimestamp3);
    checkRefractoryTimes(anomalyTracker, eventTimestamp3, refractoryPeriodSec,
            {{keyA, eventTimestamp3}, {keyB, eventTimestamp2}, {keyC, -1}});

    // Adds bucket #3.
    anomalyTracker.addPastBucket(bucket3, 3L);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 3L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 1LL);

    // Event from bucket #4 occurs. New anomaly on keyB.
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 4, bucket4, {keyB}, {keyA, keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 4, bucket4, eventTimestamp4);
    checkRefractoryTimes(anomalyTracker, eventTimestamp4, refractoryPeriodSec,
            {{keyA, eventTimestamp3}, {keyB, eventTimestamp4}, {keyC, -1}});

    // Adds bucket #4.
    anomalyTracker.addPastBucket(bucket4, 4);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 4L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 5LL);

    // Event from bucket #5 occurs. New anomaly on keyA, which is still in refractory.
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 5, bucket5, {keyA, keyB}, {keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 5, bucket5, eventTimestamp5);
    checkRefractoryTimes(anomalyTracker, eventTimestamp5, refractoryPeriodSec,
            {{keyA, eventTimestamp3}, {keyB, eventTimestamp4}, {keyC, -1}});

    // Adds bucket #5.
    anomalyTracker.addPastBucket(bucket5, 5);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 5L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 5LL);

    // Event from bucket #6 occurs. New anomaly on keyA, which is now out of refractory.
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 6, bucket6, {keyA, keyB}, {keyC}));
    detectAndDeclareAnomalies(anomalyTracker, 6, bucket6, eventTimestamp6);
    checkRefractoryTimes(anomalyTracker, eventTimestamp6, refractoryPeriodSec,
            {{keyA, eventTimestamp6}, {keyB, eventTimestamp4}, {keyC, -1}});
}

TEST(AnomalyTrackerTest, TestSparseBuckets) {
    const int64_t bucketSizeNs = 30 * NS_PER_SEC;
    const int32_t refractoryPeriodSec = 2 * bucketSizeNs / NS_PER_SEC;
    Alert alert;
    alert.set_num_buckets(3);
    alert.set_refractory_period_secs(refractoryPeriodSec);
    alert.set_trigger_if_sum_gt(2);

    AnomalyTracker anomalyTracker(alert, kConfigKey);
    MetricDimensionKey keyA = getMockMetricDimensionKey(1, "a");
    MetricDimensionKey keyB = getMockMetricDimensionKey(1, "b");
    MetricDimensionKey keyC = getMockMetricDimensionKey(1, "c");
    MetricDimensionKey keyD = getMockMetricDimensionKey(1, "d");
    MetricDimensionKey keyE = getMockMetricDimensionKey(1, "e");

    std::shared_ptr<DimToValMap> bucket9 = MockBucket({{keyA, 1}, {keyB, 2}, {keyC, 1}});
    std::shared_ptr<DimToValMap> bucket16 = MockBucket({{keyB, 4}});
    std::shared_ptr<DimToValMap> bucket18 = MockBucket({{keyB, 1}, {keyC, 1}});
    std::shared_ptr<DimToValMap> bucket20 = MockBucket({{keyB, 3}, {keyC, 1}});
    std::shared_ptr<DimToValMap> bucket25 = MockBucket({{keyD, 1}});
    std::shared_ptr<DimToValMap> bucket28 = MockBucket({{keyE, 2}});

    int64_t eventTimestamp1 = bucketSizeNs * 8 + 1;
    int64_t eventTimestamp2 = bucketSizeNs * 15 + 11;
    int64_t eventTimestamp3 = bucketSizeNs * 17 + 1;
    int64_t eventTimestamp4 = bucketSizeNs * 19 + 2;
    int64_t eventTimestamp5 = bucketSizeNs * 24 + 3;
    int64_t eventTimestamp6 = bucketSizeNs * 27 + 3;

    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, -1LL);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 9, bucket9, {}, {keyA, keyB, keyC, keyD}));
    detectAndDeclareAnomalies(anomalyTracker, 9, bucket9, eventTimestamp1);
    checkRefractoryTimes(anomalyTracker, eventTimestamp1, refractoryPeriodSec,
            {{keyA, -1}, {keyB, -1}, {keyC, -1}, {keyD, -1}, {keyE, -1}});

    // Add past bucket #9
    anomalyTracker.addPastBucket(bucket9, 9);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 9L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 3UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyA), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 2LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 16, bucket16, {keyB}, {keyA, keyC, keyD}));
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 15L);
    detectAndDeclareAnomalies(anomalyTracker, 16, bucket16, eventTimestamp2);
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 15L);
    checkRefractoryTimes(anomalyTracker, eventTimestamp2, refractoryPeriodSec,
            {{keyA, -1}, {keyB, eventTimestamp2}, {keyC, -1}, {keyD, -1}, {keyE, -1}});

    // Add past bucket #16
    anomalyTracker.addPastBucket(bucket16, 16);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 16L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 4LL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 18, bucket18, {keyB}, {keyA, keyC, keyD}));
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 4LL);
    // Within refractory period.
    detectAndDeclareAnomalies(anomalyTracker, 18, bucket18, eventTimestamp3);
    checkRefractoryTimes(anomalyTracker, eventTimestamp3, refractoryPeriodSec,
            {{keyA, -1}, {keyB, eventTimestamp2}, {keyC, -1}, {keyD, -1}, {keyE, -1}});
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 4LL);

    // Add past bucket #18
    anomalyTracker.addPastBucket(bucket18, 18);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 18L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 20, bucket20, {keyB}, {keyA, keyC, keyD}));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 19L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    detectAndDeclareAnomalies(anomalyTracker, 20, bucket20, eventTimestamp4);
    checkRefractoryTimes(anomalyTracker, eventTimestamp4, refractoryPeriodSec,
            {{keyA, -1}, {keyB, eventTimestamp4}, {keyC, -1}, {keyD, -1}, {keyE, -1}});

    // Add bucket #18 again. Nothing changes.
    anomalyTracker.addPastBucket(bucket18, 18);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 19L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 20, bucket20, {keyB}, {keyA, keyC, keyD}));
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 1LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    detectAndDeclareAnomalies(anomalyTracker, 20, bucket20, eventTimestamp4 + 1);
    // Within refractory period.
    checkRefractoryTimes(anomalyTracker, eventTimestamp4 + 1, refractoryPeriodSec,
            {{keyA, -1}, {keyB, eventTimestamp4}, {keyC, -1}, {keyD, -1}, {keyE, -1}});

    // Add past bucket #20
    anomalyTracker.addPastBucket(bucket20, 20);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 20L);
    EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 2UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyB), 3LL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyC), 1LL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 25, bucket25, {}, {keyA, keyB, keyC, keyD}));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 24L);
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    detectAndDeclareAnomalies(anomalyTracker, 25, bucket25, eventTimestamp5);
    checkRefractoryTimes(anomalyTracker, eventTimestamp5, refractoryPeriodSec,
            {{keyA, -1}, {keyB, eventTimestamp4}, {keyC, -1}, {keyD, -1}, {keyE, -1}});

    // Add past bucket #25
    anomalyTracker.addPastBucket(bucket25, 25);
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 25L);
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 1UL);
    EXPECT_EQ(anomalyTracker.getSumOverPastBuckets(keyD), 1LL);
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 28, bucket28, {},
            {keyA, keyB, keyC, keyD, keyE}));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 27L);
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    detectAndDeclareAnomalies(anomalyTracker, 28, bucket28, eventTimestamp6);
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    checkRefractoryTimes(anomalyTracker, eventTimestamp6, refractoryPeriodSec,
            {{keyA, -1}, {keyB, -1}, {keyC, -1}, {keyD, -1}, {keyE, -1}});

    // Updates current bucket #28.
    (*bucket28)[keyE] = 5;
    EXPECT_TRUE(detectAnomaliesPass(anomalyTracker, 28, bucket28, {keyE},
            {keyA, keyB, keyC, keyD}));
    EXPECT_EQ(anomalyTracker.mMostRecentBucketNum, 27L);
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    detectAndDeclareAnomalies(anomalyTracker, 28, bucket28, eventTimestamp6 + 7);
    // TODO: after detectAnomaly fix: EXPECT_EQ(anomalyTracker.mSumOverPastBuckets.size(), 0UL);
    checkRefractoryTimes(anomalyTracker, eventTimestamp6, refractoryPeriodSec,
            {{keyA, -1}, {keyB, -1}, {keyC, -1}, {keyD, -1}, {keyE, eventTimestamp6 + 7}});
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
