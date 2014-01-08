/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "LightsService"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/hardware.h>
#include <hardware/lights.h>

#include <stdio.h>

namespace android
{

// These values must correspond with the LIGHT_ID constants in
// LightsService.java
enum {
    LIGHT_INDEX_BACKLIGHT = 0,
    LIGHT_INDEX_KEYBOARD = 1,
    LIGHT_INDEX_BUTTONS = 2,
    LIGHT_INDEX_BATTERY = 3,
    LIGHT_INDEX_NOTIFICATIONS = 4,
    LIGHT_INDEX_ATTENTION = 5,
    LIGHT_INDEX_BLUETOOTH = 6,
    LIGHT_INDEX_WIFI = 7,
    LIGHT_COUNT
};

struct Devices {
    light_device_t* lights[LIGHT_COUNT];
};

static light_device_t* get_device(hw_module_t* module, char const* name)
{
    int err;
    hw_device_t* device;
    err = module->methods->open(module, name, &device);
    if (err == 0) {
        return (light_device_t*)device;
    } else {
        return NULL;
    }
}

static jlong init_native(JNIEnv *env, jobject clazz)
{
    int err;
    hw_module_t* module;
    Devices* devices;
    
    devices = (Devices*)malloc(sizeof(Devices));

    err = hw_get_module(LIGHTS_HARDWARE_MODULE_ID, (hw_module_t const**)&module);
    if (err == 0) {
        devices->lights[LIGHT_INDEX_BACKLIGHT]
                = get_device(module, LIGHT_ID_BACKLIGHT);
        devices->lights[LIGHT_INDEX_KEYBOARD]
                = get_device(module, LIGHT_ID_KEYBOARD);
        devices->lights[LIGHT_INDEX_BUTTONS]
                = get_device(module, LIGHT_ID_BUTTONS);
        devices->lights[LIGHT_INDEX_BATTERY]
                = get_device(module, LIGHT_ID_BATTERY);
        devices->lights[LIGHT_INDEX_NOTIFICATIONS]
                = get_device(module, LIGHT_ID_NOTIFICATIONS);
        devices->lights[LIGHT_INDEX_ATTENTION]
                = get_device(module, LIGHT_ID_ATTENTION);
        devices->lights[LIGHT_INDEX_BLUETOOTH]
                = get_device(module, LIGHT_ID_BLUETOOTH);
        devices->lights[LIGHT_INDEX_WIFI]
                = get_device(module, LIGHT_ID_WIFI);
    } else {
        memset(devices, 0, sizeof(Devices));
    }

    return (jlong)devices;
}

static void finalize_native(JNIEnv *env, jobject clazz, jlong ptr)
{
    Devices* devices = (Devices*)ptr;
    if (devices == NULL) {
        return;
    }

    free(devices);
}

static void setLight_native(JNIEnv *env, jobject clazz, jlong ptr,
        jint light, jint colorARGB, jint flashMode, jint onMS, jint offMS, jint brightnessMode)
{
    Devices* devices = (Devices*)ptr;
    light_state_t state;

    if (light < 0 || light >= LIGHT_COUNT || devices->lights[light] == NULL) {
        return ;
    }

    memset(&state, 0, sizeof(light_state_t));
    state.color = colorARGB;
    state.flashMode = flashMode;
    state.flashOnMS = onMS;
    state.flashOffMS = offMS;
    state.brightnessMode = brightnessMode;

    {
        ALOGD_IF_SLOW(50, "Excessive delay setting light");
        devices->lights[light]->set_light(devices->lights[light], &state);
    }
}

static JNINativeMethod method_table[] = {
    { "init_native", "()J", (void*)init_native },
    { "finalize_native", "(J)V", (void*)finalize_native },
    { "setLight_native", "(JIIIIII)V", (void*)setLight_native },
};

int register_android_server_LightsService(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/android/server/lights/LightsService",
            method_table, NELEM(method_table));
}

};
