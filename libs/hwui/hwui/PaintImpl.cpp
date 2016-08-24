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

Paint::Paint() :
        SkPaint(), mLetterSpacing(0), mFontFeatureSettings(), mMinikinLangListId(0),
        mFontVariant(VARIANT_DEFAULT) {
}

Paint::Paint(const Paint& paint) : SkPaint(paint),
        mLetterSpacing(paint.mLetterSpacing), mFontFeatureSettings(paint.mFontFeatureSettings),
        mMinikinLangListId(paint.mMinikinLangListId), mFontVariant(paint.mFontVariant),
        mHyphenEdit(paint.mHyphenEdit) {
}

Paint::Paint(const SkPaint& paint) : SkPaint(paint),
        mLetterSpacing(0), mFontFeatureSettings(), mMinikinLangListId(0),
        mFontVariant(VARIANT_DEFAULT) {
}

Paint::~Paint() {
}

Paint& Paint::operator=(const Paint& other) {
    SkPaint::operator=(other);
    mLetterSpacing = other.mLetterSpacing;
    mFontFeatureSettings = other.mFontFeatureSettings;
    mMinikinLangListId = other.mMinikinLangListId;
    mFontVariant = other.mFontVariant;
    mHyphenEdit = other.mHyphenEdit;
    return *this;
}

bool operator==(const Paint& a, const Paint& b) {
    return static_cast<const SkPaint&>(a) == static_cast<const SkPaint&>(b)
            && a.mLetterSpacing == b.mLetterSpacing
            && a.mFontFeatureSettings == b.mFontFeatureSettings
            && a.mMinikinLangListId == b.mMinikinLangListId
            && a.mFontVariant == b.mFontVariant
            && a.mHyphenEdit == b.mHyphenEdit;
}

}
