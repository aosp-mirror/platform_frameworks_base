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

    result = parseRecord("", " ");
    EXPECT_TRUE(result.empty());
}

TEST(IhUtilTest, ParseRecordByColumns) {
    record_t result, expected;
    std::vector<int> indices = { 3, 10 };

    result = parseRecordByColumns("12345", indices);
    expected = {};
    EXPECT_EQ(expected, result);

    result = parseRecordByColumns("abc \t2345  6789 ", indices);
    expected = { "abc", "2345  6789" };
    EXPECT_EQ(expected, result);

    std::string extraColumn1 = "abc \t23456789 bob";
    std::string emptyMidColm = "abc \t         bob";
    std::string longFirstClm = "abcdefgt\t6789 bob";
    std::string lngFrstEmpty = "abcdefgt\t     bob";

    result = parseRecordByColumns(extraColumn1, indices);
    expected = { "abc", "23456789 bob" };
    EXPECT_EQ(expected, result);

    // 2nd column should be treated as an empty entry.
    result = parseRecordByColumns(emptyMidColm, indices);
    expected = { "abc", "bob" };
    EXPECT_EQ(expected, result);

    result = parseRecordByColumns(longFirstClm, indices);
    expected = { "abcdefgt", "6789 bob" };
    EXPECT_EQ(expected, result);

    result = parseRecordByColumns(lngFrstEmpty, indices);
    expected = { "abcdefgt", "bob" };
    EXPECT_EQ(expected, result);
}

TEST(IhUtilTest, stripPrefix) {
    string data1 = "Swap: abc ";
    EXPECT_TRUE(stripPrefix(&data1, "Swap:"));
    EXPECT_THAT(data1, StrEq("abc"));

    string data2 = "Swap: abc ";
    EXPECT_FALSE(stripPrefix(&data2, "Total:"));
    EXPECT_THAT(data2, StrEq("Swap: abc "));

    string data3 = "Swap: abc ";
    EXPECT_TRUE(stripPrefix(&data3, "Swa"));
    EXPECT_THAT(data3, StrEq("p: abc"));

    string data4 = "Swap: abc ";
    EXPECT_FALSE(stripPrefix(&data4, "Swa", true));
    EXPECT_THAT(data4, StrEq("Swap: abc "));
}

TEST(IhUtilTest, stripSuffix) {
    string data1 = " 243%abc";
    EXPECT_TRUE(stripSuffix(&data1, "abc"));
    EXPECT_THAT(data1, StrEq("243%"));

    string data2 = " 243%abc";
    EXPECT_FALSE(stripSuffix(&data2, "Not right"));
    EXPECT_THAT(data2, StrEq(" 243%abc"));

    string data3 = " 243%abc";
    EXPECT_TRUE(stripSuffix(&data3, "bc"));
    EXPECT_THAT(data3, StrEq("243%a"));

    string data4 = " 243%abc";
    EXPECT_FALSE(stripSuffix(&data4, "bc", true));
    EXPECT_THAT(data4, StrEq(" 243%abc"));
}

TEST(IhUtilTest, behead) {
    string testcase1 = "81002 dropbox_file_copy (a)(b)";
    EXPECT_THAT(behead(&testcase1, ' '), StrEq("81002"));
    EXPECT_THAT(behead(&testcase1, ' '), StrEq("dropbox_file_copy"));
    EXPECT_THAT(testcase1, "(a)(b)");

    string testcase2 = "adbce,erwqr";
    EXPECT_THAT(behead(&testcase2, ' '), StrEq("adbce,erwqr"));
    EXPECT_THAT(testcase2, "");

    string testcase3 = "first second";
    EXPECT_THAT(behead(&testcase3, ' '), StrEq("first"));
    EXPECT_THAT(behead(&testcase3, ' '), StrEq("second"));
    EXPECT_THAT(testcase3, "");
}

TEST(IhUtilTest, Reader) {
    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile("test string\nsecond\nooo\n", tf.path));

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

TEST(IhUtilTest, ReaderEmpty) {
    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile("", tf.path));

    Reader r(tf.fd);
    string line;
    ASSERT_FALSE(r.readLine(&line));
    EXPECT_THAT(line, StrEq(""));
    ASSERT_TRUE(r.ok(&line));
}

TEST(IhUtilTest, ReaderMultipleEmptyLines) {
    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile("\n\n", tf.path));

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
    EXPECT_THAT(line, StrEq("Invalid fd -123"));
}

TEST(IhUtilTest, ReaderFailedBadFd) {
    Reader r(1231432);
    string line;
    EXPECT_FALSE(r.readLine(&line));
    EXPECT_FALSE(r.ok(&line));
    EXPECT_THAT(line, StrEq("Invalid fd 1231432"));
}
