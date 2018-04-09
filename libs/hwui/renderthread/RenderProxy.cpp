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

#include "RenderProxy.h"

#include "DeferredLayerUpdater.h"
#include "DisplayList.h"
#include "Properties.h"
#include "Readback.h"
#include "Rect.h"
#include "pipeline/skia/VectorDrawableAtlas.h"
#include "renderstate/RenderState.h"
#include "renderthread/CanvasContext.h"
#include "renderthread/EglManager.h"
#include "renderthread/RenderTask.h"
#include "renderthread/RenderThread.h"
#include "utils/Macros.h"
#include "utils/TimeUtils.h"

#include <ui/GraphicBuffer.h>

namespace android {
namespace uirenderer {
namespace renderthread {

RenderProxy::RenderProxy(bool translucent, RenderNode* rootRenderNode,
                         IContextFactory* contextFactory)
        : mRenderThread(RenderThread::getInstance()), mContext(nullptr) {
    mContext = mRenderThread.queue().runSync([&]() -> CanvasContext* {
        return CanvasContext::create(mRenderThread, translucent, rootRenderNode, contextFactory);
    });
    mDrawFrameTask.setContext(&mRenderThread, mContext, rootRenderNode);
}

RenderProxy::~RenderProxy() {
    destroyContext();
}

void RenderProxy::destroyContext() {
    if (mContext) {
        mDrawFrameTask.setContext(nullptr, nullptr, nullptr);
        // This is also a fence as we need to be certain that there are no
        // outstanding mDrawFrame tasks posted before it is destroyed
        mRenderThread.queue().runSync([this]() { delete mContext; });
        mContext = nullptr;
    }
}

void RenderProxy::setSwapBehavior(SwapBehavior swapBehavior) {
    mRenderThread.queue().post([this, swapBehavior]() { mContext->setSwapBehavior(swapBehavior); });
}

bool RenderProxy::loadSystemProperties() {
    return mRenderThread.queue().runSync([this]() -> bool {
        bool needsRedraw = false;
        if (Caches::hasInstance()) {
            needsRedraw = Properties::load();
        }
        if (mContext->profiler().consumeProperties()) {
            needsRedraw = true;
        }
        return needsRedraw;
    });
}

void RenderProxy::setName(const char* name) {
    // block since name/value pointers owned by caller
    // TODO: Support move arguments
    mRenderThread.queue().runSync([this, name]() { mContext->setName(std::string(name)); });
}

void RenderProxy::initialize(const sp<Surface>& surface) {
    mRenderThread.queue().post(
            [ this, surf = surface ]() mutable { mContext->setSurface(std::move(surf)); });
}

void RenderProxy::updateSurface(const sp<Surface>& surface) {
    mRenderThread.queue().post(
            [ this, surf = surface ]() mutable { mContext->setSurface(std::move(surf)); });
}

bool RenderProxy::pauseSurface(const sp<Surface>& surface) {
    return mRenderThread.queue().runSync([this]() -> bool { return mContext->pauseSurface(); });
}

void RenderProxy::setStopped(bool stopped) {
    mRenderThread.queue().runSync([this, stopped]() { mContext->setStopped(stopped); });
}

void RenderProxy::setup(float lightRadius, uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    mRenderThread.queue().post(
            [=]() { mContext->setup(lightRadius, ambientShadowAlpha, spotShadowAlpha); });
}

void RenderProxy::setLightCenter(const Vector3& lightCenter) {
    mRenderThread.queue().post([=]() { mContext->setLightCenter(lightCenter); });
}

void RenderProxy::setOpaque(bool opaque) {
    mRenderThread.queue().post([=]() { mContext->setOpaque(opaque); });
}

void RenderProxy::setWideGamut(bool wideGamut) {
    mRenderThread.queue().post([=]() { mContext->setWideGamut(wideGamut); });
}

int64_t* RenderProxy::frameInfo() {
    return mDrawFrameTask.frameInfo();
}

int RenderProxy::syncAndDrawFrame() {
    return mDrawFrameTask.drawFrame();
}

void RenderProxy::destroy() {
    // destroyCanvasAndSurface() needs a fence as when it returns the
    // underlying BufferQueue is going to be released from under
    // the render thread.
    mRenderThread.queue().runSync([=]() { mContext->destroy(); });
}

void RenderProxy::invokeFunctor(Functor* functor, bool waitForCompletion) {
    ATRACE_CALL();
    RenderThread& thread = RenderThread::getInstance();
    auto invoke = [&thread, functor]() { CanvasContext::invokeFunctor(thread, functor); };
    if (waitForCompletion) {
        // waitForCompletion = true is expected to be fairly rare and only
        // happen in destruction. Thus it should be fine to temporarily
        // create a Mutex
        thread.queue().runSync(std::move(invoke));
    } else {
        thread.queue().post(std::move(invoke));
    }
}

DeferredLayerUpdater* RenderProxy::createTextureLayer() {
    return mRenderThread.queue().runSync([this]() -> auto {
        return mContext->createTextureLayer();
    });
}

void RenderProxy::buildLayer(RenderNode* node) {
    mRenderThread.queue().runSync([&]() { mContext->buildLayer(node); });
}

bool RenderProxy::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap& bitmap) {
    return mRenderThread.queue().runSync(
            [&]() -> bool { return mContext->copyLayerInto(layer, &bitmap); });
}

void RenderProxy::pushLayerUpdate(DeferredLayerUpdater* layer) {
    mDrawFrameTask.pushLayerUpdate(layer);
}

void RenderProxy::cancelLayerUpdate(DeferredLayerUpdater* layer) {
    mDrawFrameTask.removeLayerUpdate(layer);
}

void RenderProxy::detachSurfaceTexture(DeferredLayerUpdater* layer) {
    return mRenderThread.queue().runSync([&]() { layer->detachSurfaceTexture(); });
}

void RenderProxy::destroyHardwareResources() {
    return mRenderThread.queue().runSync([&]() { mContext->destroyHardwareResources(); });
}

void RenderProxy::trimMemory(int level) {
    // Avoid creating a RenderThread to do a trimMemory.
    if (RenderThread::hasInstance()) {
        RenderThread& thread = RenderThread::getInstance();
        thread.queue().post([&thread, level]() { CanvasContext::trimMemory(thread, level); });
    }
}

void RenderProxy::overrideProperty(const char* name, const char* value) {
    // expensive, but block here since name/value pointers owned by caller
    RenderThread::getInstance().queue().runSync(
            [&]() { Properties::overrideProperty(name, value); });
}

void RenderProxy::fence() {
    mRenderThread.queue().runSync([]() {});
}

void RenderProxy::staticFence() {
    RenderThread::getInstance().queue().runSync([]() {});
}

void RenderProxy::stopDrawing() {
    mRenderThread.queue().runSync([this]() { mContext->stopDrawing(); });
}

void RenderProxy::notifyFramePending() {
    mRenderThread.queue().post([this]() { mContext->notifyFramePending(); });
}

void RenderProxy::dumpProfileInfo(int fd, int dumpFlags) {
    mRenderThread.queue().runSync([&]() {
        mContext->profiler().dumpData(fd);
        if (dumpFlags & DumpFlags::FrameStats) {
            mContext->dumpFrames(fd);
        }
        if (dumpFlags & DumpFlags::JankStats) {
            mRenderThread.globalProfileData()->dump(fd);
        }
        if (dumpFlags & DumpFlags::Reset) {
            mContext->resetFrameStats();
        }
    });
}

void RenderProxy::resetProfileInfo() {
    mRenderThread.queue().runSync([=]() { mContext->resetFrameStats(); });
}

uint32_t RenderProxy::frameTimePercentile(int percentile) {
    return mRenderThread.queue().runSync([&]() -> auto {
        return mRenderThread.globalProfileData()->findPercentile(percentile);
    });
}

void RenderProxy::dumpGraphicsMemory(int fd) {
    auto& thread = RenderThread::getInstance();
    thread.queue().runSync([&]() { thread.dumpGraphicsMemory(fd); });
}

void RenderProxy::setProcessStatsBuffer(int fd) {
    auto& rt = RenderThread::getInstance();
    rt.queue().post([&rt, fd = dup(fd) ]() {
        rt.globalProfileData().switchStorageToAshmem(fd);
        close(fd);
    });
}

void RenderProxy::rotateProcessStatsBuffer() {
    auto& rt = RenderThread::getInstance();
    rt.queue().post([&rt]() { rt.globalProfileData().rotateStorage(); });
}

int RenderProxy::getRenderThreadTid() {
    return mRenderThread.getTid();
}

void RenderProxy::addRenderNode(RenderNode* node, bool placeFront) {
    mRenderThread.queue().post([=]() { mContext->addRenderNode(node, placeFront); });
}

void RenderProxy::removeRenderNode(RenderNode* node) {
    mRenderThread.queue().post([=]() { mContext->removeRenderNode(node); });
}

void RenderProxy::drawRenderNode(RenderNode* node) {
    mRenderThread.queue().runSync([=]() { mContext->prepareAndDraw(node); });
}

void RenderProxy::setContentDrawBounds(int left, int top, int right, int bottom) {
    mDrawFrameTask.setContentDrawBounds(left, top, right, bottom);
}

void RenderProxy::setFrameCallback(std::function<void(int64_t)>&& callback) {
    mDrawFrameTask.setFrameCallback(std::move(callback));
}

void RenderProxy::addFrameMetricsObserver(FrameMetricsObserver* observerPtr) {
    mRenderThread.queue().post([ this, observer = sp{observerPtr} ]() {
        mContext->addFrameMetricsObserver(observer.get());
    });
}

void RenderProxy::removeFrameMetricsObserver(FrameMetricsObserver* observerPtr) {
    mRenderThread.queue().post([ this, observer = sp{observerPtr} ]() {
        mContext->removeFrameMetricsObserver(observer.get());
    });
}

int RenderProxy::copySurfaceInto(sp<Surface>& surface, int left, int top, int right, int bottom,
                                 SkBitmap* bitmap) {
    auto& thread = RenderThread::getInstance();
    return static_cast<int>(thread.queue().runSync([&]() -> auto {
        return thread.readback().copySurfaceInto(*surface, Rect(left, top, right, bottom), bitmap);
    }));
}

void RenderProxy::prepareToDraw(Bitmap& bitmap) {
    // If we haven't spun up a hardware accelerated window yet, there's no
    // point in precaching these bitmaps as it can't impact jank.
    // We also don't know if we even will spin up a hardware-accelerated
    // window or not.
    if (!RenderThread::hasInstance()) return;
    RenderThread* renderThread = &RenderThread::getInstance();
    bitmap.ref();
    auto task = [renderThread, &bitmap]() {
        CanvasContext::prepareToDraw(*renderThread, &bitmap);
        bitmap.unref();
    };
    nsecs_t lastVsync = renderThread->timeLord().latestVsync();
    nsecs_t estimatedNextVsync = lastVsync + renderThread->timeLord().frameIntervalNanos();
    nsecs_t timeToNextVsync = estimatedNextVsync - systemTime(CLOCK_MONOTONIC);
    // We expect the UI thread to take 4ms and for RT to be active from VSYNC+4ms to
    // VSYNC+12ms or so, so aim for the gap during which RT is expected to
    // be idle
    // TODO: Make this concept a first-class supported thing? RT could use
    // knowledge of pending draws to better schedule this task
    if (timeToNextVsync > -6_ms && timeToNextVsync < 1_ms) {
        renderThread->queue().postAt(estimatedNextVsync + 8_ms, task);
    } else {
        renderThread->queue().post(task);
    }
}

sk_sp<Bitmap> RenderProxy::allocateHardwareBitmap(SkBitmap& bitmap) {
    auto& thread = RenderThread::getInstance();
    return thread.queue().runSync([&]() -> auto { return thread.allocateHardwareBitmap(bitmap); });
}

int RenderProxy::copyGraphicBufferInto(GraphicBuffer* buffer, SkBitmap* bitmap) {
    RenderThread& thread = RenderThread::getInstance();
    if (Properties::isSkiaEnabled() && gettid() == thread.getTid()) {
        // TODO: fix everything that hits this. We should never be triggering a readback ourselves.
        return (int)thread.readback().copyGraphicBufferInto(buffer, bitmap);
    } else {
        return thread.queue().runSync([&]() -> int {
            return (int)thread.readback().copyGraphicBufferInto(buffer, bitmap);
        });
    }
}

void RenderProxy::onBitmapDestroyed(uint32_t pixelRefId) {
    if (!RenderThread::hasInstance()) return;
    RenderThread& thread = RenderThread::getInstance();
    thread.queue().post(
            [&thread, pixelRefId]() { thread.renderState().onBitmapDestroyed(pixelRefId); });
}

void RenderProxy::disableVsync() {
    Properties::disableVsync = true;
}

void RenderProxy::repackVectorDrawableAtlas() {
    RenderThread& thread = RenderThread::getInstance();
    thread.queue().post([&thread]() {
        // The context may be null if trimMemory executed, but then the atlas was deleted too.
        if (thread.getGrContext() != nullptr) {
            thread.cacheManager().acquireVectorDrawableAtlas()->repackIfNeeded(
                    thread.getGrContext());
        }
    });
}

void RenderProxy::releaseVDAtlasEntries() {
    RenderThread& thread = RenderThread::getInstance();
    thread.queue().post([&thread]() {
        // The context may be null if trimMemory executed, but then the atlas was deleted too.
        if (thread.getGrContext() != nullptr) {
            thread.cacheManager().acquireVectorDrawableAtlas()->delayedReleaseEntries();
        }
    });
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
