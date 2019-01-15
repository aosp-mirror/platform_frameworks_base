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

#include "KernelWakesParser.h"

#include "frameworks/base/core/proto/android/os/kernelwake.pb.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <google/protobuf/message_lite.h>
#include <gtest/gtest.h>
#include <string.h>
#include <fcntl.h>

using namespace android::base;
using namespace android::os;
using namespace std;
using ::testing::StrEq;
using ::testing::Test;
using ::testing::internal::CaptureStderr;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStderr;
using ::testing::internal::GetCapturedStdout;

class KernelWakesParserTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_TRUE(tf.fd != -1);
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(KernelWakesParserTest, Short) {
    const string testFile = kTestDataPath + "kernel_wakeups_short.txt";
    KernelWakesParser parser;
    KernelWakeSourcesProto expected;

    KernelWakeSourcesProto::WakeupSource* record1 = expected.add_wakeup_sources();
    record1->set_name("ab");
    record1->set_active_count(8);
    record1->set_last_change(123456123456LL);

    KernelWakeSourcesProto::WakeupSource* record2 = expected.add_wakeup_sources();
    record2->set_name("df");
    record2->set_active_count(143);
    record2->set_last_change(0LL);

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expected.SerializeAsString());
    close(fd);
}

TEST_F(KernelWakesParserTest, Normal) {
    const string testFile = kTestDataPath + "kernel_wakeups.txt";
    KernelWakesParser parser;
    KernelWakeSourcesProto expected;

    KernelWakeSourcesProto::WakeupSource* record1 = expected.add_wakeup_sources();
    record1->set_name("ipc000000ab_ATFWD-daemon");
    record1->set_active_count(8);
    record1->set_event_count(8);
    record1->set_wakeup_count(0);
    record1->set_expire_count(0);
    record1->set_active_since(0L);
    record1->set_total_time(0L);
    record1->set_max_time(0L);
    record1->set_last_change(131348LL);
    record1->set_prevent_suspend_time(0LL);

    KernelWakeSourcesProto::WakeupSource* record2 = expected.add_wakeup_sources();
    record2->set_name("ipc000000aa_ATFWD-daemon");
    record2->set_active_count(143);
    record2->set_event_count(143);
    record2->set_wakeup_count(0);
    record2->set_expire_count(0);
    record2->set_active_since(0L);
    record2->set_total_time(123L);
    record2->set_max_time(3L);
    record2->set_last_change(2067286206LL);
    record2->set_prevent_suspend_time(0LL);

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expected.SerializeAsString());
    close(fd);
}
