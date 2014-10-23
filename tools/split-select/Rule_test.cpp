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

#include <algorithm>
#include <string>
#include <gtest/gtest.h>
#include <utils/String8.h>

using namespace android;

namespace split {

TEST(RuleTest, generatesValidJson) {
    sp<Rule> rule = new Rule();
    rule->op = Rule::AND_SUBRULES;

    sp<Rule> subrule = new Rule();
    subrule->op = Rule::EQUALS;
    subrule->key = Rule::SDK_VERSION;
    subrule->longArgs.add(7);
    rule->subrules.add(subrule);

    subrule = new Rule();
    subrule->op = Rule::OR_SUBRULES;
    rule->subrules.add(subrule);

    sp<Rule> subsubrule = new Rule();
    subsubrule->op = Rule::GREATER_THAN;
    subsubrule->key = Rule::SCREEN_DENSITY;
    subsubrule->longArgs.add(10);
    subrule->subrules.add(subsubrule);

    subsubrule = new Rule();
    subsubrule->op = Rule::LESS_THAN;
    subsubrule->key = Rule::SCREEN_DENSITY;
    subsubrule->longArgs.add(5);
    subrule->subrules.add(subsubrule);

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
    // Trim
    expected.erase(std::remove_if(expected.begin(), expected.end(), ::isspace), expected.end());

    std::string result(rule->toJson().string());

    // Trim
    result.erase(std::remove_if(result.begin(), result.end(), ::isspace), result.end());

    ASSERT_EQ(expected, result);
}

TEST(RuleTest, simplifiesSingleSubruleRules) {
    sp<Rule> rule = new Rule();
    rule->op = Rule::AND_SUBRULES;

    sp<Rule> subrule = new Rule();
    subrule->op = Rule::EQUALS;
    subrule->key = Rule::SDK_VERSION;
    subrule->longArgs.add(7);
    rule->subrules.add(subrule);

    sp<Rule> simplified = Rule::simplify(rule);
    EXPECT_EQ(Rule::EQUALS, simplified->op);
    EXPECT_EQ(Rule::SDK_VERSION, simplified->key);
    ASSERT_EQ(1u, simplified->longArgs.size());
    EXPECT_EQ(7, simplified->longArgs[0]);
}

TEST(RuleTest, simplifiesNestedSameOpSubrules) {
    sp<Rule> rule = new Rule();
    rule->op = Rule::AND_SUBRULES;

    sp<Rule> subrule = new Rule();
    subrule->op = Rule::AND_SUBRULES;
    rule->subrules.add(subrule);

    sp<Rule> subsubrule = new Rule();
    subsubrule->op = Rule::EQUALS;
    subsubrule->key = Rule::SDK_VERSION;
    subsubrule->longArgs.add(7);
    subrule->subrules.add(subsubrule);

    subrule = new Rule();
    subrule->op = Rule::EQUALS;
    subrule->key = Rule::SDK_VERSION;
    subrule->longArgs.add(8);
    rule->subrules.add(subrule);

    sp<Rule> simplified = Rule::simplify(rule);
    EXPECT_EQ(Rule::AND_SUBRULES, simplified->op);
    ASSERT_EQ(2u, simplified->subrules.size());

    sp<Rule> simplifiedSubrule = simplified->subrules[0];
    EXPECT_EQ(Rule::EQUALS, simplifiedSubrule->op);
    EXPECT_EQ(Rule::SDK_VERSION, simplifiedSubrule->key);
    ASSERT_EQ(1u, simplifiedSubrule->longArgs.size());
    EXPECT_EQ(7, simplifiedSubrule->longArgs[0]);

    simplifiedSubrule = simplified->subrules[1];
    EXPECT_EQ(Rule::EQUALS, simplifiedSubrule->op);
    EXPECT_EQ(Rule::SDK_VERSION, simplifiedSubrule->key);
    ASSERT_EQ(1u, simplifiedSubrule->longArgs.size());
    EXPECT_EQ(8, simplifiedSubrule->longArgs[0]);
}

} // namespace split
