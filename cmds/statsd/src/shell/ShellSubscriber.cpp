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

#include <android-base/file.h>

#include "matchers/matcher_util.h"
#include "stats_log_util.h"

using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {

const static int FIELD_ID_ATOM = 1;

void ShellSubscriber::startNewSubscription(int in, int out, int timeoutSec) {
    int myToken = claimToken();
    VLOG("ShellSubscriber: new subscription %d has come in", myToken);
    mSubscriptionShouldEnd.notify_one();

    shared_ptr<SubscriptionInfo> mySubscriptionInfo = make_shared<SubscriptionInfo>(in, out);
    if (!readConfig(mySubscriptionInfo)) return;

    {
        std::unique_lock<std::mutex> lock(mMutex);
        mSubscriptionInfo = mySubscriptionInfo;
        spawnHelperThread(myToken);
        waitForSubscriptionToEndLocked(mySubscriptionInfo, myToken, lock, timeoutSec);

        if (mSubscriptionInfo == mySubscriptionInfo) {
            mSubscriptionInfo = nullptr;
        }

    }
}

void ShellSubscriber::spawnHelperThread(int myToken) {
    std::thread t([this, myToken] { pullAndSendHeartbeats(myToken); });
    t.detach();
}

void ShellSubscriber::waitForSubscriptionToEndLocked(shared_ptr<SubscriptionInfo> myInfo,
                                                     int myToken,
                                                     std::unique_lock<std::mutex>& lock,
                                                     int timeoutSec) {
    if (timeoutSec > 0) {
        mSubscriptionShouldEnd.wait_for(lock, timeoutSec * 1s, [this, myToken, &myInfo] {
            return mToken != myToken || !myInfo->mClientAlive;
        });
    } else {
        mSubscriptionShouldEnd.wait(lock, [this, myToken, &myInfo] {
            return mToken != myToken || !myInfo->mClientAlive;
        });
    }
}

// Atomically claim the next token. Token numbers denote subscriber ordering.
int ShellSubscriber::claimToken() {
    std::unique_lock<std::mutex> lock(mMutex);
    int myToken = ++mToken;
    return myToken;
}

// Read and parse single config. There should only one config per input.
bool ShellSubscriber::readConfig(shared_ptr<SubscriptionInfo> subscriptionInfo) {
    // Read the size of the config.
    size_t bufferSize;
    if (!android::base::ReadFully(subscriptionInfo->mInputFd, &bufferSize, sizeof(bufferSize))) {
        return false;
    }

    // Read the config.
    vector<uint8_t> buffer(bufferSize);
    if (!android::base::ReadFully(subscriptionInfo->mInputFd, buffer.data(), bufferSize)) {
        return false;
    }

    // Parse the config.
    ShellSubscription config;
    if (!config.ParseFromArray(buffer.data(), bufferSize)) {
        return false;
    }

    // Update SubscriptionInfo with state from config
    for (const auto& pushed : config.pushed()) {
        subscriptionInfo->mPushedMatchers.push_back(pushed);
    }

    for (const auto& pulled : config.pulled()) {
        vector<string> packages;
        vector<int32_t> uids;
        for (const string& pkg : pulled.packages()) {
            auto it = UidMap::sAidToUidMapping.find(pkg);
            if (it != UidMap::sAidToUidMapping.end()) {
                uids.push_back(it->second);
            } else {
                packages.push_back(pkg);
            }
        }

        subscriptionInfo->mPulledInfo.emplace_back(pulled.matcher(), pulled.freq_millis(), packages,
                                                   uids);
        VLOG("adding matcher for pulled atom %d", pulled.matcher().atom_id());
    }

    return true;
}

void ShellSubscriber::pullAndSendHeartbeats(int myToken) {
    VLOG("ShellSubscriber: helper thread %d starting", myToken);
    while (true) {
        int64_t sleepTimeMs = INT_MAX;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            if (!mSubscriptionInfo || mToken != myToken) {
                VLOG("ShellSubscriber: helper thread %d done!", myToken);
                return;
            }

            int64_t nowMillis = getElapsedRealtimeMillis();
            int64_t nowNanos = getElapsedRealtimeNs();
            for (PullInfo& pullInfo : mSubscriptionInfo->mPulledInfo) {
                if (pullInfo.mPrevPullElapsedRealtimeMs + pullInfo.mInterval >= nowMillis) {
                    continue;
                }

                vector<int32_t> uids;
                getUidsForPullAtom(&uids, pullInfo);

                vector<std::shared_ptr<LogEvent>> data;
                mPullerMgr->Pull(pullInfo.mPullerMatcher.atom_id(), uids, nowNanos, &data);
                VLOG("Pulled %zu atoms with id %d", data.size(), pullInfo.mPullerMatcher.atom_id());
                writePulledAtomsLocked(data, pullInfo.mPullerMatcher);

                pullInfo.mPrevPullElapsedRealtimeMs = nowMillis;
            }

            // Send a heartbeat, consisting of a data size of 0, if perfd hasn't recently received
            // data from statsd. When it receives the data size of 0, perfd will not expect any
            // atoms and recheck whether the subscription should end.
            if (nowMillis - mLastWriteMs > kMsBetweenHeartbeats) {
                attemptWriteToPipeLocked(/*dataSize=*/0);
            }

            // Determine how long to sleep before doing more work.
            for (PullInfo& pullInfo : mSubscriptionInfo->mPulledInfo) {
                int64_t nextPullTime = pullInfo.mPrevPullElapsedRealtimeMs + pullInfo.mInterval;
                int64_t timeBeforePull = nextPullTime - nowMillis; // guaranteed to be non-negative
                if (timeBeforePull < sleepTimeMs) sleepTimeMs = timeBeforePull;
            }
            int64_t timeBeforeHeartbeat = (mLastWriteMs + kMsBetweenHeartbeats) - nowMillis;
            if (timeBeforeHeartbeat < sleepTimeMs) sleepTimeMs = timeBeforeHeartbeat;
        }

        VLOG("ShellSubscriber: helper thread %d sleeping for %lld ms", myToken,
             (long long)sleepTimeMs);
        std::this_thread::sleep_for(std::chrono::milliseconds(sleepTimeMs));
    }
}

