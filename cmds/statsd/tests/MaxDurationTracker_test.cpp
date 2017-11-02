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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "src/condition/ConditionWizard.h"
#include "src/metrics/duration_helper/MaxDurationTracker.h"

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

class MockConditionWizard : public ConditionWizard {
public:
    MOCK_METHOD2(
            query,
            ConditionState(const int conditionIndex,
                           const std::map<std::string, HashableDimensionKey>& conditionParameters));
};

TEST(MaxDurationTrackerTest, TestSimpleMaxDuration) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    vector<DurationBucketInfo> buckets;
    ConditionKey key1;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(wizard, -1, bucketStartTimeNs, bucketSizeNs, buckets);

    tracker.noteStart("", true, bucketStartTimeNs, key1);
    tracker.noteStop("", bucketStartTimeNs + 10);

    tracker.noteStart("", true, bucketStartTimeNs + 20, key1);
    tracker.noteStop("", bucketStartTimeNs + 40);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(20, buckets[0].duration_nanos());
}

TEST(MaxDurationTrackerTest, TestCrossBucketBoundary) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    vector<DurationBucketInfo> buckets;
    ConditionKey key1;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(wizard, -1, bucketStartTimeNs, bucketSizeNs, buckets);

    tracker.noteStart("", true, bucketStartTimeNs + 1, key1);
    tracker.flushIfNeeded(bucketStartTimeNs + (2 * bucketSizeNs) + 1);

    EXPECT_EQ(2u, buckets.size());
    EXPECT_EQ((long long)(bucketSizeNs - 1), buckets[0].duration_nanos());
    EXPECT_EQ((long long)bucketSizeNs, buckets[1].duration_nanos());
}

TEST(MaxDurationTrackerTest, TestMaxDurationWithCondition) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey key1;
    key1["APP_BACKGROUND"] = "1:maps|";

    EXPECT_CALL(*wizard, query(_, key1))  // #4
            .WillOnce(Return(ConditionState::kFalse));

    vector<DurationBucketInfo> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t durationTimeNs = 2 * 1000;

    MaxDurationTracker tracker(wizard, 1, bucketStartTimeNs, bucketSizeNs, buckets);

    tracker.noteStart("2:maps", true, eventStartTimeNs, key1);

    tracker.onSlicedConditionMayChange(eventStartTimeNs + 5);

    tracker.noteStop("2:maps", eventStartTimeNs + durationTimeNs);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1);
    EXPECT_EQ(1u, buckets.size());
    EXPECT_EQ(5, buckets[0].duration_nanos());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
