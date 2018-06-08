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

#include "CpuInfoParser.h"

#include "frameworks/base/core/proto/android/os/cpuinfo.pb.h"

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

class CpuInfoParserTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_TRUE(tf.fd != -1);
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(CpuInfoParserTest, Success) {
    const string testFile = kTestDataPath + "cpuinfo.txt";
    CpuInfoParser parser;
    CpuInfoProto expected;

    CpuInfoProto::TaskStats* taskStats = expected.mutable_task_stats();
    taskStats->set_total(2038);
    taskStats->set_running(1);
    taskStats->set_sleeping(2033);
    taskStats->set_stopped(0);
    taskStats->set_zombie(0);

    CpuInfoProto::MemStats* mem = expected.mutable_mem();
    mem->set_total(3842668);
    mem->set_used(3761936);
    mem->set_free(80732);
    mem->set_buffers(220188);

    CpuInfoProto::MemStats* swap = expected.mutable_swap();
    swap->set_total(524284);
    swap->set_used(25892);
    swap->set_free(498392);
    swap->set_cached(1316952);

    CpuInfoProto::CpuUsage* usage = expected.mutable_cpu_usage();
    usage->set_cpu(400);
    usage->set_user(17);
    usage->set_nice(0);
    usage->set_sys(43);
    usage->set_idle(338);
    usage->set_iow(0);
    usage->set_irq(0);
    usage->set_sirq(1);
    usage->set_host(0);

    // This is a special line which is able to be parsed by the CpuInfoParser
    CpuInfoProto::Task* task1 = expected.add_tasks();
    task1->set_pid(29438);
    task1->set_tid(29438);
    task1->set_user("rootabcdefghij");
    task1->set_pr("20");
    task1->set_ni(0);
    task1->set_cpu(57.9);
    task1->set_s(CpuInfoProto::Task::STATUS_R);
    task1->set_virt("14M");
    task1->set_res("3.8M");
    task1->set_pcy(CpuInfoProto::Task::POLICY_UNKNOWN);
    task1->set_cmd("top test");
    task1->set_name("top");

    CpuInfoProto::Task* task2 = expected.add_tasks();
    task2->set_pid(916);
    task2->set_tid(916);
    task2->set_user("system");
    task2->set_pr("18");
    task2->set_ni(-2);
    task2->set_cpu(1.4);
    task2->set_s(CpuInfoProto::Task::STATUS_S);
    task2->set_virt("4.6G");
    task2->set_res("404M");
    task2->set_pcy(CpuInfoProto::Task::POLICY_fg);
    task2->set_cmd("system_server");
    task2->set_name("system_server");

    CpuInfoProto::Task* task3 = expected.add_tasks();
    task3->set_pid(28);
    task3->set_tid(28);
    task3->set_user("root");
    task3->set_pr("-2");
    task3->set_ni(0);
    task3->set_cpu(1.4);
    task3->set_s(CpuInfoProto::Task::STATUS_S);
    task3->set_virt("0");
    task3->set_res("0");
    task3->set_pcy(CpuInfoProto::Task::POLICY_bg);
    task3->set_cmd("rcuc/3");
    task3->set_name("[rcuc/3]");

    CpuInfoProto::Task* task4 = expected.add_tasks();
    task4->set_pid(27);
    task4->set_tid(27);
    task4->set_user("root");
    task4->set_pr("RT");
    task4->set_ni(0);
    task4->set_cpu(1.4);
    task4->set_s(CpuInfoProto::Task::STATUS_S);
    task4->set_virt("0");
    task4->set_res("0");
    task4->set_pcy(CpuInfoProto::Task::POLICY_ta);
    task4->set_cmd("migration/3");
    task4->set_name("[migration/3]");

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expected.SerializeAsString());
    close(fd);
}
