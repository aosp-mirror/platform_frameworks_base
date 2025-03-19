/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <nativehelper/JNIHelp.h>

#include "jni.h"

namespace android {

// Forward declared per-class registration methods.
int register_android_server_ConsumerIrService(JNIEnv* env);
int register_android_server_app_GameManagerService(JNIEnv* env);
int register_android_server_connectivity_Vpn(JNIEnv* env);
int register_android_server_vr_VrManagerService(JNIEnv* env);

namespace {

// TODO(b/)375264322: Remove these trampoline methods after finalizing the
// registrar implementation. Instead, just update the called methods to take a
// class arg, and hand those methods to jniRegisterNativeMethods directly.
void registerConsumerIrService(JNIEnv* env, jclass) {
    register_android_server_ConsumerIrService(env);
}

void registerGameManagerService(JNIEnv* env, jclass) {
    register_android_server_app_GameManagerService(env);
}

void registerVpn(JNIEnv* env, jclass) {
    register_android_server_connectivity_Vpn(env);
}

void registerVrManagerService(JNIEnv* env, jclass) {
    register_android_server_vr_VrManagerService(env);
}

static const JNINativeMethod sJniRegistrarMethods[] = {
        {"registerConsumerIrService", "()V", (void*)registerConsumerIrService},
        {"registerGameManagerService", "()V", (void*)registerGameManagerService},
        {"registerVpn", "()V", (void*)registerVpn},
        {"registerVrManagerService", "()V", (void*)registerVrManagerService},
};

} // namespace

int register_android_server_utils_LazyJniRegistrar(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/utils/LazyJniRegistrar",
                                    sJniRegistrarMethods, NELEM(sJniRegistrarMethods));
}

} // namespace android
