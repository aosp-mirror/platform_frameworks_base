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
#include "src/metrics/CountMetricProducer.h"

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

const ConfigKey kConfigKey(0, "test");

TEST(CountMetricProducerTest, TestNonDimensionalEvents) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
    int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
    int tagId = 1;

    CountMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);

    LogEvent event1(tagId, bucketStartTimeNs + 1);
    LogEvent event2(tagId, bucketStartTimeNs + 2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    CountMetricProducer countProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs);

    // 2 events in bucket 1.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1, false);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2, false);

    // Flushes at event #2.
    countProducer.flushIfNeededLocked(bucketStartTimeNs + 2);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    // Flushes.
    countProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    const auto& buckets = countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    EXPECT_EQ(bucketStartTimeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(2LL, buckets[0].mCount);

    // 1 matched event happens in bucket 2.
    LogEvent event3(tagId, bucketStartTimeNs + bucketSizeNs + 2);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event3, false);
    countProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    EXPECT_EQ(2UL, countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY].size());
    const auto& bucketInfo2 = countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY][1];
    EXPECT_EQ(bucket2StartTimeNs, bucketInfo2.mBucketStartNs);
    EXPECT_EQ(bucket2StartTimeNs + bucketSizeNs, bucketInfo2.mBucketEndNs);
    EXPECT_EQ(1LL, bucketInfo2.mCount);

    // nothing happens in bucket 3. we should not record anything for bucket 3.
    countProducer.flushIfNeededLocked(bucketStartTimeNs + 3 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    const auto& buckets3 = countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY];
    EXPECT_EQ(2UL, buckets3.size());
}

TEST(CountMetricProducerTest, TestEventsWithNonSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    CountMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_condition("SCREEN_ON");

    LogEvent event1(1, bucketStartTimeNs + 1);
    LogEvent event2(1, bucketStartTimeNs + 10);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    CountMetricProducer countProducer(kConfigKey, metric, 1, wizard, bucketStartTimeNs);

    countProducer.onConditionChanged(true, bucketStartTimeNs);
    countProducer.onMatchedLogEvent(1 /*matcher index*/, event1, false /*pulled*/);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.onConditionChanged(false /*new condition*/, bucketStartTimeNs + 2);
    // Upon this match event, the matched event1 is flushed.
    countProducer.onMatchedLogEvent(1 /*matcher index*/, event2, false /*pulled*/);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    {
        const auto& buckets = countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY];
        EXPECT_EQ(1UL, buckets.size());
        const auto& bucketInfo = buckets[0];
        EXPECT_EQ(bucketStartTimeNs, bucketInfo.mBucketStartNs);
        EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.mBucketEndNs);
        EXPECT_EQ(1LL, bucketInfo.mCount);
    }
}

TEST(CountMetricProducerTest, TestEventsWithSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    CountMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_condition("APP_IN_BACKGROUND_PER_UID_AND_SCREEN_ON");
    MetricConditionLink* link = metric.add_links();
    link->set_condition("APP_IN_BACKGROUND_PER_UID");
    link->add_key_in_what()->set_key(1);
    link->add_key_in_condition()->set_key(2);

    LogEvent event1(1, bucketStartTimeNs + 1);
    event1.write("111");  // uid
    event1.init();
    ConditionKey key1;
    key1["APP_IN_BACKGROUND_PER_UID"] = "2:111|";

    LogEvent event2(1, bucketStartTimeNs + 10);
    event2.write("222");  // uid
    event2.init();
    ConditionKey key2;
    key2["APP_IN_BACKGROUND_PER_UID"] = "2:222|";

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    EXPECT_CALL(*wizard, query(_, key1)).WillOnce(Return(ConditionState::kFalse));

    EXPECT_CALL(*wizard, query(_, key2)).WillOnce(Return(ConditionState::kTrue));

    CountMetricProducer countProducer(kConfigKey, metric, 1 /*condition tracker index*/, wizard,
                                      bucketStartTimeNs);

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1, false);
    countProducer.flushIfNeededLocked(bucketStartTimeNs + 1);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2, false);
    countProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    const auto& buckets = countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    const auto& bucketInfo = buckets[0];
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.mBucketEndNs);
    EXPECT_EQ(1LL, bucketInfo.mCount);
}

TEST(CountMetricProducerTest, TestAnomalyDetection) {
    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name("1");
    alert.set_trigger_if_sum_gt(2);
    alert.set_number_of_buckets(2);
    alert.set_refractory_period_secs(1);

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * NS_PER_SEC;
    int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
    int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;

    sp<AnomalyTracker> anomalyTracker = new AnomalyTracker(alert);

    CountMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    CountMetricProducer countProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs);
    countProducer.addAnomalyTracker(anomalyTracker);

    int tagId = 1;
    LogEvent event1(tagId, bucketStartTimeNs + 1);
    LogEvent event2(tagId, bucketStartTimeNs + 2);
    LogEvent event3(tagId, bucketStartTimeNs + 2 * bucketSizeNs + 1);
    LogEvent event4(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 1);
    LogEvent event5(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 2);
    LogEvent event6(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 3);
    LogEvent event7(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 3 + NS_PER_SEC);

    // Two events in bucket #0.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1, false);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2, false);

    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(2L, countProducer.mCurrentSlicedCounter->begin()->second);
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), -1LL);

    // One event in bucket #2. No alarm as bucket #0 is trashed out.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event3, false);
    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(1L, countProducer.mCurrentSlicedCounter->begin()->second);
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), -1LL);

    // Two events in bucket #3.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event4, false);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event5, false);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event6, false);
    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(3L, countProducer.mCurrentSlicedCounter->begin()->second);
    // Anomaly at event 6 is within refractory period. The alarm is at event 5 timestamp not event 6
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event5.GetTimestampNs());

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event7, false);
    EXPECT_EQ(1UL, countProducer.mCurrentSlicedCounter->size());
    EXPECT_EQ(4L, countProducer.mCurrentSlicedCounter->begin()->second);
    EXPECT_EQ(anomalyTracker->getLastAlarmTimestampNs(), (long long)event7.GetTimestampNs());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
