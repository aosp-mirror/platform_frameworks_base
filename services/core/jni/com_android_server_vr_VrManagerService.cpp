/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "VrManagerService"

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>
#include <JNIHelp.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <hardware/hardware.h>
#include <hardware/vr.h>

namespace android {

static vr_module_t *gVrHardwareModule = NULL;


static void init_native(JNIEnv* /* env */, jclass /* clazz */) {
    if (gVrHardwareModule != NULL) {
        // This call path should never be hit.
        ALOGE("%s: May not initialize VR hardware module more than once!", __FUNCTION__);
        return;
    }

    int err = hw_get_module(VR_HARDWARE_MODULE_ID, (hw_module_t const**)&gVrHardwareModule);
    if (err) {
        ALOGW("%s: Could not open VR hardware module, error %s (%d).", __FUNCTION__,
                strerror(-err), err);
        return;
    }

    // Call init method if implemented.
    if (gVrHardwareModule->init) {
        gVrHardwareModule->init(gVrHardwareModule);
    }
}

static void setVrMode_native(JNIEnv* /* env */, jclass /* clazz */, jboolean enabled) {
    if (gVrHardwareModule == NULL) {
        // There is no VR hardware module implemented, do nothing.
        return;
    }

    // Call set_vr_mode method, this must be implemented if the HAL exists.
    gVrHardwareModule->set_vr_mode(gVrHardwareModule, static_cast<bool>(enabled));
}

static const JNINativeMethod method_table[] = {
    { "initializeNative", "()V", (void*)init_native },
    { "setVrModeNative", "(Z)V", (void*)setVrMode_native },
};

int register_android_server_vr_VrManagerService(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/android/server/vr/VrManagerService",
            method_table, NELEM(method_table));
}

}; // namespace android
