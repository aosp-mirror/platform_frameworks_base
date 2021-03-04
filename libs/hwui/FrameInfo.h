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

#include <array>
#include <memory.h>
#include <string>

namespace android {
namespace uirenderer {

static constexpr size_t UI_THREAD_FRAME_INFO_SIZE = 10;

enum class FrameInfoIndex {
    Flags = 0,
    FrameTimelineVsyncId,
    IntendedVsync,
    Vsync,
    InputEventId,
    HandleInputStart,
    AnimationStart,
    PerformTraversalsStart,
    DrawStart,
    FrameDeadline,
    // End of UI frame info

    SyncQueued,

    SyncStart,
    IssueDrawCommandsStart,
    SwapBuffers,
    FrameCompleted,

    DequeueBufferDuration,
    QueueBufferDuration,

    GpuCompleted,
    SwapBuffersCompleted,
    DisplayPresentTime,

    // Must be the last value!
    // Also must be kept in sync with FrameMetrics.java#FRAME_STATS_COUNT
    NumIndexes
};

extern const std::array<const char*, static_cast<int>(FrameInfoIndex::NumIndexes)> FrameInfoNames;

namespace FrameInfoFlags {
enum {
    WindowLayoutChanged = 1 << 0,
    RTAnimation = 1 << 1,
    SurfaceCanvas = 1 << 2,
    SkippedFrame = 1 << 3,
};
};

class UiFrameInfoBuilder {
public:
    static constexpr int64_t INVALID_VSYNC_ID = -1;

    explicit UiFrameInfoBuilder(int64_t* buffer) : mBuffer(buffer) {
        memset(mBuffer, 0, UI_THREAD_FRAME_INFO_SIZE * sizeof(int64_t));
        set(FrameInfoIndex::FrameTimelineVsyncId) = INVALID_VSYNC_ID;
        // The struct is zeroed by memset above. That also sets FrameInfoIndex::InputEventId to
        // equal android::os::IInputConstants::INVALID_INPUT_EVENT_ID == 0.
        // Therefore, we can skip setting the value for InputEventId here. If the value for
        // INVALID_INPUT_EVENT_ID changes, this code would have to be updated, as well.
        set(FrameInfoIndex::FrameDeadline) = std::numeric_limits<int64_t>::max();
    }

    UiFrameInfoBuilder& setVsync(nsecs_t vsyncTime, nsecs_t intendedVsync,
                                 int64_t vsyncId, int64_t frameDeadline) {
        set(FrameInfoIndex::FrameTimelineVsyncId) = vsyncId;
        set(FrameInfoIndex::Vsync) = vsyncTime;
        set(FrameInfoIndex::IntendedVsync) = intendedVsync;
        // Pretend the other fields are all at vsync, too, so that naive
        // duration calculations end up being 0 instead of very large
        set(FrameInfoIndex::HandleInputStart) = vsyncTime;
        set(FrameInfoIndex::AnimationStart) = vsyncTime;
        set(FrameInfoIndex::PerformTraversalsStart) = vsyncTime;
        set(FrameInfoIndex::DrawStart) = vsyncTime;
        set(FrameInfoIndex::FrameDeadline) = frameDeadline;
        return *this;
    }

    UiFrameInfoBuilder& addFlag(int frameInfoFlag) {
        set(FrameInfoIndex::Flags) |= static_cast<uint64_t>(frameInfoFlag);
        return *this;
    }

private:
    inline int64_t& set(FrameInfoIndex index) { return mBuffer[static_cast<int>(index)]; }

    int64_t* mBuffer;
};

class FrameInfo {
public:
    void importUiThreadInfo(int64_t* info);

    void markSyncStart() { set(FrameInfoIndex::SyncStart) = systemTime(SYSTEM_TIME_MONOTONIC); }

    void markIssueDrawCommandsStart() {
        set(FrameInfoIndex::IssueDrawCommandsStart) = systemTime(SYSTEM_TIME_MONOTONIC);
    }

    void markSwapBuffers() { set(FrameInfoIndex::SwapBuffers) = systemTime(SYSTEM_TIME_MONOTONIC); }

    void markSwapBuffersCompleted() {
        set(FrameInfoIndex::SwapBuffersCompleted) = systemTime(SYSTEM_TIME_MONOTONIC);
    }

    void markFrameCompleted() { set(FrameInfoIndex::FrameCompleted) = systemTime(SYSTEM_TIME_MONOTONIC); }

    void addFlag(int frameInfoFlag) {
        set(FrameInfoIndex::Flags) |= static_cast<uint64_t>(frameInfoFlag);
    }

    const int64_t* data() const { return mFrameInfo; }

    inline int64_t operator[](FrameInfoIndex index) const { return get(index); }

    inline int64_t operator[](int index) const {
        if (index < 0 || index >= static_cast<int>(FrameInfoIndex::NumIndexes)) return 0;
        return mFrameInfo[index];
    }

    inline int64_t duration(FrameInfoIndex start, FrameInfoIndex end) const {
        int64_t endtime = get(end);
        int64_t starttime = get(start);
        int64_t gap = endtime - starttime;
        gap = starttime > 0 ? gap : 0;
        if (end > FrameInfoIndex::SyncQueued && start < FrameInfoIndex::SyncQueued) {
            // Need to subtract out the time spent in a stalled state
            // as this will be captured by the previous frame's info
            int64_t offset = get(FrameInfoIndex::SyncStart) - get(FrameInfoIndex::SyncQueued);
            if (offset > 0) {
                gap -= offset;
            }
        }
        return gap > 0 ? gap : 0;
    }

    inline int64_t totalDuration() const {
        return duration(FrameInfoIndex::IntendedVsync, FrameInfoIndex::FrameCompleted);
    }

    inline int64_t gpuDrawTime() const {
        // GPU start time is approximated to the moment before swapBuffer is invoked.
        // We could add an EGLSyncKHR fence at the beginning of the frame, but that is an overhead.
        int64_t endTime = get(FrameInfoIndex::GpuCompleted);
        return endTime > 0 ? endTime - get(FrameInfoIndex::SwapBuffers) : -1;
    }

    inline int64_t& set(FrameInfoIndex index) { return mFrameInfo[static_cast<int>(index)]; }

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
