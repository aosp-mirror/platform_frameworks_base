/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "BatteryStatsService"
//#define LOG_NDEBUG 0

#include <climits>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <semaphore.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <unordered_map>
#include <utility>

#include <android/hardware/power/1.0/IPower.h>
#include <android/hardware/power/1.1/IPower.h>
#include <android/hardware/power/stats/1.0/IPowerStats.h>
#include <android/system/suspend/BnSuspendCallback.h>
#include <android/system/suspend/ISuspendControlService.h>
#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include <log/log.h>
#include <utils/misc.h>
#include <utils/Log.h>

using android::hardware::Return;
using android::hardware::Void;
using android::system::suspend::BnSuspendCallback;
using android::hardware::power::V1_0::PowerStatePlatformSleepState;
using android::hardware::power::V1_0::PowerStateVoter;
using android::hardware::power::V1_0::Status;
using android::hardware::power::V1_1::PowerStateSubsystem;
using android::hardware::power::V1_1::PowerStateSubsystemSleepState;
using android::hardware::hidl_vec;
using android::system::suspend::ISuspendControlService;
using IPowerV1_1 = android::hardware::power::V1_1::IPower;
using IPowerV1_0 = android::hardware::power::V1_0::IPower;

namespace android
{

#define LAST_RESUME_REASON "/sys/kernel/wakeup_reasons/last_resume_reason"
#define MAX_REASON_SIZE 512

static bool wakeup_init = false;
static sem_t wakeup_sem;
extern sp<IPowerV1_0> getPowerHalV1_0();
extern sp<IPowerV1_1> getPowerHalV1_1();
extern bool processPowerHalReturn(const Return<void> &ret, const char* functionName);
extern sp<ISuspendControlService> getSuspendControl();

// Java methods used in getLowPowerStats
static jmethodID jgetAndUpdatePlatformState = NULL;
static jmethodID jgetSubsystem = NULL;
static jmethodID jputVoter = NULL;
static jmethodID jputState = NULL;

std::mutex gPowerHalMutex;
std::unordered_map<uint32_t, std::string> gPowerStatsHalEntityNames = {};
std::unordered_map<uint32_t, std::unordered_map<uint32_t, std::string>>
    gPowerStatsHalStateNames = {};
std::vector<uint32_t> gPowerStatsHalPlatformIds = {};
std::vector<uint32_t> gPowerStatsHalSubsystemIds = {};
sp<android::hardware::power::stats::V1_0::IPowerStats> gPowerStatsHalV1_0 = nullptr;
std::function<void(JNIEnv*, jobject)> gGetLowPowerStatsImpl = {};
std::function<jint(JNIEnv*, jobject)> gGetPlatformLowPowerStatsImpl = {};
std::function<jint(JNIEnv*, jobject)> gGetSubsystemLowPowerStatsImpl = {};

// Cellular/Wifi power monitor rail information
static jmethodID jupdateRailData = NULL;
static jmethodID jsetRailStatsAvailability = NULL;

std::function<void(JNIEnv*, jobject)> gGetRailEnergyPowerStatsImpl = {};

std::unordered_map<uint32_t, std::pair<std::string, std::string>> gPowerStatsHalRailNames = {};
static bool power_monitor_available = false;

// The caller must be holding gPowerHalMutex.
static void deinitPowerStatsLocked() {
    gPowerStatsHalV1_0 = nullptr;
}

struct PowerHalDeathRecipient : virtual public hardware::hidl_death_recipient {
    virtual void serviceDied(uint64_t cookie,
            const wp<android::hidl::base::V1_0::IBase>& who) override {
        // The HAL just died. Reset all handles to HAL services.
        std::lock_guard<std::mutex> lock(gPowerHalMutex);
        deinitPowerStatsLocked();
    }
};

sp<PowerHalDeathRecipient> gDeathRecipient = new PowerHalDeathRecipient();

class WakeupCallback : public BnSuspendCallback {
   public:
    binder::Status notifyWakeup(bool success) override {
        ALOGI("In wakeup_callback: %s", success ? "resumed from suspend" : "suspend aborted");
        int ret = sem_post(&wakeup_sem);
        if (ret < 0) {
            char buf[80];
            strerror_r(errno, buf, sizeof(buf));
            ALOGE("Error posting wakeup sem: %s\n", buf);
        }
        return binder::Status::ok();
    }
};

static jint nativeWaitWakeup(JNIEnv *env, jobject clazz, jobject outBuf)
{
    if (outBuf == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "null argument");
        return -1;
    }

