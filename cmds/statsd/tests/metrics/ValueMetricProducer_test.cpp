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

#include "src/metrics/ValueMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <math.h>
#include <stdio.h>

#include <vector>

#include "metrics_test_helper.h"
#include "src/matchers/SimpleLogMatchingTracker.h"
#include "src/metrics/MetricProducer.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

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

namespace {

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

static void assertPastBucketValuesSingleKey(
        const std::unordered_map<MetricDimensionKey, std::vector<ValueBucket>>& mPastBuckets,
        const std::initializer_list<int>& expectedValuesList,
        const std::initializer_list<int64_t>& expectedDurationNsList,
        const std::initializer_list<int64_t>& expectedStartTimeNsList,
        const std::initializer_list<int64_t>& expectedEndTimeNsList) {
    vector<int> expectedValues(expectedValuesList);
    vector<int64_t> expectedDurationNs(expectedDurationNsList);
    vector<int64_t> expectedStartTimeNs(expectedStartTimeNsList);
    vector<int64_t> expectedEndTimeNs(expectedEndTimeNsList);

    ASSERT_EQ(expectedValues.size(), expectedDurationNs.size());
    ASSERT_EQ(expectedValues.size(), expectedStartTimeNs.size());
    ASSERT_EQ(expectedValues.size(), expectedEndTimeNs.size());

    if (expectedValues.size() == 0) {
        ASSERT_EQ(0, mPastBuckets.size());
        return;
    }

    ASSERT_EQ(1, mPastBuckets.size());
    ASSERT_EQ(expectedValues.size(), mPastBuckets.begin()->second.size());

    const vector<ValueBucket>& buckets = mPastBuckets.begin()->second;
    for (int i = 0; i < expectedValues.size(); i++) {
        EXPECT_EQ(expectedValues[i], buckets[i].values[0].long_value)
                << "Values differ at index " << i;
        EXPECT_EQ(expectedDurationNs[i], buckets[i].mConditionTrueNs)
                << "Condition duration value differ at index " << i;
        EXPECT_EQ(expectedStartTimeNs[i], buckets[i].mBucketStartNs)
                << "Start time differs at index " << i;
        EXPECT_EQ(expectedEndTimeNs[i], buckets[i].mBucketEndNs)
                << "End time differs at index " << i;
    }
}

}  // anonymous namespace

class ValueMetricProducerTestHelper {
public:
    static sp<ValueMetricProducer> createValueProducerNoConditions(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _))
                .WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _))
                .WillRepeatedly(Return());

        sp<ValueMetricProducer> valueProducer =
                new ValueMetricProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                        wizard, logEventMatcherIndex, eventMatcherWizard, tagId,
                                        bucketStartTimeNs, bucketStartTimeNs, pullerManager);
        valueProducer->prepareFirstBucket();
        return valueProducer;
    }

    static sp<ValueMetricProducer> createValueProducerWithCondition(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric,
            ConditionState conditionAfterFirstBucketPrepared) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _))
                .WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _))
                .WillRepeatedly(Return());

        sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
                kConfigKey, metric, 0 /*condition index*/, {ConditionState::kUnknown}, wizard,
                logEventMatcherIndex, eventMatcherWizard, tagId, bucketStartTimeNs,
                bucketStartTimeNs, pullerManager);
        valueProducer->prepareFirstBucket();
        valueProducer->mCondition = conditionAfterFirstBucketPrepared;
        return valueProducer;
    }

    static sp<ValueMetricProducer> createValueProducerWithState(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric,
            vector<int32_t> slicedStateAtoms,
            unordered_map<int, unordered_map<int, int64_t>> stateGroupMap) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _))
                .WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _))
                .WillRepeatedly(Return());

        sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
                kConfigKey, metric, -1 /* no condition */, {}, wizard, logEventMatcherIndex,
                eventMatcherWizard, tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager, {},
                {}, slicedStateAtoms, stateGroupMap);
        valueProducer->prepareFirstBucket();
        return valueProducer;
    }

    static sp<ValueMetricProducer> createValueProducerWithConditionAndState(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric,
            vector<int32_t> slicedStateAtoms,
            unordered_map<int, unordered_map<int, int64_t>> stateGroupMap,
            ConditionState conditionAfterFirstBucketPrepared) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _))
                .WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _))
                .WillRepeatedly(Return());

        sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
                kConfigKey, metric, 0 /* condition tracker index */, {ConditionState::kUnknown},
                wizard, logEventMatcherIndex, eventMatcherWizard, tagId, bucketStartTimeNs,
                bucketStartTimeNs, pullerManager, {}, {}, slicedStateAtoms, stateGroupMap);
        valueProducer->prepareFirstBucket();
        valueProducer->mCondition = conditionAfterFirstBucketPrepared;
        return valueProducer;
    }

    static ValueMetric createMetric() {
        ValueMetric metric;
        metric.set_id(metricId);
        metric.set_bucket(ONE_MINUTE);
        metric.mutable_value_field()->set_field(tagId);
        metric.mutable_value_field()->add_child()->set_field(2);
        metric.set_max_pull_delay_sec(INT_MAX);
        return metric;
    }

    static ValueMetric createMetricWithCondition() {
        ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
        metric.set_condition(StringToId("SCREEN_ON"));
        return metric;
    }

    static ValueMetric createMetricWithState(string state) {
        ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
        metric.add_slice_by_state(StringToId(state));
        return metric;
    }

    static ValueMetric createMetricWithConditionAndState(string state) {
        ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
        metric.set_condition(StringToId("SCREEN_ON"));
        metric.add_slice_by_state(StringToId(state));
        return metric;
    }
};

// Setup for parameterized tests.
class ValueMetricProducerTest_PartialBucket : public TestWithParam<BucketSplitEvent> {};

INSTANTIATE_TEST_SUITE_P(ValueMetricProducerTest_PartialBucket,
                         ValueMetricProducerTest_PartialBucket,
                         testing::Values(APP_UPGRADE, BOOT_COMPLETE));

/*
 * Tests that the first bucket works correctly
 */
TEST(ValueMetricProducerTest, TestCalcPreviousBucketEndTime) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

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
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, -1,
                                      startTimeBase, 22, pullerManager);
    valueProducer.prepareFirstBucket();

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
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

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
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard, -1, 5,
                                      600 * NS_PER_SEC + NS_PER_SEC / 2, pullerManager);
    valueProducer.prepareFirstBucket();

    EXPECT_EQ(600500000000, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(10, valueProducer.mCurrentBucketNum);
    EXPECT_EQ(660000000005, valueProducer.getCurrentBucketEndTimeNs());
}

/*
 * Tests pulled atoms with no conditions
 */
TEST(ValueMetricProducerTest, TestPulledEventsNoCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 23));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(23, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(12, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    ASSERT_EQ(2UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
    EXPECT_EQ(12, valueProducer->mPastBuckets.begin()->second.back().values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second.back().mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket4StartTimeNs + 1, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(13, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    ASSERT_EQ(3UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
    EXPECT_EQ(12, valueProducer->mPastBuckets.begin()->second[1].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[1].mConditionTrueNs);
    EXPECT_EQ(13, valueProducer->mPastBuckets.begin()->second[2].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[2].mConditionTrueNs);
}

TEST_P(ValueMetricProducerTest_PartialBucket, TestPartialBucketCreated) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    int64_t partialBucketSplitTimeNs = bucket2StartTimeNs + 2;
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Initialize bucket.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 1));
                return true;
            }))
            // Partial bucket.
            .WillOnce(Invoke([partialBucketSplitTimeNs](
                                     int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                     vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, partialBucketSplitTimeNs);
                data->clear();
                data->push_back(
                        CreateRepeatedValueLogEvent(tagId, partialBucketSplitTimeNs + 8, 5));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    // First bucket ends.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 10, 2));
    valueProducer->onDataPulled(allData, /** success */ true, bucket2StartTimeNs);

    // Partial buckets created in 2nd bucket.
    switch (GetParam()) {
        case APP_UPGRADE:
            valueProducer->notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            valueProducer->onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }
    EXPECT_EQ(partialBucketSplitTimeNs, valueProducer->mCurrentBucketStartTimeNs);
    EXPECT_EQ(1, valueProducer->getCurrentBucketNum());

    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {1, 3},
                                    {bucketSizeNs, partialBucketSplitTimeNs - bucket2StartTimeNs},
                                    {bucketStartTimeNs, bucket2StartTimeNs},
                                    {bucket2StartTimeNs, partialBucketSplitTimeNs});
}

/*
 * Tests pulled atoms with filtering
 */
TEST(ValueMetricProducerTest, TestPulledEventsWithFiltering) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

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
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, bucketStartTimeNs, 3, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
            kConfigKey, metric, -1 /*-1 meaning no condition*/, {}, wizard, logEventMatcherIndex,
            eventMatcherWizard, tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager);
    valueProducer->prepareFirstBucket();

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 3, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket3StartTimeNs + 1, 4, 23));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // No new data seen, so data has been cleared.
    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket4StartTimeNs + 1, 3, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    // the base was reset
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second.back().values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second.back().mConditionTrueNs);
}

/*
 * Tests pulled atoms with no conditions and take absolute value after reset
 */
TEST(ValueMetricProducerTest, TestPulledEventsTakeAbsoluteValueOnReset) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_use_absolute_value_on_reset(true);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Return(true));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(10, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(10, valueProducer->mPastBuckets.begin()->second.back().values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second.back().mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket4StartTimeNs + 1, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(26, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    ASSERT_EQ(2UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(10, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
    EXPECT_EQ(26, valueProducer->mPastBuckets.begin()->second[1].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[1].mConditionTrueNs);
}

/*
 * Tests pulled atoms with no conditions and take zero value after reset
 */
TEST(ValueMetricProducerTest, TestPulledEventsTakeZeroOnReset) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Return(false));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(10, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket4StartTimeNs + 1, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(26, curInterval.value.long_value);
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(26, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
}

/*
 * Test pulled event with non sliced condition.
 */
TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 8);  // First condition change.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 100));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 1);  // Second condition change.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 130));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket3StartTimeNs + 1);  // Third condition change.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 180));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    // startUpdated:false sum:0 start:100
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(100, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 110));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});

    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(110, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);

    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 1);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});

    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);
    EXPECT_EQ(false, curBaseInfo.hasBase);

    valueProducer->onConditionChanged(true, bucket3StartTimeNs + 1);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10, 20}, {bucketSizeNs - 8, 1},
                                    {bucketStartTimeNs, bucket2StartTimeNs},
                                    {bucket2StartTimeNs, bucket3StartTimeNs});
}

