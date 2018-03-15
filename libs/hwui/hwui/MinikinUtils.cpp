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

#include <minikin/MeasuredText.h>
#include "Paint.h"
#include "SkPathMeasure.h"
#include "Typeface.h"

namespace android {

minikin::MinikinPaint MinikinUtils::prepareMinikinPaint(const Paint* paint,
                                                        const Typeface* typeface) {
    const Typeface* resolvedFace = Typeface::resolveDefault(typeface);

    minikin::MinikinPaint minikinPaint(resolvedFace->fFontCollection);
    /* Prepare minikin Paint */
    minikinPaint.size =
            paint->isLinearText() ? paint->getTextSize() : static_cast<int>(paint->getTextSize());
    minikinPaint.scaleX = paint->getTextScaleX();
    minikinPaint.skewX = paint->getTextSkewX();
    minikinPaint.letterSpacing = paint->getLetterSpacing();
    minikinPaint.wordSpacing = paint->getWordSpacing();
    minikinPaint.paintFlags = MinikinFontSkia::packPaintFlags(paint);
    minikinPaint.localeListId = paint->getMinikinLocaleListId();
    minikinPaint.familyVariant = paint->getFamilyVariant();
    minikinPaint.fontStyle = resolvedFace->fStyle;
    minikinPaint.fontFeatureSettings = paint->getFontFeatureSettings();
    return minikinPaint;
}

minikin::Layout MinikinUtils::doLayout(const Paint* paint, minikin::Bidi bidiFlags,
                                       const Typeface* typeface, const uint16_t* buf, size_t start,
                                       size_t count, size_t bufSize, minikin::MeasuredText* mt) {
    minikin::MinikinPaint minikinPaint = prepareMinikinPaint(paint, typeface);
    minikin::Layout layout;

    const minikin::U16StringPiece textBuf(buf, bufSize);
    const minikin::Range range(start, start + count);
    const minikin::HyphenEdit hyphenEdit = static_cast<minikin::HyphenEdit>(paint->getHyphenEdit());
    const minikin::StartHyphenEdit startHyphen = minikin::startHyphenEdit(hyphenEdit);
    const minikin::EndHyphenEdit endHyphen = minikin::endHyphenEdit(hyphenEdit);

    if (mt == nullptr) {
        layout.doLayout(textBuf,range, bidiFlags, minikinPaint, startHyphen, endHyphen);
    } else {
        mt->buildLayout(textBuf, range, minikinPaint, bidiFlags, startHyphen, endHyphen, &layout);
    }
    return layout;
}

float MinikinUtils::measureText(const Paint* paint, minikin::Bidi bidiFlags,
                                const Typeface* typeface, const uint16_t* buf, size_t start,
                                size_t count, size_t bufSize, float* advances) {
    minikin::MinikinPaint minikinPaint = prepareMinikinPaint(paint, typeface);
    const minikin::U16StringPiece textBuf(buf, bufSize);
    const minikin::Range range(start, start + count);
    const minikin::HyphenEdit hyphenEdit = static_cast<minikin::HyphenEdit>(paint->getHyphenEdit());
    const minikin::StartHyphenEdit startHyphen = minikin::startHyphenEdit(hyphenEdit);
    const minikin::EndHyphenEdit endHyphen = minikin::endHyphenEdit(hyphenEdit);

    return minikin::Layout::measureText(textBuf, range, bidiFlags, minikinPaint, startHyphen,
                                        endHyphen, advances, nullptr /* extent */,
                                        nullptr /* layout pieces */);
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
