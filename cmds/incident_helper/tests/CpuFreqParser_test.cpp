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

#include "CpuFreqParser.h"

#include "frameworks/base/core/proto/android/os/cpufreq.pb.h"

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

class CpuFreqParserTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_TRUE(tf.fd != -1);
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(CpuFreqParserTest, Success) {
    const string testFile = kTestDataPath + "cpufreq.txt";
    CpuFreqParser parser;
    CpuFreqProto expected;

    long jiffyHz = sysconf(_SC_CLK_TCK);
    expected.set_jiffy_hz(jiffyHz);

    CpuFreqProto::Stats::TimeInState* state;

    CpuFreqProto::Stats* cpu0 = expected.add_cpu_freqs();
    cpu0->set_cpu_name("cpu0");
    state = cpu0->add_times();
    state->set_state_khz(307200);
    state->set_time_jiffy(23860761);
    state = cpu0->add_times();
    state->set_state_khz(384000);
    state->set_time_jiffy(83124);
    state = cpu0->add_times();
    state->set_state_khz(768000);
    state->set_time_jiffy(22652);

    CpuFreqProto::Stats* cpu1 = expected.add_cpu_freqs();
    cpu1->set_cpu_name("cpu1");
    state = cpu1->add_times();
    state->set_state_khz(307200);
    state->set_time_jiffy(23860761);
    state = cpu1->add_times();
    state->set_state_khz(384000);
    state->set_time_jiffy(83124);
    state = cpu1->add_times();
    state->set_state_khz(768000);
    state->set_time_jiffy(22652);

    CpuFreqProto::Stats* cpu2 = expected.add_cpu_freqs();
    cpu2->set_cpu_name("cpu2");
    state = cpu2->add_times();
    state->set_state_khz(307200);
    state->set_time_jiffy(23890935);
    state = cpu2->add_times();
    state->set_state_khz(384000);
    state->set_time_jiffy(29383);
    state = cpu2->add_times();
    state->set_state_khz(748800);
    state->set_time_jiffy(10547);
    state = cpu2->add_times();
    state->set_state_khz(825600);
    state->set_time_jiffy(13173);

    CpuFreqProto::Stats* cpu3 = expected.add_cpu_freqs();
    cpu3->set_cpu_name("cpu3");
    state = cpu3->add_times();
    state->set_state_khz(307200);
    state->set_time_jiffy(23890935);
    state = cpu3->add_times();
    state->set_state_khz(384000);
    state->set_time_jiffy(29383);
    state = cpu3->add_times();
    state->set_state_khz(748800);
    state->set_time_jiffy(10547);
    state = cpu3->add_times();
    state->set_state_khz(825600);
    state->set_time_jiffy(13173);

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expected.SerializeAsString());
    close(fd);
}
