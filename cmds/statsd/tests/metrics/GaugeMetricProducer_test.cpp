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

#include "src/matchers/SimpleLogMatchingTracker.h"
#include "src/metrics/GaugeMetricProducer.h"
#include "src/stats_log_util.h"
#include "logd/LogEvent.h"
#include "metrics_test_helper.h"
#include "tests/statsd_test_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <math.h>
#include <stdio.h>
#include <vector>

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
const int64_t eventUpgradeTimeNs = bucketStartTimeNs + 15 * NS_PER_SEC;

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
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard,
                                      -1, -1, tagId, 5, 600 * NS_PER_SEC + NS_PER_SEC / 2,
                                      pullerManager);

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
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
                event->write(3);
                event->write("some value");
                event->write(11);
                event->init();
                data->push_back(event);
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard,
                                      tagId, -1, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(10);
    event->write("some value");
    event->write(11);
    event->init();
    allData.push_back(event);

    gaugeProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    auto it = gaugeProducer.mCurrentSlicedBucket->begin()->second.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(10, it->mValue.int_value);
    it++;
    EXPECT_EQ(11, it->mValue.int_value);
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(3, gaugeProducer.mPastBuckets.begin()->second.back().mGaugeAtoms
        .front().mFields->begin()->mValue.int_value);

    allData.clear();
    std::shared_ptr<LogEvent> event2 = std::make_shared<LogEvent>(tagId, bucket3StartTimeNs + 10);
    event2->write(24);
    event2->write("some value");
    event2->write(25);
    event2->init();
    allData.push_back(event2);
    gaugeProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    it = gaugeProducer.mCurrentSlicedBucket->begin()->second.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(24, it->mValue.int_value);
    it++;
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(25, it->mValue.int_value);
    // One dimension.
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.size());
    it = gaugeProducer.mPastBuckets.begin()->second.back().mGaugeAtoms.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(10L, it->mValue.int_value);
    it++;
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(11L, it->mValue.int_value);

    gaugeProducer.flushIfNeededLocked(bucket4StartTimeNs);
    EXPECT_EQ(0UL, gaugeProducer.mCurrentSlicedBucket->size());
    // One dimension.
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(3UL, gaugeProducer.mPastBuckets.begin()->second.size());
    it = gaugeProducer.mPastBuckets.begin()->second.back().mGaugeAtoms.front().mFields->begin();
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(24L, it->mValue.int_value);
    it++;
    EXPECT_EQ(INT, it->mValue.getType());
    EXPECT_EQ(25L, it->mValue.int_value);
}