TEST_P(ValueMetricProducerTest_PartialBucket, TestPushedEvents) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    int64_t partialBucketSplitTimeNs = bucketStartTimeNs + 150;
    switch (GetParam()) {
        case APP_UPGRADE:
            valueProducer.notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            valueProducer.onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {10},
                                    {partialBucketSplitTimeNs - bucketStartTimeNs},
                                    {bucketStartTimeNs}, {partialBucketSplitTimeNs});
    EXPECT_EQ(partialBucketSplitTimeNs, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(0, valueProducer.getCurrentBucketNum());

    // Event arrives after the bucket split.
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 59 * NS_PER_SEC, 20);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {10},
                                    {partialBucketSplitTimeNs - bucketStartTimeNs},
                                    {bucketStartTimeNs}, {partialBucketSplitTimeNs});
    EXPECT_EQ(partialBucketSplitTimeNs, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(0, valueProducer.getCurrentBucketNum());

    // Next value should create a new bucket.
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event3, tagId, bucket2StartTimeNs + 5 * NS_PER_SEC, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {10, 20},
                                    {partialBucketSplitTimeNs - bucketStartTimeNs,
                                     bucket2StartTimeNs - partialBucketSplitTimeNs},
                                    {bucketStartTimeNs, partialBucketSplitTimeNs},
                                    {partialBucketSplitTimeNs, bucket2StartTimeNs});
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(1, valueProducer.getCurrentBucketNum());
}

TEST_P(ValueMetricProducerTest_PartialBucket, TestPulledValue) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    int64_t partialBucketSplitTimeNs = bucket2StartTimeNs + 150;
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Return(true))
            .WillOnce(Invoke([partialBucketSplitTimeNs](
                                     int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                     vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, partialBucketSplitTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, partialBucketSplitTimeNs, 120));
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.prepareFirstBucket();

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 100));

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    switch (GetParam()) {
        case APP_UPGRADE:
            valueProducer.notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            valueProducer.onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }
    EXPECT_EQ(partialBucketSplitTimeNs, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(1, valueProducer.getCurrentBucketNum());
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {20}, {150}, {bucket2StartTimeNs},
                                    {partialBucketSplitTimeNs});

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 150));
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    EXPECT_EQ(bucket3StartTimeNs, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(2, valueProducer.getCurrentBucketNum());
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {20, 30}, {150, bucketSizeNs - 150},
                                    {bucket2StartTimeNs, partialBucketSplitTimeNs},
                                    {partialBucketSplitTimeNs, bucket3StartTimeNs});
}

TEST(ValueMetricProducerTest, TestPulledWithAppUpgradeDisabled) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_split_bucket_for_app_upgrade(false);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Return(true));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.prepareFirstBucket();

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 100));

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.notifyAppUpgrade(bucket2StartTimeNs + 150);
    ASSERT_EQ(0UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucket2StartTimeNs, valueProducer.mCurrentBucketStartTimeNs);
}

TEST_P(ValueMetricProducerTest_PartialBucket, TestPulledValueWhileConditionFalse) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 1);  // Condition change to true time.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 100));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs,
                          bucket2StartTimeNs - 100);  // Condition change to false time.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs - 100, 120));
                return true;
            }));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 1);

    valueProducer->onConditionChanged(false, bucket2StartTimeNs - 100);
    EXPECT_FALSE(valueProducer->mCondition);

    int64_t partialBucketSplitTimeNs = bucket2StartTimeNs - 50;
    switch (GetParam()) {
        case APP_UPGRADE:
            valueProducer->notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            valueProducer->onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }
    // Expect one full buckets already done and starting a partial bucket.
    EXPECT_EQ(partialBucketSplitTimeNs, valueProducer->mCurrentBucketStartTimeNs);
    EXPECT_EQ(0, valueProducer->getCurrentBucketNum());
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20},
                                    {(bucket2StartTimeNs - 100) - (bucketStartTimeNs + 1)},
                                    {bucketStartTimeNs}, {partialBucketSplitTimeNs});
    EXPECT_FALSE(valueProducer->mCondition);
}

TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 20, 20);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(30, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {30}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestPushedEventsWithCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, 0, {ConditionState::kUnknown}, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, -1,
                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);
    valueProducer.prepareFirstBucket();
    valueProducer.mCondition = ConditionState::kFalse;

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    // has 1 slice
    ASSERT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.onConditionChangedLocked(true, bucketStartTimeNs + 15);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 20, 20);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(20, curInterval.value.long_value);

    LogEvent event3(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event3, tagId, bucketStartTimeNs + 30, 30);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(50, curInterval.value.long_value);

    valueProducer.onConditionChangedLocked(false, bucketStartTimeNs + 35);

    LogEvent event4(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event4, tagId, bucketStartTimeNs + 40, 40);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event4);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(50, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {50}, {20}, {bucketStartTimeNs},
                                    {bucket2StartTimeNs});
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

    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, {},
                                      wizard, logEventMatcherIndex, eventMatcherWizard,
                                      -1 /*not pulled*/, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    sp<AnomalyTracker> anomalyTracker = valueProducer.addAnomalyTracker(alert, alarmMonitor);

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 1 * NS_PER_SEC, 10);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 2 + NS_PER_SEC, 20);

    LogEvent event3(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event3, tagId,
                                bucketStartTimeNs + 2 * bucketSizeNs + 1 * NS_PER_SEC, 130);

    LogEvent event4(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event4, tagId,
                                bucketStartTimeNs + 3 * bucketSizeNs + 1 * NS_PER_SEC, 1);

    LogEvent event5(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event5, tagId,
                                bucketStartTimeNs + 3 * bucketSizeNs + 2 * NS_PER_SEC, 150);

    LogEvent event6(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event6, tagId,
                                bucketStartTimeNs + 3 * bucketSizeNs + 10 * NS_PER_SEC, 160);

    // Two events in bucket #0.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);
    // Value sum == 30 <= 130.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    // One event in bucket #2. No alarm as bucket #0 is trashed out.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    // Value sum == 130 <= 130.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);

    // Three events in bucket #3.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event4);
    // Anomaly at event 4 since Value sum == 131 > 130!
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event4.GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event5);
    // Event 5 is within 3 sec refractory period. Thus last alarm timestamp is still event4.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event4.GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event6);
    // Anomaly at event 6 since Value sum == 160 > 130 and after refractory period.
    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
              std::ceil(1.0 * event6.GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
}

// Test value metric no condition, the pull on bucket boundary come in time and too late
TEST(ValueMetricProducerTest, TestBucketBoundaryNoCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Return(true));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    // pull 1
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    // startUpdated:true sum:0 start:11
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    // pull 2 at correct time
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 23));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    // tartUpdated:false sum:12
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(23, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {12}, {bucketSizeNs},
                                    {bucket2StartTimeNs}, {bucket3StartTimeNs});

    // pull 3 come late.
    // The previous bucket gets closed with error. (Has start value 23, no ending)
    // Another bucket gets closed with error. (No start, but ending with 36)
    // The new bucket is back to normal.
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket6StartTimeNs + 1, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket6StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    // startUpdated:false sum:12
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {12}, {bucketSizeNs},
                                    {bucket2StartTimeNs}, {bucket3StartTimeNs});
    // The 1st bucket is dropped because of no data
    // The 3rd bucket is dropped due to multiple buckets being skipped.
    ASSERT_EQ(2, valueProducer->mSkippedBuckets.size());

    EXPECT_EQ(bucketStartTimeNs, valueProducer->mSkippedBuckets[0].bucketStartTimeNs);
    EXPECT_EQ(bucket2StartTimeNs, valueProducer->mSkippedBuckets[0].bucketEndTimeNs);
    ASSERT_EQ(1, valueProducer->mSkippedBuckets[0].dropEvents.size());
    EXPECT_EQ(NO_DATA, valueProducer->mSkippedBuckets[0].dropEvents[0].reason);
    EXPECT_EQ(bucket2StartTimeNs, valueProducer->mSkippedBuckets[0].dropEvents[0].dropTimeNs);

    EXPECT_EQ(bucket3StartTimeNs, valueProducer->mSkippedBuckets[1].bucketStartTimeNs);
    EXPECT_EQ(bucket6StartTimeNs, valueProducer->mSkippedBuckets[1].bucketEndTimeNs);
    ASSERT_EQ(1, valueProducer->mSkippedBuckets[1].dropEvents.size());
    EXPECT_EQ(MULTIPLE_BUCKETS_SKIPPED, valueProducer->mSkippedBuckets[1].dropEvents[0].reason);
    EXPECT_EQ(bucket6StartTimeNs, valueProducer->mSkippedBuckets[1].dropEvents[0].dropTimeNs);
}

/*
 * Test pulled event with non sliced condition. The pull on boundary come late because the alarm
 * was delivered late.
 */
TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // condition becomes true
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 8);  // First condition change.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 100));
                return true;
            }))
            // condition becomes false
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 1);  // Second condition change.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 120));
                return true;
            }));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(100, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    // pull on bucket boundary come late, condition change happens before it
    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 1);
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
    EXPECT_EQ(false, curBaseInfo.hasBase);

    // Now the alarm is delivered.
    // since the condition turned to off before this pull finish, it has no effect
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 30, 110));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
}

/*
 * Test pulled event with non sliced condition. The pull on boundary come late, after the condition
 * change to false, and then true again. This is due to alarm delivered late.
 */
TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition2) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // condition becomes true
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 8);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 100));
                return true;
            }))
            // condition becomes false
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 1);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 120));
                return true;
            }))
            // condition becomes true again
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 25);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 25, 130));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    // startUpdated:false sum:0 start:100
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(100, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    // pull on bucket boundary come late, condition change happens before it
    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 1);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);

    // condition changed to true again, before the pull alarm is delivered
    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 25);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(130, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);

    // Now the alarm is delivered, but it is considered late, the data will be used
    // for the new bucket since it was just pulled.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 50, 140));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 50);

    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(140, curBaseInfo.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs, 160));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    assertPastBucketValuesSingleKey(
            valueProducer->mPastBuckets, {20, 30}, {bucketSizeNs - 8, bucketSizeNs - 24},
            {bucketStartTimeNs, bucket2StartTimeNs}, {bucket2StartTimeNs, bucket3StartTimeNs});
}

