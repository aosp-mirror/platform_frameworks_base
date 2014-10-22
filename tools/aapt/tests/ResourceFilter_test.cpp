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

#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>
#include <gtest/gtest.h>

#include "AaptConfig.h"
#include "ResourceFilter.h"
#include "ConfigDescription.h"

using android::String8;

// In this context, 'Axis' represents a particular field in the configuration,
// such as language or density.

TEST(WeakResourceFilterTest, EmptyFilterMatchesAnything) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("")));

    ConfigDescription config;
    config.density = 320;

    EXPECT_TRUE(filter.match(config));

    config.language[0] = 'f';
    config.language[1] = 'r';

    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, MatchesConfigWithUnrelatedAxis) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("fr")));

    ConfigDescription config;
    config.density = 320;

    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, MatchesConfigWithSameValueAxis) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("fr")));

    ConfigDescription config;
    config.language[0] = 'f';
    config.language[1] = 'r';

    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, MatchesConfigWithSameValueAxisAndOtherUnrelatedAxis) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("fr")));

    ConfigDescription config;
    config.language[0] = 'f';
    config.language[1] = 'r';
    config.density = 320;

    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, MatchesConfigWithOneMatchingAxis) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("fr_FR,sw360dp,normal,en_US")));

    ConfigDescription config;
    config.language[0] = 'e';
    config.language[1] = 'n';

    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, DoesNotMatchConfigWithDifferentValueAxis) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("fr")));

    ConfigDescription config;
    config.language[0] = 'd';
    config.language[1] = 'e';

    EXPECT_FALSE(filter.match(config));
}

TEST(WeakResourceFilterTest, DoesNotMatchWhenOneQualifierIsExplicitlyNotMatched) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("fr_FR,en_US,normal,large,xxhdpi,sw320dp")));

    ConfigDescription config;
    config.language[0] = 'f';
    config.language[1] = 'r';
    config.smallestScreenWidthDp = 600;
    config.version = 13;

    EXPECT_FALSE(filter.match(config));
}

TEST(WeakResourceFilterTest, MatchesSmallestWidthWhenSmaller) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("sw600dp")));

    ConfigDescription config;
    config.language[0] = 'f';
    config.language[1] = 'r';
    config.smallestScreenWidthDp = 320;
    config.version = 13;

    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, MatchesConfigWithSameLanguageButNoRegionSpecified) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("de-rDE")));

    ConfigDescription config;
    config.language[0] = 'd';
    config.language[1] = 'e';

    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, ParsesStandardLocaleOnlyString) {
    WeakResourceFilter filter;
    EXPECT_EQ(NO_ERROR, filter.parse(String8("de_DE")));
}

TEST(WeakResourceFilterTest, IgnoresVersion) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("normal-v4")));

    ConfigDescription config;
    config.smallestScreenWidthDp = 600;
    config.version = 13;

    // The configs don't match on any axis besides version, which should be ignored.
    EXPECT_TRUE(filter.match(config));
}

TEST(WeakResourceFilterTest, MatchesConfigWithRegion) {
    WeakResourceFilter filter;
    ASSERT_EQ(NO_ERROR, filter.parse(String8("kok,kok_IN,kok_419")));

    ConfigDescription config;
    AaptLocaleValue val;
    ASSERT_TRUE(val.initFromFilterString(String8("kok_IN")));
    val.writeTo(&config);

    EXPECT_TRUE(filter.match(config));
}

TEST(StrongResourceFilterTest, MatchesDensities) {
    ConfigDescription config;
    config.density = 160;
    config.version = 4;
    std::set<ConfigDescription> configs;
    configs.insert(config);

    StrongResourceFilter filter(configs);

    ConfigDescription expectedConfig;
    expectedConfig.density = 160;
    expectedConfig.version = 4;
    ASSERT_TRUE(filter.match(expectedConfig));
}

TEST(StrongResourceFilterTest, MatchOnlyMdpiAndExcludeAllOthers) {
    std::set<ConfigDescription> configsToMatch;
    ConfigDescription config;
    config.density = 160;
    config.version = 4;
    configsToMatch.insert(config);

    std::set<ConfigDescription> configsToNotMatch;
    config.density = 480;
    configsToNotMatch.insert(config);

    AndResourceFilter filter;
    filter.addFilter(new InverseResourceFilter(new StrongResourceFilter(configsToNotMatch)));
    filter.addFilter(new StrongResourceFilter(configsToMatch));

    ConfigDescription expectedConfig;
    expectedConfig.density = 160;
    expectedConfig.version = 4;
    ASSERT_TRUE(filter.match(expectedConfig));
}
