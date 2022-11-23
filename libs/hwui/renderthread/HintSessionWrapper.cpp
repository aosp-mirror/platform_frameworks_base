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
#include <utils/Log.h>

#include <vector>

#include "../Properties.h"
#include "thread/CommonPool.h"

namespace android {
namespace uirenderer {
namespace renderthread {

namespace {

typedef APerformanceHintManager* (*APH_getManager)();
typedef APerformanceHintSession* (*APH_createSession)(APerformanceHintManager*, const int32_t*,
                                                      size_t, int64_t);
typedef void (*APH_closeSession)(APerformanceHintSession* session);
typedef void (*APH_updateTargetWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_reportActualWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_sendHint)(APerformanceHintSession* session, int32_t);

bool gAPerformanceHintBindingInitialized = false;
APH_getManager gAPH_getManagerFn = nullptr;
APH_createSession gAPH_createSessionFn = nullptr;
APH_closeSession gAPH_closeSessionFn = nullptr;
APH_updateTargetWorkDuration gAPH_updateTargetWorkDurationFn = nullptr;
APH_reportActualWorkDuration gAPH_reportActualWorkDurationFn = nullptr;
APH_sendHint gAPH_sendHintFn = nullptr;

void ensureAPerformanceHintBindingInitialized() {
    if (gAPerformanceHintBindingInitialized) return;

    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    LOG_ALWAYS_FATAL_IF(handle_ == nullptr, "Failed to dlopen libandroid.so!");

    gAPH_getManagerFn = (APH_getManager)dlsym(handle_, "APerformanceHint_getManager");
    LOG_ALWAYS_FATAL_IF(gAPH_getManagerFn == nullptr,
                        "Failed to find required symbol APerformanceHint_getManager!");

    gAPH_createSessionFn = (APH_createSession)dlsym(handle_, "APerformanceHint_createSession");
    LOG_ALWAYS_FATAL_IF(gAPH_createSessionFn == nullptr,
                        "Failed to find required symbol APerformanceHint_createSession!");

    gAPH_closeSessionFn = (APH_closeSession)dlsym(handle_, "APerformanceHint_closeSession");
    LOG_ALWAYS_FATAL_IF(gAPH_closeSessionFn == nullptr,
                        "Failed to find required symbol APerformanceHint_closeSession!");

    gAPH_updateTargetWorkDurationFn = (APH_updateTargetWorkDuration)dlsym(
            handle_, "APerformanceHint_updateTargetWorkDuration");
    LOG_ALWAYS_FATAL_IF(
            gAPH_updateTargetWorkDurationFn == nullptr,
            "Failed to find required symbol APerformanceHint_updateTargetWorkDuration!");

    gAPH_reportActualWorkDurationFn = (APH_reportActualWorkDuration)dlsym(
            handle_, "APerformanceHint_reportActualWorkDuration");
    LOG_ALWAYS_FATAL_IF(
            gAPH_reportActualWorkDurationFn == nullptr,
            "Failed to find required symbol APerformanceHint_reportActualWorkDuration!");

    gAPH_sendHintFn = (APH_sendHint)dlsym(handle_, "APerformanceHint_sendHint");
    LOG_ALWAYS_FATAL_IF(gAPH_sendHintFn == nullptr,
                        "Failed to find required symbol APerformanceHint_sendHint!");

    gAPerformanceHintBindingInitialized = true;
}

}  // namespace

HintSessionWrapper::HintSessionWrapper(pid_t uiThreadId, pid_t renderThreadId)
        : mUiThreadId(uiThreadId), mRenderThreadId(renderThreadId) {}

HintSessionWrapper::~HintSessionWrapper() {
    if (mHintSession) {
        gAPH_closeSessionFn(mHintSession);
    }
}

bool HintSessionWrapper::useHintSession() {
    if (!Properties::useHintManager || !Properties::isDrawingEnabled()) return false;
    if (mHintSession) return true;
    // If session does not exist, create it;
    // this defers session creation until we try to actually use it.
    if (!mSessionValid) return false;
    return init();
}

bool HintSessionWrapper::init() {
    if (mUiThreadId < 0 || mRenderThreadId < 0) return false;

    // Assume that if we return before the end, it broke
    mSessionValid = false;

    ensureAPerformanceHintBindingInitialized();

    APerformanceHintManager* manager = gAPH_getManagerFn();
    if (!manager) return false;

    std::vector<pid_t> tids = CommonPool::getThreadIds();
    tids.push_back(mUiThreadId);
    tids.push_back(mRenderThreadId);

    // Use a placeholder target value to initialize,
    // this will always be replaced elsewhere before it gets used
    int64_t defaultTargetDurationNanos = 16666667;
    mHintSession =
            gAPH_createSessionFn(manager, tids.data(), tids.size(), defaultTargetDurationNanos);

    mSessionValid = !!mHintSession;
    return mSessionValid;
}

void HintSessionWrapper::updateTargetWorkDuration(long targetWorkDurationNanos) {
    if (!useHintSession()) return;
    targetWorkDurationNanos = targetWorkDurationNanos * Properties::targetCpuTimePercentage / 100;
    if (targetWorkDurationNanos != mLastTargetWorkDuration &&
        targetWorkDurationNanos > kSanityCheckLowerBound &&
        targetWorkDurationNanos < kSanityCheckUpperBound) {
        mLastTargetWorkDuration = targetWorkDurationNanos;
        gAPH_updateTargetWorkDurationFn(mHintSession, targetWorkDurationNanos);
    }
    mLastFrameNotification = systemTime();
}

void HintSessionWrapper::reportActualWorkDuration(long actualDurationNanos) {
    if (!useHintSession()) return;
    if (actualDurationNanos > kSanityCheckLowerBound &&
        actualDurationNanos < kSanityCheckUpperBound) {
        gAPH_reportActualWorkDurationFn(mHintSession, actualDurationNanos);
    }
}

void HintSessionWrapper::sendLoadResetHint() {
    if (!useHintSession()) return;
    nsecs_t now = systemTime();
    if (now - mLastFrameNotification > kResetHintTimeout) {
        gAPH_sendHintFn(mHintSession, static_cast<int>(SessionHint::CPU_LOAD_RESET));
    }
    mLastFrameNotification = now;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
