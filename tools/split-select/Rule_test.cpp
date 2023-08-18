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

#include "Rule.h"

#include "SplitDescription.h"
#include "TestRules.h"

#include <algorithm>
#include <gtest/gtest.h>
#include <string>
#include <utils/String8.h>

using namespace android;
using namespace split::test;

namespace split {

TEST(RuleTest, generatesValidJson) {
    Rule rule(AndRule()
        .add(EqRule(Rule::SDK_VERSION, 7))
        .add(OrRule()
                .add(GtRule(Rule::SCREEN_DENSITY, 10))
                .add(LtRule(Rule::SCREEN_DENSITY, 5))
        )
    );

    // Expected
    std::string expected(
            "{"
            "  \"op\": \"AND_SUBRULES\","
            "  \"subrules\": ["
            "    {"
            "      \"op\": \"EQUALS\","
            "      \"property\": \"SDK_VERSION\","
            "      \"args\": [7]"
            "    },"
            "    {"
            "      \"op\": \"OR_SUBRULES\","
            "      \"subrules\": ["
            "        {"
            "          \"op\": \"GREATER_THAN\","
            "          \"property\": \"SCREEN_DENSITY\","
            "          \"args\": [10]"
            "        },"
            "        {"
            "          \"op\": \"LESS_THAN\","
            "          \"property\": \"SCREEN_DENSITY\","
            "          \"args\": [5]"
            "        }"
            "      ]"
            "     }"
            "  ]"
            "}");
    expected.erase(std::remove_if(expected.begin(), expected.end(), ::isspace), expected.end());

    // Result
    std::string result(rule.toJson().c_str());
    result.erase(std::remove_if(result.begin(), result.end(), ::isspace), result.end());

    ASSERT_EQ(expected, result);
}

TEST(RuleTest, simplifiesSingleSubruleRules) {
    sp<Rule> rule = new Rule(AndRule()
        .add(EqRule(Rule::SDK_VERSION, 7))
    );

    EXPECT_RULES_EQ(Rule::simplify(rule), EqRule(Rule::SDK_VERSION, 7));
}

TEST(RuleTest, simplifiesNestedSameOpSubrules) {
    sp<Rule> rule = new Rule(AndRule()
        .add(AndRule()
            .add(EqRule(Rule::SDK_VERSION, 7))
        )
        .add(EqRule(Rule::SDK_VERSION, 8))
    );

    EXPECT_RULES_EQ(Rule::simplify(rule),
            AndRule()
                .add(EqRule(Rule::SDK_VERSION, 7))
                .add(EqRule(Rule::SDK_VERSION, 8))
    );
}

} // namespace split
