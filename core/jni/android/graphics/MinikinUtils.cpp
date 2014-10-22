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

// Do an sprintf starting at offset n, abort on overflow
static int snprintfcat(char* buf, int off, int size, const char* format, ...)
        __attribute__((__format__(__printf__, 4, 5)));
static int snprintfcat(char* buf, int off, int size, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int n = vsnprintf(buf + off, size - off, format, args);
    LOG_ALWAYS_FATAL_IF(n >= size - off, "String overflow in setting layout properties");
    va_end(args);
    return off + n;
}

void MinikinUtils::doLayout(Layout* layout, const Paint* paint, int bidiFlags, TypefaceImpl* typeface,
        const uint16_t* buf, size_t start, size_t count, size_t bufSize) {
    TypefaceImpl* resolvedFace = TypefaceImpl_resolveDefault(typeface);
    layout->setFontCollection(resolvedFace->fFontCollection);
    FontStyle resolved = resolvedFace->fStyle;

    /* Prepare minikin FontStyle */
    std::string lang = paint->getTextLocale();
    FontLanguage minikinLang(lang.c_str(), lang.size());
    FontVariant minikinVariant = (paint->getFontVariant() == VARIANT_ELEGANT) ? VARIANT_ELEGANT
            : VARIANT_COMPACT;
    FontStyle minikinStyle(minikinLang, minikinVariant, resolved.getWeight(), resolved.getItalic());

    /* Prepare minikin Paint */
    MinikinPaint minikinPaint;
    minikinPaint.size = (int)/*WHY?!*/paint->getTextSize();
    minikinPaint.scaleX = paint->getTextScaleX();
    minikinPaint.skewX = paint->getTextSkewX();
    minikinPaint.letterSpacing = paint->getLetterSpacing();
    minikinPaint.paintFlags = MinikinFontSkia::packPaintFlags(paint);
    minikinPaint.fontFeatureSettings = paint->getFontFeatureSettings();

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
