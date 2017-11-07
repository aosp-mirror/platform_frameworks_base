/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define DEBUG true
#include "Log.h"

#include <android/os/IStatsCompanionService.h>
#include <cutils/log.h>
#include <algorithm>
#include <climits>
#include "ResourcePowerManagerPuller.h"
#include "StatsCompanionServicePuller.h"
#include "StatsPullerManager.h"
#include "StatsService.h"
#include "logd/LogEvent.h"
#include "statslog.h"

#include <iostream>

using std::make_shared;
using std::map;
using std::shared_ptr;
using std::string;
using std::vector;

namespace android {
namespace os {
namespace statsd {

StatsPullerManager::StatsPullerManager()
    : mCurrentPullingInterval(LONG_MAX), mPullStartTimeMs(get_pull_start_time_ms()) {
    shared_ptr<StatsPuller> statsCompanionServicePuller =
            make_shared<StatsCompanionServicePuller>();
    shared_ptr<StatsPuller> resourcePowerManagerPuller = make_shared<ResourcePowerManagerPuller>();

    mPullers.insert({android::util::KERNEL_WAKELOCK_PULLED, statsCompanionServicePuller});
    mPullers.insert({android::util::WIFI_BYTES_TRANSFERRED, statsCompanionServicePuller});
    mPullers.insert({android::util::MOBILE_BYTES_TRANSFERRED, statsCompanionServicePuller});
    mPullers.insert({android::util::WIFI_BYTES_TRANSFERRED_BY_FG_BG, statsCompanionServicePuller});
    mPullers.insert(
            {android::util::MOBILE_BYTES_TRANSFERRED_BY_FG_BG, statsCompanionServicePuller});
    mPullers.insert(
            {android::util::POWER_STATE_PLATFORM_SLEEP_STATE_PULLED, resourcePowerManagerPuller});
    mPullers.insert({android::util::POWER_STATE_VOTER_PULLED, resourcePowerManagerPuller});
    mPullers.insert(
            {android::util::POWER_STATE_SUBSYSTEM_SLEEP_STATE_PULLED, resourcePowerManagerPuller});

    mStatsCompanionService = StatsService::getStatsCompanionService();
}

bool StatsPullerManager::Pull(int tagId, vector<shared_ptr<LogEvent>>* data) {
    if (DEBUG) ALOGD("Initiating pulling %d", tagId);

    if (mPullers.find(tagId) != mPullers.end()) {
        return mPullers.find(tagId)->second->Pull(tagId, data);
    } else {
        ALOGD("Unknown tagId %d", tagId);
        return false;  // Return early since we don't know what to pull.
    }
}

StatsPullerManager& StatsPullerManager::GetInstance() {
    static StatsPullerManager instance;
    return instance;
}

bool StatsPullerManager::PullerForMatcherExists(int tagId) {
    return mPullers.find(tagId) != mPullers.end();
}

long StatsPullerManager::get_pull_start_time_ms() {
    // TODO: limit and align pull intervals to 10min boundaries if this turns out to be a problem
    return time(nullptr) * 1000;
}

void StatsPullerManager::RegisterReceiver(int tagId, sp<PullDataReceiver> receiver,
                                          long intervalMs) {
    AutoMutex _l(mReceiversLock);
    vector<ReceiverInfo>& receivers = mReceivers[tagId];
    for (auto it = receivers.begin(); it != receivers.end(); it++) {
        if (it->receiver.get() == receiver.get()) {
            VLOG("Receiver already registered of %d", (int)receivers.size());
            return;
        }
    }
    ReceiverInfo receiverInfo;
    receiverInfo.receiver = receiver;
    receiverInfo.timeInfo.first = intervalMs;
    receivers.push_back(receiverInfo);

    // There is only one alarm for all pulled events. So only set it to the smallest denom.
    if (intervalMs < mCurrentPullingInterval) {
        VLOG("Updating pulling interval %ld", intervalMs);
        mCurrentPullingInterval = intervalMs;
        if (mStatsCompanionService != nullptr) {
            mStatsCompanionService->setPullingAlarms(mPullStartTimeMs, mCurrentPullingInterval);
        } else {
            VLOG("Failed to update pulling interval");
        }
    }
    VLOG("Puller for tagId %d registered of %d", tagId, (int)receivers.size());
}

void StatsPullerManager::UnRegisterReceiver(int tagId, sp<PullDataReceiver> receiver) {
    AutoMutex _l(mReceiversLock);
    if (mReceivers.find(tagId) == mReceivers.end()) {
        VLOG("Unknown pull code or no receivers: %d", tagId);
        return;
    }
    auto& receivers = mReceivers.find(tagId)->second;
    for (auto it = receivers.begin(); it != receivers.end(); it++) {
        if (receiver.get() == it->receiver.get()) {
            receivers.erase(it);
            VLOG("Puller for tagId %d unregistered of %d", tagId, (int)receivers.size());
            return;
        }
    }
}

void StatsPullerManager::OnAlarmFired() {
    AutoMutex _l(mReceiversLock);

    uint64_t currentTimeMs = time(nullptr) * 1000;

    vector<pair<int, vector<ReceiverInfo*>>> needToPull =
            vector<pair<int, vector<ReceiverInfo*>>>();
    for (auto& pair : mReceivers) {
        vector<ReceiverInfo*> receivers = vector<ReceiverInfo*>();
        if (pair.second.size() != 0) {
            for (auto& receiverInfo : pair.second) {
                if (receiverInfo.timeInfo.first + receiverInfo.timeInfo.second > currentTimeMs) {
                    receivers.push_back(&receiverInfo);
                }
            }
            if (receivers.size() > 0) {
                needToPull.push_back(make_pair(pair.first, receivers));
            }
        }
    }

    for (const auto& pullInfo : needToPull) {
        vector<shared_ptr<LogEvent>> data;
        if (Pull(pullInfo.first, &data)) {
            for (const auto& receiverInfo : pullInfo.second) {
                receiverInfo->receiver->onDataPulled(data);
                receiverInfo->timeInfo.second = currentTimeMs;
            }
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
