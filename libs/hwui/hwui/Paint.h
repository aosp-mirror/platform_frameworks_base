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

#ifndef ANDROID_GRAPHICS_PAINT_H_
#define ANDROID_GRAPHICS_PAINT_H_

#include "Typeface.h"

#include <cutils/compiler.h>

#include <SkFont.h>
#include <SkPaint.h>
#include <string>

#include <minikin/FontFamily.h>
#include <minikin/FamilyVariant.h>

namespace android {

class ANDROID_API Paint : public SkPaint {
public:
    // Default values for underlined and strikethrough text,
    // as defined by Skia in SkTextFormatParams.h.
    constexpr static float kStdStrikeThru_Offset = (-6.0f / 21.0f);
    constexpr static float kStdUnderline_Offset = (1.0f / 9.0f);
    constexpr static float kStdUnderline_Thickness = (1.0f / 18.0f);

    constexpr static float kStdUnderline_Top =
            kStdUnderline_Offset - 0.5f * kStdUnderline_Thickness;

    constexpr static float kStdStrikeThru_Thickness = kStdUnderline_Thickness;
    constexpr static float kStdStrikeThru_Top =
            kStdStrikeThru_Offset - 0.5f * kStdStrikeThru_Thickness;

    Paint();
    Paint(const Paint& paint);
    ~Paint();

    Paint& operator=(const Paint& other);

    friend bool operator==(const Paint& a, const Paint& b);
    friend bool operator!=(const Paint& a, const Paint& b) { return !(a == b); }

    SkFont& getSkFont() { return mFont; }
    const SkFont& getSkFont() const { return mFont; }

    // These shadow the methods on SkPaint, but we need to so we can keep related
    // attributes in-sync.

    void reset();
    void setAntiAlias(bool);

    // End method shadowing

    void setLetterSpacing(float letterSpacing) { mLetterSpacing = letterSpacing; }

    float getLetterSpacing() const { return mLetterSpacing; }

    void setWordSpacing(float wordSpacing) { mWordSpacing = wordSpacing; }

    float getWordSpacing() const { return mWordSpacing; }

    void setFontFeatureSettings(const std::string& fontFeatureSettings) {
        mFontFeatureSettings = fontFeatureSettings;
    }

    std::string getFontFeatureSettings() const { return mFontFeatureSettings; }

    void setMinikinLocaleListId(uint32_t minikinLocaleListId) {
        mMinikinLocaleListId = minikinLocaleListId;
    }

    uint32_t getMinikinLocaleListId() const { return mMinikinLocaleListId; }

    void setFamilyVariant(minikin::FamilyVariant variant) { mFamilyVariant = variant; }

    minikin::FamilyVariant getFamilyVariant() const { return mFamilyVariant; }

    void setHyphenEdit(uint32_t hyphen) { mHyphenEdit = hyphen; }

    uint32_t getHyphenEdit() const { return mHyphenEdit; }

    void setAndroidTypeface(Typeface* typeface) { mTypeface = typeface; }

    const Typeface* getAndroidTypeface() const { return mTypeface; }

    enum Align {
        kLeft_Align,
        kCenter_Align,
        kRight_Align,
    };
    Align getTextAlign() const { return mAlign; }
    void setTextAlign(Align align) { mAlign = align; }

    bool isStrikeThru() const { return mStrikeThru; }
    void setStrikeThru(bool st) { mStrikeThru = st; }

    bool isUnderline() const { return mUnderline; }
    void setUnderline(bool u) { mUnderline = u; }

    bool isDevKern() const { return mDevKern; }
    void setDevKern(bool d) { mDevKern = d; }

    // The Java flags (Paint.java) no longer fit into the native apis directly.
    // These methods handle converting to and from them and the native representations
    // in android::Paint.

    uint32_t getJavaFlags() const;
    void setJavaFlags(uint32_t);

    // Helpers that return or apply legacy java flags to SkPaint, ignoring all flags
    // that are meant for SkFont or Paint (e.g. underline, strikethru)
    // The only respected flags are : [ antialias, dither, filterBitmap ]
    static uint32_t GetSkPaintJavaFlags(const SkPaint&);
    static void SetSkPaintJavaFlags(SkPaint*, uint32_t flags);
 
private:
    SkFont mFont;

    float mLetterSpacing = 0;
    float mWordSpacing = 0;
    std::string mFontFeatureSettings;
    uint32_t mMinikinLocaleListId;
    minikin::FamilyVariant mFamilyVariant;
    uint32_t mHyphenEdit = 0;
    // The native Typeface object has the same lifetime of the Java Typeface
    // object. The Java Paint object holds a strong reference to the Java Typeface
    // object. Thus, following pointer can never be a dangling pointer. Note that
    // nullptr is valid: it means the default typeface.
    const Typeface* mTypeface = nullptr;
    Align mAlign = kLeft_Align;
    bool mStrikeThru = false;
    bool mUnderline = false;
    bool mDevKern = false;
};

}  // namespace android

#endif  // ANDROID_GRAPHICS_PAINT_H_