void ShellSubscriber::getUidsForPullAtom(vector<int32_t>* uids, const PullInfo& pullInfo) {
    uids->insert(uids->end(), pullInfo.mPullUids.begin(), pullInfo.mPullUids.end());
    // This is slow. Consider storing the uids per app and listening to uidmap updates.
    for (const string& pkg : pullInfo.mPullPackages) {
        set<int32_t> uidsForPkg = mUidMap->getAppUid(pkg);
        uids->insert(uids->end(), uidsForPkg.begin(), uidsForPkg.end());
    }
    uids->push_back(DEFAULT_PULL_UID);
}

void ShellSubscriber::writePulledAtomsLocked(const vector<std::shared_ptr<LogEvent>>& data,
                                             const SimpleAtomMatcher& matcher) {
    mProto.clear();
    int count = 0;
    for (const auto& event : data) {
        if (matchesSimple(*mUidMap, matcher, *event)) {
            count++;
            uint64_t atomToken = mProto.start(util::FIELD_TYPE_MESSAGE |
                                              util::FIELD_COUNT_REPEATED | FIELD_ID_ATOM);
            event->ToProto(mProto);
            mProto.end(atomToken);
        }
    }

    if (count > 0) attemptWriteToPipeLocked(mProto.size());
}

void ShellSubscriber::onLogEvent(const LogEvent& event) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mSubscriptionInfo) return;

    mProto.clear();
    for (const auto& matcher : mSubscriptionInfo->mPushedMatchers) {
        if (matchesSimple(*mUidMap, matcher, event)) {
            uint64_t atomToken = mProto.start(util::FIELD_TYPE_MESSAGE |
                                              util::FIELD_COUNT_REPEATED | FIELD_ID_ATOM);
            event.ToProto(mProto);
            mProto.end(atomToken);
            attemptWriteToPipeLocked(mProto.size());
        }
    }
}

// Tries to write the atom encoded in mProto to the pipe. If the write fails
// because the read end of the pipe has closed, signals to other threads that
// the subscription should end.
void ShellSubscriber::attemptWriteToPipeLocked(size_t dataSize) {
    // First, write the payload size.
    if (!android::base::WriteFully(mSubscriptionInfo->mOutputFd, &dataSize, sizeof(dataSize))) {
        mSubscriptionInfo->mClientAlive = false;
        mSubscriptionShouldEnd.notify_one();
        return;
    }

    // Then, write the payload if this is not just a heartbeat.
    if (dataSize > 0 && !mProto.flush(mSubscriptionInfo->mOutputFd)) {
        mSubscriptionInfo->mClientAlive = false;
        mSubscriptionShouldEnd.notify_one();
        return;
    }

    mLastWriteMs = getElapsedRealtimeMillis();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
