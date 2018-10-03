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
#include "src/shell/ShellSubscriber.h"
#include "tests/metrics/metrics_test_helper.h"

#include <stdio.h>
#include <vector>

using namespace android::os::statsd;
using android::sp;
using std::vector;
using testing::NaggyMock;

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

TEST(ShellSubscriberTest, testPushedSubscription) {
    // set up 2 pipes for read/write config and data
    int fds_config[2];
    ASSERT_EQ(0, pipe(fds_config));

    int fds_data[2];
    ASSERT_EQ(0, pipe(fds_data));

    // create a simple config to get screen events
    ShellSubscription config;
    config.add_pushed()->set_atom_id(29);

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

    // create a shell subscriber.
    sp<MockUidMap> uidMap = new NaggyMock<MockUidMap>();
    sp<ShellSubscriber> shellClient = new ShellSubscriber(uidMap);
    sp<MyResultReceiver> resultReceiver = new MyResultReceiver();

    LogEvent event1(29, 1000);
    event1.write(2);
    event1.init();

    // mimic a binder thread that a shell subscriber runs on. it would block.
    std::thread reader([&resultReceiver, &fds_config, &fds_data, &shellClient] {
        shellClient->startNewSubscription(fds_config[0], fds_data[1], resultReceiver);
    });
    reader.detach();

    // let the shell subscriber to receive the config from pipe.
    std::this_thread::sleep_for(100ms);

    // send a log event that matches the config.
    std::thread log_reader([&shellClient, &event1] { shellClient->onLogEvent(event1); });
    log_reader.detach();

    if (log_reader.joinable()) {
        log_reader.join();
    }

    // wait for the data to be written.
    std::this_thread::sleep_for(100ms);

    // this is the expected screen event atom.
    Atom atom;
    atom.mutable_screen_state_changed()->set_state(
            ::android::view::DisplayStateEnum::DISPLAY_STATE_ON);

    int atom_size = atom.ByteSize();

    // now read from the pipe. firstly read the atom size.
    size_t dataSize = 0;
    EXPECT_EQ((int)sizeof(dataSize), read(fds_data[0], &dataSize, sizeof(dataSize)));
    EXPECT_EQ(atom_size, (int)dataSize);

    // then read that much data which is the atom in proto binary format
    vector<uint8_t> dataBuffer(dataSize);
    EXPECT_EQ((int)dataSize, read(fds_data[0], dataBuffer.data(), dataSize));

    // make sure the received bytes can be parsed to an atom
    Atom receivedAtom;
    EXPECT_TRUE(receivedAtom.ParseFromArray(dataBuffer.data(), dataSize) != 0);

    // serialze the expected atom to bytes. and compare. to make sure they are the same.
    vector<uint8_t> atomBuffer(atom_size);
    atom.SerializeToArray(&atomBuffer[0], atom_size);
    EXPECT_EQ(atomBuffer, dataBuffer);
    close(fds_data[0]);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
