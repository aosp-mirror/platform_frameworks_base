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
#include "src/condition/SimpleConditionTracker.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <vector>

using std::map;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

SimpleCondition getWakeLockHeldCondition(bool countNesting, bool defaultFalse,
                                         bool outputSlicedUid) {
    SimpleCondition simpleCondition;
    simpleCondition.set_start("WAKE_LOCK_ACQUIRE");
    simpleCondition.set_stop("WAKE_LOCK_RELEASE");
    simpleCondition.set_stop_all("RELEASE_ALL");
    if (outputSlicedUid) {
        KeyMatcher* keyMatcher = simpleCondition.add_dimension();
        keyMatcher->set_key(1);
    }

    simpleCondition.set_count_nesting(countNesting);
    simpleCondition.set_initial_value(defaultFalse ? SimpleCondition_InitialValue_FALSE
                                                       : SimpleCondition_InitialValue_UNKNOWN);
    return simpleCondition;
}

void makeWakeLockEvent(LogEvent* event, int uid, const string& wl, int acquire) {
    event->write(uid);  // uid
    event->write(wl);
    event->write(acquire);
    event->init();
}

map<string, HashableDimensionKey> getWakeLockQueryKey(int key, int uid,
                                                      const string& conditionName) {
    // test query
    KeyValuePair kv1;
    kv1.set_key(key);
    kv1.set_value_int(uid);
    vector<KeyValuePair> kv_list;
    kv_list.push_back(kv1);
    map<string, HashableDimensionKey> queryKey;
    queryKey[conditionName] = getHashableKey(kv_list);
    return queryKey;
}

TEST(SimpleConditionTrackerTest, TestNonSlicedCondition) {
    SimpleCondition simpleCondition;
    simpleCondition.set_start("SCREEN_TURNED_ON");
    simpleCondition.set_stop("SCREEN_TURNED_OFF");
    simpleCondition.set_count_nesting(false);
    simpleCondition.set_initial_value(SimpleCondition_InitialValue_UNKNOWN);

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["SCREEN_TURNED_ON"] = 0;
    trackerNameIndexMap["SCREEN_TURNED_OFF"] = 1;

    SimpleConditionTracker conditionTracker("SCREEN_IS_ON", 0 /*tracker index*/, simpleCondition,
                                            trackerNameIndexMap);

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);

    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);

    vector<sp<ConditionTracker>> allConditions;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);
    // not matched start or stop. condition doesn't change
    EXPECT_EQ(ConditionState::kUnknown, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // prepare a case for match start.
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);
    // now condition should change to true.
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);

    // match nothing.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // the case for match stop.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);

    // condition changes to false.
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);

    // match stop again.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);
    // condition should still be false. not changed.
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);
}

TEST(SimpleConditionTrackerTest, TestNonSlicedConditionNestCounting) {
    SimpleCondition simpleCondition;
    simpleCondition.set_start("SCREEN_TURNED_ON");
    simpleCondition.set_stop("SCREEN_TURNED_OFF");
    simpleCondition.set_count_nesting(true);

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["SCREEN_TURNED_ON"] = 0;
    trackerNameIndexMap["SCREEN_TURNED_OFF"] = 1;

    SimpleConditionTracker conditionTracker("SCREEN_IS_ON", 0 /*condition tracker index*/,
                                            simpleCondition, trackerNameIndexMap);

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);

    // one matched start
    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    vector<sp<ConditionTracker>> allConditions;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);

    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);

    // prepare for another matched start.
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);

    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // ONE MATCHED STOP
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);
    // result should still be true
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // ANOTHER MATCHED STOP
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);
    // result should still be true
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);
}

TEST(SimpleConditionTrackerTest, TestSlicedCondition) {
    SimpleCondition simpleCondition = getWakeLockHeldCondition(
            true /*nesting*/, true /*default to false*/, true /*output slice by uid*/);
    string conditionName = "WL_HELD_BY_UID2";

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["WAKE_LOCK_ACQUIRE"] = 0;
    trackerNameIndexMap["WAKE_LOCK_RELEASE"] = 1;
    trackerNameIndexMap["RELEASE_ALL"] = 2;

    SimpleConditionTracker conditionTracker(conditionName, 0 /*condition tracker index*/,
                                            simpleCondition, trackerNameIndexMap);
    int uid = 111;

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event, uid, "wl1", 1);

    // one matched start
    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    vector<sp<ConditionTracker>> allConditions;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);

    EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // Now test query
    const auto queryKey = getWakeLockQueryKey(1, uid, conditionName);
    conditionCache[0] = ConditionState::kNotEvaluated;

    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);

    // another wake lock acquired by this uid
    LogEvent event2(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event2, uid, "wl2", 1);
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event2, matcherState, allConditions, conditionCache,
                                       changedCache);
    EXPECT_FALSE(changedCache[0]);

    // wake lock 1 release
    LogEvent event3(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event3, uid, "wl1", 0);  // now release it.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event3, matcherState, allConditions, conditionCache,
                                       changedCache);
    // nothing changes, because wake lock 2 is still held for this uid
    EXPECT_FALSE(changedCache[0]);

    LogEvent event4(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event4, uid, "wl2", 0);  // now release it.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event4, matcherState, allConditions, conditionCache,
                                       changedCache);
    EXPECT_EQ(0UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // query again
    conditionCache[0] = ConditionState::kNotEvaluated;
    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
}

