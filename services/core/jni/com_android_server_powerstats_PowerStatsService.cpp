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

// Channel
static jclass class_C;
static jmethodID method_C_init;
static jfieldID field_C_id;
static jfieldID field_C_name;
static jfieldID field_C_subsystem;

// EnergyMeasurement
static jclass class_EM;
static jmethodID method_EM_init;
static jfieldID field_EM_id;
static jfieldID field_EM_timestampMs;
static jfieldID field_EM_durationMs;
static jfieldID field_EM_energyUWs;

// State
static jclass class_S;
static jmethodID method_S_init;
static jfieldID field_S_id;
static jfieldID field_S_name;

// PowerEntity
static jclass class_PE;
static jmethodID method_PE_init;
static jfieldID field_PE_id;
static jfieldID field_PE_name;
static jfieldID field_PE_states;

// StateResidency
static jclass class_SR;
static jmethodID method_SR_init;
static jfieldID field_SR_id;
static jfieldID field_SR_totalTimeInStateMs;
static jfieldID field_SR_totalStateEntryCount;
static jfieldID field_SR_lastEntryTimestampMs;

// StateResidencyResult
static jclass class_SRR;
static jmethodID method_SRR_init;
static jfieldID field_SRR_id;
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

    jobjectArray powerEntityArray = nullptr;
    Return<void> ret = gPowerStatsHalV1_0_ptr->getPowerEntityInfo(
            [&env, &powerEntityArray](auto infos, auto status) {
                if (status != Status::SUCCESS) {
                    ALOGE("Error getting power entity info");
                } else {
                    powerEntityArray = env->NewObjectArray(infos.size(), class_PE, nullptr);
                    for (int i = 0; i < infos.size(); i++) {
                        jstring name = env->NewStringUTF(infos[i].powerEntityName.c_str());
                        jobject powerEntity = env->NewObject(class_PE, method_PE_init);
                        env->SetIntField(powerEntity, field_PE_id, infos[i].powerEntityId);
                        env->SetObjectField(powerEntity, field_PE_name, name);
                        env->SetObjectArrayElement(powerEntityArray, i, powerEntity);
                        env->DeleteLocalRef(name);
                        env->DeleteLocalRef(powerEntity);
                    }
                }
            });
    if (!checkResult(ret, __func__)) {
        return nullptr;
    }

    ret = gPowerStatsHalV1_0_ptr
                  ->getPowerEntityStateInfo({}, [&env, &powerEntityArray](auto infos, auto status) {
                      if (status != Status::SUCCESS) {
                          ALOGE("Error getting power entity state info");
                      } else {
                          for (int i = 0; i < infos.size(); i++) {
                              jobjectArray stateArray =
                                      env->NewObjectArray(infos[i].states.size(), class_S, nullptr);
                              for (int j = 0; j < infos[i].states.size(); j++) {
                                  jstring name = env->NewStringUTF(
                                          infos[i].states[j].powerEntityStateName.c_str());
                                  jobject state = env->NewObject(class_S, method_S_init);
                                  env->SetIntField(state, field_S_id,
                                                   infos[i].states[j].powerEntityStateId);
                                  env->SetObjectField(state, field_S_name, name);
                                  env->SetObjectArrayElement(stateArray, j, state);
                                  env->DeleteLocalRef(name);
                                  env->DeleteLocalRef(state);
                              }

                              for (int j = 0; j < env->GetArrayLength(powerEntityArray); j++) {
                                  jobject powerEntity =
                                          env->GetObjectArrayElement(powerEntityArray, j);
                                  if (env->GetIntField(powerEntity, field_PE_id) ==
                                      infos[i].powerEntityId) {
                                      env->SetObjectField(powerEntity, field_PE_states, stateArray);
                                      env->SetObjectArrayElement(powerEntityArray, j, powerEntity);
                                      break;
                                  }
                              }
                          }
                      }
                  });
    if (!checkResult(ret, __func__)) {
        return nullptr;
    }

    return powerEntityArray;
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
                stateResidencyResultArray = env->NewObjectArray(results.size(), class_SRR, nullptr);
                for (int i = 0; i < results.size(); i++) {
                    jobjectArray stateResidencyArray =
                            env->NewObjectArray(results[i].stateResidencyData.size(), class_SR,
                                                nullptr);
                    for (int j = 0; j < results[i].stateResidencyData.size(); j++) {
                        jobject stateResidency = env->NewObject(class_SR, method_SR_init);
                        env->SetIntField(stateResidency, field_SR_id,
                                         results[i].stateResidencyData[j].powerEntityStateId);
                        env->SetLongField(stateResidency, field_SR_totalTimeInStateMs,
                                          results[i].stateResidencyData[j].totalTimeInStateMs);
                        env->SetLongField(stateResidency, field_SR_totalStateEntryCount,
                                          results[i].stateResidencyData[j].totalStateEntryCount);
                        env->SetLongField(stateResidency, field_SR_lastEntryTimestampMs,
                                          results[i].stateResidencyData[j].lastEntryTimestampMs);
                        env->SetObjectArrayElement(stateResidencyArray, j, stateResidency);
                        env->DeleteLocalRef(stateResidency);
                    }
                    jobject stateResidencyResult = env->NewObject(class_SRR, method_SRR_init);
                    env->SetIntField(stateResidencyResult, field_SRR_id, results[i].powerEntityId);
                    env->SetObjectField(stateResidencyResult, field_SRR_stateResidencyData,
                                        stateResidencyArray);
                    env->SetObjectArrayElement(stateResidencyResultArray, i, stateResidencyResult);
                    env->DeleteLocalRef(stateResidencyResult);
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

    jobjectArray channelArray = nullptr;
    Return<void> ret =
            gPowerStatsHalV1_0_ptr->getRailInfo([&env, &channelArray](auto railInfo, auto status) {
                if (status != Status::SUCCESS) {
                    ALOGW("Error getting rail info");
                } else {
                    channelArray = env->NewObjectArray(railInfo.size(), class_C, nullptr);
                    for (int i = 0; i < railInfo.size(); i++) {
                        jstring name = env->NewStringUTF(railInfo[i].railName.c_str());
                        jstring subsystem = env->NewStringUTF(railInfo[i].subsysName.c_str());
                        jobject channel = env->NewObject(class_C, method_C_init);
                        env->SetIntField(channel, field_C_id, railInfo[i].index);
                        env->SetObjectField(channel, field_C_name, name);
                        env->SetObjectField(channel, field_C_subsystem, subsystem);
                        env->SetObjectArrayElement(channelArray, i, channel);
                        env->DeleteLocalRef(name);
                        env->DeleteLocalRef(subsystem);
                        env->DeleteLocalRef(channel);
                    }
                }
            });

    if (!checkResult(ret, __func__)) {
        ALOGE("getRailInfo failed");
        return nullptr;
    }

    return channelArray;
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
                                        energyMeasurementArray =
                                                env->NewObjectArray(energyData.size(), class_EM,
                                                                    nullptr);
                                        for (int i = 0; i < energyData.size(); i++) {
                                            jobject energyMeasurement =
                                                    env->NewObject(class_EM, method_EM_init);
                                            env->SetIntField(energyMeasurement, field_EM_id,
                                                             energyData[i].index);
                                            env->SetLongField(energyMeasurement,
                                                              field_EM_timestampMs,
                                                              energyData[i].timestamp);
                                            env->SetLongField(energyMeasurement,
                                                              field_EM_durationMs,
                                                              energyData[i].timestamp);
                                            env->SetLongField(energyMeasurement, field_EM_energyUWs,
                                                              energyData[i].energy);
                                            env->SetObjectArrayElement(energyMeasurementArray, i,
                                                                       energyMeasurement);
                                            env->DeleteLocalRef(energyMeasurement);
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

    // Channel
    jclass temp = env->FindClass("android/hardware/power/stats/Channel");
    class_C = (jclass)env->NewGlobalRef(temp);
    method_C_init = env->GetMethodID(class_C, "<init>", "()V");
    field_C_id = env->GetFieldID(class_C, "id", "I");
    field_C_name = env->GetFieldID(class_C, "name", "Ljava/lang/String;");
    field_C_subsystem = env->GetFieldID(class_C, "subsystem", "Ljava/lang/String;");

    // EnergyMeasurement
    temp = env->FindClass("android/hardware/power/stats/EnergyMeasurement");
    class_EM = (jclass)env->NewGlobalRef(temp);
    method_EM_init = env->GetMethodID(class_EM, "<init>", "()V");
    field_EM_id = env->GetFieldID(class_EM, "id", "I");
    field_EM_timestampMs = env->GetFieldID(class_EM, "timestampMs", "J");
    field_EM_durationMs = env->GetFieldID(class_EM, "durationMs", "J");
    field_EM_energyUWs = env->GetFieldID(class_EM, "energyUWs", "J");

    // State
    temp = env->FindClass("android/hardware/power/stats/State");
    class_S = (jclass)env->NewGlobalRef(temp);
    method_S_init = env->GetMethodID(class_S, "<init>", "()V");
    field_S_id = env->GetFieldID(class_S, "id", "I");
    field_S_name = env->GetFieldID(class_S, "name", "Ljava/lang/String;");

    // PowerEntity
    temp = env->FindClass("android/hardware/power/stats/PowerEntity");
    class_PE = (jclass)env->NewGlobalRef(temp);
    method_PE_init = env->GetMethodID(class_PE, "<init>", "()V");
    field_PE_id = env->GetFieldID(class_PE, "id", "I");
    field_PE_name = env->GetFieldID(class_PE, "name", "Ljava/lang/String;");
    field_PE_states = env->GetFieldID(class_PE, "states", "[Landroid/hardware/power/stats/State;");

    // StateResidency
    temp = env->FindClass("android/hardware/power/stats/StateResidency");
    class_SR = (jclass)env->NewGlobalRef(temp);
    method_SR_init = env->GetMethodID(class_SR, "<init>", "()V");
    field_SR_id = env->GetFieldID(class_SR, "id", "I");
    field_SR_totalTimeInStateMs = env->GetFieldID(class_SR, "totalTimeInStateMs", "J");
    field_SR_totalStateEntryCount = env->GetFieldID(class_SR, "totalStateEntryCount", "J");
    field_SR_lastEntryTimestampMs = env->GetFieldID(class_SR, "lastEntryTimestampMs", "J");

    // StateResidencyResult
    temp = env->FindClass("android/hardware/power/stats/StateResidencyResult");
    class_SRR = (jclass)env->NewGlobalRef(temp);
    method_SRR_init = env->GetMethodID(class_SRR, "<init>", "()V");
    field_SRR_id = env->GetFieldID(class_SRR, "id", "I");
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
        {"nativeGetPowerEntityInfo", "()[Landroid/hardware/power/stats/PowerEntity;",
         (void *)nativeGetPowerEntityInfo},
        {"nativeGetStateResidency", "([I)[Landroid/hardware/power/stats/StateResidencyResult;",
         (void *)nativeGetStateResidency},
        {"nativeGetEnergyMeterInfo", "()[Landroid/hardware/power/stats/Channel;",
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