TEST(ValueMetricProducerTest, TestPushedAggregateMin) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_aggregation_type(ValueMetric::MIN);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 20, 20);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {10}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestPushedAggregateMax) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_aggregation_type(ValueMetric::MAX);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 20, 20);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(20, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {20}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestPushedAggregateAvg) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_aggregation_type(ValueMetric::AVG);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 20, 15);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval;
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(1, curInterval.sampleSize);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(25, curInterval.value.long_value);
    EXPECT_EQ(2, curInterval.sampleSize);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
    ASSERT_EQ(1UL, valueProducer.mPastBuckets.size());
    ASSERT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());

    EXPECT_TRUE(std::abs(valueProducer.mPastBuckets.begin()->second.back().values[0].double_value -
                         12.5) < epsilon);
}

TEST(ValueMetricProducerTest, TestPushedAggregateSum) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_aggregation_type(ValueMetric::SUM);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 20, 15);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(25, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {25}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestSkipZeroDiffOutput) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
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

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(10, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event2, tagId, bucketStartTimeNs + 15, 15);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(5, curInterval.value.long_value);

    // no change in data.
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event3, tagId, bucket2StartTimeNs + 10, 15);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);

    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(15, curBaseInfo.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(0, curInterval.value.long_value);

    LogEvent event4(/*uid=*/0, /*pid=*/0);
    CreateRepeatedValueLogEvent(&event4, tagId, bucket2StartTimeNs + 15, 15);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event4);
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(15, curBaseInfo.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(0, curInterval.value.long_value);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {5}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestSkipZeroDiffOutputMultiValue) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
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

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);
    valueProducer.prepareFirstBucket();

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateThreeValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 1, 10, 20);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateThreeValueLogEvent(&event2, tagId, bucketStartTimeNs + 15, 1, 15, 22);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer.mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(10, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(20, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);

    // has one slice
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(5, curInterval.value.long_value);
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[1];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(2, curInterval.value.long_value);

    // no change in first value field
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    CreateThreeValueLogEvent(&event3, tagId, bucket2StartTimeNs + 10, 1, 15, 25);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(15, curBaseInfo.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[1];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(25, curBaseInfo.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    LogEvent event4(/*uid=*/0, /*pid=*/0);
    CreateThreeValueLogEvent(&event4, tagId, bucket2StartTimeNs + 15, 1, 15, 29);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event4);
    ASSERT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(15, curBaseInfo.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[1];
    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(29, curBaseInfo.base.long_value);
    EXPECT_EQ(true, curInterval.hasValue);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);

    ASSERT_EQ(1UL, valueProducer.mPastBuckets.size());
    ASSERT_EQ(2UL, valueProducer.mPastBuckets.begin()->second.size());
    ASSERT_EQ(2UL, valueProducer.mPastBuckets.begin()->second[0].values.size());
    ASSERT_EQ(1UL, valueProducer.mPastBuckets.begin()->second[1].values.size());

    EXPECT_EQ(bucketSizeNs, valueProducer.mPastBuckets.begin()->second[0].mConditionTrueNs);
    EXPECT_EQ(5, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(0, valueProducer.mPastBuckets.begin()->second[0].valueIndex[0]);
    EXPECT_EQ(2, valueProducer.mPastBuckets.begin()->second[0].values[1].long_value);
    EXPECT_EQ(1, valueProducer.mPastBuckets.begin()->second[0].valueIndex[1]);

    EXPECT_EQ(bucketSizeNs, valueProducer.mPastBuckets.begin()->second[1].mConditionTrueNs);
    EXPECT_EQ(3, valueProducer.mPastBuckets.begin()->second[1].values[0].long_value);
    EXPECT_EQ(1, valueProducer.mPastBuckets.begin()->second[1].valueIndex[0]);
}

/*
 * Tests zero default base.
 */
TEST(ValueMetricProducerTest, TestUseZeroDefaultBase) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_use_zero_default_base(true);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, bucketStartTimeNs, 1, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    auto iter = valueProducer->mCurrentSlicedBucket.begin();
    auto& interval1 = iter->second[0];
    auto iterBase = valueProducer->mCurrentBaseInfo.begin();
    auto& baseInfo1 = iterBase->second[0];
    EXPECT_EQ(1, iter->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, baseInfo1.hasBase);
    EXPECT_EQ(3, baseInfo1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());
    vector<shared_ptr<LogEvent>> allData;

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 2, 4));
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    EXPECT_EQ(true, baseInfo1.hasBase);
    EXPECT_EQ(11, baseInfo1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(8, interval1.value.long_value);

    auto it = valueProducer->mCurrentSlicedBucket.begin();
    for (; it != valueProducer->mCurrentSlicedBucket.end(); it++) {
        if (it != iter) {
            break;
        }
    }
    auto itBase = valueProducer->mCurrentBaseInfo.begin();
    for (; itBase != valueProducer->mCurrentBaseInfo.end(); it++) {
        if (itBase != iterBase) {
            break;
        }
    }
    EXPECT_TRUE(it != iter);
    EXPECT_TRUE(itBase != iterBase);
    auto& interval2 = it->second[0];
    auto& baseInfo2 = itBase->second[0];
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, baseInfo2.hasBase);
    EXPECT_EQ(4, baseInfo2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_EQ(4, interval2.value.long_value);

    ASSERT_EQ(2UL, valueProducer->mPastBuckets.size());
    auto iterator = valueProducer->mPastBuckets.begin();
    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
    EXPECT_EQ(8, iterator->second[0].values[0].long_value);
    iterator++;
    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
    EXPECT_EQ(4, iterator->second[0].values[0].long_value);
}

/*
 * Tests using zero default base with failed pull.
 */
TEST(ValueMetricProducerTest, TestUseZeroDefaultBaseWithPullFailures) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_use_zero_default_base(true);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, bucketStartTimeNs, 1, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    const auto& it = valueProducer->mCurrentSlicedBucket.begin();
    ValueMetricProducer::Interval& interval1 = it->second[0];
    ValueMetricProducer::BaseInfo& baseInfo1 =
            valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat())->second[0];
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, baseInfo1.hasBase);
    EXPECT_EQ(3, baseInfo1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());
    vector<shared_ptr<LogEvent>> allData;

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 2, 4));
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    EXPECT_EQ(true, baseInfo1.hasBase);
    EXPECT_EQ(11, baseInfo1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(8, interval1.value.long_value);

    auto it2 = valueProducer->mCurrentSlicedBucket.begin();
    for (; it2 != valueProducer->mCurrentSlicedBucket.end(); it2++) {
        if (it2 != it) {
            break;
        }
    }
    EXPECT_TRUE(it2 != it);
    ValueMetricProducer::Interval& interval2 = it2->second[0];
    ValueMetricProducer::BaseInfo& baseInfo2 =
            valueProducer->mCurrentBaseInfo.find(it2->first.getDimensionKeyInWhat())->second[0];
    EXPECT_EQ(2, it2->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, baseInfo2.hasBase);
    EXPECT_EQ(4, baseInfo2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_EQ(4, interval2.value.long_value);
    ASSERT_EQ(2UL, valueProducer->mPastBuckets.size());

    // next pull somehow did not happen, skip to end of bucket 3
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket4StartTimeNs + 1, 2, 5));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);

    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    EXPECT_EQ(true, baseInfo2.hasBase);
    EXPECT_EQ(5, baseInfo2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
    ASSERT_EQ(2UL, valueProducer->mPastBuckets.size());

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket5StartTimeNs + 1, 2, 13));
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket5StartTimeNs + 1, 1, 5));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket5StartTimeNs);

    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    // Get new references now that entries have been deleted from the map
    const auto& it3 = valueProducer->mCurrentSlicedBucket.begin();
    const auto& it4 = std::next(valueProducer->mCurrentSlicedBucket.begin());
    ASSERT_EQ(it3->second.size(), 1);
    ASSERT_EQ(it4->second.size(), 1);
    ValueMetricProducer::Interval& interval3 = it3->second[0];
    ValueMetricProducer::Interval& interval4 = it4->second[0];
    ValueMetricProducer::BaseInfo& baseInfo3 =
            valueProducer->mCurrentBaseInfo.find(it3->first.getDimensionKeyInWhat())->second[0];
    ValueMetricProducer::BaseInfo& baseInfo4 =
            valueProducer->mCurrentBaseInfo.find(it4->first.getDimensionKeyInWhat())->second[0];

    EXPECT_EQ(true, baseInfo3.hasBase);
    EXPECT_EQ(5, baseInfo3.base.long_value);
    EXPECT_EQ(false, interval3.hasValue);
    EXPECT_EQ(5, interval3.value.long_value);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);

    EXPECT_EQ(true, baseInfo4.hasBase);
    EXPECT_EQ(13, baseInfo4.base.long_value);
    EXPECT_EQ(false, interval4.hasValue);
    EXPECT_EQ(8, interval4.value.long_value);

    ASSERT_EQ(2UL, valueProducer->mPastBuckets.size());
}

/*
 * Tests trim unused dimension key if no new data is seen in an entire bucket.
 */
