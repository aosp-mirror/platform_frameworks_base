/*
 * Copyright (C) 2015 The Android Open Source Project
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
#ifndef UTILS_TIMEUTILS_H
#define UTILS_TIMEUTILS_H

#include <utils/Timers.h>

namespace android {
namespace uirenderer {

constexpr nsecs_t operator"" _s(unsigned long long s) {
    return seconds_to_nanoseconds(s);
}

constexpr nsecs_t operator"" _ms(unsigned long long ms) {
    return milliseconds_to_nanoseconds(ms);
}

constexpr nsecs_t operator"" _us(unsigned long long us) {
    return microseconds_to_nanoseconds(us);
}

} /* namespace uirenderer */
} /* namespace android */

#endif /* UTILS_TIMEUTILS_H */
