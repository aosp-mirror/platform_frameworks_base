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

#ifndef ANDROID_AUDIO_TRACK_SHARED_H
#define ANDROID_AUDIO_TRACK_SHARED_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/threads.h>

namespace android {

// ----------------------------------------------------------------------------

#define MAX_SAMPLE_RATE     65535
#define THREAD_PRIORITY_AUDIO_CLIENT (ANDROID_PRIORITY_AUDIO)

struct audio_track_cblk_t
{
    enum {
        SEQUENCE_MASK   = 0xFFFFFF00,
        BUFFER_MASK     = 0x000000FF
    };

                Mutex       lock;
                Condition   cv;
    volatile    uint32_t    user;
    volatile    uint32_t    server;
    volatile    union {
                    uint16_t    volume[2];
                    uint32_t    volumeLR;
                };
                uint16_t    sampleRate;
                uint16_t    reserved;

                void*       buffers;
                size_t      size;
            
                            audio_track_cblk_t();
                uint32_t    stepUser(int bufferCount);
                bool        stepServer(int bufferCount);
                void*       buffer(int id) const;
};


// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_AUDIO_TRACK_SHARED_H
