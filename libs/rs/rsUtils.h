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

#include <stdint.h>
#include <sys/types.h>
#include <stdlib.h>

namespace android {
namespace renderscript {

#if 1
#define rsAssert(v) do {if(!(v)) LOGE("rsAssert failed: %s, in %s at %i", #v, __FILE__, __LINE__);} while(0)
#else
#define rsAssert(v) while(0)
#endif

template<typename T>
T rsMin(T in1, T in2)
{
    if (in1 > in2) {
        return in2;
    }
    return in1;
}

template<typename T>
T rsMax(T in1, T in2)
{
    if (in1 < in2) {
        return in2;
    }
    return in1;
}

template<typename T>
T rsFindHighBit(T val)
{
    uint32_t bit = 0;
    while(val > 1) {
        bit++;
        val>>=1;
    }
    return bit;
}

template<typename T>
bool rsIsPow2(T val)
{
    return (val & (val-1)) == 0;
}

template<typename T>
T rsHigherPow2(T v)
{
    if (rsIsPow2(v)) {
        return v;
    }
    return 1 << (rsFindHighBit(v) + 1);
}

template<typename T>
T rsLowerPow2(T v)
{
    if (rsIsPow2(v)) {
        return v;
    }
    return 1 << rsFindHighBit(v);
}


static inline uint16_t rs888to565(uint32_t r, uint32_t g, uint32_t b)
{
    uint16_t t = 0;
    t |= r >> 3;
    t |= (g >> 2) << 5;
    t |= (b >> 3) << 11;
    return t;
}

static inline uint16_t rsBoxFilter565(uint16_t i1, uint16_t i2, uint16_t i3, uint16_t i4)
{
    uint32_t r = ((i1 & 0x1f) + (i2 & 0x1f) + (i3 & 0x1f) + (i4 & 0x1f));
    uint32_t g = ((i1 >> 5) & 0x3f) + ((i2 >> 5) & 0x3f) + ((i3 >> 5) & 0x3f) + ((i1 >> 5) & 0x3f);
    uint32_t b = ((i1 >> 11) + (i2 >> 11) + (i3 >> 11) + (i4 >> 11));
    return (r >> 2) | ((g >> 2) << 5) | ((b >> 2) << 11);
}






}
}

#endif //ANDROID_RS_OBJECT_BASE_H


