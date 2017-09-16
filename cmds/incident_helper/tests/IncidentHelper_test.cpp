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
#include "frameworks/base/core/proto/android/os/pagetypeinfo.pb.h"
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
using namespace std;
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

    string getSerializedString(::google::protobuf::Message& message) {
        string expectedStr;
        message.SerializeToFileDescriptor(tf.fd);
        ReadFileToString(tf.path, &expectedStr);
        return expectedStr;
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
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
    const string testFile = kTestDataPath + "kernel_wakeups.txt";
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
    const string testFile = kTestDataPath + "procrank.txt";
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
    const string testFile = kTestDataPath + "procrank_short.txt";
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

TEST_F(IncidentHelperTest, PageTypeInfoParser) {
    const string testFile = kTestDataPath + "pagetypeinfo.txt";
    PageTypeInfoParser parser;
    PageTypeInfo expected;

    expected.set_page_block_order(10);
    expected.set_pages_per_block(1024);

    MigrateTypeProto* mt1 = expected.add_migrate_types();
    mt1->set_node(0);
    mt1->set_zone("DMA");
    mt1->set_type("Unmovable");
    int arr1[] = { 426, 279, 226, 1, 1, 1, 0, 0, 2, 2, 0};
    for (auto i=0; i<11; i++) {
        mt1->add_free_pages_count(arr1[i]);
    }

    MigrateTypeProto* mt2 = expected.add_migrate_types();
    mt2->set_node(0);
    mt2->set_zone("Normal");
    mt2->set_type("Reclaimable");
    int arr2[] = { 953, 773, 437, 154, 92, 26, 15, 14, 12, 7, 0};
    for (auto i=0; i<11; i++) {
        mt2->add_free_pages_count(arr2[i]);
    }

    BlockProto* block1 = expected.add_blocks();
    block1->set_node(0);
    block1->set_zone("DMA");
    block1->set_unmovable(74);
    block1->set_reclaimable(9);
    block1->set_movable(337);
    block1->set_cma(41);
    block1->set_reserve(1);
    block1->set_isolate(0);


    BlockProto* block2 = expected.add_blocks();
    block2->set_node(0);
    block2->set_zone("Normal");
    block2->set_unmovable(70);
    block2->set_reclaimable(12);
    block2->set_movable(423);
    block2->set_cma(0);
    block2->set_reserve(1);
    block2->set_isolate(0);

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), getSerializedString(expected));
    close(fd);
}