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

using namespace android::base;
using namespace std;
using ::testing::StrEq;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStdout;

// NOTICE: this test requires /system/bin/incident_helper is installed.
TEST(SectionTest, WriteHeader) {
    int id = 13; // expect output is 13 << 3 & 2 = 106 --> \x6a in ASCII
    FileSection s(id, ""); // ignore the path, just used to test the header
    ReportRequestSet requests;

    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, s.WriteHeader(&requests, 300));
    // According to protobuf encoding, 300 is "1010 1100 0000 0010" -> \xac \x02
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x6a\xac\x02"));
}

TEST(SectionTest, FileSection) {
    TemporaryFile tf;
    FileSection fs(0, tf.path);
    ReportRequestSet requests;

    ASSERT_TRUE(tf.fd != -1);
    ASSERT_TRUE(WriteStringToFile("iamtestdata", tf.path, false));

    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    // The input string is reversed in incident helper
    // The length is 11, in 128Varint it is "0000 1011" -> \v
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\vatadtsetmai"));
}

TEST(SectionTest, FileSectionTimeout) {
    TemporaryFile tf;
    // id -1 is timeout parser
    FileSection fs(-1, tf.path);
    ReportRequestSet requests;
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
}