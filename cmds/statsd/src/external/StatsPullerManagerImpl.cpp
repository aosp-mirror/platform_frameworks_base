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

#define DEBUG false
#include "Log.h"

#include <android/os/IStatsCompanionService.h>
#include <cutils/log.h>
#include <math.h>
#include <algorithm>
#include <climits>
#include "../StatsService.h"
#include "../logd/LogEvent.h"
#include "../stats_log_util.h"
#include "../statscompanion_util.h"
#include "ResourceHealthManagerPuller.h"
#include "ResourceThermalManagerPuller.h"
#include "StatsCompanionServicePuller.h"
#include "StatsPullerManagerImpl.h"
#include "SubsystemSleepStatePuller.h"
#include "statslog.h"

#include <iostream>

using std::make_shared;
using std::map;
using std::shared_ptr;
using std::string;
using std::vector;
using std::list;

namespace android {
namespace os {
namespace statsd {

const std::map<int, PullAtomInfo> StatsPullerManagerImpl::kAllPullAtomInfo = {
        // wifi_bytes_transfer
        {android::util::WIFI_BYTES_TRANSFER,
         {{2, 3, 4, 5},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::WIFI_BYTES_TRANSFER)}},
        // wifi_bytes_transfer_by_fg_bg
        {android::util::WIFI_BYTES_TRANSFER_BY_FG_BG,
         {{3, 4, 5, 6},
          {2},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::WIFI_BYTES_TRANSFER_BY_FG_BG)}},
        // mobile_bytes_transfer
        {android::util::MOBILE_BYTES_TRANSFER,
         {{2, 3, 4, 5},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::MOBILE_BYTES_TRANSFER)}},
        // mobile_bytes_transfer_by_fg_bg
        {android::util::MOBILE_BYTES_TRANSFER_BY_FG_BG,
         {{3, 4, 5, 6},
          {2},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::MOBILE_BYTES_TRANSFER_BY_FG_BG)}},
        // bluetooth_bytes_transfer
        {android::util::BLUETOOTH_BYTES_TRANSFER,
         {{2, 3},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::BLUETOOTH_BYTES_TRANSFER)}},
        // kernel_wakelock
        {android::util::KERNEL_WAKELOCK,
         {{}, {}, 1 * NS_PER_SEC, new StatsCompanionServicePuller(android::util::KERNEL_WAKELOCK)}},
        // subsystem_sleep_state
        {android::util::SUBSYSTEM_SLEEP_STATE,
         {{}, {}, 1 * NS_PER_SEC, new SubsystemSleepStatePuller()}},
        // cpu_time_per_freq
        {android::util::CPU_TIME_PER_FREQ,
         {{3},
          {2},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::CPU_TIME_PER_FREQ)}},
        // cpu_time_per_uid
        {android::util::CPU_TIME_PER_UID,
         {{2, 3},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::CPU_TIME_PER_UID)}},
        // cpu_time_per_uid_freq
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        {android::util::CPU_TIME_PER_UID_FREQ,
         {{4},
          {2, 3},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::CPU_TIME_PER_UID_FREQ)}},
        // cpu_active_time
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        {android::util::CPU_ACTIVE_TIME,
         {{2},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::CPU_ACTIVE_TIME)}},
        // cpu_cluster_time
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        {android::util::CPU_CLUSTER_TIME,
         {{3},
          {2},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::CPU_CLUSTER_TIME)}},
        // wifi_activity_energy_info
        {android::util::WIFI_ACTIVITY_INFO,
         {{},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::WIFI_ACTIVITY_INFO)}},
        // modem_activity_info
        {android::util::MODEM_ACTIVITY_INFO,
         {{},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::MODEM_ACTIVITY_INFO)}},
        // bluetooth_activity_info
        {android::util::BLUETOOTH_ACTIVITY_INFO,
         {{},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::BLUETOOTH_ACTIVITY_INFO)}},
        // system_elapsed_realtime
        {android::util::SYSTEM_ELAPSED_REALTIME,
         {{},
          {},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::SYSTEM_ELAPSED_REALTIME)}},
        // system_uptime
        {android::util::SYSTEM_UPTIME,
         {{}, {}, 1 * NS_PER_SEC, new StatsCompanionServicePuller(android::util::SYSTEM_UPTIME)}},
        // disk_space
        {android::util::DISK_SPACE,
         {{}, {}, 1 * NS_PER_SEC, new StatsCompanionServicePuller(android::util::DISK_SPACE)}},
        // remaining_battery_capacity
        {android::util::REMAINING_BATTERY_CAPACITY,
         {{},
          {},
          1 * NS_PER_SEC,
          new ResourceHealthManagerPuller(android::util::REMAINING_BATTERY_CAPACITY)}},
        // full_battery_capacity
        {android::util::FULL_BATTERY_CAPACITY,
         {{},
          {},
          1 * NS_PER_SEC,
          new ResourceHealthManagerPuller(android::util::FULL_BATTERY_CAPACITY)}},
        // process_memory_state
        {android::util::PROCESS_MEMORY_STATE,
         {{4, 5, 6, 7, 8},
          {2, 3},
          1 * NS_PER_SEC,
          new StatsCompanionServicePuller(android::util::PROCESS_MEMORY_STATE)}},
        // temperature
        {android::util::TEMPERATURE, {{}, {}, 1, new ResourceThermalManagerPuller()}}};

