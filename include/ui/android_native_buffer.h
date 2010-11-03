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

#ifndef ANDROID_ANDROID_NATIVES_PRIV_H
#define ANDROID_ANDROID_NATIVES_PRIV_H

#include <ui/egl/android_natives.h>

#ifdef __cplusplus
extern "C" {
#endif

/*****************************************************************************/

typedef struct android_native_buffer_t
{
#ifdef __cplusplus
    android_native_buffer_t() { 
        common.magic = ANDROID_NATIVE_BUFFER_MAGIC;
        common.version = sizeof(android_native_buffer_t);
        memset(common.reserved, 0, sizeof(common.reserved));
    }

    // Implement the methods that sp<android_native_buffer_t> expects so that it
    // can be used to automatically refcount android_native_buffer_t's.
    void incStrong(const void* id) const {
        common.incRef(const_cast<android_native_base_t*>(&common));
    }
    void decStrong(const void* id) const {
        common.decRef(const_cast<android_native_base_t*>(&common));
    }
#endif

    struct android_native_base_t common;

    int width;
    int height;
    int stride;
    int format;
    int usage;

    /* transformation as defined in hardware.h */
    uint8_t transform;

    uint8_t reserved_bytes[3];
    void* reserved[1];

    buffer_handle_t handle;

    void* reserved_proc[8];
} android_native_buffer_t;


/*****************************************************************************/

#ifdef __cplusplus
}
#endif

/*****************************************************************************/

#endif /* ANDROID_ANDROID_NATIVES_PRIV_H */
