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

    CountMetricProducer countProducer(metric, -1 /*-1 meaning no condition*/, wizard,
                                      bucketStartTimeNs);

    // 2 events in bucket 1.
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1, false);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2, false);
    countProducer.flushCounterIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    const auto& buckets = countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets.size());
    const auto& bucketInfo = buckets[0];
    EXPECT_EQ(bucketStartTimeNs, bucketInfo.mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, bucketInfo.mBucketEndNs);
    EXPECT_EQ(2LL, bucketInfo.mCount);

    // 1 matched event happens in bucket 2.
    LogEvent event3(tagId, bucketStartTimeNs + bucketSizeNs + 2);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event3, false);
    countProducer.flushCounterIfNeeded(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, countProducer.mPastBuckets.size());
    EXPECT_TRUE(countProducer.mPastBuckets.find(DEFAULT_DIMENSION_KEY) !=
                countProducer.mPastBuckets.end());
    EXPECT_EQ(2UL, countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY].size());
    const auto& bucketInfo2 = countProducer.mPastBuckets[DEFAULT_DIMENSION_KEY][1];
    EXPECT_EQ(bucket2StartTimeNs, bucketInfo2.mBucketStartNs);
    EXPECT_EQ(bucket2StartTimeNs + bucketSizeNs, bucketInfo2.mBucketEndNs);
    EXPECT_EQ(1LL, bucketInfo2.mCount);

    // nothing happens in bucket 3. we should not record anything for bucket 3.
    countProducer.flushCounterIfNeeded(bucketStartTimeNs + 3 * bucketSizeNs + 1);
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

    CountMetricProducer countProducer(metric, 1, wizard, bucketStartTimeNs);

    countProducer.onConditionChanged(true, bucketStartTimeNs);
    countProducer.onMatchedLogEvent(1 /*matcher index*/, event1, false /*pulled*/);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.onConditionChanged(false /*new condition*/, bucketStartTimeNs + 2);
    countProducer.onMatchedLogEvent(1 /*matcher index*/, event2, false /*pulled*/);
    EXPECT_EQ(0UL, countProducer.mPastBuckets.size());

    countProducer.flushCounterIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);

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

TEST(CountMetricProducerTest, TestEventsWithSlicedCondition) {
    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    CountMetric metric;
    metric.set_name("1");
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_condition("APP_IN_BACKGROUND_PER_UID_AND_SCREEN_ON");
    EventConditionLink* link = metric.add_links();
    link->set_condition("APP_IN_BACKGROUND_PER_UID");
    link->add_key_in_main()->set_key(1);
    link->add_key_in_condition()->set_key(2);

    LogEvent event1(1, bucketStartTimeNs + 1);
    auto list = event1.GetAndroidLogEventList();
    *list << "111";  // uid
    event1.init();
    ConditionKey key1;
    key1["APP_IN_BACKGROUND_PER_UID"] = "2:111|";

    LogEvent event2(1, bucketStartTimeNs + 10);
    auto list2 = event2.GetAndroidLogEventList();
    *list2 << "222";  // uid
    event2.init();
    ConditionKey key2;
    key2["APP_IN_BACKGROUND_PER_UID"] = "2:222|";

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    EXPECT_CALL(*wizard, query(_, key1)).WillOnce(Return(ConditionState::kFalse));

    EXPECT_CALL(*wizard, query(_, key2)).WillOnce(Return(ConditionState::kTrue));

    CountMetricProducer countProducer(metric, 1 /*condition tracker index*/, wizard,
                                      bucketStartTimeNs);

    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event1, false);
    countProducer.onMatchedLogEvent(1 /*log matcher index*/, event2, false);

    countProducer.flushCounterIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);

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

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
