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
    for (size_t i = 0; i < count; i++) {
        delete mCache.valueAt(i);
    }
    mCache.clear();
}

Patch* PatchCache::get(const float bitmapWidth, const float bitmapHeight,
        const float pixelWidth, const float pixelHeight,
        const int32_t* xDivs, const int32_t* yDivs, const uint32_t* colors,
        const uint32_t width, const uint32_t height, const int8_t numColors) {

    int8_t transparentQuads = 0;
    uint32_t colorKey = 0;

    if (uint8_t(numColors) < sizeof(uint32_t) * 4) {
        for (int8_t i = 0; i < numColors; i++) {
            if (colors[i] == 0x0) {
                transparentQuads++;
                colorKey |= 0x1 << i;
            }
        }
    }

    // If the 9patch is made of only transparent quads
    if (transparentQuads == int8_t((width + 1) * (height + 1))) {
        return NULL;
    }

    const PatchDescription description(bitmapWidth, bitmapHeight,
            pixelWidth, pixelHeight, width, height, transparentQuads, colorKey);

    ssize_t index = mCache.indexOfKey(description);
    Patch* mesh = NULL;
    if (index >= 0) {
        mesh = mCache.valueAt(index);
    }

    if (!mesh) {
        PATCH_LOGD("New patch mesh "
                "xCount=%d yCount=%d, w=%.2f h=%.2f, bw=%.2f bh=%.2f",
                width, height, pixelWidth, pixelHeight, bitmapWidth, bitmapHeight);

        mesh = new Patch(width, height, transparentQuads);
        mesh->updateColorKey(colorKey);
        mesh->copy(xDivs, yDivs);
        mesh->updateVertices(bitmapWidth, bitmapHeight, 0.0f, 0.0f, pixelWidth, pixelHeight);

        if (mCache.size() >= mMaxEntries) {
            delete mCache.valueAt(mCache.size() - 1);
            mCache.removeItemsAt(mCache.size() - 1, 1);
        }

        mCache.add(description, mesh);
    } else if (!mesh->matches(xDivs, yDivs, colorKey)) {
        PATCH_LOGD("Patch mesh does not match, refreshing vertices");
        mesh->updateVertices(bitmapWidth, bitmapHeight, 0.0f, 0.0f, pixelWidth, pixelHeight);
    }

    return mesh;
}

}; // namespace uirenderer
}; // namespace android
