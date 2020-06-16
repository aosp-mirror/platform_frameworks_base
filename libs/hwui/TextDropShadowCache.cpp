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

#include <utils/JenkinsHash.h>

#include "Caches.h"
#include "Debug.h"
#include "FontRenderer.h"
#include "TextDropShadowCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Cache support
///////////////////////////////////////////////////////////////////////////////

hash_t ShadowText::hash() const {
    uint32_t hash = JenkinsHashMix(0, glyphCount);
    hash = JenkinsHashMix(hash, android::hash_type(radius));
    hash = JenkinsHashMix(hash, android::hash_type(textSize));
    hash = JenkinsHashMix(hash, android::hash_type(typeface));
    hash = JenkinsHashMix(hash, flags);
    hash = JenkinsHashMix(hash, android::hash_type(italicStyle));
    hash = JenkinsHashMix(hash, android::hash_type(scaleX));
    if (glyphs) {
        hash = JenkinsHashMixShorts(
            hash, reinterpret_cast<const uint16_t*>(glyphs), glyphCount);
    }
    if (positions) {
        for (uint32_t i = 0; i < glyphCount * 2; i++) {
            hash = JenkinsHashMix(hash, android::hash_type(positions[i]));
        }
    }
    return JenkinsHashWhiten(hash);
}

int ShadowText::compare(const ShadowText& lhs, const ShadowText& rhs) {
    int deltaInt = int(lhs.glyphCount) - int(rhs.glyphCount);
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

    if (lhs.glyphs != rhs.glyphs) {
        if (!lhs.glyphs) return -1;
        if (!rhs.glyphs) return +1;

        deltaInt = memcmp(lhs.glyphs, rhs.glyphs, lhs.glyphCount * sizeof(glyph_t));
        if (deltaInt != 0) return deltaInt;
    }

    if (lhs.positions != rhs.positions) {
        if (!lhs.positions) return -1;
        if (!rhs.positions) return +1;

        return memcmp(lhs.positions, rhs.positions, lhs.glyphCount * sizeof(float) * 2);
    }

    return 0;
}

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextDropShadowCache::TextDropShadowCache()
        : TextDropShadowCache(Properties::textDropShadowCacheSize) {}

TextDropShadowCache::TextDropShadowCache(uint32_t maxByteSize)
        : mCache(LruCache<ShadowText, ShadowTexture*>::kUnlimitedCapacity)
        , mSize(0)
        , mMaxSize(maxByteSize) {
    mCache.setOnEntryRemovedListener(this);
    mDebugEnabled = Properties::debugLevel & kDebugMoreCaches;
}

TextDropShadowCache::~TextDropShadowCache() {
    mCache.clear();
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

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void TextDropShadowCache::operator()(ShadowText&, ShadowTexture*& texture) {
    if (texture) {
        mSize -= texture->objectSize();

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

ShadowTexture* TextDropShadowCache::get(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs,
        float radius, const float* positions) {
    ShadowText entry(paint, radius, numGlyphs, glyphs, positions);
    ShadowTexture* texture = mCache.get(entry);

    if (!texture) {
        SkPaint paintCopy(*paint);
        paintCopy.setTextAlign(SkPaint::kLeft_Align);
        FontRenderer::DropShadow shadow = mRenderer->renderDropShadow(&paintCopy, glyphs, numGlyphs,
                radius, positions);

        if (!shadow.image) {
            return nullptr;
        }

        Caches& caches = Caches::getInstance();

        texture = new ShadowTexture(caches);
        texture->left = shadow.penX;
        texture->top = shadow.penY;
        texture->generation = 0;
        texture->blend = true;

        const uint32_t size = shadow.width * shadow.height;

        // Don't even try to cache a bitmap that's bigger than the cache
        if (size < mMaxSize) {
            while (mSize + size > mMaxSize) {
                LOG_ALWAYS_FATAL_IF(!mCache.removeOldest(),
                        "Failed to remove oldest from cache. mSize = %"
                        PRIu32 ", mCache.size() = %zu", mSize, mCache.size());
            }
        }

        // Textures are Alpha8
        texture->upload(GL_ALPHA, shadow.width, shadow.height,
                GL_ALPHA, GL_UNSIGNED_BYTE, shadow.image);
        texture->setFilter(GL_LINEAR);
        texture->setWrap(GL_CLAMP_TO_EDGE);

        if (size < mMaxSize) {
            if (mDebugEnabled) {
                ALOGD("Shadow texture created, size = %d", texture->bitmapSize);
            }

            entry.copyTextLocally();

            mSize += texture->objectSize();
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
