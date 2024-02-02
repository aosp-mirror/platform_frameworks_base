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

#include "HintSessionWrapper.h"

#include <dlfcn.h>
#include <private/performance_hint_private.h>
#include <utils/Log.h>

#include <chrono>
#include <vector>

#include "../Properties.h"
#include "thread/CommonPool.h"

using namespace std::chrono_literals;

namespace android {
namespace uirenderer {
namespace renderthread {

#define BIND_APH_METHOD(name)                                         \
    name = (decltype(name))dlsym(handle_, "APerformanceHint_" #name); \
    LOG_ALWAYS_FATAL_IF(name == nullptr, "Failed to find required symbol APerformanceHint_" #name)

void HintSessionWrapper::HintSessionBinding::init() {
    if (mInitialized) return;

    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    LOG_ALWAYS_FATAL_IF(handle_ == nullptr, "Failed to dlopen libandroid.so!");

    BIND_APH_METHOD(getManager);
    BIND_APH_METHOD(createSession);
    BIND_APH_METHOD(closeSession);
    BIND_APH_METHOD(updateTargetWorkDuration);
    BIND_APH_METHOD(reportActualWorkDuration);
    BIND_APH_METHOD(sendHint);

    mInitialized = true;
}

HintSessionWrapper::HintSessionWrapper(pid_t uiThreadId, pid_t renderThreadId)
        : mUiThreadId(uiThreadId)
        , mRenderThreadId(renderThreadId)
        , mBinding(std::make_shared<HintSessionBinding>()) {}

HintSessionWrapper::~HintSessionWrapper() {
    destroy();
}

void HintSessionWrapper::destroy() {
    if (mHintSessionFuture.valid()) {
        mHintSession = mHintSessionFuture.get();
    }
    if (mHintSession) {
        mBinding->closeSession(mHintSession);
        mSessionValid = true;
        mHintSession = nullptr;
    }
}

bool HintSessionWrapper::init() {
    if (mHintSession != nullptr) return true;

    // If we're waiting for the session
    if (mHintSessionFuture.valid()) {
        // If the session is here
        if (mHintSessionFuture.wait_for(0s) == std::future_status::ready) {
            mHintSession = mHintSessionFuture.get();
            if (mHintSession != nullptr) {
                mSessionValid = true;
                return true;
            }
        }
        return false;
    }

    // If it broke last time we tried this, shouldn't be running, or
    // has bad argument values, don't even bother
    if (!mSessionValid || !Properties::useHintManager || !Properties::isDrawingEnabled() ||
        mUiThreadId < 0 || mRenderThreadId < 0) {
        return false;
    }

    // Assume that if we return before the end, it broke
    mSessionValid = false;

    mBinding->init();

    APerformanceHintManager* manager = mBinding->getManager();
    if (!manager) return false;

    std::vector<pid_t> tids = CommonPool::getThreadIds();
    tids.push_back(mUiThreadId);
    tids.push_back(mRenderThreadId);

    // Use a placeholder target value to initialize,
    // this will always be replaced elsewhere before it gets used
    int64_t defaultTargetDurationNanos = 16666667;
    mHintSessionFuture = CommonPool::async([=, this, tids = std::move(tids)] {
        return mBinding->createSession(manager, tids.data(), tids.size(),
                                       defaultTargetDurationNanos);
    });
    return false;
}

void HintSessionWrapper::updateTargetWorkDuration(long targetWorkDurationNanos) {
    if (!init()) return;
    targetWorkDurationNanos = targetWorkDurationNanos * Properties::targetCpuTimePercentage / 100;
    if (targetWorkDurationNanos != mLastTargetWorkDuration &&
        targetWorkDurationNanos > kSanityCheckLowerBound &&
        targetWorkDurationNanos < kSanityCheckUpperBound) {
        mLastTargetWorkDuration = targetWorkDurationNanos;
        mBinding->updateTargetWorkDuration(mHintSession, targetWorkDurationNanos);
    }
    mLastFrameNotification = systemTime();
}

void HintSessionWrapper::reportActualWorkDuration(long actualDurationNanos) {
    if (!init()) return;
    mResetsSinceLastReport = 0;
    if (actualDurationNanos > kSanityCheckLowerBound &&
        actualDurationNanos < kSanityCheckUpperBound) {
        mBinding->reportActualWorkDuration(mHintSession, actualDurationNanos);
    }
}

void HintSessionWrapper::sendLoadResetHint() {
    static constexpr int kMaxResetsSinceLastReport = 2;
    if (!init()) return;
    nsecs_t now = systemTime();
    if (now - mLastFrameNotification > kResetHintTimeout &&
        mResetsSinceLastReport <= kMaxResetsSinceLastReport) {
        ++mResetsSinceLastReport;
        mBinding->sendHint(mHintSession, static_cast<int32_t>(SessionHint::CPU_LOAD_RESET));
    }
    mLastFrameNotification = now;
}

void HintSessionWrapper::sendLoadIncreaseHint() {
    if (!init()) return;
    mBinding->sendHint(mHintSession, static_cast<int32_t>(SessionHint::CPU_LOAD_UP));
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
