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

#include <minikin/Layout.h>
#include "Paint.h"
#include "MinikinSkia.h"
#include "TypefaceImpl.h"

namespace android {

class MinikinUtils {
public:
    static FontStyle prepareMinikinPaint(MinikinPaint* minikinPaint, FontCollection** pFont,
            const Paint* paint, TypefaceImpl* typeface);

    static void doLayout(Layout* layout, const Paint* paint, int bidiFlags,
            TypefaceImpl* typeface, const uint16_t* buf, size_t start, size_t count,
            size_t bufSize);

    static float xOffsetForTextAlign(Paint* paint, const Layout& layout);

    static float hOffsetForTextAlign(Paint* paint, const Layout& layout, const SkPath& path);
    // f is a functor of type void f(size_t start, size_t end);
    template <typename F>
    static void forFontRun(const Layout& layout, Paint* paint, F& f) {
        float saveSkewX = paint->getTextSkewX();
        bool savefakeBold = paint->isFakeBoldText();
        MinikinFont* curFont = NULL;
        size_t start = 0;
        size_t nGlyphs = layout.nGlyphs();
        for (size_t i = 0; i < nGlyphs; i++) {
            MinikinFont* nextFont = layout.getFont(i);
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
