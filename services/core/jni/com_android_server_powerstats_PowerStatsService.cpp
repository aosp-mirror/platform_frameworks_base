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

// ChannelInfo
static jclass class_CI;
static jmethodID method_CI_init;
static jfieldID field_CI_channelId;
static jfieldID field_CI_channelName;

// EnergyMeasurement
static jclass class_EM;
static jmethodID method_EM_init;
static jfieldID field_EM_channelId;
static jfieldID field_EM_timestampMs;
static jfieldID field_EM_durationMs;
static jfieldID field_EM_energyUWs;

// StateInfo
static jclass class_SI;
static jmethodID method_SI_init;
static jfieldID field_SI_stateId;
static jfieldID field_SI_stateName;

// PowerEntityInfo
static jclass class_PEI;
static jmethodID method_PEI_init;
static jfieldID field_PEI_powerEntityId;
static jfieldID field_PEI_powerEntityName;
static jfieldID field_PEI_states;

// StateResidency
static jclass class_SR;
static jmethodID method_SR_init;
static jfieldID field_SR_stateId;
static jfieldID field_SR_totalTimeInStateMs;
static jfieldID field_SR_totalStateEntryCount;
static jfieldID field_SR_lastEntryTimestampMs;

// StateResidencyResult
static jclass class_SRR;
static jmethodID method_SRR_init;
static jfieldID field_SRR_powerEntityId;
static jfieldID field_SRR_stateResidencyData;

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

static jobjectArray nativeGetPowerEntityInfo(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeGetPowerEntityInfo failed to connect to power.stats HAL");
        return nullptr;
    }

    jobjectArray powerEntityInfoArray = nullptr;
    Return<void> ret = gPowerStatsHalV1_0_ptr->getPowerEntityInfo(
            [&env, &powerEntityInfoArray](auto infos, auto status) {
                if (status != Status::SUCCESS) {
                    ALOGE("Error getting power entity info");
                } else {
                    powerEntityInfoArray = env->NewObjectArray(infos.size(), class_PEI, nullptr);
                    for (int i = 0; i < infos.size(); i++) {
                        jstring powerEntityName =
                                env->NewStringUTF(infos[i].powerEntityName.c_str());
                        jobject powerEntityInfo = env->NewObject(class_PEI, method_PEI_init);
                        env->SetIntField(powerEntityInfo, field_PEI_powerEntityId,
                                         infos[i].powerEntityId);
                        env->SetObjectField(powerEntityInfo, field_PEI_powerEntityName,
                                            powerEntityName);
                        env->SetObjectArrayElement(powerEntityInfoArray, i, powerEntityInfo);
                        env->DeleteLocalRef(powerEntityName);
                        env->DeleteLocalRef(powerEntityInfo);
                    }
                }
            });
    if (!checkResult(ret, __func__)) {
        return nullptr;
    }

    ret = gPowerStatsHalV1_0_ptr->getPowerEntityStateInfo(
            {}, [&env, &powerEntityInfoArray](auto infos, auto status) {
                if (status != Status::SUCCESS) {
                    ALOGE("Error getting power entity state info");
                } else {
                    for (int i = 0; i < infos.size(); i++) {
                        jobjectArray stateInfoArray =
                                env->NewObjectArray(infos[i].states.size(), class_SI, nullptr);
                        for (int j = 0; j < infos[i].states.size(); j++) {
                            jstring powerEntityStateName = env->NewStringUTF(
                                    infos[i].states[j].powerEntityStateName.c_str());
                            jobject stateInfo = env->NewObject(class_SI, method_SI_init);
                            env->SetIntField(stateInfo, field_SI_stateId,
                                             infos[i].states[j].powerEntityStateId);
                            env->SetObjectField(stateInfo, field_SI_stateName,
                                                powerEntityStateName);
                            env->SetObjectArrayElement(stateInfoArray, j, stateInfo);
                            env->DeleteLocalRef(powerEntityStateName);
                            env->DeleteLocalRef(stateInfo);
                        }

                        for (int j = 0; j < env->GetArrayLength(powerEntityInfoArray); j++) {
                            jobject powerEntityInfo =
                                    env->GetObjectArrayElement(powerEntityInfoArray, j);
                            if (env->GetIntField(powerEntityInfo, field_PEI_powerEntityId) ==
                                infos[i].powerEntityId) {
                                env->SetObjectField(powerEntityInfo, field_PEI_states,
                                                    stateInfoArray);
                                env->SetObjectArrayElement(powerEntityInfoArray, j,
                                                           powerEntityInfo);
                                break;
                            }
                        }
                    }
                }
            });
    if (!checkResult(ret, __func__)) {
        return nullptr;
    }

    return powerEntityInfoArray;
}

