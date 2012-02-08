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

#ifndef ANDROID_RS_FIFO_H
#define ANDROID_RS_FIFO_H


#include "rsUtils.h"

namespace android {
namespace renderscript {


// A simple FIFO to be used as a producer / consumer between two
// threads.  One is writer and one is reader.  The common cases
// will not require locking.  It is not threadsafe for multiple
// readers or writers by design.

class Fifo {
protected:
    Fifo();
    virtual ~Fifo();

public:
    bool virtual writeAsync(const void *data, size_t bytes, bool waitForSpace = true) = 0;
    void virtual writeWaitReturn(void *ret, size_t retSize) = 0;
    size_t virtual read(void *data, size_t bytes, bool doWait = true, uint64_t timeToWait = 0) = 0;
    void virtual readReturn(const void *data, size_t bytes) = 0;

    void virtual flush() = 0;

};

}
}
#endif
