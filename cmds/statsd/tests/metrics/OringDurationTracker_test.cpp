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

TEST(OringDurationTrackerTest, TestDurationOverlap) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    vector<DurationBucket> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(wizard, 1, false, bucketStartTimeNs, bucketSizeNs, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    tracker.noteStart("2:maps", true, eventStartTimeNs + 10, key1);  // overlapping wl

    tracker.noteStop("2:maps", eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
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

    OringDurationTracker tracker(wizard, 1, true, bucketStartTimeNs, bucketSizeNs, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    tracker.noteStart("2:maps", true, eventStartTimeNs + 10, key1);  // overlapping wl

    tracker.noteStop("2:maps", eventStartTimeNs + 2000, false);
    tracker.noteStop("2:maps", eventStartTimeNs + 2003, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(2003, buckets[0].mDuration);
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
    int64_t durationTimeNs = 2 * 1000;

    OringDurationTracker tracker(wizard, 1, false, bucketStartTimeNs, bucketSizeNs, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);

    tracker.onSlicedConditionMayChange(eventStartTimeNs + 5);

    tracker.noteStop("2:maps", eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(5, buckets[0].mDuration);
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

    OringDurationTracker tracker(wizard, 1, true, bucketStartTimeNs, bucketSizeNs, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);
    tracker.noteStart("2:maps", true, eventStartTimeNs + 2, key1);

    tracker.noteStop("2:maps", eventStartTimeNs + 3, false);

    tracker.onSlicedConditionMayChange(eventStartTimeNs + 15);

    tracker.noteStop("2:maps", eventStartTimeNs + 2003, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(15, buckets[0].mDuration);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
