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

#include "src/metrics/GaugeMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <math.h>
#include <stdio.h>

#include <vector>

#include "logd/LogEvent.h"
#include "metrics_test_helper.h"
#include "src/matchers/SimpleLogMatchingTracker.h"
#include "src/metrics/MetricProducer.h"
#include "src/stats_log_util.h"
#include "stats_event.h"
#include "tests/statsd_test_util.h"

using namespace testing;
using android::sp;
using std::set;
using std::unordered_map;
using std::vector;
using std::make_shared;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

namespace {

const ConfigKey kConfigKey(0, 12345);
const int tagId = 1;
const int64_t metricId = 123;
const int64_t atomMatcherId = 678;
const int logEventMatcherIndex = 0;
const int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
const int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
const int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
const int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
const int64_t bucket4StartTimeNs = bucketStartTimeNs + 3 * bucketSizeNs;
const int64_t partialBucketSplitTimeNs = bucketStartTimeNs + 15 * NS_PER_SEC;

shared_ptr<LogEvent> makeLogEvent(int32_t atomId, int64_t timestampNs, int32_t value1, string str1,
                                  int32_t value2) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    AStatsEvent_writeInt32(statsEvent, value1);
    AStatsEvent_writeString(statsEvent, str1.c_str());
    AStatsEvent_writeInt32(statsEvent, value2);

    shared_ptr<LogEvent> logEvent = std::make_shared<LogEvent>(/*uid=*/0, /*pid=*/0);
    parseStatsEventToLogEvent(statsEvent, logEvent.get());
    return logEvent;
}
}  // anonymous namespace

// Setup for parameterized tests.
class GaugeMetricProducerTest_PartialBucket : public TestWithParam<BucketSplitEvent> {};

INSTANTIATE_TEST_SUITE_P(GaugeMetricProducerTest_PartialBucket,
                         GaugeMetricProducerTest_PartialBucket,
                         testing::Values(APP_UPGRADE, BOOT_COMPLETE));

/*
 * Tests that the first bucket works correctly
 */
TEST(GaugeMetricProducerTest, TestFirstBucket) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_gauge_fields_filter()->set_include_all(false);
    auto gaugeFieldMatcher = metric.mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(tagId);
    gaugeFieldMatcher->add_child()->set_field(1);
    gaugeFieldMatcher->add_child()->set_field(3);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    // statsd started long ago.
    // The metric starts in the middle of the bucket
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, -1, -1,
                                      tagId, 5, 600 * NS_PER_SEC + NS_PER_SEC / 2, pullerManager);
    gaugeProducer.prepareFirstBucket();

    EXPECT_EQ(600500000000, gaugeProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(10, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(660000000005, gaugeProducer.getCurrentBucketEndTimeNs());
}

TEST(GaugeMetricProducerTest, TestPulledEventsNoCondition) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_gauge_fields_filter()->set_include_all(false);
    metric.set_max_pull_delay_sec(INT_MAX);
    auto gaugeFieldMatcher = metric.mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(tagId);
    gaugeFieldMatcher->add_child()->set_field(1);
    gaugeFieldMatcher->add_child()->set_field(3);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(makeLogEvent(tagId, eventTimeNs + 10, 3, "some value", 11));
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, tagId, -1,
                                      tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager);
    gaugeProducer.prepareFirstBucket();

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(makeLogEvent(tagId, bucket2StartTimeNs + 1, 10, "some value", 11));

    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    auto it = gaugeProducer.mCurrentSlicedBucket->begin()->second.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(10, it->mValue.int_value);
    it++;
    EXPECT_EQ(11, it->mValue.int_value);
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(3, gaugeProducer.mPastBuckets.begin()
                         ->second.back()
                         .mGaugeAtoms.front()
                         .mFields->begin()
                         ->mValue.int_value);

    allData.clear();
    allData.push_back(makeLogEvent(tagId, bucket3StartTimeNs + 10, 24, "some value", 25));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    it = gaugeProducer.mCurrentSlicedBucket->begin()->second.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(24, it->mValue.int_value);
    it++;
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(25, it->mValue.int_value);
    // One dimension.
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    ASSERT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.size());
    it = gaugeProducer.mPastBuckets.begin()->second.back().mGaugeAtoms.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(10L, it->mValue.int_value);
    it++;
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(11L, it->mValue.int_value);

    gaugeProducer.flushIfNeededLocked(bucket4StartTimeNs);
    ASSERT_EQ(0UL, gaugeProducer.mCurrentSlicedBucket->size());
    // One dimension.
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    ASSERT_EQ(3UL, gaugeProducer.mPastBuckets.begin()->second.size());
    it = gaugeProducer.mPastBuckets.begin()->second.back().mGaugeAtoms.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(24L, it->mValue.int_value);
    it++;
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(25L, it->mValue.int_value);
}

