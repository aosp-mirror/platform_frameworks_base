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

#include "CanvasContext.h"
#include "DeviceInfo.h"
#include "EglManager.h"
#include "OpenGLReadback.h"
#include "RenderProxy.h"
#include "VulkanManager.h"
#include "hwui/Bitmap.h"
#include "pipeline/skia/SkiaOpenGLPipeline.h"
#include "pipeline/skia/SkiaOpenGLReadback.h"
#include "pipeline/skia/SkiaVulkanPipeline.h"
#include "pipeline/skia/SkiaVulkanReadback.h"
#include "renderstate/RenderState.h"
#include "renderthread/OpenGLPipeline.h"
#include "utils/FatVector.h"
#include "utils/TimeUtils.h"

#include <gui/DisplayEventReceiver.h>
#include <sys/resource.h>
#include <utils/Condition.h>
#include <utils/Log.h>
#include <utils/Mutex.h>

namespace android {
namespace uirenderer {
namespace renderthread {

// Number of events to read at a time from the DisplayEventReceiver pipe.
// The value should be large enough that we can quickly drain the pipe
// using just a few large reads.
static const size_t EVENT_BUFFER_SIZE = 100;

// Slight delay to give the UI time to push us a new frame before we replay
static const nsecs_t DISPATCH_FRAME_CALLBACKS_DELAY = milliseconds_to_nanoseconds(4);

static bool gHasRenderThreadInstance = false;

static void (*gOnStartHook)() = nullptr;

class DisplayEventReceiverWrapper : public VsyncSource {
public:
    DisplayEventReceiverWrapper(std::unique_ptr<DisplayEventReceiver>&& receiver)
            : mDisplayEventReceiver(std::move(receiver)) {}

    virtual void requestNextVsync() override {
        status_t status = mDisplayEventReceiver->requestNextVsync();
        LOG_ALWAYS_FATAL_IF(status != NO_ERROR, "requestNextVsync failed with status: %d", status);
    }

    virtual nsecs_t latestVsyncEvent() override {
        DisplayEventReceiver::Event buf[EVENT_BUFFER_SIZE];
        nsecs_t latest = 0;
        ssize_t n;
        while ((n = mDisplayEventReceiver->getEvents(buf, EVENT_BUFFER_SIZE)) > 0) {
            for (ssize_t i = 0; i < n; i++) {
                const DisplayEventReceiver::Event& ev = buf[i];
                switch (ev.header.type) {
                    case DisplayEventReceiver::DISPLAY_EVENT_VSYNC:
                        latest = ev.header.timestamp;
                        break;
                }
            }
        }
        if (n < 0) {
            ALOGW("Failed to get events from display event receiver, status=%d", status_t(n));
        }
        return latest;
    }

private:
    std::unique_ptr<DisplayEventReceiver> mDisplayEventReceiver;
};

class DummyVsyncSource : public VsyncSource {
public:
    DummyVsyncSource(RenderThread* renderThread) : mRenderThread(renderThread) {}

    virtual void requestNextVsync() override {
        mRenderThread->queue().postDelayed(16_ms,
                                           [this]() { mRenderThread->drainDisplayEventQueue(); });
    }

