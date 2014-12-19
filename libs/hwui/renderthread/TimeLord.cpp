/*
 * Copyright (C) 2014 The Android Open Source Project
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
#include "TimeLord.h"

namespace android {
namespace uirenderer {
namespace renderthread {

TimeLord::TimeLord()
        : mFrameIntervalNanos(milliseconds_to_nanoseconds(16))
        , mFrameTimeNanos(0) {
}

bool TimeLord::vsyncReceived(nsecs_t vsync) {
    if (vsync > mFrameTimeNanos) {
        mFrameTimeNanos = vsync;
        return true;
    }
    return false;
}

nsecs_t TimeLord::computeFrameTimeMs() {
    // Logic copied from Choreographer.java
    nsecs_t now = systemTime(CLOCK_MONOTONIC);
    nsecs_t jitterNanos = now - mFrameTimeNanos;
    if (jitterNanos >= mFrameIntervalNanos) {
        nsecs_t lastFrameOffset = jitterNanos % mFrameIntervalNanos;
        mFrameTimeNanos = now - lastFrameOffset;
    }
    return nanoseconds_to_milliseconds(mFrameTimeNanos);
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