    // Register our wakeup callback if not yet done.
    if (!wakeup_init) {
        wakeup_init = true;
        ALOGV("Creating semaphore...");
        int ret = sem_init(&wakeup_sem, 0, 0);
        if (ret < 0) {
            char buf[80];
            strerror_r(errno, buf, sizeof(buf));
            ALOGE("Error creating semaphore: %s\n", buf);
            jniThrowException(env, "java/lang/IllegalStateException", buf);
            return -1;
        }
        sp<ISuspendControlService> suspendControl = getSuspendControl();
        bool isRegistered = false;
        suspendControl->registerCallback(new WakeupCallback(), &isRegistered);
        if (!isRegistered) {
            ALOGE("Failed to register wakeup callback");
        }
    }

    // Wait for wakeup.
    ALOGV("Waiting for wakeup...");
    // TODO(b/116747600): device can suspend and wakeup after sem_wait() finishes and before wakeup
    // reason is recorded, i.e. BatteryStats might occasionally miss wakeup events.
    int ret = sem_wait(&wakeup_sem);
    if (ret < 0) {
        char buf[80];
        strerror_r(errno, buf, sizeof(buf));
        ALOGE("Error waiting on semaphore: %s\n", buf);
        // Return 0 here to let it continue looping but not return results.
        return 0;
    }

    FILE *fp = fopen(LAST_RESUME_REASON, "r");
    if (fp == NULL) {
        ALOGE("Failed to open %s", LAST_RESUME_REASON);
        return -1;
    }

    char* mergedreason = (char*)env->GetDirectBufferAddress(outBuf);
    int remainreasonlen = (int)env->GetDirectBufferCapacity(outBuf);

    ALOGV("Reading wakeup reasons");
    char* mergedreasonpos = mergedreason;
    char reasonline[128];
    int i = 0;
    while (fgets(reasonline, sizeof(reasonline), fp) != NULL) {
        char* pos = reasonline;
        char* endPos;
        int len;
        // First field is the index or 'Abort'.
        int irq = (int)strtol(pos, &endPos, 10);
        if (pos != endPos) {
            // Write the irq number to the merged reason string.
            len = snprintf(mergedreasonpos, remainreasonlen, i == 0 ? "%d" : ":%d", irq);
        } else {
            // The first field is not an irq, it may be the word Abort.
            const size_t abortPrefixLen = strlen("Abort:");
            if (strncmp(pos, "Abort:", abortPrefixLen) != 0) {
                // Ooops.
                ALOGE("Bad reason line: %s", reasonline);
                continue;
            }

            // Write 'Abort' to the merged reason string.
            len = snprintf(mergedreasonpos, remainreasonlen, i == 0 ? "Abort" : ":Abort");
            endPos = pos + abortPrefixLen;
        }
        pos = endPos;

        if (len >= 0 && len < remainreasonlen) {
            mergedreasonpos += len;
            remainreasonlen -= len;
        }

        // Skip whitespace; rest of the buffer is the reason string.
        while (*pos == ' ') {
            pos++;
        }

        // Chop newline at end.
        char* endpos = pos;
        while (*endpos != 0) {
            if (*endpos == '\n') {
                *endpos = 0;
                break;
            }
            endpos++;
        }

        len = snprintf(mergedreasonpos, remainreasonlen, ":%s", pos);
        if (len >= 0 && len < remainreasonlen) {
            mergedreasonpos += len;
            remainreasonlen -= len;
        }
        i++;
    }

    ALOGV("Got %d reasons", i);
    if (i > 0) {
        *mergedreasonpos = 0;
    }

    if (fclose(fp) != 0) {
        ALOGE("Failed to close %s", LAST_RESUME_REASON);
        return -1;
    }
    return mergedreasonpos - mergedreason;
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
    using android::hardware::power::stats::V1_0::PowerEntityType;

