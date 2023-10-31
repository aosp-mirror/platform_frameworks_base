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

#include <SkBitmap.h>
#include <SkImage.h>
#include <SkPicture.h>
#include <gui/TraceUtils.h>
#include <pthread.h>
#include <ui/GraphicBufferAllocator.h>

#include "DeferredLayerUpdater.h"
#include "DisplayList.h"
#include "Properties.h"
#include "Readback.h"
#include "Rect.h"
#include "WebViewFunctorManager.h"
#include "renderthread/CanvasContext.h"
#include "renderthread/RenderTask.h"
#include "renderthread/RenderThread.h"
#include "utils/Macros.h"
#include "utils/TimeUtils.h"

namespace android {
namespace uirenderer {
namespace renderthread {

RenderProxy::RenderProxy(bool translucent, RenderNode* rootRenderNode,
                         IContextFactory* contextFactory)
        : mRenderThread(RenderThread::getInstance()), mContext(nullptr) {
    pid_t uiThreadId = pthread_gettid_np(pthread_self());
    pid_t renderThreadId = getRenderThreadTid();
    mContext = mRenderThread.queue().runSync([=, this]() -> CanvasContext* {
        CanvasContext* context = CanvasContext::create(mRenderThread, translucent, rootRenderNode,
                                                       contextFactory, uiThreadId, renderThreadId);
        if (context != nullptr) {
            mRenderThread.queue().post([=] { context->startHintSession(); });
        }
        return context;
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
        bool needsRedraw = Properties::load();
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

void RenderProxy::setHardwareBuffer(AHardwareBuffer* buffer) {
    if (buffer) {
        AHardwareBuffer_acquire(buffer);
    }
    mRenderThread.queue().post([this, hardwareBuffer = buffer]() mutable {
        mContext->setHardwareBuffer(hardwareBuffer);
        if (hardwareBuffer) {
            AHardwareBuffer_release(hardwareBuffer);
        }
    });
}

void RenderProxy::setSurface(ANativeWindow* window, bool enableTimeout) {
    if (window) { ANativeWindow_acquire(window); }
    mRenderThread.queue().post([this, win = window, enableTimeout]() mutable {
        mContext->setSurface(win, enableTimeout);
        if (win) { ANativeWindow_release(win); }
    });
}

void RenderProxy::setSurfaceControl(ASurfaceControl* surfaceControl) {
    auto funcs = mRenderThread.getASurfaceControlFunctions();
    if (surfaceControl) {
        funcs.acquireFunc(surfaceControl);
    }
    mRenderThread.queue().post([this, control = surfaceControl, funcs]() mutable {
        mContext->setSurfaceControl(control);
        if (control) {
            funcs.releaseFunc(control);
        }
    });
}

void RenderProxy::allocateBuffers() {
    mRenderThread.queue().post([=]() { mContext->allocateBuffers(); });
}

bool RenderProxy::pause() {
    return mRenderThread.queue().runSync([this]() -> bool { return mContext->pauseSurface(); });
}

void RenderProxy::setStopped(bool stopped) {
    mRenderThread.queue().runSync([this, stopped]() { mContext->setStopped(stopped); });
}

void RenderProxy::setLightAlpha(uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    mRenderThread.queue().post(
            [=]() { mContext->setLightAlpha(ambientShadowAlpha, spotShadowAlpha); });
}

void RenderProxy::setLightGeometry(const Vector3& lightCenter, float lightRadius) {
    mRenderThread.queue().post([=]() { mContext->setLightGeometry(lightCenter, lightRadius); });
}

void RenderProxy::setOpaque(bool opaque) {
    mRenderThread.queue().post([=]() { mContext->setOpaque(opaque); });
}

float RenderProxy::setColorMode(ColorMode mode) {
    // We only need to figure out what the renderer supports for HDR, otherwise this can stay
    // an async call since we already know the return value
    if (mode == ColorMode::Hdr || mode == ColorMode::Hdr10) {
        return mRenderThread.queue().runSync(
                [=]() -> float { return mContext->setColorMode(mode); });
    } else {
        mRenderThread.queue().post([=]() { mContext->setColorMode(mode); });
        return 1.f;
    }
}

void RenderProxy::setRenderSdrHdrRatio(float ratio) {
    mDrawFrameTask.setRenderSdrHdrRatio(ratio);
}

int64_t* RenderProxy::frameInfo() {
    return mDrawFrameTask.frameInfo();
}

void RenderProxy::forceDrawNextFrame() {
    mDrawFrameTask.forceDrawNextFrame();
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

void RenderProxy::destroyFunctor(int functor) {
    ATRACE_CALL();
    RenderThread& thread = RenderThread::getInstance();
    thread.queue().post([=]() { WebViewFunctorManager::instance().destroyFunctor(functor); });
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
    ATRACE_NAME("TextureView#getBitmap");
    auto& thread = RenderThread::getInstance();
    return thread.queue().runSync([&]() -> bool {
        return thread.readback().copyLayerInto(layer, &bitmap) == CopyResult::Success;
    });
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
        const auto trimLevel = static_cast<TrimLevel>(level);
        thread.queue().post([&thread, trimLevel]() { thread.trimMemory(trimLevel); });
    }
}

void RenderProxy::trimCaches(int level) {
    // Avoid creating a RenderThread to do a trimMemory.
    if (RenderThread::hasInstance()) {
        RenderThread& thread = RenderThread::getInstance();
        const auto trimLevel = static_cast<CacheTrimLevel>(level);
        thread.queue().post([&thread, trimLevel]() { thread.trimCaches(trimLevel); });
    }
}

void RenderProxy::purgeCaches() {
    if (RenderThread::hasInstance()) {
        RenderThread& thread = RenderThread::getInstance();
        thread.queue().post([&thread]() {
            if (thread.getGrContext()) {
                thread.cacheManager().trimMemory(TrimLevel::COMPLETE);
            }
        });
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

int RenderProxy::maxTextureSize() {
    static int maxTextureSize = RenderThread::getInstance().queue().runSync(
            []() { return DeviceInfo::get()->maxTextureSize(); });
    return maxTextureSize;
}

void RenderProxy::stopDrawing() {
    mRenderThread.queue().runSync([this]() { mContext->stopDrawing(); });
}

void RenderProxy::notifyFramePending() {
    mRenderThread.queue().post([this]() { mContext->notifyFramePending(); });
}

void RenderProxy::notifyCallbackPending() {
    mRenderThread.queue().post([this]() { mContext->sendLoadResetHint(); });
}

void RenderProxy::notifyExpensiveFrame() {
    mRenderThread.queue().post([this]() { mContext->sendLoadIncreaseHint(); });
}

void RenderProxy::dumpProfileInfo(int fd, int dumpFlags) {
    mRenderThread.queue().runSync([&]() {
        std::lock_guard lock(mRenderThread.getJankDataMutex());
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
    mRenderThread.queue().runSync([=]() {
        std::lock_guard lock(mRenderThread.getJankDataMutex());
        mContext->resetFrameStats();
    });
}

uint32_t RenderProxy::frameTimePercentile(int percentile) {
    return mRenderThread.queue().runSync([&]() -> auto {
        std::lock_guard lock(mRenderThread.globalProfileData().getDataMutex());
        return mRenderThread.globalProfileData()->findPercentile(percentile);
    });
}

void RenderProxy::dumpGraphicsMemory(int fd, bool includeProfileData, bool resetProfile) {
    if (RenderThread::hasInstance()) {
        auto& thread = RenderThread::getInstance();
        thread.queue().runSync([&]() {
            thread.dumpGraphicsMemory(fd, includeProfileData);
            if (resetProfile) {
                thread.globalProfileData()->reset();
            }
        });
    }
    if (!Properties::isolatedProcess) {
        std::string grallocInfo;
        GraphicBufferAllocator::getInstance().dump(grallocInfo);
        dprintf(fd, "%s\n", grallocInfo.c_str());
    }
}

void RenderProxy::getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage) {
    if (RenderThread::hasInstance()) {
        auto& thread = RenderThread::getInstance();
        thread.queue().runSync([&]() { thread.getMemoryUsage(cpuUsage, gpuUsage); });
    }
}

void RenderProxy::setProcessStatsBuffer(int fd) {
    auto& rt = RenderThread::getInstance();
    rt.queue().post([&rt, fd = dup(fd)]() {
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

void RenderProxy::setHardwareBufferRenderParams(const HardwareBufferRenderParams& params) {
    mDrawFrameTask.setHardwareBufferRenderParams(params);
}

void RenderProxy::setPictureCapturedCallback(
        const std::function<void(sk_sp<SkPicture>&&)>& callback) {
    mRenderThread.queue().post(
            [this, cb = callback]() { mContext->setPictureCapturedCallback(cb); });
}

void RenderProxy::setASurfaceTransactionCallback(
        const std::function<bool(int64_t, int64_t, int64_t)>& callback) {
    mRenderThread.queue().post(
            [this, cb = callback]() { mContext->setASurfaceTransactionCallback(cb); });
}

void RenderProxy::setPrepareSurfaceControlForWebviewCallback(
        const std::function<void()>& callback) {
    mRenderThread.queue().post(
            [this, cb = callback]() { mContext->setPrepareSurfaceControlForWebviewCallback(cb); });
}

void RenderProxy::setFrameCallback(
        std::function<std::function<void(bool)>(int32_t, int64_t)>&& callback) {
    mDrawFrameTask.setFrameCallback(std::move(callback));
}

void RenderProxy::setFrameCommitCallback(std::function<void(bool)>&& callback) {
    mDrawFrameTask.setFrameCommitCallback(std::move(callback));
}

void RenderProxy::setFrameCompleteCallback(std::function<void()>&& callback) {
    mDrawFrameTask.setFrameCompleteCallback(std::move(callback));
}

void RenderProxy::addFrameMetricsObserver(FrameMetricsObserver* observerPtr) {
    mRenderThread.queue().post([this, observer = sp{observerPtr}]() {
        mContext->addFrameMetricsObserver(observer.get());
    });
}

void RenderProxy::removeFrameMetricsObserver(FrameMetricsObserver* observerPtr) {
    mRenderThread.queue().post([this, observer = sp{observerPtr}]() {
        mContext->removeFrameMetricsObserver(observer.get());
    });
}

void RenderProxy::setForceDark(ForceDarkType type) {
    mRenderThread.queue().post([this, type]() { mContext->setForceDark(type); });
}

void RenderProxy::copySurfaceInto(ANativeWindow* window, std::shared_ptr<CopyRequest>&& request) {
    auto& thread = RenderThread::getInstance();
    ANativeWindow_acquire(window);
    thread.queue().post([&thread, window, request = std::move(request)] {
        thread.readback().copySurfaceInto(window, request);
        ANativeWindow_release(window);
    });
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
    nsecs_t timeToNextVsync = estimatedNextVsync - systemTime(SYSTEM_TIME_MONOTONIC);
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

int RenderProxy::copyHWBitmapInto(Bitmap* hwBitmap, SkBitmap* bitmap) {
    ATRACE_NAME("HardwareBitmap readback");
    RenderThread& thread = RenderThread::getInstance();
    if (gettid() == thread.getTid()) {
        // TODO: fix everything that hits this. We should never be triggering a readback ourselves.
        return (int)thread.readback().copyHWBitmapInto(hwBitmap, bitmap);
    } else {
        return thread.queue().runSync(
                [&]() -> int { return (int)thread.readback().copyHWBitmapInto(hwBitmap, bitmap); });
    }
}

int RenderProxy::copyImageInto(const sk_sp<SkImage>& image, SkBitmap* bitmap) {
    RenderThread& thread = RenderThread::getInstance();
    if (gettid() == thread.getTid()) {
        // TODO: fix everything that hits this. We should never be triggering a readback ourselves.
        return (int)thread.readback().copyImageInto(image, bitmap);
    } else {
        return thread.queue().runSync(
                [&]() -> int { return (int)thread.readback().copyImageInto(image, bitmap); });
    }
}

void RenderProxy::disableVsync() {
    Properties::disableVsync = true;
}

void RenderProxy::preload() {
    // Create RenderThread object and start the thread. Then preload Vulkan/EGL driver.
    auto& thread = RenderThread::getInstance();
    thread.queue().post([&thread]() { thread.preload(); });
}

void RenderProxy::setRtAnimationsEnabled(bool enabled) {
    if (RenderThread::hasInstance()) {
        RenderThread::getInstance().queue().post(
                [enabled]() { Properties::enableRTAnimations = enabled; });
    } else {
        Properties::enableRTAnimations = enabled;
    }
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
