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
#include "Properties.h"
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

#include <dlfcn.h>
#include <sys/resource.h>
#include <utils/Condition.h>
#include <utils/Log.h>
#include <utils/Mutex.h>
#include <thread>

#include <android-base/properties.h>
#include <ui/FatVector.h>

namespace android {
namespace uirenderer {
namespace renderthread {

static bool gHasRenderThreadInstance = false;

static JVMAttachHook gOnStartHook = nullptr;

ASurfaceControlFunctions::ASurfaceControlFunctions() {
    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    createFunc = (ASC_create)dlsym(handle_, "ASurfaceControl_create");
    LOG_ALWAYS_FATAL_IF(createFunc == nullptr,
                        "Failed to find required symbol ASurfaceControl_create!");

    acquireFunc = (ASC_acquire) dlsym(handle_, "ASurfaceControl_acquire");
    LOG_ALWAYS_FATAL_IF(acquireFunc == nullptr,
            "Failed to find required symbol ASurfaceControl_acquire!");

    releaseFunc = (ASC_release) dlsym(handle_, "ASurfaceControl_release");
    LOG_ALWAYS_FATAL_IF(releaseFunc == nullptr,
            "Failed to find required symbol ASurfaceControl_release!");

    registerListenerFunc = (ASC_registerSurfaceStatsListener) dlsym(handle_,
            "ASurfaceControl_registerSurfaceStatsListener");
    LOG_ALWAYS_FATAL_IF(registerListenerFunc == nullptr,
            "Failed to find required symbol ASurfaceControl_registerSurfaceStatsListener!");

    unregisterListenerFunc = (ASC_unregisterSurfaceStatsListener) dlsym(handle_,
            "ASurfaceControl_unregisterSurfaceStatsListener");
    LOG_ALWAYS_FATAL_IF(unregisterListenerFunc == nullptr,
            "Failed to find required symbol ASurfaceControl_unregisterSurfaceStatsListener!");

    getAcquireTimeFunc = (ASCStats_getAcquireTime) dlsym(handle_,
            "ASurfaceControlStats_getAcquireTime");
    LOG_ALWAYS_FATAL_IF(getAcquireTimeFunc == nullptr,
            "Failed to find required symbol ASurfaceControlStats_getAcquireTime!");

    getFrameNumberFunc = (ASCStats_getFrameNumber) dlsym(handle_,
            "ASurfaceControlStats_getFrameNumber");
    LOG_ALWAYS_FATAL_IF(getFrameNumberFunc == nullptr,
            "Failed to find required symbol ASurfaceControlStats_getFrameNumber!");

    transactionCreateFunc = (AST_create)dlsym(handle_, "ASurfaceTransaction_create");
    LOG_ALWAYS_FATAL_IF(transactionCreateFunc == nullptr,
                        "Failed to find required symbol ASurfaceTransaction_create!");

    transactionDeleteFunc = (AST_delete)dlsym(handle_, "ASurfaceTransaction_delete");
    LOG_ALWAYS_FATAL_IF(transactionDeleteFunc == nullptr,
                        "Failed to find required symbol ASurfaceTransaction_delete!");

    transactionApplyFunc = (AST_apply)dlsym(handle_, "ASurfaceTransaction_apply");
    LOG_ALWAYS_FATAL_IF(transactionApplyFunc == nullptr,
                        "Failed to find required symbol ASurfaceTransaction_apply!");

    transactionSetVisibilityFunc =
            (AST_setVisibility)dlsym(handle_, "ASurfaceTransaction_setVisibility");
    LOG_ALWAYS_FATAL_IF(transactionSetVisibilityFunc == nullptr,
                        "Failed to find required symbol ASurfaceTransaction_setVisibility!");
}

void RenderThread::frameCallback(int64_t frameTimeNanos, void* data) {
    RenderThread* rt = reinterpret_cast<RenderThread*>(data);
    int64_t vsyncId = AChoreographer_getVsyncId(rt->mChoreographer);
    int64_t frameDeadline = AChoreographer_getFrameDeadline(rt->mChoreographer);
    int64_t frameInterval = AChoreographer_getFrameInterval(rt->mChoreographer);
    rt->mVsyncRequested = false;
    if (rt->timeLord().vsyncReceived(frameTimeNanos, frameTimeNanos, vsyncId, frameDeadline,
            frameInterval) && !rt->mFrameCallbackTaskPending) {
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
    [[clang::no_destroy]] static sp<RenderThread> sInstance = []() {
        sp<RenderThread> thread = sp<RenderThread>::make();
        thread->start("RenderThread");
        return thread;
    }();
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
        , mGlobalProfileData(mJankDataMutex) {
    Properties::load();
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
    mVkManager = VulkanManager::getInstance();
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
    sk_sp<GrDirectContext> grContext(GrDirectContext::MakeGL(std::move(glInterface), options));
    LOG_ALWAYS_FATAL_IF(!grContext.get());
    setGrContext(grContext);
}

void RenderThread::requireVkContext() {
    // the getter creates the context in the event it had been destroyed by destroyRenderingContext
    // Also check if we have a GrContext before returning fast. VulkanManager may be shared with
    // the HardwareBitmapUploader which initializes the Vk context without persisting the GrContext
    // in the rendering thread.
    if (vulkanManager().hasVkContext() && mGrContext) {
        return;
    }
    mVkManager->initialize();
    GrContextOptions options;
    initGrContextOptions(options);
    auto vkDriverVersion = mVkManager->getDriverVersion();
    cacheManager().configureContext(&options, &vkDriverVersion, sizeof(vkDriverVersion));
    sk_sp<GrDirectContext> grContext = mVkManager->createContext(options);
    LOG_ALWAYS_FATAL_IF(!grContext.get());
    setGrContext(grContext);
}

void RenderThread::initGrContextOptions(GrContextOptions& options) {
    options.fPreferExternalImagesOverES3 = true;
    options.fDisableDistanceFieldPaths = true;
    if (android::base::GetBoolProperty(PROPERTY_REDUCE_OPS_TASK_SPLITTING, true)) {
        options.fReduceOpsTaskSplitting = GrContextOptions::Enable::kYes;
    } else {
        options.fReduceOpsTaskSplitting = GrContextOptions::Enable::kNo;
    }
}

void RenderThread::destroyRenderingContext() {
    mFunctorManager.onContextDestroyed();
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        if (mEglManager->hasEglContext()) {
            setGrContext(nullptr);
            mEglManager->destroy();
        }
    } else {
        setGrContext(nullptr);
        mVkManager.clear();
    }
}

VulkanManager& RenderThread::vulkanManager() {
    if (!mVkManager.get()) {
        mVkManager = VulkanManager::getInstance();
    }
    return *mVkManager.get();
}

static const char* pipelineToString() {
    switch (auto renderType = Properties::getRenderPipelineType()) {
        case RenderPipelineType::SkiaGL:
            return "Skia (OpenGL)";
        case RenderPipelineType::SkiaVulkan:
            return "Skia (Vulkan)";
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
    }
}

void RenderThread::dumpGraphicsMemory(int fd, bool includeProfileData) {
    if (includeProfileData) {
        globalProfileData()->dump(fd);
    }

    String8 cachesOutput;
    mCacheManager->dumpMemoryUsage(cachesOutput, mRenderState);
    dprintf(fd, "\nPipeline=%s\n%s\n", pipelineToString(), cachesOutput.string());
}

void RenderThread::getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage) {
    mCacheManager->getMemoryUsage(cpuUsage, gpuUsage);
}

Readback& RenderThread::readback() {
    if (!mReadback) {
        mReadback = new Readback(*this);
    }

    return *mReadback;
}

void RenderThread::setGrContext(sk_sp<GrDirectContext> context) {
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

sk_sp<GrDirectContext> RenderThread::requireGrContext() {
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        requireGlContext();
    } else {
        requireVkContext();
    }
    return mGrContext;
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
