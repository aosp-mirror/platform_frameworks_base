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

#include <utils/String8.h>
#include <gtest/gtest.h>

#include "AaptConfig.h"
#include "ConfigDescription.h"
#include "TestHelper.h"

using android::String8;

static ::testing::AssertionResult TestParse(const String8& input, ConfigDescription* config=NULL) {
    if (AaptConfig::parse(String8(input), config)) {
        return ::testing::AssertionSuccess() << input << " was successfully parsed";
    }
    return ::testing::AssertionFailure() << input << " could not be parsed";
}

static ::testing::AssertionResult TestParse(const char* input, ConfigDescription* config=NULL) {
    return TestParse(String8(input), config);
}

TEST(AaptConfigTest, ParseFailWhenQualifiersAreOutOfOrder) {
    EXPECT_FALSE(TestParse("en-sw600dp-ldrtl"));
    EXPECT_FALSE(TestParse("land-en"));
    EXPECT_FALSE(TestParse("hdpi-320dpi"));
}

TEST(AaptConfigTest, ParseFailWhenQualifiersAreNotMatched) {
    EXPECT_FALSE(TestParse("en-sw600dp-ILLEGAL"));
}

TEST(AaptConfigTest, ParseFailWhenQualifiersHaveTrailingDash) {
    EXPECT_FALSE(TestParse("en-sw600dp-land-"));
}

TEST(AaptConfigTest, ParseBasicQualifiers) {
    ConfigDescription config;
    EXPECT_TRUE(TestParse("", &config));
    EXPECT_EQ(String8(""), config.toString());

    EXPECT_TRUE(TestParse("fr-land", &config));
    EXPECT_EQ(String8("fr-land"), config.toString());

    EXPECT_TRUE(TestParse("mcc310-pl-sw720dp-normal-long-port-night-"
                "xhdpi-keyssoft-qwerty-navexposed-nonav", &config));
    EXPECT_EQ(String8("mcc310-pl-sw720dp-normal-long-port-night-"
                "xhdpi-keyssoft-qwerty-navexposed-nonav-v13"), config.toString());
}

TEST(AaptConfigTest, ParseLocales) {
    ConfigDescription config;
    EXPECT_TRUE(TestParse("en-rUS", &config));
    EXPECT_EQ(String8("en-US"), config.toString());
}

TEST(AaptConfigTest, ParseQualifierAddedInApi13) {
    ConfigDescription config;
    EXPECT_TRUE(TestParse("sw600dp", &config));
    EXPECT_EQ(String8("sw600dp-v13"), config.toString());

    EXPECT_TRUE(TestParse("sw600dp-v8", &config));
    EXPECT_EQ(String8("sw600dp-v13"), config.toString());
}
