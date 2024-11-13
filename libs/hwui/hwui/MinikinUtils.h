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
#include <log/log.h>
#include <minikin/Layout.h>

#include "FeatureFlags.h"
#include "MinikinSkia.h"
#include "Paint.h"
#include "Typeface.h"

namespace minikin {
class MeasuredText;
}  // namespace minikin

namespace android {

class MinikinUtils {
public:
    static minikin::MinikinPaint prepareMinikinPaint(const Paint* paint,
                                                                 const Typeface* typeface);

    static minikin::Layout doLayout(const Paint* paint, minikin::Bidi bidiFlags,
                                                const Typeface* typeface, const uint16_t* buf,
                                                size_t bufSize, size_t start, size_t count,
                                                size_t contextStart, size_t contextCount,
                                                minikin::MeasuredText* mt);

    static void getBounds(const Paint* paint, minikin::Bidi bidiFlags, const Typeface* typeface,
                          const uint16_t* buf, size_t bufSize, minikin::MinikinRect* out);

    static float measureText(const Paint* paint, minikin::Bidi bidiFlags, const Typeface* typeface,
                             const uint16_t* buf, size_t start, size_t count, size_t bufSize,
                             float* advances, minikin::MinikinRect* bounds, uint32_t* clusterCount);

    static minikin::MinikinExtent getFontExtent(const Paint* paint, minikin::Bidi bidiFlags,
                                                const Typeface* typeface, const uint16_t* buf,
                                                size_t start, size_t count, size_t bufSize);

    static bool hasVariationSelector(const Typeface* typeface, uint32_t codepoint,
                                                 uint32_t vs);

    static float xOffsetForTextAlign(Paint* paint, const minikin::Layout& layout);

    static float hOffsetForTextAlign(Paint* paint, const minikin::Layout& layout,
                                                 const SkPath& path);
    // f is a functor of type void f(size_t start, size_t end);
    template <typename F>
    static void forFontRun(const minikin::Layout& layout, Paint* paint, F& f) {
        float saveSkewX = paint->getSkFont().getSkewX();
        bool savefakeBold = paint->getSkFont().isEmbolden();
        if (text_feature::typeface_redesign()) {
            for (uint32_t runIdx = 0; runIdx < layout.getFontRunCount(); ++runIdx) {
                uint32_t start = layout.getFontRunStart(runIdx);
                uint32_t end = layout.getFontRunEnd(runIdx);
                const minikin::FakedFont& fakedFont = layout.getFontRunFont(runIdx);

                std::shared_ptr<minikin::MinikinFont> font = fakedFont.typeface();
                SkFont* skfont = &paint->getSkFont();
                MinikinFontSkia::populateSkFont(skfont, font.get(), fakedFont.fakery);
                f(start, end);
                skfont->setSkewX(saveSkewX);
                skfont->setEmbolden(savefakeBold);
            }
        } else {
            const minikin::MinikinFont* curFont = nullptr;
            size_t start = 0;
            size_t nGlyphs = layout.nGlyphs();
            for (size_t i = 0; i < nGlyphs; i++) {
                const minikin::MinikinFont* nextFont = layout.typeface(i).get();
                if (i > 0 && nextFont != curFont) {
                    SkFont* skfont = &paint->getSkFont();
                    MinikinFontSkia::populateSkFont(skfont, curFont, layout.getFakery(start));
                    f(start, i);
                    skfont->setSkewX(saveSkewX);
                    skfont->setEmbolden(savefakeBold);
                    start = i;
                }
                curFont = nextFont;
            }
            if (nGlyphs > start) {
                SkFont* skfont = &paint->getSkFont();
                MinikinFontSkia::populateSkFont(skfont, curFont, layout.getFakery(start));
                f(start, nGlyphs);
                skfont->setSkewX(saveSkewX);
                skfont->setEmbolden(savefakeBold);
            }
        }
    }
};

}  // namespace android

#endif  // _ANDROID_GRAPHICS_MINIKIN_UTILS_H_
