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

#define DEBUG false // STOPSHIP if true
#define LOG_TAG "PowerStatsPuller"

#include <android/hardware/power/stats/1.0/IPowerStats.h>
#include <log/log.h>
#include <statslog.h>

#include <vector>

#include "PowerStatsPuller.h"

using android::hardware::hidl_vec;
using android::hardware::Return;
using android::hardware::Void;
using android::hardware::power::stats::V1_0::EnergyData;
using android::hardware::power::stats::V1_0::IPowerStats;
using android::hardware::power::stats::V1_0::RailInfo;
using android::hardware::power::stats::V1_0::Status;

namespace android {
namespace server {
namespace stats {

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

PowerStatsPuller::PowerStatsPuller() {}

AStatsManager_PullAtomCallbackReturn PowerStatsPuller::Pull(int32_t atomTag,
                                                            AStatsEventList* data) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!getPowerStatsHalLocked()) {
        return AStatsManager_PULL_SKIP;
    }

    // Pull getRailInfo if necessary
    if (gRailInfo.empty()) {
        bool resultSuccess = true;
        Return<void> ret = gPowerStatsHal->getRailInfo(
                [&resultSuccess](const hidl_vec<RailInfo>& list, Status status) {
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
            return AStatsManager_PULL_SKIP;
        }
        // If SUCCESS but empty, or if NOT_SUPPORTED, then never try again.
        if (gRailInfo.empty()) {
            ALOGE("power.stats has no rail information");
            gPowerStatsExist = false; // No rail info, so never try again.
            gPowerStatsHal = nullptr;
            return AStatsManager_PULL_SKIP;
        }
    }

    // Pull getEnergyData and write the data out
    const hidl_vec<uint32_t> desiredRailIndices; // Empty vector indicates we want all.
    bool resultSuccess = true;
    Return<void> ret =
            gPowerStatsHal
                    ->getEnergyData(desiredRailIndices,
                                    [&data, &resultSuccess](hidl_vec<EnergyData> energyDataList,
                                                            Status status) {
                                        resultSuccess = (status == Status::SUCCESS);
                                        if (!resultSuccess) return;

                                        for (size_t i = 0; i < energyDataList.size(); i++) {
                                            const EnergyData& energyData = energyDataList[i];

                                            if (energyData.index >= gRailInfo.size()) {
                                                ALOGE("power.stats getEnergyData() returned an "
                                                      "invalid rail index %u.",
                                                      energyData.index);
                                                resultSuccess = false;
                                                return;
                                            }
                                            const RailInfo& rail = gRailInfo[energyData.index];

                                            AStatsEvent* event =
                                                    AStatsEventList_addStatsEvent(data);
                                            AStatsEvent_setAtomId(
                                                    event,
                                                    android::util::ON_DEVICE_POWER_MEASUREMENT);
                                            AStatsEvent_writeString(event, rail.subsysName.c_str());
                                            AStatsEvent_writeString(event, rail.railName.c_str());
                                            AStatsEvent_writeInt64(event, energyData.timestamp);
                                            AStatsEvent_writeInt64(event, energyData.energy);
                                            AStatsEvent_build(event);

                                            ALOGV("power.stat: %s.%s: %llu, %llu",
                                                  rail.subsysName.c_str(), rail.railName.c_str(),
                                                  (unsigned long long)energyData.timestamp,
                                                  (unsigned long long)energyData.energy);
                                        }
                                    });
    if (!resultSuccess || !ret.isOk()) {
        ALOGE("power.stats getEnergyData() failed. Description: %s", ret.description().c_str());
        gPowerStatsHal = nullptr;
        return AStatsManager_PULL_SKIP;
    }
    return AStatsManager_PULL_SUCCESS;
}

} // namespace stats
} // namespace server
} // namespace android