    // Clear out previous content if we are re-initializing
    gPowerStatsHalEntityNames.clear();
    gPowerStatsHalStateNames.clear();
    gPowerStatsHalPlatformIds.clear();
    gPowerStatsHalSubsystemIds.clear();
    gPowerStatsHalRailNames.clear();

    Return<void> ret;
    ret = gPowerStatsHalV1_0->getPowerEntityInfo([](auto infos, auto status) {
        if (status != Status::SUCCESS) {
            ALOGE("Error getting power entity info");
            return;
        }

        // construct lookup table of powerEntityId to power entity name
        // also construct vector of platform and subsystem IDs
        for (auto info : infos) {
            gPowerStatsHalEntityNames.emplace(info.powerEntityId, info.powerEntityName);
            if (info.type == PowerEntityType::POWER_DOMAIN) {
                gPowerStatsHalPlatformIds.emplace_back(info.powerEntityId);
            } else {
                gPowerStatsHalSubsystemIds.emplace_back(info.powerEntityId);
            }
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
            gPowerStatsHalStateNames.emplace(stateSpace.powerEntityId, stateNames);
        }
    });
    if (!checkResultLocked(ret, __func__)) {
        return false;
    }

    // Get Power monitor rails available
    ret = gPowerStatsHalV1_0->getRailInfo([](auto rails, auto status) {
        if (status != Status::SUCCESS) {
            ALOGW("Rail information is not available");
            power_monitor_available = false;
            return;
        }

        // Fill out rail names/subsystems into gPowerStatsHalRailNames
        for (auto rail : rails) {
            gPowerStatsHalRailNames.emplace(rail.index,
                std::make_pair(rail.railName, rail.subsysName));
        }
        if (!gPowerStatsHalRailNames.empty()) {
            power_monitor_available = true;
        }
    });
    if (!checkResultLocked(ret, __func__)) {
        return false;
    }

    return (!gPowerStatsHalEntityNames.empty()) && (!gPowerStatsHalStateNames.empty());
}

