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

const int32_t TAG_ID = 123;
const int FIELD_ID_1 = 1;
const int FIELD_ID_2 = 2;
const int FIELD_ID_3 = 2;

// Private API from liblog.
extern "C" void android_log_rewind(android_log_context ctx);

#ifdef __ANDROID__
TEST(AtomMatcherTest, TestSimpleMatcher) {
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_tag(TAG_ID);

    LogEvent event(TAG_ID, 0);
    event.init();

    // Test
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
}

TEST(AtomMatcherTest, TestBoolMatcher) {
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_tag(TAG_ID);
    auto keyValue1 = simpleMatcher->add_key_value_matcher();
    keyValue1->mutable_key_matcher()->set_key(FIELD_ID_1);
    auto keyValue2 = simpleMatcher->add_key_value_matcher();
    keyValue2->mutable_key_matcher()->set_key(FIELD_ID_2);

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(true);
    event.write(false);
    // Convert to a LogEvent
    event.init();

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

TEST(AtomMatcherTest, TestStringMatcher) {
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_tag(TAG_ID);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(FIELD_ID_1);
    keyValue->set_eq_string("some value");

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write("some value");
    // Convert to a LogEvent
    event.init();

    // Test
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));
}

TEST(AtomMatcherTest, TestMultiFieldsMatcher) {
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_tag(TAG_ID);
    auto keyValue1 = simpleMatcher->add_key_value_matcher();
    keyValue1->mutable_key_matcher()->set_key(FIELD_ID_1);
    auto keyValue2 = simpleMatcher->add_key_value_matcher();
    keyValue2->mutable_key_matcher()->set_key(FIELD_ID_2);

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(2);
    event.write(3);

    // Convert to a LogEvent
    event.init();

    // Test
    keyValue1->set_eq_int(2);
    keyValue2->set_eq_int(3);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event));

    keyValue1->set_eq_int(2);
    keyValue2->set_eq_int(4);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));

    keyValue1->set_eq_int(4);
    keyValue2->set_eq_int(3);
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event));
}

TEST(AtomMatcherTest, TestIntComparisonMatcher) {
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();

    simpleMatcher->set_tag(TAG_ID);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(FIELD_ID_1);

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(11);
    event.init();

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

TEST(AtomMatcherTest, TestFloatComparisonMatcher) {
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_tag(TAG_ID);

    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(FIELD_ID_1);

    LogEvent event1(TAG_ID, 0);
    keyValue->set_lt_float(10.0);
    event1.write(10.1f);
    event1.init();
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event1));

    LogEvent event2(TAG_ID, 0);
    event2.write(9.9f);
    event2.init();
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event2));

    LogEvent event3(TAG_ID, 0);
    event3.write(10.1f);
    event3.init();
    keyValue->set_gt_float(10.0);
    EXPECT_TRUE(matchesSimple(*simpleMatcher, event3));

    LogEvent event4(TAG_ID, 0);
    event4.write(9.9f);
    event4.init();
    EXPECT_FALSE(matchesSimple(*simpleMatcher, event4));
}

// Helper for the composite matchers.
void addSimpleMatcher(SimpleAtomMatcher* simpleMatcher, int tag, int key, int val) {
    simpleMatcher->set_tag(tag);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(key);
    keyValue->set_eq_int(val);
}

TEST(AtomMatcherTest, TestAndMatcher) {
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

TEST(AtomMatcherTest, TestOrMatcher) {
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

TEST(AtomMatcherTest, TestNotMatcher) {
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

TEST(AtomMatcherTest, TestNandMatcher) {
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

TEST(AtomMatcherTest, TestNorMatcher) {
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
