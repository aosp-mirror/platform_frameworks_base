/*
 * Copyright (C) 2022 The Android Open Source Project
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

#pragma once

#include <android/performance_hint.h>

#include <future>

#include "utils/TimeUtils.h"

namespace android {
namespace uirenderer {

namespace renderthread {

class HintSessionWrapper {
public:
    HintSessionWrapper(pid_t uiThreadId, pid_t renderThreadId);
    ~HintSessionWrapper();

    void updateTargetWorkDuration(long targetDurationNanos);
    void reportActualWorkDuration(long actualDurationNanos);
    void sendLoadResetHint();
    void sendLoadIncreaseHint();
    bool init();

private:
    APerformanceHintSession* mHintSession = nullptr;
    std::future<APerformanceHintSession*> mHintSessionFuture;

    nsecs_t mLastFrameNotification = 0;
    nsecs_t mLastTargetWorkDuration = 0;

    pid_t mUiThreadId;
    pid_t mRenderThreadId;

    bool mSessionValid = true;

    static constexpr nsecs_t kResetHintTimeout = 100_ms;
    static constexpr int64_t kSanityCheckLowerBound = 100_us;
    static constexpr int64_t kSanityCheckUpperBound = 10_s;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
