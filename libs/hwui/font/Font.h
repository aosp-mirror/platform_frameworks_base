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

#include <utils/KeyedVector.h>

#include <SkScalerContext.h>
#include <SkPaint.h>
#include <SkPathMeasure.h>

#include "CachedGlyphInfo.h"
#include "../Rect.h"

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

    ~Font();

    /**
     * Renders the specified string of text.
     * If bitmap is specified, it will be used as the render target
     */
    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, uint8_t *bitmap = NULL,
            uint32_t bitmapW = 0, uint32_t bitmapH = 0);

    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, const float* positions);

    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, SkPath* path, float hOffset, float vOffset);

    /**
     * Creates a new font associated with the specified font state.
     */
    static Font* create(FontRenderer* state, uint32_t fontId, float fontSize,
            int flags, uint32_t italicStyle, uint32_t scaleX, SkPaint::Style style,
            uint32_t strokeWidth);

private:
    friend class FontRenderer;
    typedef void (Font::*RenderGlyph)(CachedGlyphInfo*, int, int, uint8_t*,
            uint32_t, uint32_t, Rect*, const float*);

    enum RenderMode {
        FRAMEBUFFER,
        BITMAP,
        MEASURE,
    };

    void precache(SkPaint* paint, const char* text, int numGlyphs);

    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, RenderMode mode, uint8_t *bitmap,
            uint32_t bitmapW, uint32_t bitmapH, Rect *bounds, const float* positions);

    void measure(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
            int numGlyphs, Rect *bounds, const float* positions);

    Font(FontRenderer* state, uint32_t fontId, float fontSize, int flags, uint32_t italicStyle,
            uint32_t scaleX, SkPaint::Style style, uint32_t strokeWidth);

    // Cache of glyphs
    DefaultKeyedVector<glyph_t, CachedGlyphInfo*> mCachedGlyphs;

    void invalidateTextureCache(CacheTexture* cacheTexture = NULL);

    CachedGlyphInfo* cacheGlyph(SkPaint* paint, glyph_t glyph, bool precaching);
    void updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo* glyph,
            bool precaching);

    void measureCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyphBitmap(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyph(CachedGlyphInfo* glyph, float x, float hOffset, float vOffset,
            SkPathMeasure& measure, SkPoint* position, SkVector* tangent);

    CachedGlyphInfo* getCachedGlyph(SkPaint* paint, glyph_t textUnit, bool precaching = false);

    FontRenderer* mState;
    uint32_t mFontId;
    float mFontSize;
    int mFlags;
    uint32_t mItalicStyle;
    uint32_t mScaleX;
    SkPaint::Style mStyle;
    uint32_t mStrokeWidth;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FONT_H
