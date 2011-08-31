/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_SF_LAYER_STATE_H
#define ANDROID_SF_LAYER_STATE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>

#include <ui/Region.h>

#include <surfaceflinger/ISurface.h>

namespace android {

class Parcel;
class ISurfaceComposerClient;

struct layer_state_t {

    layer_state_t()
        :   surface(0), what(0),
            x(0), y(0), z(0), w(0), h(0),
            alpha(0), tint(0), flags(0), mask(0),
            reserved(0)
    {
        matrix.dsdx = matrix.dtdy = 1.0f;
        matrix.dsdy = matrix.dtdx = 0.0f;
    }

    status_t    write(Parcel& output) const;
    status_t    read(const Parcel& input);

            struct matrix22_t {
                float   dsdx;
                float   dtdx;
                float   dsdy;
                float   dtdy;
            };
            SurfaceID       surface;
            uint32_t        what;
            float           x;
            float           y;
            uint32_t        z;
            uint32_t        w;
            uint32_t        h;
            float           alpha;
            uint32_t        tint;
            uint8_t         flags;
            uint8_t         mask;
            uint8_t         reserved;
            matrix22_t      matrix;
            // non POD must be last. see write/read
            Region          transparentRegion;
};

struct ComposerState {
    sp<ISurfaceComposerClient> client;
    layer_state_t state;
    status_t    write(Parcel& output) const;
    status_t    read(const Parcel& input);
};

}; // namespace android

#endif // ANDROID_SF_LAYER_STATE_H

