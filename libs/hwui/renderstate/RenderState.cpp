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
#include "GlLayer.h"
#include "VkLayer.h"
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

void RenderState::onGLContextCreated() {
    GpuMemoryTracker::onGpuContextCreated();

    // Deferred because creation needs GL context for texture limits
    if (!mLayerPool) {
        mLayerPool = new OffscreenBufferPool();
    }

    // This is delayed because the first access of Caches makes GL calls
    if (!mCaches) {
        mCaches = &Caches::createInstance(*this);
    }
    mCaches->init();
}

static void layerLostGlContext(Layer* layer) {
    LOG_ALWAYS_FATAL_IF(layer->getApi() != Layer::Api::OpenGL,
                        "layerLostGlContext on non GL layer");
    static_cast<GlLayer*>(layer)->onGlContextLost();
}

void RenderState::onGLContextDestroyed() {
    mLayerPool->clear();

    // TODO: reset all cached state in state objects
    std::for_each(mActiveLayers.begin(), mActiveLayers.end(), layerLostGlContext);

    mCaches->terminate();

    destroyLayersInUpdater();
    GpuMemoryTracker::onGpuContextDestroyed();
}

void RenderState::onVkContextCreated() {
    GpuMemoryTracker::onGpuContextCreated();
}

static void layerDestroyedVkContext(Layer* layer) {
    LOG_ALWAYS_FATAL_IF(layer->getApi() != Layer::Api::Vulkan,
                        "layerLostVkContext on non Vulkan layer");
    static_cast<VkLayer*>(layer)->onVkContextDestroyed();
}

void RenderState::onVkContextDestroyed() {
    std::for_each(mActiveLayers.begin(), mActiveLayers.end(), layerDestroyedVkContext);
    destroyLayersInUpdater();
    GpuMemoryTracker::onGpuContextDestroyed();
}

GrContext* RenderState::getGrContext() const {
    return mRenderThread.getGrContext();
}

void RenderState::flush(Caches::FlushMode mode) {
    switch (mode) {
        case Caches::FlushMode::Full:
        // fall through
        case Caches::FlushMode::Moderate:
        // fall through
        case Caches::FlushMode::Layers:
            if (mLayerPool) mLayerPool->clear();
            break;
    }
    if (mCaches) mCaches->flush(mode);
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

void RenderState::invokeFunctor(Functor* functor, DrawGlInfo::Mode mode, DrawGlInfo* info) {
    if (mode == DrawGlInfo::kModeProcessNoContext) {
        // If there's no context we don't need to interrupt as there's
        // no gl state to save/restore
        (*functor)(mode, info);
    } else {
        interruptForFunctorInvoke();
        (*functor)(mode, info);
        resumeFromFunctorInvoke();
    }
}

void RenderState::interruptForFunctorInvoke() {
    mCaches->textureState().resetActiveTexture();
    debugOverdraw(false, false);
    // TODO: We need a way to know whether the functor is sRGB aware (b/32072673)
    if (mCaches->extensions().hasLinearBlending() && mCaches->extensions().hasSRGBWriteControl()) {
        glDisable(GL_FRAMEBUFFER_SRGB_EXT);
    }
}

void RenderState::resumeFromFunctorInvoke() {
    if (mCaches->extensions().hasLinearBlending() && mCaches->extensions().hasSRGBWriteControl()) {
        glEnable(GL_FRAMEBUFFER_SRGB_EXT);
    }

    glViewport(0, 0, mViewportWidth, mViewportHeight);
    glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
    debugOverdraw(false, false);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    mCaches->textureState().activateTexture(0);
    mCaches->textureState().resetBoundTextures();
}

void RenderState::debugOverdraw(bool enable, bool clear) {
    // DEAD CODE
}

static void destroyLayerInUpdater(DeferredLayerUpdater* layerUpdater) {
    layerUpdater->destroyLayer();
}

void RenderState::destroyLayersInUpdater() {
    std::for_each(mActiveLayerUpdaters.begin(), mActiveLayerUpdaters.end(), destroyLayerInUpdater);
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

} /* namespace uirenderer */
} /* namespace android */
