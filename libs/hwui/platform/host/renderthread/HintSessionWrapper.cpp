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

#include "renderthread/HintSessionWrapper.h"

namespace android {
namespace uirenderer {
namespace renderthread {

void HintSessionWrapper::HintSessionBinding::init() {}

HintSessionWrapper::HintSessionWrapper(pid_t uiThreadId, pid_t renderThreadId)
        : mUiThreadId(uiThreadId)
        , mRenderThreadId(renderThreadId)
        , mBinding(std::make_shared<HintSessionBinding>()) {}

HintSessionWrapper::~HintSessionWrapper() {}

void HintSessionWrapper::destroy() {}

bool HintSessionWrapper::init() {
    return false;
}

void HintSessionWrapper::updateTargetWorkDuration(long targetWorkDurationNanos) {}

void HintSessionWrapper::reportActualWorkDuration(long actualDurationNanos) {}

void HintSessionWrapper::sendLoadResetHint() {}

void HintSessionWrapper::sendLoadIncreaseHint() {}

bool HintSessionWrapper::alive() {
    return false;
}

nsecs_t HintSessionWrapper::getLastUpdate() {
    return -1;
}

void HintSessionWrapper::delayedDestroy(RenderThread& rt, nsecs_t delay,
                                        std::shared_ptr<HintSessionWrapper> wrapperPtr) {}

void HintSessionWrapper::setActiveFunctorThreads(std::vector<pid_t> threadIds) {}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
