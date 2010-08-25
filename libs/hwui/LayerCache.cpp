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

LayerCache::LayerCache():
        mCache(GenerationCache<LayerSize, Layer*>::kUnlimitedCapacity),
        mIdGenerator(1), mSize(0), mMaxSize(MB(DEFAULT_LAYER_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_LAYER_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting layer cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default layer cache size of %.2fMB", DEFAULT_LAYER_CACHE_SIZE);
    }
}

LayerCache::LayerCache(uint32_t maxByteSize):
        mCache(GenerationCache<LayerSize, Layer*>::kUnlimitedCapacity),
        mIdGenerator(1), mSize(0), mMaxSize(maxByteSize) {
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

Layer* LayerCache::get(LayerSize& size, GLuint previousFbo) {
    Layer* layer = mCache.remove(size);
    if (layer) {
        LAYER_LOGD("Reusing layer");

        mSize -= layer->layer.getWidth() * layer->layer.getHeight() * 4;
    } else {
        LAYER_LOGD("Creating new layer");

        layer = new Layer;
        layer->blend = true;

        // Generate the FBO and attach the texture
        glGenFramebuffers(1, &layer->fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);

        // Generate the texture in which the FBO will draw
        glGenTextures(1, &layer->texture);
        glBindTexture(GL_TEXTURE_2D, layer->texture);

        // The FBO will not be scaled, so we can use lower quality filtering
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size.width, size.height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, NULL);

        // Bind texture to FBO
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                layer->texture, 0);

        GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("Framebuffer incomplete (GL error code 0x%x)", status);

            glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

            glDeleteFramebuffers(1, &layer->fbo);
            glDeleteTextures(1, &layer->texture);
            delete layer;

            return NULL;
        }
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

        layerSize.id = mIdGenerator++;
        mCache.put(layerSize, layer);
        mSize += size;

        return true;
    }
    return false;
}

}; // namespace uirenderer
}; // namespace android
