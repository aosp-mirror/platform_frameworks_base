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

#define LOG_TAG "HardwarePropertiesManager-JNI"

#include "JNIHelp.h"
#include "jni.h"

#include <stdlib.h>

#include <hardware/hardware_properties.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include <hardware_properties/HardwarePropertiesManager.h>

#include "core_jni_helpers.h"

namespace android {

// ---------------------------------------------------------------------------

static struct {
    jclass clazz;
    jmethodID initMethod;
} gCpuUsageInfoClassInfo;

static struct hardware_properties_module* gHardwarePropertiesModule;

// ----------------------------------------------------------------------------

static void nativeInit(JNIEnv* env, jobject obj) {
    status_t err = hw_get_module(HARDWARE_PROPERTIES_HARDWARE_MODULE_ID,
            (hw_module_t const**)&gHardwarePropertiesModule);
    if (err) {
        ALOGE("Couldn't load %s module (%s)", HARDWARE_PROPERTIES_HARDWARE_MODULE_ID,
              strerror(-err));
    }
}

static jfloatArray nativeGetFanSpeeds(JNIEnv *env, jclass /* clazz */) {
    if (gHardwarePropertiesModule && gHardwarePropertiesModule->getFanSpeeds) {
        float *speeds = nullptr;
        ssize_t size = gHardwarePropertiesModule->getFanSpeeds(gHardwarePropertiesModule, &speeds);

        if (speeds && size > 0) {
            jfloatArray fanSpeeds = env->NewFloatArray(size);
            env->SetFloatArrayRegion(fanSpeeds, 0, size, speeds);
            free(speeds);
            return fanSpeeds;
        }

        if (size < 0) {
            ALOGE("Cloudn't get fan speeds because of HAL error");
        }
    }
    return env->NewFloatArray(0);
}

static jfloatArray nativeGetDeviceTemperatures(JNIEnv *env, jclass /* clazz */, int type) {
    if (gHardwarePropertiesModule) {
        ssize_t size = 0;
        float *temps = nullptr;
        switch (type) {
        case DEVICE_TEMPERATURE_CPU:
            if (gHardwarePropertiesModule->getCpuTemperatures) {
                size = gHardwarePropertiesModule->getCpuTemperatures(gHardwarePropertiesModule,
                                                                     &temps);
            }
            break;
        case DEVICE_TEMPERATURE_GPU:
            if (gHardwarePropertiesModule->getGpuTemperatures) {
                size = gHardwarePropertiesModule->getGpuTemperatures(gHardwarePropertiesModule,
                                                                    &temps);
            }
            break;
        case DEVICE_TEMPERATURE_BATTERY:
            if (gHardwarePropertiesModule->getBatteryTemperatures) {
                size = gHardwarePropertiesModule->getBatteryTemperatures(gHardwarePropertiesModule,
                                                                        &temps);
            }
            break;
        }
        if (temps && size > 0) {
            jfloatArray deviceTemps = env->NewFloatArray(size);
            env->SetFloatArrayRegion(deviceTemps, 0, size, temps);
            free(temps);
            return deviceTemps;
        }
        if (size < 0) {
            ALOGE("Couldn't get device temperatures type=%d because of HAL error", type);
        }
    }
    return env->NewFloatArray(0);
}

static jobjectArray nativeGetCpuUsages(JNIEnv *env, jclass /* clazz */) {
    if (gHardwarePropertiesModule && gHardwarePropertiesModule->getCpuUsages
        && gCpuUsageInfoClassInfo.initMethod) {
        int64_t *active_times = nullptr;
        int64_t *total_times = nullptr;
        ssize_t size = gHardwarePropertiesModule->getCpuUsages(gHardwarePropertiesModule,
                                                               &active_times, &total_times);
        if (active_times && total_times && size > 0) {
            jobjectArray cpuUsages = env->NewObjectArray(size, gCpuUsageInfoClassInfo.clazz,
                                                         nullptr);
            for (ssize_t i = 0; i < size; ++i) {
                jobject cpuUsage = env->NewObject(gCpuUsageInfoClassInfo.clazz,
                                                  gCpuUsageInfoClassInfo.initMethod,
                                                  active_times[i], total_times[i]);
                env->SetObjectArrayElement(cpuUsages, i, cpuUsage);
            }
            free(active_times);
            free(total_times);
            return cpuUsages;
        }

        if (size < 0) {
            ALOGE("Couldn't get CPU usages because of HAL error");
        }
    }
    return env->NewObjectArray(0, gCpuUsageInfoClassInfo.clazz, nullptr);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gHardwarePropertiesManagerMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()V",
            (void*) nativeInit },
    { "nativeGetFanSpeeds", "()[F",
            (void*) nativeGetFanSpeeds },
    { "nativeGetDeviceTemperatures", "(I)[F",
            (void*) nativeGetDeviceTemperatures },
    { "nativeGetCpuUsages", "()[Landroid/os/CpuUsageInfo;",
            (void*) nativeGetCpuUsages }
};

int register_android_os_HardwarePropertiesManager(JNIEnv* env) {
    gHardwarePropertiesModule = nullptr;
    int res = jniRegisterNativeMethods(env, "android/os/HardwarePropertiesManager",
                                       gHardwarePropertiesManagerMethods,
                                       NELEM(gHardwarePropertiesManagerMethods));
    jclass clazz = env->FindClass("android/os/CpuUsageInfo");
    gCpuUsageInfoClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gCpuUsageInfoClassInfo.initMethod = GetMethodIDOrDie(env, gCpuUsageInfoClassInfo.clazz,
                                                         "<init>", "(JJ)V");
    return res;
}

} /* namespace android */