TEST(ValueMetricProducerTest, TestTrimUnusedDimensionKey) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, bucketStartTimeNs, 1, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    auto iter = valueProducer->mCurrentSlicedBucket.begin();
    auto& interval1 = iter->second[0];
    auto iterBase = valueProducer->mCurrentBaseInfo.begin();
    auto& baseInfo1 = iterBase->second[0];
    EXPECT_EQ(1, iter->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, baseInfo1.hasBase);
    EXPECT_EQ(3, baseInfo1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 2, 4));
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 1, 11));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    EXPECT_EQ(true, baseInfo1.hasBase);
    EXPECT_EQ(11, baseInfo1.base.long_value);
    EXPECT_EQ(false, interval1.hasValue);
    EXPECT_EQ(8, interval1.value.long_value);
    EXPECT_FALSE(interval1.seenNewData);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {8}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});

    auto it = valueProducer->mCurrentSlicedBucket.begin();
    for (; it != valueProducer->mCurrentSlicedBucket.end(); it++) {
        if (it != iter) {
            break;
        }
    }
    auto itBase = valueProducer->mCurrentBaseInfo.begin();
    for (; itBase != valueProducer->mCurrentBaseInfo.end(); it++) {
        if (itBase != iterBase) {
            break;
        }
    }
    EXPECT_TRUE(it != iter);
    EXPECT_TRUE(itBase != iterBase);
    auto interval2 = it->second[0];
    auto baseInfo2 = itBase->second[0];
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, baseInfo2.hasBase);
    EXPECT_EQ(4, baseInfo2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_FALSE(interval2.seenNewData);

    // next pull somehow did not happen, skip to end of bucket 3
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket4StartTimeNs + 1, 2, 5));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    // Only one interval left. One was trimmed.
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    interval2 = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    baseInfo2 = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    EXPECT_EQ(true, baseInfo2.hasBase);
    EXPECT_EQ(5, baseInfo2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_FALSE(interval2.seenNewData);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {8}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket5StartTimeNs + 1, 2, 14));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket5StartTimeNs);

    interval2 = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    baseInfo2 = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, baseInfo2.hasBase);
    EXPECT_EQ(14, baseInfo2.base.long_value);
    EXPECT_EQ(false, interval2.hasValue);
    EXPECT_FALSE(interval2.seenNewData);
    ASSERT_EQ(2UL, valueProducer->mPastBuckets.size());
    auto iterator = valueProducer->mPastBuckets.begin();
    EXPECT_EQ(bucket4StartTimeNs, iterator->second[0].mBucketStartNs);
    EXPECT_EQ(bucket5StartTimeNs, iterator->second[0].mBucketEndNs);
    EXPECT_EQ(9, iterator->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
    iterator++;
    EXPECT_EQ(bucketStartTimeNs, iterator->second[0].mBucketStartNs);
    EXPECT_EQ(bucket2StartTimeNs, iterator->second[0].mBucketEndNs);
    EXPECT_EQ(8, iterator->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange_EndOfBucket) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    // Used by onConditionChanged.
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 8, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 100));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo& curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(100, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);

    vector<shared_ptr<LogEvent>> allData;
    valueProducer->onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 8);  // Condition change to true.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 100));
                return true;
            }))
            .WillOnce(Return(false));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo& curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(100, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());

    valueProducer->onConditionChanged(false, bucketStartTimeNs + 20);

    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullFailBeforeConditionChange) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 50));
                return false;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 1);  // Condition change to false.
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 100));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Don't directly set mCondition; the real code never does that. Go through regular code path
    // to avoid unexpected behaviors.
    // valueProducer->mCondition = ConditionState::kTrue;
    valueProducer->onConditionChanged(true, bucketStartTimeNs);

    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());

    valueProducer->onConditionChanged(false, bucketStartTimeNs + 1);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullDelayExceeded) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_condition(StringToId("SCREEN_ON"));
    metric.set_max_pull_delay_sec(0);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 1, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 120));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Max delay is set to 0 so pull will exceed max delay.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 1);
    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
}

TEST(ValueMetricProducerTest, TestResetBaseOnPullTooLate) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillRepeatedly(Return());

    ValueMetricProducer valueProducer(kConfigKey, metric, 0, {ConditionState::kUnknown}, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, tagId,
                                      bucket2StartTimeNs, bucket2StartTimeNs, pullerManager);
    valueProducer.prepareFirstBucket();
    valueProducer.mCondition = ConditionState::kFalse;

    // Event should be skipped since it is from previous bucket.
    // Pull should not be called.
    valueProducer.onConditionChanged(true, bucketStartTimeNs);
    ASSERT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());
}

TEST(ValueMetricProducerTest, TestBaseSetOnConditionChange) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 1, _, _))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 100));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);
    valueProducer->mHasGlobalBase = false;

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 1);
    valueProducer->mHasGlobalBase = true;
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(100, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
}

/*
 * Tests that a bucket is marked invalid when a condition change pull fails.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenOneConditionFailed) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First onConditionChanged
            .WillOnce(Return(false))
            // Second onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 3);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 130));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kTrue);

    // Bucket start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 110));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);

    // This will fail and should invalidate the whole bucket since we do not have all the data
    // needed to compute the metric value when the screen was on.
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 2);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 3);

    // Bucket end.
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 140));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    valueProducer->flushIfNeededLocked(bucket2StartTimeNs + 1);

    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());
    // Contains base from last pull which was successful.
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(140, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 10, false /* include partial bucket */, true,
                                FAST /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 2), dropEvent.drop_time_millis());
}

/*
 * Tests that a bucket is marked invalid when the guardrail is hit.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenGuardRailHit) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_condition(StringToId("SCREEN_ON"));

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 2, _, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                for (int i = 0; i < 2000; i++) {
                    data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, i));
                }
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 2);
    EXPECT_EQ(true, valueProducer->mCurrentBucketIsSkipped);
    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
    ASSERT_EQ(0UL, valueProducer->mSkippedBuckets.size());

    // Bucket 2 start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 1, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // First bucket added to mSkippedBuckets after flush.
    ASSERT_EQ(1UL, valueProducer->mSkippedBuckets.size());

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 10000, false /* include recent buckets */,
                                true, FAST /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::DIMENSION_GUARDRAIL_REACHED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 2), dropEvent.drop_time_millis());
}

/*
 * Tests that a bucket is marked invalid when the bucket's initial pull fails.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenInitialPullFailed) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 2);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 120));
                return true;
            }))
            // Second onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 3);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 130));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kTrue);

    // Bucket start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 110));
    valueProducer->onDataPulled(allData, /** succeed */ false, bucketStartTimeNs);

    valueProducer->onConditionChanged(false, bucketStartTimeNs + 2);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 3);

    // Bucket end.
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 140));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    valueProducer->flushIfNeededLocked(bucket2StartTimeNs + 1);

    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());
    // Contains base from last pull which was successful.
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(140, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 10000, false /* include recent buckets */,
                                true, FAST /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 2), dropEvent.drop_time_millis());
}

/*
 * Tests that a bucket is marked invalid when the bucket's final pull fails
 * (i.e. failed pull on bucket boundary).
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenLastPullFailed) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 2);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 120));
                return true;
            }))
            // Second onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 3);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 130));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kTrue);

    // Bucket start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 110));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);

    valueProducer->onConditionChanged(false, bucketStartTimeNs + 2);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 3);

    // Bucket end.
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 140));
    valueProducer->onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);

    valueProducer->flushIfNeededLocked(bucket2StartTimeNs + 1);

    ASSERT_EQ(0UL, valueProducer->mPastBuckets.size());
    // Last pull failed so base has been reset.
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer->mHasGlobalBase);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 10000, false /* include recent buckets */,
                                true, FAST /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs), dropEvent.drop_time_millis());
}

TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onDataPulled) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            // Start bucket.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    // Bucket 2 start.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 110));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());

    // Bucket 3 empty.
    allData.clear();
    allData.push_back(CreateNoValuesLogEvent(tagId, bucket3StartTimeNs + 1));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // Data has been trimmed.
    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
}

TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onConditionChanged) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 3));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);

    // Empty pull.
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 10);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
}

TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onBucketBoundary) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 1));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 11);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 2));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 12);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 5));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 11);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 12);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval& curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);

    // End of bucket
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    // Data is empty, base should be reset.
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(5, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(true, valueProducer->mHasGlobalBase);

    ASSERT_EQ(1UL, valueProducer->mPastBuckets.size());
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {1}, {bucketSizeNs - 12 + 1},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestPartialResetOnBucketBoundaries) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
    metric.set_condition(StringToId("SCREEN_ON"));

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 10, _, _))
            // First onConditionChanged
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 1));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());

    // End of bucket
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 2));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // Key 1 should be reset since in not present in the most pull.
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    auto iterator = valueProducer->mCurrentSlicedBucket.begin();
    auto baseInfoIter = valueProducer->mCurrentBaseInfo.begin();
    EXPECT_EQ(true, baseInfoIter->second[0].hasBase);
    EXPECT_EQ(2, baseInfoIter->second[0].base.long_value);
    EXPECT_EQ(false, iterator->second[0].hasValue);
    iterator++;
    baseInfoIter++;
    EXPECT_EQ(false, baseInfoIter->second[0].hasBase);
    EXPECT_EQ(1, baseInfoIter->second[0].base.long_value);
    EXPECT_EQ(false, iterator->second[0].hasValue);

    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
}

TEST_P(ValueMetricProducerTest_PartialBucket, TestFullBucketResetWhenLastBucketInvalid) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    int64_t partialBucketSplitTimeNs = bucketStartTimeNs + bucketSizeNs / 2;
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Initialization.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 1));
                return true;
            }))
            // notifyAppUpgrade.
            .WillOnce(Invoke([partialBucketSplitTimeNs](
                                     int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                     vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, partialBucketSplitTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, partialBucketSplitTimeNs, 10));
                return true;
            }));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
    ASSERT_EQ(0UL, valueProducer->mCurrentFullBucket.size());

    switch (GetParam()) {
        case APP_UPGRADE:
            valueProducer->notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            valueProducer->onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }
    EXPECT_EQ(partialBucketSplitTimeNs, valueProducer->mCurrentBucketStartTimeNs);
    EXPECT_EQ(0, valueProducer->getCurrentBucketNum());
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {9},
                                    {partialBucketSplitTimeNs - bucketStartTimeNs},
                                    {bucketStartTimeNs}, {partialBucketSplitTimeNs});
    ASSERT_EQ(1UL, valueProducer->mCurrentFullBucket.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 4));
    // Pull fails and arrives late.
    valueProducer->onDataPulled(allData, /** fails */ false, bucket3StartTimeNs + 1);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {9},
                                    {partialBucketSplitTimeNs - bucketStartTimeNs},
                                    {bucketStartTimeNs}, {partialBucketSplitTimeNs});
    ASSERT_EQ(1, valueProducer->mSkippedBuckets.size());
    ASSERT_EQ(2, valueProducer->mSkippedBuckets[0].dropEvents.size());
    EXPECT_EQ(PULL_FAILED, valueProducer->mSkippedBuckets[0].dropEvents[0].reason);
    EXPECT_EQ(MULTIPLE_BUCKETS_SKIPPED, valueProducer->mSkippedBuckets[0].dropEvents[1].reason);
    EXPECT_EQ(partialBucketSplitTimeNs, valueProducer->mSkippedBuckets[0].bucketStartTimeNs);
    EXPECT_EQ(bucket3StartTimeNs, valueProducer->mSkippedBuckets[0].bucketEndTimeNs);
    ASSERT_EQ(0UL, valueProducer->mCurrentFullBucket.size());
}

