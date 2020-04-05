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

#include "src/metrics/duration_helper/OringDurationTracker.h"
#include "src/condition/ConditionWizard.h"
#include "metrics_test_helper.h"
#include "tests/statsd_test_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <math.h>
#include <stdio.h>
#include <set>
#include <unordered_map>
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
const int TagId = 1;
const int64_t metricId = 123;
const HashableDimensionKey eventKey = getMockedDimensionKey(TagId, 0, "event");

const HashableDimensionKey kConditionKey1 = getMockedDimensionKey(TagId, 1, "maps");
const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
const int64_t bucketSizeNs = 30 * NS_PER_SEC;

TEST(OringDurationTrackerTest, TestDurationOverlap) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;

    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, false,
                                 bucketStartTimeNs, bucketNum, bucketStartTimeNs, bucketSizeNs,
                                 false, false, {});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, ConditionKey());
    EXPECT_EQ((long long)eventStartTimeNs, tracker.mLastStartTime);
    tracker.noteStart(kEventKey1, true, eventStartTimeNs + 10, ConditionKey());  // overlapping wl
    EXPECT_EQ((long long)eventStartTimeNs, tracker.mLastStartTime);

    tracker.noteStop(kEventKey1, eventStartTimeNs + durationTimeNs, false);
    tracker.flushIfNeeded(eventStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());

    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(durationTimeNs, buckets[eventKey][0].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationNested) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;

    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;

    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, true, bucketStartTimeNs,
                                 bucketNum, bucketStartTimeNs, bucketSizeNs, false, false, {});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, ConditionKey());
    tracker.noteStart(kEventKey1, true, eventStartTimeNs + 10, ConditionKey());  // overlapping wl

    tracker.noteStop(kEventKey1, eventStartTimeNs + 2000, false);
    tracker.noteStop(kEventKey1, eventStartTimeNs + 2003, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(2003LL, buckets[eventKey][0].mDuration);
}

TEST(OringDurationTrackerTest, TestStopAll) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const std::vector<HashableDimensionKey> kConditionKey1 =
        {getMockedDimensionKey(TagId, 1, "maps")};
    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;

    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;

    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, true, bucketStartTimeNs,
                                 bucketNum, bucketStartTimeNs, bucketSizeNs, false, false, {});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, ConditionKey());
    tracker.noteStart(kEventKey2, true, eventStartTimeNs + 10, ConditionKey());  // overlapping wl

    tracker.noteStopAll(eventStartTimeNs + 2003);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(2003LL, buckets[eventKey][0].mDuration);
}

