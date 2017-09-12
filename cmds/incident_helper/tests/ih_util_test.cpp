/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "ih_util.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <string>

using namespace android::base;
using namespace std;
using ::testing::StrEq;

TEST(IhUtilTest, ParseHeader) {
    header_t result, expected;
    result = parseHeader(" \t \t\t ");
    EXPECT_EQ(expected, result);

    result = parseHeader(" \t 100 00\tOpQ \t wqrw");
    expected = { "100", "00", "opq", "wqrw" };
    EXPECT_EQ(expected, result);

    result = parseHeader(" \t 100 00\toooh \t wTF", "\t");
    expected = { "100 00", "oooh", "wtf" };
    EXPECT_EQ(expected, result);

    result = parseHeader("123,456,78_9", ",");
    expected = { "123", "456", "78_9" };
    EXPECT_EQ(expected, result);
}

TEST(IhUtilTest, ParseRecord) {
    record_t result, expected;
    result = parseRecord(" \t \t\t ");
    EXPECT_EQ(expected, result);

    result = parseRecord(" \t 100 00\toooh \t wqrw");
    expected = { "100", "00", "oooh", "wqrw" };
    EXPECT_EQ(expected, result);

    result = parseRecord(" \t 100 00\toooh \t wqrw", "\t");
    expected = { "100 00", "oooh", "wqrw" };
    EXPECT_EQ(expected, result);

    result = parseRecord("123,456,78_9", ",");
    expected = { "123", "456", "78_9" };
    EXPECT_EQ(expected, result);
}

TEST(IhUtilTest, Reader) {
    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile("test string\nsecond\nooo\n", tf.path, false));

    Reader r(tf.fd);
    string line;
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq("test string"));
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq("second"));
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq("ooo"));
    ASSERT_FALSE(r.readLine(&line));
    ASSERT_TRUE(r.ok(&line));
}

TEST(IhUtilTest, ReaderSmallBufSize) {
    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile("test string\nsecond\nooiecccojreo", tf.path, false));

    Reader r(tf.fd, 5);
    string line;
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq("test string"));
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq("second"));
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq("ooiecccojreo"));
    ASSERT_FALSE(r.readLine(&line));
    ASSERT_TRUE(r.ok(&line));
}

TEST(IhUtilTest, ReaderEmpty) {
    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile("", tf.path, false));

    Reader r(tf.fd);
    string line;
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq(""));
    ASSERT_FALSE(r.readLine(&line));
    ASSERT_TRUE(r.ok(&line));
}

TEST(IhUtilTest, ReaderMultipleEmptyLines) {
    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile("\n\n", tf.path, false));

    Reader r(tf.fd);
    string line;
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq(""));
    ASSERT_TRUE(r.readLine(&line));
    EXPECT_THAT(line, StrEq(""));
    ASSERT_FALSE(r.readLine(&line));
    EXPECT_THAT(line, StrEq(""));
    ASSERT_TRUE(r.ok(&line));
}

TEST(IhUtilTest, ReaderFailedNegativeFd) {
    Reader r(-123);
    string line;
    EXPECT_FALSE(r.readLine(&line));
    EXPECT_FALSE(r.ok(&line));
    EXPECT_THAT(line, StrEq("Negative fd"));
}

TEST(IhUtilTest, ReaderFailedZeroBufferSize) {
    Reader r(23, 0);
    string line;
    EXPECT_FALSE(r.readLine(&line));
    EXPECT_FALSE(r.ok(&line));
    EXPECT_THAT(line, StrEq("Zero buffer capacity"));
}

TEST(IhUtilTest, ReaderFailedBadFd) {
    Reader r(1231432);
    string line;
    EXPECT_FALSE(r.readLine(&line));
    EXPECT_FALSE(r.ok(&line));
    EXPECT_THAT(line, StrEq("Fail to read from fd"));
}