TEST(ValueMetricProducerTest, TestBucketBoundariesOnConditionChange) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Second onConditionChanged.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 10, 5));
                return true;
            }))
            // Third onConditionChanged.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket3StartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 10, 7));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(
                    pullerManager, metric, ConditionState::kUnknown);

    valueProducer->onConditionChanged(false, bucketStartTimeNs);
    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());

    // End of first bucket
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 4));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 1);
    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());

    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 10);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    auto curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    auto curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(5, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);

    valueProducer->onConditionChanged(false, bucket3StartTimeNs + 10);

    // Bucket should have been completed.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {2}, {bucketSizeNs - 10},
                                    {bucket2StartTimeNs}, {bucket3StartTimeNs});
}

TEST(ValueMetricProducerTest, TestLateOnDataPulledWithoutDiff) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_use_diff(false);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 30, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs + 30);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 20));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // Bucket should have been completed.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {30}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestLateOnDataPulledWithDiff) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            // Initialization.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 1));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 30, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs + 30);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 20));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // Bucket should have been completed.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {19}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST_P(ValueMetricProducerTest_PartialBucket, TestBucketBoundariesOnPartialBucket) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    int64_t partialBucketSplitTimeNs = bucket2StartTimeNs + 2;
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Initialization.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 1));
                return true;
            }))
            // notifyAppUpgrade.
            .WillOnce(Invoke([partialBucketSplitTimeNs](
                                     int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                     vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, partialBucketSplitTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, partialBucketSplitTimeNs, 10));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    switch (GetParam()) {
        case APP_UPGRADE:
            valueProducer->notifyAppUpgrade(partialBucketSplitTimeNs);
            break;
        case BOOT_COMPLETE:
            valueProducer->onStatsdInitCompleted(partialBucketSplitTimeNs);
            break;
    }

    // Bucket should have been completed.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {9}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestDataIsNotUpdatedWhenNoConditionChanged) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First on condition changed.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 8);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 1));
                return true;
            }))
            // Second on condition changed.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 10);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 12);

    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    auto curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    auto curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(2, curInterval.value.long_value);

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 1);

    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {2}, {2}, {bucketStartTimeNs},
                                    {bucket2StartTimeNs});
}

// TODO: b/145705635 fix or delete this test
TEST(ValueMetricProducerTest, TestBucketInvalidIfGlobalBaseIsNotSet) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First condition change.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 1));
                return true;
            }))
            // 2nd condition change.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 8);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 1));
                return true;
            }))
            // 3rd condition change.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 1));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);
    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 10);

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 3, 10));
    valueProducer->onDataPulled(allData, /** succeed */ false, bucketStartTimeNs + 3);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 20));
    valueProducer->onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);

    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 8);
    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 10);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs, 30));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // There was not global base available so all buckets are invalid.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {}, {}, {}, {});
}

TEST(ValueMetricProducerTest, TestPullNeededFastDump) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            // Initial pull.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateThreeValueLogEvent(tagId, bucketStartTimeNs, tagId, 1, 1));
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.prepareFirstBucket();

    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer.onDumpReport(bucketStartTimeNs + 10, true /* include recent buckets */, true,
                               FAST, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    // Bucket is invalid since we did not pull when dump report was called.
    ASSERT_EQ(0, report.value_metrics().data_size());
}

TEST(ValueMetricProducerTest, TestFastDumpWithoutCurrentBucket) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs, _, _))
            // Initial pull.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateThreeValueLogEvent(tagId, bucketStartTimeNs, tagId, 1, 1));
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.prepareFirstBucket();

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateThreeValueLogEvent(tagId, bucket2StartTimeNs + 1, tagId, 2, 2));
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer.onDumpReport(bucket4StartTimeNs, false /* include recent buckets */, true, FAST,
                               &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    // Previous bucket is part of the report.
    ASSERT_EQ(1, report.value_metrics().data_size());
    EXPECT_EQ(0, report.value_metrics().data(0).bucket_info(0).bucket_num());
}

TEST(ValueMetricProducerTest, TestPullNeededNoTimeConstraints) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, kConfigKey, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, kConfigKey, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Initial pull.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateThreeValueLogEvent(tagId, bucketStartTimeNs, tagId, 1, 1));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(
                        CreateThreeValueLogEvent(tagId, bucketStartTimeNs + 10, tagId, 3, 3));
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, {}, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);
    valueProducer.prepareFirstBucket();

    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer.onDumpReport(bucketStartTimeNs + 10, true /* include recent buckets */, true,
                               NO_TIME_CONSTRAINTS, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    ASSERT_EQ(1, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().data(0).bucket_info_size());
    EXPECT_EQ(2, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
}

TEST(ValueMetricProducerTest, TestPulledData_noDiff_withoutCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_use_diff(false);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 30, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 30);

    // Bucket should have been completed.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10}, {bucketSizeNs},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
}

TEST(ValueMetricProducerTest, TestPulledData_noDiff_withMultipleConditionChanges) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
    metric.set_use_diff(false);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // condition becomes true
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 8);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 30, 10));
                return true;
            }))
            // condition becomes false
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 50);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 50, 20));
                return true;
            }));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 50);
    // has one slice
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);

    // Now the alarm is delivered. Condition is off though.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 30, 110));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {50 - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
}

TEST(ValueMetricProducerTest, TestPulledData_noDiff_bucketBoundaryTrue) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
    metric.set_use_diff(false);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 8, _, _))
            // condition becomes true
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 30, 10));
                return true;
            }));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);

    // Now the alarm is delivered. Condition is off though.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 30, 30));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {30}, {bucketSizeNs - 8},
                                    {bucketStartTimeNs}, {bucket2StartTimeNs});
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(false, curBaseInfo.hasBase);
    EXPECT_EQ(false, curInterval.hasValue);
}

TEST(ValueMetricProducerTest, TestPulledData_noDiff_bucketBoundaryFalse) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
    metric.set_use_diff(false);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Now the alarm is delivered. Condition is off though.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 30, 30));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // Condition was always false.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {}, {}, {}, {});
}

TEST(ValueMetricProducerTest, TestPulledData_noDiff_withFailure) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
    metric.set_use_diff(false);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // condition becomes true
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 8);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 30, 10));
                return true;
            }))
            .WillOnce(Return(false));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 50);

    // Now the alarm is delivered. Condition is off though.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 30, 30));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // No buckets, we had a failure.
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {}, {}, {}, {});
}

/*
 * Test that DUMP_REPORT_REQUESTED dump reason is logged.
 *
 * For the bucket to be marked invalid during a dump report requested,
 * three things must be true:
 * - we want to include the current partial bucket
 * - we need a pull (metric is pulled and condition is true)
 * - the dump latency must be FAST
 */

TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenDumpReportRequested) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 20, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 20, 10));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 20);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucketStartTimeNs + 40, true /* include recent buckets */, true,
                                FAST /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 40),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::DUMP_REPORT_REQUESTED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 40), dropEvent.drop_time_millis());
}

/*
 * Test that EVENT_IN_WRONG_BUCKET dump reason is logged for a late condition
 * change event (i.e. the condition change occurs in the wrong bucket).
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenConditionEventWrongBucket) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 50, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 50, 10));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);

    // Bucket boundary pull.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 15));
    valueProducer->onDataPulled(allData, /** succeeds */ true, bucket2StartTimeNs + 1);

    // Late condition change event.
    valueProducer->onConditionChanged(false, bucket2StartTimeNs - 100);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 100, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(1, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs + 100),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(2, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::EVENT_IN_WRONG_BUCKET, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs - 100), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(1);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs + 100), dropEvent.drop_time_millis());
}

/*
 * Test that EVENT_IN_WRONG_BUCKET dump reason is logged for a late accumulate
 * event (i.e. the accumulate events call occurs in the wrong bucket).
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenAccumulateEventWrongBucket) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 50);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 50, 10));
                return true;
            }))
            // Dump report requested.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 100);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 100, 15));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);

    // Bucket boundary pull.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 15));
    valueProducer->onDataPulled(allData, /** succeeds */ true, bucket2StartTimeNs + 1);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs - 100, 20));

    // Late accumulateEvents event.
    valueProducer->accumulateEvents(allData, bucket2StartTimeNs - 100, bucket2StartTimeNs - 100);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 100, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(1, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs + 100),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::EVENT_IN_WRONG_BUCKET, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs - 100), dropEvent.drop_time_millis());
}

