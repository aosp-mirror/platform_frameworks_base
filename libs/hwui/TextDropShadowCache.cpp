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

#include "Debug.h"
#include "TextDropShadowCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextDropShadowCache::TextDropShadowCache():
        mCache(GenerationCache<ShadowText, ShadowTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_DROP_SHADOW_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_DROP_SHADOW_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting drop shadow cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        INIT_LOGD("  Using default drop shadow cache size of %.2fMB",
                DEFAULT_DROP_SHADOW_CACHE_SIZE);
    }

    init();
}

TextDropShadowCache::TextDropShadowCache(uint32_t maxByteSize):
        mCache(GenerationCache<ShadowText, ShadowTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(maxByteSize) {
    init();
}

TextDropShadowCache::~TextDropShadowCache() {
    mCache.clear();
}

void TextDropShadowCache::init() {
    mCache.setOnEntryRemovedListener(this);
    mDebugEnabled = readDebugLevel() & kDebugMoreCaches;
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t TextDropShadowCache::getSize() {
    return mSize;
}

uint32_t TextDropShadowCache::getMaxSize() {
    return mMaxSize;
}

void TextDropShadowCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void TextDropShadowCache::operator()(ShadowText& text, ShadowTexture*& texture) {
    if (texture) {
        mSize -= texture->bitmapSize;

        if (mDebugEnabled) {
            ALOGD("Shadow texture deleted, size = %d", texture->bitmapSize);
        }

        glDeleteTextures(1, &texture->id);
        delete texture;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void TextDropShadowCache::clear() {
    mCache.clear();
}

ShadowTexture* TextDropShadowCache::get(SkPaint* paint, const char* text, uint32_t len,
        int numGlyphs, uint32_t radius) {
    ShadowText entry(paint, radius, len, text);
    ShadowTexture* texture = mCache.get(entry);

    if (!texture) {
        FontRenderer::DropShadow shadow = mRenderer->renderDropShadow(paint, text, 0,
                len, numGlyphs, radius);

        texture = new ShadowTexture;
        texture->left = shadow.penX;
        texture->top = shadow.penY;
        texture->width = shadow.width;
        texture->height = shadow.height;
        texture->generation = 0;
        texture->blend = true;

        const uint32_t size = shadow.width * shadow.height;
        texture->bitmapSize = size;

        // Don't even try to cache a bitmap that's bigger than the cache
        if (size < mMaxSize) {
            while (mSize + size > mMaxSize) {
                mCache.removeOldest();
            }
        }

        glGenTextures(1, &texture->id);

        glBindTexture(GL_TEXTURE_2D, texture->id);
        // Textures are Alpha8
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, texture->width, texture->height, 0,
                GL_ALPHA, GL_UNSIGNED_BYTE, shadow.image);

        texture->setFilter(GL_LINEAR, GL_LINEAR);
        texture->setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);

        if (size < mMaxSize) {
            if (mDebugEnabled) {
                ALOGD("Shadow texture created, size = %d", texture->bitmapSize);
            }

            entry.copyTextLocally();

            mSize += size;
            mCache.put(entry, texture);
        } else {
            texture->cleanup = true;
        }

        // Cleanup shadow
        delete[] shadow.image;
    }

    return texture;
}

}; // namespace uirenderer
}; // namespace android
