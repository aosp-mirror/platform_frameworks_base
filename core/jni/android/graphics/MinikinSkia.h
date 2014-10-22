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

#ifndef ANDROID_MINIKIN_SKIA_H
#define ANDROID_MINIKIN_SKIA_H

#include <minikin/MinikinFont.h>

namespace android {

class MinikinFontSkia : public MinikinFont {
public:
    // Note: this takes ownership of the reference (will unref on dtor)
    explicit MinikinFontSkia(SkTypeface *typeface);

    ~MinikinFontSkia();

    bool GetGlyph(uint32_t codepoint, uint32_t *glyph) const;

    float GetHorizontalAdvance(uint32_t glyph_id,
        const MinikinPaint &paint) const;

    void GetBounds(MinikinRect* bounds, uint32_t glyph_id,
        const MinikinPaint &paint) const;

    // If buf is NULL, just update size
    bool GetTable(uint32_t tag, uint8_t *buf, size_t *size);

    int32_t GetUniqueId() const;

    SkTypeface* GetSkTypeface() const;

    static uint32_t packPaintFlags(const SkPaint* paint);
    static void unpackPaintFlags(SkPaint* paint, uint32_t paintFlags);

    // set typeface and fake bold/italic parameters
    static void populateSkPaint(SkPaint* paint, const MinikinFont* font, FontFakery fakery);
private:
    SkTypeface *mTypeface;
};

}  // namespace android

#endif  // ANDROID_MINIKIN_SKIA_H