static jobjectArray nativeGetStateResidency(JNIEnv *env, jclass clazz, jintArray powerEntityIds) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeGetStateResidency failed to connect to power.stats HAL");
        return nullptr;
    }

    size_t powerEntityIdCount = env->GetArrayLength(powerEntityIds);
    hidl_vec<uint32_t> powerEntityIdVector(powerEntityIdCount);

    jint *powerEntityIdElements = env->GetIntArrayElements(powerEntityIds, 0);
    for (int i = 0; i < powerEntityIdCount; i++) {
        powerEntityIdVector[i] = powerEntityIdElements[i];
    }
    env->ReleaseIntArrayElements(powerEntityIds, powerEntityIdElements, 0);

    jobjectArray stateResidencyResultArray = nullptr;
    Return<void> ret = gPowerStatsHalV1_0_ptr->getPowerEntityStateResidencyData(
            powerEntityIdVector, [&env, &stateResidencyResultArray](auto results, auto status) {
                if (status != Status::SUCCESS) {
                    ALOGE("Error getting power entity state residency data");
                } else {
                    stateResidencyResultArray =
                            env->NewObjectArray(results.size(), class_SRR, nullptr);
                    for (int i = 0; i < results.size(); i++) {
                        jobjectArray stateResidencyArray =
                                env->NewObjectArray(results[i].stateResidencyData.size(), class_SR,
                                                    nullptr);
                        for (int j = 0; j < results[i].stateResidencyData.size(); j++) {
                            jobject stateResidency = env->NewObject(class_SR, method_SR_init);
                            env->SetIntField(stateResidency, field_SR_stateId,
                                             results[i].stateResidencyData[j].powerEntityStateId);
                            env->SetLongField(stateResidency, field_SR_totalTimeInStateMs,
                                              results[i].stateResidencyData[j].totalTimeInStateMs);
                            env->SetLongField(stateResidency, field_SR_totalStateEntryCount,
                                              results[i]
                                                      .stateResidencyData[j]
                                                      .totalStateEntryCount);
                            env->SetLongField(stateResidency, field_SR_lastEntryTimestampMs,
                                              results[i]
                                                      .stateResidencyData[j]
                                                      .lastEntryTimestampMs);
                            env->SetObjectArrayElement(stateResidencyArray, j, stateResidency);
                            env->DeleteLocalRef(stateResidency);
                        }
                        jobject stateResidencyResult = env->NewObject(class_SRR, method_SRR_init);
                        env->SetIntField(stateResidencyResult, field_SRR_powerEntityId,
                                         results[i].powerEntityId);
                        env->SetObjectField(stateResidencyResult, field_SRR_stateResidencyData,
                                            stateResidencyArray);
                        env->SetObjectArrayElement(stateResidencyResultArray, i,
                                                   stateResidencyResult);
                        env->DeleteLocalRef(stateResidencyResult);
                    }
                }
            });
    if (!checkResult(ret, __func__)) {
        return nullptr;
    }

    return stateResidencyResultArray;
}

