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

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

TEST(GaugeMetricProducerTest, TestWithCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;

    GaugeMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_gauge_field(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    GaugeMetricProducer gaugeProducer(metric, 1 /*has condition*/, wizard, -1, bucketStartTimeNs);

    vector<std::shared_ptr<LogEvent>> allData;
    std::shared_ptr<LogEvent> event1 = std::make_shared<LogEvent>(1, bucketStartTimeNs + 1);
    event1->write(1);
    event1->write(13);
    event1->init();
    allData.push_back(event1);

    std::shared_ptr<LogEvent> event2 = std::make_shared<LogEvent>(1, bucketStartTimeNs + 10);
    event2->write(1);
    event2->write(15);
    event2->init();
    allData.push_back(event2);

    gaugeProducer.onDataPulled(allData);
    gaugeProducer.flushIfNeeded(event2->GetTimestampNs() + 1);
    EXPECT_EQ(0UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(0UL, gaugeProducer.mPastBuckets.size());

    gaugeProducer.onConditionChanged(true, bucketStartTimeNs + 11);
    gaugeProducer.onConditionChanged(false, bucketStartTimeNs + 21);
    gaugeProducer.onConditionChanged(true, bucketStartTimeNs + bucketSizeNs + 11);
    std::shared_ptr<LogEvent> event3 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + 2 * bucketSizeNs + 10);
    event3->write(1);
    event3->write(25);
    event3->init();
    allData.push_back(event3);
    gaugeProducer.onDataPulled(allData);
    gaugeProducer.flushIfNeeded(bucketStartTimeNs + 2 * bucketSizeNs + 10);
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(25L, gaugeProducer.mCurrentSlicedBucket->begin()->second);
    // One dimension.
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, gaugeProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(25L, gaugeProducer.mPastBuckets.begin()->second.front().mGauge);
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.front().mBucketNum);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              gaugeProducer.mPastBuckets.begin()->second.front().mBucketStartNs);
}

TEST(GaugeMetricProducerTest, TestNoCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;

    GaugeMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_gauge_field(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    GaugeMetricProducer gaugeProducer(metric, -1 /*no condition*/, wizard, -1, bucketStartTimeNs);

    vector<std::shared_ptr<LogEvent>> allData;
    std::shared_ptr<LogEvent> event1 = std::make_shared<LogEvent>(1, bucketStartTimeNs + 1);
    event1->write(1);
    event1->write(13);
    event1->init();
    allData.push_back(event1);

    std::shared_ptr<LogEvent> event2 = std::make_shared<LogEvent>(1, bucketStartTimeNs + 10);
    event2->write(1);
    event2->write(15);
    event2->init();
    allData.push_back(event2);

    std::shared_ptr<LogEvent> event3 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + 2 * bucketSizeNs + 10);
    event3->write(1);
    event3->write(25);
    event3->init();
    allData.push_back(event3);

    gaugeProducer.onDataPulled(allData);
    // Has one slice
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(25L, gaugeProducer.mCurrentSlicedBucket->begin()->second);
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(13L, gaugeProducer.mPastBuckets.begin()->second.front().mGauge);
    EXPECT_EQ(0UL, gaugeProducer.mPastBuckets.begin()->second.front().mBucketNum);
    EXPECT_EQ(25L, gaugeProducer.mPastBuckets.begin()->second.back().mGauge);
    EXPECT_EQ(2UL, gaugeProducer.mPastBuckets.begin()->second.back().mBucketNum);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs,
              gaugeProducer.mPastBuckets.begin()->second.back().mBucketStartNs);
}

TEST(GaugeMetricProducerTest, TestAnomalyDetection) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    GaugeMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_gauge_field(2);
    GaugeMetricProducer gaugeProducer(metric, -1 /*no condition*/, wizard, -1, bucketStartTimeNs);

    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name("1");
    alert.set_trigger_if_sum_gt(25);
    alert.set_number_of_buckets(2);
    sp<AnomalyTracker> anomalyTracker = new AnomalyTracker(alert, bucketSizeNs);
    gaugeProducer.addAnomalyTracker(anomalyTracker);

    std::shared_ptr<LogEvent> event1 = std::make_shared<LogEvent>(1, bucketStartTimeNs + 1);
    event1->write(1);
    event1->write(13);
    event1->init();

    gaugeProducer.onDataPulled({event1});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(13L, gaugeProducer.mCurrentSlicedBucket->begin()->second);
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), -1LL);

    std::shared_ptr<LogEvent> event2 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + bucketSizeNs + 10);
    event2->write(1);
    event2->write(15);
    event2->init();

    gaugeProducer.onDataPulled({event2});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(15L, gaugeProducer.mCurrentSlicedBucket->begin()->second);
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event2->GetTimestampNs());

    std::shared_ptr<LogEvent> event3 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + 2 * bucketSizeNs + 10);
    event3->write(1);
    event3->write(24);
    event3->init();

    gaugeProducer.onDataPulled({event3});
    EXPECT_EQ(1UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(24L, gaugeProducer.mCurrentSlicedBucket->begin()->second);
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event3->GetTimestampNs());

    // The event4 does not have the gauge field. Thus the current bucket value is 0.
    std::shared_ptr<LogEvent> event4 =
            std::make_shared<LogEvent>(1, bucketStartTimeNs + 3 * bucketSizeNs + 10);
    event4->write(1);
    event4->init();
    gaugeProducer.onDataPulled({event4});
    EXPECT_EQ(0UL, gaugeProducer.mCurrentSlicedBucket->size());
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event3->GetTimestampNs());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
