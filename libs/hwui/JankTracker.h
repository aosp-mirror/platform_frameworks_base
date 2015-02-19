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
#ifndef JANKTRACKER_H_
#define JANKTRACKER_H_

#include "FrameInfo.h"
#include "renderthread/TimeLord.h"
#include "utils/RingBuffer.h"

#include <memory>

namespace android {
namespace uirenderer {

enum JankType {
    kMissedVsync = 0,
    kHighInputLatency,
    kSlowUI,
    kSlowSync,
    kSlowRT,

    // must be last
    NUM_BUCKETS,
};

struct JankBucket {
    // Number of frames that hit this bucket
    uint32_t count;
};

// TODO: Replace DrawProfiler with this
class JankTracker {
public:
    JankTracker(nsecs_t frameIntervalNanos);

    void setFrameInterval(nsecs_t frameIntervalNanos);

    void addFrame(const FrameInfo& frame);

    void dump(int fd);
    void reset();

private:
    uint32_t findPercentile(int p);

    JankBucket mBuckets[NUM_BUCKETS];
    int64_t mThresholds[NUM_BUCKETS];
    uint32_t mFrameCounts[128];

    int64_t mFrameInterval;
    uint32_t mTotalFrameCount;
    uint32_t mJankFrameCount;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* JANKTRACKER_H_ */
