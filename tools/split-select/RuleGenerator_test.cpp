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

#include "RuleGenerator.h"

#include <algorithm>
#include <gtest/gtest.h>
#include <utils/String8.h>

using namespace android;

namespace split {

static void expectDensityRule(const Vector<int>& densities, int density, int greaterThan, int lessThan);
static void expectAbiRule(const Vector<abi::Variant>& abis, abi::Variant variant, const char* a);
static void expectAbiRule(const Vector<abi::Variant>& abis, abi::Variant variant, const char* a, const char* b);

TEST(RuleGeneratorTest, testAbiRules) {
    Vector<abi::Variant> abis;
    abis.add(abi::Variant_armeabi);
    abis.add(abi::Variant_armeabi_v7a);
    abis.add(abi::Variant_x86);
    std::sort(abis.begin(), abis.end());

    expectAbiRule(abis, abi::Variant_armeabi, "armeabi");
    expectAbiRule(abis, abi::Variant_armeabi_v7a, "armeabi-v7a", "arm64-v8a");
    expectAbiRule(abis, abi::Variant_x86, "x86", "x86_64");
}

TEST(RuleGeneratorTest, testDensityRules) {
    Vector<int> densities;
    densities.add(ConfigDescription::DENSITY_HIGH);
    densities.add(ConfigDescription::DENSITY_XHIGH);
    densities.add(ConfigDescription::DENSITY_XXHIGH);
    densities.add(ConfigDescription::DENSITY_ANY);

    ASSERT_LT(263, (int) ConfigDescription::DENSITY_XHIGH);
    ASSERT_GT(262, (int) ConfigDescription::DENSITY_HIGH);
    ASSERT_LT(363, (int) ConfigDescription::DENSITY_XXHIGH);
    ASSERT_GT(362, (int) ConfigDescription::DENSITY_XHIGH);

    expectDensityRule(densities, ConfigDescription::DENSITY_HIGH, 0, 263);
    expectDensityRule(densities, ConfigDescription::DENSITY_XHIGH, 262, 363);
    expectDensityRule(densities, ConfigDescription::DENSITY_XXHIGH, 362, 0);
    expectDensityRule(densities, ConfigDescription::DENSITY_ANY, 0, 0);
}

//
// Helper methods.
//

static void expectDensityRule(const Vector<int>& densities, int density, int greaterThan, int lessThan) {
    const int* iter = std::find(densities.begin(), densities.end(), density);
    if (densities.end() == iter) {
        ADD_FAILURE() << density << "dpi was not in the density list.";
        return;
    }

    sp<Rule> rule = RuleGenerator::generateDensity(densities, iter - densities.begin());
    if (rule->op != Rule::AND_SUBRULES) {
        ADD_FAILURE() << "Op in rule for " << density << "dpi is not Rule::AND_SUBRULES.";
        return;
    }

    size_t index = 0;

    bool isAnyDpi = density == ConfigDescription::DENSITY_ANY;

    sp<Rule> anyDpiRule = rule->subrules[index++];
    EXPECT_EQ(Rule::EQUALS, anyDpiRule->op)
            << "for " << density << "dpi ANY DPI rule";
    EXPECT_EQ(Rule::SCREEN_DENSITY, anyDpiRule->key)
            << "for " << density << "dpi ANY DPI rule";
    EXPECT_EQ(isAnyDpi == false, anyDpiRule->negate)
            << "for " << density << "dpi ANY DPI rule";
    if (anyDpiRule->longArgs.size() == 1) {
        EXPECT_EQ((long) ConfigDescription::DENSITY_ANY, anyDpiRule->longArgs[0])
            << "for " << density << "dpi ANY DPI rule";
    } else {
        EXPECT_EQ(1u, anyDpiRule->longArgs.size())
            << "for " << density << "dpi ANY DPI rule";
    }


    if (greaterThan != 0) {
        sp<Rule> greaterThanRule = rule->subrules[index++];
        EXPECT_EQ(Rule::GREATER_THAN, greaterThanRule->op)
                << "for " << density << "dpi GREATER_THAN rule";
        EXPECT_EQ(Rule::SCREEN_DENSITY, greaterThanRule->key)
                << "for " << density << "dpi GREATER_THAN rule";
        if (greaterThanRule->longArgs.size() == 1) {
            EXPECT_EQ(greaterThan, greaterThanRule->longArgs[0])
                << "for " << density << "dpi GREATER_THAN rule";
        } else {
            EXPECT_EQ(1u, greaterThanRule->longArgs.size())
                << "for " << density << "dpi GREATER_THAN rule";
        }
    }

    if (lessThan != 0) {
        sp<Rule> lessThanRule = rule->subrules[index++];
        EXPECT_EQ(Rule::LESS_THAN, lessThanRule->op)
                << "for " << density << "dpi LESS_THAN rule";
        EXPECT_EQ(Rule::SCREEN_DENSITY, lessThanRule->key)
                << "for " << density << "dpi LESS_THAN rule";
        if (lessThanRule->longArgs.size() == 1) {
            EXPECT_EQ(lessThan, lessThanRule->longArgs[0])
                << "for " << density << "dpi LESS_THAN rule";
        } else {
            EXPECT_EQ(1u, lessThanRule->longArgs.size())
                << "for " << density << "dpi LESS_THAN rule";
        }
    }
}

static void expectAbiRule(const Vector<abi::Variant>& abis, abi::Variant variant, const Vector<const char*>& matches) {
    const abi::Variant* iter = std::find(abis.begin(), abis.end(), variant);
    if (abis.end() == iter) {
        ADD_FAILURE() << abi::toString(variant) << " was not in the abi list.";
        return;
    }

    sp<Rule> rule = RuleGenerator::generateAbi(abis, iter - abis.begin());

    EXPECT_EQ(Rule::CONTAINS_ANY, rule->op)
            << "for " << abi::toString(variant) << " rule";
    EXPECT_EQ(Rule::NATIVE_PLATFORM, rule->key)
            << " for " << abi::toString(variant) << " rule";
    EXPECT_EQ(matches.size(), rule->stringArgs.size())
            << " for " << abi::toString(variant) << " rule";

    const size_t matchCount = matches.size();
    for (size_t i = 0; i < matchCount; i++) {
        const char* match = matches[i];
        if (rule->stringArgs.end() ==
                std::find(rule->stringArgs.begin(), rule->stringArgs.end(), String8(match))) {
            ADD_FAILURE() << "Rule for abi " << abi::toString(variant)
                    << " does not contain match for expected abi " << match;
        }
    }
}

static void expectAbiRule(const Vector<abi::Variant>& abis, abi::Variant variant, const char* a) {
    Vector<const char*> matches;
    matches.add(a);
    expectAbiRule(abis, variant, matches);
}

static void expectAbiRule(const Vector<abi::Variant>& abis, abi::Variant variant, const char* a, const char* b) {
    Vector<const char*> matches;
    matches.add(a);
    matches.add(b);
    expectAbiRule(abis, variant, matches);
}

} // namespace split
