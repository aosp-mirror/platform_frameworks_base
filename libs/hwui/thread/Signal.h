/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_SIGNAL_H
#define ANDROID_HWUI_SIGNAL_H

#include <stdint.h>
#include <sys/types.h>
#include <utils/threads.h>

namespace android {
namespace uirenderer {

class Signal {
public:
    Signal(Condition::WakeUpType type = Condition::WAKE_UP_ALL) : mType(type), mSignaled(false) { }
    ~Signal() { }

    void signal() {
        {
            Mutex::Autolock l(mLock);
            mSignaled = true;
        }
        mCondition.signal(mType);
    }

    void wait() {
        Mutex::Autolock l(mLock);
        while (!mSignaled) {
            mCondition.wait(mLock);
        }
        mSignaled = false;
    }

private:
    Condition::WakeUpType mType;
    volatile bool mSignaled;
    mutable Mutex mLock;
    mutable Condition mCondition;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SIGNAL_H
