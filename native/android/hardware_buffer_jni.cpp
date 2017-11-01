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

#define LOG_TAG "AHardwareBuffer"

#include <android/hardware_buffer_jni.h>

#include <android_runtime/android_hardware_HardwareBuffer.h>

using namespace android;

AHardwareBuffer* AHardwareBuffer_fromHardwareBuffer(JNIEnv* env, jobject hardwareBufferObj) {
    return android_hardware_HardwareBuffer_getNativeHardwareBuffer(env, hardwareBufferObj);
}

jobject AHardwareBuffer_toHardwareBuffer(JNIEnv* env, AHardwareBuffer* hardwareBuffer) {
    return android_hardware_HardwareBuffer_createFromAHardwareBuffer(env, hardwareBuffer);
}
