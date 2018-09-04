/*
 * Copyright (C) 2018 The Android Open Source Project
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
#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "ShellSubscriber.h"

#include "matchers/matcher_util.h"

#include <android-base/file.h>

using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {

void ShellSubscriber::startNewSubscription(int in, int out, sp<IResultReceiver> resultReceiver) {
    VLOG("start new shell subscription");
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mResultReceiver != nullptr) {
            VLOG("Only one shell subscriber is allowed.");
            return;
        }
        mInput = in;
        mOutput = out;
        mResultReceiver = resultReceiver;
        IInterface::asBinder(mResultReceiver)->linkToDeath(this);
    }

    // Spawn another thread to read the config updates from the input file descriptor
    std::thread reader([in, this] { readConfig(in); });
    reader.detach();

    std::unique_lock<std::mutex> lk(mMutex);

    mShellDied.wait(lk, [this, resultReceiver] { return mResultReceiver != resultReceiver; });
    if (reader.joinable()) {
        reader.join();
    }
}

void ShellSubscriber::updateConfig(const ShellSubscription& config) {
    std::lock_guard<std::mutex> lock(mMutex);
    mPushedMatchers.clear();
    for (const auto& pushed : config.pushed()) {
        mPushedMatchers.push_back(pushed);
        VLOG("adding matcher for atom %d", pushed.atom_id());
    }
}

void ShellSubscriber::readConfig(int in) {
    if (in <= 0) {
        return;
    }

    while (1) {
        size_t bufferSize = 0;
        int result = 0;
        if ((result = read(in, &bufferSize, sizeof(bufferSize))) == 0) {
            VLOG("Done reading");
            break;
        } else if (result < 0 || result != sizeof(bufferSize)) {
            ALOGE("Error reading config size");
            break;
        }

        vector<uint8_t> buffer(bufferSize);
        if ((result = read(in, buffer.data(), bufferSize)) > 0 && ((size_t)result) == bufferSize) {
            ShellSubscription config;
            if (config.ParseFromArray(buffer.data(), bufferSize)) {
                updateConfig(config);
            } else {
                ALOGE("error parsing the config");
                break;
            }
        } else {
            VLOG("Error reading the config, returned: %d, expecting %zu", result, bufferSize);
            break;
        }
    }
}

void ShellSubscriber::cleanUpLocked() {
    // The file descriptors will be closed by binder.
    mInput = 0;
    mOutput = 0;
    mResultReceiver = nullptr;
    mPushedMatchers.clear();
    VLOG("done clean up");
}

void ShellSubscriber::onLogEvent(const LogEvent& event) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mOutput <= 0) {
        return;
    }

    for (const auto& matcher : mPushedMatchers) {
        if (matchesSimple(*mUidMap, matcher, event)) {
            // First write the payload size.
            size_t bufferSize = mProto.size();
            write(mOutput, &bufferSize, sizeof(bufferSize));

            // Then write the payload.
            event.ToProto(mProto);
            mProto.flush(mOutput);
            mProto.clear();
            break;
        }
    }
}

void ShellSubscriber::binderDied(const wp<IBinder>& who) {
    {
        VLOG("Shell exits");
        std::lock_guard<std::mutex> lock(mMutex);
        cleanUpLocked();
    }
    mShellDied.notify_all();
}

}  // namespace statsd
}  // namespace os
}  // namespace android