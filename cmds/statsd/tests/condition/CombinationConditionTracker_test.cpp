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

#include "condition/condition_util.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <gtest/gtest.h>

#include <stdio.h>
#include <vector>

using namespace android::os::statsd;
using std::vector;

#ifdef __ANDROID__
TEST(ConditionTrackerTest, TestUnknownCondition) {
    LogicalOperation operation = LogicalOperation::AND;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);
    children.push_back(2);

    vector<ConditionState> conditionResults;
    conditionResults.push_back(ConditionState::kUnknown);
    conditionResults.push_back(ConditionState::kFalse);
    conditionResults.push_back(ConditionState::kTrue);

    EXPECT_EQ(evaluateCombinationCondition(children, operation, conditionResults),
              ConditionState::kUnknown);
}

TEST(ConditionTrackerTest, TestAndCondition) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::AND;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);
    children.push_back(2);

    vector<ConditionState> conditionResults;
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kFalse);
    conditionResults.push_back(ConditionState::kTrue);

    EXPECT_FALSE(evaluateCombinationCondition(children, operation, conditionResults));

    conditionResults.clear();
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kTrue);

    EXPECT_TRUE(evaluateCombinationCondition(children, operation, conditionResults));
}

TEST(ConditionTrackerTest, TestOrCondition) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::OR;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);
    children.push_back(2);

    vector<ConditionState> conditionResults;
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kFalse);
    conditionResults.push_back(ConditionState::kTrue);

    EXPECT_TRUE(evaluateCombinationCondition(children, operation, conditionResults));

    conditionResults.clear();
    conditionResults.push_back(ConditionState::kFalse);
    conditionResults.push_back(ConditionState::kFalse);
    conditionResults.push_back(ConditionState::kFalse);

    EXPECT_FALSE(evaluateCombinationCondition(children, operation, conditionResults));
}

TEST(ConditionTrackerTest, TestNotCondition) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NOT;

    vector<int> children;
    children.push_back(0);

    vector<ConditionState> conditionResults;
    conditionResults.push_back(ConditionState::kTrue);

    EXPECT_FALSE(evaluateCombinationCondition(children, operation, conditionResults));

    conditionResults.clear();
    conditionResults.push_back(ConditionState::kFalse);
    EXPECT_TRUE(evaluateCombinationCondition(children, operation, conditionResults));

    children.clear();
    conditionResults.clear();
    EXPECT_EQ(evaluateCombinationCondition(children, operation, conditionResults),
              ConditionState::kUnknown);
}

TEST(ConditionTrackerTest, TestNandCondition) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NAND;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);

    vector<ConditionState> conditionResults;
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kFalse);

    EXPECT_TRUE(evaluateCombinationCondition(children, operation, conditionResults));

    conditionResults.clear();
    conditionResults.push_back(ConditionState::kFalse);
    conditionResults.push_back(ConditionState::kFalse);
    EXPECT_TRUE(evaluateCombinationCondition(children, operation, conditionResults));

    conditionResults.clear();
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kTrue);
    EXPECT_FALSE(evaluateCombinationCondition(children, operation, conditionResults));
}

TEST(ConditionTrackerTest, TestNorCondition) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NOR;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);

    vector<ConditionState> conditionResults;
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kFalse);

    EXPECT_FALSE(evaluateCombinationCondition(children, operation, conditionResults));

    conditionResults.clear();
    conditionResults.push_back(ConditionState::kFalse);
    conditionResults.push_back(ConditionState::kFalse);
    EXPECT_TRUE(evaluateCombinationCondition(children, operation, conditionResults));

    conditionResults.clear();
    conditionResults.push_back(ConditionState::kTrue);
    conditionResults.push_back(ConditionState::kTrue);
    EXPECT_FALSE(evaluateCombinationCondition(children, operation, conditionResults));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
