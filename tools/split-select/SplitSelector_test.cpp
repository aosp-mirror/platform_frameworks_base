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

#include <gtest/gtest.h>
#include <utils/String8.h>
#include <utils/Vector.h>

#include "SplitDescription.h"
#include "SplitSelector.h"
#include "TestRules.h"

namespace split {

using namespace android;

static ::testing::AssertionResult addSplit(Vector<SplitDescription>& splits, const char* str) {
    SplitDescription split;
    if (!SplitDescription::parse(String8(str), &split)) {
        return ::testing::AssertionFailure() << str << " is not a valid configuration.";
    }
    splits.add(split);
    return ::testing::AssertionSuccess();
}

TEST(SplitSelectorTest, rulesShouldMatchSelection) {
    Vector<SplitDescription> splits;
    ASSERT_TRUE(addSplit(splits, "hdpi"));
    ASSERT_TRUE(addSplit(splits, "xhdpi"));
    ASSERT_TRUE(addSplit(splits, "xxhdpi"));
    ASSERT_TRUE(addSplit(splits, "mdpi"));

    SplitDescription targetSplit;
    ASSERT_TRUE(SplitDescription::parse(String8("hdpi"), &targetSplit));

    SplitSelector selector(splits);
    SortedVector<SplitDescription> bestSplits;
    bestSplits.merge(selector.getBestSplits(targetSplit));

    SplitDescription expected;
    ASSERT_TRUE(SplitDescription::parse(String8("hdpi"), &expected));
    EXPECT_GE(bestSplits.indexOf(expected), 0);

    KeyedVector<SplitDescription, sp<Rule> > rules = selector.getRules();
    ssize_t idx = rules.indexOfKey(expected);
    ASSERT_GE(idx, 0);
    sp<Rule> rule = rules[idx];
    ASSERT_TRUE(rule != NULL);

    ASSERT_GT(ResTable_config::DENSITY_HIGH, 180);
    ASSERT_LT(ResTable_config::DENSITY_HIGH, 263);

    Rule expectedRule(test::AndRule()
            .add(test::GtRule(Rule::SDK_VERSION, 3))
            .add(test::GtRule(Rule::SCREEN_DENSITY, 180))
            .add(test::LtRule(Rule::SCREEN_DENSITY, 263)));
    EXPECT_RULES_EQ(rule, expectedRule);
}

} // namespace split
