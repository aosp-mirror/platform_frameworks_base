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
#include <sys/types.h>
#include <sys/socket.h>

using namespace android;
using namespace android::renderscript;

FifoSocket::FifoSocket() {
    sequence = 1;
}

FifoSocket::~FifoSocket() {

}

bool FifoSocket::init() {
    int ret = socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    return false;
}

void FifoSocket::shutdown() {
}

void FifoSocket::writeAsync(const void *data, size_t bytes) {
    size_t ret = ::write(sv[0], data, bytes);
    rsAssert(ret == bytes);
}

void FifoSocket::writeWaitReturn(void *retData, size_t retBytes) {
    size_t ret = ::read(sv[1], retData, retBytes);
    rsAssert(ret == retBytes);
}

size_t FifoSocket::read(void *data, size_t bytes) {
    size_t ret = ::read(sv[0], data, bytes);
    rsAssert(ret == bytes);
    return ret;
}

void FifoSocket::readReturn(const void *data, size_t bytes) {
    size_t ret = ::write(sv[1], data, bytes);
    rsAssert(ret == bytes);
}


void FifoSocket::flush() {
}


