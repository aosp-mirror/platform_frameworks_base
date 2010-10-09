/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include <utils/Log.h>
#include <utils/ResourceTypes.h>

#include "PatchCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

PatchCache::PatchCache(): mMaxEntries(DEFAULT_PATCH_CACHE_SIZE) {
}

PatchCache::PatchCache(uint32_t maxEntries): mMaxEntries(maxEntries) {
}

PatchCache::~PatchCache() {
    clear();
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void PatchCache::clear() {
    size_t count = mCache.size();
    for (int i = 0; i < count; i++) {
        delete mCache.valueAt(i);
    }
    mCache.clear();
}

Patch* PatchCache::get(const float bitmapWidth, const float bitmapHeight,
        const float pixelWidth, const float pixelHeight,
        const int32_t* xDivs, const int32_t* yDivs,
        const uint32_t width, const uint32_t height) {

    const PatchDescription description(bitmapWidth, bitmapHeight,
            pixelWidth, pixelHeight, width, height);

    ssize_t index = mCache.indexOfKey(description);
    Patch* mesh = NULL;
    if (index >= 0) {
        mesh = mCache.valueAt(index);
    }

    if (!mesh) {
        PATCH_LOGD("Creating new patch mesh, w=%d h=%d", width, height);

        mesh = new Patch(width, height);
        mesh->updateVertices(bitmapWidth, bitmapHeight, 0.0f, 0.0f,
                pixelWidth, pixelHeight, xDivs, yDivs, width, height);

        if (mCache.size() >= mMaxEntries) {
            delete mCache.valueAt(0);
            mCache.removeItemsAt(0, 1);
        }

        mCache.add(description, mesh);
    }

    return mesh;
}

}; // namespace uirenderer
}; // namespace android
