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
#include "src/metrics/ValueMetricProducer.h"
#include "src/stats_log_util.h"
#include "metrics_test_helper.h"
#include "tests/statsd_test_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <math.h>
#include <stdio.h>
#include <vector>

using namespace testing;
using android::sp;
using std::make_shared;
using std::set;
using std::shared_ptr;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

const ConfigKey kConfigKey(0, 12345);
const int tagId = 1;
const int64_t metricId = 123;
const int64_t atomMatcherId = 678;
const int logEventMatcherIndex = 0;
const int64_t bucketStartTimeNs = 10000000000;
const int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
const int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
const int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
const int64_t bucket4StartTimeNs = bucketStartTimeNs + 3 * bucketSizeNs;
const int64_t bucket5StartTimeNs = bucketStartTimeNs + 4 * bucketSizeNs;
const int64_t bucket6StartTimeNs = bucketStartTimeNs + 5 * bucketSizeNs;
double epsilon = 0.001;

/*
 * Tests that the first bucket works correctly
 */
TEST(ValueMetricProducerTest, TestCalcPreviousBucketEndTime) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);

    int64_t startTimeBase = 11;
    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    // statsd started long ago.
    // The metric starts in the middle of the bucket
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, -1, startTimeBase,
                                      22, pullerManager);

    EXPECT_EQ(startTimeBase, valueProducer.calcPreviousBucketEndTime(60 * NS_PER_SEC + 10));
    EXPECT_EQ(startTimeBase, valueProducer.calcPreviousBucketEndTime(60 * NS_PER_SEC + 10));
    EXPECT_EQ(60 * NS_PER_SEC + startTimeBase,
              valueProducer.calcPreviousBucketEndTime(2 * 60 * NS_PER_SEC));
    EXPECT_EQ(2 * 60 * NS_PER_SEC + startTimeBase,
              valueProducer.calcPreviousBucketEndTime(3 * 60 * NS_PER_SEC));
}

/*
 * Tests that the first bucket works correctly
 */
TEST(ValueMetricProducerTest, TestFirstBucket) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    // statsd started long ago.
    // The metric starts in the middle of the bucket
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, -1, 5,
                                      600 * NS_PER_SEC + NS_PER_SEC / 2, pullerManager);

    EXPECT_EQ(600500000000, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(10, valueProducer.mCurrentBucketNum);
    EXPECT_EQ(660000000005, valueProducer.getCurrentBucketEndTimeNs());
}

/*
 * Tests pulled atoms with no conditions
 */
TEST(ValueMetricProducerTest, TestPulledEventsNoCondition) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(tagId);
                event->write(3);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(11);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];

    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(11, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(8, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(tagId);
    event->write(23);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];

    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(23, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(12, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(12, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
    event->write(tagId);
    event->write(36);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];

    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(36, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(13, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(3UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(12, valueProducer.mPastBuckets.begin()->second[1].values[0].long_value);
    EXPECT_EQ(13, valueProducer.mPastBuckets.begin()->second[2].values[0].long_value);
}

TEST(ValueMetricProducerTest, TestPartialBucketCreated) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // Initialize bucket.
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
                event->write(tagId);
                event->write(1);
                event->init();
                data->push_back(event);
                return true;
            }))
            // Partial bucket.
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
                event->write(tagId);
                event->write(5);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    // First bucket ends.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
    event->write(tagId);
    event->write(2);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** success */ true, bucket2StartTimeNs);

    // Partial buckets created in 2nd bucket.
    valueProducer.notifyAppUpgrade(bucket2StartTimeNs + 2, "com.foo", 10000, 1);

    // One full bucket and one partial bucket.
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    vector<ValueBucket> buckets = valueProducer.mPastBuckets.begin()->second;
    EXPECT_EQ(2UL, buckets.size());
    // Full bucket (2 - 1)
    EXPECT_EQ(1, buckets[0].values[0].long_value);
    // Full bucket (5 - 3)
    EXPECT_EQ(3, buckets[1].values[0].long_value);
}

