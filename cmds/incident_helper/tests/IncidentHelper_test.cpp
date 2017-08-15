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
#include "frameworks/base/core/proto/android/os/procrank.pb.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <google/protobuf/message.h>
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
        ASSERT_TRUE(tf.fd != -1);
    }

    std::string getSerializedString(::google::protobuf::Message& message) {
        std::string expectedStr;
        message.SerializeToFileDescriptor(tf.fd);
        ReadFileToString(tf.path, &expectedStr);
        return expectedStr;
    }

protected:
    TemporaryFile tf;

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

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), getSerializedString(expected));
    close(fd);
}

TEST_F(IncidentHelperTest, ProcrankParser) {
    const std::string testFile = kTestDataPath + "procrank.txt";
    ProcrankParser parser;
    Procrank expected;

    ProcessProto* process1 = expected.add_processes();
    process1->set_pid(1119);
    process1->set_vss(2607640);
    process1->set_rss(339564);
    process1->set_pss(180278);
    process1->set_uss(114216);
    process1->set_swap(1584);
    process1->set_pswap(46);
    process1->set_uswap(0);
    process1->set_zswap(10);
    process1->set_cmdline("system_server");

    ProcessProto* process2 = expected.add_processes();
    process2->set_pid(649);
    process2->set_vss(11016);
    process2->set_rss(1448);
    process2->set_pss(98);
    process2->set_uss(48);
    process2->set_swap(472);
    process2->set_pswap(342);
    process2->set_uswap(212);
    process2->set_zswap(75);
    process2->set_cmdline("/vendor/bin/qseecomd");

    ProcessProto* total = expected.mutable_summary()->mutable_total();
    total->set_pss(1201993);
    total->set_uss(935300);
    total->set_swap(88164);
    total->set_pswap(31069);
    total->set_uswap(27612);
    total->set_zswap(6826);
    total->set_cmdline("TOTAL");

    expected.mutable_summary()->mutable_zram()
        ->set_raw_text("6828K physical used for 31076K in swap (524284K total swap)");
    expected.mutable_summary()->mutable_ram()
        ->set_raw_text("3843972K total, 281424K free, 116764K buffers, 1777452K cached, 1136K shmem, 217916K slab");

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), getSerializedString(expected));
    close(fd);
}

TEST_F(IncidentHelperTest, ProcrankParserShortHeader) {
    const std::string testFile = kTestDataPath + "procrank_short.txt";
    ProcrankParser parser;
    Procrank expected;

    ProcessProto* process1 = expected.add_processes();
    process1->set_pid(1119);
    process1->set_vss(2607640);
    process1->set_rss(339564);
    process1->set_pss(180278);
    process1->set_uss(114216);
    process1->set_cmdline("system_server");

    ProcessProto* process2 = expected.add_processes();
    process2->set_pid(649);
    process2->set_vss(11016);
    process2->set_rss(1448);
    process2->set_pss(98);
    process2->set_uss(48);
    process2->set_cmdline("/vendor/bin/qseecomd");

    ProcessProto* total = expected.mutable_summary()->mutable_total();
    total->set_pss(1201993);
    total->set_uss(935300);
    total->set_cmdline("TOTAL");

    expected.mutable_summary()->mutable_ram()
        ->set_raw_text("3843972K total, 281424K free, 116764K buffers, 1777452K cached, 1136K shmem, 217916K slab");

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), getSerializedString(expected));
    close(fd);
}
