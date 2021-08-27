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
#ifndef TIMELORD_H
#define TIMELORD_H

#include <utils/Timers.h>

namespace android {
namespace uirenderer {
namespace renderthread {

class RenderThread;

// This class serves as a helper to filter & manage frame times from multiple sources
// ensuring that time flows linearly and smoothly
class TimeLord {
public:
    void setFrameInterval(nsecs_t intervalNanos) { mFrameIntervalNanos = intervalNanos; }
    nsecs_t frameIntervalNanos() const { return mFrameIntervalNanos; }

    // returns true if the vsync is newer, false if it was rejected for staleness
    bool vsyncReceived(nsecs_t vsync, nsecs_t indendedVsync, int64_t vsyncId,
                       int64_t frameDeadline, nsecs_t frameInterval);
    nsecs_t latestVsync() { return mFrameTimeNanos; }
    nsecs_t computeFrameTimeNanos();
    int64_t lastVsyncId() const { return mFrameVsyncId; }
    int64_t lastFrameDeadline() const { return mFrameDeadline; }

private:
    friend class RenderThread;

    TimeLord();
    ~TimeLord() {}

    nsecs_t mFrameIntervalNanos;
    nsecs_t mFrameTimeNanos;
    nsecs_t mFrameIntendedTimeNanos;
    int64_t mFrameVsyncId;
    int64_t mFrameDeadline;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* TIMELORD_H */
