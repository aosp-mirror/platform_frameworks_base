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

#include "Paint.h"

namespace android {

Paint::Paint()
        : SkPaint()
        , mLetterSpacing(0)
        , mWordSpacing(0)
        , mFontFeatureSettings()
        , mMinikinLocaleListId(0)
        , mFamilyVariant(minikin::FontFamily::Variant::DEFAULT) {}

Paint::Paint(const Paint& paint)
        : SkPaint(paint)
        , mLetterSpacing(paint.mLetterSpacing)
        , mWordSpacing(paint.mWordSpacing)
        , mFontFeatureSettings(paint.mFontFeatureSettings)
        , mMinikinLocaleListId(paint.mMinikinLocaleListId)
        , mFamilyVariant(paint.mFamilyVariant)
        , mHyphenEdit(paint.mHyphenEdit)
        , mTypeface(paint.mTypeface) {}

Paint::Paint(const SkPaint& paint)
        : SkPaint(paint)
        , mLetterSpacing(0)
        , mWordSpacing(0)
        , mFontFeatureSettings()
        , mMinikinLocaleListId(0)
        , mFamilyVariant(minikin::FontFamily::Variant::DEFAULT) {}

Paint::~Paint() {}

Paint& Paint::operator=(const Paint& other) {
    SkPaint::operator=(other);
    mLetterSpacing = other.mLetterSpacing;
    mWordSpacing = other.mWordSpacing;
    mFontFeatureSettings = other.mFontFeatureSettings;
    mMinikinLocaleListId = other.mMinikinLocaleListId;
    mFamilyVariant = other.mFamilyVariant;
    mHyphenEdit = other.mHyphenEdit;
    mTypeface = other.mTypeface;
    return *this;
}

bool operator==(const Paint& a, const Paint& b) {
    return static_cast<const SkPaint&>(a) == static_cast<const SkPaint&>(b) &&
           a.mLetterSpacing == b.mLetterSpacing && a.mWordSpacing == b.mWordSpacing &&
           a.mFontFeatureSettings == b.mFontFeatureSettings &&
           a.mMinikinLocaleListId == b.mMinikinLocaleListId &&
           a.mFamilyVariant == b.mFamilyVariant && a.mHyphenEdit == b.mHyphenEdit &&
           a.mTypeface == b.mTypeface;
}
}  // namespace android
