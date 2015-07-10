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

#define LOG_TAG "Minikin"
#include <cutils/log.h>
#include <string>

#include "SkPathMeasure.h"
#include "Paint.h"
#include "TypefaceImpl.h"

#include "MinikinUtils.h"

namespace android {

FontStyle MinikinUtils::prepareMinikinPaint(MinikinPaint* minikinPaint, FontCollection** pFont,
        const Paint* paint, TypefaceImpl* typeface) {
    const TypefaceImpl* resolvedFace = TypefaceImpl_resolveDefault(typeface);
    *pFont = resolvedFace->fFontCollection;
    FontStyle resolved = resolvedFace->fStyle;

    /* Prepare minikin FontStyle */
    const std::string& lang = paint->getTextLocale();
    FontLanguage minikinLang(lang.c_str(), lang.size());
    FontVariant minikinVariant = (paint->getFontVariant() == VARIANT_ELEGANT) ? VARIANT_ELEGANT
            : VARIANT_COMPACT;
    FontStyle minikinStyle(minikinLang, minikinVariant, resolved.getWeight(), resolved.getItalic());

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
        TypefaceImpl* typeface, const uint16_t* buf, size_t start, size_t count,
        size_t bufSize) {
    FontCollection *font;
    MinikinPaint minikinPaint;
    FontStyle minikinStyle = prepareMinikinPaint(&minikinPaint, &font, paint, typeface);
    layout->setFontCollection(font);
    layout->doLayout(buf, start, count, bufSize, bidiFlags, minikinStyle, minikinPaint);
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