TEST(SimpleConditionTrackerTest, TestSlicedWithNoOutputDim) {
    SimpleCondition simpleCondition = getWakeLockHeldCondition(
            true /*nesting*/, true /*default to false*/, false /*slice output by uid*/);
    string conditionName = "WL_HELD";

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["WAKE_LOCK_ACQUIRE"] = 0;
    trackerNameIndexMap["WAKE_LOCK_RELEASE"] = 1;
    trackerNameIndexMap["RELEASE_ALL"] = 2;

    SimpleConditionTracker conditionTracker(conditionName, 0 /*condition tracker index*/,
                                            simpleCondition, trackerNameIndexMap);
    int uid1 = 111;
    string uid1_wl1 = "wl1_1";
    int uid2 = 222;
    string uid2_wl1 = "wl2_1";

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event, uid1, uid1_wl1, 1);

    // one matched start for uid1
    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    vector<sp<ConditionTracker>> allConditions;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);

    EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // Now test query
    map<string, HashableDimensionKey> queryKey;
    conditionCache[0] = ConditionState::kNotEvaluated;

    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);

    // another wake lock acquired by this uid
    LogEvent event2(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event2, uid2, uid2_wl1, 1);
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event2, matcherState, allConditions, conditionCache,
                                       changedCache);
    EXPECT_FALSE(changedCache[0]);

    // uid1 wake lock 1 release
    LogEvent event3(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event3, uid1, uid1_wl1, 0);  // now release it.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event3, matcherState, allConditions, conditionCache,
                                       changedCache);
    // nothing changes, because uid2 is still holding wl.
    EXPECT_FALSE(changedCache[0]);

    LogEvent event4(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event4, uid2, uid2_wl1, 0);  // now release it.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event4, matcherState, allConditions, conditionCache,
                                       changedCache);
    EXPECT_EQ(0UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // query again
    conditionCache[0] = ConditionState::kNotEvaluated;
    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
}

TEST(SimpleConditionTrackerTest, TestStopAll) {
    SimpleCondition simpleCondition = getWakeLockHeldCondition(
            true /*nesting*/, true /*default to false*/, true /*output slice by uid*/);
    string conditionName = "WL_HELD_BY_UID3";

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["WAKE_LOCK_ACQUIRE"] = 0;
    trackerNameIndexMap["WAKE_LOCK_RELEASE"] = 1;
    trackerNameIndexMap["RELEASE_ALL"] = 2;

    SimpleConditionTracker conditionTracker(conditionName, 0 /*condition tracker index*/,
                                            simpleCondition, trackerNameIndexMap);
    int uid1 = 111;
    int uid2 = 222;

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event, uid1, "wl1", 1);

    // one matched start
    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    vector<sp<ConditionTracker>> allConditions;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allConditions, conditionCache,
                                       changedCache);

    EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // Now test query
    const auto queryKey = getWakeLockQueryKey(1, uid1, conditionName);
    conditionCache[0] = ConditionState::kNotEvaluated;

    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);

    // another wake lock acquired by uid2
    LogEvent event2(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event2, uid2, "wl2", 1);
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event2, matcherState, allConditions, conditionCache,
                                       changedCache);
    EXPECT_EQ(2UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // TEST QUERY
    const auto queryKey2 = getWakeLockQueryKey(1, uid2, conditionName);
    conditionCache[0] = ConditionState::kNotEvaluated;

    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);


    // stop all event
    LogEvent event3(2 /*tagId*/, 0 /*timestamp*/);
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);

    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event3, matcherState, allConditions, conditionCache,
                                       changedCache);
    EXPECT_TRUE(changedCache[0]);
    EXPECT_EQ(0UL, conditionTracker.mSlicedConditionState.size());

    // TEST QUERY
    const auto queryKey3 = getWakeLockQueryKey(1, uid1, conditionName);
    conditionCache[0] = ConditionState::kNotEvaluated;

    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);

    // TEST QUERY
    const auto queryKey4 = getWakeLockQueryKey(1, uid2, conditionName);
    conditionCache[0] = ConditionState::kNotEvaluated;

    conditionTracker.isConditionMet(queryKey, allConditions, conditionCache);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
