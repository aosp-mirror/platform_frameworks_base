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

#ifndef ANDROID_HWUI_TEXT_DROP_SHADOW_CACHE_H
#define ANDROID_HWUI_TEXT_DROP_SHADOW_CACHE_H

#include <GLES2/gl2.h>

#include <SkPaint.h>

#include <utils/LruCache.h>
#include <utils/String16.h>

#include "Texture.h"
#include "font/Font.h"

namespace android {
namespace uirenderer {

class Caches;
class FontRenderer;

struct ShadowText {
    ShadowText()
            : glyphCount(0)
            , radius(0.0f)
            , textSize(0.0f)
            , typeface(nullptr)
            , flags(0)
            , italicStyle(0.0f)
            , scaleX(0)
            , glyphs(nullptr)
            , positions(nullptr) {}

    // len is the number of bytes in text
    ShadowText(const SkPaint* paint, float radius, uint32_t glyphCount, const glyph_t* srcGlyphs,
               const float* positions)
            : glyphCount(glyphCount)
            , radius(radius)
            , textSize(paint->getTextSize())
            , typeface(paint->getTypeface())
            , flags(paint->isFakeBoldText() ? Font::kFakeBold : 0)
            , italicStyle(paint->getTextSkewX())
            , scaleX(paint->getTextScaleX())
            , glyphs(srcGlyphs)
            , positions(positions) {}

    ~ShadowText() {}

    hash_t hash() const;

    static int compare(const ShadowText& lhs, const ShadowText& rhs);

    bool operator==(const ShadowText& other) const { return compare(*this, other) == 0; }

    bool operator!=(const ShadowText& other) const { return compare(*this, other) != 0; }

    void copyTextLocally() {
        str.setTo(reinterpret_cast<const char16_t*>(glyphs), glyphCount);
        glyphs = reinterpret_cast<const glyph_t*>(str.string());
        if (positions != nullptr) {
            positionsCopy.clear();
            positionsCopy.appendArray(positions, glyphCount * 2);
            positions = positionsCopy.array();
        }
    }

    uint32_t glyphCount;
    float radius;
    float textSize;
    SkTypeface* typeface;
    uint32_t flags;
    float italicStyle;
    float scaleX;
    const glyph_t* glyphs;
    const float* positions;

    // Not directly used to compute the cache key
    String16 str;
    Vector<float> positionsCopy;

};  // struct ShadowText

// Caching support

inline int strictly_order_type(const ShadowText& lhs, const ShadowText& rhs) {
    return ShadowText::compare(lhs, rhs) < 0;
}

inline int compare_type(const ShadowText& lhs, const ShadowText& rhs) {
    return ShadowText::compare(lhs, rhs);
}

inline hash_t hash_type(const ShadowText& entry) {
    return entry.hash();
}

/**
 * Alpha texture used to represent a shadow.
 */
struct ShadowTexture : public Texture {
    explicit ShadowTexture(Caches& caches) : Texture(caches) {}

    float left;
    float top;
};  // struct ShadowTexture

class TextDropShadowCache : public OnEntryRemoved<ShadowText, ShadowTexture*> {
public:
    TextDropShadowCache();
    explicit TextDropShadowCache(uint32_t maxByteSize);
    ~TextDropShadowCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(ShadowText& text, ShadowTexture*& texture) override;

    ShadowTexture* get(const SkPaint* paint, const glyph_t* text, int numGlyphs, float radius,
                       const float* positions);

    /**
     * Clears the cache. This causes all textures to be deleted.
     */
    void clear();

    void setFontRenderer(FontRenderer& fontRenderer) { mRenderer = &fontRenderer; }

    /**
     * Returns the maximum size of the cache in bytes.
     */
    uint32_t getMaxSize();
    /**
     * Returns the current size of the cache in bytes.
     */
    uint32_t getSize();

private:
    LruCache<ShadowText, ShadowTexture*> mCache;

    uint32_t mSize;
    const uint32_t mMaxSize;
    FontRenderer* mRenderer = nullptr;
    bool mDebugEnabled;
};  // class TextDropShadowCache

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_TEXT_DROP_SHADOW_CACHE_H