TEST(OringDurationTrackerTest, TestCrossBucketBoundary) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;

    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, true, bucketStartTimeNs,
                                 bucketNum, bucketStartTimeNs, bucketSizeNs, false, false, {});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, ConditionKey());
    EXPECT_EQ((long long)eventStartTimeNs, tracker.mLastStartTime);
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs, &buckets);
    tracker.noteStart(kEventKey1, true, eventStartTimeNs + 2 * bucketSizeNs, ConditionKey());
    EXPECT_EQ((long long)(bucketStartTimeNs + 2 * bucketSizeNs), tracker.mLastStartTime);

    EXPECT_EQ(2u, buckets[eventKey].size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[eventKey][0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[eventKey][1].mDuration);

    tracker.noteStop(kEventKey1, eventStartTimeNs + 2 * bucketSizeNs + 10, false);
    tracker.noteStop(kEventKey1, eventStartTimeNs + 2 * bucketSizeNs + 12, false);
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs + 12, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(2u, buckets[eventKey].size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[eventKey][0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[eventKey][1].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationConditionChange) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1[StringToId("APP_BACKGROUND")] = kConditionKey1;

    EXPECT_CALL(*wizard, query(_, key1, _))  // #4
            .WillOnce(Return(ConditionState::kFalse));

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;

    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, false,
                                 bucketStartTimeNs, bucketNum, bucketStartTimeNs, bucketSizeNs,
                                 true, false, {});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, key1);

    tracker.onSlicedConditionMayChange(true, eventStartTimeNs + 5);

    tracker.noteStop(kEventKey1, eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(5LL, buckets[eventKey][0].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationConditionChange2) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1[StringToId("APP_BACKGROUND")] = kConditionKey1;

    EXPECT_CALL(*wizard, query(_, key1, _))
            .Times(2)
            .WillOnce(Return(ConditionState::kFalse))
            .WillOnce(Return(ConditionState::kTrue));

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;
    int64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, false,
                                 bucketStartTimeNs, bucketNum, bucketStartTimeNs, bucketSizeNs,
                                 true, false, {});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, key1);
    // condition to false; record duration 5n
    tracker.onSlicedConditionMayChange(true, eventStartTimeNs + 5);
    // condition to true.
    tracker.onSlicedConditionMayChange(true, eventStartTimeNs + 1000);
    // 2nd duration: 1000ns
    tracker.noteStop(kEventKey1, eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(1005LL, buckets[eventKey][0].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationConditionChangeNested) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1[StringToId("APP_BACKGROUND")] = kConditionKey1;

    EXPECT_CALL(*wizard, query(_, key1, _))  // #4
            .WillOnce(Return(ConditionState::kFalse));

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + 1;

    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, true, bucketStartTimeNs,
                                 bucketNum, bucketStartTimeNs, bucketSizeNs, true, false, {});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, key1);
    tracker.noteStart(kEventKey1, true, eventStartTimeNs + 2, key1);

    tracker.noteStop(kEventKey1, eventStartTimeNs + 3, false);

    tracker.onSlicedConditionMayChange(true, eventStartTimeNs + 15);

    tracker.noteStop(kEventKey1, eventStartTimeNs + 2003, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(15LL, buckets[eventKey][0].mDuration);
}

TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(1);
    alert.set_trigger_if_sum_gt(40 * NS_PER_SEC);
    alert.set_num_buckets(2);
    alert.set_refractory_period_secs(1);

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + NS_PER_SEC + 1;

    sp<AlarmMonitor> alarmMonitor;
    sp<DurationAnomalyTracker> anomalyTracker =
        new DurationAnomalyTracker(alert, kConfigKey, alarmMonitor);
    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, true, bucketStartTimeNs,
                                 bucketNum, bucketStartTimeNs, bucketSizeNs, true, false,
                                 {anomalyTracker});

    // Nothing in the past bucket.
    tracker.noteStart(DEFAULT_DIMENSION_KEY, true, eventStartTimeNs, ConditionKey());
    EXPECT_EQ((long long)(alert.trigger_if_sum_gt() + eventStartTimeNs),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, eventStartTimeNs));

    tracker.noteStop(DEFAULT_DIMENSION_KEY, eventStartTimeNs + 3, false);
    EXPECT_EQ(0u, buckets[eventKey].size());

    int64_t event1StartTimeNs = eventStartTimeNs + 10;
    tracker.noteStart(kEventKey1, true, event1StartTimeNs, ConditionKey());
    // No past buckets. The anomaly will happen in bucket #0.
    EXPECT_EQ((long long)(event1StartTimeNs + alert.trigger_if_sum_gt() - 3),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, event1StartTimeNs));

    int64_t event1StopTimeNs = eventStartTimeNs + bucketSizeNs + 10;
    tracker.flushIfNeeded(event1StopTimeNs, &buckets);
    tracker.noteStop(kEventKey1, event1StopTimeNs, false);

    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(3LL + bucketStartTimeNs + bucketSizeNs - eventStartTimeNs - 10,
              buckets[eventKey][0].mDuration);

    const int64_t bucket0Duration = 3ULL + bucketStartTimeNs + bucketSizeNs - eventStartTimeNs - 10;
    const int64_t bucket1Duration = eventStartTimeNs + 10 - bucketStartTimeNs;

    // One past buckets. The anomaly will happen in bucket #1.
    int64_t event2StartTimeNs = eventStartTimeNs + bucketSizeNs + 15;
    tracker.noteStart(kEventKey1, true, event2StartTimeNs, ConditionKey());
    EXPECT_EQ((long long)(event2StartTimeNs + alert.trigger_if_sum_gt() - bucket0Duration -
                          bucket1Duration),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, event2StartTimeNs));
    tracker.noteStop(kEventKey1, event2StartTimeNs + 1, false);

    // Only one past buckets is applicable. Bucket +0 should be trashed. The anomaly will happen in
    // bucket #2.
    int64_t event3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs - 9 * NS_PER_SEC;
    tracker.noteStart(kEventKey1, true, event3StartTimeNs, ConditionKey());
    EXPECT_EQ((long long)(event3StartTimeNs + alert.trigger_if_sum_gt() - bucket1Duration - 1LL),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, event3StartTimeNs));
}

TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp2) {
    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(1);
    alert.set_trigger_if_sum_gt(5 * NS_PER_SEC);
    alert.set_num_buckets(1);
    alert.set_refractory_period_secs(20);

    int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    int64_t bucketNum = 0;

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<AlarmMonitor> alarmMonitor;
    sp<DurationAnomalyTracker> anomalyTracker =
        new DurationAnomalyTracker(alert, kConfigKey, alarmMonitor);
    OringDurationTracker tracker(kConfigKey, metricId, DEFAULT_METRIC_DIMENSION_KEY, wizard, 1,

                                 true, bucketStartTimeNs, bucketNum, bucketStartTimeNs,
                                 bucketSizeNs, true, false, {anomalyTracker});

    int64_t eventStartTimeNs = bucketStartTimeNs + 9 * NS_PER_SEC;
    tracker.noteStart(DEFAULT_DIMENSION_KEY, true, eventStartTimeNs, ConditionKey());
    // Anomaly happens in the bucket #1.
    EXPECT_EQ((long long)(bucketStartTimeNs + 14 * NS_PER_SEC),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, eventStartTimeNs));

    tracker.noteStop(DEFAULT_DIMENSION_KEY, bucketStartTimeNs + 14 * NS_PER_SEC, false);

    EXPECT_EQ((long long)(bucketStartTimeNs + 34 * NS_PER_SEC) / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY));

    int64_t event2StartTimeNs = bucketStartTimeNs + 22 * NS_PER_SEC;
    EXPECT_EQ((long long)(bucketStartTimeNs + 34 * NS_PER_SEC) / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY));
    EXPECT_EQ((long long)(bucketStartTimeNs + 35 * NS_PER_SEC),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, event2StartTimeNs));
}

TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp3) {
    // Test the cases where the refractory period is smaller than the bucket size, longer than
    // the bucket size, and longer than 2x of the anomaly detection window.
    for (int j = 0; j < 3; j++) {
        int64_t thresholdNs = j * bucketSizeNs + 5 * NS_PER_SEC;
        for (int i = 0; i <= 7; ++i) {

            Alert alert;
            alert.set_id(101);
            alert.set_metric_id(1);
            alert.set_trigger_if_sum_gt(thresholdNs);
            alert.set_num_buckets(3);
            alert.set_refractory_period_secs(
                bucketSizeNs / NS_PER_SEC / 2 + i * bucketSizeNs / NS_PER_SEC);

            int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
            int64_t bucketNum = 101;

            sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
            sp<AlarmMonitor> alarmMonitor;
            sp<DurationAnomalyTracker> anomalyTracker =
                new DurationAnomalyTracker(alert, kConfigKey, alarmMonitor);
            OringDurationTracker tracker(kConfigKey, metricId, DEFAULT_METRIC_DIMENSION_KEY, wizard,
                                         1, true, bucketStartTimeNs, bucketNum, bucketStartTimeNs,
                                         bucketSizeNs, true, false, {anomalyTracker});

            int64_t eventStartTimeNs = bucketStartTimeNs + 9 * NS_PER_SEC;
            tracker.noteStart(DEFAULT_DIMENSION_KEY, true, eventStartTimeNs, ConditionKey());
            EXPECT_EQ((long long)(eventStartTimeNs + thresholdNs),
                      tracker.predictAnomalyTimestampNs(*anomalyTracker, eventStartTimeNs));
            int64_t eventStopTimeNs = eventStartTimeNs + thresholdNs + NS_PER_SEC;
            tracker.noteStop(DEFAULT_DIMENSION_KEY, eventStopTimeNs, false);

            int64_t refractoryPeriodEndSec =
                anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY);
            EXPECT_EQ(eventStopTimeNs / (int64_t)NS_PER_SEC + alert.refractory_period_secs(),
                       refractoryPeriodEndSec);

            // Acquire and release a wakelock in the next bucket.
            int64_t event2StartTimeNs = eventStopTimeNs + bucketSizeNs;
            tracker.noteStart(DEFAULT_DIMENSION_KEY, true, event2StartTimeNs, ConditionKey());
            int64_t event2StopTimeNs = event2StartTimeNs + 4 * NS_PER_SEC;
            tracker.noteStop(DEFAULT_DIMENSION_KEY, event2StopTimeNs, false);

            // Test the alarm prediction works well when seeing another wakelock start event.
            for (int k = 0; k <= 2; ++k) {
                int64_t event3StartTimeNs = event2StopTimeNs + NS_PER_SEC + k * bucketSizeNs;
                int64_t alarmTimestampNs =
                    tracker.predictAnomalyTimestampNs(*anomalyTracker, event3StartTimeNs);
                EXPECT_GT(alarmTimestampNs, 0u);
                EXPECT_GE(alarmTimestampNs, event3StartTimeNs);
                EXPECT_GE(alarmTimestampNs, refractoryPeriodEndSec *(int64_t) NS_PER_SEC);
            }
        }
    }
}

TEST(OringDurationTrackerTest, TestAnomalyDetectionExpiredAlarm) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(1);
    alert.set_trigger_if_sum_gt(40 * NS_PER_SEC);
    alert.set_num_buckets(2);
    const int32_t refPeriodSec = 45;
    alert.set_refractory_period_secs(refPeriodSec);

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    int64_t bucketNum = 0;
    int64_t eventStartTimeNs = bucketStartTimeNs + NS_PER_SEC + 1;

    sp<AlarmMonitor> alarmMonitor;
    sp<DurationAnomalyTracker> anomalyTracker =
        new DurationAnomalyTracker(alert, kConfigKey, alarmMonitor);
    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, true /*nesting*/,
                                 bucketStartTimeNs, bucketNum, bucketStartTimeNs, bucketSizeNs,
                                 false, false, {anomalyTracker});

    tracker.noteStart(kEventKey1, true, eventStartTimeNs, ConditionKey());
    tracker.noteStop(kEventKey1, eventStartTimeNs + 10, false);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 0U);
    EXPECT_TRUE(tracker.mStarted.empty());
    EXPECT_EQ(10LL, tracker.mStateKeyDurationMap[DEFAULT_DIMENSION_KEY].mDuration);  // 10ns

    EXPECT_EQ(0u, tracker.mStarted.size());

    tracker.noteStart(kEventKey1, true, eventStartTimeNs + 20, ConditionKey());
    EXPECT_EQ(1u, anomalyTracker->mAlarms.size());
    EXPECT_EQ((long long)(52ULL * NS_PER_SEC),  // (10s + 1s + 1ns + 20ns) - 10ns + 40s, rounded up
              (long long)(anomalyTracker->mAlarms.begin()->second->timestampSec * NS_PER_SEC));
    // The alarm is set to fire at 52s, and when it does, an anomaly would be declared. However,
    // because this is a unit test, the alarm won't actually fire at all. Since the alarm fails
    // to fire in time, the anomaly is instead caught when noteStop is called, at around 71s.
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs + 25, &buckets);
    tracker.noteStop(kEventKey1, eventStartTimeNs + 2 * bucketSizeNs + 25, false);
    EXPECT_EQ(anomalyTracker->getSumOverPastBuckets(eventKey), (long long)(bucketSizeNs));
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey),
              std::ceil((eventStartTimeNs + 2 * bucketSizeNs + 25.0) / NS_PER_SEC + refPeriodSec));
}

