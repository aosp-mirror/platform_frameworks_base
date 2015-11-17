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

#define LOG_TAG "VibratorService"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/vibrator.h>

#include <stdio.h>

namespace android
{

static hw_module_t *gVibraModule = NULL;
static vibrator_device_t *gVibraDevice = NULL;

static void vibratorInit(JNIEnv /* env */, jobject /* clazz */)
{
    if (gVibraModule != NULL) {
        return;
    }

    int err = hw_get_module(VIBRATOR_HARDWARE_MODULE_ID, (hw_module_t const**)&gVibraModule);

    if (err) {
        ALOGE("Couldn't load %s module (%s)", VIBRATOR_HARDWARE_MODULE_ID, strerror(-err));
    } else {
        if (gVibraModule) {
            vibrator_open(gVibraModule, &gVibraDevice);
        }
    }
}

static jboolean vibratorExists(JNIEnv* /* env */, jobject /* clazz */)
{
    if (gVibraModule && gVibraDevice) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static void vibratorOn(JNIEnv* /* env */, jobject /* clazz */, jlong timeout_ms)
{
    if (gVibraDevice) {
        int err = gVibraDevice->vibrator_on(gVibraDevice, timeout_ms);
        if (err != 0) {
            ALOGE("The hw module failed in vibrator_on: %s", strerror(-err));
        }
    } else {
        ALOGW("Tried to vibrate but there is no vibrator device.");
    }
}

static void vibratorOff(JNIEnv* /* env */, jobject /* clazz */)
{
    if (gVibraDevice) {
        int err = gVibraDevice->vibrator_off(gVibraDevice);
        if (err != 0) {
            ALOGE("The hw module failed in vibrator_off(): %s", strerror(-err));
        }
    } else {
        ALOGW("Tried to stop vibrating but there is no vibrator device.");
    }
}

static const JNINativeMethod method_table[] = {
    { "vibratorExists", "()Z", (void*)vibratorExists },
    { "vibratorInit", "()V", (void*)vibratorInit },
    { "vibratorOn", "(J)V", (void*)vibratorOn },
    { "vibratorOff", "()V", (void*)vibratorOff }
};

int register_android_server_VibratorService(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/android/server/VibratorService",
            method_table, NELEM(method_table));
}

};