static jobjectArray nativeGetEnergyMeterInfo(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeGetEnergyMeterInfo failed to connect to power.stats HAL");
        return nullptr;
    }

    jobjectArray channelInfoArray = nullptr;
    Return<void> ret = gPowerStatsHalV1_0_ptr->getRailInfo(
            [&env, &channelInfoArray](auto railInfo, auto status) {
                if (status != Status::SUCCESS) {
                    ALOGW("Error getting rail info");
                } else {
                    channelInfoArray = env->NewObjectArray(railInfo.size(), class_CI, nullptr);
                    for (int i = 0; i < railInfo.size(); i++) {
                        jstring channelName = env->NewStringUTF(railInfo[i].railName.c_str());
                        jobject channelInfo = env->NewObject(class_CI, method_CI_init);
                        env->SetIntField(channelInfo, field_CI_channelId, railInfo[i].index);
                        env->SetObjectField(channelInfo, field_CI_channelName, channelName);
                        env->SetObjectArrayElement(channelInfoArray, i, channelInfo);
                        env->DeleteLocalRef(channelName);
                        env->DeleteLocalRef(channelInfo);
                    }
                }
            });

    if (!checkResult(ret, __func__)) {
        ALOGE("getRailInfo failed");
        return nullptr;
    }

    return channelInfoArray;
}

static jobjectArray nativeReadEnergyMeters(JNIEnv *env, jclass clazz, jintArray channelIds) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeGetEnergy failed to connect to power.stats HAL");
    }

    size_t channelIdCount = env->GetArrayLength(channelIds);
    hidl_vec<uint32_t> channelIdVector(channelIdCount);

    jint *channelIdElements = env->GetIntArrayElements(channelIds, 0);
    for (int i = 0; i < channelIdCount; i++) {
        channelIdVector[i] = channelIdElements[i];
    }
    env->ReleaseIntArrayElements(channelIds, channelIdElements, 0);

    jobjectArray energyMeasurementArray = nullptr;
    Return<void> ret =
            gPowerStatsHalV1_0_ptr
                    ->getEnergyData(channelIdVector,
                                    [&env, &energyMeasurementArray](auto energyData, auto status) {
                                        if (status != Status::SUCCESS) {
                                            ALOGW("Error getting energy data");
                                        } else {
                                            energyMeasurementArray =
                                                    env->NewObjectArray(energyData.size(), class_EM,
                                                                        nullptr);
                                            for (int i = 0; i < energyData.size(); i++) {
                                                jobject energyMeasurement =
                                                        env->NewObject(class_EM, method_EM_init);
                                                env->SetIntField(energyMeasurement,
                                                                 field_EM_channelId,
                                                                 energyData[i].index);
                                                env->SetLongField(energyMeasurement,
                                                                  field_EM_timestampMs,
                                                                  energyData[i].timestamp);
                                                env->SetLongField(energyMeasurement,
                                                                  field_EM_durationMs, -1);
                                                env->SetLongField(energyMeasurement,
                                                                  field_EM_energyUWs,
                                                                  energyData[i].energy);
                                                env->SetObjectArrayElement(energyMeasurementArray,
                                                                           i, energyMeasurement);
                                                env->DeleteLocalRef(energyMeasurement);
                                            }
                                        }
                                    });

    if (!checkResult(ret, __func__)) {
        ALOGE("getEnergyData failed");
        return nullptr;
    }

    return energyMeasurementArray;
}

