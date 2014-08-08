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

#ifndef ANDROID_GRAPHICS_PAINT_H
#define ANDROID_GRAPHICS_PAINT_H

#include <SkPaint.h>
#include <string>

#include <minikin/FontFamily.h>

namespace android {

class Paint : public SkPaint {
public:
    Paint();
    Paint(const Paint& paint);
    ~Paint();

    Paint& operator=(const Paint& other);

    friend bool operator==(const Paint& a, const Paint& b);
    friend bool operator!=(const Paint& a, const Paint& b) {
        return !(a == b);
    }

    void setLetterSpacing(float letterSpacing) {
        mLetterSpacing = letterSpacing;
    }

    float getLetterSpacing() const {
        return mLetterSpacing;
    }

    void setFontFeatureSettings(const std::string &fontFeatureSettings) {
        mFontFeatureSettings = fontFeatureSettings;
    }

    std::string getFontFeatureSettings() const {
        return mFontFeatureSettings;
    }

    void setTextLocale(const std::string &textLocale) {
        mTextLocale = textLocale;
    }

    std::string getTextLocale() const {
        return mTextLocale;
    }

    void setFontVariant(FontVariant variant) {
        mFontVariant = variant;
    }

    FontVariant getFontVariant() const {
        return mFontVariant;
    }

private:
    float mLetterSpacing;
    std::string mFontFeatureSettings;
    std::string mTextLocale;
    FontVariant mFontVariant;
};

}  // namespace android

#endif // ANDROID_GRAPHICS_PAINT_H