StatsPullerManagerImpl::StatsPullerManagerImpl() : mNextPullTimeNs(LONG_MAX) {
}

bool StatsPullerManagerImpl::Pull(const int tagId, const int64_t timeNs,
                                  vector<shared_ptr<LogEvent>>* data) {
    VLOG("Initiating pulling %d", tagId);

    if (kAllPullAtomInfo.find(tagId) != kAllPullAtomInfo.end()) {
        bool ret = kAllPullAtomInfo.find(tagId)->second.puller->Pull(timeNs, data);
        VLOG("pulled %d items", (int)data->size());
        return ret;
    } else {
        VLOG("Unknown tagId %d", tagId);
        return false;  // Return early since we don't know what to pull.
    }
}

StatsPullerManagerImpl& StatsPullerManagerImpl::GetInstance() {
    static StatsPullerManagerImpl instance;
    return instance;
}

bool StatsPullerManagerImpl::PullerForMatcherExists(int tagId) const {
    return kAllPullAtomInfo.find(tagId) != kAllPullAtomInfo.end();
}

void StatsPullerManagerImpl::updateAlarmLocked() {
    if (mNextPullTimeNs == LONG_MAX) {
        VLOG("No need to set alarms. Skipping");
        return;
    }

    sp<IStatsCompanionService> statsCompanionServiceCopy = mStatsCompanionService;
    if (statsCompanionServiceCopy != nullptr) {
        statsCompanionServiceCopy->setPullingAlarm(mNextPullTimeNs / 1000000);
    } else {
        VLOG("StatsCompanionService not available. Alarm not set.");
    }
    return;
}

void StatsPullerManagerImpl::SetStatsCompanionService(
        sp<IStatsCompanionService> statsCompanionService) {
    AutoMutex _l(mLock);
    sp<IStatsCompanionService> tmpForLock = mStatsCompanionService;
    mStatsCompanionService = statsCompanionService;
    for (const auto& pulledAtom : kAllPullAtomInfo) {
        pulledAtom.second.puller->SetStatsCompanionService(statsCompanionService);
    }
    if (mStatsCompanionService != nullptr) {
        updateAlarmLocked();
    }
}

void StatsPullerManagerImpl::RegisterReceiver(int tagId, wp<PullDataReceiver> receiver,
                                              int64_t nextPullTimeNs, int64_t intervalNs) {
    AutoMutex _l(mLock);
    auto& receivers = mReceivers[tagId];
    for (auto it = receivers.begin(); it != receivers.end(); it++) {
        if (it->receiver == receiver) {
            VLOG("Receiver already registered of %d", (int)receivers.size());
            return;
        }
    }
    ReceiverInfo receiverInfo;
    receiverInfo.receiver = receiver;

    // Round it to the nearest minutes. This is the limit of alarm manager.
    // In practice, we should always have larger buckets.
    int64_t roundedIntervalNs = intervalNs / NS_PER_SEC / 60 * NS_PER_SEC * 60;
    // Scheduled pulling should be at least 1 min apart.
    // This can be lower in cts tests, in which case we round it to 1 min.
    if (roundedIntervalNs < 60 * (int64_t)NS_PER_SEC) {
        roundedIntervalNs = 60 * (int64_t)NS_PER_SEC;
    }

    receiverInfo.intervalNs = roundedIntervalNs;
    receiverInfo.nextPullTimeNs = nextPullTimeNs;
    receivers.push_back(receiverInfo);

    // There is only one alarm for all pulled events. So only set it to the smallest denom.
    if (nextPullTimeNs < mNextPullTimeNs) {
        VLOG("Updating next pull time %lld", (long long)mNextPullTimeNs);
        mNextPullTimeNs = nextPullTimeNs;
        updateAlarmLocked();
    }
    VLOG("Puller for tagId %d registered of %d", tagId, (int)receivers.size());
}

