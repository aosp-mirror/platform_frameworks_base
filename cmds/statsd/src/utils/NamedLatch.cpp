/*
 * Copyright (C) 2020 The Android Open Source Project
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
#define DEBUG false  // STOPSHIP if true

#include "NamedLatch.h"

using namespace std;

namespace android {
namespace os {
namespace statsd {

NamedLatch::NamedLatch(const set<string>& eventNames) : mRemainingEventNames(eventNames) {
}

void NamedLatch::countDown(const string& eventName) {
    bool notify = false;
    {
        lock_guard<mutex> lg(mMutex);
        mRemainingEventNames.erase(eventName);
        notify = mRemainingEventNames.empty();
    }
    if (notify) {
        mConditionVariable.notify_all();
    }
}

void NamedLatch::wait() const {
    unique_lock<mutex> unique_lk(mMutex);
    mConditionVariable.wait(unique_lk, [this] { return mRemainingEventNames.empty(); });
}

}  // namespace statsd
}  // namespace os
}  // namespace android
