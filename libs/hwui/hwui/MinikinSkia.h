/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef _ANDROID_GRAPHICS_MINIKIN_SKIA_H_
#define _ANDROID_GRAPHICS_MINIKIN_SKIA_H_

#include <cutils/compiler.h>
#include <minikin/MinikinFont.h>

class SkPaint;
class SkTypeface;

namespace android {

class ANDROID_API MinikinFontSkia : public MinikinFont {
public:
    // Note: this takes ownership of the reference (will unref on dtor)
    explicit MinikinFontSkia(SkTypeface *typeface, const void* fontData, size_t fontSize,
        int ttcIndex);

    ~MinikinFontSkia();

    float GetHorizontalAdvance(uint32_t glyph_id,
        const MinikinPaint &paint) const;

    void GetBounds(MinikinRect* bounds, uint32_t glyph_id,
        const MinikinPaint &paint) const;

    const void* GetTable(uint32_t tag, size_t* size, MinikinDestroyFunc* destroy);

    SkTypeface* GetSkTypeface() const;

    // Access to underlying raw font bytes
    const void* GetFontData() const;
    size_t GetFontSize() const;
    int GetFontIndex() const;

    static uint32_t packPaintFlags(const SkPaint* paint);
    static void unpackPaintFlags(SkPaint* paint, uint32_t paintFlags);

    // set typeface and fake bold/italic parameters
    static void populateSkPaint(SkPaint* paint, const MinikinFont* font, FontFakery fakery);
private:
    SkTypeface* mTypeface;

    // A raw pointer to the font data - it should be owned by some other object with
    // lifetime at least as long as this object.
    const void* mFontData;
    size_t mFontSize;
    int mTtcIndex;
};

}  // namespace android

#endif  // _ANDROID_GRAPHICS_MINIKIN_SKIA_H_
