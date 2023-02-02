/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "HardwareBufferHelpers.h"

#include <dlfcn.h>
#include <log/log.h>

#ifdef __ANDROID__
typedef AHardwareBuffer* (*AHB_from_HB)(JNIEnv*, jobject);
typedef jobject (*AHB_to_HB)(JNIEnv*, AHardwareBuffer*);
static AHB_from_HB fromHardwareBuffer = nullptr;
static AHB_to_HB toHardwareBuffer = nullptr;
#endif

void android::uirenderer::HardwareBufferHelpers::init() {
#ifdef __ANDROID__  // Layoutlib does not support graphic buffer or parcel
    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    fromHardwareBuffer = (AHB_from_HB)dlsym(handle_, "AHardwareBuffer_fromHardwareBuffer");
    LOG_ALWAYS_FATAL_IF(fromHardwareBuffer == nullptr,
                        "Failed to find required symbol AHardwareBuffer_fromHardwareBuffer!");

    toHardwareBuffer = (AHB_to_HB)dlsym(handle_, "AHardwareBuffer_toHardwareBuffer");
    LOG_ALWAYS_FATAL_IF(toHardwareBuffer == nullptr,
                        " Failed to find required symbol AHardwareBuffer_toHardwareBuffer!");
#endif
}

AHardwareBuffer* android::uirenderer::HardwareBufferHelpers::AHardwareBuffer_fromHardwareBuffer(
        JNIEnv* env, jobject hardwarebuffer) {
#ifdef __ANDROID__
    LOG_ALWAYS_FATAL_IF(fromHardwareBuffer == nullptr,
                        "Failed to find symbol AHardwareBuffer_fromHardwareBuffer, did you forget "
                        "to call HardwareBufferHelpers::init?");
    return fromHardwareBuffer(env, hardwarebuffer);
#else
    ALOGE("ERROR attempting to invoke AHardwareBuffer_fromHardwareBuffer on non Android "
          "configuration");
    return nullptr;
#endif
}

jobject android::uirenderer::HardwareBufferHelpers::AHardwareBuffer_toHardwareBuffer(
        JNIEnv* env, AHardwareBuffer* ahardwarebuffer) {
#ifdef __ANDROID__
    LOG_ALWAYS_FATAL_IF(toHardwareBuffer == nullptr,
                        "Failed to find symbol AHardwareBuffer_toHardwareBuffer, did you forget to "
                        "call HardwareBufferHelpers::init?");
    return toHardwareBuffer(env, ahardwarebuffer);
#else
    ALOGE("ERROR attempting to invoke AHardwareBuffer_toHardwareBuffer on non Android "
          "configuration");
    return nullptr;
#endif
}