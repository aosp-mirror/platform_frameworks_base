/*
 * Copyright (C) 2014 The Android Open Source Project
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
#include "renderstate/RenderState.h"

#include "renderthread/RenderThread.h"

namespace android {
namespace uirenderer {

RenderState::RenderState(renderthread::RenderThread& thread) : mRenderThread(thread) {
    mThreadId = pthread_self();
}

void RenderState::onContextDestroyed() {
    for(auto callback : mContextCallbacks) {
        callback->onContextDestroyed();
    }
}

void RenderState::postDecStrong(VirtualLightRefBase* object) {
    if (pthread_equal(mThreadId, pthread_self())) {
        object->decStrong(nullptr);
    } else {
        mRenderThread.queue().post([object]() { object->decStrong(nullptr); });
    }
}

///////////////////////////////////////////////////////////////////////////////
// Render
///////////////////////////////////////////////////////////////////////////////

} /* namespace uirenderer */
} /* namespace android */