/*
 * Test that CONDITION_UNKNOWN dump reason is logged due to an unknown condition
 * when a metric is initialized.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenConditionUnknown) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 50);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 50, 10));
                return true;
            }))
            // Dump report requested.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10000);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 100, 15));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(
                    pullerManager, metric, ConditionState::kUnknown);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucketStartTimeNs + 10000;
    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test that PULL_FAILED dump reason is logged due to a pull failure in
 * #pullAndMatchEventsLocked.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenPullFailed) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 50);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 50, 10));
                return true;
            }))
            // Dump report requested, pull fails.
            .WillOnce(Return(false));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucketStartTimeNs + 10000;
    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test that MULTIPLE_BUCKETS_SKIPPED dump reason is logged when a log event
 * skips over more than one bucket.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenMultipleBucketsSkipped) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 10, 10));
                return true;
            }))
            // Dump report requested.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket4StartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket4StartTimeNs + 1000, 15));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);

    // Condition change event that skips forward by three buckets.
    valueProducer->onConditionChanged(false, bucket4StartTimeNs + 10);

    int64_t dumpTimeNs = bucket4StartTimeNs + 1000;

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(dumpTimeNs, true /* include current buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(2, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket4StartTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::MULTIPLE_BUCKETS_SKIPPED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucket4StartTimeNs + 10), dropEvent.drop_time_millis());

    // This bucket is skipped because a dumpReport with include current buckets is called.
    // This creates a new bucket from bucket4StartTimeNs to dumpTimeNs in which we have no data
    // since the condition is false for the entire bucket interval.
    EXPECT_EQ(NanoToMillis(bucket4StartTimeNs),
              report.value_metrics().skipped(1).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpTimeNs),
              report.value_metrics().skipped(1).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(1).drop_event_size());

    dropEvent = report.value_metrics().skipped(1).drop_event(0);
    EXPECT_EQ(BucketDropReason::NO_DATA, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(dumpTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test that BUCKET_TOO_SMALL dump reason is logged when a flushed bucket size
 * is smaller than the "min_bucket_size_nanos" specified in the metric config.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestBucketDropWhenBucketTooSmall) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
    metric.set_min_bucket_size_nanos(10000000000);  // 10 seconds

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 10, 10));
                return true;
            }))
            // Dump report requested.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 9000000);
                data->clear();
                data->push_back(
                        CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 9000000, 15));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucketStartTimeNs + 9000000;
    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::BUCKET_TOO_SMALL, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test that NO_DATA dump reason is logged when a flushed bucket contains no data.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestBucketDropWhenDataUnavailable) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(
                    pullerManager, metric, ConditionState::kFalse);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucketStartTimeNs + 10000000000; // 10 seconds
    valueProducer->onDumpReport(dumpReportTimeNs, true /* include current bucket */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::NO_DATA, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test that all buckets are dropped due to condition unknown until the first onConditionChanged.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestConditionUnknownMultipleBuckets) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 10 * NS_PER_SEC);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(
                        tagId, bucket2StartTimeNs + 10 * NS_PER_SEC, 10));
                return true;
            }))
            // Dump report requested.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 15 * NS_PER_SEC);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(
                        tagId, bucket2StartTimeNs + 15 * NS_PER_SEC, 15));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(
                    pullerManager, metric, ConditionState::kUnknown);

    // Bucket should be dropped because of condition unknown.
    int64_t appUpgradeTimeNs = bucketStartTimeNs + 5 * NS_PER_SEC;
    valueProducer->notifyAppUpgrade(appUpgradeTimeNs);

    // Bucket also dropped due to condition unknown
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 3));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    // This bucket is also dropped due to condition unknown.
    int64_t conditionChangeTimeNs = bucket2StartTimeNs + 10 * NS_PER_SEC;
    valueProducer->onConditionChanged(true, conditionChangeTimeNs);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucket2StartTimeNs + 15 * NS_PER_SEC; // 15 seconds
    valueProducer->onDumpReport(dumpReportTimeNs, true /* include current bucket */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(3, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(appUpgradeTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(appUpgradeTimeNs), dropEvent.drop_time_millis());

    EXPECT_EQ(NanoToMillis(appUpgradeTimeNs),
              report.value_metrics().skipped(1).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(1).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(1).drop_event_size());

    dropEvent = report.value_metrics().skipped(1).drop_event(0);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs), dropEvent.drop_time_millis());

    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(2).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
              report.value_metrics().skipped(2).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(2).drop_event_size());

    dropEvent = report.value_metrics().skipped(2).drop_event(0);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(conditionChangeTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test that a skipped bucket is logged when a forced bucket split occurs when the previous bucket
 * was not flushed in time.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestBucketDropWhenForceBucketSplitBeforeBucketFlush) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 10, 10));
                return true;
            }))
            // App Update.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 1000);
                data->clear();
                data->push_back(
                        CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1000, 15));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric,
                                                                            ConditionState::kFalse);

    // Condition changed event
    int64_t conditionChangeTimeNs = bucketStartTimeNs + 10;
    valueProducer->onConditionChanged(true, conditionChangeTimeNs);

    // App update event.
    int64_t appUpdateTimeNs = bucket2StartTimeNs + 1000;
    valueProducer->notifyAppUpgrade(appUpdateTimeNs);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucket2StartTimeNs + 10000000000; // 10 seconds
    valueProducer->onDumpReport(dumpReportTimeNs, false /* include current buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(1, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    ASSERT_EQ(1, report.value_metrics().data(0).bucket_info_size());
    auto data = report.value_metrics().data(0);
    ASSERT_EQ(0, data.bucket_info(0).bucket_num());
    EXPECT_EQ(5, data.bucket_info(0).values(0).value_long());

    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(appUpdateTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::NO_DATA, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(appUpdateTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test multiple bucket drop events in the same bucket.
 */
TEST(ValueMetricProducerTest_BucketDrop, TestMultipleBucketDropEvents) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, bucketStartTimeNs + 10, _, _))
            // Condition change to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 10, 10));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(
                    pullerManager, metric, ConditionState::kUnknown);

    // Condition change event.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucketStartTimeNs + 1000;
    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
                                FAST /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(2, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 10), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(1);
    EXPECT_EQ(BucketDropReason::DUMP_REPORT_REQUESTED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
}

/*
 * Test that the number of logged bucket drop events is capped at the maximum.
 * The maximum is currently 10 and is set in MetricProducer::maxDropEventsReached().
 */
TEST(ValueMetricProducerTest_BucketDrop, TestMaxBucketDropEvents) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // First condition change event.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                for (int i = 0; i < 2000; i++) {
                    data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, i));
                }
                return true;
            }))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Return(false))
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 220);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 220, 10));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(
                    pullerManager, metric, ConditionState::kUnknown);

    // First condition change event causes guardrail to be reached.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);

    // 2-10 condition change events result in failed pulls.
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 30);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 70);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 90);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 100);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 150);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 170);
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 190);
    valueProducer->onConditionChanged(false, bucketStartTimeNs + 200);

    // Condition change event 11
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 220);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucketStartTimeNs + 1000;
    // Because we already have 10 dump events in the current bucket,
    // this case should not be added to the list of dump events.
    valueProducer->onDumpReport(bucketStartTimeNs + 1000, true /* include recent buckets */, true,
                                FAST /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(10, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 10), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(1);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 30), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(2);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 50), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(3);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 70), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(4);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 90), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(5);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 100), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(6);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 150), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(7);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 170), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(8);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 190), dropEvent.drop_time_millis());

    dropEvent = report.value_metrics().skipped(0).drop_event(9);
    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 200), dropEvent.drop_time_millis());
}

/*
 * Test metric with a simple sliced state
 * - Increasing values
 * - Using diff
 * - Second field is value field
 */
TEST(ValueMetricProducerTest, TestSlicedState) {
    // Set up ValueMetricProducer.
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithState("SCREEN_STATE");
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // ValueMetricProducer initialized.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 3));
                return true;
            }))
            // Screen state change to ON.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 5);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 5, 5));
                return true;
            }))
            // Screen state change to OFF.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 10);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 10, 9));
                return true;
            }))
            // Screen state change to ON.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 15);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 15, 21));
                return true;
            }))
            // Dump report requested.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 50);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 50, 30));
                return true;
            }));

    StateManager::getInstance().clear();
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithState(
                    pullerManager, metric, {util::SCREEN_STATE_CHANGED}, {});
    EXPECT_EQ(1, valueProducer->mSlicedStateAtoms.size());

    // Set up StateManager and check that StateTrackers are initialized.
    StateManager::getInstance().registerListener(SCREEN_STATE_ATOM_ID, valueProducer);
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));

    // Bucket status after metric initialized.
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    auto it = valueProducer->mCurrentSlicedBucket.begin();
    auto itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(3, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, kStateUnknown}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Bucket status after screen state change kStateUnknown->ON.
    auto screenEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 5, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    StateManager::getInstance().onLogEvent(*screenEvent);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(5, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, kStateUnknown}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Bucket status after screen state change ON->OFF.
    screenEvent = CreateScreenStateChangedEvent(bucketStartTimeNs + 10,
                                                android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    StateManager::getInstance().onLogEvent(*screenEvent);
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(9, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_OFF,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, ON}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(4, it->second[0].value.long_value);
    // Value for dimension, state key {{}, kStateUnknown}
    it++;
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Bucket status after screen state change OFF->ON.
    screenEvent = CreateScreenStateChangedEvent(bucketStartTimeNs + 15,
                                                android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    StateManager::getInstance().onLogEvent(*screenEvent);
    ASSERT_EQ(3UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(21, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, OFF}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_OFF,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(12, it->second[0].value.long_value);
    // Value for dimension, state key {{}, ON}
    it++;
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(4, it->second[0].value.long_value);
    // Value for dimension, state key {{}, kStateUnknown}
    it++;
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Start dump report and check output.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucketStartTimeNs + 50, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(3, report.value_metrics().data_size());

    auto data = report.value_metrics().data(0);
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(2, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */, data.slice_by_state(0).value());

    data = report.value_metrics().data(1);
    ASSERT_EQ(1, report.value_metrics().data(1).bucket_info_size());
    EXPECT_EQ(13, report.value_metrics().data(1).bucket_info(0).values(0).value_long());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON, data.slice_by_state(0).value());

    data = report.value_metrics().data(2);
    ASSERT_EQ(1, report.value_metrics().data(2).bucket_info_size());
    EXPECT_EQ(12, report.value_metrics().data(2).bucket_info(0).values(0).value_long());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_OFF, data.slice_by_state(0).value());
}

/*
 * Test metric with sliced state with map
 * - Increasing values
 * - Using diff
 * - Second field is value field
 */
