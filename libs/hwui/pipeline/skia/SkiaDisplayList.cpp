/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "SkiaDisplayList.h"

#include "renderthread/CanvasContext.h"
#include "VectorDrawable.h"

#include <SkImagePriv.h>


namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaDisplayList::SkiaDisplayList(SkRect bounds) : mDrawable(SkLiteDL::New(bounds)) {
    SkASSERT(projectionReceiveIndex == -1);
}

void SkiaDisplayList::syncContents() {
    for (auto& functor : mChildFunctors) {
        functor.syncFunctor();
    }
    for (auto& vectorDrawable : mVectorDrawables) {
        vectorDrawable->syncProperties();
    }
}

bool SkiaDisplayList::reuseDisplayList(RenderNode* node, renderthread::CanvasContext* context) {
    reset(SkRect::MakeEmpty());
    node->attachAvailableList(this);
    return true;
}

void SkiaDisplayList::updateChildren(std::function<void(RenderNode*)> updateFn) {
    for (auto& child : mChildNodes) {
        updateFn(child.getRenderNode());
    }
}

bool SkiaDisplayList::prepareListAndChildren(TreeInfo& info, bool functorsNeedLayer,
        std::function<void(RenderNode*, TreeInfo&, bool)> childFn) {
    // If the prepare tree is triggered by the UI thread then we must force all
    // mutable images to be pinned in the GPU cache until the next UI thread
    // draw
    if (info.mode == TreeInfo::MODE_FULL) {
        info.prepareTextures = info.canvasContext.pinImages(mMutableImages);
    }

    for (auto& child : mChildNodes) {
        RenderNode* childNode = child.getRenderNode();
        Matrix4 mat4(child.getRecordedMatrix());
        info.damageAccumulator->pushTransform(&mat4);
        // TODO: a layer is needed if the canvas is rotated or has a non-rect clip
        bool childFunctorsNeedLayer = functorsNeedLayer;
        childFn(childNode, info, childFunctorsNeedLayer);
        info.damageAccumulator->popTransform();
    }

    bool isDirty = false;
    for (auto& vectorDrawable : mVectorDrawables) {
        // If any vector drawable in the display list needs update, damage the node.
        if (vectorDrawable->isDirty()) {
            isDirty = true;
        }
        vectorDrawable->setPropertyChangeWillBeConsumed(true);
    }
    return isDirty;
}

void SkiaDisplayList::reset(SkRect bounds) {
    mIsProjectionReceiver = false;

    mDrawable->reset(bounds);

    mMutableImages.clear();
    mVectorDrawables.clear();
    mChildFunctors.clear();
    mChildNodes.clear();

    projectionReceiveIndex = -1;
    allocator.~LinearAllocator();
    new (&allocator) LinearAllocator();
}

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
