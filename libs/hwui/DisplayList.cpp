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

#include "Debug.h"
#include "DisplayList.h"
#include "RenderNode.h"

#if HWUI_NEW_OPS
#include "RecordedOp.h"
#else
#include "DisplayListOp.h"
#endif

namespace android {
namespace uirenderer {

DisplayListData::DisplayListData()
        : projectionReceiveIndex(-1)
        , hasDrawOps(false) {
}

DisplayListData::~DisplayListData() {
    cleanupResources();
}

void DisplayListData::cleanupResources() {
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
        if (path->unique() && Caches::hasInstance()) {
            Caches::getInstance().pathCache.removeDeferred(path);
        }
        delete path;
    }

    patchResources.clear();
    pathResources.clear();
    paints.clear();
    regions.clear();
}

#if HWUI_NEW_OPS
size_t DisplayListData::addChild(RenderNodeOp* op) {
    mReferenceHolders.push_back(op->renderNode);
#else
size_t DisplayListData::addChild(DrawRenderNodeOp* op) {
    mReferenceHolders.push_back(op->renderNode);
#endif
    size_t index = mChildren.size();
    mChildren.push_back(op);
    return index;
}

}; // namespace uirenderer
}; // namespace android
