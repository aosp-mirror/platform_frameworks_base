// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#define LOG_TAG "incidentd"

#include "Section.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <string.h>

const int TIMEOUT_PARSER = -1;
const int NOOP_PARSER = 0;
const int REVERSE_PARSER = 1;

const int QUICK_TIMEOUT_MS = 100;

const string VARINT_FIELD_1 = "\x08\x96\x01"; // 150
const string STRING_FIELD_2 = "\x12\vwhatthefuck";
const string FIX64_FIELD_3 = "\x19\xff\xff\xff\xff\xff\xff\xff\xff"; // -1

using namespace android::base;
using namespace std;
using ::testing::StrEq;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStdout;

// NOTICE: this test requires /system/bin/incident_helper is installed.
TEST(SectionTest, FileSection) {
    TemporaryFile tf;
    FileSection fs(REVERSE_PARSER, tf.path);
    ReportRequestSet requests;

    ASSERT_TRUE(tf.fd != -1);
    ASSERT_TRUE(WriteStringToFile("iamtestdata", tf.path, false));

    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    // The input string is reversed in incident helper
    // The length is 11, in 128Varint it is "0000 1011" -> \v
    EXPECT_THAT(GetCapturedStdout(), StrEq("\xa\vatadtsetmai"));
}

TEST(SectionTest, FileSectionTimeout) {
    TemporaryFile tf;
    // id -1 is timeout parser
    FileSection fs(TIMEOUT_PARSER, tf.path, QUICK_TIMEOUT_MS);
    ReportRequestSet requests;
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
}

TEST(SectionTest, CommandSectionConstructor) {
    CommandSection cs1(1, "echo", "\"this is a test\"", "ooo", NULL);
    CommandSection cs2(2, "single_command", NULL);
    CommandSection cs3(1, 3123, "echo", "\"this is a test\"", "ooo", NULL);
    CommandSection cs4(2, 43214, "single_command", NULL);

    EXPECT_THAT(cs1.name.string(), StrEq("echo \"this is a test\" ooo"));
    EXPECT_THAT(cs2.name.string(), StrEq("single_command"));
    EXPECT_EQ(3123, cs3.timeoutMs);
    EXPECT_EQ(43214, cs4.timeoutMs);
    EXPECT_THAT(cs3.name.string(), StrEq("echo \"this is a test\" ooo"));
    EXPECT_THAT(cs4.name.string(), StrEq("single_command"));
}

TEST(SectionTest, CommandSectionEcho) {
    CommandSection cs(REVERSE_PARSER, "/system/bin/echo", "about", NULL);
    ReportRequestSet requests;
    requests.setMainFd(STDOUT_FILENO);
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\xa\x06\ntuoba"));
}

TEST(SectionTest, CommandSectionCommandTimeout) {
    CommandSection cs(NOOP_PARSER, QUICK_TIMEOUT_MS, "/system/bin/yes", NULL);
    ReportRequestSet requests;
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
}

TEST(SectionTest, CommandSectionIncidentHelperTimeout) {
    CommandSection cs(TIMEOUT_PARSER, QUICK_TIMEOUT_MS, "/system/bin/echo", "about", NULL);
    ReportRequestSet requests;
    requests.setMainFd(STDOUT_FILENO);
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
}

TEST(SectionTest, CommandSectionBadCommand) {
    CommandSection cs(NOOP_PARSER, "echo", "about", NULL);
    ReportRequestSet requests;
    ASSERT_EQ(NAME_NOT_FOUND, cs.Execute(&requests));
}

TEST(SectionTest, CommandSectionBadCommandAndTimeout) {
    CommandSection cs(TIMEOUT_PARSER, QUICK_TIMEOUT_MS, "nonexistcommand", "-opt", NULL);
    ReportRequestSet requests;
    // timeout will return first
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
}

TEST(SectionTest, TestFilterPiiTaggedFields) {
    TemporaryFile tf;
    FileSection fs(NOOP_PARSER, tf.path);
    ReportRequestSet requests;

    ASSERT_TRUE(tf.fd != -1);
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, tf.path, false));

    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));
}