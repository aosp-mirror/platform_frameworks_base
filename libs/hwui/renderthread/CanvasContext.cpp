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

#include "CanvasContext.h"

#include <private/hwui/DrawGlInfo.h>
#include <strings.h>

#include "EglManager.h"
#include "RenderThread.h"
#include "../Caches.h"
#include "../DeferredLayerUpdater.h"
#include "../RenderState.h"
#include "../LayerRenderer.h"
#include "../OpenGLRenderer.h"
#include "../Stencil.h"

#define TRIM_MEMORY_COMPLETE 80
#define TRIM_MEMORY_UI_HIDDEN 20

namespace android {
namespace uirenderer {
namespace renderthread {

CanvasContext::CanvasContext(RenderThread& thread, bool translucent, RenderNode* rootRenderNode)
        : mRenderThread(thread)
        , mEglManager(thread.eglManager())
        , mEglSurface(EGL_NO_SURFACE)
        , mDirtyRegionsEnabled(false)
        , mOpaque(!translucent)
        , mCanvas(NULL)
        , mHaveNewSurface(false)
        , mRootRenderNode(rootRenderNode) {
}

CanvasContext::~CanvasContext() {
    destroyCanvasAndSurface();
    mRenderThread.removeFrameCallback(this);
}

void CanvasContext::destroyCanvasAndSurface() {
    if (mCanvas) {
        delete mCanvas;
        mCanvas = 0;
    }
    setSurface(NULL);
}

void CanvasContext::setSurface(ANativeWindow* window) {
    mNativeWindow = window;

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (window) {
        mEglSurface = mEglManager.createSurface(window);
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        mDirtyRegionsEnabled = mEglManager.enableDirtyRegions(mEglSurface);
        mHaveNewSurface = true;
        makeCurrent();
    } else {
        mRenderThread.removeFrameCallback(this);
    }
}

void CanvasContext::swapBuffers() {
    mEglManager.swapBuffers(mEglSurface);
    mHaveNewSurface = false;
}

void CanvasContext::requireSurface() {
    LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
            "requireSurface() called but no surface set!");
    makeCurrent();
}

bool CanvasContext::initialize(ANativeWindow* window) {
    if (mCanvas) return false;
    setSurface(window);
    mCanvas = new OpenGLRenderer(mRenderThread.renderState());
    mCanvas->initProperties();
    return true;
}

void CanvasContext::updateSurface(ANativeWindow* window) {
    setSurface(window);
}

void CanvasContext::pauseSurface(ANativeWindow* window) {
    // TODO: For now we just need a fence, in the future suspend any animations
    // and such to prevent from trying to render into this surface
}

void CanvasContext::setup(int width, int height, const Vector3& lightCenter, float lightRadius,
        uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    if (!mCanvas) return;
    mCanvas->setViewport(width, height);
    mCanvas->initLight(lightCenter, lightRadius, ambientShadowAlpha, spotShadowAlpha);
}

void CanvasContext::setOpaque(bool opaque) {
    mOpaque = opaque;
}

void CanvasContext::makeCurrent() {
    // TODO: Figure out why this workaround is needed, see b/13913604
    // In the meantime this matches the behavior of GLRenderer, so it is not a regression
    mHaveNewSurface |= mEglManager.makeCurrent(mEglSurface);
}

void CanvasContext::processLayerUpdate(DeferredLayerUpdater* layerUpdater) {
    bool success = layerUpdater->apply();
    LOG_ALWAYS_FATAL_IF(!success, "Failed to update layer!");
    if (layerUpdater->backingLayer()->deferredUpdateScheduled) {
        mCanvas->pushLayerUpdate(layerUpdater->backingLayer());
    }
}

void CanvasContext::prepareTree(TreeInfo& info) {
    mRenderThread.removeFrameCallback(this);

    info.frameTimeMs = mRenderThread.timeLord().frameTimeMs();
    info.damageAccumulator = &mDamageAccumulator;
    info.renderer = mCanvas;
    mRootRenderNode->prepareTree(info);

    int runningBehind = 0;
    // TODO: This query is moderately expensive, investigate adding some sort
    // of fast-path based off when we last called eglSwapBuffers() as well as
    // last vsync time. Or something.
    mNativeWindow->query(mNativeWindow.get(),
            NATIVE_WINDOW_CONSUMER_RUNNING_BEHIND, &runningBehind);
    info.out.canDrawThisFrame = !runningBehind;

    if (info.out.hasAnimations || !info.out.canDrawThisFrame) {
        if (!info.out.requiresUiRedraw) {
            // If animationsNeedsRedraw is set don't bother posting for an RT anim
            // as we will just end up fighting the UI thread.
            mRenderThread.postFrameCallback(this);
        }
    }
}

void CanvasContext::stopDrawing() {
    mRenderThread.removeFrameCallback(this);
}

void CanvasContext::notifyFramePending() {
    ATRACE_CALL();
    mRenderThread.pushBackFrameCallback(this);
}

void CanvasContext::draw() {
    LOG_ALWAYS_FATAL_IF(!mCanvas || mEglSurface == EGL_NO_SURFACE,
            "drawRenderNode called on a context with no canvas or surface!");

    profiler().markPlaybackStart();

    SkRect dirty;
    mDamageAccumulator.finish(&dirty);

    EGLint width, height;
    mEglManager.beginFrame(mEglSurface, &width, &height);
    if (width != mCanvas->getViewportWidth() || height != mCanvas->getViewportHeight()) {
        mCanvas->setViewport(width, height);
        dirty.setEmpty();
    } else if (!mDirtyRegionsEnabled || mHaveNewSurface) {
        dirty.setEmpty();
    } else {
        if (!dirty.isEmpty() && !dirty.intersect(0, 0, width, height)) {
            ALOGW("Dirty " RECT_STRING " doesn't intersect with 0 0 %d %d ?",
                    SK_RECT_ARGS(dirty), width, height);
            dirty.setEmpty();
        }
        profiler().unionDirty(&dirty);
    }

    status_t status;
    if (!dirty.isEmpty()) {
        status = mCanvas->prepareDirty(dirty.fLeft, dirty.fTop,
                dirty.fRight, dirty.fBottom, mOpaque);
    } else {
        status = mCanvas->prepare(mOpaque);
    }

    Rect outBounds;
    status |= mCanvas->drawRenderNode(mRootRenderNode.get(), outBounds);

    profiler().draw(mCanvas);

    mCanvas->finish();

    profiler().markPlaybackEnd();

    if (status & DrawGlInfo::kStatusDrew) {
        swapBuffers();
    }

    profiler().finishFrame();
}

// Called by choreographer to do an RT-driven animation
void CanvasContext::doFrame() {
    if (CC_UNLIKELY(!mCanvas || mEglSurface == EGL_NO_SURFACE)) {
        return;
    }

    ATRACE_CALL();

    profiler().startFrame();

    TreeInfo info(TreeInfo::MODE_RT_ONLY, mRenderThread.renderState());
    prepareTree(info);
    if (info.out.canDrawThisFrame) {
        draw();
    }
}

void CanvasContext::invokeFunctor(RenderThread& thread, Functor* functor) {
    ATRACE_CALL();
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    if (thread.eglManager().hasEglContext()) {
        thread.eglManager().requireGlContext();
        mode = DrawGlInfo::kModeProcess;
    }

    thread.renderState().invokeFunctor(functor, mode, NULL);
}

void CanvasContext::buildLayer(RenderNode* node) {
    ATRACE_CALL();
    if (!mEglManager.hasEglContext() || !mCanvas) {
        return;
    }
    requireGlContext();
    // buildLayer() will leave the tree in an unknown state, so we must stop drawing
    stopDrawing();

    TreeInfo info(TreeInfo::MODE_FULL, mRenderThread.renderState());
    info.frameTimeMs = mRenderThread.timeLord().frameTimeMs();
    info.damageAccumulator = &mDamageAccumulator;
    info.renderer = mCanvas;
    info.runAnimations = false;
    node->prepareTree(info);
    SkRect ignore;
    mDamageAccumulator.finish(&ignore);
    // Tickle the GENERIC property on node to mark it as dirty for damaging
    // purposes when the frame is actually drawn
    node->setPropertyFieldsDirty(RenderNode::GENERIC);

    mCanvas->flushLayerUpdates();
}

bool CanvasContext::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    requireGlContext();
    layer->apply();
    return LayerRenderer::copyLayer(mRenderThread.renderState(), layer->backingLayer(), bitmap);
}

void CanvasContext::destroyHardwareResources() {
    stopDrawing();
    if (mEglManager.hasEglContext()) {
        requireGlContext();
        mRootRenderNode->destroyHardwareResources();
        Caches::getInstance().flush(Caches::kFlushMode_Layers);
    }
}

void CanvasContext::trimMemory(RenderThread& thread, int level) {
    // No context means nothing to free
    if (!thread.eglManager().hasEglContext()) return;

    thread.eglManager().requireGlContext();
    if (level >= TRIM_MEMORY_COMPLETE) {
        Caches::getInstance().flush(Caches::kFlushMode_Full);
        thread.eglManager().destroy();
    } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
        Caches::getInstance().flush(Caches::kFlushMode_Moderate);
    }
}

void CanvasContext::runWithGlContext(RenderTask* task) {
    requireGlContext();
    task->run();
}

Layer* CanvasContext::createRenderLayer(int width, int height) {
    requireSurface();
    return LayerRenderer::createRenderLayer(mRenderThread.renderState(), width, height);
}

Layer* CanvasContext::createTextureLayer() {
    requireSurface();
    return LayerRenderer::createTextureLayer(mRenderThread.renderState());
}

void CanvasContext::requireGlContext() {
    mEglManager.requireGlContext();
}

void CanvasContext::setTextureAtlas(RenderThread& thread,
        const sp<GraphicBuffer>& buffer, int64_t* map, size_t mapSize) {
    thread.eglManager().setTextureAtlas(buffer, map, mapSize);
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
