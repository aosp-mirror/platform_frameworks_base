/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_SF_SHARED_BUFFER_STACK_H
#define ANDROID_SF_SHARED_BUFFER_STACK_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Debug.h>

namespace android {
// ---------------------------------------------------------------------------

#define NUM_DISPLAY_MAX 4

struct display_cblk_t
{
    uint16_t    w;
    uint16_t    h;
    uint8_t     format;
    uint8_t     orientation;
    uint8_t     reserved[2];
    float       fps;
    float       density;
    float       xdpi;
    float       ydpi;
    uint32_t    pad[2];
};

struct surface_flinger_cblk_t   // 4KB max
{
    uint8_t         connected;
    uint8_t         reserved[3];
    uint32_t        pad[7];
    display_cblk_t  displays[NUM_DISPLAY_MAX];
};

// ---------------------------------------------------------------------------

COMPILE_TIME_ASSERT(sizeof(surface_flinger_cblk_t) <= 4096)

// ---------------------------------------------------------------------------
}; // namespace android

#endif /* ANDROID_SF_SHARED_BUFFER_STACK_H */
