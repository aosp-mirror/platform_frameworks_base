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

#include "src/metrics/CountMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <math.h>
#include <stdio.h>

#include <vector>

#include "metrics_test_helper.h"
#include "src/stats_log_util.h"
#include "stats_event.h"
#include "tests/statsd_test_util.h"

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

void makeLogEvent(LogEvent* logEvent, int64_t timestampNs, int atomId, string uid) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeString(statsEvent, uid.c_str());
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    logEvent->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);
}

}  // namespace

TEST(CountMetricProducerTest, TestFirstBucket) {
    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    CountMetricProducer countProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard, 5,
                                      600 * NS_PER_SEC + NS_PER_SEC / 2);
    EXPECT_EQ(600500000000, countProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(10, countProducer.mCurrentBucketNum);
    EXPECT_EQ(660000000005, countProducer.getCurrentBucketEndTimeNs());
}

TEST(CountMetricProducerTest, TestNonDimensionalEvents) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
    int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
    int tagId = 1;

    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    CountMetricProducer countProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs, bucketStartTimeNs);

    // 2 events in bucket 1.
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 2, tagId);

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // Flushes at event #2.
    countProducer.flushIfNeededLocked(bucketStartTimeNs + 2);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    // Flushes.
    countProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    const auto& buckets = countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    EXPECT_EQ(bucketStartTimeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(2LL, buckets[0].mCount);

    // 1 matched event happens in bucket 2.
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event3, bucketStartTimeNs + bucketSizeNs + 2, tagId);

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);

    countProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    EXPECT_EQ(2UL, countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    const auto& bucketInfo2 = countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][1];
    EXPECT_EQ(bucket2StartTimeNs, bucketInfo2.mBucketStartNs);
    EXPECT_EQ(bucket2StartTimeNs + bucketSizeNs, bucketInfo2.mBucketEndNs);
    EXPECT_EQ(1LL, bucketInfo2.mCount);

    // nothing happens in bucket 3. we should not record anything for bucket 3.
    countProducer.flushIfNeededLocked(bucketStartTimeNs + 3 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    const auto& buckets3 = countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(2UL, buckets3.size());
}

TEST(CountMetricProducerTest, TestEventsWithNonSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;

    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_condition(StringToId("SCREEN_ON"));

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    CountMetricProducer countProducer(kConfigKey, metric, 1, wizard, bucketStartTimeNs,
                                      bucketStartTimeNs);

    countProducer.onConditionChanged(true, bucketStartTimeNs);

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, /*atomId=*/1);
    countProducer.onMatchedLogEvent(1 /*matcher index*/, event1);

    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.onConditionChanged(false /*new condition*/, bucketStartTimeNs + 2);

    // Upon this match event, the matched event1 is flushed.
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 10, /*atomId=*/1);
    countProducer.onMatchedLogEvent(1 /*matcher index*/, event2);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());

    const auto& buckets = countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    const auto& bucketInfo = buckets[0];
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.mBucketEndNs);
    EXPECT_EQ(1LL, bucketInfo.mCount);
}

TEST(CountMetricProducerTest, TestEventsWithSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;

    int tagId = 1;
    int conditionTagId = 2;

    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_condition(StringToId("APP_IN_BACKGROUND_PER_UID_AND_SCREEN_ON"));
    MetricConditionLink* link = metric.add_links();
    link->set_condition(StringToId("APP_IN_BACKGROUND_PER_UID"));
    buildSimpleAtomFieldMatcher(tagId, 1, link->mutable_fields_in_what());
    buildSimpleAtomFieldMatcher(conditionTagId, 2, link->mutable_fields_in_condition());

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId, /*uid=*/"111");

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 10, tagId, /*uid=*/"222");

    ConditionKey key1;
    key1[StringToId("APP_IN_BACKGROUND_PER_UID")] = {
            getMockedDimensionKey(conditionTagId, 2, "111")};

    ConditionKey key2;
    key2[StringToId("APP_IN_BACKGROUND_PER_UID")] = {
            getMockedDimensionKey(conditionTagId, 2, "222")};

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    EXPECT_CALL(*wizard, query(_, key1, _)).WillOnce(Return(ConditionState::kFalse));

    EXPECT_CALL(*wizard, query(_, key2, _)).WillOnce(Return(ConditionState::kTrue));

    CountMetricProducer countProducer(kConfigKey, metric, 1 /*condition tracker index*/, wizard,
                                      bucketStartTimeNs, bucketStartTimeNs);

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    countProducer.flushIfNeededLocked(bucketStartTimeNs + 1);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);
    countProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    const auto& buckets = countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    const auto& bucketInfo = buckets[0];
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.mBucketEndNs);
    EXPECT_EQ(1LL, bucketInfo.mCount);
}

