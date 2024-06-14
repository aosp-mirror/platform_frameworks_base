/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "MinikinSkia.h"

#include <SkFont.h>
#include <SkFontDescriptor.h>
#include <SkFontMetrics.h>
#include <SkFontMgr.h>
#include <SkRect.h>
#include <SkScalar.h>
#include <SkStream.h>
#include <SkTypeface.h>
#include <log/log.h>

#include <minikin/Font.h>
#include <minikin/MinikinExtent.h>
#include <minikin/MinikinPaint.h>
#include <minikin/MinikinRect.h>
#include <utils/TypefaceUtils.h>

namespace android {

MinikinFontSkia::MinikinFontSkia(sk_sp<SkTypeface> typeface, int sourceId, const void* fontData,
                                 size_t fontSize, std::string_view filePath, int ttcIndex,
                                 const std::vector<minikin::FontVariation>& axes)
        : mTypeface(std::move(typeface))
        , mSourceId(sourceId)
        , mFontData(fontData)
        , mFontSize(fontSize)
        , mTtcIndex(ttcIndex)
        , mAxes(axes)
        , mFilePath(filePath) {}

static void MinikinFontSkia_SetSkiaFont(const minikin::MinikinFont* font, SkFont* skFont,
                                        const minikin::MinikinPaint& paint,
                                        const minikin::FontFakery& fakery) {
    skFont->setSize(paint.size);
    skFont->setScaleX(paint.scaleX);
    skFont->setSkewX(paint.skewX);
    MinikinFontSkia::unpackFontFlags(skFont, paint.fontFlags);
    // Apply font fakery on top of user-supplied flags.
    MinikinFontSkia::populateSkFont(skFont, font, fakery);
}

float MinikinFontSkia::GetHorizontalAdvance(uint32_t glyph_id, const minikin::MinikinPaint& paint,
                                            const minikin::FontFakery& fakery) const {
    SkFont skFont;
    uint16_t glyph16 = glyph_id;
    SkScalar skWidth;
    MinikinFontSkia_SetSkiaFont(this, &skFont, paint, fakery);
    skFont.getWidths(&glyph16, 1, &skWidth);
#ifdef VERBOSE
    ALOGD("width for typeface %d glyph %d = %f", mTypeface->uniqueID(), glyph_id, skWidth);
#endif
    return skWidth;
}

void MinikinFontSkia::GetHorizontalAdvances(uint16_t* glyph_ids, uint32_t count,
                                            const minikin::MinikinPaint& paint,
                                            const minikin::FontFakery& fakery,
                                            float* outAdvances) const {
    SkFont skFont;
    MinikinFontSkia_SetSkiaFont(this, &skFont, paint, fakery);
    skFont.getWidths(glyph_ids, count, outAdvances);
}

void MinikinFontSkia::GetBounds(minikin::MinikinRect* bounds, uint32_t glyph_id,
                                const minikin::MinikinPaint& paint,
                                const minikin::FontFakery& fakery) const {
    SkFont skFont;
    uint16_t glyph16 = glyph_id;
    SkRect skBounds;
    MinikinFontSkia_SetSkiaFont(this, &skFont, paint, fakery);
    skFont.getWidths(&glyph16, 1, nullptr, &skBounds);
    bounds->mLeft = skBounds.fLeft;
    bounds->mTop = skBounds.fTop;
    bounds->mRight = skBounds.fRight;
    bounds->mBottom = skBounds.fBottom;
}

void MinikinFontSkia::GetFontExtent(minikin::MinikinExtent* extent,
                                    const minikin::MinikinPaint& paint,
                                    const minikin::FontFakery& fakery) const {
    SkFont skFont;
    MinikinFontSkia_SetSkiaFont(this, &skFont, paint, fakery);
    SkFontMetrics metrics;
    skFont.getMetrics(&metrics);
    extent->ascent = metrics.fAscent;
    extent->descent = metrics.fDescent;
}

SkTypeface* MinikinFontSkia::GetSkTypeface() const {
    return mTypeface.get();
}

sk_sp<SkTypeface> MinikinFontSkia::RefSkTypeface() const {
    return mTypeface;
}

const void* MinikinFontSkia::GetFontData() const {
    return mFontData;
}

size_t MinikinFontSkia::GetFontSize() const {
    return mFontSize;
}

int MinikinFontSkia::GetFontIndex() const {
    return mTtcIndex;
}

const std::vector<minikin::FontVariation>& MinikinFontSkia::GetAxes() const {
    return mAxes;
}

std::shared_ptr<minikin::MinikinFont> MinikinFontSkia::createFontWithVariation(
        const std::vector<minikin::FontVariation>& variations) const {
    SkFontArguments args;

    std::vector<SkFontArguments::VariationPosition::Coordinate> skVariation;
    skVariation.resize(variations.size());
    for (size_t i = 0; i < variations.size(); i++) {
        skVariation[i].axis = variations[i].axisTag;
        skVariation[i].value = SkFloatToScalar(variations[i].value);
    }
    args.setVariationDesignPosition({skVariation.data(), static_cast<int>(skVariation.size())});
    sk_sp<SkTypeface> face = mTypeface->makeClone(args);

    return std::make_shared<MinikinFontSkia>(std::move(face), mSourceId, mFontData, mFontSize,
                                             mFilePath, mTtcIndex, variations);
}

// hinting<<16 | edging<<8 | bools:5bits
uint32_t MinikinFontSkia::packFontFlags(const SkFont& font) {
    uint32_t flags = (unsigned)font.getHinting() << 16;
    flags |= (unsigned)font.getEdging() << 8;
    flags |= font.isEmbolden()          << minikin::Embolden_Shift;
    flags |= font.isLinearMetrics()     << minikin::LinearMetrics_Shift;
    flags |= font.isSubpixel()          << minikin::Subpixel_Shift;
    flags |= font.isEmbeddedBitmaps()   << minikin::EmbeddedBitmaps_Shift;
    flags |= font.isForceAutoHinting()  << minikin::ForceAutoHinting_Shift;
    return flags;
}

void MinikinFontSkia::unpackFontFlags(SkFont* font, uint32_t flags) {
    // We store hinting in the top 16 bits (only need 2 of them)
    font->setHinting((SkFontHinting)(flags >> 16));
    // We store edging in bits 8:15 (only need 2 of them)
    font->setEdging((SkFont::Edging)((flags >> 8) & 0xFF));
    font->setEmbolden(        (flags & minikin::Embolden_Flag) != 0);
    font->setLinearMetrics(   (flags & minikin::LinearMetrics_Flag) != 0);
    font->setSubpixel(        (flags & minikin::Subpixel_Flag) != 0);
    font->setEmbeddedBitmaps( (flags & minikin::EmbeddedBitmaps_Flag) != 0);
    font->setForceAutoHinting((flags & minikin::ForceAutoHinting_Flag) != 0);
}

void MinikinFontSkia::populateSkFont(SkFont* skFont, const MinikinFont* font,
                                     minikin::FontFakery fakery) {
    skFont->setTypeface(reinterpret_cast<const MinikinFontSkia*>(font)->RefSkTypeface());
    skFont->setEmbolden(skFont->isEmbolden() || fakery.isFakeBold());
    if (fakery.isFakeItalic()) {
        skFont->setSkewX(skFont->getSkewX() - 0.25f);
    }
}
}
