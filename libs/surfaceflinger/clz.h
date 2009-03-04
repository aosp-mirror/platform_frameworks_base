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

#ifndef ANDROID_SURFACE_FLINGER_CLZ_H

#include <stdint.h>

namespace android {

int clz_impl(int32_t x);

int inline clz(int32_t x)
{
#if defined(__arm__) && !defined(__thumb__)
    return __builtin_clz(x);
#else
    return clz_impl(x);
#endif
}


}; // namespace android

#endif /* ANDROID_SURFACE_FLINGER_CLZ_H */
