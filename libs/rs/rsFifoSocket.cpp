/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "rsFifoSocket.h"
#include "utils/Timers.h"
#include "utils/StopWatch.h"

#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include <unistd.h>
#include <poll.h>
#include <sys/types.h>
#include <sys/socket.h>

using namespace android;
using namespace android::renderscript;

FifoSocket::FifoSocket() {
    mShutdown = false;
}

FifoSocket::~FifoSocket() {

}

bool FifoSocket::init(bool supportNonBlocking, bool supportReturnValues, size_t maxDataSize) {
    int ret = socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    return false;
}

void FifoSocket::shutdown() {
    mShutdown = true;
    uint64_t d = 0;
    ::send(sv[0], &d, sizeof(d), 0);
    ::send(sv[1], &d, sizeof(d), 0);
    close(sv[0]);
    close(sv[1]);
}

bool FifoSocket::writeAsync(const void *data, size_t bytes, bool waitForSpace) {
    if (bytes == 0) {
        return true;
    }
    //ALOGE("writeAsync %p %i", data, bytes);
    size_t ret = ::send(sv[0], data, bytes, 0);
    //ALOGE("writeAsync ret %i", ret);
    rsAssert(ret == bytes);
    return true;
}

void FifoSocket::writeWaitReturn(void *retData, size_t retBytes) {
    if (mShutdown) {
        return;
    }

    //ALOGE("writeWaitReturn %p %i", retData, retBytes);
    size_t ret = ::recv(sv[0], retData, retBytes, MSG_WAITALL);
    //ALOGE("writeWaitReturn %i", ret);
    rsAssert(ret == retBytes);
}

size_t FifoSocket::read(void *data, size_t bytes) {
    if (mShutdown) {
        return 0;
    }

    //ALOGE("read %p %i", data, bytes);
    size_t ret = ::recv(sv[1], data, bytes, MSG_WAITALL);
    rsAssert(ret == bytes || mShutdown);
    //ALOGE("read ret %i  bytes %i", ret, bytes);
    if (mShutdown) {
        ret = 0;
    }
    return ret;
}

bool FifoSocket::isEmpty() {
    struct pollfd p;
    p.fd = sv[1];
    p.events = POLLIN;
    int r = poll(&p, 1, 0);
    //ALOGE("poll r=%i", r);
    return r == 0;
}


void FifoSocket::readReturn(const void *data, size_t bytes) {
    //ALOGE("readReturn %p %Zu", data, bytes);
    size_t ret = ::send(sv[1], data, bytes, 0);
    //ALOGE("readReturn %Zu", ret);
    //rsAssert(ret == bytes);
}


