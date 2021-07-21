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
#include <limits>
#include "FrameInfo.h"

namespace android {
namespace uirenderer {
namespace renderthread {

TimeLord::TimeLord()
        : mFrameIntervalNanos(milliseconds_to_nanoseconds(16))
        , mFrameTimeNanos(0)
        , mFrameIntendedTimeNanos(0)
        , mFrameVsyncId(UiFrameInfoBuilder::INVALID_VSYNC_ID)
        , mFrameDeadline(std::numeric_limits<int64_t>::max()) {}

bool TimeLord::vsyncReceived(nsecs_t vsync, nsecs_t intendedVsync, int64_t vsyncId,
                             int64_t frameDeadline, nsecs_t frameInterval) {
    if (intendedVsync > mFrameIntendedTimeNanos) {
        mFrameIntendedTimeNanos = intendedVsync;

        // The intendedVsync might have been advanced to account for scheduling
        // jitter. Since we don't have a way to advance the vsync id we just
        // reset it.
        mFrameVsyncId = (vsyncId > mFrameVsyncId) ? vsyncId : UiFrameInfoBuilder::INVALID_VSYNC_ID;
        mFrameDeadline = frameDeadline;
        if (frameInterval > 0) {
            mFrameIntervalNanos = frameInterval;
        }
    }

    if (vsync > mFrameTimeNanos) {
        mFrameTimeNanos = vsync;
        return true;
    }
    return false;
}

nsecs_t TimeLord::computeFrameTimeNanos() {
    // Logic copied from Choreographer.java
    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    nsecs_t jitterNanos = now - mFrameTimeNanos;
    if (jitterNanos >= mFrameIntervalNanos) {
        nsecs_t lastFrameOffset = jitterNanos % mFrameIntervalNanos;
        mFrameTimeNanos = now - lastFrameOffset;
        // mFrameVsyncId is not adjusted here as we still want to send
        // the vsync id that started this frame to the Surface Composer
    }
    return mFrameTimeNanos;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