void StatsPullerManagerImpl::UnRegisterReceiver(int tagId, wp<PullDataReceiver> receiver) {
    AutoMutex _l(mLock);
    if (mReceivers.find(tagId) == mReceivers.end()) {
        VLOG("Unknown pull code or no receivers: %d", tagId);
        return;
    }
    auto& receivers = mReceivers.find(tagId)->second;
    for (auto it = receivers.begin(); it != receivers.end(); it++) {
        if (receiver == it->receiver) {
            receivers.erase(it);
            VLOG("Puller for tagId %d unregistered of %d", tagId, (int)receivers.size());
            return;
        }
    }
}

void StatsPullerManagerImpl::OnAlarmFired(const int64_t currentTimeNs) {
    AutoMutex _l(mLock);

    int64_t minNextPullTimeNs = LONG_MAX;

    vector<pair<int, vector<ReceiverInfo*>>> needToPull =
            vector<pair<int, vector<ReceiverInfo*>>>();
    for (auto& pair : mReceivers) {
        vector<ReceiverInfo*> receivers = vector<ReceiverInfo*>();
        if (pair.second.size() != 0) {
            for (ReceiverInfo& receiverInfo : pair.second) {
                if (receiverInfo.nextPullTimeNs <= currentTimeNs) {
                    receivers.push_back(&receiverInfo);
                } else {
                    if (receiverInfo.nextPullTimeNs < minNextPullTimeNs) {
                        minNextPullTimeNs = receiverInfo.nextPullTimeNs;
                    }
                }
            }
            if (receivers.size() > 0) {
                needToPull.push_back(make_pair(pair.first, receivers));
            }
        }
    }

    for (const auto& pullInfo : needToPull) {
        vector<shared_ptr<LogEvent>> data;
        if (Pull(pullInfo.first, currentTimeNs, &data)) {
            for (const auto& receiverInfo : pullInfo.second) {
                sp<PullDataReceiver> receiverPtr = receiverInfo->receiver.promote();
                if (receiverPtr != nullptr) {
                    receiverPtr->onDataPulled(data);
                    // we may have just come out of a coma, compute next pull time
                    receiverInfo->nextPullTimeNs =
                            (currentTimeNs - receiverInfo->nextPullTimeNs) /
                                receiverInfo->intervalNs * receiverInfo->intervalNs +
                            receiverInfo->intervalNs + receiverInfo->nextPullTimeNs;
                    if (receiverInfo->nextPullTimeNs < minNextPullTimeNs) {
                        minNextPullTimeNs = receiverInfo->nextPullTimeNs;
                    }
                } else {
                    VLOG("receiver already gone.");
                }
            }
        }
    }

    mNextPullTimeNs = minNextPullTimeNs;
    updateAlarmLocked();
}

int StatsPullerManagerImpl::ForceClearPullerCache() {
    int totalCleared = 0;
    for (const auto& pulledAtom : kAllPullAtomInfo) {
        totalCleared += pulledAtom.second.puller->ForceClearCache();
    }
    return totalCleared;
}

int StatsPullerManagerImpl::ClearPullerCacheIfNecessary(int64_t timestampNs) {
    int totalCleared = 0;
    for (const auto& pulledAtom : kAllPullAtomInfo) {
        totalCleared += pulledAtom.second.puller->ClearCacheIfNecessary(timestampNs);
    }
    return totalCleared;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
