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

#include "logd/LogEvent.h"
#include "metrics_test_helper.h"
#include "src/metrics/GaugeMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
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

const ConfigKey kConfigKey(0, "test");
const int tagId = 1;
const string metricName = "test_metric";
const int64_t bucketStartTimeNs = 10000000000;
const int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;
const int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
const int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
const int64_t bucket4StartTimeNs = bucketStartTimeNs + 3 * bucketSizeNs;

TEST(GaugeMetricProducerTest, TestNoCondition) {
    GaugeMetric metric;
    metric.set_name(metricName);
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.mutable_gauge_fields()->add_field_num(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    // TODO: pending refactor of StatsPullerManager
    // For now we still need this so that it doesn't do real pulling.
    shared_ptr<MockStatsPullerManager> pullerManager =
            make_shared<StrictMock<MockStatsPullerManager>>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      tagId, tagId, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(11);
    event->init();
    allData.push_back(event);

    gaugeProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(11, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    EXPECT_EQ(0UL, gaugeProducer.mPastBuckets.size());

    allData.clear();
    std::shared_ptr<LogEvent> event2 =
            std::make_shared<LogEvent>(tagId, bucket3StartTimeNs + 10);
    event2->write(tagId);
    event2->write(25);
    event2->init();
    allData.push_back(event2);
    gaugeProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(25, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    // One dimension.
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(11L, gaugeProducer.mPastBuckets.begin()->second.back().mEvent->kv[0].value_int());
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.begin()->second.back().mBucketNum);

    gaugeProducer.flushIfNeededLocked(bucket4StartTimeNs);
    EXPECT_EQ(0UL, gaugeProducer.mCurrentSlicedBucket->size());
    // One dimension.
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(25L, gaugeProducer.mPastBuckets.begin()->second.back().mEvent->kv[0].value_int());
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.back().mBucketNum);
}

TEST(GaugeMetricProducerTest, TestWithCondition) {
    GaugeMetric metric;
    metric.set_name(metricName);
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.mutable_gauge_fields()->add_field_num(2);
    metric.set_condition("SCREEN_ON");

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    shared_ptr<MockStatsPullerManager> pullerManager =
            make_shared<StrictMock<MockStatsPullerManager>>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }));

    GaugeMetricProducer gaugeProducer(kConfigKey, metric, 1, wizard, tagId, tagId,
                                      bucketStartTimeNs, pullerManager);

    gaugeProducer.onConditionChanged(true, bucketStartTimeNs + 8);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(100, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    EXPECT_EQ(0UL, gaugeProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    gaugeProducer.onDataPulled(allData);

    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(110, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(100, gaugeProducer.mPastBuckets.begin()->second.back().mEvent->kv[0].value_int());

    gaugeProducer.onConditionChanged(false, bucket2StartTimeNs + 10);
    gaugeProducer.flushIfNeededLocked(bucket3StartTimeNs + 10);
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(110L, gaugeProducer.mPastBuckets.begin()->second.back().mEvent->kv[0].value_int());
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.begin()->second.back().mBucketNum);
}

TEST(GaugeMetricProducerTest, TestAnomalyDetection) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    shared_ptr<MockStatsPullerManager> pullerManager =
            make_shared<StrictMock<MockStatsPullerManager>>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());

    GaugeMetric metric;
    metric.set_name(metricName);
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.mutable_gauge_fields()->add_field_num(2);
    GaugeMetricProducer gaugeProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      tagId, tagId, bucketStartTimeNs, pullerManager);

    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name(metricName);
    alert.set_trigger_if_sum_gt(25);
    alert.set_number_of_buckets(2);
    sp<AnomalyTracker> anomalyTracker = new AnomalyTracker(alert, kConfigKey);
    gaugeProducer.addAnomalyTracker(anomalyTracker);

    std::shared_ptr<LogEvent> event1 = std::make_shared<LogEvent>(1, bucketStartTimeNs + 1);
    event1->write(1);
    event1->write(13);
    event1->init();

    gaugeProducer.onDataPulled({event1});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(13L, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), -1LL);

    std::shared_ptr<LogEvent> event2 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + bucketSizeNs + 10);
    event2->write(1);
    event2->write(15);
    event2->init();

    gaugeProducer.onDataPulled({event2});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(15L, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event2->GetTimestampNs());

    std::shared_ptr<LogEvent> event3 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + 2 * bucketSizeNs + 10);
    event3->write(1);
    event3->write(24);
    event3->init();

    gaugeProducer.onDataPulled({event3});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(24L, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event3->GetTimestampNs());

    // The event4 does not have the gauge field. Thus the current bucket value is 0.
    std::shared_ptr<LogEvent> event4 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + 3 * bucketSizeNs + 10);
    event4->write(1);
    event4->init();
    gaugeProducer.onDataPulled({event4});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(0, gaugeProducer.mCurrentSlicedBucket->begin()->second->kv[0].value_int());
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event3->GetTimestampNs());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
