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

#ifndef ANDROID_UI_SHARED_STATE_H
#define ANDROID_UI_SHARED_STATE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Debug.h>
#include <utils/threads.h>

namespace android {

/*
 * These structures are shared between the composer process and its clients
 */

// ---------------------------------------------------------------------------

struct surface_info_t { // 4 longs, 16 bytes
    enum {
        eBufferDirty    = 0x01,
        eNeedNewBuffer  = 0x02
    };
    uint8_t     reserved[11];
    uint8_t     flags;
    status_t    status;
};

// ---------------------------------------------------------------------------

const uint32_t NUM_LAYERS_MAX = 31;

enum { // layer_cblk_t swapState
    eIndex              = 0x00000001,
    eFlipRequested      = 0x00000002,
    
    eResizeBuffer0      = 0x00000004,
    eResizeBuffer1      = 0x00000008,    
    eResizeRequested    = eResizeBuffer0 | eResizeBuffer1,
    
    eBusy               = 0x00000010,
    eLocked             = 0x00000020,
    eNextFlipPending    = 0x00000040,    
    eInvalidSurface     = 0x00000080
};

enum { // layer_cblk_t flags
    eLayerNotPosted     = 0x00000001,
    eNoCopyBack         = 0x00000002,
    eReserved           = 0x0000007C,
    eBufferIndexShift   = 7,
    eBufferIndex        = 1<<eBufferIndexShift,
};

struct flat_region_t    // 40 bytes
{
    int32_t     count;
    int16_t     l;
    int16_t     t;
    int16_t     r;
    int16_t     b;
    uint16_t    runs[14];
};

struct layer_cblk_t     // (128 bytes)
{
    volatile    int32_t             swapState;      //  4
    volatile    int32_t             flags;          //  4
    volatile    int32_t             identity;       //  4
                int32_t             reserved;       //  4
                surface_info_t      surface[2];     // 32
                flat_region_t       region[2];      // 80

    static inline int backBuffer(uint32_t state) {
        return ((state & eIndex) ^ ((state & eFlipRequested)>>1));
    }
    static inline int frontBuffer(uint32_t state) {
        return 1 - backBuffer(state);
    }
};

// ---------------------------------------------------------------------------

struct per_client_cblk_t   // 4KB max
{
    per_client_cblk_t() : lock(Mutex::SHARED) { }

                Mutex           lock;
                Condition       cv;
                layer_cblk_t    layers[NUM_LAYERS_MAX] __attribute__((aligned(32)));

    enum {
        BLOCKING = 0x00000001,
        INSPECT  = 0x00000002
    };

    // these functions are used by the clients
    status_t validate(size_t i) const;
    int32_t lock_layer(size_t i, uint32_t flags);
    uint32_t unlock_layer_and_post(size_t i);
    void unlock_layer(size_t i);
};
// ---------------------------------------------------------------------------

const uint32_t NUM_DISPLAY_MAX = 4;

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

COMPILE_TIME_ASSERT(sizeof(layer_cblk_t) == 128)
COMPILE_TIME_ASSERT(sizeof(per_client_cblk_t) <= 4096)
COMPILE_TIME_ASSERT(sizeof(surface_flinger_cblk_t) <= 4096)

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_UI_SHARED_STATE_H

