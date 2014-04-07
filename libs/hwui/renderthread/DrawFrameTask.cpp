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

SetDisplayListData::SetDisplayListData() : mNewData(0) {}

SetDisplayListData::SetDisplayListData(RenderNode* node, DisplayListData* newData)
        : mTargetNode(node), mNewData(newData) {
}

SetDisplayListData::~SetDisplayListData() {}

void SetDisplayListData::apply() const {
    mTargetNode->setData(mNewData);
}

DrawFrameTask::DrawFrameTask() : mContext(0), mTaskMode(MODE_INVALID), mRenderNode(0) {
}

DrawFrameTask::~DrawFrameTask() {
}

void DrawFrameTask::setContext(CanvasContext* context) {
    mContext = context;
}

void DrawFrameTask::setDisplayListData(RenderNode* renderNode, DisplayListData* newData) {
    LOG_ALWAYS_FATAL_IF(!mContext, "Lifecycle violation, there's no context to setDisplayListData with!");

    SetDisplayListData setter(renderNode, newData);
    mDisplayListDataUpdates.push(setter);
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

void DrawFrameTask::setRenderNode(RenderNode* renderNode) {
    LOG_ALWAYS_FATAL_IF(!mContext, "Lifecycle violation, there's no context to setRenderNode with!");

    mRenderNode = renderNode;
}

void DrawFrameTask::setDirty(int left, int top, int right, int bottom) {
    mDirty.set(left, top, right, bottom);
}

void DrawFrameTask::drawFrame(RenderThread* renderThread) {
    LOG_ALWAYS_FATAL_IF(!mRenderNode.get(), "Cannot drawFrame with no render node!");
    LOG_ALWAYS_FATAL_IF(!mContext, "Cannot drawFrame with no CanvasContext!");

    postAndWait(renderThread, MODE_FULL);

    // Reset the single-frame data
    mDirty.setEmpty();
    mRenderNode = 0;
}

void DrawFrameTask::flushStateChanges(RenderThread* renderThread) {
    LOG_ALWAYS_FATAL_IF(!mContext, "Cannot drawFrame with no CanvasContext!");

    postAndWait(renderThread, MODE_STATE_ONLY);
}

void DrawFrameTask::postAndWait(RenderThread* renderThread, TaskMode mode) {
    LOG_ALWAYS_FATAL_IF(mode == MODE_INVALID, "That's not a real mode, silly!");

    mTaskMode = mode;
    AutoMutex _lock(mLock);
    renderThread->queue(this);
    mSignal.wait(mLock);
}

void DrawFrameTask::run() {
    ATRACE_NAME("DrawFrame");

    syncFrameState();

    if (mTaskMode == MODE_STATE_ONLY) {
        unblockUiThread();
        return;
    }

    // Grab a copy of everything we need
    Rect dirtyCopy(mDirty);
    sp<RenderNode> renderNode = mRenderNode;
    CanvasContext* context = mContext;

    // This is temporary until WebView has a solution for syncing frame state
    bool canUnblockUiThread = !requiresSynchronousDraw(renderNode.get());

    // From this point on anything in "this" is *UNSAFE TO ACCESS*
    if (canUnblockUiThread) {
        unblockUiThread();
    }

    drawRenderNode(context, renderNode.get(), &dirtyCopy);

    if (!canUnblockUiThread) {
        unblockUiThread();
    }
}

void DrawFrameTask::syncFrameState() {
    ATRACE_CALL();

    for (size_t i = 0; i < mDisplayListDataUpdates.size(); i++) {
        const SetDisplayListData& setter = mDisplayListDataUpdates[i];
        setter.apply();
    }
    mDisplayListDataUpdates.clear();

    mContext->processLayerUpdates(&mLayers);

    // If we don't have an mRenderNode this is a state flush only
    if (mRenderNode.get()) {
        mRenderNode->updateProperties();
    }
}

void DrawFrameTask::unblockUiThread() {
    AutoMutex _lock(mLock);
    mSignal.signal();
}

void DrawFrameTask::drawRenderNode(CanvasContext* context, RenderNode* renderNode, Rect* dirty) {
    ATRACE_CALL();

    if (dirty->bottom == -1 && dirty->left == -1
            && dirty->top == -1 && dirty->right == -1) {
        dirty = 0;
    }
    context->drawDisplayList(renderNode, dirty);
}

bool DrawFrameTask::requiresSynchronousDraw(RenderNode* renderNode) {
    return renderNode->hasFunctors();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
