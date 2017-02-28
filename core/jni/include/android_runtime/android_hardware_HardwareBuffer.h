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

#ifndef _ANDROID_HARDWARE_HARDWAREBUFFER_H
#define _ANDROID_HARDWARE_HARDWAREBUFFER_H

#include <android/hardware_buffer.h>

#include "jni.h"

namespace android {

/* Gets the underlying AHardwareBuffer for a HardwareBuffer. */
extern AHardwareBuffer* android_hardware_HardwareBuffer_getNativeHardwareBuffer(
        JNIEnv* env, jobject hardwareBufferObj);

/* Returns a HardwareBuffer wrapper for the underlying AHardwareBuffer. */
extern jobject android_hardware_HardwareBuffer_createFromAHardwareBuffer(
        JNIEnv* env, AHardwareBuffer* hardwareBuffer);

/* Convert from HAL_PIXEL_FORMAT values to AHARDWAREBUFFER_FORMAT values. */
extern uint32_t android_hardware_HardwareBuffer_convertFromPixelFormat(
      uint32_t format);

/* Convert from AHARDWAREBUFFER_FORMAT values to HAL_PIXEL_FORMAT values. */
extern uint32_t android_hardware_HardwareBuffer_convertToPixelFormat(
      uint32_t format);

/* Convert from AHARDWAREBUFFER_USAGE* flags to to gralloc usage flags. */
extern void android_hardware_HardwareBuffer_convertToGrallocUsageBits(
      uint64_t* outProducerUsage, uint64_t* outConsumerUsage, uint64_t usage0,
      uint64_t usage1);

/* Convert from gralloc usage flags to to AHARDWAREBUFFER_USAGE0* flags. */
extern void android_hardware_HardwareBuffer_convertFromGrallocUsageBits(
      uint64_t* outUsage0, uint64_t* outUsage1, uint64_t producerUsage,
      uint64_t consumerUsage);

} // namespace android

#endif // _ANDROID_HARDWARE_HARDWAREBUFFER_H
