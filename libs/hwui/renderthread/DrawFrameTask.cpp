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

#define ATRACE_TAG ATRACE_TAG_VIEW

#include "DrawFrameTask.h"

#include <utils/Log.h>
#include <utils/Trace.h>

#include "../DisplayList.h"
#include "../RenderNode.h"
#include "CanvasContext.h"
#include "RenderThread.h"

namespace android {
namespace uirenderer {
namespace renderthread {

DrawFrameTask::DrawFrameTask()
        : mRenderThread(NULL)
        , mContext(NULL)
        , mFrameTimeNanos(0)
        , mSyncResult(kSync_OK) {
}

DrawFrameTask::~DrawFrameTask() {
}

void DrawFrameTask::setContext(RenderThread* thread, CanvasContext* context) {
    mRenderThread = thread;
    mContext = context;
}

void DrawFrameTask::addLayer(DeferredLayerUpdater* layer) {
    LOG_ALWAYS_FATAL_IF(!mContext, "Lifecycle violation, there's no context to addLayer with!");

    mLayers.push(layer);
}

void DrawFrameTask::removeLayer(DeferredLayerUpdater* layer) {
    for (size_t i = 0; i < mLayers.size(); i++) {
        if (mLayers[i] == layer) {
            mLayers.removeAt(i);
            break;
        }
    }
}

void DrawFrameTask::setDirty(int left, int top, int right, int bottom) {
    mDirty.set(left, top, right, bottom);
}

int DrawFrameTask::drawFrame(nsecs_t frameTimeNanos) {
    LOG_ALWAYS_FATAL_IF(!mContext, "Cannot drawFrame with no CanvasContext!");

    mSyncResult = kSync_OK;
    mFrameTimeNanos = frameTimeNanos;
    postAndWait();

    // Reset the single-frame data
    mFrameTimeNanos = 0;
    mDirty.setEmpty();

    return mSyncResult;
}

void DrawFrameTask::postAndWait() {
    AutoMutex _lock(mLock);
    mRenderThread->queue(this);
    mSignal.wait(mLock);
}

void DrawFrameTask::run() {
    ATRACE_NAME("DrawFrame");

    bool canUnblockUiThread;
    bool canDrawThisFrame;
    {
        TreeInfo info;
        canUnblockUiThread = syncFrameState(info);
        canDrawThisFrame = info.out.canDrawThisFrame;
    }

    // Grab a copy of everything we need
    Rect dirty(mDirty);
    CanvasContext* context = mContext;

    // From this point on anything in "this" is *UNSAFE TO ACCESS*
    if (canUnblockUiThread) {
        unblockUiThread();
    }

    if (CC_LIKELY(canDrawThisFrame)) {
        context->draw(&dirty);
    }

    if (!canUnblockUiThread) {
        unblockUiThread();
    }
}

static void initTreeInfo(TreeInfo& info) {
    info.prepareTextures = true;
    info.performStagingPush = true;
    info.evaluateAnimations = true;
}

bool DrawFrameTask::syncFrameState(TreeInfo& info) {
    ATRACE_CALL();
    mRenderThread->timeLord().vsyncReceived(mFrameTimeNanos);
    mContext->makeCurrent();
    Caches::getInstance().textureCache.resetMarkInUse();
    initTreeInfo(info);
    mContext->prepareDraw(&mLayers, info);
    if (info.out.hasAnimations) {
        // TODO: dirty calculations, for now just do a full-screen inval
        mDirty.setEmpty();
        if (info.out.requiresUiRedraw) {
            mSyncResult |= kSync_UIRedrawRequired;
        }
    }
    // If prepareTextures is false, we ran out of texture cache space
    return !info.out.hasFunctors && info.prepareTextures;
}

void DrawFrameTask::unblockUiThread() {
    AutoMutex _lock(mLock);
    mSignal.signal();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
