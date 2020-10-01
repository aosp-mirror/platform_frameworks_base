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

#include "RenderThread.h"

#include "../HardwareBitmapUploader.h"
#include "CanvasContext.h"
#include "DeviceInfo.h"
#include "EglManager.h"
#include "Readback.h"
#include "RenderProxy.h"
#include "VulkanManager.h"
#include "hwui/Bitmap.h"
#include "pipeline/skia/SkiaOpenGLPipeline.h"
#include "pipeline/skia/SkiaVulkanPipeline.h"
#include "renderstate/RenderState.h"
#include "utils/TimeUtils.h"
#include "utils/TraceUtils.h"

#include <GrContextOptions.h>
#include <gl/GrGLInterface.h>

#include <sys/resource.h>
#include <utils/Condition.h>
#include <utils/Log.h>
#include <utils/Mutex.h>
#include <thread>

#include <ui/FatVector.h>

namespace android {
namespace uirenderer {
namespace renderthread {

static bool gHasRenderThreadInstance = false;

static JVMAttachHook gOnStartHook = nullptr;

void RenderThread::frameCallback(int64_t frameTimeNanos, void* data) {
    RenderThread* rt = reinterpret_cast<RenderThread*>(data);
    rt->mVsyncRequested = false;
    if (rt->timeLord().vsyncReceived(frameTimeNanos) && !rt->mFrameCallbackTaskPending) {
        ATRACE_NAME("queue mFrameCallbackTask");
        rt->mFrameCallbackTaskPending = true;
        nsecs_t runAt = (frameTimeNanos + rt->mDispatchFrameDelay);
        rt->queue().postAt(runAt, [=]() { rt->dispatchFrameCallbacks(); });
    }
}

void RenderThread::refreshRateCallback(int64_t vsyncPeriod, void* data) {
    ATRACE_NAME("refreshRateCallback");
    RenderThread* rt = reinterpret_cast<RenderThread*>(data);
    DeviceInfo::get()->onRefreshRateChanged(vsyncPeriod);
    rt->setupFrameInterval();
}

class ChoreographerSource : public VsyncSource {
public:
    ChoreographerSource(RenderThread* renderThread) : mRenderThread(renderThread) {}

    virtual void requestNextVsync() override {
        AChoreographer_postFrameCallback64(mRenderThread->mChoreographer,
                                           RenderThread::frameCallback, mRenderThread);
    }

    virtual void drainPendingEvents() override {
        AChoreographer_handlePendingEvents(mRenderThread->mChoreographer, mRenderThread);
    }

private:
    RenderThread* mRenderThread;
};

class DummyVsyncSource : public VsyncSource {
public:
    DummyVsyncSource(RenderThread* renderThread) : mRenderThread(renderThread) {}

    virtual void requestNextVsync() override {
        mRenderThread->queue().postDelayed(16_ms, [this]() {
            RenderThread::frameCallback(systemTime(SYSTEM_TIME_MONOTONIC), mRenderThread);
        });
    }

