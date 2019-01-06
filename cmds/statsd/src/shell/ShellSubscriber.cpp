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

#include <android-base/file.h>
#include "matchers/matcher_util.h"
#include "stats_log_util.h"

using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {

const static int FIELD_ID_ATOM = 1;

void ShellSubscriber::startNewSubscription(int in, int out, sp<IResultReceiver> resultReceiver,
                                           int timeoutSec) {
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

    // Note that the following is blocking, and it's intended as we cannot return until the shell
    // cmd exits, otherwise all resources & FDs will be automatically closed.

    // Read config forever until EOF is reached. Clients may send multiple configs -- each new
    // config replace the previous one.
    readConfig(in);
    VLOG("timeout : %d", timeoutSec);

    // Now we have read an EOF we now wait for the semaphore until the client exits.
    VLOG("Now wait for client to exit");
    std::unique_lock<std::mutex> lk(mMutex);

    if (timeoutSec > 0) {
        mShellDied.wait_for(lk, timeoutSec * 1s,
                            [this, resultReceiver] { return mResultReceiver != resultReceiver; });
    } else {
        mShellDied.wait(lk, [this, resultReceiver] { return mResultReceiver != resultReceiver; });
    }
}

void ShellSubscriber::updateConfig(const ShellSubscription& config) {
    std::lock_guard<std::mutex> lock(mMutex);
    mPushedMatchers.clear();
    mPulledInfo.clear();

    for (const auto& pushed : config.pushed()) {
        mPushedMatchers.push_back(pushed);
        VLOG("adding matcher for atom %d", pushed.atom_id());
    }

    int64_t token = getElapsedRealtimeNs();
    mPullToken = token;

    int64_t minInterval = -1;
    for (const auto& pulled : config.pulled()) {
        // All intervals need to be multiples of the min interval.
        if (minInterval < 0 || pulled.freq_millis() < minInterval) {
            minInterval = pulled.freq_millis();
        }

        mPulledInfo.emplace_back(pulled.matcher(), pulled.freq_millis());
        VLOG("adding matcher for pulled atom %d", pulled.matcher().atom_id());
    }

    if (mPulledInfo.size() > 0 && minInterval > 0) {
        // This thread is guaranteed to terminate after it detects the token is different or
        // cleaned up.
        std::thread puller([token, minInterval, this] { startPull(token, minInterval); });
        puller.detach();
    }
}

void ShellSubscriber::writeToOutputLocked(const vector<std::shared_ptr<LogEvent>>& data,
                                          const SimpleAtomMatcher& matcher) {
    if (mOutput == 0) return;
    int count = 0;
    mProto.clear();
    for (const auto& event : data) {
        VLOG("%s", event->ToString().c_str());
        if (matchesSimple(*mUidMap, matcher, *event)) {
            VLOG("matched");
            count++;
            uint64_t atomToken = mProto.start(util::FIELD_TYPE_MESSAGE |
                                              util::FIELD_COUNT_REPEATED | FIELD_ID_ATOM);
            event->ToProto(mProto);
            mProto.end(atomToken);
        }
    }

    if (count > 0) {
        // First write the payload size.
        size_t bufferSize = mProto.size();
        write(mOutput, &bufferSize, sizeof(bufferSize));
        VLOG("%d atoms, proto size: %zu", count, bufferSize);
        // Then write the payload.
        mProto.flush(mOutput);
    }
    mProto.clear();
}

void ShellSubscriber::startPull(int64_t token, int64_t intervalMillis) {
    while (1) {
        int64_t nowMillis = getElapsedRealtimeMillis();
        {
            std::lock_guard<std::mutex> lock(mMutex);
            if (mPulledInfo.size() == 0 || mPullToken != token) {
                VLOG("Pulling thread %lld done!", (long long)token);
                return;
            }
            for (auto& pullInfo : mPulledInfo) {
                if (pullInfo.mPrevPullElapsedRealtimeMs + pullInfo.mInterval < nowMillis) {
                    VLOG("pull atom %d now", pullInfo.mPullerMatcher.atom_id());

                    vector<std::shared_ptr<LogEvent>> data;
                    mPullerMgr->Pull(pullInfo.mPullerMatcher.atom_id(), &data);
                    VLOG("pulled %zu atoms", data.size());
                    if (data.size() > 0) {
                        writeToOutputLocked(data, pullInfo.mPullerMatcher);
                    }
                    pullInfo.mPrevPullElapsedRealtimeMs = nowMillis;
                }
            }
        }
        VLOG("Pulling thread %lld sleep....", (long long)token);
        std::this_thread::sleep_for(std::chrono::milliseconds(intervalMillis));
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
    mPulledInfo.clear();
    mPullToken = 0;
    VLOG("done clean up");
}

void ShellSubscriber::onLogEvent(const LogEvent& event) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mOutput <= 0) {
        return;
    }
    for (const auto& matcher : mPushedMatchers) {
        if (matchesSimple(*mUidMap, matcher, event)) {
            VLOG("%s", event.ToString().c_str());
            uint64_t atomToken = mProto.start(util::FIELD_TYPE_MESSAGE |
                                              util::FIELD_COUNT_REPEATED | FIELD_ID_ATOM);
            event.ToProto(mProto);
            mProto.end(atomToken);
            // First write the payload size.
            size_t bufferSize = mProto.size();
            write(mOutput, &bufferSize, sizeof(bufferSize));

            // Then write the payload.
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
