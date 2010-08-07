/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "String8_test"
#include <utils/Log.h>
#include <utils/String8.h>

#include <gtest/gtest.h>

namespace android {

class String8Test : public testing::Test {
protected:
    virtual void SetUp() {
    }

    virtual void TearDown() {
    }
};

TEST_F(String8Test, Cstr) {
    String8 tmp("Hello, world!");

    EXPECT_STREQ(tmp.string(), "Hello, world!");
}

TEST_F(String8Test, OperatorPlus) {
    String8 src1("Hello, ");

    // Test adding String8 + const char*
    const char* ccsrc2 = "world!";
    String8 dst1 = src1 + ccsrc2;
    EXPECT_STREQ(dst1.string(), "Hello, world!");
    EXPECT_STREQ(src1.string(), "Hello, ");
    EXPECT_STREQ(ccsrc2, "world!");

    // Test adding String8 + String8
    String8 ssrc2("world!");
    String8 dst2 = src1 + ssrc2;
    EXPECT_STREQ(dst2.string(), "Hello, world!");
    EXPECT_STREQ(src1.string(), "Hello, ");
    EXPECT_STREQ(ssrc2.string(), "world!");
}

TEST_F(String8Test, OperatorPlusEquals) {
    String8 src1("My voice");

    // Testing String8 += String8
    String8 src2(" is my passport.");
    src1 += src2;
    EXPECT_STREQ(src1.string(), "My voice is my passport.");
    EXPECT_STREQ(src2.string(), " is my passport.");

    // Adding const char* to the previous string.
    const char* src3 = " Verify me.";
    src1 += src3;
    EXPECT_STREQ(src1.string(), "My voice is my passport. Verify me.");
    EXPECT_STREQ(src2.string(), " is my passport.");
    EXPECT_STREQ(src3, " Verify me.");
}

}