TEST_P(GaugeMetricProducerTest_PartialBucket, TestPushedEvents) {
    sp<AlarmMonitor> alarmMonitor;
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_gauge_fields_filter()->set_include_all(true);

    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(metricId);
    alert.set_trigger_if_sum_gt(25);
    alert.set_num_buckets(100);
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard,
                                      -1 /* -1 means no pulling */, -1, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    gaugeProducer.prepareFirstBucket();

    sp<AnomalyTracker> anomalyTracker = gaugeProducer.addAnomalyTracker(alert, alarmMonitor);
    EXPECT_TRUE(anomalyTracker != nullptr);

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 1, 10);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    EXPECT_EQ(1UL, (*gaugeProducer.mCurrentSlicedBucket).count(DEFAULT_METRIC_DIMENSION_KEY));

    switch (GetParam()) {
        case APP_UPGRADE:
            gaugeProducer.notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            gaugeProducer.onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }
    EXPECT_EQ(0UL, (*gaugeProducer.mCurrentSlicedBucket).count(DEFAULT_METRIC_DIMENSION_KEY));
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs,
              gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketStartNs);
    EXPECT_EQ(partialBucketSplitTimeNs,
              gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketEndNs);
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(partialBucketSplitTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    // Partial buckets are not sent to anomaly tracker.
    EXPECT_EQ(0, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // Create an event in the same partial bucket.
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event2, tagId, bucketStartTimeNs + 59 * NS_PER_SEC, 1, 10);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs,
              gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketStartNs);
    EXPECT_EQ(partialBucketSplitTimeNs,
              gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketEndNs);
    EXPECT_EQ((int64_t)partialBucketSplitTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    // Partial buckets are not sent to anomaly tracker.
    EXPECT_EQ(0, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // Next event should trigger creation of new bucket and send previous full bucket to anomaly
    // tracker.
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event3, tagId, bucketStartTimeNs + 65 * NS_PER_SEC, 1, 10);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    EXPECT_EQ(1L, gaugeProducer.mCurrentBucketNum);
    ASSERT_EQ(2UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ((int64_t)bucketStartTimeNs + bucketSizeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(1, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // Next event should trigger creation of new bucket.
    LogEvent event4(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event4, tagId, bucketStartTimeNs + 125 * NS_PER_SEC, 1, 10);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, event4);
    EXPECT_EQ(2L, gaugeProducer.mCurrentBucketNum);
    ASSERT_EQ(3UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(2, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));
}

TEST_P(GaugeMetricProducerTest_PartialBucket, TestPulled) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.set_max_pull_delay_sec(INT_MAX);
    auto gaugeFieldMatcher = metric.mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(tagId);
    gaugeFieldMatcher->add_child()->set_field(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Return(false))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, partialBucketSplitTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, eventTimeNs, 2));
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, tagId, -1,
                                      tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager);
    gaugeProducer.prepareFirstBucket();

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 1));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(1, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);

    switch (GetParam()) {
        case APP_UPGRADE:
            gaugeProducer.notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            gaugeProducer.onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs,
              gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketStartNs);
    EXPECT_EQ(partialBucketSplitTimeNs,
              gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketEndNs);
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(partialBucketSplitTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(2, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + bucketSizeNs + 1, 3));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs + bucketSizeNs);
    ASSERT_EQ(2UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(3, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);
}

