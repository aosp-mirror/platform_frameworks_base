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

#include <SkFont.h>
#include <SkPaint.h>
#include <SkSamplingOptions.h>
#include <cutils/compiler.h>
#include <minikin/FamilyVariant.h>
#include <minikin/FontFamily.h>
#include <minikin/FontFeature.h>
#include <minikin/Hyphenator.h>
#include <minikin/Layout.h>

#include <string>

#include "Typeface.h"

namespace android {

class BlurDrawLooper;

class Paint : public SkPaint {
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

    BlurDrawLooper* getLooper() const { return mLooper.get(); }
    void setLooper(sk_sp<BlurDrawLooper> looper);

    // These shadow the methods on SkPaint, but we need to so we can keep related
    // attributes in-sync.

    void reset();
    void setAntiAlias(bool);

    bool nothingToDraw() const { return !mLooper && SkPaint::nothingToDraw(); }

    // End method shadowing

    void setLetterSpacing(float letterSpacing) { mLetterSpacing = letterSpacing; }

    float getLetterSpacing() const { return mLetterSpacing; }

    void setWordSpacing(float wordSpacing) { mWordSpacing = wordSpacing; }

    float getWordSpacing() const { return mWordSpacing; }

    void setFontFeatureSettings(std::string_view fontFeatures) {
        mFontFeatureSettings = minikin::FontFeature::parse(fontFeatures);
    }

    void resetFontFeatures() { mFontFeatureSettings.clear(); }

    const std::vector<minikin::FontFeature>& getFontFeatureSettings() const {
        return mFontFeatureSettings;
    }

    void setMinikinLocaleListId(uint32_t minikinLocaleListId) {
        mMinikinLocaleListId = minikinLocaleListId;
    }

    uint32_t getMinikinLocaleListId() const { return mMinikinLocaleListId; }

    void resetFamilyVariant() { mFamilyVariant.reset(); }
    void setFamilyVariant(minikin::FamilyVariant variant) { mFamilyVariant = variant; }

    std::optional<minikin::FamilyVariant> getFamilyVariant() const { return mFamilyVariant; }

    void setStartHyphenEdit(uint32_t startHyphen) {
        mHyphenEdit = minikin::packHyphenEdit(
            static_cast<minikin::StartHyphenEdit>(startHyphen),
            minikin::endHyphenEdit(mHyphenEdit));
    }

    void setEndHyphenEdit(uint32_t endHyphen) {
        mHyphenEdit = minikin::packHyphenEdit(
            minikin::startHyphenEdit(mHyphenEdit),
            static_cast<minikin::EndHyphenEdit>(endHyphen));
    }

    minikin::StartHyphenEdit getStartHyphenEdit() const {
        return minikin::startHyphenEdit(mHyphenEdit);
    }

    minikin::EndHyphenEdit getEndHyphenEdit() const {
        return minikin::endHyphenEdit(mHyphenEdit);
    }

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

    minikin::RunFlag getRunFlag() const { return mRunFlag; }
    void setRunFlag(minikin::RunFlag runFlag) { mRunFlag = runFlag; }

    // Deprecated -- bitmapshaders will be taking this flag explicitly
    bool isFilterBitmap() const { return mFilterBitmap; }
    void setFilterBitmap(bool filter) { mFilterBitmap = filter; }

    SkFilterMode filterMode() const {
        return mFilterBitmap ? SkFilterMode::kLinear : SkFilterMode::kNearest;
    }
    SkSamplingOptions sampling() const {
        return SkSamplingOptions(this->filterMode());
    }

    void setVariationOverride(minikin::VariationSettings&& varSettings) {
        mFontVariationOverride = std::move(varSettings);
    }

    const minikin::VariationSettings& getFontVariationOverride() const {
        return mFontVariationOverride;
    }

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
    sk_sp<BlurDrawLooper> mLooper;

    float mLetterSpacing = 0;
    float mWordSpacing = 0;
    std::vector<minikin::FontFeature> mFontFeatureSettings;
    minikin::VariationSettings mFontVariationOverride;
    uint32_t mMinikinLocaleListId;
    std::optional<minikin::FamilyVariant> mFamilyVariant;
    uint32_t mHyphenEdit = 0;
    // The native Typeface object has the same lifetime of the Java Typeface
    // object. The Java Paint object holds a strong reference to the Java Typeface
    // object. Thus, following pointer can never be a dangling pointer. Note that
    // nullptr is valid: it means the default typeface.
    const Typeface* mTypeface = nullptr;
    Align mAlign = kLeft_Align;
    bool mFilterBitmap = false;
    bool mStrikeThru = false;
    bool mUnderline = false;
    bool mDevKern = false;
    minikin::RunFlag mRunFlag = minikin::RunFlag::NONE;
};

}  // namespace android

#endif  // ANDROID_GRAPHICS_PAINT_H_
