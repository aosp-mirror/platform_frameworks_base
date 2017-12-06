/*
 * Copyright (C) 2015 The Android Open Source Project
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

#define LOG_TAG "HardwarePropertiesManagerService-JNI"

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include <math.h>
#include <stdlib.h>

#include <android/hardware/thermal/1.0/IThermal.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include "core_jni_helpers.h"

namespace android {

using android::hidl::base::V1_0::IBase;
using hardware::hidl_death_recipient;
using hardware::hidl_vec;
using hardware::thermal::V1_0::CoolingDevice;
using hardware::thermal::V1_0::CpuUsage;
using hardware::thermal::V1_0::IThermal;
using hardware::thermal::V1_0::Temperature;
using hardware::thermal::V1_0::ThermalStatus;
using hardware::thermal::V1_0::ThermalStatusCode;
template<typename T>
using Return = hardware::Return<T>;

// ---------------------------------------------------------------------------

// These values must be kept in sync with the temperature source constants in
// HardwarePropertiesManager.java
enum {
    TEMPERATURE_CURRENT = 0,
    TEMPERATURE_THROTTLING = 1,
    TEMPERATURE_SHUTDOWN = 2,
    TEMPERATURE_THROTTLING_BELOW_VR_MIN = 3
};

static struct {
    jclass clazz;
    jmethodID initMethod;
} gCpuUsageInfoClassInfo;

jfloat gUndefinedTemperature;

static void getThermalHalLocked();
static std::mutex gThermalHalMutex;
static sp<IThermal> gThermalHal = nullptr;

// struct ThermalHalDeathRecipient;
struct ThermalHalDeathRecipient : virtual public hidl_death_recipient {
      // hidl_death_recipient interface
      virtual void serviceDied(uint64_t cookie, const wp<IBase>& who) override {
          std::lock_guard<std::mutex> lock(gThermalHalMutex);
          ALOGE("ThermalHAL just died");
          gThermalHal = nullptr;
          getThermalHalLocked();
      }
};

sp<ThermalHalDeathRecipient> gThermalHalDeathRecipient = nullptr;

// ----------------------------------------------------------------------------

float finalizeTemperature(float temperature) {
    return isnan(temperature) ? gUndefinedTemperature : temperature;
}

// The caller must be holding gThermalHalMutex.
static void getThermalHalLocked() {
    if (gThermalHal != nullptr) {
        return;
    }

    gThermalHal = IThermal::getService();

    if (gThermalHal == nullptr) {
        ALOGE("Unable to get Thermal service.");
    } else {
        if (gThermalHalDeathRecipient == nullptr) {
            gThermalHalDeathRecipient = new ThermalHalDeathRecipient();
        }
        hardware::Return<bool> linked = gThermalHal->linkToDeath(
            gThermalHalDeathRecipient, 0x451F /* cookie */);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to ThermalHAL death: %s",
            linked.description().c_str());
            gThermalHal = nullptr;
        } else if (!linked) {
            ALOGW("Unable to link to ThermalHal death notifications");
            gThermalHal = nullptr;
        } else {
            ALOGD("Link to death notification successful");
        }
    }
}

static void nativeInit(JNIEnv* env, jobject obj) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
}

static jfloatArray nativeGetFanSpeeds(JNIEnv *env, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
    if (gThermalHal == nullptr) {
        ALOGE("Couldn't get fan speeds because of HAL error.");
        return env->NewFloatArray(0);
    }

    hidl_vec<CoolingDevice> list;
    Return<void> ret = gThermalHal->getCoolingDevices(
            [&list](ThermalStatus status, hidl_vec<CoolingDevice> devices) {
                if (status.code == ThermalStatusCode::SUCCESS) {
                    list = std::move(devices);
                } else {
                    ALOGE("Couldn't get fan speeds because of HAL error: %s",
                          status.debugMessage.c_str());
                }
            });

    if (!ret.isOk()) {
        ALOGE("getCoolingDevices failed status: %s", ret.description().c_str());
    }

    float values[list.size()];
    for (size_t i = 0; i < list.size(); ++i) {
        values[i] = list[i].currentValue;
    }
    jfloatArray fanSpeeds = env->NewFloatArray(list.size());
    env->SetFloatArrayRegion(fanSpeeds, 0, list.size(), values);
    return fanSpeeds;
}

