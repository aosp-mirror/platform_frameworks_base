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

#ifndef ANDROID_UI_FONT_RENDERER_H
#define ANDROID_UI_FONT_RENDERER_H

#include <utils/String8.h>
#include <utils/Vector.h>
#include <utils/KeyedVector.h>

#include <SkScalerContext.h>
#include <SkPaint.h>

#include <GLES2/gl2.h>

namespace android {
namespace uirenderer {

class FontRenderer;

class Font {
public:
    ~Font();

    // Pointer to the utf data, length of data, where to start, number of glyphs ot read
    // (each glyph may be longer than a char because we are dealing with utf data)
    // Last two variables are the initial pen position
    void renderUTF(SkPaint* paint, const char *text, uint32_t len, uint32_t start,
            int numGlyphs, int x, int y);

    static Font* create(FontRenderer* state, uint32_t fontId, float fontSize);

protected:

    friend class FontRenderer;

    void invalidateTextureCache();
    struct CachedGlyphInfo {
        // Has the cache been invalidated?
        bool mIsValid;
        // Location of the cached glyph in the bitmap
        // in case we need to resize the texture
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
        uint32_t mBitmapLeft;
        uint32_t mBitmapTop;
    };

    FontRenderer* mState;
    uint32_t mFontId;
    float mFontSize;

    Font(FontRenderer* state, uint32_t fontId, float fontSize);

    DefaultKeyedVector<int32_t, CachedGlyphInfo*> mCachedGlyphs;

    CachedGlyphInfo *cacheGlyph(SkPaint* paint, int32_t glyph);
    void updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo *glyph);
    void drawCachedGlyph(CachedGlyphInfo *glyph, int x, int y);
};

class FontRenderer {
public:
    FontRenderer();
    ~FontRenderer();

    void init();
    void deinit();

    void setFont(uint32_t fontId, float fontSize);
    void renderText(SkPaint* paint, const char *text, uint32_t len, uint32_t startIndex,
            int numGlyphs, int x, int y);
    void renderText(SkPaint* paint, const char *text, int x, int y);

    GLuint getTexture() {
        checkInit();
        return mTextureId;
    }

protected:
    friend class Font;

    struct CacheTextureLine {
        uint16_t mMaxHeight;
        uint16_t mMaxWidth;
        uint32_t mCurrentRow;
        uint32_t mCurrentCol;

        CacheTextureLine(uint16_t maxHeight, uint16_t maxWidth, uint32_t currentRow,
                uint32_t currentCol):
            mMaxHeight(maxHeight), mMaxWidth(maxWidth), mCurrentRow(currentRow),
            mCurrentCol(currentCol) {
        }

        bool fitBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY) {
            if (glyph.fHeight > mMaxHeight) {
                return false;
            }

            if (mCurrentCol + glyph.fWidth < mMaxWidth) {
                *retOriginX = mCurrentCol;
                *retOriginY = mCurrentRow;
                mCurrentCol += glyph.fWidth;
                return true;
            }

            return false;
        }
    };

    uint32_t getCacheWidth() const {
        return mCacheWidth;
    }

    uint32_t getCacheHeight() const {
        return mCacheHeight;
    }

    void initTextTexture();

    bool cacheBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY);

    void flushAllAndInvalidate();
    void initVertexArrayBuffers();

    void checkInit();

    void issueDrawCommand();

    void appendMeshQuad(float x1, float y1, float z1, float u1, float v1, float x2, float y2,
            float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3,
            float x4, float y4, float z4, float u4, float v4);

    uint32_t mCacheWidth;
    uint32_t mCacheHeight;

    Font* mCurrentFont;

    Vector<CacheTextureLine*> mCacheLines;

    Vector<Font*> mActiveFonts;

    // Texture to cache glyph bitmaps
    unsigned char* mTextTexture;
    GLuint mTextureId;
    bool mUploadTexture;

    // Pointer to vertex data to speed up frame to frame work
    float *mTextMeshPtr;
    uint32_t mCurrentQuadIndex;
    uint32_t mMaxNumberOfQuads;

    uint32_t mIndexBufferID;

    bool mInitialized;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_FONT_RENDERER_H
