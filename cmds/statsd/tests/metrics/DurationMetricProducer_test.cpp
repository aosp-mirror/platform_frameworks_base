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

#include "src/metrics/DurationMetricProducer.h"
#include "src/stats_log_util.h"
#include "metrics_test_helper.h"
#include "src/condition/ConditionWizard.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <set>
#include <unordered_map>
#include <vector>

using namespace android::os::statsd;
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

TEST(DurationMetricTrackerTest, TestNoCondition) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    uint64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);

    int tagId = 1;
    LogEvent event1(tagId, bucketStartTimeNs + 1);
    LogEvent event2(tagId, bucketStartTimeNs + bucketSizeNs + 2);

    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(
            kConfigKey, metric, -1 /*no condition*/, 1 /* start index */, 2 /* stop index */,
            3 /* stop_all index */, false /*nesting*/, wizard, dimensions, bucketStartTimeNs);

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, durationProducer.mPastBuckets.size());
    EXPECT_TRUE(durationProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                durationProducer.mPastBuckets.end());
    const auto& buckets = durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(2UL, buckets.size());
    EXPECT_EQ(bucketStartTimeNs, buckets[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[0].mBucketEndNs);
    EXPECT_EQ(bucketSizeNs - 1ULL, buckets[0].mDuration);
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets[1].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets[1].mBucketEndNs);
    EXPECT_EQ(2ULL, buckets[1].mDuration);
}

TEST(DurationMetricTrackerTest, TestNonSlicedCondition) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    uint64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;

    DurationMetric metric;
    metric.set_id(1);
    metric.set_bucket(ONE_MINUTE);
    metric.set_aggregation_type(DurationMetric_AggregationType_SUM);

    int tagId = 1;
    LogEvent event1(tagId, bucketStartTimeNs + 1);
    LogEvent event2(tagId, bucketStartTimeNs + 2);
    LogEvent event3(tagId, bucketStartTimeNs + bucketSizeNs + 1);
    LogEvent event4(tagId, bucketStartTimeNs + bucketSizeNs + 3);

    FieldMatcher dimensions;
    DurationMetricProducer durationProducer(
            kConfigKey, metric, 0 /* condition index */, 1 /* start index */, 2 /* stop index */,
            3 /* stop_all index */, false /*nesting*/, wizard, dimensions, bucketStartTimeNs);
    EXPECT_FALSE(durationProducer.mCondition);
    EXPECT_FALSE(durationProducer.isConditionSliced());

    durationProducer.onMatchedLogEvent(1 /* start index*/, event1);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event2);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(0UL, durationProducer.mPastBuckets.size());

    durationProducer.onMatchedLogEvent(1 /* start index*/, event3);
    durationProducer.onConditionChanged(true /* condition */, bucketStartTimeNs + bucketSizeNs + 2);
    durationProducer.onMatchedLogEvent(2 /* stop index*/, event4);
    durationProducer.flushIfNeededLocked(bucketStartTimeNs + 2 * bucketSizeNs + 1);
    EXPECT_EQ(1UL, durationProducer.mPastBuckets.size());
    EXPECT_TRUE(durationProducer.mPastBuckets.find(DEFAULT_METRIC_DIMENSION_KEY) !=
                durationProducer.mPastBuckets.end());
    const auto& buckets2 = durationProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY];
    EXPECT_EQ(1UL, buckets2.size());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, buckets2[0].mBucketStartNs);
    EXPECT_EQ(bucketStartTimeNs + 2 * bucketSizeNs, buckets2[0].mBucketEndNs);
    EXPECT_EQ(1ULL, buckets2[0].mDuration);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
