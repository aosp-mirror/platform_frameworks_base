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

#define ATRACE_TAG ATRACE_TAG_VIEW

#include <SkCanvas.h>
#include <algorithm>

#include <utils/Trace.h>

#include "Debug.h"
#include "DisplayList.h"
#include "DisplayListOp.h"
#include "DisplayListLogBuffer.h"

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
    Caches& caches = Caches::getInstance();
    caches.unregisterFunctors(functors.size());
    caches.resourceCache.lock();

    for (size_t i = 0; i < bitmapResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(bitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < ownedBitmapResources.size(); i++) {
        const SkBitmap* bitmap = ownedBitmapResources.itemAt(i);
        caches.resourceCache.decrementRefcountLocked(bitmap);
        caches.resourceCache.destructorLocked(bitmap);
    }

    for (size_t i = 0; i < patchResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(patchResources.itemAt(i));
    }

    for (size_t i = 0; i < sourcePaths.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(sourcePaths.itemAt(i));
    }

    for (size_t i = 0; i < layers.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(layers.itemAt(i));
    }

    caches.resourceCache.unlock();

    for (size_t i = 0; i < paints.size(); i++) {
        delete paints.itemAt(i);
    }

    for (size_t i = 0; i < regions.size(); i++) {
        delete regions.itemAt(i);
    }

    for (size_t i = 0; i < paths.size(); i++) {
        delete paths.itemAt(i);
    }

    bitmapResources.clear();
    ownedBitmapResources.clear();
    patchResources.clear();
    sourcePaths.clear();
    paints.clear();
    regions.clear();
    paths.clear();
    layers.clear();
}

size_t DisplayListData::addChild(DrawRenderNodeOp* op) {
    mReferenceHolders.push(op->renderNode());
    return mChildren.add(op);
}

}; // namespace uirenderer
}; // namespace android
