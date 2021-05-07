/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "SensorService"

#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "core_jni_helpers.h"
#include "jni.h"

#include <cutils/properties.h>
#include <sensorservice/SensorService.h>
#include <utils/Log.h>
#include <utils/misc.h>

namespace android {

static void startNativeSensorService(JNIEnv* env, jclass clazz) {
    char propBuf[PROPERTY_VALUE_MAX];
    property_get("system_init.startsensorservice", propBuf, "1");
    if (strcmp(propBuf, "1") == 0) {
        SensorService::publish(false /* allowIsolated */,
                               IServiceManager::DUMP_FLAG_PRIORITY_CRITICAL);
    }
}

static const JNINativeMethod methods[] = {
        {"startNativeSensorService", "()V", (void*)startNativeSensorService},
};

int register_android_server_sensor_SensorService(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/sensors/SensorService", methods,
                                    NELEM(methods));
}

}; // namespace android
