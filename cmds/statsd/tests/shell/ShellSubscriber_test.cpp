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

#include <gtest/gtest.h>

#include <unistd.h>
#include "frameworks/base/cmds/statsd/src/atoms.pb.h"
#include "frameworks/base/cmds/statsd/src/shell/shell_config.pb.h"
#include "frameworks/base/cmds/statsd/src/shell/shell_data.pb.h"
#include "src/shell/ShellSubscriber.h"
#include "tests/metrics/metrics_test_helper.h"

#include <stdio.h>
#include <vector>

using namespace android::os::statsd;
using android::sp;
using std::vector;
using testing::_;
using testing::Invoke;
using testing::NaggyMock;
using testing::StrictMock;

#ifdef __ANDROID__

class MyResultReceiver : public BnResultReceiver {
public:
    Mutex mMutex;
    Condition mCondition;
    bool mHaveResult = false;
    int32_t mResult = 0;

    virtual void send(int32_t resultCode) {
        AutoMutex _l(mMutex);
        mResult = resultCode;
        mHaveResult = true;
        mCondition.signal();
    }

    int32_t waitForResult() {
        AutoMutex _l(mMutex);
        mCondition.waitRelative(mMutex, 1000000000);
        return mResult;
    }
};

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
    vector<uint8_t> size_buffer(sizeof(bufferSize));
    std::memcpy(size_buffer.data(), &bufferSize, sizeof(bufferSize));
    write(fds_config[1], &bufferSize, sizeof(bufferSize));
    // then write config itself
    vector<uint8_t> buffer(bufferSize);
    config.SerializeToArray(&buffer[0], bufferSize);
    write(fds_config[1], buffer.data(), bufferSize);
    close(fds_config[1]);

    sp<ShellSubscriber> shellClient = new ShellSubscriber(uidMap, pullerManager);
    sp<MyResultReceiver> resultReceiver = new MyResultReceiver();

    // mimic a binder thread that a shell subscriber runs on. it would block.
    std::thread reader([&resultReceiver, &fds_config, &fds_data, &shellClient] {
        shellClient->startNewSubscription(fds_config[0], fds_data[1], resultReceiver, -1);
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

    int expected_data_size = expectedData.ByteSize();

    // now read from the pipe. firstly read the atom size.
    size_t dataSize = 0;
    EXPECT_EQ((int)sizeof(dataSize), read(fds_data[0], &dataSize, sizeof(dataSize)));
    EXPECT_EQ(expected_data_size, (int)dataSize);

    // then read that much data which is the atom in proto binary format
    vector<uint8_t> dataBuffer(dataSize);
    EXPECT_EQ((int)dataSize, read(fds_data[0], dataBuffer.data(), dataSize));

    // make sure the received bytes can be parsed to an atom
    ShellData receivedAtom;
    EXPECT_TRUE(receivedAtom.ParseFromArray(dataBuffer.data(), dataSize) != 0);

    // serialze the expected atom to bytes. and compare. to make sure they are the same.
    vector<uint8_t> atomBuffer(expected_data_size);
    expectedData.SerializeToArray(&atomBuffer[0], expected_data_size);
    EXPECT_EQ(atomBuffer, dataBuffer);
    close(fds_data[0]);
}

TEST(ShellSubscriberTest, testPushedSubscription) {
    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    vector<std::shared_ptr<LogEvent>> pushedList;

    std::shared_ptr<LogEvent> event1 =
            std::make_shared<LogEvent>(29 /*screen_state_atom_id*/, 1000 /*timestamp*/);
    event1->write(::android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    event1->init();
    pushedList.push_back(event1);

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

}  // namespace

TEST(ShellSubscriberTest, testPulledSubscription) {
    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(10016, _))
            .WillRepeatedly(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, 1111L);
                event->write(kUid1);
                event->write(kCpuTime1);
                event->init();
                data->push_back(event);
                // another event
                event = make_shared<LogEvent>(tagId, 1111L);
                event->write(kUid2);
                event->write(kCpuTime2);
                event->init();
                data->push_back(event);
                return true;
            }));

    runShellTest(getPulledConfig(), uidMap, pullerManager, vector<std::shared_ptr<LogEvent>>(),
                 getExpectedShellData());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
