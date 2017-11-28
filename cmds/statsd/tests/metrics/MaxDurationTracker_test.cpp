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
#include "src/condition/ConditionWizard.h"
#include "src/metrics/duration_helper/MaxDurationTracker.h"

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

const ConfigKey kConfigKey(0, "test");

TEST(MaxDurationTrackerTest, TestSimpleMaxDuration) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    vector<DurationBucket> buckets;
    ConditionKey key1;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", "event", wizard, -1, false, bucketStartTimeNs,
                               bucketSizeNs, {}, buckets);

    tracker.noteStart("1", true, bucketStartTimeNs, key1);
    // Event starts again. This would not change anything as it already starts.
    tracker.noteStart("1", true, bucketStartTimeNs + 3, key1);
    // Stopped.
    tracker.noteStop("1", bucketStartTimeNs + 10, false);

    // Another event starts in this bucket.
    tracker.noteStart("2", true, bucketStartTimeNs + 20, key1);
    tracker.noteStop("2", bucketStartTimeNs + 40, false /*stop all*/);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(20ULL, buckets[0].mDuration);
}

TEST(MaxDurationTrackerTest, TestStopAll) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    vector<DurationBucket> buckets;
    ConditionKey key1;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", "event", wizard, -1, false, bucketStartTimeNs,
                               bucketSizeNs, {}, buckets);

    tracker.noteStart("1", true, bucketStartTimeNs + 1, key1);

    // Another event starts in this bucket.
    tracker.noteStart("2", true, bucketStartTimeNs + 20, key1);
    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 40);
    tracker.noteStopAll(bucketStartTimeNs + bucketSizeNs + 40);
    EXPECT_TRUE(tracker.mInfos.empty());
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[0].mDuration);

    tracker.flushIfNeeded(bucketStartTimeNs + 3 * bucketSizeNs + 40);
    EXPECT_EQ(2u, buckets.size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[0].mDuration);
    EXPECT_EQ(40ULL, buckets[1].mDuration);
}

TEST(MaxDurationTrackerTest, TestCrossBucketBoundary) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    vector<DurationBucket> buckets;
    ConditionKey key1;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", "event", wizard, -1, false, bucketStartTimeNs,
                               bucketSizeNs, {}, buckets);

    // The event starts.
    tracker.noteStart("", true, bucketStartTimeNs + 1, key1);

    // Starts again. Does not change anything.
    tracker.noteStart("", true, bucketStartTimeNs + bucketSizeNs + 1, key1);

    // The event stops at early 4th bucket.
    tracker.flushIfNeeded(bucketStartTimeNs + (3 * bucketSizeNs) + 20);
    tracker.noteStop("", bucketStartTimeNs + (3 * bucketSizeNs) + 20, false /*stop all*/);
    EXPECT_EQ(3u, buckets.size());
    EXPECT_EQ((unsigned long long)(bucketSizeNs - 1), buckets[0].mDuration);
    EXPECT_EQ((unsigned long long)bucketSizeNs, buckets[1].mDuration);
    EXPECT_EQ((unsigned long long)bucketSizeNs, buckets[2].mDuration);
}

TEST(MaxDurationTrackerTest, TestCrossBucketBoundary_nested) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    vector<DurationBucket> buckets;
    ConditionKey key1;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", "event", wizard, -1, true, bucketStartTimeNs,
                               bucketSizeNs, {}, buckets);

    // 2 starts
    tracker.noteStart("", true, bucketStartTimeNs + 1, key1);
    tracker.noteStart("", true, bucketStartTimeNs + 10, key1);
    // one stop
    tracker.noteStop("", bucketStartTimeNs + 20, false /*stop all*/);

    tracker.flushIfNeeded(bucketStartTimeNs + (2 * bucketSizeNs) + 1);

    EXPECT_EQ(2u, buckets.size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[1].mDuration);

    // real stop now.
    tracker.noteStop("", bucketStartTimeNs + (2 * bucketSizeNs) + 5, false);
    tracker.flushIfNeeded(bucketStartTimeNs + (3 * bucketSizeNs) + 1);

    EXPECT_EQ(3u, buckets.size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[1].mDuration);
    EXPECT_EQ(5ULL, buckets[2].mDuration);
}

TEST(MaxDurationTrackerTest, TestMaxDurationWithCondition) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    EXPECT_CALL(*wizard, query(_, key1))  // #4
            .WillOnce(Return(ConditionState::kFalse));

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t durationTimeNs = 2 * 1000;

    MaxDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, false, bucketStartTimeNs,
                               bucketSizeNs, {}, buckets);
    EXPECT_TRUE(tracker.mAnomalyTrackers.empty());

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);

    tracker.onSlicedConditionMayChange(eventStartTimeNs + 5);

    tracker.noteStop("2:maps", eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(5ULL, buckets[0].mDuration);
}

TEST(MaxDurationTrackerTest, TestAnomalyDetection) {
    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name("1");
    alert.set_trigger_if_sum_gt(32 * NS_PER_SEC);
    alert.set_number_of_buckets(2);
    alert.set_refractory_period_secs(1);

    vector<DurationBucket> buckets;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";
    uint64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    uint64_t eventStartTimeNs = bucketStartTimeNs + NS_PER_SEC + 1;
    uint64_t bucketSizeNs = 30 * NS_PER_SEC;

    sp<AnomalyTracker> anomalyTracker = new AnomalyTracker(alert);
    MaxDurationTracker tracker(kConfigKey, "metric", "event", wizard, -1, true, bucketStartTimeNs,
                               bucketSizeNs, {anomalyTracker}, buckets);

    tracker.noteStart("1", true, eventStartTimeNs, key1);
    tracker.noteStop("1", eventStartTimeNs + 10, false);
    EXPECT_EQ(anomalyTracker->mLastAlarmTimestampNs, -1);
    EXPECT_EQ(10LL, tracker.mDuration);

    tracker.noteStart("2", true, eventStartTimeNs + 20, key1);
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs + 3 * NS_PER_SEC);
    tracker.noteStop("2", eventStartTimeNs + 2 * bucketSizeNs + 3 * NS_PER_SEC, false);
    EXPECT_EQ((long long)(4 * NS_PER_SEC + 1LL), tracker.mDuration);
    EXPECT_EQ(anomalyTracker->mLastAlarmTimestampNs,
              (long long)(eventStartTimeNs + 2 * bucketSizeNs + 3 * NS_PER_SEC));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
