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

#include "PsParser.h"

#include "frameworks/base/core/proto/android/os/ps.pb.h"

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

class PsParserTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_TRUE(tf.fd != -1);
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(PsParserTest, Normal) {
    const string testFile = kTestDataPath + "ps.txt";
    PsParser parser;
    PsProto expected;
    PsProto got;

    PsProto::Process* record1 = expected.add_processes();
    record1->set_label("u:r:init:s0");
    record1->set_user("root");
    record1->set_pid(1);
    record1->set_tid(1);
    record1->set_ppid(0);
    record1->set_vsz(15816);
    record1->set_rss(2636);
    record1->set_wchan("SyS_epoll_wait");
    record1->set_addr("0");
    record1->set_s(PsProto_Process_ProcessStateCode_STATE_S);
    record1->set_pri(19);
    record1->set_ni(0);
    record1->set_rtprio("-");
    record1->set_sch(PsProto_Process_SchedulingPolicy_SCH_NORMAL);
    record1->set_pcy(PsProto::Process::POLICY_FG);
    record1->set_time("00:00:01");
    record1->set_cmd("init");

    PsProto::Process* record2 = expected.add_processes();
    record2->set_label("u:r:kernel:s0");
    record2->set_user("root");
    record2->set_pid(2);
    record2->set_tid(2);
    record2->set_ppid(0);
    record2->set_vsz(0);
    record2->set_rss(0);
    record2->set_wchan("kthreadd");
    record2->set_addr("0");
    record2->set_s(PsProto_Process_ProcessStateCode_STATE_S);
    record2->set_pri(19);
    record2->set_ni(0);
    record2->set_rtprio("-");
    record2->set_sch(PsProto_Process_SchedulingPolicy_SCH_NORMAL);
    record2->set_pcy(PsProto::Process::POLICY_FG);
    record2->set_time("00:00:00");
    record2->set_cmd("kthreadd");

    PsProto::Process* record3 = expected.add_processes();
    record3->set_label("u:r:surfaceflinger:s0");
    record3->set_user("system");
    record3->set_pid(499);
    record3->set_tid(534);
    record3->set_ppid(1);
    record3->set_vsz(73940);
    record3->set_rss(22024);
    record3->set_wchan("futex_wait_queue_me");
    record3->set_addr("0");
    record3->set_s(PsProto_Process_ProcessStateCode_STATE_S);
    record3->set_pri(42);
    record3->set_ni(-9);
    record3->set_rtprio("2");
    record3->set_sch(PsProto_Process_SchedulingPolicy_SCH_FIFO);
    record3->set_pcy(PsProto::Process::POLICY_FG);
    record3->set_time("00:00:00");
    record3->set_cmd("EventThread");

    PsProto::Process* record4 = expected.add_processes();
    record4->set_label("u:r:hal_gnss_default:s0");
    record4->set_user("gps");
    record4->set_pid(670);
    record4->set_tid(2004);
    record4->set_ppid(1);
    record4->set_vsz(43064);
    record4->set_rss(7272);
    record4->set_wchan("poll_schedule_timeout");
    record4->set_addr("0");
    record4->set_s(PsProto_Process_ProcessStateCode_STATE_S);
    record4->set_pri(19);
    record4->set_ni(0);
    record4->set_rtprio("-");
    record4->set_sch(PsProto_Process_SchedulingPolicy_SCH_NORMAL);
    record4->set_pcy(PsProto::Process::POLICY_FG);
    record4->set_time("00:00:00");
    record4->set_cmd("Loc_hal_worker");

    PsProto::Process* record5 = expected.add_processes();
    record5->set_label("u:r:platform_app:s0:c512,c768");
    record5->set_user("u0_a48");
    record5->set_pid(1660);
    record5->set_tid(1976);
    record5->set_ppid(806);
    record5->set_vsz(4468612);
    record5->set_rss(138328);
    record5->set_wchan("binder_thread_read");
    record5->set_addr("0");
    record5->set_s(PsProto_Process_ProcessStateCode_STATE_S);
    record5->set_pri(35);
    record5->set_ni(-16);
    record5->set_rtprio("-");
    record5->set_sch(PsProto_Process_SchedulingPolicy_SCH_NORMAL);
    record5->set_pcy(PsProto::Process::POLICY_TA);
    record5->set_time("00:00:00");
    record5->set_cmd("HwBinder:1660_1");

    PsProto::Process* record6 = expected.add_processes();
    record6->set_label("u:r:perfd:s0");
    record6->set_user("root");
    record6->set_pid(1939);
    record6->set_tid(1946);
    record6->set_ppid(1);
    record6->set_vsz(18132);
    record6->set_rss(2088);
    record6->set_wchan("__skb_recv_datagram");
    record6->set_addr("7b9782fd14");
    record6->set_s(PsProto_Process_ProcessStateCode_STATE_S);
    record6->set_pri(19);
    record6->set_ni(0);
    record6->set_rtprio("-");
    record6->set_sch(PsProto_Process_SchedulingPolicy_SCH_NORMAL);
    record6->set_pcy(PsProto::Process::POLICY_UNKNOWN);
    record6->set_time("00:00:00");
    record6->set_cmd("perfd");

    PsProto::Process* record7 = expected.add_processes();
    record7->set_label("u:r:perfd:s0");
    record7->set_user("root");
    record7->set_pid(1939);
    record7->set_tid(1955);
    record7->set_ppid(1);
    record7->set_vsz(18132);
    record7->set_rss(2088);
    record7->set_wchan("do_sigtimedwait");
    record7->set_addr("7b9782ff6c");
    record7->set_s(PsProto_Process_ProcessStateCode_STATE_S);
    record7->set_pri(19);
    record7->set_ni(0);
    record7->set_rtprio("-");
    record7->set_sch(PsProto_Process_SchedulingPolicy_SCH_NORMAL);
    record7->set_pcy(PsProto::Process::POLICY_UNKNOWN);
    record7->set_time("00:00:00");
    record7->set_cmd("POSIX timer 0");

    PsProto::Process* record8 = expected.add_processes();
    record8->set_label("u:r:shell:s0");
    record8->set_user("shell");
    record8->set_pid(2645);
    record8->set_tid(2645);
    record8->set_ppid(802);
    record8->set_vsz(11664);
    record8->set_rss(2972);
    record8->set_wchan("0");
    record8->set_addr("7f67a2f8b4");
    record8->set_s(PsProto_Process_ProcessStateCode_STATE_R);
    record8->set_pri(19);
    record8->set_ni(0);
    record8->set_rtprio("-");
    record8->set_sch(PsProto_Process_SchedulingPolicy_SCH_NORMAL);
    record8->set_pcy(PsProto::Process::POLICY_FG);
    record8->set_time("00:00:00");
    record8->set_cmd("ps");

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    got.ParseFromString(GetCapturedStdout());
    bool matches = true;

    if (got.processes_size() != expected.processes_size()) {
        fprintf(stderr, "Got %d processes, want %d\n", got.processes_size(), expected.processes_size());
        matches = false;
    } else {
        int n = got.processes_size();
        for (int i = 0; i < n; i++) {
            PsProto::Process g = got.processes(i);
            PsProto::Process e = expected.processes(i);

            if (g.label() != e.label()) {
                fprintf(stderr, "prcs[%d]: Invalid label. Got %s, want %s\n", i, g.label().c_str(), e.label().c_str());
                matches = false;
            }
            if (g.user() != e.user()) {
                fprintf(stderr, "prcs[%d]: Invalid user. Got %s, want %s\n", i, g.user().c_str(), e.user().c_str());
                matches = false;
            }
            if (g.pid() != e.pid()) {
                fprintf(stderr, "prcs[%d]: Invalid pid. Got %d, want %d\n", i, g.pid(), e.pid());
                matches = false;
            }
            if (g.tid() != e.tid()) {
                fprintf(stderr, "prcs[%d]: Invalid tid. Got %d, want %d\n", i, g.tid(), e.tid());
                matches = false;
            }
            if (g.ppid() != e.ppid()) {
                fprintf(stderr, "prcs[%d]: Invalid ppid. Got %d, want %d\n", i, g.ppid(), e.ppid());
                matches = false;
            }
            if (g.vsz() != e.vsz()) {
                fprintf(stderr, "prcs[%d]: Invalid vsz. Got %d, want %d\n", i, g.vsz(), e.vsz());
                matches = false;
            }
            if (g.rss() != e.rss()) {
                fprintf(stderr, "prcs[%d]: Invalid rss. Got %d, want %d\n", i, g.rss(), e.rss());
                matches = false;
            }
            if (g.wchan() != e.wchan()) {
                fprintf(stderr, "prcs[%d]: Invalid wchan. Got %s, want %s\n", i, g.wchan().c_str(), e.wchan().c_str());
                matches = false;
            }
            if (g.addr() != e.addr()) {
                fprintf(stderr, "prcs[%d]: Invalid addr. Got %s, want %s\n", i, g.addr().c_str(), e.addr().c_str());
                matches = false;
            }
            if (g.s() != e.s()) {
                fprintf(stderr, "prcs[%d]: Invalid s. Got %u, want %u\n", i, g.s(), e.s());
                matches = false;
            }
            if (g.pri() != e.pri()) {
                fprintf(stderr, "prcs[%d]: Invalid pri. Got %d, want %d\n", i, g.pri(), e.pri());
                matches = false;
            }
            if (g.ni() != e.ni()) {
                fprintf(stderr, "prcs[%d]: Invalid ni. Got %d, want %d\n", i, g.ni(), e.ni());
                matches = false;
            }
            if (g.rtprio() != e.rtprio()) {
                fprintf(stderr, "prcs[%d]: Invalid rtprio. Got %s, want %s\n", i, g.rtprio().c_str(), e.rtprio().c_str());
                matches = false;
            }
            if (g.sch() != e.sch()) {
                fprintf(stderr, "prcs[%d]: Invalid sch. Got %u, want %u\n", i, g.sch(), e.sch());
                matches = false;
            }
            if (g.pcy() != e.pcy()) {
                fprintf(stderr, "prcs[%d]: Invalid pcy. Got %u, want %u\n", i, g.pcy(), e.pcy());
                matches = false;
            }
            if (g.time() != e.time()) {
                fprintf(stderr, "prcs[%d]: Invalid time. Got %s, want %s\n", i, g.time().c_str(), e.time().c_str());
                matches = false;
            }
            if (g.cmd() != e.cmd()) {
                fprintf(stderr, "prcs[%d]: Invalid cmd. Got %s, want %s\n", i, g.cmd().c_str(), e.cmd().c_str());
                matches = false;
            }
        }
    }

    EXPECT_TRUE(matches);
    close(fd);
}