TEST(GaugeMetricProducerTest, TestPushedEventsWithUpgrade) {
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
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard,
                                      -1 /* -1 means no pulling */, -1, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    sp<AnomalyTracker> anomalyTracker = gaugeProducer.addAnomalyTracker(alert, alarmMonitor);
    EXPECT_TRUE(anomalyTracker != nullptr);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    EXPECT_EQ(1UL, (*gaugeProducer.mCurrentSlicedBucket).count(DEFAULT_METRIC_DIMENSION_KEY));

    gaugeProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(0UL, (*gaugeProducer.mCurrentSlicedBucket).count(DEFAULT_METRIC_DIMENSION_KEY));
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(eventUpgradeTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    // Partial buckets are not sent to anomaly tracker.
    EXPECT_EQ(0, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // Create an event in the same partial bucket.
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 59 * NS_PER_SEC);
    event2->write(1);
    event2->write(10);
    event2->init();
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ((int64_t)eventUpgradeTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    // Partial buckets are not sent to anomaly tracker.
    EXPECT_EQ(0, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // Next event should trigger creation of new bucket and send previous full bucket to anomaly
    // tracker.
    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 65 * NS_PER_SEC);
    event3->write(1);
    event3->write(10);
    event3->init();
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
    EXPECT_EQ(1L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ((int64_t)bucketStartTimeNs + bucketSizeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(1, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));

    // Next event should trigger creation of new bucket.
    shared_ptr<LogEvent> event4 =
            make_shared<LogEvent>(tagId, bucketStartTimeNs + 125 * NS_PER_SEC);
    event4->write(1);
    event4->write(10);
    event4->init();
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
    EXPECT_EQ(2L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(3UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(2, anomalyTracker->getSumOverPastBuckets(DEFAULT_METRIC_DIMENSION_KEY));
}

TEST(GaugeMetricProducerTest, TestPulledWithUpgrade) {
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
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Return(false))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, eventUpgradeTimeNs);
                event->write("some value");
                event->write(2);
                event->init();
                data->push_back(event);
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId, -1, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
    event->write("some value");
    event->write(1);
    event->init();
    allData.push_back(event);
    gaugeProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(1, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);

    gaugeProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ((int64_t)eventUpgradeTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(2, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucketStartTimeNs + bucketSizeNs + 1);
    event->write("some value");
    event->write(3);
    event->init();
    allData.push_back(event);
    gaugeProducer.onDataPulled(allData);
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
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
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(false));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard,
                                      tagId, -1, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
    event->write("some value");
    event->write(1);
    event->init();
    allData.push_back(event);
    gaugeProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(1, gaugeProducer.mCurrentSlicedBucket->begin()
                         ->second.front()
                         .mFields->begin()
                         ->mValue.int_value);

    gaugeProducer.notifyAppUpgrade(eventUpgradeTimeNs, "ANY.APP", 1, 1);
    EXPECT_EQ(0UL, gaugeProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(0L, gaugeProducer.mCurrentBucketNum);
    EXPECT_EQ(bucketStartTimeNs, gaugeProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
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
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
                event->write("some value");
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, 1, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId, -1, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    gaugeProducer.onConditionChanged(true, bucketStartTimeNs + 8);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(100, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(0UL, gaugeProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write("some value");
    event->write(110);
    event->init();
    allData.push_back(event);
    gaugeProducer.onDataPulled(allData);

    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(110, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(100, gaugeProducer.mPastBuckets.begin()
                           ->second.back()
                           .mGaugeAtoms.front()
                           .mFields->begin()
                           ->mValue.int_value);

    gaugeProducer.onConditionChanged(false, bucket2StartTimeNs + 10);
    gaugeProducer.flushIfNeededLocked(bucket3StartTimeNs + 10);
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.size());
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

    dim = metric.mutable_dimensions_in_condition();
    dim->set_field(conditionTag);
    dim->add_child()->set_field(1);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    EXPECT_CALL(*wizard, query(_, _, _, _, _, _))
            .WillRepeatedly(
                    Invoke([](const int conditionIndex, const ConditionKey& conditionParameters,
                              const vector<Matcher>& dimensionFields, const bool isSubsetDim,
                              const bool isPartialLink,
                              std::unordered_set<HashableDimensionKey>* dimensionKeySet) {
                        dimensionKeySet->clear();
                        int pos[] = {1, 0, 0};
                        Field f(conditionTag, pos, 0);
                        HashableDimensionKey key;
                        key.mutableValues()->emplace_back(f, Value((int32_t)1000000));
                        dimensionKeySet->insert(key);

                        return ConditionState::kTrue;
                    }));

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
                event->write(1000);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, 1, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId, -1, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    gaugeProducer.onSlicedConditionMayChange(true, bucketStartTimeNs + 8);

    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    const auto& key = gaugeProducer.mCurrentSlicedBucket->begin()->first;
    EXPECT_EQ(1UL, key.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1000, key.getDimensionKeyInWhat().getValues()[0].mValue.int_value);

    EXPECT_EQ(1UL, key.getDimensionKeyInCondition().getValues().size());
    EXPECT_EQ(1000000, key.getDimensionKeyInCondition().getValues()[0].mValue.int_value);

    EXPECT_EQ(0UL, gaugeProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(1000);
    event->write(110);
    event->init();
    allData.push_back(event);
    gaugeProducer.onDataPulled(allData);

    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
}

TEST(GaugeMetricProducerTest, TestPulledEventsAnomalyDetection) {
    sp<AlarmMonitor> alarmMonitor;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(false));

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
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard,
                                      tagId, -1, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(metricId);
    alert.set_trigger_if_sum_gt(25);
    alert.set_num_buckets(2);
    const int32_t refPeriodSec = 60;
    alert.set_refractory_period_secs(refPeriodSec);
    sp<AnomalyTracker> anomalyTracker = gaugeProducer.addAnomalyTracker(alert, alarmMonitor);

    int tagId = 1;
    std::shared_ptr<LogEvent> event1 = std::make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
    event1->write("some value");
    event1->write(13);
    event1->init();

    gaugeProducer.onDataPulled({event1});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(13L, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    std::shared_ptr<LogEvent> event2 =
            std::make_shared<LogEvent>(tagId, bucketStartTimeNs + bucketSizeNs + 20);
    event2->write("some value");
    event2->write(15);
    event2->init();

    gaugeProducer.onDataPulled({event2});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(15L, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event2->GetElapsedTimestampNs() / NS_PER_SEC) + refPeriodSec);

    std::shared_ptr<LogEvent> event3 =
            std::make_shared<LogEvent>(tagId, bucketStartTimeNs + 2 * bucketSizeNs + 10);
    event3->write("some value");
    event3->write(26);
    event3->init();

    gaugeProducer.onDataPulled({event3});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(26L, gaugeProducer.mCurrentSlicedBucket->begin()
                           ->second.front()
                           .mFields->begin()
                           ->mValue.int_value);
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event2->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));

    // The event4 does not have the gauge field. Thus the current bucket value is 0.
    std::shared_ptr<LogEvent> event4 =
            std::make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 10);
    event4->write("some value");
    event4->init();
    gaugeProducer.onDataPulled({event4});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
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
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
                event->write(4);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
                event->write(5);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Return(true));

    int triggerId = 5;
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard,
                                      tagId, triggerId, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    vector<shared_ptr<LogEvent>> allData;

    EXPECT_EQ(0UL, gaugeProducer.mCurrentSlicedBucket->size());
    LogEvent trigger(triggerId, bucketStartTimeNs + 10);
    trigger.init();
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, trigger);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    trigger.setElapsedTimestampNs(bucketStartTimeNs + 20);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, trigger);
    EXPECT_EQ(2UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    trigger.setElapsedTimestampNs(bucket2StartTimeNs + 1);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, trigger);

    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.back().mGaugeAtoms.size());
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
    sp<EventMatcherWizard> eventMatcherWizard = new EventMatcherWizard({
        new SimpleLogMatchingTracker(atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3);
                event->write(3);
                event->write(4);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
                event->write(4);
                event->write(5);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
                event->write(4);
                event->write(6);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Return(true));

    int triggerId = 5;
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard,
                                      tagId, triggerId, tagId, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    vector<shared_ptr<LogEvent>> allData;

    LogEvent trigger(triggerId, bucketStartTimeNs + 3);
    trigger.init();
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, trigger);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    trigger.setElapsedTimestampNs(bucketStartTimeNs + 10);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, trigger);
    EXPECT_EQ(2UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    trigger.setElapsedTimestampNs(bucketStartTimeNs + 20);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, trigger);
    EXPECT_EQ(2UL, gaugeProducer.mCurrentSlicedBucket->begin()->second.size());
    trigger.setElapsedTimestampNs(bucket2StartTimeNs + 1);
    gaugeProducer.onMatchedLogEvent(1 /*log matcher index*/, trigger);

    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.size());
    auto bucketIt = gaugeProducer.mPastBuckets.begin();
    EXPECT_EQ(1UL, bucketIt->second.back().mGaugeAtoms.size());
    EXPECT_EQ(3, bucketIt->first.getDimensionKeyInWhat().getValues().begin()->mValue.int_value);
    EXPECT_EQ(4, bucketIt->second.back().mGaugeAtoms[0].mFields->begin()->mValue.int_value);
    bucketIt++;
    EXPECT_EQ(2UL, bucketIt->second.back().mGaugeAtoms.size());
    EXPECT_EQ(4, bucketIt->first.getDimensionKeyInWhat().getValues().begin()->mValue.int_value);
    EXPECT_EQ(5, bucketIt->second.back().mGaugeAtoms[0].mFields->begin()->mValue.int_value);
    EXPECT_EQ(6, bucketIt->second.back().mGaugeAtoms[1].mFields->begin()->mValue.int_value);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
