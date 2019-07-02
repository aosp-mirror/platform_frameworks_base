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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include <android/hardware/power/1.0/IPower.h>
#include <android/hardware/power/1.1/IPower.h>
#include <android/hardware/power/stats/1.0/IPowerStats.h>

#include <fcntl.h>
#include <hardware/power.h>
#include <hardware_legacy/power.h>
#include <inttypes.h>
#include <semaphore.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include "external/SubsystemSleepStatePuller.h"
#include "external/StatsPuller.h"

#include "SubsystemSleepStatePuller.h"
#include "logd/LogEvent.h"
#include "statslog.h"
#include "stats_log_util.h"

using android::hardware::hidl_vec;
using android::hardware::power::V1_0::IPower;
using android::hardware::power::V1_0::PowerStatePlatformSleepState;
using android::hardware::power::V1_0::PowerStateVoter;
using android::hardware::power::V1_1::PowerStateSubsystem;
using android::hardware::power::V1_1::PowerStateSubsystemSleepState;
using android::hardware::power::stats::V1_0::PowerEntityInfo;
using android::hardware::power::stats::V1_0::PowerEntityStateResidencyResult;
using android::hardware::power::stats::V1_0::PowerEntityStateSpace;

using android::hardware::Return;
using android::hardware::Void;

using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

static std::function<bool(vector<shared_ptr<LogEvent>>* data)> gPuller = {};

static sp<android::hardware::power::V1_0::IPower> gPowerHalV1_0 = nullptr;
static sp<android::hardware::power::V1_1::IPower> gPowerHalV1_1 = nullptr;
static sp<android::hardware::power::stats::V1_0::IPowerStats> gPowerStatsHalV1_0 = nullptr;

static std::unordered_map<uint32_t, std::string> gEntityNames = {};
static std::unordered_map<uint32_t, std::unordered_map<uint32_t, std::string>> gStateNames = {};

static std::mutex gPowerHalMutex;

// The caller must be holding gPowerHalMutex.
static void deinitPowerStatsLocked() {
    gPowerHalV1_0 = nullptr;
    gPowerHalV1_1 = nullptr;
    gPowerStatsHalV1_0 = nullptr;
}

struct SubsystemSleepStatePullerDeathRecipient : virtual public hardware::hidl_death_recipient {
    virtual void serviceDied(uint64_t cookie,
            const wp<android::hidl::base::V1_0::IBase>& who) override {

        // The HAL just died. Reset all handles to HAL services.
        std::lock_guard<std::mutex> lock(gPowerHalMutex);
        deinitPowerStatsLocked();
    }
};

static sp<SubsystemSleepStatePullerDeathRecipient> gDeathRecipient =
        new SubsystemSleepStatePullerDeathRecipient();

SubsystemSleepStatePuller::SubsystemSleepStatePuller() :
    StatsPuller(android::util::SUBSYSTEM_SLEEP_STATE) {
}

// The caller must be holding gPowerHalMutex.
static bool checkResultLocked(const Return<void> &ret, const char* function) {
    if (!ret.isOk()) {
        ALOGE("%s failed: requested HAL service not available. Description: %s",
            function, ret.description().c_str());
        if (ret.isDeadObject()) {
            deinitPowerStatsLocked();
        }
        return false;
    }
    return true;
}

// The caller must be holding gPowerHalMutex.
// gPowerStatsHalV1_0 must not be null
static bool initializePowerStats() {
    using android::hardware::power::stats::V1_0::Status;

    // Clear out previous content if we are re-initializing
    gEntityNames.clear();
    gStateNames.clear();

    Return<void> ret;
    ret = gPowerStatsHalV1_0->getPowerEntityInfo([](auto infos, auto status) {
        if (status != Status::SUCCESS) {
            ALOGE("Error getting power entity info");
            return;
        }

        // construct lookup table of powerEntityId to power entity name
        for (auto info : infos) {
            gEntityNames.emplace(info.powerEntityId, info.powerEntityName);
        }
    });
    if (!checkResultLocked(ret, __func__)) {
        return false;
    }

    ret = gPowerStatsHalV1_0->getPowerEntityStateInfo({}, [](auto stateSpaces, auto status) {
        if (status != Status::SUCCESS) {
            ALOGE("Error getting state info");
            return;
        }

        // construct lookup table of powerEntityId, powerEntityStateId to power entity state name
        for (auto stateSpace : stateSpaces) {
            std::unordered_map<uint32_t, std::string> stateNames = {};
            for (auto state : stateSpace.states) {
                stateNames.emplace(state.powerEntityStateId,
                    state.powerEntityStateName);
            }
            gStateNames.emplace(stateSpace.powerEntityId, stateNames);
        }
    });
    if (!checkResultLocked(ret, __func__)) {
        return false;
    }

    return (!gEntityNames.empty()) && (!gStateNames.empty());
}

