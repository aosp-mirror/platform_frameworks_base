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

DrawFrameTask::DrawFrameTask() : mContext(0), mRenderNode(0) {
}

DrawFrameTask::~DrawFrameTask() {
}

void DrawFrameTask::setContext(CanvasContext* context) {
    mContext = context;
}

void DrawFrameTask::setDisplayListData(RenderNode* renderNode, DisplayListData* newData) {
    SetDisplayListData setter;
    setter.targetNode = renderNode;
    setter.newData = newData;
    mDisplayListDataUpdates.push(setter);
}

void DrawFrameTask::addLayer(DeferredLayerUpdater* layer) {
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
    mRenderNode = renderNode;
}

void DrawFrameTask::setDirty(int left, int top, int right, int bottom) {
    mDirty.set(left, top, right, bottom);
}

void DrawFrameTask::drawFrame(RenderThread* renderThread) {
    LOG_ALWAYS_FATAL_IF(!mRenderNode, "Cannot drawFrame with no render node!");
    LOG_ALWAYS_FATAL_IF(!mContext, "Cannot drawFrame with no CanvasContext!");

    AutoMutex _lock(mLock);
    renderThread->queue(this);
    mSignal.wait(mLock);

    // Reset the single-frame data
    mDirty.setEmpty();
    mRenderNode = 0;
}

void DrawFrameTask::run() {
    ATRACE_NAME("DrawFrame");

    syncFrameState();

    // Grab a copy of everything we need
    Rect dirtyCopy(mDirty);
    RenderNode* renderNode = mRenderNode;
    CanvasContext* context = mContext;

    // This is temporary until WebView has a solution for syncing frame state
    bool canUnblockUiThread = !requiresSynchronousDraw(renderNode);

    // From this point on anything in "this" is *UNSAFE TO ACCESS*
    if (canUnblockUiThread) {
        unblockUiThread();
    }

    drawRenderNode(context, renderNode, &dirtyCopy);

    if (!canUnblockUiThread) {
        unblockUiThread();
    }
}

void DrawFrameTask::syncFrameState() {
    ATRACE_CALL();

    for (size_t i = 0; i < mDisplayListDataUpdates.size(); i++) {
        const SetDisplayListData& setter = mDisplayListDataUpdates[i];
        setter.targetNode->setData(setter.newData);
    }
    mDisplayListDataUpdates.clear();

    mContext->processLayerUpdates(&mLayers);
    mRenderNode->updateProperties();
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