TEST(GaugeMetricProducerTest, TestPulledWithAppUpgradeDisabled) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.set_max_pull_delay_sec(INT_MAX);
    metric.set_split_bucket_for_app_upgrade(false);
    auto gaugeFieldMatcher = metric.mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(tagId);
    gaugeFieldMatcher->add_child()->set_field(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Return(false));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, tagId, -1,
                                      tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager);
    gaugeProducer.prepareFirstBucket();

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 1));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(1, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);

    gaugeProducer.notifyAppUpgrade(partialBucketSplitTimeNs);
    ASSERT_EQ(0UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(bucketStartTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(1, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);
}

TEST(GaugeMetricProducerTest, TestPulledEventsWithCondition) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.set_max_pull_delay_sec(INT_MAX);
    auto gaugeFieldMatcher = metric.mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(tagId);
    gaugeFieldMatcher->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    int64_t conditionChangeNs = bucketStartTimeNs + 8;

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, conditionChangeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, eventTimeNs + 10, 100));
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, 0 /*condition index*/,
                                      {ConditionState::kUnknown}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, -1, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    gaugeProducer.prepareFirstBucket();

    gaugeProducer.onConditionChanged(true, conditionChangeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(100, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    ASSERT_EQ(0UL, gaugeProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 110));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(110, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(100, gaugeProducer.mPastBuckets.begin()
                           ->second.back()
                           .mGaugeAtoms.front()
                           .mFields->begin()
                           ->mValue.int_value);

    gaugeProducer.onConditionChanged(false, bucket2StartTimeNs + 10);
    gaugeProducer.flushIfNeededLocked(bucket3StartTimeNs + 10);
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    ASSERT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(110L, gaugeProducer.mPastBuckets.begin()
                            ->second.back()
                            .mGaugeAtoms.front()
                            .mFields->begin()
                            ->mValue.int_value);
}

TEST(GaugeMetricProducerTest, TestPulledEventsWithSlicedCondition) {
    const int conditionTag = 65;
    GaugeMetric metric;
    metric.set_id(1111111);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_gauge_fields_filter()->set_include_all(true);
    metric.set_condition(StringToId("APP_DIED"));
    metric.set_max_pull_delay_sec(INT_MAX);
    auto dim = metric.mutable_dimensions_in_what();
    dim->set_field(tagId);
    dim->add_child()->set_field(1);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    EXPECT_CALL(*wizard, query(_, _, _))
            .WillRepeatedly(
                    Invoke([](const int conditionIndex, const ConditionKey& conditionParameters,
                              const bool isPartialLink) {
                        int pos[] = {1, 0, 0};
                        Field f(conditionTag, pos, 0);
                        HashableDimensionKey key;
                        key.mutableValues()->emplace_back(f, Value((int32_t)1000000));

                        return ConditionState::kTrue;
                    }));

    int64_t sliceConditionChangeNs = bucketStartTimeNs + 8;

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, sliceConditionChangeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, eventTimeNs + 10, 1000, 100));
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, 0 /*condition index*/,
                                      {ConditionState::kUnknown}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, -1, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    gaugeProducer.prepareFirstBucket();

    gaugeProducer.onSlicedConditionMayChange(true, sliceConditionChangeNs);

    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    const auto& key = gaugeProducer.mCurrentSlicedBucket->begin()->first;
    ASSERT_EQ(1UL, key.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1000, key.getDimensionKeyInWhat().getValues()[0].mValue.int_value);

    ASSERT_EQ(0UL, gaugeProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 1000, 110));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets.size());
}

TEST(GaugeMetricProducerTest, TestPulledEventsAnomalyDetection) {
    sp<AlarmMonitor> alarmMonitor;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Return(false));

    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.set_max_pull_delay_sec(INT_MAX);
    auto gaugeFieldMatcher = metric.mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(tagId);
    gaugeFieldMatcher->add_child()->set_field(2);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, tagId, -1,
                                      tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager);
    gaugeProducer.prepareFirstBucket();

    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(metricId);
    alert.set_trigger_if_sum_gt(25);
    alert.set_num_buckets(2);
    const int32_t refPeriodSec = 60;
    alert.set_refractory_period_secs(refPeriodSec);
    sp<AnomalyTracker> anomalyTracker = gaugeProducer.addAnomalyTracker(alert, alarmMonitor);

    int tagId = 1;
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 13));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(13L, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    std::shared_ptr<LogEvent> event2 =
            CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + bucketSizeNs + 20, 15);

    allData.clear();
    allData.push_back(event2);
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs + bucketSizeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(15L, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event2->GetElapsedTimestampNs() / NS_PER_SEC) + refPeriodSec);

    allData.clear();
    allData.push_back(
            CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 2 * bucketSizeNs + 10, 26));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 2 * bucketSizeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(26L, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event2->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));

    // This event does not have the gauge field. Thus the current bucket value is 0.
    allData.clear();
    allData.push_back(CreateNoValuesLogEvent(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 10));
    gaugeProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs + 3 * bucketSizeNs);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_TRUE(gaugeProducer.mCurrentSlicedBucket->begin()->second.front().mFields->empty());
}

TEST(GaugeMetricProducerTest, TestPullOnTrigger) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.set_sampling_type(GaugeMetric::FIRST_N_SAMPLES);
    metric.mutable_gauge_fields_filter()->set_include_all(false);
    metric.set_max_pull_delay_sec(INT_MAX);
    auto gaugeFieldMatcher = metric.mutable_gauge_fields_filter()->mutable_fields();
    gaugeFieldMatcher->set_field(tagId);
    gaugeFieldMatcher->add_child()->set_field(1);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, eventTimeNs, 4));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 20);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, eventTimeNs, 5));
                return true;
            }))
            .WillOnce(Return(true));

    int triggerId = 5;
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, tagId,
                                      triggerId, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    gaugeProducer.prepareFirstBucket();

    ASSERT_EQ(0UL, gaugeProducer.mCurrentSlicedBucket->size());

    LogEvent triggerEvent(/*uid=*/0, /*pid=*/0);
    CreateNoValuesLogEvent(&triggerEvent, triggerId, bucketStartTimeNs + 10);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    triggerEvent.setElapsedTimestampNs(bucketStartTimeNs + 20);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);
    ASSERT_EQ(2UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    triggerEvent.setElapsedTimestampNs(bucket2StartTimeNs + 1);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);

    ASSERT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    ASSERT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.back().mGaugeAtoms.size());
    EXPECT_EQ(4, gaugeProducer.mPastBuckets.begin()
                         ->second.back()
                         .mGaugeAtoms[0]
                         .mFields->begin()
                         ->mValue.int_value);
    EXPECT_EQ(5, gaugeProducer.mPastBuckets.begin()
                         ->second.back()
                         .mGaugeAtoms[1]
                         .mFields->begin()
                         ->mValue.int_value);
}

