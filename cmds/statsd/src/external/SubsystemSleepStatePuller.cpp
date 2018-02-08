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
using android::hardware::power::V1_0::Status;
using android::hardware::power::V1_1::PowerStateSubsystem;
using android::hardware::power::V1_1::PowerStateSubsystemSleepState;
using android::hardware::Return;
using android::hardware::Void;

using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

sp<android::hardware::power::V1_0::IPower> gPowerHalV1_0 = nullptr;
sp<android::hardware::power::V1_1::IPower> gPowerHalV1_1 = nullptr;
std::mutex gPowerHalMutex;
bool gPowerHalExists = true;

bool getPowerHal() {
    if (gPowerHalExists && gPowerHalV1_0 == nullptr) {
        gPowerHalV1_0 = android::hardware::power::V1_0::IPower::getService();
        if (gPowerHalV1_0 != nullptr) {
            gPowerHalV1_1 = android::hardware::power::V1_1::IPower::castFrom(gPowerHalV1_0);
            ALOGI("Loaded power HAL service");
        } else {
            ALOGW("Couldn't load power HAL service");
            gPowerHalExists = false;
        }
    }
    return gPowerHalV1_0 != nullptr;
}

SubsystemSleepStatePuller::SubsystemSleepStatePuller() : StatsPuller(android::util::SUBSYSTEM_SLEEP_STATE) {
}

bool SubsystemSleepStatePuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);

    if (!getPowerHal()) {
        ALOGE("Power Hal not loaded");
        return false;
    }

    int64_t wallClockTimestampNs = getWallClockNs();
    int64_t elapsedTimestampNs = getElapsedRealtimeNs();

    data->clear();

    Return<void> ret;
        ret = gPowerHalV1_0->getPlatformLowPowerStats(
                [&data, wallClockTimestampNs, elapsedTimestampNs](hidl_vec<PowerStatePlatformSleepState> states, Status status) {
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
                        for (auto voter : state.voters) {
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
        if (!ret.isOk()) {
            ALOGE("getLowPowerStats() failed: power HAL service not available");
            gPowerHalV1_0 = nullptr;
            return false;
        }

        // Trying to cast to IPower 1.1, this will succeed only for devices supporting 1.1
        sp<android::hardware::power::V1_1::IPower> gPowerHal_1_1 =
                android::hardware::power::V1_1::IPower::castFrom(gPowerHalV1_0);
        if (gPowerHal_1_1 != nullptr) {
            ret = gPowerHal_1_1->getSubsystemLowPowerStats(
                    [&data, wallClockTimestampNs, elapsedTimestampNs](hidl_vec<PowerStateSubsystem> subsystems, Status status) {
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

}  // namespace statsd
}  // namespace os
}  // namespace android
