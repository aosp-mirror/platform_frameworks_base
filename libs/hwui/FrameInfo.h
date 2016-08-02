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
#ifndef FRAMEINFO_H_
#define FRAMEINFO_H_

#include "utils/Macros.h"

#include <cutils/compiler.h>
#include <utils/Timers.h>

#include <memory.h>
#include <string>

namespace android {
namespace uirenderer {

#define UI_THREAD_FRAME_INFO_SIZE 9

enum class FrameInfoIndex {
    Flags = 0,
    IntendedVsync,
    Vsync,
    OldestInputEvent,
    NewestInputEvent,
    HandleInputStart,
    AnimationStart,
    PerformTraversalsStart,
    DrawStart,
    // End of UI frame info

    SyncQueued,

    SyncStart,
    IssueDrawCommandsStart,
    SwapBuffers,
    FrameCompleted,

    DequeueBufferDuration,
    QueueBufferDuration,

    // Must be the last value!
    // Also must be kept in sync with FrameMetrics.java#FRAME_STATS_COUNT
    NumIndexes
};

extern const std::string FrameInfoNames[];

namespace FrameInfoFlags {
    enum {
        WindowLayoutChanged = 1 << 0,
        RTAnimation = 1 << 1,
        SurfaceCanvas = 1 << 2,
        SkippedFrame = 1 << 3,
    };
};

class ANDROID_API UiFrameInfoBuilder {
public:
    UiFrameInfoBuilder(int64_t* buffer) : mBuffer(buffer) {
        memset(mBuffer, 0, UI_THREAD_FRAME_INFO_SIZE * sizeof(int64_t));
    }

    UiFrameInfoBuilder& setVsync(nsecs_t vsyncTime, nsecs_t intendedVsync) {
        set(FrameInfoIndex::Vsync) = vsyncTime;
        set(FrameInfoIndex::IntendedVsync) = intendedVsync;
        // Pretend the other fields are all at vsync, too, so that naive
        // duration calculations end up being 0 instead of very large
        set(FrameInfoIndex::HandleInputStart) = vsyncTime;
        set(FrameInfoIndex::AnimationStart) = vsyncTime;
        set(FrameInfoIndex::PerformTraversalsStart) = vsyncTime;
        set(FrameInfoIndex::DrawStart) = vsyncTime;
        return *this;
    }

    UiFrameInfoBuilder& addFlag(int frameInfoFlag) {
        set(FrameInfoIndex::Flags) |= static_cast<uint64_t>(frameInfoFlag);
        return *this;
    }

private:
    inline int64_t& set(FrameInfoIndex index) {
        return mBuffer[static_cast<int>(index)];
    }

    int64_t* mBuffer;
};

class FrameInfo {
public:
    void importUiThreadInfo(int64_t* info);

    void markSyncStart() {
        set(FrameInfoIndex::SyncStart) = systemTime(CLOCK_MONOTONIC);
    }

    void markIssueDrawCommandsStart() {
        set(FrameInfoIndex::IssueDrawCommandsStart) = systemTime(CLOCK_MONOTONIC);
    }

    void markSwapBuffers() {
        set(FrameInfoIndex::SwapBuffers) = systemTime(CLOCK_MONOTONIC);
    }

    void markFrameCompleted() {
        set(FrameInfoIndex::FrameCompleted) = systemTime(CLOCK_MONOTONIC);
    }

    void addFlag(int frameInfoFlag) {
        set(FrameInfoIndex::Flags) |= static_cast<uint64_t>(frameInfoFlag);
    }

    const int64_t* data() const {
        return mFrameInfo;
    }

    inline int64_t operator[](FrameInfoIndex index) const {
        return get(index);
    }

    inline int64_t operator[](int index) const {
        if (index < 0 || index >= static_cast<int>(FrameInfoIndex::NumIndexes)) return 0;
        return mFrameInfo[index];
    }

    inline int64_t duration(FrameInfoIndex start, FrameInfoIndex end) const {
        int64_t endtime = get(end);
        int64_t starttime = get(start);
        int64_t gap = endtime - starttime;
        gap = starttime > 0 ? gap : 0;
        if (end > FrameInfoIndex::SyncQueued &&
                start < FrameInfoIndex::SyncQueued) {
            // Need to subtract out the time spent in a stalled state
            // as this will be captured by the previous frame's info
            int64_t offset = get(FrameInfoIndex::SyncStart)
                    - get(FrameInfoIndex::SyncQueued);
            if (offset > 0) {
                gap -= offset;
            }
        }
        return gap > 0 ? gap : 0;
    }

    inline int64_t totalDuration() const {
        return duration(FrameInfoIndex::IntendedVsync, FrameInfoIndex::FrameCompleted);
    }

    inline int64_t& set(FrameInfoIndex index) {
        return mFrameInfo[static_cast<int>(index)];
    }

    inline int64_t get(FrameInfoIndex index) const {
        if (index == FrameInfoIndex::NumIndexes) return 0;
        return mFrameInfo[static_cast<int>(index)];
    }

private:
    int64_t mFrameInfo[static_cast<int>(FrameInfoIndex::NumIndexes)];
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* FRAMEINFO_H_ */
