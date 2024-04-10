/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "renderthread/RenderThread.h"

#include "Readback.h"
#include "renderstate/RenderState.h"
#include "renderthread/VulkanManager.h"

namespace android {
namespace uirenderer {
namespace renderthread {

static bool gHasRenderThreadInstance = false;
static JVMAttachHook gOnStartHook = nullptr;

ASurfaceControlFunctions::ASurfaceControlFunctions() {}

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

RenderThread::~RenderThread() {}

void RenderThread::initThreadLocals() {
    mRenderState = new RenderState(*this);
    mCacheManager = new CacheManager(*this);
}

void RenderThread::requireGlContext() {}

void RenderThread::requireVkContext() {}

void RenderThread::initGrContextOptions(GrContextOptions& options) {}

void RenderThread::destroyRenderingContext() {}

VulkanManager& RenderThread::vulkanManager() {
    return *mVkManager;
}

void RenderThread::dumpGraphicsMemory(int fd, bool includeProfileData) {}

void RenderThread::getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage) {}

Readback& RenderThread::readback() {
    if (!mReadback) {
        mReadback = new Readback(*this);
    }

    return *mReadback;
}

void RenderThread::setGrContext(sk_sp<GrDirectContext> context) {}

sk_sp<GrDirectContext> RenderThread::requireGrContext() {
    return mGrContext;
}

bool RenderThread::threadLoop() {
    if (gOnStartHook) {
        gOnStartHook("RenderThread");
    }
    initThreadLocals();

    while (true) {
        waitForWork();
        processQueue();
        mCacheManager->onThreadIdle();
    }

    return false;
}

void RenderThread::postFrameCallback(IFrameCallback* callback) {}

bool RenderThread::removeFrameCallback(IFrameCallback* callback) {
    return false;
}

void RenderThread::pushBackFrameCallback(IFrameCallback* callback) {}

sk_sp<Bitmap> RenderThread::allocateHardwareBitmap(SkBitmap& skBitmap) {
    return nullptr;
}

bool RenderThread::isCurrent() {
    return true;
}

void RenderThread::preload() {}

void RenderThread::trimMemory(TrimLevel level) {}

void RenderThread::trimCaches(CacheTrimLevel level) {}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
