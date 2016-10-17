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
#include <SkMutex.h>

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
    reset(context ? context->getGrContext() : nullptr, SkRect::MakeEmpty());
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
    // force all mutable images to be pinned in the GPU cache for the duration
    // of this frame
    pinImages(info.canvasContext.getGrContext());

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

static std::vector<sk_sp<SkImage>> gPinnedImages;
static SkBaseMutex gLock;

void SkiaDisplayList::pinImages(GrContext* context) {
    if (mPinnedImages) return;
    for (SkImage* image : mMutableImages) {
        SkImage_pinAsTexture(image, context);
    }
    mPinnedImages = true;
}

void SkiaDisplayList::unpinImages(GrContext* context) {
    if (!mPinnedImages) return;
    if (context) {
        for (SkImage* image : mMutableImages) {
            SkImage_unpinAsTexture(image, context);
        }
    } else {
        gLock.acquire();
        for (SkImage* image : mMutableImages) {
            gPinnedImages.emplace_back(sk_ref_sp(image));
        }
        gLock.release();
    }
    mPinnedImages = false;
}

void SkiaDisplayList::cleanupImages(GrContext* context) {
    gLock.acquire();
    for (auto& image : gPinnedImages) {
        SkImage_unpinAsTexture(image.get(), context);
    }
    gPinnedImages.clear();
    gLock.release();
}

void SkiaDisplayList::reset(GrContext* context, SkRect bounds) {
    unpinImages(context);
    SkASSERT(!mPinnedImages);
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