static jfloatArray nativeGetDeviceTemperatures(JNIEnv *env, jclass /* clazz */, int type,
                                               int source) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
    if (gThermalHal == nullptr) {
        ALOGE("Couldn't get device temperatures because of HAL error.");
        return env->NewFloatArray(0);
    }
    hidl_vec<Temperature> list;
    Return<void> ret = gThermalHal->getTemperatures(
            [&list](ThermalStatus status, hidl_vec<Temperature> temperatures) {
                if (status.code == ThermalStatusCode::SUCCESS) {
                    list = std::move(temperatures);
                } else {
                    ALOGE("Couldn't get temperatures because of HAL error: %s",
                          status.debugMessage.c_str());
                }
            });

    if (!ret.isOk()) {
        ALOGE("getDeviceTemperatures failed status: %s", ret.description().c_str());
    }

    jfloat values[list.size()];
    size_t length = 0;
    for (size_t i = 0; i < list.size(); ++i) {
        if (static_cast<int>(list[i].type) == type) {
            switch (source) {
                case TEMPERATURE_CURRENT:
                    values[length++] = finalizeTemperature(list[i].currentValue);
                    break;
                case TEMPERATURE_THROTTLING:
                    values[length++] = finalizeTemperature(list[i].throttlingThreshold);
                    break;
                case TEMPERATURE_SHUTDOWN:
                    values[length++] = finalizeTemperature(list[i].shutdownThreshold);
                    break;
                case TEMPERATURE_THROTTLING_BELOW_VR_MIN:
                    values[length++] = finalizeTemperature(list[i].vrThrottlingThreshold);
                    break;
            }
        }
    }
    jfloatArray deviceTemps = env->NewFloatArray(length);
    env->SetFloatArrayRegion(deviceTemps, 0, length, values);
    return deviceTemps;
}

static jobjectArray nativeGetCpuUsages(JNIEnv *env, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
    if (gThermalHal == nullptr || !gCpuUsageInfoClassInfo.initMethod) {
        ALOGE("Couldn't get CPU usages because of HAL error.");
        return env->NewObjectArray(0, gCpuUsageInfoClassInfo.clazz, nullptr);
    }
    hidl_vec<CpuUsage> list;
    Return<void> ret = gThermalHal->getCpuUsages(
            [&list](ThermalStatus status, hidl_vec<CpuUsage> cpuUsages) {
                if (status.code == ThermalStatusCode::SUCCESS) {
                    list = std::move(cpuUsages);
                } else {
                    ALOGE("Couldn't get CPU usages because of HAL error: %s",
                          status.debugMessage.c_str());
                }
            });

    if (!ret.isOk()) {
        ALOGE("getCpuUsages failed status: %s", ret.description().c_str());
    }

    jobjectArray cpuUsages = env->NewObjectArray(list.size(), gCpuUsageInfoClassInfo.clazz,
                                                 nullptr);
    for (size_t i = 0; i < list.size(); ++i) {
        if (list[i].isOnline) {
            jobject cpuUsage = env->NewObject(gCpuUsageInfoClassInfo.clazz,
                                              gCpuUsageInfoClassInfo.initMethod,
                                              list[i].active,
                                              list[i].total);
            env->SetObjectArrayElement(cpuUsages, i, cpuUsage);
        }
    }
    return cpuUsages;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gHardwarePropertiesManagerServiceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()V",
            (void*) nativeInit },
    { "nativeGetFanSpeeds", "()[F",
            (void*) nativeGetFanSpeeds },
    { "nativeGetDeviceTemperatures", "(II)[F",
            (void*) nativeGetDeviceTemperatures },
    { "nativeGetCpuUsages", "()[Landroid/os/CpuUsageInfo;",
            (void*) nativeGetCpuUsages }
};

int register_android_server_HardwarePropertiesManagerService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/HardwarePropertiesManagerService",
                                       gHardwarePropertiesManagerServiceMethods,
                                       NELEM(gHardwarePropertiesManagerServiceMethods));
    jclass clazz = env->FindClass("android/os/CpuUsageInfo");
    gCpuUsageInfoClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gCpuUsageInfoClassInfo.initMethod = GetMethodIDOrDie(env, gCpuUsageInfoClassInfo.clazz,
                                                         "<init>", "(JJ)V");

    clazz = env->FindClass("android/os/HardwarePropertiesManager");
    jfieldID undefined_temperature_field = GetStaticFieldIDOrDie(env, clazz,
                                                                 "UNDEFINED_TEMPERATURE", "F");
    gUndefinedTemperature = env->GetStaticFloatField(clazz, undefined_temperature_field);

    return res;
}

} /* namespace android */
