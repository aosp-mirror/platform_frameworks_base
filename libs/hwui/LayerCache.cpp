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

#include "LayerCache.h"

#include "Caches.h"
#include "Properties.h"

#include <utils/Log.h>

#include <GLES2/gl2.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

LayerCache::LayerCache()
        : mSize(0)
        , mMaxSize(Properties::layerPoolSize) {}

LayerCache::~LayerCache() {
    clear();
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

size_t LayerCache::getCount() {
    return mCache.size();
}

uint32_t LayerCache::getSize() {
    return mSize;
}

uint32_t LayerCache::getMaxSize() {
    return mMaxSize;
}

void LayerCache::setMaxSize(uint32_t maxSize) {
    clear();
    mMaxSize = maxSize;
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

int LayerCache::LayerEntry::compare(const LayerCache::LayerEntry& lhs,
        const LayerCache::LayerEntry& rhs) {
    int deltaInt = int(lhs.mWidth) - int(rhs.mWidth);
    if (deltaInt != 0) return deltaInt;

    return int(lhs.mHeight) - int(rhs.mHeight);
}

void LayerCache::deleteLayer(Layer* layer) {
    if (layer) {
        LAYER_LOGD("Destroying layer %dx%d, fbo %d", layer->getWidth(), layer->getHeight(),
                layer->getFbo());
        mSize -= layer->getWidth() * layer->getHeight() * 4;
        layer->state = Layer::State::DeletedFromCache;
        layer->decStrong(nullptr);
    }
}

void LayerCache::clear() {
    for (auto entry : mCache) {
        deleteLayer(entry.mLayer);
    }
    mCache.clear();
}

Layer* LayerCache::get(RenderState& renderState, const uint32_t width, const uint32_t height) {
    Layer* layer = nullptr;

    LayerEntry entry(width, height);
    auto iter = mCache.find(entry);

    if (iter != mCache.end()) {
        entry = *iter;
        mCache.erase(iter);

        layer = entry.mLayer;
        layer->state = Layer::State::RemovedFromCache;
        mSize -= layer->getWidth() * layer->getHeight() * 4;

        LAYER_LOGD("Reusing layer %dx%d", layer->getWidth(), layer->getHeight());
    } else {
        LAYER_LOGD("Creating new layer %dx%d", entry.mWidth, entry.mHeight);

        layer = new Layer(Layer::Type::DisplayList, renderState, entry.mWidth, entry.mHeight);
        layer->setBlend(true);
        layer->generateTexture();
        layer->bindTexture();
        layer->setFilter(GL_NEAREST);
        layer->setWrap(GL_CLAMP_TO_EDGE, false);

#if DEBUG_LAYERS
        dump();
#endif
    }

    return layer;
}

void LayerCache::dump() {
    for (auto entry : mCache) {
        ALOGD("  Layer size %dx%d", entry.mWidth, entry.mHeight);
    }
}

bool LayerCache::put(Layer* layer) {
    if (!layer->isCacheable()) return false;

    const uint32_t size = layer->getWidth() * layer->getHeight() * 4;
    // Don't even try to cache a layer that's bigger than the cache
    if (size < mMaxSize) {
        // TODO: Use an LRU
        while (mSize + size > mMaxSize) {
            Layer* victim = mCache.begin()->mLayer;
            deleteLayer(victim);
            mCache.erase(mCache.begin());

            LAYER_LOGD("  Deleting layer %.2fx%.2f", victim->layer.getWidth(),
                    victim->layer.getHeight());
        }

        layer->cancelDefer();

        LayerEntry entry(layer);

        mCache.insert(entry);
        mSize += size;

        layer->state = Layer::State::InCache;
        return true;
    }

    layer->state = Layer::State::FailedToCache;
    return false;
}

}; // namespace uirenderer
}; // namespace android
