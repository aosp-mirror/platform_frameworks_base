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

#include <android_companion_virtualdevice_build_flags.h>
#include <nativehelper/JNIHelp.h>

#include <array>

#include "jni.h"

namespace android {
namespace {

jboolean nativeVirtualCameraServiceBuildFlagEnabled(JNIEnv* env, jobject clazz) {
    return ::android::companion::virtualdevice::flags::virtual_camera_service_build_flag();
}

const std::array<JNINativeMethod, 1> kMethods = {
        {{"nativeVirtualCameraServiceBuildFlagEnabled", "()Z",
          (void*)nativeVirtualCameraServiceBuildFlagEnabled}},
};

} // namespace

int register_android_server_companion_virtual_VirtualDeviceImpl(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/companion/virtual/VirtualDeviceImpl",
                                    kMethods.data(), kMethods.size());
}

} // namespace android
