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
#include <GpuMemoryTracker.h>
#include "DeferredLayerUpdater.h"
#include "Snapshot.h"

#include "renderthread/CanvasContext.h"
#include "renderthread/EglManager.h"
#include "utils/GLUtils.h"

#include <algorithm>

#include <ui/ColorSpace.h>

namespace android {
namespace uirenderer {

RenderState::RenderState(renderthread::RenderThread& thread)
        : mRenderThread(thread), mViewportWidth(0), mViewportHeight(0), mFramebuffer(0) {
    mThreadId = pthread_self();
}

RenderState::~RenderState() {
}

void RenderState::onContextCreated() {
    GpuMemoryTracker::onGpuContextCreated();
}

static void destroyLayerInUpdater(DeferredLayerUpdater* layerUpdater) {
    layerUpdater->destroyLayer();
}

void RenderState::onContextDestroyed() {
    std::for_each(mActiveLayerUpdaters.begin(), mActiveLayerUpdaters.end(), destroyLayerInUpdater);
    for(auto callback : mContextCallbacks) {
        callback->onContextDestroyed();
    }
    GpuMemoryTracker::onGpuContextDestroyed();
}

GrContext* RenderState::getGrContext() const {
    return mRenderThread.getGrContext();
}

void RenderState::onBitmapDestroyed(uint32_t pixelRefId) {
    // DEAD CODE
}

void RenderState::setViewport(GLsizei width, GLsizei height) {
    mViewportWidth = width;
    mViewportHeight = height;
    glViewport(0, 0, mViewportWidth, mViewportHeight);
}

void RenderState::getViewport(GLsizei* outWidth, GLsizei* outHeight) {
    *outWidth = mViewportWidth;
    *outHeight = mViewportHeight;
}

void RenderState::bindFramebuffer(GLuint fbo) {
    if (mFramebuffer != fbo) {
        mFramebuffer = fbo;
        glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
    }
}

GLuint RenderState::createFramebuffer() {
    GLuint ret;
    glGenFramebuffers(1, &ret);
    return ret;
}

void RenderState::deleteFramebuffer(GLuint fbo) {
    if (mFramebuffer == fbo) {
        // GL defines that deleting the currently bound FBO rebinds FBO 0.
        // Reflect this in our cached value.
        mFramebuffer = 0;
    }
    glDeleteFramebuffers(1, &fbo);
}

void RenderState::debugOverdraw(bool enable, bool clear) {
    // DEAD CODE
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

void RenderState::dump() {
    // DEAD CODE
}

renderthread::RenderThread& RenderState::getRenderThread() {
    return mRenderThread;
}

} /* namespace uirenderer */
} /* namespace android */
