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

#include "DrawFrameTask.h"

#include <dlfcn.h>
#include <gui/TraceUtils.h>
#include <utils/Log.h>
#include <algorithm>

#include "../DeferredLayerUpdater.h"
#include "../DisplayList.h"
#include "../Properties.h"
#include "../RenderNode.h"
#include "CanvasContext.h"
#include "RenderThread.h"
#include "thread/CommonPool.h"

namespace android {
namespace uirenderer {
namespace renderthread {

namespace {

typedef APerformanceHintManager* (*APH_getManager)();
typedef APerformanceHintSession* (*APH_createSession)(APerformanceHintManager*, const int32_t*,
                                                      size_t, int64_t);
typedef void (*APH_updateTargetWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_reportActualWorkDuration)(APerformanceHintSession*, int64_t);
typedef void (*APH_closeSession)(APerformanceHintSession* session);

bool gAPerformanceHintBindingInitialized = false;
APH_getManager gAPH_getManagerFn = nullptr;
APH_createSession gAPH_createSessionFn = nullptr;
APH_updateTargetWorkDuration gAPH_updateTargetWorkDurationFn = nullptr;
APH_reportActualWorkDuration gAPH_reportActualWorkDurationFn = nullptr;
APH_closeSession gAPH_closeSessionFn = nullptr;

void ensureAPerformanceHintBindingInitialized() {
    if (gAPerformanceHintBindingInitialized) return;

    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    LOG_ALWAYS_FATAL_IF(handle_ == nullptr, "Failed to dlopen libandroid.so!");

    gAPH_getManagerFn = (APH_getManager)dlsym(handle_, "APerformanceHint_getManager");
    LOG_ALWAYS_FATAL_IF(gAPH_getManagerFn == nullptr,
                        "Failed to find required symbol APerformanceHint_getManager!");

    gAPH_createSessionFn = (APH_createSession)dlsym(handle_, "APerformanceHint_createSession");
    LOG_ALWAYS_FATAL_IF(gAPH_createSessionFn == nullptr,
                        "Failed to find required symbol APerformanceHint_createSession!");

    gAPH_updateTargetWorkDurationFn = (APH_updateTargetWorkDuration)dlsym(
            handle_, "APerformanceHint_updateTargetWorkDuration");
    LOG_ALWAYS_FATAL_IF(
            gAPH_updateTargetWorkDurationFn == nullptr,
            "Failed to find required symbol APerformanceHint_updateTargetWorkDuration!");

    gAPH_reportActualWorkDurationFn = (APH_reportActualWorkDuration)dlsym(
            handle_, "APerformanceHint_reportActualWorkDuration");
    LOG_ALWAYS_FATAL_IF(
            gAPH_reportActualWorkDurationFn == nullptr,
            "Failed to find required symbol APerformanceHint_reportActualWorkDuration!");

    gAPH_closeSessionFn = (APH_closeSession)dlsym(handle_, "APerformanceHint_closeSession");
    LOG_ALWAYS_FATAL_IF(gAPH_closeSessionFn == nullptr,
                        "Failed to find required symbol APerformanceHint_closeSession!");

    gAPerformanceHintBindingInitialized = true;
}

}  // namespace

DrawFrameTask::DrawFrameTask()
        : mRenderThread(nullptr)
        , mContext(nullptr)
        , mContentDrawBounds(0, 0, 0, 0)
        , mSyncResult(SyncResult::OK) {}

DrawFrameTask::~DrawFrameTask() {}

void DrawFrameTask::setContext(RenderThread* thread, CanvasContext* context, RenderNode* targetNode,
                               int32_t uiThreadId, int32_t renderThreadId) {
    mRenderThread = thread;
    mContext = context;
    mTargetNode = targetNode;
    mUiThreadId = uiThreadId;
    mRenderThreadId = renderThreadId;
}

void DrawFrameTask::pushLayerUpdate(DeferredLayerUpdater* layer) {
    LOG_ALWAYS_FATAL_IF(!mContext,
                        "Lifecycle violation, there's no context to pushLayerUpdate with!");

    for (size_t i = 0; i < mLayers.size(); i++) {
        if (mLayers[i].get() == layer) {
            return;
        }
    }
    mLayers.push_back(layer);
}

void DrawFrameTask::removeLayerUpdate(DeferredLayerUpdater* layer) {
    for (size_t i = 0; i < mLayers.size(); i++) {
        if (mLayers[i].get() == layer) {
            mLayers.erase(mLayers.begin() + i);
            return;
        }
    }
}

int DrawFrameTask::drawFrame() {
    LOG_ALWAYS_FATAL_IF(!mContext, "Cannot drawFrame with no CanvasContext!");

    mSyncResult = SyncResult::OK;
    mSyncQueued = systemTime(SYSTEM_TIME_MONOTONIC);
    postAndWait();

    return mSyncResult;
}

void DrawFrameTask::postAndWait() {
    AutoMutex _lock(mLock);
    mRenderThread->queue().post([this]() { run(); });
    mSignal.wait(mLock);
}

void DrawFrameTask::run() {
    const int64_t vsyncId = mFrameInfo[static_cast<int>(FrameInfoIndex::FrameTimelineVsyncId)];
    ATRACE_FORMAT("DrawFrames %" PRId64, vsyncId);
    nsecs_t syncDelayDuration = systemTime(SYSTEM_TIME_MONOTONIC) - mSyncQueued;

    bool canUnblockUiThread;
    bool canDrawThisFrame;
    {
        TreeInfo info(TreeInfo::MODE_FULL, *mContext);
        canUnblockUiThread = syncFrameState(info);
        canDrawThisFrame = info.out.canDrawThisFrame;

        if (mFrameCommitCallback) {
            mContext->addFrameCommitListener(std::move(mFrameCommitCallback));
            mFrameCommitCallback = nullptr;
        }
    }

    // Grab a copy of everything we need
    CanvasContext* context = mContext;
    std::function<void(int64_t)> frameCallback = std::move(mFrameCallback);
    std::function<void()> frameCompleteCallback = std::move(mFrameCompleteCallback);
    mFrameCallback = nullptr;
    mFrameCompleteCallback = nullptr;
    int64_t intendedVsync = mFrameInfo[static_cast<int>(FrameInfoIndex::IntendedVsync)];
    int64_t frameDeadline = mFrameInfo[static_cast<int>(FrameInfoIndex::FrameDeadline)];
    int64_t frameStartTime = mFrameInfo[static_cast<int>(FrameInfoIndex::FrameStartTime)];

    // From this point on anything in "this" is *UNSAFE TO ACCESS*
    if (canUnblockUiThread) {
        unblockUiThread();
    }

    // Even if we aren't drawing this vsync pulse the next frame number will still be accurate
    if (CC_UNLIKELY(frameCallback)) {
        context->enqueueFrameWork(
                [frameCallback, frameNr = context->getFrameNumber()]() { frameCallback(frameNr); });
    }

    nsecs_t dequeueBufferDuration = 0;
    if (CC_LIKELY(canDrawThisFrame)) {
        dequeueBufferDuration = context->draw();
    } else {
        // Do a flush in case syncFrameState performed any texture uploads. Since we skipped
        // the draw() call, those uploads (or deletes) will end up sitting in the queue.
        // Do them now
        if (GrDirectContext* grContext = mRenderThread->getGrContext()) {
            grContext->flushAndSubmit();
        }
        // wait on fences so tasks don't overlap next frame
        context->waitOnFences();
    }

    if (CC_UNLIKELY(frameCompleteCallback)) {
        std::invoke(frameCompleteCallback);
    }

    if (!canUnblockUiThread) {
        unblockUiThread();
    }

    if (!mHintSessionWrapper) mHintSessionWrapper.emplace(mUiThreadId, mRenderThreadId);
    constexpr int64_t kSanityCheckLowerBound = 100000;       // 0.1ms
    constexpr int64_t kSanityCheckUpperBound = 10000000000;  // 10s
    int64_t targetWorkDuration = frameDeadline - intendedVsync;
    targetWorkDuration = targetWorkDuration * Properties::targetCpuTimePercentage / 100;
    if (targetWorkDuration > kSanityCheckLowerBound &&
        targetWorkDuration < kSanityCheckUpperBound &&
        targetWorkDuration != mLastTargetWorkDuration) {
        mLastTargetWorkDuration = targetWorkDuration;
        mHintSessionWrapper->updateTargetWorkDuration(targetWorkDuration);
    }
    int64_t frameDuration = systemTime(SYSTEM_TIME_MONOTONIC) - frameStartTime;
    int64_t actualDuration = frameDuration -
                             (std::min(syncDelayDuration, mLastDequeueBufferDuration)) -
                             dequeueBufferDuration;
    if (actualDuration > kSanityCheckLowerBound && actualDuration < kSanityCheckUpperBound) {
        mHintSessionWrapper->reportActualWorkDuration(actualDuration);
    }

    mLastDequeueBufferDuration = dequeueBufferDuration;
}

bool DrawFrameTask::syncFrameState(TreeInfo& info) {
    ATRACE_CALL();
    int64_t vsync = mFrameInfo[static_cast<int>(FrameInfoIndex::Vsync)];
    int64_t intendedVsync = mFrameInfo[static_cast<int>(FrameInfoIndex::IntendedVsync)];
    int64_t vsyncId = mFrameInfo[static_cast<int>(FrameInfoIndex::FrameTimelineVsyncId)];
    int64_t frameDeadline = mFrameInfo[static_cast<int>(FrameInfoIndex::FrameDeadline)];
    int64_t frameInterval = mFrameInfo[static_cast<int>(FrameInfoIndex::FrameInterval)];
    mRenderThread->timeLord().vsyncReceived(vsync, intendedVsync, vsyncId, frameDeadline,
            frameInterval);
    bool canDraw = mContext->makeCurrent();
    mContext->unpinImages();

    for (size_t i = 0; i < mLayers.size(); i++) {
        mLayers[i]->apply();
    }
    mLayers.clear();
    mContext->setContentDrawBounds(mContentDrawBounds);
    mContext->prepareTree(info, mFrameInfo, mSyncQueued, mTargetNode);

    // This is after the prepareTree so that any pending operations
    // (RenderNode tree state, prefetched layers, etc...) will be flushed.
    if (CC_UNLIKELY(!mContext->hasSurface() || !canDraw)) {
        if (!mContext->hasSurface()) {
            mSyncResult |= SyncResult::LostSurfaceRewardIfFound;
        } else {
            // If we have a surface but can't draw we must be stopped
            mSyncResult |= SyncResult::ContextIsStopped;
        }
        info.out.canDrawThisFrame = false;
    }

    if (info.out.hasAnimations) {
        if (info.out.requiresUiRedraw) {
            mSyncResult |= SyncResult::UIRedrawRequired;
        }
    }
    if (!info.out.canDrawThisFrame) {
        mSyncResult |= SyncResult::FrameDropped;
    }
    // If prepareTextures is false, we ran out of texture cache space
    return info.prepareTextures;
}

void DrawFrameTask::unblockUiThread() {
    AutoMutex _lock(mLock);
    mSignal.signal();
}

DrawFrameTask::HintSessionWrapper::HintSessionWrapper(int32_t uiThreadId, int32_t renderThreadId) {
    if (!Properties::useHintManager) return;
    if (uiThreadId < 0 || renderThreadId < 0) return;

    ensureAPerformanceHintBindingInitialized();

    APerformanceHintManager* manager = gAPH_getManagerFn();
    if (!manager) return;

    std::vector<int32_t> tids = CommonPool::getThreadIds();
    tids.push_back(uiThreadId);
    tids.push_back(renderThreadId);

    // DrawFrameTask code will always set a target duration before reporting actual durations.
    // So this is just a placeholder value that's never used.
    int64_t dummyTargetDurationNanos = 16666667;
    mHintSession =
            gAPH_createSessionFn(manager, tids.data(), tids.size(), dummyTargetDurationNanos);
}

DrawFrameTask::HintSessionWrapper::~HintSessionWrapper() {
    if (mHintSession) {
        gAPH_closeSessionFn(mHintSession);
    }
}

void DrawFrameTask::HintSessionWrapper::updateTargetWorkDuration(long targetDurationNanos) {
    if (mHintSession) {
        gAPH_updateTargetWorkDurationFn(mHintSession, targetDurationNanos);
    }
}

void DrawFrameTask::HintSessionWrapper::reportActualWorkDuration(long actualDurationNanos) {
    if (mHintSession) {
        gAPH_reportActualWorkDurationFn(mHintSession, actualDurationNanos);
    }
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
