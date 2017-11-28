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
#include "src/metrics/duration_helper/OringDurationTracker.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <set>
#include <unordered_map>
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

TEST(OringDurationTrackerTest, TestDurationOverlap) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    uint64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, false, bucketStartTimeNs,
                                 bucketSizeNs, {}, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    EXPECT_EQ((long long)eventStartTimeNs, tracker.mLastStartTime);
    tracker.noteStart("2:maps", true, eventStartTimeNs + 10, key1);  // overlapping wl
    EXPECT_EQ((long long)eventStartTimeNs, tracker.mLastStartTime);

    tracker.noteStop("2:maps", eventStartTimeNs + durationTimeNs, false);
    tracker.flushIfNeeded(eventStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(durationTimeNs, buckets[0].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationNested) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, true, bucketStartTimeNs,
                                 bucketSizeNs, {}, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    tracker.noteStart("2:maps", true, eventStartTimeNs + 10, key1);  // overlapping wl

    tracker.noteStop("2:maps", eventStartTimeNs + 2000, false);
    tracker.noteStop("2:maps", eventStartTimeNs + 2003, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(2003ULL, buckets[0].mDuration);
}

TEST(OringDurationTrackerTest, TestStopAll) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, true, bucketStartTimeNs,
                                 bucketSizeNs, {}, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    tracker.noteStart("3:maps", true, eventStartTimeNs + 10, key1);  // overlapping wl

    tracker.noteStopAll(eventStartTimeNs + 2003);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(2003ULL, buckets[0].mDuration);
}

TEST(OringDurationTrackerTest, TestCrossBucketBoundary) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    uint64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, true, bucketStartTimeNs,
                                 bucketSizeNs, {}, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    EXPECT_EQ((long long)eventStartTimeNs, tracker.mLastStartTime);
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs);
    tracker.noteStart("2:maps", true, eventStartTimeNs + 2 * bucketSizeNs, key1);
    EXPECT_EQ((long long)(bucketStartTimeNs + 2 * bucketSizeNs), tracker.mLastStartTime);

    EXPECT_EQ(2u, buckets.size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[1].mDuration);

    tracker.noteStop("2:maps", eventStartTimeNs + 2 * bucketSizeNs + 10, false);
    tracker.noteStop("2:maps", eventStartTimeNs + 2 * bucketSizeNs + 12, false);
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs + 12);
    EXPECT_EQ(2u, buckets.size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[1].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationConditionChange) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    EXPECT_CALL(*wizard, query(_, key1))  // #4
            .WillOnce(Return(ConditionState::kFalse));

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    uint64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, false, bucketStartTimeNs,
                                 bucketSizeNs, {}, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);

    tracker.onSlicedConditionMayChange(eventStartTimeNs + 5);

    tracker.noteStop("2:maps", eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(5ULL, buckets[0].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationConditionChange2) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    EXPECT_CALL(*wizard, query(_, key1))
            .Times(2)
            .WillOnce(Return(ConditionState::kFalse))
            .WillOnce(Return(ConditionState::kTrue));

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    uint64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, false, bucketStartTimeNs,
                                 bucketSizeNs, {}, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    // condition to false; record duration 5n
    tracker.onSlicedConditionMayChange(eventStartTimeNs + 5);
    // condition to true.
    tracker.onSlicedConditionMayChange(eventStartTimeNs + 1000);
    // 2nd duration: 1000ns
    tracker.noteStop("2:maps", eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(1005ULL, buckets[0].mDuration);
}

TEST(OringDurationTrackerTest, TestDurationConditionChangeNested) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    EXPECT_CALL(*wizard, query(_, key1))  // #4
            .WillOnce(Return(ConditionState::kFalse));

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, true, bucketStartTimeNs,
                                 bucketSizeNs, {}, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    tracker.noteStart("2:maps", true, eventStartTimeNs + 2, key1);

    tracker.noteStop("2:maps", eventStartTimeNs + 3, false);

    tracker.onSlicedConditionMayChange(eventStartTimeNs + 15);

    tracker.noteStop("2:maps", eventStartTimeNs + 2003, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(15ULL, buckets[0].mDuration);
}

TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp) {
    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name("1");
    alert.set_trigger_if_sum_gt(40 * NS_PER_SEC);
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
    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, true, bucketStartTimeNs,
                                 bucketSizeNs, {anomalyTracker}, buckets);

    // Nothing in the past bucket.
    tracker.noteStart("", true, eventStartTimeNs, key1);
    EXPECT_EQ((long long)(alert.trigger_if_sum_gt() + eventStartTimeNs),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, eventStartTimeNs));

    tracker.noteStop("", eventStartTimeNs + 3, false);
    EXPECT_EQ(0u, buckets.size());

    uint64_t event1StartTimeNs = eventStartTimeNs + 10;
    tracker.noteStart("1", true, event1StartTimeNs, key1);
    // No past buckets. The anomaly will happen in bucket #0.
    EXPECT_EQ((long long)(event1StartTimeNs + alert.trigger_if_sum_gt() - 3),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, event1StartTimeNs));

    uint64_t event1StopTimeNs = eventStartTimeNs + bucketSizeNs + 10;
    tracker.flushIfNeeded(event1StopTimeNs);
    tracker.noteStop("1", event1StopTimeNs, false);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(3ULL + bucketStartTimeNs + bucketSizeNs - eventStartTimeNs - 10,
              buckets[0].mDuration);

    const int64_t bucket0Duration = 3ULL + bucketStartTimeNs + bucketSizeNs - eventStartTimeNs - 10;
    const int64_t bucket1Duration = eventStartTimeNs + 10 - bucketStartTimeNs;

    // One past buckets. The anomaly will happen in bucket #1.
    uint64_t event2StartTimeNs = eventStartTimeNs + bucketSizeNs + 15;
    tracker.noteStart("1", true, event2StartTimeNs, key1);
    EXPECT_EQ((long long)(event2StartTimeNs + alert.trigger_if_sum_gt() - bucket0Duration -
                          bucket1Duration),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, event2StartTimeNs));
    tracker.noteStop("1", event2StartTimeNs + 1, false);

    // Only one past buckets is applicable. Bucket +0 should be trashed. The anomaly will happen in
    // bucket #2.
    uint64_t event3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs - 9 * NS_PER_SEC;
    tracker.noteStart("1", true, event3StartTimeNs, key1);
    EXPECT_EQ((long long)(event3StartTimeNs + alert.trigger_if_sum_gt() - bucket1Duration - 1LL),
              tracker.predictAnomalyTimestampNs(*anomalyTracker, event3StartTimeNs));
}