/*
 * Tests pulled atoms with filtering
 */
TEST(ValueMetricProducerTest, TestPulledEventsWithFiltering) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    auto keyValue = atomMatcher.add_field_value_matcher();
    keyValue->set_field(1);
    keyValue->set_eq_int(3);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(3);
                event->write(3);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(3);
    event->write(11);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];

    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(11, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(8, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(4);
    event->write(23);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // No new data seen, so data has been cleared.
    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());

    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(11, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(8, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
    event->write(3);
    event->write(36);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];

    // the base was reset
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(36, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

/*
 * Tests pulled atoms with no conditions and take absolute value after reset
 */
TEST(ValueMetricProducerTest, TestPulledEventsTakeAbsoluteValueOnReset) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_use_absolute_value_on_reset(true);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(true));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(11);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];

    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(11, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(tagId);
    event->write(10);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(10, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(10, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
    event->write(tagId);
    event->write(36);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(36, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(26, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(10, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(26, valueProducer.mPastBuckets.begin()->second[1].values[0].long_value);
}

/*
 * Tests pulled atoms with no conditions and take zero value after reset
 */
TEST(ValueMetricProducerTest, TestPulledEventsTakeZeroOnReset) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(false));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(11);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];

    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(11, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(tagId);
    event->write(10);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(10, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
    event->write(tagId);
    event->write(36);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(36, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(26, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(26, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
}

/*
 * Test pulled event with non sliced condition.
 */
TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    // startUpdated:false sum:0 start:100
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(100, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(110, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(10, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);

    valueProducer.onConditionChanged(false, bucket2StartTimeNs + 1);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(false, curInterval.hasBase);
}

TEST(ValueMetricProducerTest, TestPushedEventsWithUpgrade) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.notifyAppUpgrade(bucketStartTimeNs + 150, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs + 150, valueProducer.mCurrentBucketStartTimeNs);

    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 59 * NS_PER_SEC);
    event2->write(1);
    event2->write(10);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs + 150, valueProducer.mCurrentBucketStartTimeNs);

    // Next value should create a new bucket.
    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 65 * NS_PER_SEC);
    event3->write(1);
    event3->write(10);
    event3->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, valueProducer.mCurrentBucketStartTimeNs);
}

TEST(ValueMetricProducerTest, TestPulledValueWithUpgrade) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Return(true))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 149);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }));
    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(100);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.notifyAppUpgrade(bucket2StartTimeNs + 150, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucket2StartTimeNs + 150, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(20L,
              valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].values[0].long_value);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(tagId);
    event->write(150);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucket3StartTimeNs, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(20L,
              valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].values[0].long_value);
}

TEST(ValueMetricProducerTest, TestPulledWithAppUpgradeDisabled) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);
    metric.set_split_bucket_for_app_upgrade(false);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(true));
    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(100);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.notifyAppUpgrade(bucket2StartTimeNs + 150, "ANY.APP", 1, 1);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucket2StartTimeNs, valueProducer.mCurrentBucketStartTimeNs);
}

TEST(ValueMetricProducerTest, TestPulledValueWithUpgradeWhileConditionFalse) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs - 100);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }));
    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 1);

    valueProducer.onConditionChanged(false, bucket2StartTimeNs-100);
    EXPECT_FALSE(valueProducer.mCondition);

    valueProducer.notifyAppUpgrade(bucket2StartTimeNs-50, "ANY.APP", 1, 1);
    // Expect one full buckets already done and starting a partial bucket.
    EXPECT_EQ(bucket2StartTimeNs-50, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketStartNs);
    EXPECT_EQ(20L, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].values[0].long_value);
    EXPECT_FALSE(valueProducer.mCondition);
}

TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
    event2->write(1);
    event2->write(20);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(30, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(30, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

TEST(ValueMetricProducerTest, TestPushedEventsWithCondition) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has 1 slice
    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.onConditionChangedLocked(true, bucketStartTimeNs + 15);
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
    event2->write(1);
    event2->write(20);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(20, curInterval.value.long_value);

    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 30);
    event3->write(1);
    event3->write(30);
    event3->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(50, curInterval.value.long_value);

    valueProducer.onConditionChangedLocked(false, bucketStartTimeNs + 35);
    shared_ptr<LogEvent> event4 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 40);
    event4->write(1);
    event4->write(40);
    event4->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(50, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(50, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

TEST(ValueMetricProducerTest, TestAnomalyDetection) {
    sp<AlarmMonitor> alarmMonitor;
    Alert alert;
    alert.set_id(101);
    alert.set_metric_id(metricId);
    alert.set_trigger_if_sum_gt(130);
    alert.set_num_buckets(2);
    const int32_t refPeriodSec = 3;
    alert.set_refractory_period_secs(refPeriodSec);

    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, -1 /*not pulled*/,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    sp<AnomalyTracker> anomalyTracker = valueProducer.addAnomalyTracker(alert, alarmMonitor);


    shared_ptr<LogEvent> event1
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1 * NS_PER_SEC);
    event1->write(161);
    event1->write(10); // value of interest
    event1->init();
    shared_ptr<LogEvent> event2
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 2 + NS_PER_SEC);
    event2->write(162);
    event2->write(20); // value of interest
    event2->init();
    shared_ptr<LogEvent> event3
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 2 * bucketSizeNs + 1 * NS_PER_SEC);
    event3->write(163);
    event3->write(130); // value of interest
    event3->init();
    shared_ptr<LogEvent> event4
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 1 * NS_PER_SEC);
    event4->write(35);
    event4->write(1); // value of interest
    event4->init();
    shared_ptr<LogEvent> event5
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 2 * NS_PER_SEC);
    event5->write(45);
    event5->write(150); // value of interest
    event5->init();
    shared_ptr<LogEvent> event6
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 10 * NS_PER_SEC);
    event6->write(25);
    event6->write(160); // value of interest
    event6->init();

    // Two events in bucket #0.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
    // Value sum == 30 <= 130.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    // One event in bucket #2. No alarm as bucket #0 is trashed out.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
    // Value sum == 130 <= 130.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    // Three events in bucket #3.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
    // Anomaly at event 4 since Value sum == 131 > 130!
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
            std::ceil(1.0 * event4->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event5);
    // Event 5 is within 3 sec refractory period. Thus last alarm timestamp is still event4.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
            std::ceil(1.0 * event4->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event6);
    // Anomaly at event 6 since Value sum == 160 > 130 and after refractory period.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
            std::ceil(1.0 * event6->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
}

// Test value metric no condition, the pull on bucket boundary come in time and too late
TEST(ValueMetricProducerTest, TestBucketBoundaryNoCondition) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(true));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    // pull 1
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(11);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];

    // startUpdated:true sum:0 start:11
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(11, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    // pull 2 at correct time
    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(tagId);
    event->write(23);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    // tartUpdated:false sum:12
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(23, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(12, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);

    // pull 3 come late.
    // The previous bucket gets closed with error. (Has start value 23, no ending)
    // Another bucket gets closed with error. (No start, but ending with 36)
    // The new bucket is back to normal.
    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket6StartTimeNs + 1);
    event->write(tagId);
    event->write(36);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket6StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    // startUpdated:false sum:12
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(36, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(12, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

/*
 * Test pulled event with non sliced condition. The pull on boundary come late because the alarm
 * was delivered late.
 */
TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // condition becomes true
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }))
            // condition becomes false
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(100, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    // pull on bucket boundary come late, condition change happens before it
    valueProducer.onConditionChanged(false, bucket2StartTimeNs + 1);
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    // Now the alarm is delivered.
    // since the condition turned to off before this pull finish, it has no effect
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 30);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
}

/*
 * Test pulled event with non sliced condition. The pull on boundary come late, after the condition
 * change to false, and then true again. This is due to alarm delivered late.
 */
TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition2) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillRepeatedly(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // condition becomes true
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }))
            // condition becomes false
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }))
            // condition becomes true again
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 25);
                event->write(tagId);
                event->write(130);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    // startUpdated:false sum:0 start:100
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(100, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    // pull on bucket boundary come late, condition change happens before it
    valueProducer.onConditionChanged(false, bucket2StartTimeNs + 1);
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    // condition changed to true again, before the pull alarm is delivered
    valueProducer.onConditionChanged(true, bucket2StartTimeNs + 25);
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(130, curInterval.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    // Now the alarm is delivered, but it is considered late, the bucket is invalidated.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 50);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(130, curInterval.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
}

TEST(ValueMetricProducerTest, TestPushedAggregateMin) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_aggregation_type(ValueMetric::MIN);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
    event2->write(1);
    event2->write(20);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(10, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

TEST(ValueMetricProducerTest, TestPushedAggregateMax) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_aggregation_type(ValueMetric::MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
    event2->write(1);
    event2->write(20);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(20, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(20, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

TEST(ValueMetricProducerTest, TestPushedAggregateAvg) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_aggregation_type(ValueMetric::AVG);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
    event2->write(1);
    event2->write(15);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval;
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(1, curInterval.sampleSize);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(25, curInterval.value.long_value);
    EXPECT_EQ(2, curInterval.sampleSize);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_TRUE(std::abs(valueProducer.mPastBuckets.begin()->second.back().values[0].double_value - 12.5) < epsilon);
}

TEST(ValueMetricProducerTest, TestPushedAggregateSum) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_aggregation_type(ValueMetric::SUM);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
    event2->write(1);
    event2->write(15);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(25, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(25, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

TEST(ValueMetricProducerTest, TestSkipZeroDiffOutput) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_aggregation_type(ValueMetric::MIN);
    metric.set_use_diff(true);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 15);
    event2->write(1);
    event2->write(15);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(10, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(5, curInterval.value.long_value);

    // no change in data.
    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
    event3->write(1);
    event3->write(15);
    event3->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(15, curInterval.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    shared_ptr<LogEvent> event4 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 15);
    event4->write(1);
    event4->write(15);
    event4->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(15, curInterval.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(5, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value);
}

TEST(ValueMetricProducerTest, TestSkipZeroDiffOutputMultiValue) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.mutable_value_field()->add_child()->set_field(3);
    metric.set_aggregation_type(ValueMetric::MIN);
    metric.set_use_diff(true);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->write(20);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 15);
    event2->write(1);
    event2->write(15);
    event2->write(22);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval0 =
        valueProducer.mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::Interval curInterval1 =
        valueProducer.mCurrentSlicedBucket.begin()->second[1];
    EXPECT_EQ(true, curInterval0.hasBase);
    EXPECT_EQ(10, curInterval0.base.long_value);
    EXPECT_EQ(false, curInterval0.hasValue);
    EXPECT_EQ(true, curInterval1.hasBase);
    EXPECT_EQ(20, curInterval1.base.long_value);
    EXPECT_EQ(false, curInterval1.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval0 = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curInterval1 = valueProducer.mCurrentSlicedBucket.begin()->second[1];
    EXPECT_EQ(true, curInterval0.hasValue);
    EXPECT_EQ(5, curInterval0.value.long_value);
    EXPECT_EQ(true, curInterval1.hasValue);
    EXPECT_EQ(2, curInterval1.value.long_value);

    // no change in first value field
    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
    event3->write(1);
    event3->write(15);
    event3->write(25);
    event3->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval0 = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curInterval1 = valueProducer.mCurrentSlicedBucket.begin()->second[1];
    EXPECT_EQ(true, curInterval0.hasBase);
    EXPECT_EQ(15, curInterval0.base.long_value);
    EXPECT_EQ(true, curInterval0.hasValue);
    EXPECT_EQ(true, curInterval1.hasBase);
    EXPECT_EQ(25, curInterval1.base.long_value);
    EXPECT_EQ(true, curInterval1.hasValue);

    shared_ptr<LogEvent> event4 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 15);
    event4->write(1);
    event4->write(15);
    event4->write(29);
    event4->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval0 = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curInterval1 = valueProducer.mCurrentSlicedBucket.begin()->second[1];
    EXPECT_EQ(true, curInterval0.hasBase);
    EXPECT_EQ(15, curInterval0.base.long_value);
    EXPECT_EQ(true, curInterval0.hasValue);
    EXPECT_EQ(true, curInterval1.hasBase);
    EXPECT_EQ(29, curInterval1.base.long_value);
    EXPECT_EQ(true, curInterval1.hasValue);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);

    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second[0].values.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second[1].values.size());

    EXPECT_EQ(5, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(0, valueProducer.mPastBuckets.begin()->second[0].valueIndex[0]);
    EXPECT_EQ(2, valueProducer.mPastBuckets.begin()->second[0].values[1].long_value);
    EXPECT_EQ(1, valueProducer.mPastBuckets.begin()->second[0].valueIndex[1]);

    EXPECT_EQ(3, valueProducer.mPastBuckets.begin()->second[1].values[0].long_value);
    EXPECT_EQ(1, valueProducer.mPastBuckets.begin()->second[1].valueIndex[0]);
}

/*
 * Tests zero default base.
 */
TEST(ValueMetricProducerTest, TestUseZeroDefaultBase) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_use_zero_default_base(true);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(1);
                event->write(3);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    auto iter = valueProducer.mCurrentSlicedBucket.begin();
    auto& interval1 = iter->second[0];
    EXPECT_EQ(1, iter->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(3, interval1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
    vector<shared_ptr<LogEvent>> allData;

    allData.clear();
    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event1->write(2);
    event1->write(4);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event2->write(1);
    event2->write(11);
    event2->init();
    allData.push_back(event1);
    allData.push_back(event2);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(2UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(11, interval1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(8, interval1.value.long_value);

    auto it = valueProducer.mCurrentSlicedBucket.begin();
    for (; it != valueProducer.mCurrentSlicedBucket.end(); it++) {
        if (it != iter) {
            break;
        }
    }
    EXPECT_TRUE(it != iter);
    auto& interval2 = it->second[0];
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, interval2.hasBase);
    EXPECT_EQ(4, interval2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_EQ(4, interval2.value.long_value);

    EXPECT_EQ(2UL, valueProducer.mPastBuckets.size());
    auto iterator = valueProducer.mPastBuckets.begin();
    EXPECT_EQ(8, iterator->second[0].values[0].long_value);
    iterator++;
    EXPECT_EQ(4, iterator->second[0].values[0].long_value);
}

/*
 * Tests using zero default base with failed pull.
 */
TEST(ValueMetricProducerTest, TestUseZeroDefaultBaseWithPullFailures) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_use_zero_default_base(true);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(1);
                event->write(3);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    auto iter = valueProducer.mCurrentSlicedBucket.begin();
    auto& interval1 = iter->second[0];
    EXPECT_EQ(1, iter->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(3, interval1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
    vector<shared_ptr<LogEvent>> allData;

    allData.clear();
    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event1->write(2);
    event1->write(4);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event2->write(1);
    event2->write(11);
    event2->init();
    allData.push_back(event1);
    allData.push_back(event2);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(2UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(11, interval1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(8, interval1.value.long_value);

    auto it = valueProducer.mCurrentSlicedBucket.begin();
    for (; it != valueProducer.mCurrentSlicedBucket.end(); it++) {
        if (it != iter) {
            break;
        }
    }
    EXPECT_TRUE(it != iter);
    auto& interval2 = it->second[0];
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, interval2.hasBase);
    EXPECT_EQ(4, interval2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_EQ(4, interval2.value.long_value);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.size());

    // next pull somehow did not happen, skip to end of bucket 3
    allData.clear();
    event1 = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
    event1->write(2);
    event1->write(5);
    event1->init();
    allData.push_back(event1);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);

    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(true, interval2.hasBase);
    EXPECT_EQ(4, interval2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.size());

    allData.clear();
    event1 = make_shared<LogEvent>(tagId, bucket5StartTimeNs + 1);
    event1->write(2);
    event1->write(13);
    event1->init();
    allData.push_back(event1);
    event2 = make_shared<LogEvent>(tagId, bucket5StartTimeNs + 1);
    event2->write(1);
    event2->write(5);
    event2->init();
    allData.push_back(event2);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket5StartTimeNs);

    EXPECT_EQ(2UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(true, interval2.hasBase);
    EXPECT_EQ(5, interval2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_EQ(5, interval2.value.long_value);
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(13, interval1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(8, interval1.value.long_value);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.size());
}

/*
 * Tests trim unused dimension key if no new data is seen in an entire bucket.
 */
TEST(ValueMetricProducerTest, TestTrimUnusedDimensionKey) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(1);
                event->write(3);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    auto iter = valueProducer.mCurrentSlicedBucket.begin();
    auto& interval1 = iter->second[0];
    EXPECT_EQ(1, iter->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(3, interval1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
    vector<shared_ptr<LogEvent>> allData;

    allData.clear();
    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event1->write(2);
    event1->write(4);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event2->write(1);
    event2->write(11);
    event2->init();
    allData.push_back(event1);
    allData.push_back(event2);

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(2UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(true, interval1.hasBase);
    EXPECT_EQ(11, interval1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(8, interval1.value.long_value);
    EXPECT_FALSE(interval1.seenNewData);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(8, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);

    auto it = valueProducer.mCurrentSlicedBucket.begin();
    for (; it != valueProducer.mCurrentSlicedBucket.end(); it++) {
        if (it != iter) {
            break;
        }
    }
    EXPECT_TRUE(it != iter);
    auto& interval2 = it->second[0];
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, interval2.hasBase);
    EXPECT_EQ(4, interval2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_FALSE(interval2.seenNewData);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());

    // next pull somehow did not happen, skip to end of bucket 3
    allData.clear();
    event1 = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
    event1->write(2);
    event1->write(5);
    event1->init();
    allData.push_back(event1);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);

	// Only one interval left. One was trimmed.
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    interval2 = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, interval2.hasBase);
    EXPECT_EQ(5, interval2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_FALSE(interval2.seenNewData);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());

    allData.clear();
    event1 = make_shared<LogEvent>(tagId, bucket5StartTimeNs + 1);
    event1->write(2);
    event1->write(14);
    event1->init();
    allData.push_back(event1);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket5StartTimeNs);

    interval2 = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, interval2.hasBase);
    EXPECT_EQ(14, interval2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_FALSE(interval2.seenNewData);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.size());
    auto iterator = valueProducer.mPastBuckets.begin();
    EXPECT_EQ(9, iterator->second[0].values[0].long_value);
    iterator++;
    EXPECT_EQ(8, iterator->second[0].values[0].long_value);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange_EndOfBucket) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    // Used by onConditionChanged.
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 8);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(100, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);

    vector<shared_ptr<LogEvent>> allData;
    valueProducer.onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Return(false));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(100, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    valueProducer.onConditionChanged(false, bucketStartTimeNs + 20);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(false, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullFailBeforeConditionChange) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.mCondition = true;

    vector<shared_ptr<LogEvent>> allData;
    valueProducer.onDataPulled(allData, /** succeed */ false, bucketStartTimeNs);
    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.onConditionChanged(false, bucketStartTimeNs + 1);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullDelayExceeded) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(0);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.mCondition = false;

    // Max delay is set to 0 so pull will exceed max delay.
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 1);
    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullTooLate) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucket2StartTimeNs,
                                      bucket2StartTimeNs, pullerManager);

    valueProducer.mCondition = false;

    // Event should be skipped since it is from previous bucket.
    // Pull should not be called.
    valueProducer.onConditionChanged(true, bucketStartTimeNs);
    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());
}

