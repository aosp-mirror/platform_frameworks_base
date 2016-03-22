/*
 * Copyright (C) 2014 The Android Open Source Project
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
#include "MinikinUtils.h"

#include "Paint.h"
#include "SkPathMeasure.h"
#include "Typeface.h"

#include <cutils/log.h>
#include <string>

namespace android {

FontStyle MinikinUtils::prepareMinikinPaint(MinikinPaint* minikinPaint, FontCollection** pFont,
        const Paint* paint, Typeface* typeface) {
    const Typeface* resolvedFace = Typeface::resolveDefault(typeface);
    *pFont = resolvedFace->fFontCollection;
    FontStyle resolved = resolvedFace->fStyle;

    /* Prepare minikin FontStyle */
    FontVariant minikinVariant = (paint->getFontVariant() == VARIANT_ELEGANT) ? VARIANT_ELEGANT
            : VARIANT_COMPACT;
    const uint32_t langListId = paint->getMinikinLangListId();
    FontStyle minikinStyle(langListId, minikinVariant, resolved.getWeight(), resolved.getItalic());

    /* Prepare minikin Paint */
    // Note: it would be nice to handle fractional size values (it would improve smooth zoom
    // behavior), but historically size has been treated as an int.
    // TODO: explore whether to enable fractional sizes, possibly when linear text flag is set.
    minikinPaint->size = (int)paint->getTextSize();
    minikinPaint->scaleX = paint->getTextScaleX();
    minikinPaint->skewX = paint->getTextSkewX();
    minikinPaint->letterSpacing = paint->getLetterSpacing();
    minikinPaint->paintFlags = MinikinFontSkia::packPaintFlags(paint);
    minikinPaint->fontFeatureSettings = paint->getFontFeatureSettings();
    minikinPaint->hyphenEdit = HyphenEdit(paint->getHyphenEdit());
    return minikinStyle;
}

void MinikinUtils::doLayout(Layout* layout, const Paint* paint, int bidiFlags,
        Typeface* typeface, const uint16_t* buf, size_t start, size_t count,
        size_t bufSize) {
    FontCollection *font;
    MinikinPaint minikinPaint;
    FontStyle minikinStyle = prepareMinikinPaint(&minikinPaint, &font, paint, typeface);
    layout->setFontCollection(font);
    layout->doLayout(buf, start, count, bufSize, bidiFlags, minikinStyle, minikinPaint);
}

float MinikinUtils::measureText(const Paint* paint, int bidiFlags, Typeface* typeface,
        const uint16_t* buf, size_t start, size_t count, size_t bufSize, float *advances) {
    FontCollection *font;
    MinikinPaint minikinPaint;
    FontStyle minikinStyle = prepareMinikinPaint(&minikinPaint, &font, paint, typeface);
    return Layout::measureText(buf, start, count, bufSize, bidiFlags, minikinStyle, minikinPaint,
            font, advances);
}

bool MinikinUtils::hasVariationSelector(Typeface* typeface, uint32_t codepoint, uint32_t vs) {
    const Typeface* resolvedFace = Typeface::resolveDefault(typeface);
    return resolvedFace->fFontCollection->hasVariationSelector(codepoint, vs);
}

float MinikinUtils::xOffsetForTextAlign(Paint* paint, const Layout& layout) {
    switch (paint->getTextAlign()) {
        case Paint::kCenter_Align:
            return layout.getAdvance() * -0.5f;
            break;
        case Paint::kRight_Align:
            return -layout.getAdvance();
            break;
        default:
            break;
    }
    return 0;
}

float MinikinUtils::hOffsetForTextAlign(Paint* paint, const Layout& layout, const SkPath& path) {
    float align = 0;
    switch (paint->getTextAlign()) {
        case Paint::kCenter_Align:
            align = -0.5f;
            break;
        case Paint::kRight_Align:
            align = -1;
            break;
        default:
            return 0;
    }
    SkPathMeasure measure(path, false);
    return align * (layout.getAdvance() - measure.getLength());
}

}
