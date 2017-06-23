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

#include "IncidentHelper.h"

#include "frameworks/base/core/proto/android/os/kernelwake.pb.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <string.h>
#include <fcntl.h>

using namespace android::base;
using namespace android::os;
using ::testing::StrEq;
using ::testing::Test;
using ::testing::internal::CaptureStderr;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStderr;
using ::testing::internal::GetCapturedStdout;

class IncidentHelperTest : public Test {
public:
    virtual void SetUp() override {

    }

protected:
    const std::string kTestPath = GetExecutableDirectory();
    const std::string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(IncidentHelperTest, ReverseParser) {
    ReverseParser parser;
    TemporaryFile tf;

    ASSERT_TRUE(tf.fd != -1);
    ASSERT_TRUE(WriteStringToFile("TestData", tf.path, false));

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(tf.fd, STDOUT_FILENO));
    EXPECT_THAT(GetCapturedStdout(), StrEq("ataDtseT"));
}

TEST_F(IncidentHelperTest, KernelWakesParser) {
    const std::string testFile = kTestDataPath + "kernel_wakeups.txt";
    KernelWakesParser parser;
    KernelWakeSources expected;
    std::string expectedStr;
    TemporaryFile tf;
    ASSERT_TRUE(tf.fd != -1);

    WakeupSourceProto* record1 = expected.add_wakeup_sources();
    record1->set_name("ipc000000ab_ATFWD-daemon");
    record1->set_active_count(8);
    record1->set_event_count(8);
    record1->set_wakeup_count(0);
    record1->set_expire_count(0);
    record1->set_active_since(0l);
    record1->set_total_time(0l);
    record1->set_max_time(0l);
    record1->set_last_change(131348l);
    record1->set_prevent_suspend_time(0l);

    WakeupSourceProto* record2 = expected.add_wakeup_sources();
    record2->set_name("ipc000000aa_ATFWD-daemon");
    record2->set_active_count(143);
    record2->set_event_count(143);
    record2->set_wakeup_count(0);
    record2->set_expire_count(0);
    record2->set_active_since(0l);
    record2->set_total_time(123l);
    record2->set_max_time(3l);
    record2->set_last_change(2067286206l);
    record2->set_prevent_suspend_time(0l);

    ASSERT_TRUE(expected.SerializeToFileDescriptor(tf.fd));
    ASSERT_TRUE(ReadFileToString(tf.path, &expectedStr));

    int fd = open(testFile.c_str(), O_RDONLY, 0444);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expectedStr);
    close(fd);
}

TEST_F(IncidentHelperTest, KernelWakesParserBadHeaders) {
    const std::string testFile = kTestDataPath + "kernel_wakeups_bad_headers.txt";
    KernelWakesParser parser;

    int fd = open(testFile.c_str(), O_RDONLY, 0444);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    CaptureStderr();
    ASSERT_EQ(BAD_VALUE, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_THAT(GetCapturedStdout(), StrEq(""));
    EXPECT_THAT(GetCapturedStderr(), StrEq("[KernelWakeSources]Bad header:\nTHIS IS BAD HEADER\n"));
    close(fd);
}
