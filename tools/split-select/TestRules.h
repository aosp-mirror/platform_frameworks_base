/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef H_AAPT_SPLIT_TEST_RULES
#define H_AAPT_SPLIT_TEST_RULES

#include "Rule.h"

#include <gtest/gtest.h>

namespace split {
namespace test {

struct AndRule : public Rule {
    AndRule() {
        op = Rule::AND_SUBRULES;
    }

    AndRule& add(const Rule& rhs) {
        subrules.add(new Rule(rhs));
        return *this;
    }
};

struct OrRule : public Rule {
    OrRule() {
        op = Rule::OR_SUBRULES;
    }

    OrRule& add(const Rule& rhs) {
        subrules.add(new Rule(rhs));
        return *this;
    }
};

const Rule EqRule(Rule::Key key, long value);
const Rule LtRule(Rule::Key key, long value);
const Rule GtRule(Rule::Key key, long value);
const Rule ContainsAnyRule(Rule::Key key, const char* str1);
const Rule ContainsAnyRule(Rule::Key key, const char* str1, const char* str2);
const Rule AlwaysTrue();

::testing::AssertionResult RulePredFormat(
        const char* actualExpr, const char* expectedExpr,
        const android::sp<Rule>& actual, const Rule& expected);

#define EXPECT_RULES_EQ(actual, expected) \
        EXPECT_PRED_FORMAT2(::split::test::RulePredFormat, actual, expected)

} // namespace test
} // namespace split

#endif // H_AAPT_SPLIT_TEST_RULES