TEST(OringDurationTrackerTest, TestAnomalyDetection) {
    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name("1");
    alert.set_trigger_if_sum_gt(40 * NS_PER_SEC);
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
    OringDurationTracker tracker(kConfigKey, "metric", "event", wizard, 1, true /*nesting*/,
                                 bucketStartTimeNs, bucketSizeNs, {anomalyTracker}, buckets);

    tracker.noteStart("", true, eventStartTimeNs, key1);
    tracker.noteStop("", eventStartTimeNs + 10, false);
    EXPECT_EQ(anomalyTracker->mLastAlarmTimestampNs, -1);
    EXPECT_TRUE(tracker.mStarted.empty());
    EXPECT_EQ(10LL, tracker.mDuration);

    EXPECT_EQ(0u, tracker.mStarted.size());

    tracker.noteStart("", true, eventStartTimeNs + 20, key1);
    EXPECT_EQ(1u, anomalyTracker->mAlarms.size());
    EXPECT_EQ((long long)(51ULL * NS_PER_SEC),
              (long long)(anomalyTracker->mAlarms.begin()->second->timestampSec * NS_PER_SEC));
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs + 25);
    tracker.noteStop("", eventStartTimeNs + 2 * bucketSizeNs + 25, false);
    EXPECT_EQ(anomalyTracker->getSumOverPastBuckets("event"), (long long)(bucketSizeNs));
    EXPECT_EQ((long long)(eventStartTimeNs + 2 * bucketSizeNs + 25),
              anomalyTracker->mLastAlarmTimestampNs);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
