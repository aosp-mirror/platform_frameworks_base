/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <utils/StringUtils.h>

using namespace android;
using namespace android::uirenderer;

TEST(StringUtils, simpleBuildSet) {
    auto collection = StringUtils::split("a b c");

    EXPECT_TRUE(collection.has("a"));
    EXPECT_TRUE(collection.has("b"));
    EXPECT_TRUE(collection.has("c"));
    EXPECT_FALSE(collection.has("d"));
}

TEST(StringUtils, advancedBuildSet) {
    auto collection = StringUtils::split("GL_ext1 GL_ext2 GL_ext3");

    EXPECT_TRUE(collection.has("GL_ext1"));
    EXPECT_FALSE(collection.has("GL_ext"));  // string present, but not in list
}

TEST(StringUtils, sizePrinter) {
    std::stringstream os;
    os << SizePrinter{500};
    EXPECT_EQ("500.00B", os.str());
    os.str("");
    os << SizePrinter{46080};
    EXPECT_EQ("45.00KiB", os.str());
    os.str("");
    os << SizePrinter{5 * 1024 * 1024 + 520 * 1024};
    EXPECT_EQ("5.51MiB", os.str());
    os.str("");
    os << SizePrinter{2147483647};
    EXPECT_EQ("2048.00MiB", os.str());
}
