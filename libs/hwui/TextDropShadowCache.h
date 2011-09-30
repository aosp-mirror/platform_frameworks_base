/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_TEXT_DROP_SHADOW_CACHE_H
#define ANDROID_HWUI_TEXT_DROP_SHADOW_CACHE_H

#include <GLES2/gl2.h>

#include <SkPaint.h>

#include <utils/String16.h>

#include "utils/Compare.h"
#include "utils/GenerationCache.h"
#include "FontRenderer.h"
#include "Texture.h"

namespace android {
namespace uirenderer {

struct ShadowText {
    ShadowText(): radius(0), len(0), textSize(0.0f), typeface(NULL) {
    }

    ShadowText(SkPaint* paint, uint32_t radius, uint32_t len, const char* srcText):
            radius(radius), len(len) {
        // TODO: Propagate this through the API, we should not cast here
        text = (const char16_t*) srcText;

        textSize = paint->getTextSize();
        typeface = paint->getTypeface();

        flags = 0;
        if (paint->isFakeBoldText()) {
            flags |= Font::kFakeBold;
        }

        const float skewX = paint->getTextSkewX();
        italicStyle = *(uint32_t*) &skewX;

        const float scaleXFloat = paint->getTextScaleX();
        scaleX = *(uint32_t*) &scaleXFloat;
    }

    ~ShadowText() {
    }

    uint32_t radius;
    uint32_t len;
    float textSize;
    SkTypeface* typeface;
    uint32_t flags;
    uint32_t italicStyle;
    uint32_t scaleX;
    const char16_t* text;
    String16 str;

    void copyTextLocally() {
        str.setTo((const char16_t*) text, len >> 1);
        text = str.string();
    }

    bool operator<(const ShadowText& rhs) const {
        LTE_INT(len) {
            LTE_INT(radius) {
                LTE_FLOAT(textSize) {
                    LTE_INT(typeface) {
                        LTE_INT(flags) {
                            LTE_INT(italicStyle) {
                                LTE_INT(scaleX) {
                                    return memcmp(text, rhs.text, len) < 0;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}; // struct ShadowText

/**
 * Alpha texture used to represent a shadow.
 */
struct ShadowTexture: public Texture {
    ShadowTexture(): Texture() {
    }

    float left;
    float top;
}; // struct ShadowTexture

class TextDropShadowCache: public OnEntryRemoved<ShadowText, ShadowTexture*> {
public:
    TextDropShadowCache();
    TextDropShadowCache(uint32_t maxByteSize);
    ~TextDropShadowCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(ShadowText& text, ShadowTexture*& texture);

    ShadowTexture* get(SkPaint* paint, const char* text, uint32_t len,
            int numGlyphs, uint32_t radius);

    /**
     * Clears the cache. This causes all textures to be deleted.
     */
    void clear();

    void setFontRenderer(FontRenderer& fontRenderer) {
        mRenderer = &fontRenderer;
    }

    /**
     * Sets the maximum size of the cache in bytes.
     */
    void setMaxSize(uint32_t maxSize);
    /**
     * Returns the maximum size of the cache in bytes.
     */
    uint32_t getMaxSize();
    /**
     * Returns the current size of the cache in bytes.
     */
    uint32_t getSize();

private:
    void init();

    GenerationCache<ShadowText, ShadowTexture*> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;
    FontRenderer* mRenderer;
    bool mDebugEnabled;
}; // class TextDropShadowCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TEXT_DROP_SHADOW_CACHE_H