    virtual void drainPendingEvents() override {
        RenderThread::frameCallback(systemTime(SYSTEM_TIME_MONOTONIC), mRenderThread);
    }

private:
    RenderThread* mRenderThread;
};

bool RenderThread::hasInstance() {
    return gHasRenderThreadInstance;
}

void RenderThread::setOnStartHook(JVMAttachHook onStartHook) {
    LOG_ALWAYS_FATAL_IF(hasInstance(), "can't set an onStartHook after we've started...");
    gOnStartHook = onStartHook;
}

JVMAttachHook RenderThread::getOnStartHook() {
    return gOnStartHook;
}

RenderThread& RenderThread::getInstance() {
    // This is a pointer because otherwise __cxa_finalize
    // will try to delete it like a Good Citizen but that causes us to crash
    // because we don't want to delete the RenderThread normally.
    static RenderThread* sInstance = new RenderThread();
    gHasRenderThreadInstance = true;
    return *sInstance;
}

RenderThread::RenderThread()
        : ThreadBase()
        , mVsyncSource(nullptr)
        , mVsyncRequested(false)
        , mFrameCallbackTaskPending(false)
        , mRenderState(nullptr)
        , mEglManager(nullptr)
        , mFunctorManager(WebViewFunctorManager::instance())
        , mVkManager(nullptr) {
    Properties::load();
    start("RenderThread");
}

RenderThread::~RenderThread() {
    // Note that if this fatal assertion is removed then member variables must
    // be properly destroyed.
    LOG_ALWAYS_FATAL("Can't destroy the render thread");
}

void RenderThread::initializeChoreographer() {
    LOG_ALWAYS_FATAL_IF(mVsyncSource, "Initializing a second Choreographer?");

    if (!Properties::isolatedProcess) {
        mChoreographer = AChoreographer_create();
        LOG_ALWAYS_FATAL_IF(mChoreographer == nullptr, "Initialization of Choreographer failed");
        AChoreographer_registerRefreshRateCallback(mChoreographer,
                                                   RenderThread::refreshRateCallback, this);

        // Register the FD
        mLooper->addFd(AChoreographer_getFd(mChoreographer), 0, Looper::EVENT_INPUT,
                       RenderThread::choreographerCallback, this);
        mVsyncSource = new ChoreographerSource(this);
    } else {
        mVsyncSource = new DummyVsyncSource(this);
    }
}

void RenderThread::initThreadLocals() {
    setupFrameInterval();
    initializeChoreographer();
    mEglManager = new EglManager();
    mRenderState = new RenderState(*this);
    mVkManager = new VulkanManager();
    mCacheManager = new CacheManager();
}

void RenderThread::setupFrameInterval() {
    nsecs_t frameIntervalNanos = DeviceInfo::getVsyncPeriod();
    mTimeLord.setFrameInterval(frameIntervalNanos);
    mDispatchFrameDelay = static_cast<nsecs_t>(frameIntervalNanos * .25f);
}

void RenderThread::requireGlContext() {
    if (mEglManager->hasEglContext()) {
        return;
    }
    mEglManager->initialize();

    sk_sp<const GrGLInterface> glInterface(GrGLCreateNativeInterface());
    LOG_ALWAYS_FATAL_IF(!glInterface.get());

    GrContextOptions options;
    initGrContextOptions(options);
    auto glesVersion = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    auto size = glesVersion ? strlen(glesVersion) : -1;
    cacheManager().configureContext(&options, glesVersion, size);
    sk_sp<GrContext> grContext(GrContext::MakeGL(std::move(glInterface), options));
    LOG_ALWAYS_FATAL_IF(!grContext.get());
    setGrContext(grContext);
}

void RenderThread::requireVkContext() {
    if (mVkManager->hasVkContext()) {
        return;
    }
    mVkManager->initialize();
    GrContextOptions options;
    initGrContextOptions(options);
    auto vkDriverVersion = mVkManager->getDriverVersion();
    cacheManager().configureContext(&options, &vkDriverVersion, sizeof(vkDriverVersion));
    sk_sp<GrContext> grContext = mVkManager->createContext(options);
    LOG_ALWAYS_FATAL_IF(!grContext.get());
    setGrContext(grContext);
}

void RenderThread::initGrContextOptions(GrContextOptions& options) {
    options.fPreferExternalImagesOverES3 = true;
    options.fDisableDistanceFieldPaths = true;
}

void RenderThread::destroyRenderingContext() {
    mFunctorManager.onContextDestroyed();
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        if (mEglManager->hasEglContext()) {
            setGrContext(nullptr);
            mEglManager->destroy();
        }
    } else {
        if (vulkanManager().hasVkContext()) {
            setGrContext(nullptr);
            vulkanManager().destroy();
        }
    }
}

void RenderThread::dumpGraphicsMemory(int fd) {
    globalProfileData()->dump(fd);

    String8 cachesOutput;
    String8 pipeline;
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::SkiaGL: {
            mCacheManager->dumpMemoryUsage(cachesOutput, mRenderState);
            pipeline.appendFormat("Skia (OpenGL)");
            break;
        }
        case RenderPipelineType::SkiaVulkan: {
            mCacheManager->dumpMemoryUsage(cachesOutput, mRenderState);
            pipeline.appendFormat("Skia (Vulkan)");
            break;
        }
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
            break;
    }

