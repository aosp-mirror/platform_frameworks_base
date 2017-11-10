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

#include "metrics_test_helper.h"
#include "src/metrics/ValueMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <vector>

using namespace testing;
using android::sp;
using std::set;
using std::unordered_map;
using std::vector;
using std::shared_ptr;
using std::make_shared;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

/*
 * Tests pulled atoms with no conditions
 */
TEST(ValueMetricProducerTest, TestNonDimensionalEvents) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;

    int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
    int64_t bucket3StartTimeNs = bucketStartTimeNs + 2*bucketSizeNs;

    ValueMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_value_field(2);

    int tagId = 1;

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    // TODO: pending refactor of StatsPullerManager
    // For now we still need this so that it doesn't do real pulling.
    shared_ptr<MockStatsPullerManager> pullerManager = make_shared<StrictMock<MockStatsPullerManager>>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());

    ValueMetricProducer valueProducer(metric, -1 /*-1 meaning no condition*/, wizard,tagId,
                                      bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
    event->write(1);
    event->write(11);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(1UL, curInterval.raw.size());
    // value is 11, 11
    EXPECT_EQ(11, curInterval.raw.front().first);
    EXPECT_EQ(11, curInterval.raw.front().second);
    ValueMetricProducer::Interval nextInterval = valueProducer.mNextSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(1UL, nextInterval.raw.size());
    // value is 11, 0
    EXPECT_EQ(11, nextInterval.raw.front().first);
    EXPECT_EQ(0, nextInterval.raw.front().second);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(1);
    event->write(22);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(1UL, curInterval.raw.size());
    // value is 22, 0
    EXPECT_EQ(22, curInterval.raw.front().first);
    EXPECT_EQ(0, curInterval.raw.front().second);
    EXPECT_EQ(0UL, valueProducer.mNextSlicedBucket.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(11, valueProducer.mPastBuckets.begin()->second.back().mValue);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(1);
    event->write(33);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    EXPECT_EQ(1UL, curInterval.raw.size());
    // value is 33, 0
    EXPECT_EQ(33, curInterval.raw.front().first);
    EXPECT_EQ(0, curInterval.raw.front().second);
    EXPECT_EQ(0UL, valueProducer.mNextSlicedBucket.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(11, valueProducer.mPastBuckets.begin()->second.back().mValue);
}

/*
 * Test pulled event with non sliced condition.
 */
TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;

    int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
    int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;

    ValueMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_value_field(2);
    metric.set_condition("SCREEN_ON");

    int tagId = 1;

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    shared_ptr<MockStatsPullerManager> pullerManager = make_shared<StrictMock<MockStatsPullerManager>>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Invoke([] (int tagId, vector<std::shared_ptr<LogEvent>>* data) {
        int64_t bucketStartTimeNs = 10000000000;
        int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;

        int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
        int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
        data->clear();
        shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
        event->write(1);
        event->write(100);
        event->init();
        data->push_back(event);
        return true;
    }))
    .WillOnce(Invoke([] (int tagId, vector<std::shared_ptr<LogEvent>>* data) {
        int64_t bucketStartTimeNs = 10000000000;
        int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;

        int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
        int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
        data->clear();
        shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
        event->write(1);
        event->write(120);
        event->init();
        data->push_back(event);
        return true;
    }));

    ValueMetricProducer valueProducer(metric, 1, wizard,tagId,
                                      bucketStartTimeNs, pullerManager);

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 10);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(1UL, curInterval.raw.size());
    // value is 100, 0
    EXPECT_EQ(100, curInterval.raw.front().first);
    EXPECT_EQ(0, curInterval.raw.front().second);
    EXPECT_EQ(0UL, valueProducer.mNextSlicedBucket.size());
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(1UL, curInterval.raw.size());
    // value is 110, 0
    EXPECT_EQ(110, curInterval.raw.front().first);
    EXPECT_EQ(0, curInterval.raw.front().second);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(10, valueProducer.mPastBuckets.begin()->second.back().mValue);

    valueProducer.onConditionChanged(false, bucket2StartTimeNs + 1);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(1UL, curInterval.raw.size());
    // value is 110, 120
    EXPECT_EQ(110, curInterval.raw.front().first);
    EXPECT_EQ(120, curInterval.raw.front().second);
}

TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;

    int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
    int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;

    ValueMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_value_field(2);

    int tagId = 1;

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    shared_ptr<MockStatsPullerManager> pullerManager = make_shared<StrictMock<MockStatsPullerManager>>();

    ValueMetricProducer valueProducer(metric, -1, wizard,-1,
                                      bucketStartTimeNs, pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event2->write(1);
    event2->write(20);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1, false);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(1UL, curInterval.raw.size());
    // value is 10, 0
    EXPECT_EQ(10, curInterval.raw.front().first);
    EXPECT_EQ(0, curInterval.raw.front().second);
    EXPECT_EQ(0UL, valueProducer.mNextSlicedBucket.size());

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2, false);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // has one raw pair
    EXPECT_EQ(2UL, curInterval.raw.size());
    // value is 10, 20
    EXPECT_EQ(10, curInterval.raw.front().first);
    EXPECT_EQ(20, curInterval.raw.back().first);
    EXPECT_EQ(0UL, valueProducer.mNextSlicedBucket.size());

    valueProducer.flushIfNeeded(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(30, valueProducer.mPastBuckets.begin()->second.back().mValue);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
