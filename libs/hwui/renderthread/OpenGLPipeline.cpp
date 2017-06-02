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
#include "Frame.h"
#include "GlLayer.h"
#include "ProfileRenderer.h"
#include "renderstate/RenderState.h"
#include "OpenGLReadback.h"

#include <cutils/properties.h>
#include <strings.h>

namespace android {
namespace uirenderer {
namespace renderthread {

OpenGLPipeline::OpenGLPipeline(RenderThread& thread)
        :  mEglManager(thread.eglManager())
        , mRenderThread(thread) {
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
    ProfileRenderer profileRenderer(renderer);
    profiler->draw(profileRenderer);
    drew = renderer.didDraw();

    // post frame cleanup
    caches.clearGarbage();
    caches.pathCache.trim();
    caches.tessellationCache.trim();

#if DEBUG_MEMORY_USAGE
    caches.dumpMemoryUsage();
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
    ATRACE_CALL();
    // acquire most recent buffer for drawing
    layer->updateTexImage();
    layer->apply();
    return OpenGLReadbackImpl::copyLayerInto(mRenderThread,
            static_cast<GlLayer&>(*layer->backingLayer()), bitmap);
}

static Layer* createLayer(RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight,
        SkColorFilter* colorFilter, int alpha, SkBlendMode mode, bool blend) {
    GlLayer* layer = new GlLayer(renderState, layerWidth, layerHeight, colorFilter, alpha,
            mode, blend);
    Caches::getInstance().textureState().activateTexture(0);
    layer->generateTexture();
    return layer;
}

DeferredLayerUpdater* OpenGLPipeline::createTextureLayer() {
    mEglManager.initialize();
    return new DeferredLayerUpdater(mRenderThread.renderState(), createLayer, Layer::Api::OpenGL);
}

void OpenGLPipeline::onStop() {
    if (mEglManager.isCurrent(mEglSurface)) {
        mEglManager.makeCurrent(EGL_NO_SURFACE);
    }
}

bool OpenGLPipeline::setSurface(Surface* surface, SwapBehavior swapBehavior, ColorMode colorMode) {

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (surface) {
        const bool wideColorGamut = colorMode == ColorMode::WideColorGamut;
        mEglSurface = mEglManager.createSurface(surface, wideColorGamut);
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

static bool layerMatchesWH(OffscreenBuffer* layer, int width, int height) {
    return layer->viewportWidth == (uint32_t)width && layer->viewportHeight == (uint32_t)height;
}

bool OpenGLPipeline::createOrUpdateLayer(RenderNode* node,
        const DamageAccumulator& damageAccumulator) {
    RenderState& renderState = mRenderThread.renderState();
    OffscreenBufferPool& layerPool = renderState.layerPool();
    bool transformUpdateNeeded = false;
    if (node->getLayer() == nullptr) {
        node->setLayer(layerPool.get(renderState, node->getWidth(), node->getHeight()));
        transformUpdateNeeded = true;
    } else if (!layerMatchesWH(node->getLayer(), node->getWidth(), node->getHeight())) {
        // TODO: remove now irrelevant, currently enqueued damage (respecting damage ordering)
        // Or, ideally, maintain damage between frames on node/layer so ordering is always correct
        if (node->properties().fitsOnLayer()) {
            node->setLayer(layerPool.resize(node->getLayer(), node->getWidth(), node->getHeight()));
        } else {
            destroyLayer(node);
        }
        transformUpdateNeeded = true;
    }

    if (transformUpdateNeeded && node->getLayer()) {
        // update the transform in window of the layer to reset its origin wrt light source position
        Matrix4 windowTransform;
        damageAccumulator.computeCurrentTransform(&windowTransform);
        node->getLayer()->setWindowTransform(windowTransform);
    }

    return transformUpdateNeeded;
}

bool OpenGLPipeline::pinImages(LsaVector<sk_sp<Bitmap>>& images) {
    TextureCache& cache = Caches::getInstance().textureCache;
    bool prefetchSucceeded = true;
    for (auto& bitmapResource : images) {
        prefetchSucceeded &= cache.prefetchAndMarkInUse(this, bitmapResource.get());
    }
    return prefetchSucceeded;
}

void OpenGLPipeline::unpinImages() {
    Caches::getInstance().textureCache.resetMarkInUse(this);
}

void OpenGLPipeline::destroyLayer(RenderNode* node) {
    if (OffscreenBuffer* layer = node->getLayer()) {
        layer->renderState.layerPool().putOrDelete(layer);
        node->setLayer(nullptr);
    }
}

void OpenGLPipeline::prepareToDraw(const RenderThread& thread, Bitmap* bitmap) {
    if (Caches::hasInstance() && thread.eglManager().hasEglContext()) {
        ATRACE_NAME("Bitmap#prepareToDraw task");
        Caches::getInstance().textureCache.prefetch(bitmap);
    }
}

void OpenGLPipeline::invokeFunctor(const RenderThread& thread, Functor* functor) {
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    if (thread.eglManager().hasEglContext()) {
        mode = DrawGlInfo::kModeProcess;
    }
    thread.renderState().invokeFunctor(functor, mode, nullptr);
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