TEST(ValueMetricProducerTest, TestBaseSetOnConditionChange) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.mCondition = false;
    valueProducer.mHasGlobalBase = false;

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 1);
    valueProducer.mHasGlobalBase = true;
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(100, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestInvalidBucketWhenOneConditionFailed) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // First onConditionChanged
            .WillOnce(Return(false))
            // Second onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(130);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.mCondition = true;

    // Bucket start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);

    // This will fail and should invalidate the whole bucket since we do not have all the data
    // needed to compute the metric value when the screen was on.
    valueProducer.onConditionChanged(false, bucketStartTimeNs + 2);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 3);

    // Bucket end.
    allData.clear();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event2->write(1);
    event2->write(140);
    event2->init();
    allData.push_back(event2);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs + 1);
    
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
    // Contains base from last pull which was successful.
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(140, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestInvalidBucketWhenInitialPullFailed) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }))
            // Second onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(130);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.mCondition = true;

    // Bucket start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ false, bucketStartTimeNs);

    valueProducer.onConditionChanged(false, bucketStartTimeNs + 2);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 3);

    // Bucket end.
    allData.clear();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event2->write(1);
    event2->write(140);
    event2->init();
    allData.push_back(event2);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs + 1);
    
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
    // Contains base from last pull which was successful.
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(140, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestInvalidBucketWhenLastPullFailed) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }))
            // Second onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
                event->write(tagId);
                event->write(130);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.mCondition = true;

    // Bucket start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);

    // This will fail and should invalidate the whole bucket since we do not have all the data
    // needed to compute the metric value when the screen was on.
    valueProducer.onConditionChanged(false, bucketStartTimeNs + 2);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 3);

    // Bucket end.
    allData.clear();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event2->write(1);
    event2->write(140);
    event2->init();
    allData.push_back(event2);
    valueProducer.onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs + 1);
    
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());
    // Last pull failed so based has been reset.
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onDataPulled) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // Start bucket.
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(tagId);
                event->write(3);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    // Bucket 2 start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());

    // Bucket 3 empty.
    allData.clear();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event2->init();
    allData.push_back(event2);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // Data has been trimmed.
    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
}

TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onConditionChanged) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(tagId);
                event->write(3);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 10);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);

    // Empty pull.
    valueProducer.onConditionChanged(false, bucketStartTimeNs + 10);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer.mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onBucketBoundary) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(tagId);
                event->write(1);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(tagId);
                event->write(2);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(tagId);
                event->write(5);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 10);
    valueProducer.onConditionChanged(false, bucketStartTimeNs + 11);
    valueProducer.onConditionChanged(true, bucketStartTimeNs + 12);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasBase);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);

    // End of bucket
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    // Data is empty, base should be reset.
    EXPECT_EQ(false, curInterval.hasBase);
    EXPECT_EQ(5, curInterval.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer.mHasGlobalBase);

    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
}


TEST(ValueMetricProducerTest, TestPartialResetOnBucketBoundaries) {
    ValueMetric metric;
    metric.set_id(metricId);
    metric.set_bucket(ONE_MINUTE);
    metric.mutable_value_field()->set_field(tagId);
    metric.mutable_value_field()->add_child()->set_field(2);
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(INT_MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
                event->write(tagId);
                event->write(1);
                event->write(1);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 10);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    // End of bucket
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(2);
    event->write(2);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // Key 1 should be reset since in not present in the most pull.
    EXPECT_EQ(2UL, valueProducer.mCurrentSlicedBucket.size());
    auto iterator = valueProducer.mCurrentSlicedBucket.begin();
    EXPECT_EQ(true, iterator->second[0].hasBase);
    EXPECT_EQ(2, iterator->second[0].base.long_value);
    EXPECT_EQ(false, iterator->second[0].hasValue);
    iterator++;
    EXPECT_EQ(false, iterator->second[0].hasBase);
    EXPECT_EQ(1, iterator->second[0].base.long_value);
    EXPECT_EQ(false, iterator->second[0].hasValue);

    EXPECT_EQ(true, valueProducer.mHasGlobalBase);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