// The caller must be holding gPowerHalMutex.
static bool getPowerStatsHalLocked() {
    if (gPowerStatsHalV1_0 == nullptr) {
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

// The caller must be holding powerHalMutex.
static void getPowerStatsHalLowPowerData(JNIEnv* env, jobject jrpmStats) {
    using android::hardware::power::stats::V1_0::Status;

    if (!getPowerStatsHalLocked()) {
        ALOGE("failed to get low power stats");
        return;
    }

    // Get power entity state residency data
    bool success = false;
    Return<void> ret = gPowerStatsHalV1_0->getPowerEntityStateResidencyData({},
        [&env, &jrpmStats, &success](auto results, auto status) {
            if (status == Status::NOT_SUPPORTED) {
                ALOGW("getPowerEntityStateResidencyData is not supported");
                success = false;
                return;
            }

            for (auto result : results) {
                jobject jsubsystem = env->CallObjectMethod(jrpmStats, jgetSubsystem,
                    env->NewStringUTF(gPowerStatsHalEntityNames.at(result.powerEntityId).c_str()));
                if (jsubsystem == NULL) {
                    ALOGE("The rpmstats jni jobject jsubsystem is null.");
                    return;
                }
                for (auto stateResidency : result.stateResidencyData) {

                    env->CallVoidMethod(jsubsystem, jputState,
                        env->NewStringUTF(gPowerStatsHalStateNames.at(result.powerEntityId)
                        .at(stateResidency.powerEntityStateId).c_str()),
                        stateResidency.totalTimeInStateMs,
                        stateResidency.totalStateEntryCount);
                }
            }
            success = true;
        });
    checkResultLocked(ret, __func__);
    if (!success) {
        ALOGE("getPowerEntityStateResidencyData failed");
    }
}

static jint getPowerStatsHalPlatformData(JNIEnv* env, jobject outBuf) {
    using android::hardware::power::stats::V1_0::Status;
    using hardware::power::stats::V1_0::PowerEntityStateResidencyResult;
    using hardware::power::stats::V1_0::PowerEntityStateResidencyData;

    if (!getPowerStatsHalLocked()) {
        ALOGE("failed to get low power stats");
        return -1;
    }

    char *output = (char*)env->GetDirectBufferAddress(outBuf);
    char *offset = output;
    int remaining = (int)env->GetDirectBufferCapacity(outBuf);
    int total_added = -1;

    // Get power entity state residency data
    Return<void> ret = gPowerStatsHalV1_0->getPowerEntityStateResidencyData(
        gPowerStatsHalPlatformIds,
        [&offset, &remaining, &total_added](auto results, auto status) {
            if (status == Status::NOT_SUPPORTED) {
                ALOGW("getPowerEntityStateResidencyData is not supported");
                return;
            }

            for (size_t i = 0; i < results.size(); i++) {
                const PowerEntityStateResidencyResult& result = results[i];

                for (size_t j = 0; j < result.stateResidencyData.size(); j++) {
                    const PowerEntityStateResidencyData& stateResidency =
                        result.stateResidencyData[j];
                    int added = snprintf(offset, remaining,
                        "state_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " ",
                        j + 1, gPowerStatsHalStateNames.at(result.powerEntityId)
                           .at(stateResidency.powerEntityStateId).c_str(),
                        stateResidency.totalTimeInStateMs,
                        stateResidency.totalStateEntryCount);
                    if (added < 0) {
                        break;
                    }
                    if (added > remaining) {
                        added = remaining;
                    }
                    offset += added;
                    remaining -= added;
                    total_added += added;
                }
                if (remaining <= 0) {
                    /* rewrite NULL character*/
                    offset--;
                    total_added--;
                    ALOGE("power.stats Hal: buffer not enough");
                    break;
                }
            }
        });
    if (!checkResultLocked(ret, __func__)) {
        return -1;
    }

    total_added += 1;
    return total_added;
}

static jint getPowerStatsHalSubsystemData(JNIEnv* env, jobject outBuf) {
    using android::hardware::power::stats::V1_0::Status;
    using hardware::power::stats::V1_0::PowerEntityStateResidencyResult;
    using hardware::power::stats::V1_0::PowerEntityStateResidencyData;

    if (!getPowerStatsHalLocked()) {
        ALOGE("failed to get low power stats");
        return -1;
    }

    char *output = (char*)env->GetDirectBufferAddress(outBuf);
    char *offset = output;
    int remaining = (int)env->GetDirectBufferCapacity(outBuf);
    int total_added = -1;

    // Get power entity state residency data
    Return<void> ret = gPowerStatsHalV1_0->getPowerEntityStateResidencyData(
        gPowerStatsHalSubsystemIds,
        [&offset, &remaining, &total_added](auto results, auto status) {
            if (status == Status::NOT_SUPPORTED) {
                ALOGW("getPowerEntityStateResidencyData is not supported");
                return;
            }

            int added = snprintf(offset, remaining, "SubsystemPowerState ");
            offset += added;
            remaining -= added;
            total_added += added;

            for (size_t i = 0; i < results.size(); i++) {
                const PowerEntityStateResidencyResult& result = results[i];
                added = snprintf(offset, remaining, "subsystem_%zu name=%s ",
                        i + 1, gPowerStatsHalEntityNames.at(result.powerEntityId).c_str());
                if (added < 0) {
                    break;
                }

                if (added > remaining) {
                    added = remaining;
                }

                offset += added;
                remaining -= added;
                total_added += added;

                for (size_t j = 0; j < result.stateResidencyData.size(); j++) {
                    const PowerEntityStateResidencyData& stateResidency =
                        result.stateResidencyData[j];
                    added = snprintf(offset, remaining,
                        "state_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " last entry=%"
                        PRIu64 " ", j + 1, gPowerStatsHalStateNames.at(result.powerEntityId)
                           .at(stateResidency.powerEntityStateId).c_str(),
                        stateResidency.totalTimeInStateMs,
                        stateResidency.totalStateEntryCount,
                        stateResidency.lastEntryTimestampMs);
                    if (added < 0) {
                        break;
                    }
                    if (added > remaining) {
                        added = remaining;
                    }
                    offset += added;
                    remaining -= added;
                    total_added += added;
                }
                if (remaining <= 0) {
                    /* rewrite NULL character*/
                    offset--;
                    total_added--;
                    ALOGE("power.stats Hal: buffer not enough");
                    break;
                }
            }
        });
    if (!checkResultLocked(ret, __func__)) {
        return -1;
    }

    total_added += 1;
    return total_added;
}

static void getPowerStatsHalRailEnergyData(JNIEnv* env, jobject jrailStats) {
    using android::hardware::power::stats::V1_0::Status;
    using android::hardware::power::stats::V1_0::EnergyData;

    if (!getPowerStatsHalLocked()) {
        ALOGE("failed to get power stats");
        return;
    }

    if (!power_monitor_available) {
        env->CallVoidMethod(jrailStats, jsetRailStatsAvailability, false);
        ALOGW("Rail energy data is not available");
        return;
    }

    // Get power rail energySinceBoot data
    Return<void> ret = gPowerStatsHalV1_0->getEnergyData({},
        [&env, &jrailStats](auto energyData, auto status) {
            if (status == Status::NOT_SUPPORTED) {
                ALOGW("getEnergyData is not supported");
                return;
            }

            for (auto data : energyData) {
                if (!(data.timestamp > LLONG_MAX || data.energy > LLONG_MAX)) {
                    env->CallVoidMethod(jrailStats,
                        jupdateRailData,
                        data.index,
                        env->NewStringUTF(
                            gPowerStatsHalRailNames.at(data.index).first.c_str()),
                        env->NewStringUTF(
                            gPowerStatsHalRailNames.at(data.index).second.c_str()),
                        data.timestamp,
                        data.energy);
                } else {
                    ALOGE("Java long overflow seen. Rail index %d not updated", data.index);
                }
            }
        });
    if (!checkResultLocked(ret, __func__)) {
        ALOGE("getEnergyData failed");
    }
}

// The caller must be holding powerHalMutex.
static void getPowerHalLowPowerData(JNIEnv* env, jobject jrpmStats) {
    sp<IPowerV1_0> powerHalV1_0 = getPowerHalV1_0();
    if (powerHalV1_0 == nullptr) {
        ALOGE("Power Hal not loaded");
        return;
    }

    Return<void> ret = powerHalV1_0->getPlatformLowPowerStats(
            [&env, &jrpmStats](hidl_vec<PowerStatePlatformSleepState> states, Status status) {

            if (status != Status::SUCCESS) return;

            for (size_t i = 0; i < states.size(); i++) {
                const PowerStatePlatformSleepState& state = states[i];

                jobject jplatformState = env->CallObjectMethod(jrpmStats,
                        jgetAndUpdatePlatformState,
                        env->NewStringUTF(state.name.c_str()),
                        state.residencyInMsecSinceBoot,
                        state.totalTransitions);
                if (jplatformState == NULL) {
                    ALOGE("The rpmstats jni jobject jplatformState is null.");
                    return;
                }

                for (size_t j = 0; j < state.voters.size(); j++) {
                    const PowerStateVoter& voter = state.voters[j];
                    env->CallVoidMethod(jplatformState, jputVoter,
                            env->NewStringUTF(voter.name.c_str()),
                            voter.totalTimeInMsecVotedForSinceBoot,
                            voter.totalNumberOfTimesVotedSinceBoot);
                }
            }
    });
    if (!processPowerHalReturn(ret, "getPlatformLowPowerStats")) {
        return;
    }

    // Trying to get IPower 1.1, this will succeed only for devices supporting 1.1
    sp<IPowerV1_1> powerHal_1_1 = getPowerHalV1_1();
    if (powerHal_1_1 == nullptr) {
        // This device does not support IPower@1.1, exiting gracefully
        return;
    }
    ret = powerHal_1_1->getSubsystemLowPowerStats(
            [&env, &jrpmStats](hidl_vec<PowerStateSubsystem> subsystems, Status status) {

        if (status != Status::SUCCESS) return;

        if (subsystems.size() > 0) {
            for (size_t i = 0; i < subsystems.size(); i++) {
                const PowerStateSubsystem &subsystem = subsystems[i];

                jobject jsubsystem = env->CallObjectMethod(jrpmStats, jgetSubsystem,
                        env->NewStringUTF(subsystem.name.c_str()));
                if (jsubsystem == NULL) {
                    ALOGE("The rpmstats jni jobject jsubsystem is null.");
                    return;
                }

                for (size_t j = 0; j < subsystem.states.size(); j++) {
                    const PowerStateSubsystemSleepState& state = subsystem.states[j];
                    env->CallVoidMethod(jsubsystem, jputState,
                            env->NewStringUTF(state.name.c_str()),
                            state.residencyInMsecSinceBoot,
                            state.totalTransitions);
                }
            }
        }
    });
    processPowerHalReturn(ret, "getSubsystemLowPowerStats");
}

static jint getPowerHalPlatformData(JNIEnv* env, jobject outBuf) {
    char *output = (char*)env->GetDirectBufferAddress(outBuf);
    char *offset = output;
    int remaining = (int)env->GetDirectBufferCapacity(outBuf);
    int total_added = -1;

    {
        sp<IPowerV1_0> powerHalV1_0 = getPowerHalV1_0();
        if (powerHalV1_0 == nullptr) {
            ALOGE("Power Hal not loaded");
            return -1;
        }

        Return<void> ret = powerHalV1_0->getPlatformLowPowerStats(
            [&offset, &remaining, &total_added](hidl_vec<PowerStatePlatformSleepState> states,
                    Status status) {
                if (status != Status::SUCCESS)
                    return;
                for (size_t i = 0; i < states.size(); i++) {
                    int added;
                    const PowerStatePlatformSleepState& state = states[i];

                    added = snprintf(offset, remaining,
                        "state_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " ",
                        i + 1, state.name.c_str(), state.residencyInMsecSinceBoot,
                        state.totalTransitions);
                    if (added < 0) {
                        break;
                    }
                    if (added > remaining) {
                        added = remaining;
                    }
                    offset += added;
                    remaining -= added;
                    total_added += added;

                    for (size_t j = 0; j < state.voters.size(); j++) {
                        const PowerStateVoter& voter = state.voters[j];
                        added = snprintf(offset, remaining,
                                "voter_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " ",
                                j + 1, voter.name.c_str(),
                                voter.totalTimeInMsecVotedForSinceBoot,
                                voter.totalNumberOfTimesVotedSinceBoot);
                        if (added < 0) {
                            break;
                        }
                        if (added > remaining) {
                            added = remaining;
                        }
                        offset += added;
                        remaining -= added;
                        total_added += added;
                    }

                    if (remaining <= 0) {
                        /* rewrite NULL character*/
                        offset--;
                        total_added--;
                        ALOGE("PowerHal: buffer not enough");
                        break;
                    }
                }
            }
        );

        if (!processPowerHalReturn(ret, "getPlatformLowPowerStats")) {
            return -1;
        }
    }
    *offset = 0;
    total_added += 1;
    return total_added;
}

static jint getPowerHalSubsystemData(JNIEnv* env, jobject outBuf) {
    char *output = (char*)env->GetDirectBufferAddress(outBuf);
    char *offset = output;
    int remaining = (int)env->GetDirectBufferCapacity(outBuf);
    int total_added = -1;

    // This is a IPower 1.1 API
    sp<IPowerV1_1> powerHal_1_1 = nullptr;

    {
        // Trying to get 1.1, this will succeed only for devices supporting 1.1
        powerHal_1_1 = getPowerHalV1_1();
        if (powerHal_1_1 == nullptr) {
            //This device does not support IPower@1.1, exiting gracefully
            return 0;
        }

        Return<void> ret = powerHal_1_1->getSubsystemLowPowerStats(
           [&offset, &remaining, &total_added](hidl_vec<PowerStateSubsystem> subsystems,
                Status status) {

            if (status != Status::SUCCESS)
                return;

            if (subsystems.size() > 0) {
                int added = snprintf(offset, remaining, "SubsystemPowerState ");
                offset += added;
                remaining -= added;
                total_added += added;

                for (size_t i = 0; i < subsystems.size(); i++) {
                    const PowerStateSubsystem &subsystem = subsystems[i];

                    added = snprintf(offset, remaining,
                                     "subsystem_%zu name=%s ", i + 1, subsystem.name.c_str());
                    if (added < 0) {
                        break;
                    }

                    if (added > remaining) {
                        added = remaining;
                    }

                    offset += added;
                    remaining -= added;
                    total_added += added;

                    for (size_t j = 0; j < subsystem.states.size(); j++) {
                        const PowerStateSubsystemSleepState& state = subsystem.states[j];
                        added = snprintf(offset, remaining,
                                         "state_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " last entry=%" PRIu64 " ",
                                         j + 1, state.name.c_str(), state.residencyInMsecSinceBoot,
                                         state.totalTransitions, state.lastEntryTimestampMs);
                        if (added < 0) {
                            break;
                        }

                        if (added > remaining) {
                            added = remaining;
                        }

                        offset += added;
                        remaining -= added;
                        total_added += added;
                    }

                    if (remaining <= 0) {
                        /* rewrite NULL character*/
                        offset--;
                        total_added--;
                        ALOGE("PowerHal: buffer not enough");
                        break;
                    }
                }
            }
        }
        );

        if (!processPowerHalReturn(ret, "getSubsystemLowPowerStats")) {
            return -1;
        }
    }

    *offset = 0;
    total_added += 1;
    return total_added;
}

static void setUpPowerStatsLocked() {
    // First see if power.stats HAL is available. Fall back to power HAL if
    // power.stats HAL is unavailable.
    if (android::hardware::power::stats::V1_0::IPowerStats::getService() != nullptr) {
        ALOGI("Using power.stats HAL");
        gGetLowPowerStatsImpl = getPowerStatsHalLowPowerData;
        gGetPlatformLowPowerStatsImpl = getPowerStatsHalPlatformData;
        gGetSubsystemLowPowerStatsImpl = getPowerStatsHalSubsystemData;
        gGetRailEnergyPowerStatsImpl = getPowerStatsHalRailEnergyData;
    } else if (android::hardware::power::V1_0::IPower::getService() != nullptr) {
        ALOGI("Using power HAL");
        gGetLowPowerStatsImpl = getPowerHalLowPowerData;
        gGetPlatformLowPowerStatsImpl = getPowerHalPlatformData;
        gGetSubsystemLowPowerStatsImpl = getPowerHalSubsystemData;
        gGetRailEnergyPowerStatsImpl = NULL;
    }
}

static void getLowPowerStats(JNIEnv* env, jobject /* clazz */, jobject jrpmStats) {
    if (jrpmStats == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                "The rpmstats jni input jobject jrpmStats is null.");
        return;
    }
    if (jgetAndUpdatePlatformState == NULL || jgetSubsystem == NULL
            || jputVoter == NULL || jputState == NULL) {
        ALOGE("A rpmstats jni jmethodID is null.");
        return;
    }

    std::lock_guard<std::mutex> lock(gPowerHalMutex);

    if (!gGetLowPowerStatsImpl) {
        setUpPowerStatsLocked();
    }

    if (gGetLowPowerStatsImpl) {
        return gGetLowPowerStatsImpl(env, jrpmStats);
    }

    ALOGE("Unable to load Power Hal or power.stats HAL");
    return;
}

static jint getPlatformLowPowerStats(JNIEnv* env, jobject /* clazz */, jobject outBuf) {
    if (outBuf == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "null argument");
        return -1;
    }

    std::lock_guard<std::mutex> lock(gPowerHalMutex);

    if (!gGetPlatformLowPowerStatsImpl) {
        setUpPowerStatsLocked();
    }

    if (gGetPlatformLowPowerStatsImpl) {
        return gGetPlatformLowPowerStatsImpl(env, outBuf);
    }

    ALOGE("Unable to load Power Hal or power.stats HAL");
    return -1;
}

