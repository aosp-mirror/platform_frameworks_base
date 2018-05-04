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

#include <SkCanvas.h>
#include <algorithm>

#include <utils/Trace.h>

#include "DamageAccumulator.h"
#include "Debug.h"
#include "DisplayList.h"
#include "OpDumper.h"
#include "RecordedOp.h"
#include "RenderNode.h"
#include "VectorDrawable.h"
#include "renderthread/CanvasContext.h"

namespace android {
namespace uirenderer {

DisplayList::DisplayList()
        : projectionReceiveIndex(-1)
        , stdAllocator(allocator)
        , chunks(stdAllocator)
        , ops(stdAllocator)
        , children(stdAllocator)
        , bitmapResources(stdAllocator)
        , pathResources(stdAllocator)
        , patchResources(stdAllocator)
        , paints(stdAllocator)
        , regions(stdAllocator)
        , referenceHolders(stdAllocator)
        , functors(stdAllocator)
        , vectorDrawables(stdAllocator) {}

DisplayList::~DisplayList() {
    cleanupResources();
}

void DisplayList::cleanupResources() {
    if (CC_UNLIKELY(patchResources.size())) {
        ResourceCache& resourceCache = ResourceCache::getInstance();
        resourceCache.lock();

        for (size_t i = 0; i < patchResources.size(); i++) {
            resourceCache.decrementRefcountLocked(patchResources[i]);
        }

        resourceCache.unlock();
    }

    for (size_t i = 0; i < pathResources.size(); i++) {
        const SkPath* path = pathResources[i];
        delete path;
    }

    for (auto& iter : functors) {
        if (iter.listener) {
            iter.listener->onGlFunctorReleased(iter.functor);
        }
    }

    patchResources.clear();
    pathResources.clear();
    paints.clear();
    regions.clear();
}

size_t DisplayList::addChild(NodeOpType* op) {
    referenceHolders.push_back(op->renderNode);
    size_t index = children.size();
    children.push_back(op);
    return index;
}

void DisplayList::syncContents() {
    for (auto& iter : functors) {
        (*iter.functor)(DrawGlInfo::kModeSync, nullptr);
    }
    for (auto& vectorDrawable : vectorDrawables) {
        vectorDrawable->syncProperties();
    }
}

void DisplayList::updateChildren(std::function<void(RenderNode*)> updateFn) {
    for (auto&& child : children) {
        updateFn(child->renderNode);
    }
}

bool DisplayList::prepareListAndChildren(
        TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
        std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn) {
    info.prepareTextures = info.canvasContext.pinImages(bitmapResources);

    for (auto&& op : children) {
        RenderNode* childNode = op->renderNode;
        info.damageAccumulator->pushTransform(&op->localMatrix);
        bool childFunctorsNeedLayer =
                functorsNeedLayer;  // TODO! || op->mRecordedWithPotentialStencilClip;
        childFn(childNode, observer, info, childFunctorsNeedLayer);
        info.damageAccumulator->popTransform();
    }

    bool isDirty = false;
    for (auto& vectorDrawable : vectorDrawables) {
        // If any vector drawable in the display list needs update, damage the node.
        if (vectorDrawable->isDirty()) {
            isDirty = true;
        }
        vectorDrawable->setPropertyChangeWillBeConsumed(true);
    }
    return isDirty;
}

void DisplayList::output(std::ostream& output, uint32_t level) {
    for (auto&& op : getOps()) {
        OpDumper::dump(*op, output, level + 1);
        if (op->opId == RecordedOpId::RenderNodeOp) {
            auto rnOp = reinterpret_cast<const RenderNodeOp*>(op);
            rnOp->renderNode->output(output, level + 1);
        } else {
            output << std::endl;
        }
    }
}

};  // namespace uirenderer
};  // namespace android
