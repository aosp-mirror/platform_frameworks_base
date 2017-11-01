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
#include <nativehelper/JNIHelp.h>

#include <android/hardware/vr/1.0/IVr.h>
#include <utils/Errors.h>
#include <utils/Log.h>

namespace android {

using ::android::hardware::vr::V1_0::IVr;

static sp<IVr> gVr;

static void init_native(JNIEnv* /* env */, jclass /* clazz */) {
    // TODO(b/31632518)
    if (gVr != nullptr) {
        // This call path should never be hit.
        ALOGE("%s: May not initialize IVr interface module more than once!", __FUNCTION__);
        return;
    }

    gVr = IVr::getService();
    if (gVr == nullptr) {
        ALOGW("%s: Could not open IVr interface", __FUNCTION__);
        return;
    }

    gVr->init();
}

static void setVrMode_native(JNIEnv* /* env */, jclass /* clazz */, jboolean enabled) {
    if (gVr == nullptr) {
        // There is no VR hardware module implemented, do nothing.
        return;
    }

    // Call set_vr_mode method, this must be implemented if the HAL exists.
    gVr->setVrMode(static_cast<bool>(enabled));
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