TEST(OringDurationTrackerTest, TestAnomalyDetectionFiredAlarm) {
    const MetricDimensionKey eventKey = getMockedMetricDimensionKey(TagId, 0, "event");

    const HashableDimensionKey kEventKey1 = getMockedDimensionKey(TagId, 2, "maps");
    const HashableDimensionKey kEventKey2 = getMockedDimensionKey(TagId, 3, "maps");
    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(1);
    alert.set_trigger_if_sum_gt(40 * NS_PER_SEC);
    alert.set_num_buckets(2);
    const int32_t refPeriodSec = 45;
    alert.set_refractory_period_secs(refPeriodSec);

    unordered_map<MetricDimensionKey, vector<DurationBucket>> buckets;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    ConditionKey conkey;
    conkey[StringToId("APP_BACKGROUND")] = kConditionKey1;
    int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    int64_t bucketSizeNs = 30 * NS_PER_SEC;

    sp<AlarmMonitor> alarmMonitor;
    sp<DurationAnomalyTracker> anomalyTracker =
        new DurationAnomalyTracker(alert, kConfigKey, alarmMonitor);
    OringDurationTracker tracker(kConfigKey, metricId, eventKey, wizard, 1, true /*nesting*/,
                                 bucketStartTimeNs, 0, bucketStartTimeNs, bucketSizeNs, false,
                                 false, {anomalyTracker});

    tracker.noteStart(kEventKey1, true, 15 * NS_PER_SEC, conkey);  // start key1
    EXPECT_EQ(1u, anomalyTracker->mAlarms.size());
    sp<const InternalAlarm> alarm = anomalyTracker->mAlarms.begin()->second;
    EXPECT_EQ((long long)(55ULL * NS_PER_SEC), (long long)(alarm->timestampSec * NS_PER_SEC));
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 0U);

    tracker.noteStop(kEventKey1, 17 * NS_PER_SEC, false); // stop key1 (2 seconds later)
    EXPECT_EQ(0u, anomalyTracker->mAlarms.size());
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 0U);

    tracker.noteStart(kEventKey1, true, 22 * NS_PER_SEC, conkey);  // start key1 again
    EXPECT_EQ(1u, anomalyTracker->mAlarms.size());
    alarm = anomalyTracker->mAlarms.begin()->second;
    EXPECT_EQ((long long)(60ULL * NS_PER_SEC), (long long)(alarm->timestampSec * NS_PER_SEC));
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 0U);

    tracker.noteStart(kEventKey2, true, 32 * NS_PER_SEC, conkey);  // start key2
    EXPECT_EQ(1u, anomalyTracker->mAlarms.size());
    alarm = anomalyTracker->mAlarms.begin()->second;
    EXPECT_EQ((long long)(60ULL * NS_PER_SEC), (long long)(alarm->timestampSec * NS_PER_SEC));
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 0U);

    tracker.noteStop(kEventKey1, 47 * NS_PER_SEC, false); // stop key1
    EXPECT_EQ(1u, anomalyTracker->mAlarms.size());
    alarm = anomalyTracker->mAlarms.begin()->second;
    EXPECT_EQ((long long)(60ULL * NS_PER_SEC), (long long)(alarm->timestampSec * NS_PER_SEC));
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 0U);

    // Now, at 60s, which is 38s after key1 started again, we have reached 40s of 'on' time.
    std::unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> firedAlarms({alarm});
    anomalyTracker->informAlarmsFired(62 * NS_PER_SEC, firedAlarms);
    EXPECT_EQ(0u, anomalyTracker->mAlarms.size());
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 62U + refPeriodSec);

    tracker.noteStop(kEventKey2, 69 * NS_PER_SEC, false); // stop key2
    EXPECT_EQ(0u, anomalyTracker->mAlarms.size());
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(eventKey), 62U + refPeriodSec);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
