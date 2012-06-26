/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "utils.h"

namespace android {

void Timeout::setTimeout(int msec) {
    if (msec < 0) {
        mSystemEndTime = 0;
        return;
    }

    mSystemEndTime = systemTime() + (static_cast<nsecs_t>(msec) * 1000000);
}

int Timeout::msecTillTimeout(nsecs_t nowTime) {
    if (!mSystemEndTime) {
        return -1;
    }

    if (mSystemEndTime < nowTime) {
        return 0;
    }

    nsecs_t delta = mSystemEndTime - nowTime;
    delta += 999999;
    delta /= 1000000;
    if (delta > 0x7FFFFFFF) {
        return 0x7FFFFFFF;
    }

    return static_cast<int>(delta);
}

}  // namespace android
