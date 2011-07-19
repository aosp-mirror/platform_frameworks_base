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

int inline clz(int32_t x) {
    return __builtin_clz(x);
}

template <typename T>
static inline T min(T a, T b) {
    return a<b ? a : b;
}
template <typename T>
static inline T min(T a, T b, T c) {
    return min(a, min(b, c));
}
template <typename T>
static inline T min(T a, T b, T c, T d) {
    return min(a, b, min(c, d));
}

template <typename T>
static inline T max(T a, T b) {
    return a>b ? a : b;
}
template <typename T>
static inline T max(T a, T b, T c) {
    return max(a, max(b, c));
}
template <typename T>
static inline T max(T a, T b, T c, T d) {
    return max(a, b, max(c, d));
}

template <typename T>
static inline
void swap(T& a, T& b) {
    T t(a);
    a = b;
    b = t;
}


}; // namespace android

#endif /* ANDROID_SURFACE_FLINGER_CLZ_H */
