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

#include "src/metrics/DurationMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <set>
#include <unordered_map>
#include <vector>

#include "metrics_test_helper.h"
#include "src/condition/ConditionWizard.h"
#include "src/stats_log_util.h"
#include "stats_event.h"

using namespace android::os::statsd;
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

namespace {

void makeLogEvent(LogEvent* logEvent, int64_t timestampNs, int atomId) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
}

}  // namespace

TEST(DurationMetricTrackerTest, TestFirstBucket) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);

    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(
            kConfigKey, metric, -1 /*no condition*/, 1 /* start index */, 2 /* stop index */,
            3 /* stop_all index */, false /*nesting*/, wizard, dimensions, 5, 600 * NS_PER_SEC + NS_PER_SEC/2);

    EXPECT_EQ(600500000000, durationProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(10, durationProducer.mCurrentBucketNum);
    EXPECT_EQ(660000000005, durationProducer.getCurrentBucketEndTimeNs());
}

TEST(DurationMetricTrackerTest, TestNoCondition) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + bucketSizeNs + 2, tagId);

    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, -1 /*no condition*/,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, durationProducer.mPastBuckets.size());
    EXPECT_TRUE(durationProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                durationProducer.mPastBuckets.end());
    const auto& buckets = durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(2UL, buckets.size());
    EXPECT_EQ(bucketStartTimeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(bucketSizeNs - 1LL, buckets[0].mDuration);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[1].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets[1].mBucketEndNs);
    EXPECT_EQ(2LL, buckets[1].mDuration);
}

TEST(DurationMetricTrackerTest, TestNonSlicedCondition) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 2, tagId);
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event3, bucketStartTimeNs + bucketSizeNs + 1, tagId);
    LogEvent event4(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event4, bucketStartTimeNs + bucketSizeNs + 3, tagId);

    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, 0 /* condition index */,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);
    durationProducer.mCondition = ConditionState::kFalse;

    EXPECT_FALSE(durationProducer.mCondition);
    EXPECT_FALSE(durationProducer.isConditionSliced());

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets.size());

    durationProducer.onMatchedLogEvent(1 /* start index*/, event3);
    durationProducer.onConditionChanged(true /* condition */, bucketStartTimeNs + bucketSizeNs + 2);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event4);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, durationProducer.mPastBuckets.size());
    EXPECT_TRUE(durationProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                durationProducer.mPastBuckets.end());
    const auto& buckets2 = durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets2.size());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets2[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets2[0].mBucketEndNs);
    EXPECT_EQ(1LL, buckets2[0].mDuration);
}

TEST(DurationMetricTrackerTest, TestNonSlicedConditionUnknownState) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 2, tagId);
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event3, bucketStartTimeNs + bucketSizeNs + 1, tagId);
    LogEvent event4(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event4, bucketStartTimeNs + bucketSizeNs + 3, tagId);

    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, 0 /* condition index */,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);

    EXPECT_EQ(ConditionState::kUnknown, durationProducer.mCondition);
    EXPECT_FALSE(durationProducer.isConditionSliced());

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets.size());

    durationProducer.onMatchedLogEvent(1 /* start index*/, event3);
    durationProducer.onConditionChanged(true /* condition */, bucketStartTimeNs + bucketSizeNs + 2);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event4);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, durationProducer.mPastBuckets.size());
    const auto& buckets2 = durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets2.size());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets2[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets2[0].mBucketEndNs);
    EXPECT_EQ(1LL, buckets2[0].mDuration);
}

