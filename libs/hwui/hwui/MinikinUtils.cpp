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

#include <string>

#include <log/log.h>

#include "Paint.h"
#include "SkPathMeasure.h"
#include "Typeface.h"

namespace android {

minikin::MinikinPaint MinikinUtils::prepareMinikinPaint(const Paint* paint,
                                                        const Typeface* typeface) {
    const Typeface* resolvedFace = Typeface::resolveDefault(typeface);
    minikin::FontStyle resolved = resolvedFace->fStyle;
    const minikin::FontVariant minikinVariant =
            (paint->getFontVariant() == minikin::FontVariant::ELEGANT)
                    ? minikin::FontVariant::ELEGANT
                    : minikin::FontVariant::COMPACT;
    float textSize = paint->getTextSize();
    if (!paint->isLinearText()) {
        // If linear text is not specified, truncate the value.
        textSize = trunc(textSize);
    }
    return minikin::MinikinPaint(
            textSize,
            paint->getTextScaleX(),
            paint->getTextSkewX(),
            paint->getLetterSpacing(),
            paint->getWordSpacing(),
            MinikinFontSkia::packPaintFlags(paint),
            paint->getMinikinLocaleListId(),
            minikin::FontStyle(minikinVariant, resolved.weight, resolved.slant),
            minikin::HyphenEdit(paint->getHyphenEdit()),
            paint->getFontFeatureSettings(),
            resolvedFace->fFontCollection);
}

minikin::Layout MinikinUtils::doLayout(const Paint* paint, minikin::Bidi bidiFlags,
                                       const Typeface* typeface, const uint16_t* buf, size_t start,
                                       size_t count, size_t bufSize) {
    minikin::Layout layout;
    layout.doLayout(buf, start, count, bufSize, bidiFlags, prepareMinikinPaint(paint, typeface));
    return layout;
}

float MinikinUtils::measureText(const Paint* paint, minikin::Bidi bidiFlags,
                                const Typeface* typeface, const uint16_t* buf, size_t start,
                                size_t count, size_t bufSize, float* advances) {
    return minikin::Layout::measureText(
            buf, start, count, bufSize, bidiFlags, prepareMinikinPaint(paint, typeface), advances,
            nullptr /* extent */, nullptr /* overhangs */);
}

bool MinikinUtils::hasVariationSelector(const Typeface* typeface, uint32_t codepoint, uint32_t vs) {
    const Typeface* resolvedFace = Typeface::resolveDefault(typeface);
    return resolvedFace->fFontCollection->hasVariationSelector(codepoint, vs);
}

float MinikinUtils::xOffsetForTextAlign(Paint* paint, const minikin::Layout& layout) {
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

float MinikinUtils::hOffsetForTextAlign(Paint* paint, const minikin::Layout& layout,
                                        const SkPath& path) {
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
}  // namespace android
