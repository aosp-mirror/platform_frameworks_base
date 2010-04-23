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

#ifndef TESTHELPERS_H
#define TESTHELPERS_H

#include <utils/threads.h>

namespace android {

class DelayedTask : public Thread {
    int mDelayMillis;

public:
    DelayedTask(int delayMillis) : mDelayMillis(delayMillis) { }

protected:
    virtual ~DelayedTask() { }

    virtual void doTask() = 0;

    virtual bool threadLoop() {
        usleep(mDelayMillis * 1000);
        doTask();
        return false;
    }
};

} // namespace android

#endif // TESTHELPERS_H