TEST(ValueMetricProducerTest, TestSlicedStateWithMap) {
    // Set up ValueMetricProducer.
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithState("SCREEN_STATE_ONOFF");
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // ValueMetricProducer initialized.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 3));
                return true;
            }))
            // Screen state change to ON.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 5);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 5, 5));
                return true;
            }))
            // Screen state change to VR has no pull because it is in the same
            // state group as ON.

            // Screen state change to ON has no pull because it is in the same
            // state group as VR.

            // Screen state change to OFF.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 15);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 15, 21));
                return true;
            }))
            // Dump report requested.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 50);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 50, 30));
                return true;
            }));

    const StateMap& stateMap =
            CreateScreenStateOnOffMap(/*screen on id=*/321, /*screen off id=*/123);
    const StateMap_StateGroup screenOnGroup = stateMap.group(0);
    const StateMap_StateGroup screenOffGroup = stateMap.group(1);

    unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
    for (auto group : stateMap.group()) {
        for (auto value : group.value()) {
            stateGroupMap[SCREEN_STATE_ATOM_ID][value] = group.group_id();
        }
    }

    StateManager::getInstance().clear();
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithState(
                    pullerManager, metric, {util::SCREEN_STATE_CHANGED}, stateGroupMap);

    // Set up StateManager and check that StateTrackers are initialized.
    StateManager::getInstance().registerListener(SCREEN_STATE_ATOM_ID, valueProducer);
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));

    // Bucket status after metric initialized.
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    auto it = valueProducer->mCurrentSlicedBucket.begin();
    auto itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(3, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, {kStateUnknown}}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Bucket status after screen state change kStateUnknown->ON.
    auto screenEvent = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 5, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    StateManager::getInstance().onLogEvent(*screenEvent);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(5, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(screenOnGroup.group_id(),
              itBase->second[0].currentState.getValues()[0].mValue.long_value);
    // Value for dimension, state key {{}, kStateUnknown}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Bucket status after screen state change ON->VR.
    // Both ON and VR are in the same state group, so the base should not change.
    screenEvent = CreateScreenStateChangedEvent(bucketStartTimeNs + 10,
                                                android::view::DisplayStateEnum::DISPLAY_STATE_VR);
    StateManager::getInstance().onLogEvent(*screenEvent);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(5, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(screenOnGroup.group_id(),
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, kStateUnknown}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Bucket status after screen state change VR->ON.
    // Both ON and VR are in the same state group, so the base should not change.
    screenEvent = CreateScreenStateChangedEvent(bucketStartTimeNs + 12,
                                                android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    StateManager::getInstance().onLogEvent(*screenEvent);
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(5, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(screenOnGroup.group_id(),
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, kStateUnknown}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Bucket status after screen state change VR->OFF.
    screenEvent = CreateScreenStateChangedEvent(bucketStartTimeNs + 15,
                                                android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    StateManager::getInstance().onLogEvent(*screenEvent);
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(21, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(screenOffGroup.group_id(),
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{}, ON GROUP}
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(screenOnGroup.group_id(),
              it->first.getStateValuesKey().getValues()[0].mValue.long_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(16, it->second[0].value.long_value);
    // Value for dimension, state key {{}, kStateUnknown}
    it++;
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Start dump report and check output.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucketStartTimeNs + 50, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(3, report.value_metrics().data_size());

    auto data = report.value_metrics().data(0);
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(2, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(-1 /*StateTracker::kStateUnknown*/, data.slice_by_state(0).value());

    data = report.value_metrics().data(1);
    ASSERT_EQ(1, report.value_metrics().data(1).bucket_info_size());
    EXPECT_EQ(16, report.value_metrics().data(1).bucket_info(0).values(0).value_long());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOnGroup.group_id(), data.slice_by_state(0).group_id());

    data = report.value_metrics().data(2);
    ASSERT_EQ(1, report.value_metrics().data(2).bucket_info_size());
    EXPECT_EQ(9, report.value_metrics().data(2).bucket_info(0).values(0).value_long());
    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
    EXPECT_EQ(screenOffGroup.group_id(), data.slice_by_state(0).group_id());
}

/*
 * Test metric that slices by state with a primary field and has dimensions
 * - Increasing values
 * - Using diff
 * - Second field is value field
 */
TEST(ValueMetricProducerTest, TestSlicedStateWithPrimaryField_WithDimensions) {
    // Set up ValueMetricProducer.
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithState("UID_PROCESS_STATE");
    metric.mutable_dimensions_in_what()->set_field(tagId);
    metric.mutable_dimensions_in_what()->add_child()->set_field(1);

    MetricStateLink* stateLink = metric.add_state_link();
    stateLink->set_state_atom_id(UID_PROCESS_STATE_ATOM_ID);
    auto fieldsInWhat = stateLink->mutable_fields_in_what();
    *fieldsInWhat = CreateDimensions(tagId, {1 /* uid */});
    auto fieldsInState = stateLink->mutable_fields_in_state();
    *fieldsInState = CreateDimensions(UID_PROCESS_STATE_ATOM_ID, {1 /* uid */});

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // ValueMetricProducer initialized.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs);
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, bucketStartTimeNs, 2 /*uid*/, 7));
                data->push_back(CreateTwoValueLogEvent(tagId, bucketStartTimeNs, 1 /*uid*/, 3));
                return true;
            }))
            // Uid 1 process state change from kStateUnknown -> Foreground
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 20);
                data->clear();
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucketStartTimeNs + 20, 1 /*uid*/, 6));

                // This event should be skipped.
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucketStartTimeNs + 20, 2 /*uid*/, 8));
                return true;
            }))
            // Uid 2 process state change from kStateUnknown -> Background
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 40);
                data->clear();
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucketStartTimeNs + 40, 2 /*uid*/, 9));

                // This event should be skipped.
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucketStartTimeNs + 40, 1 /*uid*/, 12));
                return true;
            }))
            // Uid 1 process state change from Foreground -> Background
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 20);
                data->clear();
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 20, 1 /*uid*/, 13));

                // This event should be skipped.
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 20, 2 /*uid*/, 11));
                return true;
            }))
            // Uid 1 process state change from Background -> Foreground
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 40);
                data->clear();
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 40, 1 /*uid*/, 17));

                // This event should be skipped.
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 40, 2 /*uid */, 15));
                return true;
            }))
            // Dump report pull.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 50);
                data->clear();
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 50, 2 /*uid*/, 20));
                data->push_back(
                        CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 50, 1 /*uid*/, 21));
                return true;
            }));

    StateManager::getInstance().clear();
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithState(
                    pullerManager, metric, {UID_PROCESS_STATE_ATOM_ID}, {});

    // Set up StateManager and check that StateTrackers are initialized.
    StateManager::getInstance().registerListener(UID_PROCESS_STATE_ATOM_ID, valueProducer);
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(UID_PROCESS_STATE_ATOM_ID));

    // Bucket status after metric initialized.
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {uid 1}.
    auto it = valueProducer->mCurrentSlicedBucket.begin();
    auto itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(3, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{uid 1}, kStateUnknown}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);
    // Base for dimension key {uid 2}
    it++;
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(7, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for dimension, state key {{uid 2}, kStateUnknown}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Bucket status after uid 1 process state change kStateUnknown -> Foreground.
    auto uidProcessEvent = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 20, 1 /* uid */, android::app::PROCESS_STATE_IMPORTANT_FOREGROUND);
    StateManager::getInstance().onLogEvent(*uidProcessEvent);
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {uid 1}.
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(6, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 1, kStateUnknown}.
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(3, it->second[0].value.long_value);

    // Base for dimension key {uid 2}
    it++;
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(7, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 2, kStateUnknown}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Bucket status after uid 2 process state change kStateUnknown -> Background.
    uidProcessEvent = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 40, 2 /* uid */, android::app::PROCESS_STATE_IMPORTANT_BACKGROUND);
    StateManager::getInstance().onLogEvent(*uidProcessEvent);
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {uid 1}.
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(6, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 1, kStateUnknown}.
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(3, it->second[0].value.long_value);

    // Base for dimension key {uid 2}
    it++;
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(9, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 2, kStateUnknown}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Pull at end of first bucket.
    vector<shared_ptr<LogEvent>> allData;
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs, 1 /*uid*/, 10));
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs, 2 /*uid*/, 15));
    valueProducer->onDataPulled(allData, /** succeeds */ true, bucket2StartTimeNs + 1);

    // Buckets flushed after end of first bucket.
    // None of the buckets should have a value.
    ASSERT_EQ(4UL, valueProducer->mCurrentSlicedBucket.size());
    ASSERT_EQ(4UL, valueProducer->mPastBuckets.size());
    ASSERT_EQ(2UL, valueProducer->mCurrentBaseInfo.size());
    // Base for dimension key {uid 2}.
    it = valueProducer->mCurrentSlicedBucket.begin();
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(15, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 2, BACKGROUND}.
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Base for dimension key {uid 1}
    it++;
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(10, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 1, kStateUnknown}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* kStateTracker::kUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Value for key {uid 1, FOREGROUND}
    it++;
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Value for key {uid 2, kStateUnknown}
    it++;
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* kStateTracker::kUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Bucket status after uid 1 process state change from Foreground -> Background.
    uidProcessEvent = CreateUidProcessStateChangedEvent(
            bucket2StartTimeNs + 20, 1 /* uid */, android::app::PROCESS_STATE_IMPORTANT_BACKGROUND);
    StateManager::getInstance().onLogEvent(*uidProcessEvent);

    ASSERT_EQ(4UL, valueProducer->mCurrentSlicedBucket.size());
    ASSERT_EQ(4UL, valueProducer->mPastBuckets.size());
    ASSERT_EQ(2UL, valueProducer->mCurrentBaseInfo.size());
    // Base for dimension key {uid 2}.
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(15, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 2, BACKGROUND}.
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);
    // Base for dimension key {uid 1}
    it++;
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(13, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 1, kStateUnknown}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);
    // Value for key {uid 1, FOREGROUND}
    it++;
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(3, it->second[0].value.long_value);
    // Value for key {uid 2, kStateUnknown}
    it++;
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Bucket status after uid 1 process state change Background->Foreground.
    uidProcessEvent = CreateUidProcessStateChangedEvent(
            bucket2StartTimeNs + 40, 1 /* uid */, android::app::PROCESS_STATE_IMPORTANT_FOREGROUND);
    StateManager::getInstance().onLogEvent(*uidProcessEvent);

    ASSERT_EQ(5UL, valueProducer->mCurrentSlicedBucket.size());
    ASSERT_EQ(2UL, valueProducer->mCurrentBaseInfo.size());
    // Base for dimension key {uid 2}
    it = valueProducer->mCurrentSlicedBucket.begin();
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(15, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 2, BACKGROUND}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Base for dimension key {uid 1}
    it++;
    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(17, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {uid 1, kStateUnknown}
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Value for key {uid 1, BACKGROUND}
    it++;
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_BACKGROUND,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(4, it->second[0].value.long_value);

    // Value for key {uid 1, FOREGROUND}
    it++;
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(android::app::PROCESS_STATE_IMPORTANT_FOREGROUND,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(3, it->second[0].value.long_value);

    // Value for key {uid 2, kStateUnknown}
    it++;
    ASSERT_EQ(1, it->first.getDimensionKeyInWhat().getValues().size());
    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /* StateTracker::kStateUnknown */,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);

    // Start dump report and check output.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 50, true /* include recent buckets */, true,
                                NO_TIME_CONSTRAINTS, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(5, report.value_metrics().data_size());

    auto data = report.value_metrics().data(0);
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(4, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND,
              data.slice_by_state(0).value());

    data = report.value_metrics().data(1);
    ASSERT_EQ(1, report.value_metrics().data(1).bucket_info_size());
    EXPECT_EQ(2, report.value_metrics().data(1).bucket_info(0).values(0).value_long());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(-1 /*StateTracker::kStateUnknown*/, data.slice_by_state(0).value());

    data = report.value_metrics().data(2);
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND,
              data.slice_by_state(0).value());
    ASSERT_EQ(2, report.value_metrics().data(2).bucket_info_size());
    EXPECT_EQ(4, report.value_metrics().data(2).bucket_info(0).values(0).value_long());
    EXPECT_EQ(7, report.value_metrics().data(2).bucket_info(1).values(0).value_long());

    data = report.value_metrics().data(3);
    ASSERT_EQ(1, report.value_metrics().data(3).bucket_info_size());
    EXPECT_EQ(3, report.value_metrics().data(3).bucket_info(0).values(0).value_long());
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(-1 /*StateTracker::kStateUnknown*/, data.slice_by_state(0).value());

    data = report.value_metrics().data(4);
    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND,
              data.slice_by_state(0).value());
    ASSERT_EQ(2, report.value_metrics().data(4).bucket_info_size());
    EXPECT_EQ(6, report.value_metrics().data(4).bucket_info(0).values(0).value_long());
    EXPECT_EQ(5, report.value_metrics().data(4).bucket_info(1).values(0).value_long());
}

