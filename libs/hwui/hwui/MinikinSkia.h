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

#include <SkRefCnt.h>
#include <cutils/compiler.h>
#include <minikin/MinikinFont.h>
#include <string>
#include <string_view>

class SkFont;
class SkTypeface;

namespace android {

class ANDROID_API MinikinFontSkia : public minikin::MinikinFont {
public:
    MinikinFontSkia(sk_sp<SkTypeface> typeface, const void* fontData, size_t fontSize,
                    std::string_view filePath, int ttcIndex,
                    const std::vector<minikin::FontVariation>& axes);

    float GetHorizontalAdvance(uint32_t glyph_id, const minikin::MinikinPaint& paint,
                               const minikin::FontFakery& fakery) const override;

    void GetHorizontalAdvances(uint16_t* glyph_ids, uint32_t count,
                               const minikin::MinikinPaint& paint,
                               const minikin::FontFakery& fakery,
                               float* outAdvances) const override;

    void GetBounds(minikin::MinikinRect* bounds, uint32_t glyph_id,
                   const minikin::MinikinPaint& paint,
                   const minikin::FontFakery& fakery) const override;

    void GetFontExtent(minikin::MinikinExtent* extent, const minikin::MinikinPaint& paint,
                       const minikin::FontFakery& fakery) const override;

    SkTypeface* GetSkTypeface() const;
    sk_sp<SkTypeface> RefSkTypeface() const;

    // Access to underlying raw font bytes
    const void* GetFontData() const;
    size_t GetFontSize() const;
    int GetFontIndex() const;
    const std::string& getFilePath() const { return mFilePath; }
    const std::vector<minikin::FontVariation>& GetAxes() const;
    std::shared_ptr<minikin::MinikinFont> createFontWithVariation(
            const std::vector<minikin::FontVariation>&) const;

    static uint32_t packFontFlags(const SkFont&);
    static void unpackFontFlags(SkFont*, uint32_t fontFlags);

    // set typeface and fake bold/italic parameters
    static void populateSkFont(SkFont*, const minikin::MinikinFont* font,
                               minikin::FontFakery fakery);

private:
    sk_sp<SkTypeface> mTypeface;

    // A raw pointer to the font data - it should be owned by some other object with
    // lifetime at least as long as this object.
    const void* mFontData;
    size_t mFontSize;
    int mTtcIndex;
    std::vector<minikin::FontVariation> mAxes;
    std::string mFilePath;
};

}  // namespace android

#endif  // _ANDROID_GRAPHICS_MINIKIN_SKIA_H_
