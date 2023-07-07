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
#include <climits>
#include <unordered_map>
#include <utility>

#include <android-base/thread_annotations.h>
#include <android/hardware/power/1.0/IPower.h>
#include <android/hardware/power/1.1/IPower.h>
#include <android/hardware/power/stats/1.0/IPowerStats.h>
#include <android/system/suspend/BnSuspendCallback.h>
#include <android/system/suspend/ISuspendControlService.h>
#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include <powermanager/PowerHalLoader.h>

#include <log/log.h>
#include <utils/misc.h>
#include <utils/Log.h>

#include <android-base/strings.h>

using android::hardware::hidl_vec;
using android::hardware::Return;
using android::hardware::Void;
using android::hardware::power::stats::V1_0::IPowerStats;
using android::system::suspend::BnSuspendCallback;
using android::system::suspend::ISuspendControlService;

namespace android
{

static bool wakeup_init = false;
static std::mutex mReasonsMutex;
static std::vector<std::string> mWakeupReasons;
static sem_t wakeup_sem;
extern sp<ISuspendControlService> getSuspendControl();

std::mutex gPowerStatsHalMutex;
sp<IPowerStats> gPowerStatsHalV1_0 = nullptr;

std::function<void(JNIEnv*, jobject)> gGetRailEnergyPowerStatsImpl = {};

// Cellular/Wifi power monitor rail information
static jmethodID jupdateRailData = NULL;
static jmethodID jsetRailStatsAvailability = NULL;

std::unordered_map<uint32_t, std::pair<std::string, std::string>> gPowerStatsHalRailNames = {};
static bool power_monitor_available = false;

static void deinitPowerStatsHalLocked() EXCLUSIVE_LOCKS_REQUIRED(gPowerStatsHalMutex) {
    gPowerStatsHalV1_0 = nullptr;
}

struct PowerHalDeathRecipient : virtual public hardware::hidl_death_recipient {
    virtual void serviceDied(uint64_t cookie,
            const wp<android::hidl::base::V1_0::IBase>& who) override {
        // The HAL just died. Reset all handles to HAL services.
        std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);
        deinitPowerStatsHalLocked();
    }
};

sp<PowerHalDeathRecipient> gDeathRecipient = new PowerHalDeathRecipient();

