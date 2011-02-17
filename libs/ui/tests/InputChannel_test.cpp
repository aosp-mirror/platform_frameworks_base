/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <ui/InputTransport.h>
#include <utils/Timers.h>
#include <utils/StopWatch.h>
#include <gtest/gtest.h>
#include <unistd.h>
#include <time.h>
#include <sys/mman.h>
#include <cutils/ashmem.h>

#include "../../utils/tests/TestHelpers.h"

namespace android {

class InputChannelTest : public testing::Test {
protected:
    virtual void SetUp() { }
    virtual void TearDown() { }
};


TEST_F(InputChannelTest, ConstructorAndDestructor_TakesOwnershipOfFileDescriptors) {
    // Our purpose here is to verify that the input channel destructor closes the
    // file descriptors provided to it.  One easy way is to provide it with one end
    // of a pipe and to check for EPIPE on the other end after the channel is destroyed.
    Pipe fakeAshmem, sendPipe, receivePipe;

    sp<InputChannel> inputChannel = new InputChannel(String8("channel name"),
            fakeAshmem.sendFd, receivePipe.receiveFd, sendPipe.sendFd);

    EXPECT_STREQ("channel name", inputChannel->getName().string())
            << "channel should have provided name";
    EXPECT_EQ(fakeAshmem.sendFd, inputChannel->getAshmemFd())
            << "channel should have provided ashmem fd";
    EXPECT_EQ(receivePipe.receiveFd, inputChannel->getReceivePipeFd())
            << "channel should have provided receive pipe fd";
    EXPECT_EQ(sendPipe.sendFd, inputChannel->getSendPipeFd())
            << "channel should have provided send pipe fd";

    inputChannel.clear(); // destroys input channel

    EXPECT_EQ(-EPIPE, fakeAshmem.readSignal())
            << "channel should have closed ashmem fd when destroyed";
    EXPECT_EQ(-EPIPE, receivePipe.writeSignal())
            << "channel should have closed receive pipe fd when destroyed";
    EXPECT_EQ(-EPIPE, sendPipe.readSignal())
            << "channel should have closed send pipe fd when destroyed";

    // clean up fds of Pipe endpoints that were closed so we don't try to close them again
    fakeAshmem.sendFd = -1;
    receivePipe.receiveFd = -1;
    sendPipe.sendFd = -1;
}

TEST_F(InputChannelTest, OpenInputChannelPair_ReturnsAPairOfConnectedChannels) {
    sp<InputChannel> serverChannel, clientChannel;

    status_t result = InputChannel::openInputChannelPair(String8("channel name"),
            serverChannel, clientChannel);

    ASSERT_EQ(OK, result)
            << "should have successfully opened a channel pair";

    // Name
    EXPECT_STREQ("channel name (server)", serverChannel->getName().string())
            << "server channel should have suffixed name";
    EXPECT_STREQ("channel name (client)", clientChannel->getName().string())
            << "client channel should have suffixed name";

    // Ashmem uniqueness
    EXPECT_NE(serverChannel->getAshmemFd(), clientChannel->getAshmemFd())
            << "server and client channel should have different ashmem fds because it was dup'd";

    // Ashmem usability
    ssize_t serverAshmemSize = ashmem_get_size_region(serverChannel->getAshmemFd());
    ssize_t clientAshmemSize = ashmem_get_size_region(clientChannel->getAshmemFd());
    uint32_t* serverAshmem = static_cast<uint32_t*>(mmap(NULL, serverAshmemSize,
            PROT_READ | PROT_WRITE, MAP_SHARED, serverChannel->getAshmemFd(), 0));
    uint32_t* clientAshmem = static_cast<uint32_t*>(mmap(NULL, clientAshmemSize,
            PROT_READ | PROT_WRITE, MAP_SHARED, clientChannel->getAshmemFd(), 0));
    ASSERT_TRUE(serverAshmem != NULL)
            << "server channel ashmem should be mappable";
    ASSERT_TRUE(clientAshmem != NULL)
            << "client channel ashmem should be mappable";
    *serverAshmem = 0xf00dd00d;
    EXPECT_EQ(0xf00dd00d, *clientAshmem)
            << "ashmem buffer should be shared by client and server";
    munmap(serverAshmem, serverAshmemSize);
    munmap(clientAshmem, clientAshmemSize);

    // Server->Client communication
    EXPECT_EQ(OK, serverChannel->sendSignal('S'))
            << "server channel should be able to send signal to client channel";
    char signal;
    EXPECT_EQ(OK, clientChannel->receiveSignal(& signal))
            << "client channel should be able to receive signal from server channel";
    EXPECT_EQ('S', signal)
            << "client channel should receive the correct signal from server channel";

    // Client->Server communication
    EXPECT_EQ(OK, clientChannel->sendSignal('c'))
            << "client channel should be able to send signal to server channel";
    EXPECT_EQ(OK, serverChannel->receiveSignal(& signal))
            << "server channel should be able to receive signal from client channel";
    EXPECT_EQ('c', signal)
            << "server channel should receive the correct signal from client channel";
}

TEST_F(InputChannelTest, ReceiveSignal_WhenNoSignalPresent_ReturnsAnError) {
    sp<InputChannel> serverChannel, clientChannel;

    status_t result = InputChannel::openInputChannelPair(String8("channel name"),
            serverChannel, clientChannel);

    ASSERT_EQ(OK, result)
            << "should have successfully opened a channel pair";

    char signal;
    EXPECT_EQ(WOULD_BLOCK, clientChannel->receiveSignal(& signal))
            << "receiveSignal should have returned WOULD_BLOCK";
}

TEST_F(InputChannelTest, ReceiveSignal_WhenPeerClosed_ReturnsAnError) {
    sp<InputChannel> serverChannel, clientChannel;

    status_t result = InputChannel::openInputChannelPair(String8("channel name"),
            serverChannel, clientChannel);

    ASSERT_EQ(OK, result)
            << "should have successfully opened a channel pair";

    serverChannel.clear(); // close server channel

    char signal;
    EXPECT_EQ(DEAD_OBJECT, clientChannel->receiveSignal(& signal))
            << "receiveSignal should have returned DEAD_OBJECT";
}

TEST_F(InputChannelTest, SendSignal_WhenPeerClosed_ReturnsAnError) {
    sp<InputChannel> serverChannel, clientChannel;

    status_t result = InputChannel::openInputChannelPair(String8("channel name"),
            serverChannel, clientChannel);

    ASSERT_EQ(OK, result)
            << "should have successfully opened a channel pair";

    serverChannel.clear(); // close server channel

    EXPECT_EQ(DEAD_OBJECT, clientChannel->sendSignal('S'))
            << "sendSignal should have returned DEAD_OBJECT";
}


} // namespace android
