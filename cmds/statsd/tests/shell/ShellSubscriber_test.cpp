// Copyright (C) 2018 The Android Open Source Project
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

#include "src/shell/ShellSubscriber.h"

#include <gtest/gtest.h>
#include <stdio.h>
#include <unistd.h>

#include <vector>

#include "frameworks/base/cmds/statsd/src/atoms.pb.h"
#include "frameworks/base/cmds/statsd/src/shell/shell_config.pb.h"
#include "frameworks/base/cmds/statsd/src/shell/shell_data.pb.h"
#include "stats_event.h"
#include "tests/metrics/metrics_test_helper.h"
#include "tests/statsd_test_util.h"

using namespace android::os::statsd;
using android::sp;
using std::vector;
using testing::_;
using testing::Invoke;
using testing::NaggyMock;
using testing::StrictMock;

#ifdef __ANDROID__

void runShellTest(ShellSubscription config, sp<MockUidMap> uidMap,
                  sp<MockStatsPullerManager> pullerManager,
                  const vector<std::shared_ptr<LogEvent>>& pushedEvents,
                  const ShellData& expectedData) {
    // set up 2 pipes for read/write config and data
    int fds_config[2];
    ASSERT_EQ(0, pipe(fds_config));

    int fds_data[2];
    ASSERT_EQ(0, pipe(fds_data));

    size_t bufferSize = config.ByteSize();
    // write the config to pipe, first write size of the config
    write(fds_config[1], &bufferSize, sizeof(bufferSize));
    // then write config itself
    vector<uint8_t> buffer(bufferSize);
    config.SerializeToArray(&buffer[0], bufferSize);
    write(fds_config[1], buffer.data(), bufferSize);
    close(fds_config[1]);

    sp<ShellSubscriber> shellClient = new ShellSubscriber(uidMap, pullerManager);

    // mimic a binder thread that a shell subscriber runs on. it would block.
    std::thread reader([&shellClient, &fds_config, &fds_data] {
        shellClient->startNewSubscription(fds_config[0], fds_data[1], /*timeoutSec=*/-1);
    });
    reader.detach();

    // let the shell subscriber to receive the config from pipe.
    std::this_thread::sleep_for(100ms);

    if (pushedEvents.size() > 0) {
        // send a log event that matches the config.
        std::thread log_reader([&shellClient, &pushedEvents] {
            for (const auto& event : pushedEvents) {
                shellClient->onLogEvent(*event);
            }
        });

        log_reader.detach();

        if (log_reader.joinable()) {
            log_reader.join();
        }
    }

    // wait for the data to be written.
    std::this_thread::sleep_for(100ms);

    // Because we might receive heartbeats from statsd, consisting of data sizes
    // of 0, encapsulate reads within a while loop.
    bool readAtom = false;
    while (!readAtom) {
        // Read the atom size.
        size_t dataSize = 0;
        read(fds_data[0], &dataSize, sizeof(dataSize));
        if (dataSize == 0) continue;
        EXPECT_EQ(expectedData.ByteSize(), int(dataSize));

        // Read that much data in proto binary format.
        vector<uint8_t> dataBuffer(dataSize);
        EXPECT_EQ((int)dataSize, read(fds_data[0], dataBuffer.data(), dataSize));

        // Make sure the received bytes can be parsed to an atom.
        ShellData receivedAtom;
        EXPECT_TRUE(receivedAtom.ParseFromArray(dataBuffer.data(), dataSize) != 0);

        // Serialize the expected atom to byte array and compare to make sure
        // they are the same.
        vector<uint8_t> expectedAtomBuffer(expectedData.ByteSize());
        expectedData.SerializeToArray(expectedAtomBuffer.data(), expectedData.ByteSize());
        EXPECT_EQ(expectedAtomBuffer, dataBuffer);

        readAtom = true;
    }

    close(fds_data[0]);
    if (reader.joinable()) {
        reader.join();
    }
}

TEST(ShellSubscriberTest, testPushedSubscription) {
    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    vector<std::shared_ptr<LogEvent>> pushedList;

    // Create the LogEvent from an AStatsEvent
    std::unique_ptr<LogEvent> logEvent = CreateScreenStateChangedEvent(
            1000 /*timestamp*/, ::android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    pushedList.push_back(std::move(logEvent));

    // create a simple config to get screen events
    ShellSubscription config;
    config.add_pushed()->set_atom_id(29);

    // this is the expected screen event atom.
    ShellData shellData;
    shellData.add_atom()->mutable_screen_state_changed()->set_state(
            ::android::view::DisplayStateEnum::DISPLAY_STATE_ON);

    runShellTest(config, uidMap, pullerManager, pushedList, shellData);
}

namespace {

int kUid1 = 1000;
int kUid2 = 2000;

int kCpuTime1 = 100;
int kCpuTime2 = 200;

ShellData getExpectedShellData() {
    ShellData shellData;
    auto* atom1 = shellData.add_atom()->mutable_cpu_active_time();
    atom1->set_uid(kUid1);
    atom1->set_time_millis(kCpuTime1);

    auto* atom2 = shellData.add_atom()->mutable_cpu_active_time();
    atom2->set_uid(kUid2);
    atom2->set_time_millis(kCpuTime2);

    return shellData;
}

ShellSubscription getPulledConfig() {
    ShellSubscription config;
    auto* pull_config = config.add_pulled();
    pull_config->mutable_matcher()->set_atom_id(10016);
    pull_config->set_freq_millis(2000);
    return config;
}

shared_ptr<LogEvent> makeCpuActiveTimeAtom(int32_t uid, int64_t timeMillis) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, 10016);
    AStatsEvent_overwriteTimestamp(statsEvent, 1111L);
    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeInt64(statsEvent, timeMillis);

    std::shared_ptr<LogEvent> logEvent = std::make_shared<LogEvent>(/*uid=*/0, /*pid=*/0);
    parseStatsEventToLogEvent(statsEvent, logEvent.get());
    return logEvent;
}

}  // namespace

TEST(ShellSubscriberTest, testPulledSubscription) {
    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    const vector<int32_t> uids = {AID_SYSTEM};
    EXPECT_CALL(*pullerManager, Pull(10016, uids, _, _, _))
            .WillRepeatedly(Invoke([](int tagId, const vector<int32_t>&, const int64_t,
                                      vector<std::shared_ptr<LogEvent>>* data, bool) {
                data->clear();
                data->push_back(makeCpuActiveTimeAtom(/*uid=*/kUid1, /*timeMillis=*/kCpuTime1));
                data->push_back(makeCpuActiveTimeAtom(/*uid=*/kUid2, /*timeMillis=*/kCpuTime2));
                return true;
            }));
    runShellTest(getPulledConfig(), uidMap, pullerManager, vector<std::shared_ptr<LogEvent>>(),
                 getExpectedShellData());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
