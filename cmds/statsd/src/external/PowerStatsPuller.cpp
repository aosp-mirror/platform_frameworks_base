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

#include <android/hardware/power/stats/1.0/IPowerStats.h>

#include <vector>

#include "PowerStatsPuller.h"
#include "stats_log_util.h"

using android::hardware::hidl_vec;
using android::hardware::power::stats::V1_0::IPowerStats;
using android::hardware::power::stats::V1_0::EnergyData;
using android::hardware::power::stats::V1_0::RailInfo;
using android::hardware::power::stats::V1_0::Status;
using android::hardware::Return;
using android::hardware::Void;

using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

static sp<android::hardware::power::stats::V1_0::IPowerStats> gPowerStatsHal = nullptr;
static std::mutex gPowerStatsHalMutex;
static bool gPowerStatsExist = true; // Initialized to ensure making a first attempt.
static std::vector<RailInfo> gRailInfo;

struct PowerStatsPullerDeathRecipient : virtual public hardware::hidl_death_recipient {
    virtual void serviceDied(uint64_t cookie,
            const wp<android::hidl::base::V1_0::IBase>& who) override {
        // The HAL just died. Reset all handles to HAL services.
        std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);
        gPowerStatsHal = nullptr;
    }
};

static sp<PowerStatsPullerDeathRecipient> gDeathRecipient = new PowerStatsPullerDeathRecipient();

static bool getPowerStatsHalLocked() {
    if (gPowerStatsHal == nullptr && gPowerStatsExist) {
        gPowerStatsHal = android::hardware::power::stats::V1_0::IPowerStats::getService();
        if (gPowerStatsHal == nullptr) {
            ALOGW("Couldn't load power.stats HAL service");
            gPowerStatsExist = false;
        } else {
            // Link death recipient to power.stats service handle
            hardware::Return<bool> linked = gPowerStatsHal->linkToDeath(gDeathRecipient, 0);
            if (!linked.isOk()) {
                ALOGE("Transaction error in linking to power.stats HAL death: %s",
                        linked.description().c_str());
                gPowerStatsHal = nullptr;
                return false;
            } else if (!linked) {
                ALOGW("Unable to link to power.stats HAL death notifications");
                // We should still continue even though linking failed
            }
        }
    }
    return gPowerStatsHal != nullptr;
}

PowerStatsPuller::PowerStatsPuller() : StatsPuller(android::util::ON_DEVICE_POWER_MEASUREMENT) {
}

bool PowerStatsPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!getPowerStatsHalLocked()) {
        ALOGE("power.stats Hal not loaded");
        return false;
    }

    int64_t wallClockTimestampNs = getWallClockNs();
    int64_t elapsedTimestampNs = getElapsedRealtimeNs();

    data->clear();

    // Pull getRailInfo if necessary
    if (gRailInfo.empty()) {
        bool resultSuccess = true;
        Return<void> ret = gPowerStatsHal->getRailInfo(
                [&resultSuccess](const hidl_vec<RailInfo> &list, Status status) {
                    resultSuccess = (status == Status::SUCCESS || status == Status::NOT_SUPPORTED);
                    if (status != Status::SUCCESS) return;

                    gRailInfo.reserve(list.size());
                    for (size_t i = 0; i < list.size(); ++i) {
                        gRailInfo.push_back(list[i]);
                    }
                });
        if (!resultSuccess || !ret.isOk()) {
            ALOGE("power.stats getRailInfo() failed. Description: %s", ret.description().c_str());
            gPowerStatsHal = nullptr;
            return false;
        }
        // If SUCCESS but empty, or if NOT_SUPPORTED, then never try again.
        if (gRailInfo.empty()) {
            ALOGE("power.stats has no rail information");
            gPowerStatsExist = false; // No rail info, so never try again.
            return false;
        }
    }

    // Pull getEnergyData and write the data out
    const hidl_vec<uint32_t> desiredRailIndices; // Empty vector indicates we want all.
    bool resultSuccess = true;
    Return<void> ret = gPowerStatsHal->getEnergyData(desiredRailIndices,
                [&data, wallClockTimestampNs, elapsedTimestampNs, &resultSuccess]
                (hidl_vec<EnergyData> energyDataList, Status status) {
                    resultSuccess = (status == Status::SUCCESS);
                    if (!resultSuccess) return;

                    for (size_t i = 0; i < energyDataList.size(); i++) {
                        const EnergyData& energyData = energyDataList[i];

                        if (energyData.index >= gRailInfo.size()) {
                            ALOGE("power.stats getEnergyData() returned an invalid rail index %u.",
                                    energyData.index);
                            resultSuccess = false;
                            return;
                        }
                        const RailInfo& rail = gRailInfo[energyData.index];

                        auto ptr = make_shared<LogEvent>(android::util::ON_DEVICE_POWER_MEASUREMENT,
                              wallClockTimestampNs, elapsedTimestampNs);
                        ptr->write(rail.subsysName);
                        ptr->write(rail.railName);
                        ptr->write(energyData.timestamp);
                        ptr->write(energyData.energy);
                        ptr->init();
                        data->push_back(ptr);

                        VLOG("power.stat: %s.%s: %llu, %llu",
                             rail.subsysName.c_str(),
                             rail.railName.c_str(),
                             (unsigned long long)energyData.timestamp,
                             (unsigned long long)energyData.energy);
                    }
                });
    if (!resultSuccess || !ret.isOk()) {
        ALOGE("power.stats getEnergyData() failed. Description: %s", ret.description().c_str());
        gPowerStatsHal = nullptr;
        return false;
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
