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

#include <utils/threads.h>

#include "PathCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Path cache
///////////////////////////////////////////////////////////////////////////////

PathCache::PathCache(): ShapeCache<PathCacheEntry>("path",
        PROPERTY_PATH_CACHE_SIZE, DEFAULT_PATH_CACHE_SIZE) {
}

void PathCache::remove(SkPath* path) {
    // TODO: Linear search...
    Vector<size_t> pathsToRemove;
    for (size_t i = 0; i < mCache.size(); i++) {
        if (mCache.getKeyAt(i).path == path) {
            pathsToRemove.push(i);
            removeTexture(mCache.getValueAt(i));
        }
    }

    mCache.setOnEntryRemovedListener(NULL);
    for (size_t i = 0; i < pathsToRemove.size(); i++) {
        // This will work because pathsToRemove is sorted
        // and because the cache is a sorted keyed vector
        mCache.removeAt(pathsToRemove.itemAt(i) - i);
    }
    mCache.setOnEntryRemovedListener(this);
}

void PathCache::removeDeferred(SkPath* path) {
    Mutex::Autolock _l(mLock);
    mGarbage.push(path);
}

void PathCache::clearGarbage() {
    Mutex::Autolock _l(mLock);
    size_t count = mGarbage.size();
    for (size_t i = 0; i < count; i++) {
        remove(mGarbage.itemAt(i));
    }
    mGarbage.clear();
}

PathTexture* PathCache::get(SkPath* path, SkPaint* paint) {
    PathCacheEntry entry(path, paint);
    PathTexture* texture = mCache.get(entry);

    if (!texture) {
        texture = addTexture(entry, path, paint);
    } else if (path->getGenerationID() != texture->generation) {
        mCache.remove(entry);
        texture = addTexture(entry, path, paint);
    }

    return texture;
}

}; // namespace uirenderer
}; // namespace android
