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
#include "KernelWakelockPuller.h"
#include "StatsPullerManager.h"
#include "StatsService.h"
#include "logd/LogEvent.h"

#include <iostream>

using std::string;
using std::vector;

namespace android {
namespace os {
namespace statsd {

const int kernel_wakelock = 1;
const unordered_map<string, int> StatsPullerManager::kPullCodes({{"KERNEL_WAKELOCK",
                                                                  kernel_wakelock}});

StatsPullerManager::StatsPullerManager()
    : mCurrentPullingInterval(LONG_MAX), mPullStartTimeMs(get_pull_start_time_ms()) {
    mPullers.insert({kernel_wakelock, make_unique<KernelWakelockPuller>()});
    mStatsCompanionService = get_stats_companion_service();
    if (mStatsCompanionService != nullptr) {
        mStatsCompanionService->cancelPullingAlarms();
    } else {
        VLOG("Failed to update pulling interval");
    }
}

static const int log_msg_header_size = 28;

vector<shared_ptr<LogEvent>> StatsPullerManager::Pull(int pullCode, uint64_t timestampSec) {
    if (DEBUG) ALOGD("Initiating pulling %d", pullCode);

    vector<shared_ptr<LogEvent>> ret;
    auto itr = mPullers.find(pullCode);
    if (itr != mPullers.end()) {
        vector<StatsLogEventWrapper> outputs = itr->second->Pull();
        for (const StatsLogEventWrapper& it : outputs) {
            log_msg tmp;
            tmp.entry_v1.sec = timestampSec;
            tmp.entry_v1.nsec = 0;
            tmp.entry_v1.len = it.bytes.size();
            // Manually set the header size to 28 bytes to match the pushed log events.
            tmp.entry.hdr_size = log_msg_header_size;
            // And set the received bytes starting after the 28 bytes reserved for header.
            copy(it.bytes.begin(), it.bytes.end(), tmp.buf + log_msg_header_size);
            shared_ptr<LogEvent> evt = make_shared<LogEvent>(tmp);
            ret.push_back(evt);
        }
        return ret;
    } else {
        ALOGD("Unknown pull code %d", pullCode);
        return ret;  // Return early since we don't know what to pull.
    }
}

sp<IStatsCompanionService> StatsPullerManager::get_stats_companion_service() {
    sp<IStatsCompanionService> statsCompanion = nullptr;
    // Get statscompanion service from service manager
    const sp<IServiceManager> sm(defaultServiceManager());
    if (sm != nullptr) {
        const String16 name("statscompanion");
        statsCompanion = interface_cast<IStatsCompanionService>(sm->checkService(name));
        if (statsCompanion == nullptr) {
            ALOGW("statscompanion service unavailable!");
            return nullptr;
        }
    }
    return statsCompanion;
}

StatsPullerManager& StatsPullerManager::GetInstance() {
    static StatsPullerManager instance;
    return instance;
}

int StatsPullerManager::GetPullCode(string atomName) {
    if (kPullCodes.find(atomName) != kPullCodes.end()) {
        return kPullCodes.find(atomName)->second;
    } else {
        return -1;
    }
}

long StatsPullerManager::get_pull_start_time_ms() {
    // TODO: limit and align pull intervals to 10min boundaries if this turns out to be a problem
    return time(nullptr) * 1000;
}

void StatsPullerManager::RegisterReceiver(int pullCode, sp<PullDataReceiver> receiver, long intervalMs) {
    AutoMutex _l(mReceiversLock);
    vector<ReceiverInfo>& receivers = mReceivers[pullCode];
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
    VLOG("Puller for pullcode %d registered of %d", pullCode, (int)receivers.size());
}

void StatsPullerManager::UnRegisterReceiver(int pullCode, sp<PullDataReceiver> receiver) {
    AutoMutex _l(mReceiversLock);
    if (mReceivers.find(pullCode) == mReceivers.end()) {
        VLOG("Unknown pull code or no receivers: %d", pullCode);
        return;
    }
    auto& receivers = mReceivers.find(pullCode)->second;
    for (auto it = receivers.begin(); it != receivers.end(); it++) {
        if (receiver.get() == it->receiver.get()) {
            receivers.erase(it);
            VLOG("Puller for pullcode %d unregistered of %d", pullCode, (int)receivers.size());
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
        if (pair.second.size() != 0){
            for(auto& receiverInfo : pair.second) {
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
        const vector<shared_ptr<LogEvent>>& data = Pull(pullInfo.first, currentTimeMs/1000);
        for(const auto& receiverInfo : pullInfo.second) {
            receiverInfo->receiver->onDataPulled(data);
            receiverInfo->timeInfo.second = currentTimeMs;
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
