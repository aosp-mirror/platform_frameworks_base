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
#include <optional>

#include "utils/TimeUtils.h"

namespace android {
namespace uirenderer {

namespace renderthread {

class RenderThread;

class HintSessionWrapper {
public:
    friend class HintSessionWrapperTests;

    HintSessionWrapper(pid_t uiThreadId, pid_t renderThreadId);
    ~HintSessionWrapper();

    void updateTargetWorkDuration(long targetDurationNanos);
    void reportActualWorkDuration(long actualDurationNanos);
    void sendLoadResetHint();
    void sendLoadIncreaseHint();
    bool init();
    void destroy();
    bool alive();
    nsecs_t getLastUpdate();
    void delayedDestroy(renderthread::RenderThread& rt, nsecs_t delay,
                        std::shared_ptr<HintSessionWrapper> wrapperPtr);

private:
    APerformanceHintSession* mHintSession = nullptr;
    // This needs to work concurrently for testing
    std::optional<std::shared_future<APerformanceHintSession*>> mHintSessionFuture;

    int mResetsSinceLastReport = 0;
    nsecs_t mLastFrameNotification = 0;
    nsecs_t mLastTargetWorkDuration = 0;

    pid_t mUiThreadId;
    pid_t mRenderThreadId;

    bool mSessionValid = true;

    static constexpr nsecs_t kResetHintTimeout = 100_ms;
    static constexpr int64_t kSanityCheckLowerBound = 100_us;
    static constexpr int64_t kSanityCheckUpperBound = 10_s;
    static constexpr int64_t kDefaultTargetDuration = 16666667;

    // Allows easier stub when testing
    class HintSessionBinding {
    public:
        virtual ~HintSessionBinding() = default;
        virtual void init();
        APerformanceHintManager* (*getManager)();
        APerformanceHintSession* (*createSession)(APerformanceHintManager* manager,
                                                  const int32_t* tids, size_t tidCount,
                                                  int64_t defaultTarget) = nullptr;
        void (*closeSession)(APerformanceHintSession* session) = nullptr;
        void (*updateTargetWorkDuration)(APerformanceHintSession* session,
                                         int64_t targetDuration) = nullptr;
        void (*reportActualWorkDuration)(APerformanceHintSession* session,
                                         int64_t actualDuration) = nullptr;
        void (*sendHint)(APerformanceHintSession* session, int32_t hintId) = nullptr;

    private:
        bool mInitialized = false;
    };

    std::shared_ptr<HintSessionBinding> mBinding;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