// The caller must be holding gPowerHalMutex.
static bool getPowerStatsHalLocked() {
    if(gPowerStatsHalV1_0 == nullptr) {
        gPowerStatsHalV1_0 = android::hardware::power::stats::V1_0::IPowerStats::getService();
        if (gPowerStatsHalV1_0 == nullptr) {
            ALOGE("Unable to get power.stats HAL service.");
            return false;
        }

        // Link death recipient to power.stats service handle
        hardware::Return<bool> linked = gPowerStatsHalV1_0->linkToDeath(gDeathRecipient, 0);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to power.stats HAL death: %s",
                    linked.description().c_str());
            deinitPowerStatsLocked();
            return false;
        } else if (!linked) {
            ALOGW("Unable to link to power.stats HAL death notifications");
            // We should still continue even though linking failed
        }
        return initializePowerStats();
    }
    return true;
}

// The caller must be holding gPowerHalMutex.
static bool getIPowerStatsDataLocked(vector<shared_ptr<LogEvent>>* data) {
    using android::hardware::power::stats::V1_0::Status;

    if(!getPowerStatsHalLocked()) {
        return false;
    }

    int64_t wallClockTimestampNs = getWallClockNs();
    int64_t elapsedTimestampNs = getElapsedRealtimeNs();

    // Get power entity state residency data
    bool success = false;
    Return<void> ret = gPowerStatsHalV1_0->getPowerEntityStateResidencyData({},
        [&data, &success, wallClockTimestampNs, elapsedTimestampNs]
        (auto results, auto status) {
        if (status == Status::NOT_SUPPORTED) {
            ALOGW("getPowerEntityStateResidencyData is not supported");
            success = false;
            return;
        }

        for(auto result : results) {
            for(auto stateResidency : result.stateResidencyData) {
                auto statePtr = make_shared<LogEvent>(
                        android::util::SUBSYSTEM_SLEEP_STATE,
                        wallClockTimestampNs, elapsedTimestampNs);
                statePtr->write(gEntityNames.at(result.powerEntityId));
                statePtr->write(gStateNames.at(result.powerEntityId)
                    .at(stateResidency.powerEntityStateId));
                statePtr->write(stateResidency.totalStateEntryCount);
                statePtr->write(stateResidency.totalTimeInStateMs);
                statePtr->init();
                data->emplace_back(statePtr);
            }
        }
        success = true;
    });
    // Intentionally not returning early here.
    // bool success determines if this succeeded or not.
    checkResultLocked(ret, __func__);

    return success;
}

// The caller must be holding gPowerHalMutex.
static bool getPowerHalLocked() {
    if(gPowerHalV1_0 == nullptr) {
        gPowerHalV1_0 = android::hardware::power::V1_0::IPower::getService();
        if(gPowerHalV1_0 == nullptr) {
            ALOGE("Unable to get power HAL service.");
            return false;
        }
        gPowerHalV1_1 = android::hardware::power::V1_1::IPower::castFrom(gPowerHalV1_0);

        // Link death recipient to power service handle
        hardware::Return<bool> linked = gPowerHalV1_0->linkToDeath(gDeathRecipient, 0);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to power HAL death: %s",
                    linked.description().c_str());
            gPowerHalV1_0 = nullptr;
            return false;
        } else if (!linked) {
            ALOGW("Unable to link to power. death notifications");
            // We should still continue even though linking failed
        }
    }
    return true;
}

