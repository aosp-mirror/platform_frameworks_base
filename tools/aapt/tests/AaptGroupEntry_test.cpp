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

#include "AaptAssets.h"
#include "ResourceFilter.h"

using android::String8;

static ::testing::AssertionResult TestParse(AaptGroupEntry& entry, const String8& dirName,
        String8* outType) {
    if (entry.initFromDirName(dirName, outType)) {
        return ::testing::AssertionSuccess() << dirName << " was successfully parsed";
    }
    return ::testing::AssertionFailure() << dirName << " could not be parsed";
}

static ::testing::AssertionResult TestParse(AaptGroupEntry& entry, const char* input,
        String8* outType) {
    return TestParse(entry, String8(input), outType);
}

TEST(AaptGroupEntryTest, ParseNoQualifier) {
    AaptGroupEntry entry;
    String8 type;
    EXPECT_TRUE(TestParse(entry, "menu", &type));
    EXPECT_EQ(String8("menu"), type);
}

TEST(AaptGroupEntryTest, ParseCorrectType) {
    AaptGroupEntry entry;
    String8 type;
    EXPECT_TRUE(TestParse(entry, "anim", &type));
    EXPECT_EQ(String8("anim"), type);

    EXPECT_TRUE(TestParse(entry, "animator", &type));
    EXPECT_EQ(String8("animator"), type);
}
