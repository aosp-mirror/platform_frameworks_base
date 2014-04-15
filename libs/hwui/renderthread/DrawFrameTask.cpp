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

DrawFrameTask::DrawFrameTask() : mContext(0) {
}

DrawFrameTask::~DrawFrameTask() {
}

void DrawFrameTask::setContext(CanvasContext* context) {
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

void DrawFrameTask::drawFrame(RenderThread* renderThread) {
    LOG_ALWAYS_FATAL_IF(!mContext, "Cannot drawFrame with no CanvasContext!");

    postAndWait(renderThread);

    // Reset the single-frame data
    mDirty.setEmpty();
}

void DrawFrameTask::postAndWait(RenderThread* renderThread) {
    AutoMutex _lock(mLock);
    renderThread->queue(this);
    mSignal.wait(mLock);
}

void DrawFrameTask::run() {
    ATRACE_NAME("DrawFrame");

    bool canUnblockUiThread = syncFrameState();

    // Grab a copy of everything we need
    Rect dirty(mDirty);
    CanvasContext* context = mContext;

    // From this point on anything in "this" is *UNSAFE TO ACCESS*
    if (canUnblockUiThread) {
        unblockUiThread();
    }

    context->draw(&dirty);

    if (!canUnblockUiThread) {
        unblockUiThread();
    }
}

static void initTreeInfo(TreeInfo& info) {
    info.prepareTextures = true;
    info.performStagingPush = true;
    info.evaluateAnimations = true;
    // TODO: Get this from Choreographer
    nsecs_t frameTimeNs = systemTime(CLOCK_MONOTONIC);
    info.frameTimeMs = nanoseconds_to_milliseconds(frameTimeNs);
}

bool DrawFrameTask::syncFrameState() {
    ATRACE_CALL();
    mContext->makeCurrent();
    Caches::getInstance().textureCache.resetMarkInUse();
    TreeInfo info;
    initTreeInfo(info);
    mContext->processLayerUpdates(&mLayers, info);
    mContext->prepareTree(info);
    // If prepareTextures is false, we ran out of texture cache space
    return !info.hasFunctors && info.prepareTextures;
}

void DrawFrameTask::unblockUiThread() {
    AutoMutex _lock(mLock);
    mSignal.signal();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
