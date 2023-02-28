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

#include <aidl/android/hardware/thermal/IThermal.h>
#include <android/binder_manager.h>
#include <android/hardware/thermal/1.0/IThermal.h>
#include <math.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include "core_jni_helpers.h"
#include "jni.h"

namespace android {

using ::aidl::android::hardware::thermal::CoolingDevice;
using ::aidl::android::hardware::thermal::IThermal;
using ::aidl::android::hardware::thermal::Temperature;
using ::aidl::android::hardware::thermal::TemperatureThreshold;
using ::aidl::android::hardware::thermal::TemperatureType;
using ::aidl::android::hardware::thermal::ThrottlingSeverity;
using android::hidl::base::V1_0::IBase;
using hardware::hidl_death_recipient;
using hardware::hidl_vec;
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
static sp<hardware::thermal::V1_0::IThermal> gThermalHidlHal = nullptr;
static std::shared_ptr<IThermal> gThermalAidlHal = nullptr;

struct ThermalHidlHalDeathRecipient : virtual public hidl_death_recipient {
    // hidl_death_recipient interface
    virtual void serviceDied(uint64_t cookie, const wp<IBase> &who) override {
        std::lock_guard<std::mutex> lock(gThermalHalMutex);
        ALOGE("Thermal HAL just died");
        gThermalHidlHal = nullptr;
        getThermalHalLocked();
    }
};

static void onThermalAidlBinderDied(void *cookie) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    ALOGE("Thermal AIDL HAL just died");
    gThermalAidlHal = nullptr;
    getThermalHalLocked();
}

sp<ThermalHidlHalDeathRecipient> gThermalHidlHalDeathRecipient = nullptr;
ndk::ScopedAIBinder_DeathRecipient gThermalAidlDeathRecipient;

// ----------------------------------------------------------------------------

float finalizeTemperature(float temperature) {
    return isnan(temperature) ? gUndefinedTemperature : temperature;
}

// The caller must be holding gThermalHalMutex.
static void getThermalHalLocked() {
    if (gThermalAidlHal || gThermalHidlHal) {
        return;
    }
    const std::string thermalInstanceName = std::string(IThermal::descriptor) + "/default";
    if (AServiceManager_isDeclared(thermalInstanceName.c_str())) {
        auto binder = AServiceManager_waitForService(thermalInstanceName.c_str());
        auto thermalAidlService = IThermal::fromBinder(ndk::SpAIBinder(binder));
        if (thermalAidlService) {
            gThermalAidlHal = thermalAidlService;
            if (gThermalAidlDeathRecipient.get() == nullptr) {
                gThermalAidlDeathRecipient = ndk::ScopedAIBinder_DeathRecipient(
                        AIBinder_DeathRecipient_new(onThermalAidlBinderDied));
            }
            auto linked = AIBinder_linkToDeath(thermalAidlService->asBinder().get(),
                                               gThermalAidlDeathRecipient.get(), nullptr);
            if (linked != STATUS_OK) {
                ALOGW("Failed to link to death (AIDL): %d", linked);
                gThermalAidlHal = nullptr;
            }
        } else {
            ALOGE("Unable to get Thermal AIDL service");
        }
        return;
    }

    ALOGI("Thermal AIDL service is not declared, trying HIDL");
    gThermalHidlHal = hardware::thermal::V1_0::IThermal::getService();

    if (gThermalHidlHal == nullptr) {
        ALOGE("Unable to get Thermal service.");
    } else {
        if (gThermalHidlHalDeathRecipient == nullptr) {
            gThermalHidlHalDeathRecipient = new ThermalHidlHalDeathRecipient();
        }
        hardware::Return<bool> linked =
                gThermalHidlHal->linkToDeath(gThermalHidlHalDeathRecipient, 0x451F /* cookie */);
        if (!linked.isOk()) {
            ALOGE("Transaction error in linking to ThermalHAL death: %s",
                  linked.description().c_str());
            gThermalHidlHal = nullptr;
        } else if (!linked) {
            ALOGW("Unable to link to ThermalHal death notifications");
            gThermalHidlHal = nullptr;
        } else {
            ALOGD("Link to death notification successful");
        }
    }
}

static void nativeInit(JNIEnv* env, jobject obj) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
}

static jfloatArray getFanSpeedsAidl(JNIEnv *env) {
    std::vector<CoolingDevice> list;
    auto status = gThermalAidlHal->getCoolingDevices(&list);
    if (!status.isOk()) {
        ALOGE("getFanSpeeds failed status: %s", status.getMessage());
        return env->NewFloatArray(0);
    }
    float values[list.size()];
    for (size_t i = 0; i < list.size(); ++i) {
        values[i] = list[i].value;
    }
    jfloatArray fanSpeeds = env->NewFloatArray(list.size());
    env->SetFloatArrayRegion(fanSpeeds, 0, list.size(), values);
    return fanSpeeds;
}

static jfloatArray getFanSpeedsHidl(JNIEnv *env) {
    hidl_vec<hardware::thermal::V1_0::CoolingDevice> list;
    Return<void> ret = gThermalHidlHal->getCoolingDevices(
            [&list](ThermalStatus status,
                    hidl_vec<hardware::thermal::V1_0::CoolingDevice> devices) {
                if (status.code == ThermalStatusCode::SUCCESS) {
                    list = std::move(devices);
                } else {
                    ALOGE("Couldn't get fan speeds because of HAL error: %s",
                          status.debugMessage.c_str());
                }
            });

    if (!ret.isOk()) {
        ALOGE("getFanSpeeds failed status: %s", ret.description().c_str());
        return env->NewFloatArray(0);
    }
    float values[list.size()];
    for (size_t i = 0; i < list.size(); ++i) {
        values[i] = list[i].currentValue;
    }
    jfloatArray fanSpeeds = env->NewFloatArray(list.size());
    env->SetFloatArrayRegion(fanSpeeds, 0, list.size(), values);
    return fanSpeeds;
}

