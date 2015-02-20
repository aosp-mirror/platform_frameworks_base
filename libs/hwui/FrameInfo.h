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

namespace android {
namespace uirenderer {

#define UI_THREAD_FRAME_INFO_SIZE 9

HWUI_ENUM(FrameInfoIndex,
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
);

HWUI_ENUM(FrameInfoFlags,
    kWindowLayoutChanged = 1 << 0,
    kRTAnimation = 1 << 1,
    kSurfaceCanvas = 1 << 2,
);

class ANDROID_API UiFrameInfoBuilder {
public:
    UiFrameInfoBuilder(int64_t* buffer) : mBuffer(buffer) {
        memset(mBuffer, 0, UI_THREAD_FRAME_INFO_SIZE * sizeof(int64_t));
    }

    UiFrameInfoBuilder& setVsync(nsecs_t vsyncTime, nsecs_t intendedVsync) {
        mBuffer[FrameInfoIndex::kVsync] = vsyncTime;
        mBuffer[FrameInfoIndex::kIntendedVsync] = intendedVsync;
        return *this;
    }

    UiFrameInfoBuilder& addFlag(FrameInfoFlagsEnum flag) {
        mBuffer[FrameInfoIndex::kFlags] |= static_cast<uint64_t>(flag);
        return *this;
    }

private:
    int64_t* mBuffer;
};

class FrameInfo {
public:
    void importUiThreadInfo(int64_t* info);

    void markSyncStart() {
        mFrameInfo[FrameInfoIndex::kSyncStart] = systemTime(CLOCK_MONOTONIC);
    }

    void markIssueDrawCommandsStart() {
        mFrameInfo[FrameInfoIndex::kIssueDrawCommandsStart] = systemTime(CLOCK_MONOTONIC);
    }

    void markSwapBuffers() {
        mFrameInfo[FrameInfoIndex::kSwapBuffers] = systemTime(CLOCK_MONOTONIC);
    }

    void markFrameCompleted() {
        mFrameInfo[FrameInfoIndex::kFrameCompleted] = systemTime(CLOCK_MONOTONIC);
    }

    int64_t operator[](FrameInfoIndexEnum index) const {
        if (index == FrameInfoIndex::kNumIndexes) return 0;
        return mFrameInfo[static_cast<int>(index)];
    }

    int64_t operator[](int index) const {
        if (index < 0 || index >= FrameInfoIndex::kNumIndexes) return 0;
        return mFrameInfo[static_cast<int>(index)];
    }

private:
    int64_t mFrameInfo[FrameInfoIndex::kNumIndexes];
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* FRAMEINFO_H_ */
