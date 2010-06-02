/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_EFFECTCBASESHARED_H
#define ANDROID_EFFECTCBASESHARED_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/threads.h>

namespace android {

// ----------------------------------------------------------------------------

// Size of buffer used to exchange parameters between application and mediaserver processes.
#define EFFECT_PARAM_BUFFER_SIZE 1024


// Shared memory area used to exchange parameters between application and mediaserver
// process.
struct effect_param_cblk_t
{
                Mutex       lock;
    volatile    uint32_t    clientIndex;    // Current read/write index for application
    volatile    uint32_t    serverIndex;    // Current read/write index for mediaserver
                uint8_t*    buffer;         // start of parameter buffer

                effect_param_cblk_t()
                    : lock(Mutex::SHARED), clientIndex(0), serverIndex(0) {}
};


// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_EFFECTCBASESHARED_H
