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
    kFlags = 0,
    kIntendedVsync,
    kVsync,
    kOldestInputEvent,
    kNewestInputEvent,
    kHandleInputStart,
    kAnimationStart,
    kPerformTraversalsStart,
    kDrawStart,
    // End of UI frame info

    kSyncStart,
    kIssueDrawCommandsStart,
    kSwapBuffers,
    kFrameCompleted,

    // Must be the last value!
    kNumIndexes
};

extern std::string FrameInfoNames[];

enum class FrameInfoFlags {
    kWindowLayoutChanged = 1 << 0,
    kRTAnimation = 1 << 1,
    kSurfaceCanvas = 1 << 2,
    kSkippedFrame = 1 << 3,
};
MAKE_FLAGS_ENUM(FrameInfoFlags)

class ANDROID_API UiFrameInfoBuilder {
public:
    UiFrameInfoBuilder(int64_t* buffer) : mBuffer(buffer) {
        memset(mBuffer, 0, UI_THREAD_FRAME_INFO_SIZE * sizeof(int64_t));
    }

    UiFrameInfoBuilder& setVsync(nsecs_t vsyncTime, nsecs_t intendedVsync) {
        set(FrameInfoIndex::kVsync) = vsyncTime;
        set(FrameInfoIndex::kIntendedVsync) = intendedVsync;
        return *this;
    }

    UiFrameInfoBuilder& addFlag(FrameInfoFlags flag) {
        set(FrameInfoIndex::kFlags) |= static_cast<uint64_t>(flag);
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
        set(FrameInfoIndex::kSyncStart) = systemTime(CLOCK_MONOTONIC);
    }

    void markIssueDrawCommandsStart() {
        set(FrameInfoIndex::kIssueDrawCommandsStart) = systemTime(CLOCK_MONOTONIC);
    }

    void markSwapBuffers() {
        set(FrameInfoIndex::kSwapBuffers) = systemTime(CLOCK_MONOTONIC);
    }

    void markFrameCompleted() {
        set(FrameInfoIndex::kFrameCompleted) = systemTime(CLOCK_MONOTONIC);
    }

    void addFlag(FrameInfoFlags flag) {
        set(FrameInfoIndex::kFlags) |= static_cast<uint64_t>(flag);
    }

    int64_t operator[](FrameInfoIndex index) const {
        if (index == FrameInfoIndex::kNumIndexes) return 0;
        return mFrameInfo[static_cast<int>(index)];
    }

    int64_t operator[](int index) const {
        if (index < 0 || index >= static_cast<int>(FrameInfoIndex::kNumIndexes)) return 0;
        return mFrameInfo[index];
    }

private:
    inline int64_t& set(FrameInfoIndex index) {
        return mFrameInfo[static_cast<int>(index)];
    }

    int64_t mFrameInfo[static_cast<int>(FrameInfoIndex::kNumIndexes)];
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* FRAMEINFO_H_ */
