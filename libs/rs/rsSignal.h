/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_SIGNAL_H
#define ANDROID_RS_SIGNAL_H


#include "rsUtils.h"

namespace android {
namespace renderscript {

class Signal {
public:
    Signal();
    ~Signal();

    bool init();

    void set();

    // returns true if the signal occured
    // false for timeout
    bool wait(uint64_t timeout = 0);

protected:
    bool mSet;
    pthread_mutex_t mMutex;
    pthread_cond_t mCondition;
};

}
}

#endif

