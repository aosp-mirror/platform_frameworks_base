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
#include "FunctorDrawable.h"

#include "DumpOpsCanvas.h"
#ifdef __ANDROID__ // Layoutlib does not support SkiaPipeline
#include "SkiaPipeline.h"
#else
#include "DamageAccumulator.h"
#endif
#include "TreeInfo.h"
#include "VectorDrawable.h"
#ifdef __ANDROID__
#include "renderthread/CanvasContext.h"
#endif

#include <SkImagePriv.h>
#include <SkPathOps.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

void SkiaDisplayList::syncContents(const WebViewSyncData& data) {
    for (auto& functor : mChildFunctors) {
        functor->syncFunctor(data);
    }
    for (auto& animatedImage : mAnimatedImages) {
        animatedImage->syncProperties();
    }
    for (auto& vectorDrawable : mVectorDrawables) {
        vectorDrawable.first->syncProperties();
    }
}

void SkiaDisplayList::onRemovedFromTree() {
    for (auto& functor : mChildFunctors) {
        functor->onRemovedFromTree();
    }
}

bool SkiaDisplayList::reuseDisplayList(RenderNode* node) {
    reset();
    node->attachAvailableList(this);
    return true;
}

void SkiaDisplayList::updateChildren(std::function<void(RenderNode*)> updateFn) {
    for (auto& child : mChildNodes) {
        updateFn(child.getRenderNode());
    }
}

void SkiaDisplayList::visit(std::function<void(const RenderNode&)> func) const {
    for (auto& child : mChildNodes) {
        child.getRenderNode()->visit(func);
    }
}

static bool intersects(const SkISize screenSize, const Matrix4& mat, const SkRect& bounds) {
    Vector3 points[] = { Vector3 {bounds.fLeft, bounds.fTop, 0},
                         Vector3 {bounds.fRight, bounds.fTop, 0},
                         Vector3 {bounds.fRight, bounds.fBottom, 0},
                         Vector3 {bounds.fLeft, bounds.fBottom, 0}};
    float minX, minY, maxX, maxY;
    bool first = true;
    for (auto& point : points) {
        mat.mapPoint3d(point);
        if (first) {
            minX = maxX = point.x;
            minY = maxY = point.y;
            first = false;
        } else {
            minX = std::min(minX, point.x);
            minY = std::min(minY, point.y);
            maxX = std::max(maxX, point.x);
            maxY = std::max(maxY, point.y);
        }
    }
    return SkRect::Make(screenSize).intersects(SkRect::MakeLTRB(minX, minY, maxX, maxY));
}

bool SkiaDisplayList::prepareListAndChildren(
        TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
        std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn) {
    // If the prepare tree is triggered by the UI thread and no previous call to
    // pinImages has failed then we must pin all mutable images in the GPU cache
    // until the next UI thread draw.
#ifdef __ANDROID__ // Layoutlib does not support CanvasContext
    if (info.prepareTextures && !info.canvasContext.pinImages(mMutableImages)) {
        // In the event that pinning failed we prevent future pinImage calls for the
        // remainder of this tree traversal and also unpin any currently pinned images
        // to free up GPU resources.
        info.prepareTextures = false;
        info.canvasContext.unpinImages();
    }

    auto grContext = info.canvasContext.getGrContext();
    for (auto mesh : mMeshes) {
        mesh->updateSkMesh(grContext);
    }

#endif

    bool hasBackwardProjectedNodesHere = false;
    bool hasBackwardProjectedNodesSubtree = false;

    for (auto& child : mChildNodes) {
        RenderNode* childNode = child.getRenderNode();
        Matrix4 mat4(child.getRecordedMatrix());
        info.damageAccumulator->pushTransform(&mat4);
        info.hasBackwardProjectedNodes = false;
        childFn(childNode, observer, info, functorsNeedLayer);
        hasBackwardProjectedNodesHere |= child.getNodeProperties().getProjectBackwards();
        hasBackwardProjectedNodesSubtree |= info.hasBackwardProjectedNodes;
        info.damageAccumulator->popTransform();
    }

    // The purpose of next block of code is to reset projected display list if there are no
    // backward projected nodes. This speeds up drawing, by avoiding an extra walk of the tree
    if (mProjectionReceiver) {
        mProjectionReceiver->setProjectedDisplayList(hasBackwardProjectedNodesSubtree ? this
                                                                                      : nullptr);
        info.hasBackwardProjectedNodes = hasBackwardProjectedNodesHere;
    } else {
        info.hasBackwardProjectedNodes =
                hasBackwardProjectedNodesSubtree || hasBackwardProjectedNodesHere;
    }

    bool isDirty = false;
    for (auto& animatedImage : mAnimatedImages) {
        nsecs_t timeTilNextFrame = TreeInfo::Out::kNoAnimatedImageDelay;
        // If any animated image in the display list needs updated, then damage the node.
        if (animatedImage->isDirty(&timeTilNextFrame)) {
            isDirty = true;
        }

        if (animatedImage->isRunning() &&
            timeTilNextFrame != TreeInfo::Out::kNoAnimatedImageDelay) {
            auto& delay = info.out.animatedImageDelay;
            if (delay == TreeInfo::Out::kNoAnimatedImageDelay || timeTilNextFrame < delay) {
                delay = timeTilNextFrame;
            }
        }
    }

    for (auto& [vectorDrawable, cachedMatrix] : mVectorDrawables) {
        // If any vector drawable in the display list needs update, damage the node.
        if (vectorDrawable->isDirty()) {
            Matrix4 totalMatrix;
            info.damageAccumulator->computeCurrentTransform(&totalMatrix);
            Matrix4 canvasMatrix(cachedMatrix);
            totalMatrix.multiply(canvasMatrix);
            const SkRect& bounds = vectorDrawable->properties().getBounds();
            if (intersects(info.screenSize, totalMatrix, bounds)) {
                isDirty = true;
                vectorDrawable->setPropertyChangeWillBeConsumed(true);
            }
        }
    }
    return isDirty;
}

void SkiaDisplayList::reset() {
    mProjectionReceiver = nullptr;

    mDisplayList.reset();

    mMeshes.clear();
    mMutableImages.clear();
    mVectorDrawables.clear();
    mAnimatedImages.clear();
    mChildFunctors.clear();
    mChildNodes.clear();

    allocator.~LinearAllocator();
    new (&allocator) LinearAllocator();
}

void SkiaDisplayList::output(std::ostream& output, uint32_t level) const {
    DumpOpsCanvas canvas(output, level, *this);
    mDisplayList.draw(&canvas);
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