TEST(ValueMetricProducerTest, TestSlicedStateWithCondition) {
    // Set up ValueMetricProducer.
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithConditionAndState(
            "BATTERY_SAVER_MODE_STATE");
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, kConfigKey, _, _, _))
            // Condition changed to true.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 20 * NS_PER_SEC);
                data->clear();
                data->push_back(
                        CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 20 * NS_PER_SEC, 3));
                return true;
            }))
            // Battery saver mode state changed to OFF.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucketStartTimeNs + 30 * NS_PER_SEC);
                data->clear();
                data->push_back(
                        CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 30 * NS_PER_SEC, 5));
                return true;
            }))
            // Condition changed to false.
            .WillOnce(Invoke([](int tagId, const ConfigKey&, const int64_t eventTimeNs,
                                vector<std::shared_ptr<LogEvent>>* data, bool) {
                EXPECT_EQ(eventTimeNs, bucket2StartTimeNs + 10 * NS_PER_SEC);
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(
                        tagId, bucket2StartTimeNs + 10 * NS_PER_SEC, 15));
                return true;
            }));

    StateManager::getInstance().clear();
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithConditionAndState(
                    pullerManager, metric, {util::BATTERY_SAVER_MODE_STATE_CHANGED}, {},
                    ConditionState::kFalse);
    EXPECT_EQ(1, valueProducer->mSlicedStateAtoms.size());

    // Set up StateManager and check that StateTrackers are initialized.
    StateManager::getInstance().registerListener(util::BATTERY_SAVER_MODE_STATE_CHANGED,
                                                 valueProducer);
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(
                         util::BATTERY_SAVER_MODE_STATE_CHANGED));

    // Bucket status after battery saver mode ON event.
    // Condition is false so we do nothing.
    unique_ptr<LogEvent> batterySaverOnEvent =
            CreateBatterySaverOnEvent(/*timestamp=*/bucketStartTimeNs + 10 * NS_PER_SEC);
    StateManager::getInstance().onLogEvent(*batterySaverOnEvent);
    EXPECT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
    EXPECT_EQ(0UL, valueProducer->mCurrentBaseInfo.size());

    // Bucket status after condition change to true.
    valueProducer->onConditionChanged(true, bucketStartTimeNs + 20 * NS_PER_SEC);
    // Base for dimension key {}
    ASSERT_EQ(1UL, valueProducer->mCurrentBaseInfo.size());
    std::unordered_map<HashableDimensionKey, std::vector<ValueMetricProducer::BaseInfo>>::iterator
            itBase = valueProducer->mCurrentBaseInfo.find(DEFAULT_DIMENSION_KEY);
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(3, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(BatterySaverModeStateChanged::ON,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {{}, -1}
    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    std::unordered_map<MetricDimensionKey, std::vector<ValueMetricProducer::Interval>>::iterator
            it = valueProducer->mCurrentSlicedBucket.begin();
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(-1 /*StateTracker::kUnknown*/,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_FALSE(it->second[0].hasValue);

    // Bucket status after battery saver mode OFF event.
    unique_ptr<LogEvent> batterySaverOffEvent =
            CreateBatterySaverOffEvent(/*timestamp=*/bucketStartTimeNs + 30 * NS_PER_SEC);
    StateManager::getInstance().onLogEvent(*batterySaverOffEvent);
    // Base for dimension key {}
    ASSERT_EQ(1UL, valueProducer->mCurrentBaseInfo.size());
    itBase = valueProducer->mCurrentBaseInfo.find(DEFAULT_DIMENSION_KEY);
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(5, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(BatterySaverModeStateChanged::OFF,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {{}, ON}
    ASSERT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
    it = valueProducer->mCurrentSlicedBucket.begin();
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(BatterySaverModeStateChanged::ON,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(2, it->second[0].value.long_value);

    // Pull at end of first bucket.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs, 11));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);

    EXPECT_EQ(2UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(3UL, valueProducer->mCurrentSlicedBucket.size());
    // Base for dimension key {}
    ASSERT_EQ(1UL, valueProducer->mCurrentBaseInfo.size());
    itBase = valueProducer->mCurrentBaseInfo.find(DEFAULT_DIMENSION_KEY);
    EXPECT_TRUE(itBase->second[0].hasBase);
    EXPECT_EQ(11, itBase->second[0].base.long_value);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(BatterySaverModeStateChanged::OFF,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);

    // Bucket 2 status after condition change to false.
    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 10 * NS_PER_SEC);
    // Base for dimension key {}
    ASSERT_EQ(1UL, valueProducer->mCurrentBaseInfo.size());
    itBase = valueProducer->mCurrentBaseInfo.find(DEFAULT_DIMENSION_KEY);
    EXPECT_FALSE(itBase->second[0].hasBase);
    EXPECT_TRUE(itBase->second[0].hasCurrentState);
    ASSERT_EQ(1, itBase->second[0].currentState.getValues().size());
    EXPECT_EQ(BatterySaverModeStateChanged::OFF,
              itBase->second[0].currentState.getValues()[0].mValue.int_value);
    // Value for key {{}, OFF}
    ASSERT_EQ(3UL, valueProducer->mCurrentSlicedBucket.size());
    it = valueProducer->mCurrentSlicedBucket.begin();
    EXPECT_EQ(0, it->first.getDimensionKeyInWhat().getValues().size());
    ASSERT_EQ(1, it->first.getStateValuesKey().getValues().size());
    EXPECT_EQ(BatterySaverModeStateChanged::OFF,
              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
    EXPECT_TRUE(it->second[0].hasValue);
    EXPECT_EQ(4, it->second[0].value.long_value);

    // Start dump report and check output.
    ProtoOutputStream output;
    std::set<string> strSet;
    valueProducer->onDumpReport(bucket2StartTimeNs + 50 * NS_PER_SEC,
                                true /* include recent buckets */, true, NO_TIME_CONSTRAINTS,
                                &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(2, report.value_metrics().data_size());

    ValueMetricData data = report.value_metrics().data(0);
    EXPECT_EQ(util::BATTERY_SAVER_MODE_STATE_CHANGED, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(BatterySaverModeStateChanged::ON, data.slice_by_state(0).value());
    ASSERT_EQ(1, data.bucket_info_size());
    EXPECT_EQ(2, data.bucket_info(0).values(0).value_long());

    data = report.value_metrics().data(1);
    EXPECT_EQ(util::BATTERY_SAVER_MODE_STATE_CHANGED, data.slice_by_state(0).atom_id());
    EXPECT_TRUE(data.slice_by_state(0).has_value());
    EXPECT_EQ(BatterySaverModeStateChanged::OFF, data.slice_by_state(0).value());
    ASSERT_EQ(2, data.bucket_info_size());
    EXPECT_EQ(6, data.bucket_info(0).values(0).value_long());
    EXPECT_EQ(4, data.bucket_info(1).values(0).value_long());
}

/*
 * Test bucket splits when condition is unknown.
 */
TEST(ValueMetricProducerTest, TestForcedBucketSplitWhenConditionUnknownSkipsBucket) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(
                    pullerManager, metric,
                    ConditionState::kUnknown);

    // App update event.
    int64_t appUpdateTimeNs = bucketStartTimeNs + 1000;
    valueProducer->notifyAppUpgrade(appUpdateTimeNs);

    // Check dump report.
    ProtoOutputStream output;
    std::set<string> strSet;
    int64_t dumpReportTimeNs = bucketStartTimeNs + 10000000000; // 10 seconds
    valueProducer->onDumpReport(dumpReportTimeNs, false /* include current buckets */, true,
                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);

    StatsLogReport report = outputStreamToProto(&output);
    EXPECT_TRUE(report.has_value_metrics());
    ASSERT_EQ(0, report.value_metrics().data_size());
    ASSERT_EQ(1, report.value_metrics().skipped_size());

    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
    EXPECT_EQ(NanoToMillis(appUpdateTimeNs),
              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
    ASSERT_EQ(1, report.value_metrics().skipped(0).drop_event_size());

    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
    EXPECT_EQ(NanoToMillis(appUpdateTimeNs), dropEvent.drop_time_millis());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
