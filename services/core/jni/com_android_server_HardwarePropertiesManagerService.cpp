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

#include "JNIHelp.h"
#include "jni.h"

#include <stdlib.h>

#include <hardware/thermal.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include "core_jni_helpers.h"

namespace android {

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

static struct thermal_module* gThermalModule;

// ----------------------------------------------------------------------------

static void nativeInit(JNIEnv* env, jobject obj) {
    status_t err = hw_get_module(THERMAL_HARDWARE_MODULE_ID, (hw_module_t const**)&gThermalModule);
    if (err) {
        ALOGE("Couldn't load %s module (%s)", THERMAL_HARDWARE_MODULE_ID, strerror(-err));
    }
}

static jfloatArray nativeGetFanSpeeds(JNIEnv *env, jclass /* clazz */) {
    if (gThermalModule && gThermalModule->getCoolingDevices) {
        ssize_t list_size = gThermalModule->getCoolingDevices(gThermalModule, nullptr, 0);

        if (list_size >= 0) {
            cooling_device_t *list = (cooling_device_t *)
                    malloc(list_size * sizeof(cooling_device_t));
            ssize_t size = gThermalModule->getCoolingDevices(gThermalModule, list, list_size);
            if (size >= 0) {
                if (list_size > size) {
                    list_size = size;
                }
                jfloat values[list_size];
                for (ssize_t i = 0; i < list_size; ++i) {
                    values[i] = list[i].current_value;
                }

                jfloatArray fanSpeeds = env->NewFloatArray(list_size);
                env->SetFloatArrayRegion(fanSpeeds, 0, list_size, values);
                free(list);
                return fanSpeeds;
            }

            free(list);
        }

        ALOGE("Cloudn't get fan speeds because of HAL error");
    }
    return env->NewFloatArray(0);
}

static jfloatArray nativeGetDeviceTemperatures(JNIEnv *env, jclass /* clazz */, int type,
                                               int source) {
    if (gThermalModule && gThermalModule->getTemperatures) {
        ssize_t list_size = gThermalModule->getTemperatures(gThermalModule, nullptr, 0);
        if (list_size >= 0) {
            temperature_t *list = (temperature_t *) malloc(list_size * sizeof(temperature_t));
            ssize_t size = gThermalModule->getTemperatures(gThermalModule, list, list_size);
            if (size >= 0) {
                if (list_size > size) {
                    list_size = size;
                }

                jfloat values[list_size];
                size_t length = 0;

                for (ssize_t i = 0; i < list_size; ++i) {
                    if (list[i].type == type) {
                        switch (source) {
                            case TEMPERATURE_CURRENT:
                                if (list[i].current_value == UNKNOWN_TEMPERATURE) {
                                    values[length++] = gUndefinedTemperature;
                                } else {
                                    values[length++] = list[i].current_value;
                                }
                                break;
                            case TEMPERATURE_THROTTLING:
                                if (list[i].throttling_threshold == UNKNOWN_TEMPERATURE) {
                                    values[length++] = gUndefinedTemperature;
                                } else {
                                    values[length++] = list[i].throttling_threshold;
                                }
                                break;
                            case TEMPERATURE_SHUTDOWN:
                                if (list[i].shutdown_threshold == UNKNOWN_TEMPERATURE) {
                                    values[length++] = gUndefinedTemperature;
                                } else {
                                    values[length++] = list[i].shutdown_threshold;
                                }
                                break;
                            case TEMPERATURE_THROTTLING_BELOW_VR_MIN:
                                if (list[i].vr_throttling_threshold == UNKNOWN_TEMPERATURE) {
                                    values[length++] = gUndefinedTemperature;
                                } else {
                                    values[length++] = list[i].vr_throttling_threshold;
                                }
                                break;
                        }
                    }
                }
                jfloatArray deviceTemps = env->NewFloatArray(length);
                env->SetFloatArrayRegion(deviceTemps, 0, length, values);
                free(list);
                return deviceTemps;
            }
            free(list);
        }
        ALOGE("Couldn't get device temperatures because of HAL error");
    }
    return env->NewFloatArray(0);
}

static jobjectArray nativeGetCpuUsages(JNIEnv *env, jclass /* clazz */) {
    if (gThermalModule && gThermalModule->getCpuUsages
            && gCpuUsageInfoClassInfo.initMethod) {
        ssize_t size = gThermalModule->getCpuUsages(gThermalModule, nullptr);
        if (size >= 0) {
            cpu_usage_t *list = (cpu_usage_t *) malloc(size * sizeof(cpu_usage_t));
            size = gThermalModule->getCpuUsages(gThermalModule, list);
            if (size >= 0) {
                jobjectArray cpuUsages = env->NewObjectArray(size, gCpuUsageInfoClassInfo.clazz,
                        nullptr);
                for (ssize_t i = 0; i < size; ++i) {
                    if (list[i].is_online) {
                        jobject cpuUsage = env->NewObject(gCpuUsageInfoClassInfo.clazz,
                                gCpuUsageInfoClassInfo.initMethod, list[i].active, list[i].total);
                        env->SetObjectArrayElement(cpuUsages, i, cpuUsage);
                    }
                }
                free(list);
                return cpuUsages;
            }
            free(list);
        }
        ALOGE("Couldn't get CPU usages because of HAL error");
    }
    return env->NewObjectArray(0, gCpuUsageInfoClassInfo.clazz, nullptr);
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
    gThermalModule = nullptr;
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
