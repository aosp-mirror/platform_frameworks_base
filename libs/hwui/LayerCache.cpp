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
        LOGD("  Setting layer cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default layer cache size of %.2fMB", DEFAULT_LAYER_CACHE_SIZE);
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
        mSize -= layer->width * layer->height * 4;

        glDeleteTextures(1, &layer->texture);
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
        mSize -= layer->width * layer->height * 4;

        LAYER_LOGD("Reusing layer %dx%d", layer->width, layer->height);
    } else {
        LAYER_LOGD("Creating new layer %dx%d", entry.mWidth, entry.mHeight);

        layer = new Layer(entry.mWidth, entry.mHeight);
        layer->blend = true;
        layer->empty = true;
        layer->fbo = 0;

        glGenTextures(1, &layer->texture);
        glBindTexture(GL_TEXTURE_2D, layer->texture);

        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

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

bool LayerCache::put(Layer* layer) {
    const uint32_t size = layer->width * layer->height * 4;
    // Don't even try to cache a layer that's bigger than the cache
    if (size < mMaxSize) {
        // TODO: Use an LRU
        while (mSize + size > mMaxSize) {
            Layer* biggest = mCache.top().mLayer;
            deleteLayer(biggest);
            mCache.removeAt(mCache.size() - 1);

            LAYER_LOGD("  Deleting layer %.2fx%.2f", biggest->layer.getWidth(),
                    biggest->layer.getHeight());
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