static jint getSubsystemLowPowerStats(JNIEnv* env, jobject /* clazz */, jobject outBuf) {
    if (outBuf == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "null argument");
        return -1;
    }

    std::lock_guard<std::mutex> lock(gPowerHalMutex);

    if (!gGetSubsystemLowPowerStatsImpl) {
        setUpPowerStatsLocked();
    }

    if (gGetSubsystemLowPowerStatsImpl) {
        return gGetSubsystemLowPowerStatsImpl(env, outBuf);
    }

    ALOGE("Unable to load Power Hal or power.stats HAL");
    return -1;
}

static void getRailEnergyPowerStats(JNIEnv* env, jobject /* clazz */, jobject jrailStats) {
    if (jrailStats == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                "The railstats jni input jobject jrailStats is null.");
        return;
    }
    if (jupdateRailData == NULL) {
        ALOGE("A railstats jni jmethodID is null.");
        return;
    }

    std::lock_guard<std::mutex> lock(gPowerHalMutex);

    if (!gGetRailEnergyPowerStatsImpl) {
        setUpPowerStatsLocked();
    }

    if (gGetRailEnergyPowerStatsImpl)  {
        gGetRailEnergyPowerStatsImpl(env, jrailStats);
        return;
    }

    if (jsetRailStatsAvailability == NULL) {
        ALOGE("setRailStatsAvailability jni jmethodID is null.");
        return;
    }
    env->CallVoidMethod(jrailStats, jsetRailStatsAvailability, false);
    ALOGE("Unable to load Power.Stats.HAL. Setting rail availability to false");
    return;
}

