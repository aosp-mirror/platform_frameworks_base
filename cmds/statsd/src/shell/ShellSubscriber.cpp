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
#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "ShellSubscriber.h"

#include "matchers/matcher_util.h"
#include "stats_log_util.h"

using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {

const static int FIELD_ID_ATOM = 1;

void ShellSubscriber::startNewSubscription(int in, int out, int timeoutSec) {
    VLOG("start new shell subscription");
    int64_t subscriberId = getElapsedRealtimeNs();

    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mSubscriberId> 0) {
            VLOG("Only one shell subscriber is allowed.");
            return;
        }
        mSubscriberId = subscriberId;
        mInput = in;
        mOutput = out;
    }

    bool success = readConfig();
    if (!success) {
        std::lock_guard<std::mutex> lock(mMutex);
        cleanUpLocked();
    }

    VLOG("Wait for client to exit or timeout (%d sec)", timeoutSec);
    std::unique_lock<std::mutex> lk(mMutex);

    // Note that the following is blocking, and it's intended as we cannot return until the shell
    // cmd exits or we time out.
    if (timeoutSec > 0) {
        mShellDied.wait_for(lk, timeoutSec * 1s,
                            [this, subscriberId] { return mSubscriberId != subscriberId; });
    } else {
        mShellDied.wait(lk, [this, subscriberId] { return mSubscriberId != subscriberId; });
    }
}


// Read configs until EOF is reached. There may be multiple configs in the input
// -- each new config should replace the previous one.
//
// Returns a boolean indicating whether the input was read successfully.
bool ShellSubscriber::readConfig() {
    if (mInput < 0) {
        return false;
    }

    while (true) {
        // Read the size of the config.
        size_t bufferSize = 0;
        ssize_t bytesRead = read(mInput, &bufferSize, sizeof(bufferSize));
        if (bytesRead == 0) {
            VLOG("We have reached the end of the input.");
            return true;
        } else if (bytesRead < 0 || (size_t)bytesRead != sizeof(bufferSize)) {
            ALOGE("Error reading config size");
            return false;
        }

        // Read and parse the config.
        vector<uint8_t> buffer(bufferSize);
        bytesRead = read(mInput, buffer.data(), bufferSize);
        if (bytesRead > 0 && (size_t)bytesRead == bufferSize) {
            ShellSubscription config;
            if (config.ParseFromArray(buffer.data(), bufferSize)) {
                updateConfig(config);
            } else {
                ALOGE("Error parsing the config");
                return false;
            }
        } else {
            VLOG("Error reading the config, expected bytes: %zu, actual bytes: %zu", bufferSize, 
                 bytesRead);
            return false;
        }
    }
}

void ShellSubscriber::updateConfig(const ShellSubscription& config) {
    mPushedMatchers.clear();
    mPulledInfo.clear();

    for (const auto& pushed : config.pushed()) {
        mPushedMatchers.push_back(pushed);
        VLOG("adding matcher for pushed atom %d", pushed.atom_id());
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
        // This thread is guaranteed to terminate after it detects the token is
        // different.
        std::thread puller([token, minInterval, this] { startPull(token, minInterval); });
        puller.detach();
    }
}

void ShellSubscriber::startPull(int64_t token, int64_t intervalMillis) {
    while (true) {
        int64_t nowMillis = getElapsedRealtimeMillis();
        {
            std::lock_guard<std::mutex> lock(mMutex);
            // If the token has changed, the config has changed, so this
            // puller can now stop.
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

// Must be called with the lock acquired, so that mProto isn't being written to
// at the same time by multiple threads.
void ShellSubscriber::writeToOutputLocked(const vector<std::shared_ptr<LogEvent>>& data,
                                          const SimpleAtomMatcher& matcher) {
    if (mOutput < 0) {
        return;
    }
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
}

void ShellSubscriber::onLogEvent(const LogEvent& event) {
    // Acquire a lock to prevent corruption from multiple threads writing to
    // mProto.
    std::lock_guard<std::mutex> lock(mMutex);
    if (mOutput < 0) {
        return;
    }

    mProto.clear();
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
        }
    }
}

void ShellSubscriber::cleanUpLocked() {
    // The file descriptors will be closed by binder.
    mInput = -1;
    mOutput = -1;
    mSubscriberId = 0;
    mPushedMatchers.clear();
    mPulledInfo.clear();
    // Setting mPullToken == 0 tells pull thread that its work is done.
    mPullToken = 0;
    VLOG("done clean up");
}

}  // namespace statsd
}  // namespace os
}  // namespace android
