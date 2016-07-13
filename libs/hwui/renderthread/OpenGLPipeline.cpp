/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "OpenGLPipeline.h"

#include "DeferredLayerUpdater.h"
#include "EglManager.h"
#include "LayerRenderer.h"
#include "renderstate/RenderState.h"
#include "Readback.h"

#include <android/native_window.h>
#include <cutils/properties.h>
#include <strings.h>

namespace android {
namespace uirenderer {
namespace renderthread {

OpenGLPipeline::OpenGLPipeline(RenderThread& thread)
        :  mEglManager(thread.eglManager()), mRenderThread(thread) {
}

MakeCurrentResult OpenGLPipeline::makeCurrent() {
    // TODO: Figure out why this workaround is needed, see b/13913604
    // In the meantime this matches the behavior of GLRenderer, so it is not a regression
    EGLint error = 0;
    bool haveNewSurface = mEglManager.makeCurrent(mEglSurface, &error);

    Caches::getInstance().textureCache.resetMarkInUse(this);
    if (!haveNewSurface) {
        return MakeCurrentResult::AlreadyCurrent;
    }
    return error ? MakeCurrentResult::Failed : MakeCurrentResult::Succeeded;
}

Frame OpenGLPipeline::getFrame() {
    LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
                "drawRenderNode called on a context with no surface!");
    return mEglManager.beginFrame(mEglSurface);
}

bool OpenGLPipeline::draw(const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
        const FrameBuilder::LightGeometry& lightGeometry,
        LayerUpdateQueue* layerUpdateQueue,
        const Rect& contentDrawBounds, bool opaque,
        const BakedOpRenderer::LightInfo& lightInfo,
        const std::vector< sp<RenderNode> >& renderNodes,
        FrameInfoVisualizer* profiler) {

    mEglManager.damageFrame(frame, dirty);

    bool drew = false;


    auto& caches = Caches::getInstance();
    FrameBuilder frameBuilder(dirty, frame.width(), frame.height(), lightGeometry, caches);

    frameBuilder.deferLayers(*layerUpdateQueue);
    layerUpdateQueue->clear();

    frameBuilder.deferRenderNodeScene(renderNodes, contentDrawBounds);

    BakedOpRenderer renderer(caches, mRenderThread.renderState(),
            opaque, lightInfo);
    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
    profiler->draw(&renderer);
    drew = renderer.didDraw();

    // post frame cleanup
    caches.clearGarbage();
    caches.pathCache.trim();
    caches.tessellationCache.trim();

#if DEBUG_MEMORY_USAGE
    mCaches.dumpMemoryUsage();
#else
    if (CC_UNLIKELY(Properties::debugLevel & kDebugMemory)) {
        caches.dumpMemoryUsage();
    }
#endif

    return drew;
}

bool OpenGLPipeline::swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
        FrameInfo* currentFrameInfo, bool* requireSwap) {

    GL_CHECKPOINT(LOW);

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    currentFrameInfo->markSwapBuffers();

    *requireSwap = drew || mEglManager.damageRequiresSwap();

    if (*requireSwap && (CC_UNLIKELY(!mEglManager.swapBuffers(frame, screenDirty)))) {
        return false;
    }

    return *requireSwap;
}

bool OpenGLPipeline::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    layer->apply();
    return Readback::copyTextureLayerInto(mRenderThread, *(layer->backingLayer()), bitmap)
            == CopyResult::Success;
}

Layer* OpenGLPipeline::createTextureLayer() {
    mEglManager.initialize();
    return LayerRenderer::createTextureLayer(mRenderThread.renderState());
}

void OpenGLPipeline::onStop() {
    if (mEglManager.isCurrent(mEglSurface)) {
        mEglManager.makeCurrent(EGL_NO_SURFACE);
    }
}

bool OpenGLPipeline::setSurface(Surface* surface, SwapBehavior swapBehavior) {

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (surface) {
        mEglSurface = mEglManager.createSurface(surface);
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        const bool preserveBuffer = (swapBehavior != SwapBehavior::kSwap_discardBuffer);
        mBufferPreserved = mEglManager.setPreserveBuffer(mEglSurface, preserveBuffer);
        return true;
    }

    return false;
}

bool OpenGLPipeline::isSurfaceReady() {
    return CC_UNLIKELY(mEglSurface != EGL_NO_SURFACE);
}

bool OpenGLPipeline::isContextReady() {
    return CC_LIKELY(mEglManager.hasEglContext());
}

void OpenGLPipeline::onDestroyHardwareResources() {
    Caches& caches = Caches::getInstance();
    // Make sure to release all the textures we were owning as there won't
    // be another draw
    caches.textureCache.resetMarkInUse(this);
    mRenderThread.renderState().flush(Caches::FlushMode::Layers);
}

void OpenGLPipeline::renderLayers(const FrameBuilder::LightGeometry& lightGeometry,
        LayerUpdateQueue* layerUpdateQueue, bool opaque,
        const BakedOpRenderer::LightInfo& lightInfo) {
    static const std::vector< sp<RenderNode> > emptyNodeList;
    auto& caches = Caches::getInstance();
    FrameBuilder frameBuilder(*layerUpdateQueue, lightGeometry, caches);
    layerUpdateQueue->clear();
    BakedOpRenderer renderer(caches, mRenderThread.renderState(),
            opaque, lightInfo);
    LOG_ALWAYS_FATAL_IF(renderer.didDraw(), "shouldn't draw in buildlayer case");
    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
}

TaskManager* OpenGLPipeline::getTaskManager() {
    return &Caches::getInstance().tasks;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
