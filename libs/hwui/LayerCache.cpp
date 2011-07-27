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

#include <utils/Log.h>

#include "Debug.h"
#include "LayerCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

LayerCache::LayerCache(): mSize(0), mMaxSize(MB(DEFAULT_LAYER_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_LAYER_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting layer cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        INIT_LOGD("  Using default layer cache size of %.2fMB", DEFAULT_LAYER_CACHE_SIZE);
    }
}

LayerCache::~LayerCache() {
    clear();
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
    clear();
    mMaxSize = maxSize;
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void LayerCache::deleteLayer(Layer* layer) {
    if (layer) {
        mSize -= layer->getWidth() * layer->getHeight() * 4;
        layer->deleteFbo();
        layer->deleteTexture();
        delete layer;
    }
}

void LayerCache::clear() {
    size_t count = mCache.size();
    for (size_t i = 0; i < count; i++) {
        deleteLayer(mCache.itemAt(i).mLayer);
    }
    mCache.clear();
}

Layer* LayerCache::get(const uint32_t width, const uint32_t height) {
    Layer* layer = NULL;

    LayerEntry entry(width, height);
    ssize_t index = mCache.indexOf(entry);

    if (index >= 0) {
        entry = mCache.itemAt(index);
        mCache.removeAt(index);

        layer = entry.mLayer;
        mSize -= layer->getWidth() * layer->getHeight() * 4;

        LAYER_LOGD("Reusing layer %dx%d", layer->getWidth(), layer->getHeight());
    } else {
        LAYER_LOGD("Creating new layer %dx%d", entry.mWidth, entry.mHeight);

        layer = new Layer(entry.mWidth, entry.mHeight);
        layer->setBlend(true);
        layer->setEmpty(true);
        layer->setFbo(0);

        layer->generateTexture();
        layer->bindTexture();
        layer->setFilter(GL_NEAREST, GL_NEAREST);
        layer->setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, false);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

#if DEBUG_LAYERS
        size_t size = mCache.size();
        for (size_t i = 0; i < size; i++) {
            const LayerEntry& entry = mCache.itemAt(i);
            LAYER_LOGD("  Layer size %dx%d", entry.mWidth, entry.mHeight);
        }
#endif
    }

    return layer;
}

bool LayerCache::resize(Layer* layer, const uint32_t width, const uint32_t height) {
    // TODO: We should be smarter and see if we have a texture of the appropriate
    //       size already in the cache, and reuse it instead of creating a new one

    LayerEntry entry(width, height);
    if (entry.mWidth <= layer->getWidth() && entry.mHeight <= layer->getHeight()) {
        return true;
    }

    uint32_t oldWidth = layer->getWidth();
    uint32_t oldHeight = layer->getHeight();

    glActiveTexture(GL_TEXTURE0);
    layer->bindTexture();
    layer->setSize(entry.mWidth, entry.mHeight);
    layer->allocateTexture(GL_RGBA, GL_UNSIGNED_BYTE);

    if (glGetError() != GL_NO_ERROR) {
        layer->setSize(oldWidth, oldHeight);
        return false;
    }

    return true;
}

bool LayerCache::put(Layer* layer) {
    if (!layer->isCacheable()) return false;

    const uint32_t size = layer->getWidth() * layer->getHeight() * 4;
    // Don't even try to cache a layer that's bigger than the cache
    if (size < mMaxSize) {
        // TODO: Use an LRU
        while (mSize + size > mMaxSize) {
            size_t position = 0;
#if LAYER_REMOVE_BIGGEST
            position = mCache.size() - 1;
#endif
            Layer* victim = mCache.itemAt(position).mLayer;
            deleteLayer(victim);
            mCache.removeAt(position);

            LAYER_LOGD("  Deleting layer %.2fx%.2f", victim->layer.getWidth(),
                    victim->layer.getHeight());
        }

        LayerEntry entry(layer);

        mCache.add(entry);
        mSize += size;

        return true;
    }
    return false;
}

}; // namespace uirenderer
}; // namespace android