TEST(CountMetricProducerTest, TestEventWithAppUpgrade) {
    sp<AlarmMonitor> alarmMonitor;
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t eventUpgradeTimeNs = bucketStartTimeNs + 15 * NS_PER_SEC;

    int tagId = 1;
    int conditionTagId = 2;

    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    Alert alert;
    alert.set_num_buckets(3);
    alert.set_trigger_if_sum_gt(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    CountMetricProducer countProducer(kConfigKey, metric, -1 /* no condition */, wizard,
                                      bucketStartTimeNs, bucketStartTimeNs);

    sp<AnomalyTracker> anomalyTracker = countProducer.addAnomalyTracker(alert, alarmMonitor);
    EXPECT_TRUE(anomalyTracker != nullptr);

    // Bucket is flushed yet.
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId, /*uid=*/"111");
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());
    EXPECT_EQ(0, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // App upgrade forces bucket flush.
    // Check that there's a past bucket and the bucket end is not adjusted.
    countProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ((long long)bucketStartTimeNs,
              countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketStartNs);
    EXPECT_EQ((long long)eventUpgradeTimeNs,
              countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketEndNs);
    EXPECT_EQ(eventUpgradeTimeNs, countProducer.mCurrentBucketStartTimeNs);
    // Anomaly tracker only contains full buckets.
    EXPECT_EQ(0, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    int64_t lastEndTimeNs = countProducer.getCurrentBucketEndTimeNs();
    // Next event occurs in same bucket as partial bucket created.
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 59 * NS_PER_SEC + 10, tagId, /*uid=*/"222");
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);
    EXPECT_EQ(1UL, countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(eventUpgradeTimeNs, countProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(0, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // Third event in following bucket.
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event3, bucketStartTimeNs + 62 * NS_PER_SEC + 10, tagId, /*uid=*/"333");
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    EXPECT_EQ(2UL, countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(lastEndTimeNs, countProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(2, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));
}

TEST(CountMetricProducerTest, TestEventWithAppUpgradeInNextBucket) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t eventUpgradeTimeNs = bucketStartTimeNs + 65 * NS_PER_SEC;

    int tagId = 1;
    int conditionTagId = 2;

    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    CountMetricProducer countProducer(kConfigKey, metric, -1 /* no condition */, wizard,
                                      bucketStartTimeNs, bucketStartTimeNs);

    // Bucket is flushed yet.
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId, /*uid=*/"111");
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    // App upgrade forces bucket flush.
    // Check that there's a past bucket and the bucket end is not adjusted.
    countProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ((int64_t)bucketStartTimeNs,
              countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs,
              countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketEndNs);
    EXPECT_EQ(eventUpgradeTimeNs, countProducer.mCurrentBucketStartTimeNs);

    // Next event occurs in same bucket as partial bucket created.
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 70 * NS_PER_SEC + 10, tagId, /*uid=*/"222");
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);
    EXPECT_EQ(1UL, countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());

    // Third event in following bucket.
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event3, bucketStartTimeNs + 121 * NS_PER_SEC + 10, tagId, /*uid=*/"333");
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    EXPECT_EQ(2UL, countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ((int64_t)eventUpgradeTimeNs,
              countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][1].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              countProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][1].mBucketEndNs);
}

TEST(CountMetricProducerTest, TestAnomalyDetectionUnSliced) {
    sp<AlarmMonitor> alarmMonitor;
    Alert alert;
    alert.set_id(11);
    alert.set_metric_id(1);
    alert.set_trigger_if_sum_gt(2);
    alert.set_num_buckets(2);
    const int32_t refPeriodSec = 1;
    alert.set_refractory_period_secs(refPeriodSec);

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
    int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
    int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;

    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    CountMetricProducer countProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs, bucketStartTimeNs);

    sp<AnomalyTracker> anomalyTracker = countProducer.addAnomalyTracker(alert, alarmMonitor);

    int tagId = 1;
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event1, bucketStartTimeNs + 1, tagId);
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event2, bucketStartTimeNs + 2, tagId);
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event3, bucketStartTimeNs + 2 * bucketSizeNs + 1, tagId);
    LogEvent event4(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event4, bucketStartTimeNs + 3 * bucketSizeNs + 1, tagId);
    LogEvent event5(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event5, bucketStartTimeNs + 3 * bucketSizeNs + 2, tagId);
    LogEvent event6(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event6, bucketStartTimeNs + 3 * bucketSizeNs + 3, tagId);
    LogEvent event7(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event7, bucketStartTimeNs + 3 * bucketSizeNs + 2 * NS_PER_SEC, tagId);

    // Two events in bucket #0.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(2L, countProducer.mCurrentSlicedCounter->begin()->second);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    // One event in bucket #2. No alarm as bucket #0 is trashed out.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(1L, countProducer.mCurrentSlicedCounter->begin()->second);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    // Two events in bucket #3.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event4);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event5);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event6);
    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(3L, countProducer.mCurrentSlicedCounter->begin()->second);
    // Anomaly at event 6 is within refractory period. The alarm is at event 5 timestamp not event 6
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event5.GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event7);
    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(4L, countProducer.mCurrentSlicedCounter->begin()->second);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event7.GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
}

TEST(CountMetricProducerTest, TestOneWeekTimeUnit) {
    CountMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_WEEK);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    int64_t oneDayNs = 24 * 60 * 60 * 1e9;
    int64_t fiveWeeksNs = 5 * 7 * oneDayNs;

    CountMetricProducer countProducer(
            kConfigKey, metric, -1 /* meaning no condition */, wizard, oneDayNs, fiveWeeksNs);

    int64_t fiveWeeksOneDayNs = fiveWeeksNs + oneDayNs;

    EXPECT_EQ(fiveWeeksNs, countProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(4, countProducer.mCurrentBucketNum);
    EXPECT_EQ(fiveWeeksOneDayNs, countProducer.getCurrentBucketEndTimeNs());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
