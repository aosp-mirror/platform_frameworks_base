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
#include <android/os/IStatsPullerCallback.h>
#include <cutils/log.h>
#include <math.h>
#include <stdint.h>
#include <algorithm>
#include "../StatsService.h"
#include "../logd/LogEvent.h"
#include "../stats_log_util.h"
#include "../statscompanion_util.h"
#include "PowerStatsPuller.h"
#include "ResourceHealthManagerPuller.h"
#include "StatsCallbackPuller.h"
#include "StatsCompanionServicePuller.h"
#include "StatsPullerManager.h"
#include "SubsystemSleepStatePuller.h"
#include "TrainInfoPuller.h"
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

// Values smaller than this may require to update the alarm.
const int64_t NO_ALARM_UPDATE = INT64_MAX;

std::map<int, PullAtomInfo> StatsPullerManager::kAllPullAtomInfo = {
        // wifi_bytes_transfer
        {android::util::WIFI_BYTES_TRANSFER,
         {.additiveFields = {2, 3, 4, 5},
          .puller = new StatsCompanionServicePuller(android::util::WIFI_BYTES_TRANSFER)}},
        // wifi_bytes_transfer_by_fg_bg
        {android::util::WIFI_BYTES_TRANSFER_BY_FG_BG,
         {.additiveFields = {3, 4, 5, 6},
          .puller = new StatsCompanionServicePuller(android::util::WIFI_BYTES_TRANSFER_BY_FG_BG)}},
        // mobile_bytes_transfer
        {android::util::MOBILE_BYTES_TRANSFER,
         {.additiveFields = {2, 3, 4, 5},
          .puller = new StatsCompanionServicePuller(android::util::MOBILE_BYTES_TRANSFER)}},
        // mobile_bytes_transfer_by_fg_bg
        {android::util::MOBILE_BYTES_TRANSFER_BY_FG_BG,
         {.additiveFields = {3, 4, 5, 6},
          .puller =
                  new StatsCompanionServicePuller(android::util::MOBILE_BYTES_TRANSFER_BY_FG_BG)}},
        // bluetooth_bytes_transfer
        {android::util::BLUETOOTH_BYTES_TRANSFER,
         {.additiveFields = {2, 3},
          .puller = new StatsCompanionServicePuller(android::util::BLUETOOTH_BYTES_TRANSFER)}},
        // kernel_wakelock
        {android::util::KERNEL_WAKELOCK,
         {.puller = new StatsCompanionServicePuller(android::util::KERNEL_WAKELOCK)}},
        // subsystem_sleep_state
        {android::util::SUBSYSTEM_SLEEP_STATE, {.puller = new SubsystemSleepStatePuller()}},
        // on_device_power_measurement
        {android::util::ON_DEVICE_POWER_MEASUREMENT, {.puller = new PowerStatsPuller()}},
        // cpu_time_per_freq
        {android::util::CPU_TIME_PER_FREQ,
         {.additiveFields = {3},
          .puller = new StatsCompanionServicePuller(android::util::CPU_TIME_PER_FREQ)}},
        // cpu_time_per_uid
        {android::util::CPU_TIME_PER_UID,
         {.additiveFields = {2, 3},
          .puller = new StatsCompanionServicePuller(android::util::CPU_TIME_PER_UID)}},
        // cpu_time_per_uid_freq
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        {android::util::CPU_TIME_PER_UID_FREQ,
         {.additiveFields = {4},
          .puller = new StatsCompanionServicePuller(android::util::CPU_TIME_PER_UID_FREQ)}},
        // cpu_active_time
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        {android::util::CPU_ACTIVE_TIME,
         {.additiveFields = {2},
          .puller = new StatsCompanionServicePuller(android::util::CPU_ACTIVE_TIME)}},
        // cpu_cluster_time
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        {android::util::CPU_CLUSTER_TIME,
         {.additiveFields = {3},
          .puller = new StatsCompanionServicePuller(android::util::CPU_CLUSTER_TIME)}},
        // wifi_activity_energy_info
        {android::util::WIFI_ACTIVITY_INFO,
         {.puller = new StatsCompanionServicePuller(android::util::WIFI_ACTIVITY_INFO)}},
        // modem_activity_info
        {android::util::MODEM_ACTIVITY_INFO,
         {.puller = new StatsCompanionServicePuller(android::util::MODEM_ACTIVITY_INFO)}},
        // bluetooth_activity_info
        {android::util::BLUETOOTH_ACTIVITY_INFO,
         {.puller = new StatsCompanionServicePuller(android::util::BLUETOOTH_ACTIVITY_INFO)}},
        // system_elapsed_realtime
        {android::util::SYSTEM_ELAPSED_REALTIME,
         {.pullTimeoutNs = NS_PER_SEC / 2,
          .coolDownNs = NS_PER_SEC,
          .puller = new StatsCompanionServicePuller(android::util::SYSTEM_ELAPSED_REALTIME)}},
        // system_uptime
        {android::util::SYSTEM_UPTIME,
         {.puller = new StatsCompanionServicePuller(android::util::SYSTEM_UPTIME)}},
        // remaining_battery_capacity
        {android::util::REMAINING_BATTERY_CAPACITY,
         {.puller = new ResourceHealthManagerPuller(android::util::REMAINING_BATTERY_CAPACITY)}},
        // full_battery_capacity
        {android::util::FULL_BATTERY_CAPACITY,
         {.puller = new ResourceHealthManagerPuller(android::util::FULL_BATTERY_CAPACITY)}},
        // battery_voltage
        {android::util::BATTERY_VOLTAGE,
         {.puller = new ResourceHealthManagerPuller(android::util::BATTERY_VOLTAGE)}},
        // battery_level
        {android::util::BATTERY_LEVEL,
         {.puller = new ResourceHealthManagerPuller(android::util::BATTERY_LEVEL)}},
        // battery_cycle_count
        {android::util::BATTERY_CYCLE_COUNT,
         {.puller = new ResourceHealthManagerPuller(android::util::BATTERY_CYCLE_COUNT)}},
        // process_memory_state
        {android::util::PROCESS_MEMORY_STATE,
         {.additiveFields = {4, 5, 6, 7, 8, 9},
          .puller = new StatsCompanionServicePuller(android::util::PROCESS_MEMORY_STATE)}},
        // native_process_memory_state
        {android::util::NATIVE_PROCESS_MEMORY_STATE,
         {.additiveFields = {3, 4, 5, 6},
          .puller = new StatsCompanionServicePuller(android::util::NATIVE_PROCESS_MEMORY_STATE)}},
        {android::util::PROCESS_MEMORY_HIGH_WATER_MARK,
         {.additiveFields = {3},
          .puller =
                  new StatsCompanionServicePuller(android::util::PROCESS_MEMORY_HIGH_WATER_MARK)}},
        // temperature
        {android::util::TEMPERATURE,
         {.puller = new StatsCompanionServicePuller(android::util::TEMPERATURE)}},
        // binder_calls
        {android::util::BINDER_CALLS,
         {.additiveFields = {4, 5, 6, 8, 12},
          .puller = new StatsCompanionServicePuller(android::util::BINDER_CALLS)}},
        // binder_calls_exceptions
        {android::util::BINDER_CALLS_EXCEPTIONS,
         {.puller = new StatsCompanionServicePuller(android::util::BINDER_CALLS_EXCEPTIONS)}},
        // looper_stats
        {android::util::LOOPER_STATS,
         {.additiveFields = {5, 6, 7, 8, 9},
          .puller = new StatsCompanionServicePuller(android::util::LOOPER_STATS)}},
        // Disk Stats
        {android::util::DISK_STATS,
         {.puller = new StatsCompanionServicePuller(android::util::DISK_STATS)}},
        // Directory usage
        {android::util::DIRECTORY_USAGE,
         {.puller = new StatsCompanionServicePuller(android::util::DIRECTORY_USAGE)}},
        // Size of app's code, data, and cache
        {android::util::APP_SIZE,
         {.puller = new StatsCompanionServicePuller(android::util::APP_SIZE)}},
        // Size of specific categories of files. Eg. Music.
        {android::util::CATEGORY_SIZE,
         {.puller = new StatsCompanionServicePuller(android::util::CATEGORY_SIZE)}},
        // Number of fingerprints enrolled for each user.
        {android::util::NUM_FINGERPRINTS_ENROLLED,
         {.puller = new StatsCompanionServicePuller(android::util::NUM_FINGERPRINTS_ENROLLED)}},
        // Number of faces enrolled for each user.
        {android::util::NUM_FACES_ENROLLED,
         {.puller = new StatsCompanionServicePuller(android::util::NUM_FACES_ENROLLED)}},
        // ProcStats.
        {android::util::PROC_STATS,
         {.puller = new StatsCompanionServicePuller(android::util::PROC_STATS)}},
        // ProcStatsPkgProc.
        {android::util::PROC_STATS_PKG_PROC,
         {.puller = new StatsCompanionServicePuller(android::util::PROC_STATS_PKG_PROC)}},
        // Disk I/O stats per uid.
        {android::util::DISK_IO,
         {.additiveFields = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
          .coolDownNs = 3 * NS_PER_SEC,
          .puller = new StatsCompanionServicePuller(android::util::DISK_IO)}},
        // PowerProfile constants for power model calculations.
        {android::util::POWER_PROFILE,
         {.puller = new StatsCompanionServicePuller(android::util::POWER_PROFILE)}},
        // Process cpu stats. Min cool-down is 5 sec, inline with what AcitivityManagerService uses.
        {android::util::PROCESS_CPU_TIME,
         {.coolDownNs = 5 * NS_PER_SEC /* min cool-down in seconds*/,
          .puller = new StatsCompanionServicePuller(android::util::PROCESS_CPU_TIME)}},
        {android::util::CPU_TIME_PER_THREAD_FREQ,
         {.additiveFields = {7, 9, 11, 13, 15, 17, 19, 21},
          .puller = new StatsCompanionServicePuller(android::util::CPU_TIME_PER_THREAD_FREQ)}},
        // DeviceCalculatedPowerUse.
        {android::util::DEVICE_CALCULATED_POWER_USE,
         {.puller = new StatsCompanionServicePuller(android::util::DEVICE_CALCULATED_POWER_USE)}},
        // DeviceCalculatedPowerBlameUid.
        {android::util::DEVICE_CALCULATED_POWER_BLAME_UID,
         {.puller = new StatsCompanionServicePuller(
                  android::util::DEVICE_CALCULATED_POWER_BLAME_UID)}},
        // DeviceCalculatedPowerBlameOther.
        {android::util::DEVICE_CALCULATED_POWER_BLAME_OTHER,
         {.puller = new StatsCompanionServicePuller(
                  android::util::DEVICE_CALCULATED_POWER_BLAME_OTHER)}},
        // DebugElapsedClock.
        {android::util::DEBUG_ELAPSED_CLOCK,
         {.additiveFields = {1, 2, 3, 4},
          .puller = new StatsCompanionServicePuller(android::util::DEBUG_ELAPSED_CLOCK)}},
        // DebugFailingElapsedClock.
        {android::util::DEBUG_FAILING_ELAPSED_CLOCK,
         {.additiveFields = {1, 2, 3, 4},
          .puller = new StatsCompanionServicePuller(android::util::DEBUG_FAILING_ELAPSED_CLOCK)}},
        // BuildInformation.
        {android::util::BUILD_INFORMATION,
         {.puller = new StatsCompanionServicePuller(android::util::BUILD_INFORMATION)}},
        // RoleHolder.
        {android::util::ROLE_HOLDER,
         {.puller = new StatsCompanionServicePuller(android::util::ROLE_HOLDER)}},
        // PermissionState.
        {android::util::DANGEROUS_PERMISSION_STATE,
         {.puller = new StatsCompanionServicePuller(android::util::DANGEROUS_PERMISSION_STATE)}},
        // TrainInfo.
        {android::util::TRAIN_INFO, {.puller = new TrainInfoPuller()}},
        // TimeZoneDataInfo.
        {android::util::TIME_ZONE_DATA_INFO,
         {.puller = new StatsCompanionServicePuller(android::util::TIME_ZONE_DATA_INFO)}},
};