// The caller must be holding gPowerHalMutex.
static bool getIPowerDataLocked(vector<shared_ptr<LogEvent>>* data) {
    using android::hardware::power::V1_0::Status;

    if(!getPowerHalLocked()) {
        return false;
    }

    int64_t wallClockTimestampNs = getWallClockNs();
    int64_t elapsedTimestampNs = getElapsedRealtimeNs();
        Return<void> ret;
        ret = gPowerHalV1_0->getPlatformLowPowerStats(
                [&data, wallClockTimestampNs, elapsedTimestampNs]
                    (hidl_vec<PowerStatePlatformSleepState> states, Status status) {
                    if (status != Status::SUCCESS) return;

                    for (size_t i = 0; i < states.size(); i++) {
                        const PowerStatePlatformSleepState& state = states[i];

                        auto statePtr = make_shared<LogEvent>(
                            android::util::SUBSYSTEM_SLEEP_STATE,
                            wallClockTimestampNs, elapsedTimestampNs);
                        statePtr->write(state.name);
                        statePtr->write("");
                        statePtr->write(state.totalTransitions);
                        statePtr->write(state.residencyInMsecSinceBoot);
                        statePtr->init();
                        data->push_back(statePtr);
                        VLOG("powerstate: %s, %lld, %lld, %d", state.name.c_str(),
                             (long long)state.residencyInMsecSinceBoot,
                             (long long)state.totalTransitions,
                             state.supportedOnlyInSuspend ? 1 : 0);
                        for (const auto& voter : state.voters) {
                            auto voterPtr = make_shared<LogEvent>(
                                android::util::SUBSYSTEM_SLEEP_STATE,
                                wallClockTimestampNs, elapsedTimestampNs);
                            voterPtr->write(state.name);
                            voterPtr->write(voter.name);
                            voterPtr->write(voter.totalNumberOfTimesVotedSinceBoot);
                            voterPtr->write(voter.totalTimeInMsecVotedForSinceBoot);
                            voterPtr->init();
                            data->push_back(voterPtr);
                            VLOG("powerstatevoter: %s, %s, %lld, %lld", state.name.c_str(),
                                 voter.name.c_str(),
                                 (long long)voter.totalTimeInMsecVotedForSinceBoot,
                                 (long long)voter.totalNumberOfTimesVotedSinceBoot);
                        }
                    }
                });
        if (!checkResultLocked(ret, __func__)) {
            return false;
        }

        // Trying to cast to IPower 1.1, this will succeed only for devices supporting 1.1
        sp<android::hardware::power::V1_1::IPower> gPowerHal_1_1 =
                android::hardware::power::V1_1::IPower::castFrom(gPowerHalV1_0);
        if (gPowerHal_1_1 != nullptr) {
            ret = gPowerHal_1_1->getSubsystemLowPowerStats(
            [&data, wallClockTimestampNs, elapsedTimestampNs]
            (hidl_vec<PowerStateSubsystem> subsystems, Status status) {
                if (status != Status::SUCCESS) return;

                if (subsystems.size() > 0) {
                    for (size_t i = 0; i < subsystems.size(); i++) {
                        const PowerStateSubsystem& subsystem = subsystems[i];
                        for (size_t j = 0; j < subsystem.states.size(); j++) {
                            const PowerStateSubsystemSleepState& state =
                                    subsystem.states[j];
                            auto subsystemStatePtr = make_shared<LogEvent>(
                                android::util::SUBSYSTEM_SLEEP_STATE,
                                wallClockTimestampNs, elapsedTimestampNs);
                            subsystemStatePtr->write(subsystem.name);
                            subsystemStatePtr->write(state.name);
                            subsystemStatePtr->write(state.totalTransitions);
                            subsystemStatePtr->write(state.residencyInMsecSinceBoot);
                            subsystemStatePtr->init();
                            data->push_back(subsystemStatePtr);
                            VLOG("subsystemstate: %s, %s, %lld, %lld, %lld",
                                 subsystem.name.c_str(), state.name.c_str(),
                                 (long long)state.residencyInMsecSinceBoot,
                                 (long long)state.totalTransitions,
                                 (long long)state.lastEntryTimestampMs);
                        }
                    }
                }
            });
        }
        return true;
}

// The caller must be holding gPowerHalMutex.
std::function<bool(vector<shared_ptr<LogEvent>>* data)> getPullerLocked() {
    std::function<bool(vector<shared_ptr<LogEvent>>* data)> ret = {};

    // First see if power.stats HAL is available. Fall back to power HAL if
    // power.stats HAL is unavailable.
    if(android::hardware::power::stats::V1_0::IPowerStats::getService() != nullptr) {
        ALOGI("Using power.stats HAL");
        ret = getIPowerStatsDataLocked;
    } else if(android::hardware::power::V1_0::IPower::getService() != nullptr) {
        ALOGI("Using power HAL");
        ret = getIPowerDataLocked;
    }

    return ret;
}

bool SubsystemSleepStatePuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);

    if(!gPuller) {
        gPuller = getPullerLocked();
    }

    if(gPuller) {
        return gPuller(data);
    }

    ALOGE("Unable to load Power Hal or power.stats HAL");
    return false;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
