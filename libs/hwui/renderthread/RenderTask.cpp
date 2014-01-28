/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "RenderTask"

#include "RenderTask.h"

#include <utils/Log.h>
#include <utils/Condition.h>
#include <utils/Mutex.h>

namespace android {
namespace uirenderer {
namespace renderthread {

void SignalingRenderTask::run() {
    mTask->run();
    mLock->lock();
    mSignal->signal();
    mLock->unlock();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
