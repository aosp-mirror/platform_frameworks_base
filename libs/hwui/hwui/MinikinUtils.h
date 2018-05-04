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

/**
 * Utilities for making Minikin work, especially from existing objects like
 * Paint and so on.
 **/

// TODO: does this really need to be separate from MinikinSkia?

#ifndef _ANDROID_GRAPHICS_MINIKIN_UTILS_H_
#define _ANDROID_GRAPHICS_MINIKIN_UTILS_H_

#include <cutils/compiler.h>
#include <minikin/Layout.h>
#include "MinikinSkia.h"
#include "Paint.h"
#include "Typeface.h"
#include <log/log.h>

namespace minikin {
class MeasuredText;
}  // namespace minikin

namespace android {

class MinikinUtils {
public:
    ANDROID_API static minikin::MinikinPaint prepareMinikinPaint(const Paint* paint,
                                                                 const Typeface* typeface);

    ANDROID_API static minikin::Layout doLayout(const Paint* paint, minikin::Bidi bidiFlags,
                                                const Typeface* typeface, const uint16_t* buf,
                                                size_t bufSize, size_t start, size_t count,
                                                size_t contextStart, size_t contextCount,
                                                minikin::MeasuredText* mt);

    ANDROID_API static float measureText(const Paint* paint, minikin::Bidi bidiFlags,
                                         const Typeface* typeface, const uint16_t* buf,
                                         size_t start, size_t count, size_t bufSize,
                                         float* advances);

    ANDROID_API static bool hasVariationSelector(const Typeface* typeface, uint32_t codepoint,
                                                 uint32_t vs);

    ANDROID_API static float xOffsetForTextAlign(Paint* paint, const minikin::Layout& layout);

    ANDROID_API static float hOffsetForTextAlign(Paint* paint, const minikin::Layout& layout,
                                                 const SkPath& path);
    // f is a functor of type void f(size_t start, size_t end);
    template <typename F>
    ANDROID_API static void forFontRun(const minikin::Layout& layout, Paint* paint, F& f) {
        float saveSkewX = paint->getTextSkewX();
        bool savefakeBold = paint->isFakeBoldText();
        const minikin::MinikinFont* curFont = nullptr;
        size_t start = 0;
        size_t nGlyphs = layout.nGlyphs();
        for (size_t i = 0; i < nGlyphs; i++) {
            const minikin::MinikinFont* nextFont = layout.getFont(i);
            if (i > 0 && nextFont != curFont) {
                MinikinFontSkia::populateSkPaint(paint, curFont, layout.getFakery(start));
                f(start, i);
                paint->setTextSkewX(saveSkewX);
                paint->setFakeBoldText(savefakeBold);
                start = i;
            }
            curFont = nextFont;
        }
        if (nGlyphs > start) {
            MinikinFontSkia::populateSkPaint(paint, curFont, layout.getFakery(start));
            f(start, nGlyphs);
            paint->setTextSkewX(saveSkewX);
            paint->setFakeBoldText(savefakeBold);
        }
    }
};

}  // namespace android

#endif  // _ANDROID_GRAPHICS_MINIKIN_UTILS_H_
