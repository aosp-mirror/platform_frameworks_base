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

#include "clz.h"

namespace android {

int clz_impl(int32_t x)
{
#if defined(__arm__) && !defined(__thumb__)
    return __builtin_clz(x);
#else
    if (!x) return 32;
    int e = 31;
    if (x&0xFFFF0000)   { e -=16; x >>=16; }
    if (x&0x0000FF00)   { e -= 8; x >>= 8; }
    if (x&0x000000F0)   { e -= 4; x >>= 4; }
    if (x&0x0000000C)   { e -= 2; x >>= 2; }
    if (x&0x00000002)   { e -= 1; }
    return e;
#endif
}

}; // namespace android
