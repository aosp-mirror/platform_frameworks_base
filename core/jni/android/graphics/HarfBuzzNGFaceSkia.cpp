/*
 * Copyright (c) 2012 Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#define LOG_TAG "TextLayoutCache"

#include "HarfBuzzNGFaceSkia.h"

#include <cutils/log.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkPoint.h>
#include <SkRect.h>
#include <SkTypeface.h>
#include <SkUtils.h>

#include <hb.h>

namespace android {

static const bool kDebugGlyphs = false;

// Our implementation of the callbacks which Harfbuzz requires by using Skia
// calls. See the Harfbuzz source for references about what these callbacks do.

struct HarfBuzzFontData {
    explicit HarfBuzzFontData(SkPaint* paint) : m_paint(paint) { }
    SkPaint* m_paint;
};

static void SkiaGetGlyphWidthAndExtents(SkPaint* paint, hb_codepoint_t codepoint, hb_position_t* width, hb_glyph_extents_t* extents)
{
    ALOG_ASSERT(codepoint <= 0xFFFF);
    paint->setTextEncoding(SkPaint::kGlyphID_TextEncoding);

    SkScalar skWidth;
    SkRect skBounds;
    uint16_t glyph = codepoint;

    paint->getTextWidths(&glyph, sizeof(glyph), &skWidth, &skBounds);
    if (kDebugGlyphs) {
        ALOGD("returned glyph for %i: width = %f", codepoint, skWidth);
    }
    if (width)
        *width = SkScalarToHBFixed(skWidth);
    if (extents) {
        // Invert y-axis because Skia is y-grows-down but we set up harfbuzz to be y-grows-up.
        extents->x_bearing = SkScalarToHBFixed(skBounds.fLeft);
        extents->y_bearing = SkScalarToHBFixed(-skBounds.fTop);
        extents->width = SkScalarToHBFixed(skBounds.width());
        extents->height = SkScalarToHBFixed(-skBounds.height());
    }
}

static hb_bool_t harfbuzzGetGlyph(hb_font_t* hbFont, void* fontData, hb_codepoint_t unicode, hb_codepoint_t variationSelector, hb_codepoint_t* glyph, void* userData)
{
    HarfBuzzFontData* hbFontData = reinterpret_cast<HarfBuzzFontData*>(fontData);

    if (unicode > 0x10ffff) {
        unicode = 0xfffd;
    }
    SkPaint* paint = hbFontData->m_paint;
    // It would be better to use kUTF32_TextEncoding directly
    paint->setTextEncoding(SkPaint::kUTF16_TextEncoding);
    uint16_t glyph16;
    uint16_t unichar[2];
    size_t size = SkUTF16_FromUnichar(unicode, unichar);
    paint->textToGlyphs(unichar, size * sizeof(*unichar), &glyph16);
    *glyph = glyph16;
    return !!*glyph;
}

static hb_position_t harfbuzzGetGlyphHorizontalAdvance(hb_font_t* hbFont, void* fontData, hb_codepoint_t glyph, void* userData)
{
    HarfBuzzFontData* hbFontData = reinterpret_cast<HarfBuzzFontData*>(fontData);
    hb_position_t advance = 0;

    SkiaGetGlyphWidthAndExtents(hbFontData->m_paint, glyph, &advance, 0);
    return advance;
}

static hb_bool_t harfbuzzGetGlyphHorizontalOrigin(hb_font_t* hbFont, void* fontData, hb_codepoint_t glyph, hb_position_t* x, hb_position_t* y, void* userData)
{
    // Just return true, following the way that Harfbuzz-FreeType
    // implementation does.
    return true;
}

static hb_bool_t harfbuzzGetGlyphExtents(hb_font_t* hbFont, void* fontData, hb_codepoint_t glyph, hb_glyph_extents_t* extents, void* userData)
{
    HarfBuzzFontData* hbFontData = reinterpret_cast<HarfBuzzFontData*>(fontData);

    SkiaGetGlyphWidthAndExtents(hbFontData->m_paint, glyph, 0, extents);
    return true;
}

static hb_font_funcs_t* harfbuzzSkiaGetFontFuncs()
{
    static hb_font_funcs_t* harfbuzzSkiaFontFuncs = 0;

    // We don't set callback functions which we can't support.
    // Harfbuzz will use the fallback implementation if they aren't set.
    if (!harfbuzzSkiaFontFuncs) {
        harfbuzzSkiaFontFuncs = hb_font_funcs_create();
        hb_font_funcs_set_glyph_func(harfbuzzSkiaFontFuncs, harfbuzzGetGlyph, 0, 0);
        hb_font_funcs_set_glyph_h_advance_func(harfbuzzSkiaFontFuncs, harfbuzzGetGlyphHorizontalAdvance, 0, 0);
        hb_font_funcs_set_glyph_h_origin_func(harfbuzzSkiaFontFuncs, harfbuzzGetGlyphHorizontalOrigin, 0, 0);
        hb_font_funcs_set_glyph_extents_func(harfbuzzSkiaFontFuncs, harfbuzzGetGlyphExtents, 0, 0);
        hb_font_funcs_make_immutable(harfbuzzSkiaFontFuncs);
    }
    return harfbuzzSkiaFontFuncs;
}

hb_blob_t* harfbuzzSkiaReferenceTable(hb_face_t* face, hb_tag_t tag, void* userData)
{
    SkTypeface* typeface = reinterpret_cast<SkTypeface*>(userData);

    const size_t tableSize = typeface->getTableSize(tag);
    if (!tableSize)
        return 0;

    char* buffer = reinterpret_cast<char*>(malloc(tableSize));
    if (!buffer)
        return 0;
    size_t actualSize = typeface->getTableData(tag, 0, tableSize, buffer);
    if (tableSize != actualSize) {
        free(buffer);
        return 0;
    }

    return hb_blob_create(const_cast<char*>(buffer), tableSize,
                          HB_MEMORY_MODE_WRITABLE, buffer, free);
}

static void destroyHarfBuzzFontData(void* data) {
    delete (HarfBuzzFontData*)data;
}

hb_font_t* createFont(hb_face_t* face, SkPaint* paint, float sizeX, float sizeY) {
    hb_font_t* font = hb_font_create(face);
    
    // Note: this needs to be reworked when we do subpixels
    int x_ppem = floor(sizeX + 0.5);
    int y_ppem = floor(sizeY + 0.5);
    hb_font_set_ppem(font, x_ppem, y_ppem); 
    hb_font_set_scale(font, HBFloatToFixed(sizeX), HBFloatToFixed(sizeY));

    HarfBuzzFontData* data = new HarfBuzzFontData(paint);
    hb_font_set_funcs(font, harfbuzzSkiaGetFontFuncs(), data, destroyHarfBuzzFontData);

    return font;
}

} // namespace android
