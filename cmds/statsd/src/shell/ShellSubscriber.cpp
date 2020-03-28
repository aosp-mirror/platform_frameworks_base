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
    mSubscriptionShouldEnd.notify_one();

    shared_ptr<SubscriptionInfo> mySubscriptionInfo = make_shared<SubscriptionInfo>(in, out);
    if (!readConfig(mySubscriptionInfo)) {
        return;
    }

    // critical-section
    std::unique_lock<std::mutex> lock(mMutex);
    if (myToken != mToken) {
        // Some other subscription has already come in. Stop.
        return;
    }
    mSubscriptionInfo = mySubscriptionInfo;

    if (mySubscriptionInfo->mPulledInfo.size() > 0 && mySubscriptionInfo->mPullIntervalMin > 0) {
        // This thread terminates after it detects that mToken has changed.
        std::thread puller([this, myToken] { startPull(myToken); });
        puller.detach();
    }

    // Block until subscription has ended.
    if (timeoutSec > 0) {
        mSubscriptionShouldEnd.wait_for(
                lock, timeoutSec * 1s, [this, myToken, &mySubscriptionInfo] {
                    return mToken != myToken || !mySubscriptionInfo->mClientAlive;
                });
    } else {
        mSubscriptionShouldEnd.wait(lock, [this, myToken, &mySubscriptionInfo] {
            return mToken != myToken || !mySubscriptionInfo->mClientAlive;
        });
    }

    if (mSubscriptionInfo == mySubscriptionInfo) {
        mSubscriptionInfo = nullptr;
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

    int minInterval = -1;
    for (const auto& pulled : config.pulled()) {
        // All intervals need to be multiples of the min interval.
        if (minInterval < 0 || pulled.freq_millis() < minInterval) {
            minInterval = pulled.freq_millis();
        }

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
    subscriptionInfo->mPullIntervalMin = minInterval;

    return true;
}

void ShellSubscriber::startPull(int64_t myToken) {
    while (true) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mSubscriptionInfo || mToken != myToken) {
            VLOG("Pulling thread %lld done!", (long long)myToken);
            return;
        }

        int64_t nowMillis = getElapsedRealtimeMillis();
        for (auto& pullInfo : mSubscriptionInfo->mPulledInfo) {
            if (pullInfo.mPrevPullElapsedRealtimeMs + pullInfo.mInterval < nowMillis) {
                vector<std::shared_ptr<LogEvent>> data;
                vector<int32_t> uids;
                uids.insert(uids.end(), pullInfo.mPullUids.begin(), pullInfo.mPullUids.end());
                // This is slow. Consider storing the uids per app and listening to uidmap updates.
                for (const string& pkg : pullInfo.mPullPackages) {
                    set<int32_t> uidsForPkg = mUidMap->getAppUid(pkg);
                    uids.insert(uids.end(), uidsForPkg.begin(), uidsForPkg.end());
                }
                uids.push_back(DEFAULT_PULL_UID);
                mPullerMgr->Pull(pullInfo.mPullerMatcher.atom_id(), uids, &data);
                VLOG("pulled %zu atoms with id %d", data.size(), pullInfo.mPullerMatcher.atom_id());

                // TODO(b/150969574): Don't write to a pipe while holding a lock.
                if (!writePulledAtomsLocked(data, pullInfo.mPullerMatcher)) {
                    mSubscriptionInfo->mClientAlive = false;
                    mSubscriptionShouldEnd.notify_one();
                    return;
                }
                pullInfo.mPrevPullElapsedRealtimeMs = nowMillis;
            }
        }

        VLOG("Pulling thread %lld sleep....", (long long)myToken);
        std::this_thread::sleep_for(std::chrono::milliseconds(mSubscriptionInfo->mPullIntervalMin));
    }
}

// \return boolean indicating if writes were successful (will return false if
// client dies)
bool ShellSubscriber::writePulledAtomsLocked(const vector<std::shared_ptr<LogEvent>>& data,
                                             const SimpleAtomMatcher& matcher) {
    mProto.clear();
    int count = 0;
    for (const auto& event : data) {
        VLOG("%s", event->ToString().c_str());
        if (matchesSimple(*mUidMap, matcher, *event)) {
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
        if (!android::base::WriteFully(mSubscriptionInfo->mOutputFd, &bufferSize,
                                       sizeof(bufferSize))) {
            return false;
        }

        VLOG("%d atoms, proto size: %zu", count, bufferSize);
        // Then write the payload.
        if (!mProto.flush(mSubscriptionInfo->mOutputFd)) {
            return false;
        }
    }

    return true;
}

void ShellSubscriber::onLogEvent(const LogEvent& event) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mSubscriptionInfo) {
        return;
    }

    mProto.clear();
    for (const auto& matcher : mSubscriptionInfo->mPushedMatchers) {
        if (matchesSimple(*mUidMap, matcher, event)) {
            VLOG("%s", event.ToString().c_str());
            uint64_t atomToken = mProto.start(util::FIELD_TYPE_MESSAGE |
                                              util::FIELD_COUNT_REPEATED | FIELD_ID_ATOM);
            event.ToProto(mProto);
            mProto.end(atomToken);

            // First write the payload size.
            size_t bufferSize = mProto.size();
            if (!android::base::WriteFully(mSubscriptionInfo->mOutputFd, &bufferSize,
                                           sizeof(bufferSize))) {
                mSubscriptionInfo->mClientAlive = false;
                mSubscriptionShouldEnd.notify_one();
                return;
            }

            // Then write the payload.
            if (!mProto.flush(mSubscriptionInfo->mOutputFd)) {
                mSubscriptionInfo->mClientAlive = false;
                mSubscriptionShouldEnd.notify_one();
                return;
            }
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