static jboolean nativeInit(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(gPowerStatsHalMutex);

    // ChannelInfo
    jclass temp = env->FindClass("android/hardware/power/stats/ChannelInfo");
    class_CI = (jclass)env->NewGlobalRef(temp);
    method_CI_init = env->GetMethodID(class_CI, "<init>", "()V");
    field_CI_channelId = env->GetFieldID(class_CI, "channelId", "I");
    field_CI_channelName = env->GetFieldID(class_CI, "channelName", "Ljava/lang/String;");

    // EnergyMeasurement
    temp = env->FindClass("android/hardware/power/stats/EnergyMeasurement");
    class_EM = (jclass)env->NewGlobalRef(temp);
    method_EM_init = env->GetMethodID(class_EM, "<init>", "()V");
    field_EM_channelId = env->GetFieldID(class_EM, "channelId", "I");
    field_EM_timestampMs = env->GetFieldID(class_EM, "timestampMs", "J");
    field_EM_durationMs = env->GetFieldID(class_EM, "durationMs", "J");
    field_EM_energyUWs = env->GetFieldID(class_EM, "energyUWs", "J");

    // StateInfo
    temp = env->FindClass("android/hardware/power/stats/StateInfo");
    class_SI = (jclass)env->NewGlobalRef(temp);
    method_SI_init = env->GetMethodID(class_SI, "<init>", "()V");
    field_SI_stateId = env->GetFieldID(class_SI, "stateId", "I");
    field_SI_stateName = env->GetFieldID(class_SI, "stateName", "Ljava/lang/String;");

    // PowerEntityInfo
    temp = env->FindClass("android/hardware/power/stats/PowerEntityInfo");
    class_PEI = (jclass)env->NewGlobalRef(temp);
    method_PEI_init = env->GetMethodID(class_PEI, "<init>", "()V");
    field_PEI_powerEntityId = env->GetFieldID(class_PEI, "powerEntityId", "I");
    field_PEI_powerEntityName = env->GetFieldID(class_PEI, "powerEntityName", "Ljava/lang/String;");
    field_PEI_states =
            env->GetFieldID(class_PEI, "states", "[Landroid/hardware/power/stats/StateInfo;");

    // StateResidency
    temp = env->FindClass("android/hardware/power/stats/StateResidency");
    class_SR = (jclass)env->NewGlobalRef(temp);
    method_SR_init = env->GetMethodID(class_SR, "<init>", "()V");
    field_SR_stateId = env->GetFieldID(class_SR, "stateId", "I");
    field_SR_totalTimeInStateMs = env->GetFieldID(class_SR, "totalTimeInStateMs", "J");
    field_SR_totalStateEntryCount = env->GetFieldID(class_SR, "totalStateEntryCount", "J");
    field_SR_lastEntryTimestampMs = env->GetFieldID(class_SR, "lastEntryTimestampMs", "J");

    // StateResidencyResult
    temp = env->FindClass("android/hardware/power/stats/StateResidencyResult");
    class_SRR = (jclass)env->NewGlobalRef(temp);
    method_SRR_init = env->GetMethodID(class_SRR, "<init>", "()V");
    field_SRR_powerEntityId = env->GetFieldID(class_SRR, "powerEntityId", "I");
    field_SRR_stateResidencyData =
            env->GetFieldID(class_SRR, "stateResidencyData",
                            "[Landroid/hardware/power/stats/StateResidency;");

    if (!connectToPowerStatsHal()) {
        ALOGE("nativeInit failed to connect to power.stats HAL");
        return false;
    }

    return true;
}

static const JNINativeMethod method_table[] = {
        {"nativeInit", "()Z", (void *)nativeInit},
        {"nativeGetPowerEntityInfo", "()[Landroid/hardware/power/stats/PowerEntityInfo;",
         (void *)nativeGetPowerEntityInfo},
        {"nativeGetStateResidency", "([I)[Landroid/hardware/power/stats/StateResidencyResult;",
         (void *)nativeGetStateResidency},
        {"nativeGetEnergyMeterInfo", "()[Landroid/hardware/power/stats/ChannelInfo;",
         (void *)nativeGetEnergyMeterInfo},
        {"nativeReadEnergyMeters", "([I)[Landroid/hardware/power/stats/EnergyMeasurement;",
         (void *)nativeReadEnergyMeters},
};

int register_android_server_PowerStatsService(JNIEnv *env) {
    return jniRegisterNativeMethods(env,
                                    "com/android/server/powerstats/"
                                    "PowerStatsHALWrapper$PowerStatsHAL10WrapperImpl",
                                    method_table, NELEM(method_table));
}

}; // namespace android
