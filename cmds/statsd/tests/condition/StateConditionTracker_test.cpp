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

#include "src/condition/StateConditionTracker.h"
#include "tests/statsd_test_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <numeric>
#include <vector>

using std::map;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__
namespace android {
namespace os {
namespace statsd {

const int kUidProcTag = 27;

SimplePredicate getUidProcStatePredicate() {
    SimplePredicate simplePredicate;
    simplePredicate.set_start(StringToId("UidProcState"));

    simplePredicate.mutable_dimensions()->set_field(kUidProcTag);
    simplePredicate.mutable_dimensions()->add_child()->set_field(1);
    simplePredicate.mutable_dimensions()->add_child()->set_field(2);

    simplePredicate.set_count_nesting(false);
    return simplePredicate;
}

void makeUidProcStateEvent(int32_t uid, int32_t state, LogEvent* event) {
    event->write(uid);
    event->write(state);
    event->init();
}

TEST(StateConditionTrackerTest, TestStateChange) {
    int uid1 = 111;
    int uid2 = 222;

    int state1 = 1001;
    int state2 = 1002;
    unordered_map<int64_t, int> trackerNameIndexMap;
    trackerNameIndexMap[StringToId("UidProcState")] = 0;
    vector<Matcher> primaryFields;
    primaryFields.push_back(getSimpleMatcher(kUidProcTag, 1));
    StateConditionTracker tracker(ConfigKey(12, 123), 123, 0, getUidProcStatePredicate(),
                         trackerNameIndexMap, primaryFields);

    LogEvent event(kUidProcTag, 0 /*timestamp*/);
    makeUidProcStateEvent(uid1, state1, &event);

    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kMatched);
    vector<sp<ConditionTracker>> allPredicates;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    tracker.evaluateCondition(event, matcherState, allPredicates, conditionCache, changedCache);
    EXPECT_EQ(1ULL, tracker.mLastChangedToTrueDimensions.size());
    EXPECT_EQ(0ULL, tracker.mLastChangedToFalseDimensions.size());
    EXPECT_TRUE(changedCache[0]);

    changedCache[0] = false;
    conditionCache[0] = ConditionState::kNotEvaluated;
    tracker.evaluateCondition(event, matcherState, allPredicates, conditionCache, changedCache);
    EXPECT_EQ(0ULL, tracker.mLastChangedToTrueDimensions.size());
    EXPECT_EQ(0ULL, tracker.mLastChangedToFalseDimensions.size());
    EXPECT_FALSE(changedCache[0]);

    LogEvent event2(kUidProcTag, 0 /*timestamp*/);
    makeUidProcStateEvent(uid1, state2, &event2);

    changedCache[0] = false;
    conditionCache[0] = ConditionState::kNotEvaluated;
    tracker.evaluateCondition(event2, matcherState, allPredicates, conditionCache, changedCache);
    EXPECT_EQ(1ULL, tracker.mLastChangedToTrueDimensions.size());
    EXPECT_EQ(1ULL, tracker.mLastChangedToFalseDimensions.size());
    EXPECT_TRUE(changedCache[0]);

    LogEvent event3(kUidProcTag, 0 /*timestamp*/);
    makeUidProcStateEvent(uid2, state1, &event3);
    changedCache[0] = false;
    conditionCache[0] = ConditionState::kNotEvaluated;
    tracker.evaluateCondition(event3, matcherState, allPredicates, conditionCache, changedCache);
    EXPECT_EQ(1ULL, tracker.mLastChangedToTrueDimensions.size());
    EXPECT_EQ(0ULL, tracker.mLastChangedToFalseDimensions.size());
    EXPECT_TRUE(changedCache[0]);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
