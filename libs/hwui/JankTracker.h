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

#include <cutils/compiler.h>
#include <ui/DisplayInfo.h>

#include <array>
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

// Try to keep as small as possible, should match ASHMEM_SIZE in
// GraphicsStatsService.java
struct ProfileData {
    std::array<uint32_t, NUM_BUCKETS> jankTypeCounts;
    // See comments on kBucket* constants for what this holds
    std::array<uint32_t, 57> frameCounts;
    // Holds a histogram of frame times in 50ms increments from 150ms to 5s
    std::array<uint16_t, 97> slowFrameCounts;

    uint32_t totalFrameCount;
    uint32_t jankFrameCount;
    nsecs_t statStartTime;
};

// TODO: Replace DrawProfiler with this
class JankTracker {
public:
    JankTracker(const DisplayInfo& displayInfo);
    ~JankTracker();

    void addFrame(const FrameInfo& frame);

    void dump(int fd) { dumpData(mData, fd); }
    void reset();

    void switchStorageToAshmem(int ashmemfd);

    uint32_t findPercentile(int p) { return findPercentile(mData, p); }

    ANDROID_API static void dumpBuffer(const void* buffer, size_t bufsize, int fd);

private:
    void freeData();
    void setFrameInterval(nsecs_t frameIntervalNanos);

    static uint32_t findPercentile(const ProfileData* data, int p);
    static void dumpData(const ProfileData* data, int fd);

    std::array<int64_t, NUM_BUCKETS> mThresholds;
    int64_t mFrameInterval;
    // The amount of time we will erase from the total duration to account
    // for SF vsync offsets with HWC2 blocking dequeueBuffers.
    // (Vsync + mDequeueBlockTolerance) is the point at which we expect
    // SF to have released the buffer normally, so we will forgive up to that
    // point in time by comparing to (IssueDrawCommandsStart + DequeueDuration)
    // This is only used if we are in pipelined mode and are using HWC2,
    // otherwise it's 0.
    nsecs_t mDequeueTimeForgiveness = 0;
    ProfileData* mData;
    bool mIsMapped = false;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* JANKTRACKER_H_ */