StatsPullerManager::StatsPullerManager() : mNextPullTimeNs(NO_ALARM_UPDATE) {
}

bool StatsPullerManager::Pull(int tagId, vector<shared_ptr<LogEvent>>* data) {
    VLOG("Initiating pulling %d", tagId);

    if (kAllPullAtomInfo.find(tagId) != kAllPullAtomInfo.end()) {
        bool ret = kAllPullAtomInfo.find(tagId)->second.puller->Pull(data);
        VLOG("pulled %d items", (int)data->size());
        if (!ret) {
            StatsdStats::getInstance().notePullFailed(tagId);
        }
        return ret;
    } else {
        VLOG("Unknown tagId %d", tagId);
        return false;  // Return early since we don't know what to pull.
    }
}

bool StatsPullerManager::PullerForMatcherExists(int tagId) const {
    return kAllPullAtomInfo.find(tagId) != kAllPullAtomInfo.end();
}

void StatsPullerManager::updateAlarmLocked() {
    if (mNextPullTimeNs == NO_ALARM_UPDATE) {
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

void StatsPullerManager::SetStatsCompanionService(
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

void StatsPullerManager::RegisterReceiver(int tagId, wp<PullDataReceiver> receiver,
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

void StatsPullerManager::UnRegisterReceiver(int tagId, wp<PullDataReceiver> receiver) {
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

void StatsPullerManager::OnAlarmFired(int64_t elapsedTimeNs) {
    AutoMutex _l(mLock);
    int64_t wallClockNs = getWallClockNs();

    int64_t minNextPullTimeNs = NO_ALARM_UPDATE;

    vector<pair<int, vector<ReceiverInfo*>>> needToPull =
            vector<pair<int, vector<ReceiverInfo*>>>();
    for (auto& pair : mReceivers) {
        vector<ReceiverInfo*> receivers = vector<ReceiverInfo*>();
        if (pair.second.size() != 0) {
            for (ReceiverInfo& receiverInfo : pair.second) {
                if (receiverInfo.nextPullTimeNs <= elapsedTimeNs) {
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
        bool pullSuccess = Pull(pullInfo.first, &data);
        if (pullSuccess) {
            StatsdStats::getInstance().notePullDelay(
                    pullInfo.first, getElapsedRealtimeNs() - elapsedTimeNs);
        } else {
            VLOG("pull failed at %lld, will try again later", (long long)elapsedTimeNs);
        }

        // Convention is to mark pull atom timestamp at request time.
        // If we pull at t0, puller starts at t1, finishes at t2, and send back
        // at t3, we mark t0 as its timestamp, which should correspond to its
        // triggering event, such as condition change at t0.
        // Here the triggering event is alarm fired from AlarmManager.
        // In ValueMetricProducer and GaugeMetricProducer we do same thing
        // when pull on condition change, etc.
        for (auto& event : data) {
            event->setElapsedTimestampNs(elapsedTimeNs);
            event->setLogdWallClockTimestampNs(wallClockNs);
        }

        for (const auto& receiverInfo : pullInfo.second) {
            sp<PullDataReceiver> receiverPtr = receiverInfo->receiver.promote();
            if (receiverPtr != nullptr) {
                receiverPtr->onDataPulled(data, pullSuccess, elapsedTimeNs);
                // We may have just come out of a coma, compute next pull time.
                int numBucketsAhead =
                        (elapsedTimeNs - receiverInfo->nextPullTimeNs) / receiverInfo->intervalNs;
                receiverInfo->nextPullTimeNs += (numBucketsAhead + 1) * receiverInfo->intervalNs;
                if (receiverInfo->nextPullTimeNs < minNextPullTimeNs) {
                    minNextPullTimeNs = receiverInfo->nextPullTimeNs;
                }
            } else {
                VLOG("receiver already gone.");
            }
        }
    }

    VLOG("mNextPullTimeNs: %lld updated to %lld", (long long)mNextPullTimeNs,
         (long long)minNextPullTimeNs);
    mNextPullTimeNs = minNextPullTimeNs;
    updateAlarmLocked();
}

int StatsPullerManager::ForceClearPullerCache() {
    int totalCleared = 0;
    for (const auto& pulledAtom : kAllPullAtomInfo) {
        totalCleared += pulledAtom.second.puller->ForceClearCache();
    }
    return totalCleared;
}

int StatsPullerManager::ClearPullerCacheIfNecessary(int64_t timestampNs) {
    int totalCleared = 0;
    for (const auto& pulledAtom : kAllPullAtomInfo) {
        totalCleared += pulledAtom.second.puller->ClearCacheIfNecessary(timestampNs);
    }
    return totalCleared;
}

void StatsPullerManager::RegisterPullerCallback(int32_t atomTag,
        const sp<IStatsPullerCallback>& callback) {
    AutoMutex _l(mLock);
    // Platform pullers cannot be changed.
    if (atomTag < StatsdStats::kMaxPlatformAtomTag) {
        VLOG("RegisterPullerCallback: atom tag %d is less than min tag %d",
                atomTag, StatsdStats::kMaxPlatformAtomTag);
        return;
    }
    VLOG("RegisterPullerCallback: adding puller for tag %d", atomTag);
    StatsdStats::getInstance().notePullerCallbackRegistrationChanged(atomTag, /*registered=*/true);
    kAllPullAtomInfo[atomTag] = {.puller = new StatsCallbackPuller(atomTag, callback)};
}

void StatsPullerManager::UnregisterPullerCallback(int32_t atomTag) {
    AutoMutex _l(mLock);
    // Platform pullers cannot be changed.
    if (atomTag < StatsdStats::kMaxPlatformAtomTag) {
        return;
    }
    StatsdStats::getInstance().notePullerCallbackRegistrationChanged(atomTag, /*registered=*/false);
    kAllPullAtomInfo.erase(atomTag);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
