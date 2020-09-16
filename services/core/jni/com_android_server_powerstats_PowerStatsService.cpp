/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "PowerStatsService"

#include <android/hardware/power/stats/1.0/IPowerStats.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include <log/log.h>

using android::hardware::hidl_vec;
using android::hardware::Return;
using android::hardware::power::stats::V1_0::EnergyData;
using android::hardware::power::stats::V1_0::RailInfo;
using android::hardware::power::stats::V1_0::Status;

static jclass class_railInfo;
static jmethodID method_railInfoInit;
static jclass class_energyData;
static jmethodID method_energyDataInit;

namespace android {

static std::mutex gPowerStatsHalMutex;
static sp<android::hardware::power::stats::V1_0::IPowerStats> gPowerStatsHalV1_0_ptr = nullptr;

static void deinitPowerStats() {
    gPowerStatsHalV1_0_ptr = nullptr;
}

struct PowerStatsHalDeathRecipient : virtual public hardware::hidl_death_recipient {
    virtual void serviceDied(uint64_t cookie,
                             const wp<android::hidl::base::V1_0::IBase> &who) override {
        // The HAL just died. Reset all handles to HAL services.
        std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);
        deinitPowerStats();
    }
};

sp<PowerStatsHalDeathRecipient> gPowerStatsHalDeathRecipient = new PowerStatsHalDeathRecipient();

static bool connectToPowerStatsHal() {
    if (gPowerStatsHalV1_0_ptr == nullptr) {
        gPowerStatsHalV1_0_ptr = android::hardware::power::stats::V1_0::IPowerStats::getService();

        if (gPowerStatsHalV1_0_ptr == nullptr) {
            ALOGE("Unable to get power.stats HAL service.");
            return false;
        }

        // Link death recipient to power.stats service handle
        hardware::Return<bool> linked =
                gPowerStatsHalV1_0_ptr->linkToDeath(gPowerStatsHalDeathRecipient, 0);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to power.stats HAL death: %s",
                  linked.description().c_str());
            deinitPowerStats();
            return false;
        } else if (!linked) {
            ALOGW("Unable to link to power.stats HAL death notifications");
            return false;
        }
    }
    return true;
}

static bool checkResult(const Return<void> &ret, const char *function) {
    if (!ret.isOk()) {
        ALOGE("%s failed: requested HAL service not available. Description: %s", function,
              ret.description().c_str());
        if (ret.isDeadObject()) {
            deinitPowerStats();
        }
        return false;
    }
    return true;
}

static jobjectArray nativeGetRailInfo(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeGetRailInfo failed to connect to power.stats HAL");
        return nullptr;
    }

    hidl_vec<RailInfo> list;
    Return<void> ret = gPowerStatsHalV1_0_ptr->getRailInfo([&list](auto rails, auto status) {
        if (status != Status::SUCCESS) {
            ALOGW("Rail information is not available");
        } else {
            list = std::move(rails);
        }
    });

    if (!checkResult(ret, __func__)) {
        ALOGE("getRailInfo failed");
        return nullptr;
    } else {
        jobjectArray railInfoArray = env->NewObjectArray(list.size(), class_railInfo, nullptr);
        for (int i = 0; i < list.size(); i++) {
            jstring railName = env->NewStringUTF(list[i].railName.c_str());
            jstring subsysName = env->NewStringUTF(list[i].subsysName.c_str());
            jobject railInfo = env->NewObject(class_railInfo, method_railInfoInit, list[i].index,
                                              railName, subsysName, list[i].samplingRate);
            env->SetObjectArrayElement(railInfoArray, i, railInfo);
            env->DeleteLocalRef(railName);
            env->DeleteLocalRef(subsysName);
            env->DeleteLocalRef(railInfo);
        }
        return railInfoArray;
    }
}

static jobjectArray nativeGetEnergyData(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeGetEnergy failed to connect to power.stats HAL");
    }

    hidl_vec<EnergyData> list;
    Return<void> ret =
            gPowerStatsHalV1_0_ptr->getEnergyData({}, [&list](auto energyData, auto status) {
                if (status != Status::SUCCESS) {
                    ALOGW("getEnergyData is not supported");
                } else {
                    list = std::move(energyData);
                }
            });

    if (!checkResult(ret, __func__)) {
        ALOGE("getEnergyData failed");
        return nullptr;
    } else {
        jobjectArray energyDataArray = env->NewObjectArray(list.size(), class_energyData, nullptr);
        for (int i = 0; i < list.size(); i++) {
            jobject energyData = env->NewObject(class_energyData, method_energyDataInit,
                                                list[i].index, list[i].timestamp, list[i].energy);
            env->SetObjectArrayElement(energyDataArray, i, energyData);
            env->DeleteLocalRef(energyData);
        }
        return energyDataArray;
    }
}

static jboolean nativeInit(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    jclass temp = env->FindClass("com/android/server/powerstats/PowerStatsData$RailInfo");
    if (temp == nullptr) return false;

    class_railInfo = (jclass)env->NewGlobalRef(temp);
    if (class_railInfo == nullptr) return false;

    method_railInfoInit =
            env->GetMethodID(class_railInfo, "<init>", "(JLjava/lang/String;Ljava/lang/String;J)V");
    if (method_railInfoInit == nullptr) return false;

    temp = env->FindClass("com/android/server/powerstats/PowerStatsData$EnergyData");
    if (temp == nullptr) return false;

    class_energyData = (jclass)env->NewGlobalRef(temp);
    if (class_energyData == nullptr) return false;

    method_energyDataInit = env->GetMethodID(class_energyData, "<init>", "(JJJ)V");
    if (method_energyDataInit == nullptr) return false;

    bool rv = true;

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeInit failed to connect to power.stats HAL");
        rv = false;
    } else {
        Return<void> ret = gPowerStatsHalV1_0_ptr->getRailInfo([&rv](auto rails, auto status) {
            if (status != Status::SUCCESS) {
                ALOGE("nativeInit RailInfo is unavailable");
                rv = false;
            }
        });

        ret = gPowerStatsHalV1_0_ptr->getEnergyData({}, [&rv](auto energyData, auto status) {
            if (status != Status::SUCCESS) {
                ALOGE("nativeInit EnergyData is unavailable");
                rv = false;
            }
        });
    }

    return rv;
}

static const JNINativeMethod method_table[] = {
        {"nativeInit", "()Z", (void *)nativeInit},
        {"nativeGetRailInfo", "()[Lcom/android/server/powerstats/PowerStatsData$RailInfo;",
         (void *)nativeGetRailInfo},
        {"nativeGetEnergyData", "()[Lcom/android/server/powerstats/PowerStatsData$EnergyData;",
         (void *)nativeGetEnergyData},
};

int register_android_server_PowerStatsService(JNIEnv *env) {
    return jniRegisterNativeMethods(env,
                                    "com/android/server/powerstats/"
                                    "PowerStatsHALWrapper$PowerStatsHALWrapperImpl",
                                    method_table, NELEM(method_table));
}

}; // namespace android
