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

#ifndef ANDROID_RS_UTILS_H
#define ANDROID_RS_UTILS_H

#define LOG_NDEBUG 0
#define LOG_TAG "RenderScript"

#include <utils/Log.h>

#include "rsStream.h"

#include <utils/String8.h>
#include <utils/Vector.h>

#include <stdlib.h>
#include <pthread.h>
#include <time.h>
#include <cutils/atomic.h>

#include <math.h>

#include "RenderScript.h"

namespace android {
namespace renderscript {

#if 1
#define rsAssert(v) do {if(!(v)) ALOGE("rsAssert failed: %s, in %s at %i", #v, __FILE__, __LINE__);} while (0)
#else
#define rsAssert(v) while (0)
#endif

typedef float rsvF_2 __attribute__ ((vector_size (8)));
typedef float rsvF_4 __attribute__ ((vector_size (16)));
typedef uint8_t rsvU8_4 __attribute__ ((vector_size (4)));

union float2 {
    rsvF_2 v;
    float f[2];
};

union float4 {
    rsvF_4 v;
    float f[4];
};

union uchar4 {
    rsvU8_4 v;
    uint8_t f[4];
    uint32_t packed;
};

template<typename T>
T rsMin(T in1, T in2)
{
    if (in1 > in2) {
        return in2;
    }
    return in1;
}

template<typename T>
T rsMax(T in1, T in2) {
    if (in1 < in2) {
        return in2;
    }
    return in1;
}

template<typename T>
T rsFindHighBit(T val) {
    uint32_t bit = 0;
    while (val > 1) {
        bit++;
        val>>=1;
    }
    return bit;
}

template<typename T>
bool rsIsPow2(T val) {
    return (val & (val-1)) == 0;
}

template<typename T>
T rsHigherPow2(T v) {
    if (rsIsPow2(v)) {
        return v;
    }
    return 1 << (rsFindHighBit(v) + 1);
}

template<typename T>
T rsLowerPow2(T v) {
    if (rsIsPow2(v)) {
        return v;
    }
    return 1 << rsFindHighBit(v);
}

static inline uint16_t rs888to565(uint32_t r, uint32_t g, uint32_t b) {
    uint16_t t = 0;
    t |= b >> 3;
    t |= (g >> 2) << 5;
    t |= (r >> 3) << 11;
    return t;
}

static inline uint16_t rsBoxFilter565(uint16_t i1, uint16_t i2, uint16_t i3, uint16_t i4) {
    uint32_t r = ((i1 & 0x1f) + (i2 & 0x1f) + (i3 & 0x1f) + (i4 & 0x1f));
    uint32_t g = ((i1 >> 5) & 0x3f) + ((i2 >> 5) & 0x3f) + ((i3 >> 5) & 0x3f) + ((i4 >> 5) & 0x3f);
    uint32_t b = ((i1 >> 11) + (i2 >> 11) + (i3 >> 11) + (i4 >> 11));
    return (r >> 2) | ((g >> 2) << 5) | ((b >> 2) << 11);
}

static inline uint32_t rsBoxFilter8888(uint32_t i1, uint32_t i2, uint32_t i3, uint32_t i4) {
    uint32_t r = (i1 & 0xff) +         (i2 & 0xff) +         (i3 & 0xff) +         (i4 & 0xff);
    uint32_t g = ((i1 >> 8) & 0xff) +  ((i2 >> 8) & 0xff) +  ((i3 >> 8) & 0xff) +  ((i4 >> 8) & 0xff);
    uint32_t b = ((i1 >> 16) & 0xff) + ((i2 >> 16) & 0xff) + ((i3 >> 16) & 0xff) + ((i4 >> 16) & 0xff);
    uint32_t a = ((i1 >> 24) & 0xff) + ((i2 >> 24) & 0xff) + ((i3 >> 24) & 0xff) + ((i4 >> 24) & 0xff);
    return (r >> 2) | ((g >> 2) << 8) | ((b >> 2) << 16) | ((a >> 2) << 24);
}

}
}

#endif //ANDROID_RS_OBJECT_BASE_H