TEST(GaugeMetricProducerTest, TestRemoveDimensionInOutput) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.set_sampling_type(GaugeMetric::FIRST_N_SAMPLES);
    metric.mutable_gauge_fields_filter()->set_include_all(true);
    metric.set_max_pull_delay_sec(INT_MAX);
    auto dimensionMatcher = metric.mutable_dimensions_in_what();
    // use field 1 as dimension.
    dimensionMatcher->set_field(tagId);
    dimensionMatcher->add_child()->set_field(1);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 3);
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, eventTimeNs, 3, 4));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, eventTimeNs, 4, 5));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 20);
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, eventTimeNs, 4, 6));
                return true;
            }))
            .WillOnce(Return(true));

    int triggerId = 5;
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, tagId,
                                      triggerId, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    gaugeProducer.prepareFirstBucket();

    LogEvent triggerEvent(/*uid=*/0, /*pid=*/0);
    CreateNoValuesLogEvent(&triggerEvent, triggerId, bucketStartTimeNs + 3);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    triggerEvent.setElapsedTimestampNs(bucketStartTimeNs + 10);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);
    ASSERT_EQ(2UL, gaugeProducer.mCurrentSlicedBucket->size());
    ASSERT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    triggerEvent.setElapsedTimestampNs(bucketStartTimeNs + 20);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);
    ASSERT_EQ(2UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    triggerEvent.setElapsedTimestampNs(bucket2StartTimeNs + 1);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);

    ASSERT_EQ(2UL, gaugeProducer.mPastBuckets.size());
    auto bucketIt = gaugeProducer.mPastBuckets.begin();
    ASSERT_EQ(1UL, bucketIt->second.back().mGaugeAtoms.size());
    EXPECT_EQ(3, bucketIt->first.getDimensionKeyInWhat().getValues().begin()->mValue.int_value);
    EXPECT_EQ(4, bucketIt->second.back().mGaugeAtoms[0].mFields->begin()->mValue.int_value);
    bucketIt++;
    ASSERT_EQ(2UL, bucketIt->second.back().mGaugeAtoms.size());
    EXPECT_EQ(4, bucketIt->first.getDimensionKeyInWhat().getValues().begin()->mValue.int_value);
    EXPECT_EQ(5, bucketIt->second.back().mGaugeAtoms[0].mFields->begin()->mValue.int_value);
    EXPECT_EQ(6, bucketIt->second.back().mGaugeAtoms[1].mFields->begin()->mValue.int_value);
}

/*
 * Test that BUCKET_TOO_SMALL dump reason is logged when a flushed bucket size
 * is smaller than the "min_bucket_size_nanos" specified in the metric config.
 */
TEST(GaugeMetricProducerTest_BucketDrop, TestBucketDropWhenBucketTooSmall) {
    GaugeMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(FIVE_MINUTES);
    metric.set_sampling_type(GaugeMetric::FIRST_N_SAMPLES);
    metric.set_min_bucket_size_nanos(10000000000);  // 10 seconds

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 3, _, _))
            // Bucket start.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, eventTimeNs, 10));
                return true;
            }));

    int triggerId = 5;
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, tagId,
                                      triggerId, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    gaugeProducer.prepareFirstBucket();

    LogEvent triggerEvent(/*uid=*/0, /*pid=*/0);
    CreateNoValuesLogEvent(&triggerEvent, triggerId, bucketStartTimeNs + 3);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, triggerEvent);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    gaugeProducer.onDumpReport(bucketStartTimeNs + 9000000, true /* include recent buckets */, true,
                               FAST /* dump_latency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_gauge_metrics());
    ASSERT_EQ(0, report.gauge_metrics().data_size());
    ASSERT_EQ(1, report.gauge_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.gauge_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 9000000),
              report.gauge_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.gauge_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.gauge_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::BUCKET_TOO_SMALL, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 9000000), dropEvent.drop_time_millis());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
