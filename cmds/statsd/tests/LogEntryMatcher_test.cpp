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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/matcher_util.h"
#include "stats_util.h"

#include <gtest/gtest.h>
#include <log/log_event_list.h>
#include <log/log_read.h>
#include <log/logprint.h>

#include <stdio.h>

using namespace android::os::statsd;
using std::unordered_map;
using std::vector;

const int TAG_ID = 123;
const int FIELD_ID_1 = 1;
const int FIELD_ID_2 = 2;
const int FIELD_ID_3 = 2;

// Private API from liblog.
extern "C" void android_log_rewind(android_log_context ctx);

#ifdef __ANDROID__
TEST(LogEntryMatcherTest, TestSimpleMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->set_tag(TAG_ID);

    // Set up the event
    android_log_event_list list(TAG_ID);

    // Convert to a LogEvent
    list.convert_to_reader();
    LogEvent event(999, &list);

    // Test
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
}

TEST(LogEntryMatcherTest, TestBoolMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->set_tag(TAG_ID);
    auto keyValue1 = simpleMatcher->add_key_value_matcher();
    keyValue1->mutable_key_matcher()->set_key(FIELD_ID_1);
    auto keyValue2 = simpleMatcher->add_key_value_matcher();
    keyValue2->mutable_key_matcher()->set_key(FIELD_ID_2);

    // Set up the event
    android_log_event_list list(TAG_ID);
    list << true;
    list << false;

    // Convert to a LogEvent
    list.convert_to_reader();
    LogEvent event(999, &list);

    // Test
    keyValue1->set_eq_bool(true);
    keyValue2->set_eq_bool(false);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));

    keyValue1->set_eq_bool(false);
    keyValue2->set_eq_bool(false);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));

    keyValue1->set_eq_bool(true);
    keyValue2->set_eq_bool(false);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));

    keyValue1->set_eq_bool(true);
    keyValue2->set_eq_bool(true);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
}

TEST(LogEntryMatcherTest, TestStringMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->set_tag(TAG_ID);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(FIELD_ID_1);
    keyValue->set_eq_string("some value");

    // Set up the event
    android_log_event_list list(TAG_ID);
    list << "some value";

    // Convert to a LogEvent
    list.convert_to_reader();
    LogEvent event(999, &list);

    // Test
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
}

TEST(LogEntryMatcherTest, TestIntComparisonMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();

    simpleMatcher->set_tag(TAG_ID);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(FIELD_ID_1);

    // Set up the event
    android_log_event_list list(TAG_ID);
    list << 11;

    // Convert to a LogEvent
    list.convert_to_reader();
    LogEvent event(999, &list);

    // Test

    // eq_int
    keyValue->set_eq_int(10);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
    keyValue->set_eq_int(11);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
    keyValue->set_eq_int(12);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));

    // lt_int
    keyValue->set_lt_int(10);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
    keyValue->set_lt_int(11);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
    keyValue->set_lt_int(12);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));

    // lte_int
    keyValue->set_lte_int(10);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
    keyValue->set_lte_int(11);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
    keyValue->set_lte_int(12);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));

    // gt_int
    keyValue->set_gt_int(10);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
    keyValue->set_gt_int(11);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
    keyValue->set_gt_int(12);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));

    // gte_int
    keyValue->set_gte_int(10);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
    keyValue->set_gte_int(11);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
    keyValue->set_gte_int(12);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
}

#if 0

TEST(LogEntryMatcherTest, TestFloatComparisonMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->set_tag(TAG_ID);

    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(FIELD_ID_1);

    LogEvent event;
    event.tagId = TAG_ID;

    keyValue->set_lt_float(10.0);
    event.floatMap[FIELD_ID_1] = 10.1;
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
    event.floatMap[FIELD_ID_1] = 9.9;
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));

    keyValue->set_gt_float(10.0);
    event.floatMap[FIELD_ID_1] = 10.1;
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
    event.floatMap[FIELD_ID_1] = 9.9;
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
}
#endif

// Helper for the composite matchers.
void addSimpleMatcher(SimpleLogEntryMatcher* simpleMatcher, int tag, int key, int val) {
    simpleMatcher->set_tag(tag);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(key);
    keyValue->set_eq_int(val);
}

TEST(LogEntryMatcherTest, TestAndMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::AND;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);
    children.push_back(2);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));
}

TEST(LogEntryMatcherTest, TestOrMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::OR;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);
    children.push_back(2);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));
}

TEST(LogEntryMatcherTest, TestNotMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NOT;

    vector<int> children;
    children.push_back(0);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));
}

TEST(LogEntryMatcherTest, TestNandMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NAND;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);

    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);
    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));
}

TEST(LogEntryMatcherTest, TestNorMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NOR;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);
    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
