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

#include <GLES2/gl2.h>

#include "LayerCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

LayerCache::LayerCache(uint32_t maxByteSize):
        mCache(GenerationCache<LayerSize, Layer*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(maxByteSize) {
}

LayerCache::~LayerCache() {
    mCache.setOnEntryRemovedListener(this);
    mCache.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t LayerCache::getSize() {
    return mSize;
}

uint32_t LayerCache::getMaxSize() {
    return mMaxSize;
}

void LayerCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        Layer* oldest = mCache.removeOldest();
        deleteLayer(oldest);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void LayerCache::operator()(LayerSize& size, Layer*& layer) {
    deleteLayer(layer);
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void LayerCache::deleteLayer(Layer* layer) {
    if (layer) {
        mSize -= layer->layer.getWidth() * layer->layer.getHeight() * 4;

        glDeleteFramebuffers(1, &layer->fbo);
        glDeleteTextures(1, &layer->texture);
        delete layer;
    }
}

void LayerCache::clear() {
    mCache.setOnEntryRemovedListener(this);
    mCache.clear();
    mCache.setOnEntryRemovedListener(NULL);
}

Layer* LayerCache::get(LayerSize& size) {
    Layer* layer = mCache.remove(size);
    if (layer) {
        mSize -= layer->layer.getWidth() * layer->layer.getHeight() * 4;
    }
    return layer;
}

bool LayerCache::put(LayerSize& layerSize, Layer* layer) {
    const uint32_t size = layerSize.width * layerSize.height * 4;
    // Don't even try to cache a layer that's bigger than the cache
    if (size < mMaxSize) {
        while (mSize + size > mMaxSize) {
            Layer* oldest = mCache.removeOldest();
            deleteLayer(oldest);
        }

        mCache.put(layerSize, layer);
        mSize += size;

        return true;
    }
    return false;
}

}; // namespace uirenderer
}; // namespace android
