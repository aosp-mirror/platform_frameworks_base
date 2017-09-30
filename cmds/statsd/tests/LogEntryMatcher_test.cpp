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

#define LOG_TAG "statsd_test"

#include <gtest/gtest.h>
#include <log/log_event_list.h>
#include <log/log_read.h>
#include <log/logprint.h>
#include "../src/matchers/LogEntryMatcherManager.h"
#include "../src/parse_util.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <stdio.h>

using namespace android::os::statsd;
using std::unordered_map;

const int kTagIdWakelock = 123;
const int kKeyIdState = 45;
const int kKeyIdPackageVersion = 67;

#ifdef __ANDROID__
TEST(LogEntryMatcherTest, TestSimpleMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->add_tag(kTagIdWakelock);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestBoolMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->add_tag(kTagIdWakelock);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(kKeyIdState);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    keyValue->set_eq_bool(true);
    wrapper.boolMap[kKeyIdState] = true;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));

    keyValue->set_eq_bool(false);
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));

    wrapper.boolMap[kTagIdWakelock] = false;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestStringMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->add_tag(kTagIdWakelock);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(kKeyIdState);
    keyValue->set_eq_string("wakelock_name");

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    wrapper.strMap[kKeyIdState] = "wakelock_name";

    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestIntComparisonMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->add_tag(kTagIdWakelock);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(kKeyIdState);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    keyValue->set_lt_int(10);
    wrapper.intMap[kKeyIdState] = 11;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 10;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 9;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));

    keyValue->set_gt_int(10);
    wrapper.intMap[kKeyIdState] = 11;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 10;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 9;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestIntWithEqualityComparisonMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->add_tag(kTagIdWakelock);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(kKeyIdState);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    keyValue->set_lte_int(10);
    wrapper.intMap[kKeyIdState] = 11;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 10;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 9;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));

    keyValue->set_gte_int(10);
    wrapper.intMap[kKeyIdState] = 11;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 10;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 9;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestFloatComparisonMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_log_entry_matcher();
    simpleMatcher->add_tag(kTagIdWakelock);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(kKeyIdState);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    keyValue->set_lt_float(10.0);
    wrapper.floatMap[kKeyIdState] = 10.1;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.floatMap[kKeyIdState] = 9.9;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));

    keyValue->set_gt_float(10.0);
    wrapper.floatMap[kKeyIdState] = 10.1;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.floatMap[kKeyIdState] = 9.9;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
}

// Helper for the composite matchers.
void addSimpleMatcher(SimpleLogEntryMatcher* simpleMatcher, int tag, int key, int val) {
    simpleMatcher->add_tag(tag);
    auto keyValue = simpleMatcher->add_key_value_matcher();
    keyValue->mutable_key_matcher()->set_key(key);
    keyValue->set_eq_int(val);
}

TEST(LogEntryMatcherTest, TestAndMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto combination = matcher.mutable_combination();
    combination->set_operation(LogicalOperation::AND);

    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
                     kTagIdWakelock, kKeyIdState, 3);
    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
                     kTagIdWakelock, kKeyIdPackageVersion, 4);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    wrapper.intMap[1003] = 4;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap.clear();
    wrapper.intMap[1] = 3;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap.clear();
    wrapper.intMap[1] = 3;
    wrapper.intMap[1003] = 4;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestOrMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto combination = matcher.mutable_combination();
    combination->set_operation(LogicalOperation::OR);

    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdState, 3);
    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdPackageVersion, 4);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    // Don't set any key-value pairs.
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[1003] = 4;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap.clear();
    wrapper.intMap[1] = 3;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap.clear();
    wrapper.intMap[1] = 3;
    wrapper.intMap[1003] = 4;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestNotMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto combination = matcher.mutable_combination();
    combination->set_operation(LogicalOperation::NOT);

    // Define first simpleMatcher
    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdState, 3);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    // Don't set any key-value pairs.
    wrapper.intMap[kKeyIdState] = 3;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestNANDMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto combination = matcher.mutable_combination();
    combination->set_operation(LogicalOperation::NAND);

    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdState, 3);
    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdPackageVersion, 4);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    // Don't set any key-value pairs.
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 3;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdPackageVersion] = 4;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
}

TEST(LogEntryMatcherTest, TestNORMatcher) {
    // Set up the matcher
    LogEntryMatcher matcher;
    auto combination = matcher.mutable_combination();
    combination->set_operation(LogicalOperation::NOR);

    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdState, 3);
    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdPackageVersion, 4);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    // Don't set any key-value pairs.
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 3;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdPackageVersion] = 4;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
}

// Tests that a NOT on top of AND is the same as NAND
TEST(LogEntryMatcherTest, TestMultipleLayerMatcher) {
    LogEntryMatcher matcher;
    auto not_combination = matcher.mutable_combination();
    not_combination->set_operation(LogicalOperation::NOT);

    // Now add the AND
    auto combination = not_combination->add_matcher()->mutable_combination();
    combination->set_operation(LogicalOperation::AND);
    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdState, 3);
    addSimpleMatcher(combination->add_matcher()->mutable_simple_log_entry_matcher(),
        kTagIdWakelock, kKeyIdPackageVersion, 4);

    LogEventWrapper wrapper;
    wrapper.tagId = kTagIdWakelock;

    // Don't set any key-value pairs.
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdState] = 3;
    EXPECT_TRUE(LogEntryMatcherManager::matches(matcher, wrapper));
    wrapper.intMap[kKeyIdPackageVersion] = 4;
    EXPECT_FALSE(LogEntryMatcherManager::matches(matcher, wrapper));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
