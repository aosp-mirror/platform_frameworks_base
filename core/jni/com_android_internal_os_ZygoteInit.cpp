/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "Zygote"

#include <ui/GraphicBufferMapper.h>

#include "core_jni_helpers.h"

namespace {

void android_internal_os_ZygoteInit_nativePreloadAppProcessHALs(JNIEnv* env, jclass) {
    android::GraphicBufferMapper::preloadHal();
    // Add preloading here for other HALs that are (a) always passthrough, and
    // (b) loaded by most app processes.
}

const JNINativeMethod gMethods[] = {
    { "nativePreloadAppProcessHALs", "()V",
      (void*)android_internal_os_ZygoteInit_nativePreloadAppProcessHALs },
};

}  // anonymous namespace

namespace android {

int register_com_android_internal_os_ZygoteInit(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/ZygoteInit",
            gMethods, NELEM(gMethods));
}

}  // namespace android
