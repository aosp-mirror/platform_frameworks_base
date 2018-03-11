/**
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

#ifndef _ANDROID_SERVER_BROADCASTRADIO_TYPES_H
#define _ANDROID_SERVER_BROADCASTRADIO_TYPES_H

#include <jni.h>

namespace android {
namespace server {
namespace BroadcastRadio {

/* Most of these enums are dereived from Java code, based at
 * frameworks/base/core/java/android/hardware/radio/RadioManager.java.
 */

// Keep in sync with STATUS_* constants from RadioManager.java.
enum class Status : jint {
    OK = 0,
    ERROR = -0x80000000ll,  // Integer.MIN_VALUE
    PERMISSION_DENIED = -1,  // -EPERM
    NO_INIT = -19,  // -ENODEV
    BAD_VALUE = -22,  // -EINVAL
    DEAD_OBJECT = -32,  // -EPIPE
    INVALID_OPERATION = -38,  // -ENOSYS
    TIMED_OUT = -110,  // -ETIMEDOUT
};

// Keep in sync with REGION_* constants from RadioManager.java.
enum class Region : jint {
    INVALID = -1,
    ITU_1 = 0,
    ITU_2 = 1,
    OIRT = 2,
    JAPAN = 3,
    KOREA = 4,
};

} // namespace BroadcastRadio
} // namespace server
} // namespace android

#endif // _ANDROID_SERVER_RADIO_TYPES_H
