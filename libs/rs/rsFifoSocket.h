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

#ifndef ANDROID_RS_FIFO_SOCKET_H
#define ANDROID_RS_FIFO_SOCKET_H


#include "rsFifo.h"

namespace android {
namespace renderscript {


class FifoSocket {
public:
    FifoSocket();
    virtual ~FifoSocket();

    bool init(bool supportNonBlocking = true,
              bool supportReturnValues = true,
              size_t maxDataSize = 0);
    void shutdown();

    bool writeAsync(const void *data, size_t bytes, bool waitForSpace = true);
    void writeWaitReturn(void *ret, size_t retSize);
    size_t read(void *data, size_t bytes);
    void readReturn(const void *data, size_t bytes);
    bool isEmpty();

    int getWriteFd() {return sv[0];}
    int getReadFd() {return sv[1];}

protected:
    int sv[2];
    bool mShutdown;
};

}
}

#endif