static const JNINativeMethod method_table[] = {
    { "nativeWaitWakeup", "(Ljava/nio/ByteBuffer;)I", (void*)nativeWaitWakeup },
    { "getLowPowerStats", "(Lcom/android/internal/os/RpmStats;)V", (void*)getLowPowerStats },
    { "getPlatformLowPowerStats", "(Ljava/nio/ByteBuffer;)I", (void*)getPlatformLowPowerStats },
    { "getSubsystemLowPowerStats", "(Ljava/nio/ByteBuffer;)I", (void*)getSubsystemLowPowerStats },
    { "getRailEnergyPowerStats", "(Lcom/android/internal/os/RailStats;)V",
        (void*)getRailEnergyPowerStats },
};

int register_android_server_BatteryStatsService(JNIEnv *env)
{
    // get java classes and methods
    jclass clsRpmStats = env->FindClass("com/android/internal/os/RpmStats");
    jclass clsPowerStatePlatformSleepState =
            env->FindClass("com/android/internal/os/RpmStats$PowerStatePlatformSleepState");
    jclass clsPowerStateSubsystem =
            env->FindClass("com/android/internal/os/RpmStats$PowerStateSubsystem");
    jclass clsRailStats = env->FindClass("com/android/internal/os/RailStats");
    if (clsRpmStats == NULL || clsPowerStatePlatformSleepState == NULL
            || clsPowerStateSubsystem == NULL || clsRailStats == NULL) {
        ALOGE("A rpmstats jni jclass is null.");
    } else {
        jgetAndUpdatePlatformState = env->GetMethodID(clsRpmStats, "getAndUpdatePlatformState",
                "(Ljava/lang/String;JI)Lcom/android/internal/os/RpmStats$PowerStatePlatformSleepState;");
        jgetSubsystem = env->GetMethodID(clsRpmStats, "getSubsystem",
                "(Ljava/lang/String;)Lcom/android/internal/os/RpmStats$PowerStateSubsystem;");
        jputVoter = env->GetMethodID(clsPowerStatePlatformSleepState, "putVoter",
                "(Ljava/lang/String;JI)V");
        jputState = env->GetMethodID(clsPowerStateSubsystem, "putState",
                "(Ljava/lang/String;JI)V");
        jupdateRailData = env->GetMethodID(clsRailStats, "updateRailData",
                "(JLjava/lang/String;Ljava/lang/String;JJ)V");
        jsetRailStatsAvailability = env->GetMethodID(clsRailStats, "setRailStatsAvailability",
                "(Z)V");
    }

    return jniRegisterNativeMethods(env, "com/android/server/am/BatteryStatsService",
            method_table, NELEM(method_table));
}

};