class WakeupCallback : public BnSuspendCallback {
public:
    binder::Status notifyWakeup(bool success,
                                const std::vector<std::string>& wakeupReasons) override {
        ALOGV("In wakeup_callback: %s", success ? "resumed from suspend" : "suspend aborted");
        bool reasonsCaptured = false;
        {
            std::unique_lock<std::mutex> reasonsLock(mReasonsMutex, std::defer_lock);
            if (reasonsLock.try_lock() && mWakeupReasons.empty()) {
                mWakeupReasons = wakeupReasons;
                reasonsCaptured = true;
            }
        }
        if (!reasonsCaptured) {
            ALOGE("Failed to write wakeup reasons. Reasons dropped:");
            for (auto wakeupReason : wakeupReasons) {
                ALOGE("\t%s", wakeupReason.c_str());
            }
        }

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
    int ret = sem_wait(&wakeup_sem);
    if (ret < 0) {
        char buf[80];
        strerror_r(errno, buf, sizeof(buf));
        ALOGE("Error waiting on semaphore: %s\n", buf);
        // Return 0 here to let it continue looping but not return results.
        return 0;
    }

    char* mergedreason = (char*)env->GetDirectBufferAddress(outBuf);
    int remainreasonlen = (int)env->GetDirectBufferCapacity(outBuf);

    ALOGV("Reading wakeup reasons");
    std::vector<std::string> wakeupReasons;
    {
        std::unique_lock<std::mutex> reasonsLock(mReasonsMutex, std::defer_lock);
        if (reasonsLock.try_lock() && !mWakeupReasons.empty()) {
            wakeupReasons = std::move(mWakeupReasons);
            mWakeupReasons.clear();
        }
    }

    if (wakeupReasons.empty()) {
        return 0;
    }

    std::string mergedReasonStr = ::android::base::Join(wakeupReasons, ":");
    strncpy(mergedreason, mergedReasonStr.c_str(), remainreasonlen);
    mergedreason[remainreasonlen - 1] = '\0';

    ALOGV("Got %d reasons", (int)wakeupReasons.size());

    return strlen(mergedreason);
}

static bool checkPowerStatsHalResultLocked(const Return<void>& ret, const char* function)
        EXCLUSIVE_LOCKS_REQUIRED(gPowerStatsHalMutex) {
    if (!ret.isOk()) {
        ALOGE("%s failed: requested HAL service not available. Description: %s",
            function, ret.description().c_str());
        if (ret.isDeadObject()) {
            deinitPowerStatsHalLocked();
        }
        return false;
    }
    return true;
}

// gPowerStatsHalV1_0 must not be null
static bool initializePowerStatsLocked() EXCLUSIVE_LOCKS_REQUIRED(gPowerStatsHalMutex) {
    using android::hardware::power::stats::V1_0::Status;

    // Clear out previous content if we are re-initializing
    gPowerStatsHalRailNames.clear();

    Return<void> ret;

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
    if (!checkPowerStatsHalResultLocked(ret, __func__)) {
        return false;
    }

    return true;
}

static bool getPowerStatsHalLocked() EXCLUSIVE_LOCKS_REQUIRED(gPowerStatsHalMutex) {
    if (gPowerStatsHalV1_0 == nullptr) {
        gPowerStatsHalV1_0 = IPowerStats::getService();
        if (gPowerStatsHalV1_0 == nullptr) {
            ALOGE("Unable to get power.stats HAL service.");
            return false;
        }

        // Link death recipient to power.stats service handle
        hardware::Return<bool> linked = gPowerStatsHalV1_0->linkToDeath(gDeathRecipient, 0);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to power.stats HAL death: %s",
                    linked.description().c_str());
            deinitPowerStatsHalLocked();
            return false;
        } else if (!linked) {
            ALOGW("Unable to link to power.stats HAL death notifications");
            // We should still continue even though linking failed
        }
        return initializePowerStatsLocked();
    }
    return true;
}

static void getPowerStatsHalRailEnergyDataLocked(JNIEnv* env, jobject jrailStats)
        EXCLUSIVE_LOCKS_REQUIRED(gPowerStatsHalMutex) {
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
    if (!checkPowerStatsHalResultLocked(ret, __func__)) {
        ALOGE("getEnergyData failed");
    }
}

static void setUpPowerStatsLocked() EXCLUSIVE_LOCKS_REQUIRED(gPowerStatsHalMutex) {
    // First see if power.stats HAL is available. Fall back to power HAL if
    // power.stats HAL is unavailable.
    if (IPowerStats::getService() != nullptr) {
        ALOGI("Using power.stats HAL");
        gGetRailEnergyPowerStatsImpl = getPowerStatsHalRailEnergyDataLocked;
    } else {
        gGetRailEnergyPowerStatsImpl = NULL;
    }
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

    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!gGetRailEnergyPowerStatsImpl) {
        setUpPowerStatsLocked();
    }

    if (gGetRailEnergyPowerStatsImpl) {
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
    { "getRailEnergyPowerStats", "(Lcom/android/internal/os/RailStats;)V",
        (void*)getRailEnergyPowerStats },
};

int register_android_server_BatteryStatsService(JNIEnv *env)
{
    // get java classes and methods
    jclass clsRailStats = env->FindClass("com/android/internal/os/RailStats");
    if (clsRailStats == NULL) {
        ALOGE("A rpmstats jni jclass is null.");
    } else {
        jupdateRailData = env->GetMethodID(clsRailStats, "updateRailData",
                "(JLjava/lang/String;Ljava/lang/String;JJ)V");
        jsetRailStatsAvailability = env->GetMethodID(clsRailStats, "setRailStatsAvailability",
                "(Z)V");
    }

    return jniRegisterNativeMethods(env, "com/android/server/am/BatteryStatsService",
            method_table, NELEM(method_table));
}

};