TEST(DurationMetricTrackerTest, TestSumDurationWithUpgrade) {
    /**
     * The duration starts from the first bucket, through the two partial buckets (10-70sec),
     * another bucket, and ends at the beginning of the next full bucket.
     * Expected buckets:
     *  - [10,25]: 14 secs
     *  - [25,70]: All 45 secs
     *  - [70,130]: All 60 secs
     *  - [130, 210]: Only 5 secs (event ended at 135sec)
     */
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t eventUpgradeTimeNs = bucketStartTimeNs + 15 * NS_PER_SEC;
    int64_t startTimeNs = bucketStartTimeNs + 1 * NS_PER_SEC;
    int64_t endTimeNs = startTimeNs + 125 * NS_PER_SEC;

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, startTimeNs, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, endTimeNs, tagId);

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, -1 /* no condition */,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets.size());
    EXPECT_EQ(bucketStartTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    durationProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    std::vector<DurationBucket> buckets =
            durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(bucketStartTimeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(eventUpgradeTimeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(eventUpgradeTimeNs - startTimeNs, buckets[0].mDuration);
    EXPECT_EQ(eventUpgradeTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    // We skip ahead one bucket, so we fill in the first two partial buckets and one full bucket.
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    buckets = durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(3UL, buckets.size());
    EXPECT_EQ(eventUpgradeTimeNs, buckets[1].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[1].mBucketEndNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs - eventUpgradeTimeNs, buckets[1].mDuration);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[2].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets[2].mBucketEndNs);
    EXPECT_EQ(bucketSizeNs, buckets[2].mDuration);
}

TEST(DurationMetricTrackerTest, TestSumDurationWithUpgradeInFollowingBucket) {
    /**
     * Expected buckets (start at 11s, upgrade at 75s, end at 135s):
     *  - [10,70]: 59 secs
     *  - [70,75]: 5 sec
     *  - [75,130]: 55 secs
     */
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t eventUpgradeTimeNs = bucketStartTimeNs + 65 * NS_PER_SEC;
    int64_t startTimeNs = bucketStartTimeNs + 1 * NS_PER_SEC;
    int64_t endTimeNs = startTimeNs + 125 * NS_PER_SEC;

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, startTimeNs, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, endTimeNs, tagId);

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, -1 /* no condition */,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets.size());
    EXPECT_EQ(bucketStartTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    durationProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(2UL, durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    std::vector<DurationBucket> buckets =
            durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(bucketStartTimeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs - startTimeNs, buckets[0].mDuration);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[1].mBucketStartNs);
    EXPECT_EQ(eventUpgradeTimeNs, buckets[1].mBucketEndNs);
    EXPECT_EQ(eventUpgradeTimeNs - (bucketStartTimeNs + bucketSizeNs), buckets[1].mDuration);
    EXPECT_EQ(eventUpgradeTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    // We skip ahead one bucket, so we fill in the first two partial buckets and one full bucket.
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    buckets = durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(3UL, buckets.size());
    EXPECT_EQ(eventUpgradeTimeNs, buckets[2].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets[2].mBucketEndNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs - eventUpgradeTimeNs, buckets[2].mDuration);
}

TEST(DurationMetricTrackerTest, TestSumDurationAnomalyWithUpgrade) {
    sp<AlarmMonitor> alarmMonitor;
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t eventUpgradeTimeNs = bucketStartTimeNs + 15 * NS_PER_SEC;
    int64_t startTimeNs = bucketStartTimeNs + 1;
    int64_t endTimeNs = startTimeNs + 65 * NS_PER_SEC;

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, startTimeNs, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, endTimeNs, tagId);

    // Setup metric with alert.
    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);
    Alert alert;
    alert.set_num_buckets(3);
    alert.set_trigger_if_sum_gt(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, -1 /* no condition */,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);

    sp<AnomalyTracker> anomalyTracker = durationProducer.addAnomalyTracker(alert, alarmMonitor);
    EXPECT_TRUE(anomalyTracker != nullptr);

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    durationProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);

    // We skip ahead one bucket, so we fill in the first two partial buckets and one full bucket.
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs - startTimeNs,
              anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));
}

TEST(DurationMetricTrackerTest, TestMaxDurationWithUpgrade) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t eventUpgradeTimeNs = bucketStartTimeNs + 15 * NS_PER_SEC;
    int64_t startTimeNs = bucketStartTimeNs + 1;
    int64_t endTimeNs = startTimeNs + 125 * NS_PER_SEC;

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, startTimeNs, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, endTimeNs, tagId);

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_MAX_SPARSE);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, -1 /* no condition */,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets.size());
    EXPECT_EQ(bucketStartTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    durationProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(eventUpgradeTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    // We skip ahead one bucket, so we fill in the first two partial buckets and one full bucket.
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());

    durationProducer.flushIfNeededLocked(bucketStartTimeNs + 3 * bucketSizeNs + 1);
    std::vector<DurationBucket> buckets =
            durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 3 * bucketSizeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(endTimeNs - startTimeNs, buckets[0].mDuration);
}

TEST(DurationMetricTrackerTest, TestMaxDurationWithUpgradeInNextBucket) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t eventUpgradeTimeNs = bucketStartTimeNs + 65 * NS_PER_SEC;
    int64_t startTimeNs = bucketStartTimeNs + 1;
    int64_t endTimeNs = startTimeNs + 115 * NS_PER_SEC;

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, startTimeNs, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, endTimeNs, tagId);

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_MAX_SPARSE);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(kConfigKey, metric, -1 /* no condition */,
                                            1 /* start index */, 2 /* stop index */,
                                            3 /* stop_all index */, false /*nesting*/, wizard,
                                            dimensions, bucketStartTimeNs, bucketStartTimeNs);

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets.size());
    EXPECT_EQ(bucketStartTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    durationProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(eventUpgradeTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    // Stop occurs in the same partial bucket as created for the app upgrade.
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(eventUpgradeTimeNs, durationProducer.mCurrentBucketStartTimeNs);

    durationProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    std::vector<DurationBucket> buckets =
            durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    EXPECT_EQ(eventUpgradeTimeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(endTimeNs - startTimeNs, buckets[0].mDuration);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
