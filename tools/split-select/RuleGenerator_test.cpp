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

#include "aapt/SdkConstants.h"
#include "TestRules.h"

#include <gtest/gtest.h>
#include <utils/Vector.h>

using namespace android;
using namespace split::test;

namespace split {

TEST(RuleGeneratorTest, testAbiRules) {
    Vector<abi::Variant> abis;
    const ssize_t armeabiIndex = abis.add(abi::Variant_armeabi);
    const ssize_t armeabi_v7aIndex = abis.add(abi::Variant_armeabi_v7a);
    const ssize_t x86Index = abis.add(abi::Variant_x86);

    EXPECT_RULES_EQ(RuleGenerator::generateAbi(abis, armeabiIndex),
            ContainsAnyRule(Rule::NATIVE_PLATFORM, "armeabi")
    );

    EXPECT_RULES_EQ(RuleGenerator::generateAbi(abis, armeabi_v7aIndex),
            ContainsAnyRule(Rule::NATIVE_PLATFORM, "armeabi-v7a", "arm64-v8a")
    );

    EXPECT_RULES_EQ(RuleGenerator::generateAbi(abis, x86Index),
            ContainsAnyRule(Rule::NATIVE_PLATFORM, "x86", "x86_64")
    );
}

TEST(RuleGeneratorTest, densityConstantsAreSane) {
    EXPECT_LT(263, (int) ConfigDescription::DENSITY_XHIGH);
    EXPECT_GT(262, (int) ConfigDescription::DENSITY_HIGH);
    EXPECT_LT(363, (int) ConfigDescription::DENSITY_XXHIGH);
    EXPECT_GT(362, (int) ConfigDescription::DENSITY_XHIGH);
}

TEST(RuleGeneratorTest, testDensityRules) {
    Vector<int> densities;
    const ssize_t highIndex = densities.add(ConfigDescription::DENSITY_HIGH);
    const ssize_t xhighIndex = densities.add(ConfigDescription::DENSITY_XHIGH);
    const ssize_t xxhighIndex = densities.add(ConfigDescription::DENSITY_XXHIGH);

    EXPECT_RULES_EQ(RuleGenerator::generateDensity(densities, highIndex),
            AndRule()
            .add(LtRule(Rule::SCREEN_DENSITY, 263))
    );

    EXPECT_RULES_EQ(RuleGenerator::generateDensity(densities, xhighIndex),
            AndRule()
            .add(GtRule(Rule::SCREEN_DENSITY, 262))
            .add(LtRule(Rule::SCREEN_DENSITY, 363))
    );

    EXPECT_RULES_EQ(RuleGenerator::generateDensity(densities, xxhighIndex),
            AndRule()
            .add(GtRule(Rule::SCREEN_DENSITY, 362))
    );
}

TEST(RuleGeneratorTest, testDensityRulesWithAnyDpi) {
    Vector<int> densities;
    const ssize_t highIndex = densities.add(ConfigDescription::DENSITY_HIGH);
    const ssize_t xhighIndex = densities.add(ConfigDescription::DENSITY_XHIGH);
    const ssize_t xxhighIndex = densities.add(ConfigDescription::DENSITY_XXHIGH);
    const ssize_t anyIndex = densities.add(ConfigDescription::DENSITY_ANY);

    EXPECT_RULES_EQ(RuleGenerator::generateDensity(densities, highIndex),
            AndRule()
            .add(LtRule(Rule::SDK_VERSION, SDK_LOLLIPOP))
            .add(LtRule(Rule::SCREEN_DENSITY, 263))
    );

    EXPECT_RULES_EQ(RuleGenerator::generateDensity(densities, xhighIndex),
            AndRule()
            .add(LtRule(Rule::SDK_VERSION, SDK_LOLLIPOP))
            .add(GtRule(Rule::SCREEN_DENSITY, 262))
            .add(LtRule(Rule::SCREEN_DENSITY, 363))
    );

    EXPECT_RULES_EQ(RuleGenerator::generateDensity(densities, xxhighIndex),
            AndRule()
            .add(LtRule(Rule::SDK_VERSION, SDK_LOLLIPOP))
            .add(GtRule(Rule::SCREEN_DENSITY, 362))
    );

    // We expect AlwaysTrue because anydpi always has attached v21 to the configuration
    // and the rest of the rule generation code generates the sdk version checks.
    EXPECT_RULES_EQ(RuleGenerator::generateDensity(densities, anyIndex), AlwaysTrue());
}

} // namespace split
