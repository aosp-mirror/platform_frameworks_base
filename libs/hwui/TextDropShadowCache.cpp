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

#include <utils/JenkinsHash.h>

#include "Caches.h"
#include "Debug.h"
#include "TextDropShadowCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Cache support
///////////////////////////////////////////////////////////////////////////////

hash_t ShadowText::hash() const {
    uint32_t charCount = len / sizeof(char16_t);
    uint32_t hash = JenkinsHashMix(0, len);
    hash = JenkinsHashMix(hash, android::hash_type(radius));
    hash = JenkinsHashMix(hash, android::hash_type(textSize));
    hash = JenkinsHashMix(hash, android::hash_type(typeface));
    hash = JenkinsHashMix(hash, flags);
    hash = JenkinsHashMix(hash, android::hash_type(italicStyle));
    hash = JenkinsHashMix(hash, android::hash_type(scaleX));
    if (text) {
        hash = JenkinsHashMixShorts(hash, text, charCount);
    }
    if (positions) {
        for (uint32_t i = 0; i < charCount * 2; i++) {
            hash = JenkinsHashMix(hash, android::hash_type(positions[i]));
        }
    }
    return JenkinsHashWhiten(hash);
}

int ShadowText::compare(const ShadowText& lhs, const ShadowText& rhs) {
    int deltaInt = int(lhs.len) - int(rhs.len);
    if (deltaInt != 0) return deltaInt;

    deltaInt = lhs.flags - rhs.flags;
    if (deltaInt != 0) return deltaInt;

    if (lhs.radius < rhs.radius) return -1;
    if (lhs.radius > rhs.radius) return +1;

    if (lhs.typeface < rhs.typeface) return -1;
    if (lhs.typeface > rhs.typeface) return +1;

    if (lhs.textSize < rhs.textSize) return -1;
    if (lhs.textSize > rhs.textSize) return +1;

    if (lhs.italicStyle < rhs.italicStyle) return -1;
    if (lhs.italicStyle > rhs.italicStyle) return +1;

    if (lhs.scaleX < rhs.scaleX) return -1;
    if (lhs.scaleX > rhs.scaleX) return +1;

    if (lhs.text != rhs.text) {
        if (!lhs.text) return -1;
        if (!rhs.text) return +1;

        deltaInt = memcmp(lhs.text, rhs.text, lhs.len);
        if (deltaInt != 0) return deltaInt;
    }

    if (lhs.positions != rhs.positions) {
        if (!lhs.positions) return -1;
        if (!rhs.positions) return +1;

        return memcmp(lhs.positions, rhs.positions, lhs.len << 2);
    }

    return 0;
}

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextDropShadowCache::TextDropShadowCache():
        mCache(LruCache<ShadowText, ShadowTexture*>::kUnlimitedCapacity),
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
        mCache(LruCache<ShadowText, ShadowTexture*>::kUnlimitedCapacity),
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

void TextDropShadowCache::operator()(ShadowText&, ShadowTexture*& texture) {
    if (texture) {
        mSize -= texture->bitmapSize;

        if (mDebugEnabled) {
            ALOGD("Shadow texture deleted, size = %d", texture->bitmapSize);
        }

        texture->deleteTexture();
        delete texture;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void TextDropShadowCache::clear() {
    mCache.clear();
}

ShadowTexture* TextDropShadowCache::get(const SkPaint* paint, const char* text, uint32_t len,
        int numGlyphs, float radius, const float* positions) {
    ShadowText entry(paint, radius, len, text, positions);
    ShadowTexture* texture = mCache.get(entry);

    if (!texture) {
        SkPaint paintCopy(*paint);
        paintCopy.setTextAlign(SkPaint::kLeft_Align);
        FontRenderer::DropShadow shadow = mRenderer->renderDropShadow(&paintCopy, text, 0,
                len, numGlyphs, radius, positions);

        if (!shadow.image) {
            return NULL;
        }

        Caches& caches = Caches::getInstance();

        texture = new ShadowTexture(caches);
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

        caches.bindTexture(texture->id);
        // Textures are Alpha8
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, texture->width, texture->height, 0,
                GL_ALPHA, GL_UNSIGNED_BYTE, shadow.image);

        texture->setFilter(GL_LINEAR);
        texture->setWrap(GL_CLAMP_TO_EDGE);

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
        free(shadow.image);
    }

    return texture;
}

}; // namespace uirenderer
}; // namespace android
