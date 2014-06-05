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

#include "SkPaint.h"
#include "minikin/Layout.h"
#include "TypefaceImpl.h"
#include "MinikinSkia.h"

#include "MinikinUtils.h"

namespace android {

// Do an sprintf starting at offset n, abort on overflow
static int snprintfcat(char* buf, int off, int size, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int n = vsnprintf(buf + off, size - off, format, args);
    LOG_ALWAYS_FATAL_IF(n >= size - off, "String overflow in setting layout properties");
    va_end(args);
    return off + n;
}

std::string MinikinUtils::setLayoutProperties(Layout* layout, const SkPaint* paint, int bidiFlags,
        TypefaceImpl* typeface) {
    TypefaceImpl* resolvedFace = TypefaceImpl_resolveDefault(typeface);
    layout->setFontCollection(resolvedFace->fFontCollection);
    FontStyle style = resolvedFace->fStyle;
    char css[256];
    int off = snprintfcat(css, 0, sizeof(css),
        "font-size: %d; font-scale-x: %f; font-skew-x: %f; -paint-flags: %d;"
        " font-weight: %d; font-style: %s; -minikin-bidi: %d;",
        (int)paint->getTextSize(),
        paint->getTextScaleX(),
        paint->getTextSkewX(),
        MinikinFontSkia::packPaintFlags(paint),
        style.getWeight() * 100,
        style.getItalic() ? "italic" : "normal",
        bidiFlags);
    SkString langString = paint->getPaintOptionsAndroid().getLanguage().getTag();
    off = snprintfcat(css, off, sizeof(css), " lang: %s;", langString.c_str());
    SkPaintOptionsAndroid::FontVariant var = paint->getPaintOptionsAndroid().getFontVariant();
    const char* varstr = var == SkPaintOptionsAndroid::kElegant_Variant ? "elegant" : "compact";
    off = snprintfcat(css, off, sizeof(css), " -minikin-variant: %s;", varstr);
    layout->setProperties(css);
    return std::string(css);
}

float MinikinUtils::xOffsetForTextAlign(SkPaint* paint, const Layout& layout) {
    switch (paint->getTextAlign()) {
        case SkPaint::kCenter_Align:
            return layout.getAdvance() * -0.5f;
            break;
        case SkPaint::kRight_Align:
            return -layout.getAdvance();
            break;
        default:
            break;
    }
    return 0;
}

}