static jfloatArray nativeGetFanSpeeds(JNIEnv *env, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
    if (!gThermalHidlHal && !gThermalAidlHal) {
        ALOGE("Couldn't get fan speeds because of HAL error.");
        return env->NewFloatArray(0);
    }
    if (gThermalAidlHal) {
        return getFanSpeedsAidl(env);
    }
    return getFanSpeedsHidl(env);
}

static jfloatArray getDeviceTemperaturesAidl(JNIEnv *env, int type, int source) {
    jfloat *values;
    size_t length = 0;
    if (source == TEMPERATURE_CURRENT) {
        std::vector<Temperature> list;
        auto status =
                gThermalAidlHal->getTemperaturesWithType(static_cast<TemperatureType>(type), &list);

        if (!status.isOk()) {
            ALOGE("getDeviceTemperatures failed status: %s", status.getMessage());
            return env->NewFloatArray(0);
        }
        values = new jfloat[list.size()];
        for (const auto &temp : list) {
            if (static_cast<int>(temp.type) == type) {
                values[length++] = finalizeTemperature(temp.value);
            }
        }
    } else if (source == TEMPERATURE_THROTTLING_BELOW_VR_MIN) {
        values = new jfloat[1];
        values[length++] = gUndefinedTemperature;
    } else {
        std::vector<TemperatureThreshold> list;
        auto status =
                gThermalAidlHal->getTemperatureThresholdsWithType(static_cast<TemperatureType>(
                                                                          type),
                                                                  &list);

        if (!status.isOk()) {
            ALOGE("getDeviceTemperatures failed status: %s", status.getMessage());
            return env->NewFloatArray(0);
        }
        values = new jfloat[list.size()];
        for (auto &t : list) {
            if (static_cast<int>(t.type) == type) {
                switch (source) {
                    case TEMPERATURE_THROTTLING:
                        values[length++] =
                                finalizeTemperature(t.hotThrottlingThresholds[static_cast<int>(
                                        ThrottlingSeverity::SEVERE)]);
                        break;
                    case TEMPERATURE_SHUTDOWN:
                        values[length++] =
                                finalizeTemperature(t.hotThrottlingThresholds[static_cast<int>(
                                        ThrottlingSeverity::SHUTDOWN)]);
                        break;
                }
            }
        }
    }
    jfloatArray deviceTemps = env->NewFloatArray(length);
    env->SetFloatArrayRegion(deviceTemps, 0, length, values);
    return deviceTemps;
}

static jfloatArray getDeviceTemperaturesHidl(JNIEnv *env, int type, int source) {
    hidl_vec<hardware::thermal::V1_0::Temperature> list;
    Return<void> ret = gThermalHidlHal->getTemperatures(
            [&list](ThermalStatus status,
                    hidl_vec<hardware::thermal::V1_0::Temperature> temperatures) {
                if (status.code == ThermalStatusCode::SUCCESS) {
                    list = std::move(temperatures);
                } else {
                    ALOGE("Couldn't get temperatures because of HAL error: %s",
                          status.debugMessage.c_str());
                }
            });

    if (!ret.isOk()) {
        ALOGE("getDeviceTemperatures failed status: %s", ret.description().c_str());
        return env->NewFloatArray(0);
    }
    float values[list.size()];
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

static jfloatArray nativeGetDeviceTemperatures(JNIEnv *env, jclass /* clazz */, int type,
                                               int source) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
    if (!gThermalHidlHal && !gThermalAidlHal) {
        ALOGE("Couldn't get device temperatures because of HAL error.");
        return env->NewFloatArray(0);
    }
    if (gThermalAidlHal) {
        return getDeviceTemperaturesAidl(env, type, source);
    }
    return getDeviceTemperaturesHidl(env, type, source);
}

static jobjectArray nativeGetCpuUsages(JNIEnv *env, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    getThermalHalLocked();
    if (gThermalAidlHal) {
        ALOGW("getCpuUsages is not supported");
        return env->NewObjectArray(0, gCpuUsageInfoClassInfo.clazz, nullptr);
    }
    if (gThermalHidlHal == nullptr || !gCpuUsageInfoClassInfo.initMethod) {
        ALOGE("Couldn't get CPU usages because of HAL error.");
        return env->NewObjectArray(0, gCpuUsageInfoClassInfo.clazz, nullptr);
    }
    hidl_vec<hardware::thermal::V1_0::CpuUsage> list;
    Return<void> ret = gThermalHidlHal->getCpuUsages(
            [&list](ThermalStatus status, hidl_vec<hardware::thermal::V1_0::CpuUsage> cpuUsages) {
                if (status.code == ThermalStatusCode::SUCCESS) {
                    list = std::move(cpuUsages);
                } else {
                    ALOGE("Couldn't get CPU usages because of HAL error: %s",
                          status.debugMessage.c_str());
                }
            });

    if (!ret.isOk()) {
        ALOGE("getCpuUsages failed status: %s", ret.description().c_str());
        return env->NewObjectArray(0, gCpuUsageInfoClassInfo.clazz, nullptr);
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