    dprintf(fd, "\n%s\n", cachesOutput.string());
    dprintf(fd, "\nPipeline=%s\n", pipeline.string());
}

Readback& RenderThread::readback() {
    if (!mReadback) {
        mReadback = new Readback(*this);
    }

    return *mReadback;
}

void RenderThread::setGrContext(sk_sp<GrContext> context) {
    mCacheManager->reset(context);
    if (mGrContext) {
        mRenderState->onContextDestroyed();
        mGrContext->releaseResourcesAndAbandonContext();
    }
    mGrContext = std::move(context);
    if (mGrContext) {
        DeviceInfo::setMaxTextureSize(mGrContext->maxRenderTargetSize());
    }
}

int RenderThread::choreographerCallback(int fd, int events, void* data) {
    if (events & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP)) {
        ALOGE("Display event receiver pipe was closed or an error occurred.  "
              "events=0x%x",
              events);
        return 0;  // remove the callback
    }

    if (!(events & Looper::EVENT_INPUT)) {
        ALOGW("Received spurious callback for unhandled poll event.  "
              "events=0x%x",
              events);
        return 1;  // keep the callback
    }
    RenderThread* rt = reinterpret_cast<RenderThread*>(data);
    AChoreographer_handlePendingEvents(rt->mChoreographer, data);

    return 1;
}

void RenderThread::dispatchFrameCallbacks() {
    ATRACE_CALL();
    mFrameCallbackTaskPending = false;

    std::set<IFrameCallback*> callbacks;
    mFrameCallbacks.swap(callbacks);

    if (callbacks.size()) {
        // Assume one of them will probably animate again so preemptively
        // request the next vsync in case it occurs mid-frame
        requestVsync();
        for (std::set<IFrameCallback*>::iterator it = callbacks.begin(); it != callbacks.end();
             it++) {
            (*it)->doFrame();
        }
    }
}

void RenderThread::requestVsync() {
    if (!mVsyncRequested) {
        mVsyncRequested = true;
        mVsyncSource->requestNextVsync();
    }
}

bool RenderThread::threadLoop() {
    setpriority(PRIO_PROCESS, 0, PRIORITY_DISPLAY);
    Looper::setForThread(mLooper);
    if (gOnStartHook) {
        gOnStartHook("RenderThread");
    }
    initThreadLocals();

    while (true) {
        waitForWork();
        processQueue();

        if (mPendingRegistrationFrameCallbacks.size() && !mFrameCallbackTaskPending) {
            mVsyncSource->drainPendingEvents();
            mFrameCallbacks.insert(mPendingRegistrationFrameCallbacks.begin(),
                                   mPendingRegistrationFrameCallbacks.end());
            mPendingRegistrationFrameCallbacks.clear();
            requestVsync();
        }

        if (!mFrameCallbackTaskPending && !mVsyncRequested && mFrameCallbacks.size()) {
            // TODO: Clean this up. This is working around an issue where a combination
            // of bad timing and slow drawing can result in dropping a stale vsync
            // on the floor (correct!) but fails to schedule to listen for the
            // next vsync (oops), so none of the callbacks are run.
            requestVsync();
        }
    }

    return false;
}

void RenderThread::postFrameCallback(IFrameCallback* callback) {
    mPendingRegistrationFrameCallbacks.insert(callback);
}

bool RenderThread::removeFrameCallback(IFrameCallback* callback) {
    size_t erased;
    erased = mFrameCallbacks.erase(callback);
    erased |= mPendingRegistrationFrameCallbacks.erase(callback);
    return erased;
}

void RenderThread::pushBackFrameCallback(IFrameCallback* callback) {
    if (mFrameCallbacks.erase(callback)) {
        mPendingRegistrationFrameCallbacks.insert(callback);
    }
}

sk_sp<Bitmap> RenderThread::allocateHardwareBitmap(SkBitmap& skBitmap) {
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::SkiaVulkan:
            return skiapipeline::SkiaVulkanPipeline::allocateHardwareBitmap(*this, skBitmap);
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
            break;
    }
    return nullptr;
}

bool RenderThread::isCurrent() {
    return gettid() == getInstance().getTid();
}

void RenderThread::preload() {
    // EGL driver is always preloaded only if HWUI renders with GL.
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        std::thread eglInitThread([]() { eglGetDisplay(EGL_DEFAULT_DISPLAY); });
        eglInitThread.detach();
    } else {
        requireVkContext();
    }
    HardwareBitmapUploader::initialize();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
