/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_HWUI_FONT_H
#define ANDROID_HWUI_FONT_H

#include <vector>

#include <utils/KeyedVector.h>

#include <SkScalar.h>
#include <SkDeviceProperties.h>
#include <SkGlyphCache.h>
#include <SkScalerContext.h>
#include <SkPaint.h>
#include <SkPathMeasure.h>

#include "CachedGlyphInfo.h"
#include "../Rect.h"
#include "../Matrix.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Font
///////////////////////////////////////////////////////////////////////////////

class FontRenderer;

/**
 * Represents a font, defined by a Skia font id and a font size. A font is used
 * to generate glyphs and cache them in the FontState.
 */
class Font {
public:
    enum Style {
        kFakeBold = 1
    };

    struct FontDescription {
        FontDescription(const SkPaint* paint, const SkMatrix& matrix);

        static int compare(const FontDescription& lhs, const FontDescription& rhs);

        hash_t hash() const;

        bool operator==(const FontDescription& other) const {
            return compare(*this, other) == 0;
        }

        bool operator!=(const FontDescription& other) const {
            return compare(*this, other) != 0;
        }

        SkFontID mFontId;
        float mFontSize;
        int mFlags;
        float mItalicStyle;
        float mScaleX;
        uint8_t mStyle;
        float mStrokeWidth;
        bool mAntiAliasing;
        uint8_t mHinting;
        SkMatrix mLookupTransform;
        SkMatrix mInverseLookupTransform;
    };

    ~Font();

    void render(const SkPaint* paint, const char* text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, const float* positions);

    void render(const SkPaint* paint, const char* text, uint32_t start, uint32_t len,
            int numGlyphs, const SkPath* path, float hOffset, float vOffset);

    const Font::FontDescription& getDescription() const {
        return mDescription;
    }

    /**
     * Creates a new font associated with the specified font state.
     */
    static Font* create(FontRenderer* state, const SkPaint* paint, const SkMatrix& matrix);

private:
    friend class FontRenderer;

    Font(FontRenderer* state, const Font::FontDescription& desc);

    typedef void (Font::*RenderGlyph)(CachedGlyphInfo*, int, int, uint8_t*,
            uint32_t, uint32_t, Rect*, const float*);

    enum RenderMode {
        FRAMEBUFFER,
        BITMAP,
        MEASURE,
    };

    void precache(const SkPaint* paint, const char* text, int numGlyphs);

    void render(const SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, RenderMode mode, uint8_t *bitmap,
            uint32_t bitmapW, uint32_t bitmapH, Rect *bounds, const float* positions);

    void measure(const SkPaint* paint, const char* text, uint32_t start, uint32_t len,
            int numGlyphs, Rect *bounds, const float* positions);

    void invalidateTextureCache(CacheTexture* cacheTexture = NULL);

    CachedGlyphInfo* cacheGlyph(const SkPaint* paint, glyph_t glyph, bool precaching);
    void updateGlyphCache(const SkPaint* paint, const SkGlyph& skiaGlyph,
            SkGlyphCache* skiaGlyphCache, CachedGlyphInfo* glyph, bool precaching);

    void measureCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyphTransformed(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyphBitmap(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyph(CachedGlyphInfo* glyph, float x, float hOffset, float vOffset,
            SkPathMeasure& measure, SkPoint* position, SkVector* tangent);

    CachedGlyphInfo* getCachedGlyph(const SkPaint* paint, glyph_t textUnit,
            bool precaching = false);

    FontRenderer* mState;
    FontDescription mDescription;

    // Cache of glyphs
    DefaultKeyedVector<glyph_t, CachedGlyphInfo*> mCachedGlyphs;

    bool mIdentityTransform;
    SkDeviceProperties mDeviceProperties;
};

inline int strictly_order_type(const Font::FontDescription& lhs,
        const Font::FontDescription& rhs) {
    return Font::FontDescription::compare(lhs, rhs) < 0;
}

inline int compare_type(const Font::FontDescription& lhs, const Font::FontDescription& rhs) {
    return Font::FontDescription::compare(lhs, rhs);
}

inline hash_t hash_type(const Font::FontDescription& entry) {
    return entry.hash();
}

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FONT_H
