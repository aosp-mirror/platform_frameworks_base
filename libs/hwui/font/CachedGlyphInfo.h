/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_HWUI_CACHED_GLYPH_INFO_H
#define ANDROID_HWUI_CACHED_GLYPH_INFO_H

#include <SkFixed.h>

#include "CacheTexture.h"

namespace android {
namespace uirenderer {

struct CachedGlyphInfo {
    // Has the cache been invalidated?
    bool mIsValid;
    // Location of the cached glyph in the bitmap
    // in case we need to resize the texture or
    // render to bitmap
    uint32_t mStartX;
    uint32_t mStartY;
    uint32_t mBitmapWidth;
    uint32_t mBitmapHeight;
    // Also cache texture coords for the quad
    float mBitmapMinU;
    float mBitmapMinV;
    float mBitmapMaxU;
    float mBitmapMaxV;
    // Minimize how much we call freetype
    uint32_t mGlyphIndex;
    uint32_t mAdvanceX;
    uint32_t mAdvanceY;
    // Values below contain a glyph's origin in the bitmap
    int32_t mBitmapLeft;
    int32_t mBitmapTop;
    // Auto-kerning
    SkFixed mLsbDelta;
    SkFixed mRsbDelta;
    CacheTexture* mCacheTexture;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CACHED_GLYPH_INFO_H
