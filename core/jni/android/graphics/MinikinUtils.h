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
 * SkPaint and so on.
 **/

 // TODO: does this really need to be separate from MinikinSkia?

#ifndef ANDROID_MINIKIN_UTILS_H
#define ANDROID_MINIKIN_UTILS_H

namespace android {

class Layout;
class TypefaceImpl;

class MinikinUtils {
public:
    static void SetLayoutProperties(Layout* layout, const SkPaint* paint, int flags,
        TypefaceImpl* face);

    static float xOffsetForTextAlign(SkPaint* paint, const Layout& layout);

    // f is a functor of type void f(SkTypeface *, size_t start, size_t end);
    template <typename F>
    static void forFontRun(const Layout& layout, F& f) {
        SkTypeface* lastFace = NULL;
        size_t start = 0;
        size_t nGlyphs = layout.nGlyphs();
        for (size_t i = 0; i < nGlyphs; i++) {
            MinikinFontSkia* mfs = static_cast<MinikinFontSkia*>(layout.getFont(i));
            SkTypeface* skFace = mfs->GetSkTypeface();
            if (i > 0 && skFace != lastFace) {
                f(lastFace, start, i);
                start = i;
            }
            lastFace = skFace;
        }
        if (nGlyphs > start) {
            f(lastFace, start, nGlyphs);
        }
    }
};

}  // namespace android

#endif  // ANDROID_MINIKIN_UTILS_H