    virtual nsecs_t latestVsyncEvent() override { return systemTime(CLOCK_MONOTONIC); }

private:
    RenderThread* mRenderThread;
};

bool RenderThread::hasInstance() {
    return gHasRenderThreadInstance;
}

void RenderThread::setOnStartHook(void (*onStartHook)()) {
    LOG_ALWAYS_FATAL_IF(hasInstance(), "can't set an onStartHook after we've started...");
    gOnStartHook = onStartHook;
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
        , mVkManager(nullptr) {
    Properties::load();
    start("RenderThread");
}

RenderThread::~RenderThread() {
    LOG_ALWAYS_FATAL("Can't destroy the render thread");
}

void RenderThread::initializeDisplayEventReceiver() {
    LOG_ALWAYS_FATAL_IF(mVsyncSource, "Initializing a second DisplayEventReceiver?");

    if (!Properties::isolatedProcess) {
        auto receiver = std::make_unique<DisplayEventReceiver>();
        status_t status = receiver->initCheck();
        LOG_ALWAYS_FATAL_IF(status != NO_ERROR,
                            "Initialization of DisplayEventReceiver "
                            "failed with status: %d",
                            status);

        // Register the FD
        mLooper->addFd(receiver->getFd(), 0, Looper::EVENT_INPUT,
                       RenderThread::displayEventReceiverCallback, this);
        mVsyncSource = new DisplayEventReceiverWrapper(std::move(receiver));
    } else {
        mVsyncSource = new DummyVsyncSource(this);
    }
}

void RenderThread::initThreadLocals() {
    mDisplayInfo = DeviceInfo::queryDisplayInfo();
    nsecs_t frameIntervalNanos = static_cast<nsecs_t>(1000000000 / mDisplayInfo.fps);
    mTimeLord.setFrameInterval(frameIntervalNanos);
    initializeDisplayEventReceiver();
    mEglManager = new EglManager(*this);
    mRenderState = new RenderState(*this);
    mVkManager = new VulkanManager(*this);
    mCacheManager = new CacheManager(mDisplayInfo);
}

void RenderThread::dumpGraphicsMemory(int fd) {
    globalProfileData()->dump(fd);

    String8 cachesOutput;
    String8 pipeline;
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::OpenGL: {
            if (Caches::hasInstance()) {
                cachesOutput.appendFormat("Caches:\n");
                Caches::getInstance().dumpMemoryUsage(cachesOutput);
            } else {
                cachesOutput.appendFormat("No caches instance.");
            }
            pipeline.appendFormat("FrameBuilder");
            break;
        }
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
        auto renderType = Properties::getRenderPipelineType();
        switch (renderType) {
            case RenderPipelineType::OpenGL:
                mReadback = new OpenGLReadbackImpl(*this);
                break;
            case RenderPipelineType::SkiaGL:
                mReadback = new skiapipeline::SkiaOpenGLReadback(*this);
                break;
            case RenderPipelineType::SkiaVulkan:
                mReadback = new skiapipeline::SkiaVulkanReadback(*this);
                break;
            default:
                LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
                break;
        }
    }

    return *mReadback;
}

void RenderThread::setGrContext(sk_sp<GrContext> context) {
    mCacheManager->reset(context);
    if (mGrContext) {
        mGrContext->releaseResourcesAndAbandonContext();
    }
    mGrContext = std::move(context);
}

int RenderThread::displayEventReceiverCallback(int fd, int events, void* data) {
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

    reinterpret_cast<RenderThread*>(data)->drainDisplayEventQueue();

    return 1;  // keep the callback
}

void RenderThread::drainDisplayEventQueue() {
    ATRACE_CALL();
    nsecs_t vsyncEvent = mVsyncSource->latestVsyncEvent();
    if (vsyncEvent > 0) {
        mVsyncRequested = false;
        if (mTimeLord.vsyncReceived(vsyncEvent) && !mFrameCallbackTaskPending) {
            ATRACE_NAME("queue mFrameCallbackTask");
            mFrameCallbackTaskPending = true;
            nsecs_t runAt = (vsyncEvent + DISPATCH_FRAME_CALLBACKS_DELAY);
            queue().postAt(runAt, [this]() { dispatchFrameCallbacks(); });
        }
    }
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
    if (gOnStartHook) {
        gOnStartHook();
    }
    initThreadLocals();

    while (true) {
        waitForWork();
        processQueue();

        if (mPendingRegistrationFrameCallbacks.size() && !mFrameCallbackTaskPending) {
            drainDisplayEventQueue();
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
        case RenderPipelineType::OpenGL:
            return OpenGLPipeline::allocateHardwareBitmap(*this, skBitmap);
        case RenderPipelineType::SkiaGL:
            return skiapipeline::SkiaOpenGLPipeline::allocateHardwareBitmap(*this, skBitmap);
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

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
