/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "filter/ConfigFilter.h"
#include "test/Common.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(ConfigFilterTest, EmptyFilterMatchesAnything) {
    AxisConfigFilter filter;

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("320dpi")));
    EXPECT_TRUE(filter.match(test::parseConfigOrDie("fr")));
}

TEST(ConfigFilterTest, MatchesConfigWithUnrelatedAxis) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("fr"));

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("320dpi")));
}

TEST(ConfigFilterTest, MatchesConfigWithSameValueAxis) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("fr"));

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("fr")));
}

TEST(ConfigFilterTest, MatchesConfigWithSameValueAxisAndOtherUnrelatedAxis) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("fr"));

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("fr-320dpi")));
}

TEST(ConfigFilterTest, MatchesConfigWithOneMatchingAxis) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("fr-rFR"));
    filter.addConfig(test::parseConfigOrDie("sw360dp"));
    filter.addConfig(test::parseConfigOrDie("normal"));
    filter.addConfig(test::parseConfigOrDie("en-rUS"));

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("en")));
}

TEST(ConfigFilterTest, DoesNotMatchConfigWithDifferentValueAxis) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("fr"));

    EXPECT_FALSE(filter.match(test::parseConfigOrDie("de")));
}

TEST(ConfigFilterTest, DoesNotMatchWhenOneQualifierIsExplicitlyNotMatched) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("fr-rFR"));
    filter.addConfig(test::parseConfigOrDie("en-rUS"));
    filter.addConfig(test::parseConfigOrDie("normal"));
    filter.addConfig(test::parseConfigOrDie("large"));
    filter.addConfig(test::parseConfigOrDie("xxhdpi"));
    filter.addConfig(test::parseConfigOrDie("sw320dp"));

    EXPECT_FALSE(filter.match(test::parseConfigOrDie("fr-sw600dp-v13")));
}

TEST(ConfigFilterTest, MatchesSmallestWidthWhenSmaller) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("sw600dp"));

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("fr-sw320dp-v13")));
}

TEST(ConfigFilterTest, MatchesConfigWithSameLanguageButNoRegionSpecified) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("de-rDE"));

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("de")));
}

TEST(ConfigFilterTest, IgnoresVersion) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("normal-v4"));

    // The configs don't match on any axis besides version, which should be ignored.
    EXPECT_TRUE(filter.match(test::parseConfigOrDie("sw600dp-v13")));
}

TEST(ConfigFilterTest, MatchesConfigWithRegion) {
    AxisConfigFilter filter;
    filter.addConfig(test::parseConfigOrDie("kok"));
    filter.addConfig(test::parseConfigOrDie("kok-rIN"));
    filter.addConfig(test::parseConfigOrDie("kok-v419"));

    EXPECT_TRUE(filter.match(test::parseConfigOrDie("kok-rIN")));
}

} // namespace aapt
