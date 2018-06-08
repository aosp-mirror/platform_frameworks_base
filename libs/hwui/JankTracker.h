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
#include "ProfileData.h"
#include "ProfileDataContainer.h"
#include "renderthread/TimeLord.h"
#include "utils/RingBuffer.h"

#include <cutils/compiler.h>
#include <ui/DisplayInfo.h>

#include <array>
#include <memory>

namespace android {
namespace uirenderer {

enum class JankTrackerType {
    // The default, means there's no description set
    Generic,
    // The profile data represents a package
    Package,
    // The profile data is for a specific window
    Window,
};

// Metadata about the ProfileData being collected
struct ProfileDataDescription {
    JankTrackerType type;
    std::string name;
};

// TODO: Replace DrawProfiler with this
class JankTracker {
public:
    explicit JankTracker(ProfileDataContainer* globalData, const DisplayInfo& displayInfo);

    void setDescription(JankTrackerType type, const std::string&& name) {
        mDescription.type = type;
        mDescription.name = name;
    }

    FrameInfo* startFrame() { return &mFrames.next(); }
    void finishFrame(const FrameInfo& frame);

    void dumpStats(int fd) { dumpData(fd, &mDescription, mData.get()); }
    void dumpFrames(int fd);
    void reset();

    // Exposed for FrameInfoVisualizer
    // TODO: Figure out a better way to handle this
    RingBuffer<FrameInfo, 120>& frames() { return mFrames; }

private:
    void setFrameInterval(nsecs_t frameIntervalNanos);

    static void dumpData(int fd, const ProfileDataDescription* description,
                         const ProfileData* data);

    std::array<int64_t, NUM_BUCKETS> mThresholds;
    int64_t mFrameInterval;
    nsecs_t mSwapDeadline;
    // The amount of time we will erase from the total duration to account
    // for SF vsync offsets with HWC2 blocking dequeueBuffers.
    // (Vsync + mDequeueBlockTolerance) is the point at which we expect
    // SF to have released the buffer normally, so we will forgive up to that
    // point in time by comparing to (IssueDrawCommandsStart + DequeueDuration)
    // This is only used if we are in pipelined mode and are using HWC2,
    // otherwise it's 0.
    nsecs_t mDequeueTimeForgiveness = 0;
    ProfileDataContainer mData;
    ProfileDataContainer* mGlobalData;
    ProfileDataDescription mDescription;

    // Ring buffer large enough for 2 seconds worth of frames
    RingBuffer<FrameInfo, 120> mFrames;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* JANKTRACKER_H_ */
