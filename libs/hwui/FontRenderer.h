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

#ifndef ANDROID_HWUI_FONT_RENDERER_H
#define ANDROID_HWUI_FONT_RENDERER_H

#include <utils/String8.h>
#include <utils/String16.h>
#include <utils/Vector.h>
#include <utils/KeyedVector.h>

#include <SkScalerContext.h>
#include <SkPaint.h>

#include <GLES2/gl2.h>

#include "Rect.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#if RENDER_TEXT_AS_GLYPHS
    typedef uint16_t glyph_t;
    #define GET_METRICS(paint, glyph) paint->getGlyphMetrics(glyph)
    #define GET_GLYPH(text) nextGlyph((const uint16_t**) &text)
    #define IS_END_OF_STRING(glyph) false
#else
    typedef SkUnichar glyph_t;
    #define GET_METRICS(paint, glyph) paint->getUnicharMetrics(glyph)
    #define GET_GLYPH(text) SkUTF16_NextUnichar((const uint16_t**) &text)
    #define IS_END_OF_STRING(glyph) glyph < 0
#endif

///////////////////////////////////////////////////////////////////////////////
// Declarations
///////////////////////////////////////////////////////////////////////////////

class FontRenderer;

class CacheTexture {
public:
    CacheTexture(){}
    CacheTexture(uint8_t* texture, GLuint textureId, uint16_t width, uint16_t height) :
        mTexture(texture), mTextureId(textureId), mWidth(width), mHeight(height),
        mLinearFiltering(false) {}
    ~CacheTexture() {
        if (mTexture != NULL) {
            delete[] mTexture;
        }
        if (mTextureId != 0) {
            glDeleteTextures(1, &mTextureId);
        }
    }

    uint8_t* mTexture;
    GLuint mTextureId;
    uint16_t mWidth;
    uint16_t mHeight;
    bool mLinearFiltering;
};

class CacheTextureLine {
public:
    CacheTextureLine(uint16_t maxWidth, uint16_t maxHeight, uint32_t currentRow,
            uint32_t currentCol, CacheTexture* cacheTexture):
                mMaxHeight(maxHeight),
                mMaxWidth(maxWidth),
                mCurrentRow(currentRow),
                mCurrentCol(currentCol),
                mDirty(false),
                mCacheTexture(cacheTexture){
    }

    bool fitBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY);

    uint16_t mMaxHeight;
    uint16_t mMaxWidth;
    uint32_t mCurrentRow;
    uint32_t mCurrentCol;
    bool mDirty;
    CacheTexture *mCacheTexture;
};

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
    CacheTextureLine* mCachedTextureLine;
};


///////////////////////////////////////////////////////////////////////////////
// Font
///////////////////////////////////////////////////////////////////////////////

/**
 * Represents a font, defined by a Skia font id and a font size. A font is used
 * to generate glyphs and cache them in the FontState.
 */
class Font {
public:
    enum Style {
        kFakeBold = 1
    };

    ~Font();

    /**
     * Renders the specified string of text.
     * If bitmap is specified, it will be used as the render target
     */
    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, uint8_t *bitmap = NULL,
            uint32_t bitmapW = 0, uint32_t bitmapH = 0);
    /**
     * Creates a new font associated with the specified font state.
     */
    static Font* create(FontRenderer* state, uint32_t fontId, float fontSize,
            int flags, uint32_t italicStyle, uint32_t scaleX, SkPaint::Style style,
            uint32_t strokeWidth);

protected:
    friend class FontRenderer;

    enum RenderMode {
        FRAMEBUFFER,
        BITMAP,
        MEASURE,
    };

    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, RenderMode mode, uint8_t *bitmap,
            uint32_t bitmapW, uint32_t bitmapH, Rect *bounds);

    void measure(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
            int numGlyphs, Rect *bounds);

    Font(FontRenderer* state, uint32_t fontId, float fontSize, int flags, uint32_t italicStyle,
            uint32_t scaleX, SkPaint::Style style, uint32_t strokeWidth);

    // Cache of glyphs
    DefaultKeyedVector<glyph_t, CachedGlyphInfo*> mCachedGlyphs;

    void invalidateTextureCache(CacheTextureLine *cacheLine = NULL);

    CachedGlyphInfo* cacheGlyph(SkPaint* paint, glyph_t glyph);
    void updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo *glyph);
    void measureCachedGlyph(CachedGlyphInfo *glyph, int x, int y, Rect *bounds);
    void drawCachedGlyph(CachedGlyphInfo *glyph, int x, int y);
    void drawCachedGlyph(CachedGlyphInfo *glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH);

    CachedGlyphInfo* getCachedGlyph(SkPaint* paint, glyph_t textUnit);

    static glyph_t nextGlyph(const uint16_t** srcPtr) {
        const uint16_t* src = *srcPtr;
        glyph_t g = *src++;
        *srcPtr = src;
        return g;
    }

    FontRenderer* mState;
    uint32_t mFontId;
    float mFontSize;
    int mFlags;
    uint32_t mItalicStyle;
    uint32_t mScaleX;
    SkPaint::Style mStyle;
    uint32_t mStrokeWidth;
};

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

class FontRenderer {
public:
    FontRenderer();
    ~FontRenderer();

    void init();
    void deinit();
    void flushLargeCaches();

    void setGammaTable(const uint8_t* gammaTable) {
        mGammaTable = gammaTable;
    }

    void setFont(SkPaint* paint, uint32_t fontId, float fontSize);
    bool renderText(SkPaint* paint, const Rect* clip, const char *text, uint32_t startIndex,
            uint32_t len, int numGlyphs, int x, int y, Rect* bounds);

    struct DropShadow {
        DropShadow() { };

        DropShadow(const DropShadow& dropShadow):
            width(dropShadow.width), height(dropShadow.height),
            image(dropShadow.image), penX(dropShadow.penX),
            penY(dropShadow.penY) {
        }

        uint32_t width;
        uint32_t height;
        uint8_t* image;
        int32_t penX;
        int32_t penY;
    };

    // After renderDropShadow returns, the called owns the memory in DropShadow.image
    // and is responsible for releasing it when it's done with it
    DropShadow renderDropShadow(SkPaint* paint, const char *text, uint32_t startIndex,
            uint32_t len, int numGlyphs, uint32_t radius);

    GLuint getTexture(bool linearFiltering = false) {
        checkInit();
        if (linearFiltering != mCurrentCacheTexture->mLinearFiltering) {
            mCurrentCacheTexture->mLinearFiltering = linearFiltering;
            mLinearFiltering = linearFiltering;
            const GLenum filtering = linearFiltering ? GL_LINEAR : GL_NEAREST;

            glBindTexture(GL_TEXTURE_2D, mCurrentCacheTexture->mTextureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);
        }
        return mCurrentCacheTexture->mTextureId;
    }

    uint32_t getCacheSize() const {
        uint32_t size = 0;
        if (mCacheTextureSmall != NULL && mCacheTextureSmall->mTexture != NULL) {
            size += mCacheTextureSmall->mWidth * mCacheTextureSmall->mHeight;
        }
        if (mCacheTexture128 != NULL && mCacheTexture128->mTexture != NULL) {
            size += mCacheTexture128->mWidth * mCacheTexture128->mHeight;
        }
        if (mCacheTexture256 != NULL && mCacheTexture256->mTexture != NULL) {
            size += mCacheTexture256->mWidth * mCacheTexture256->mHeight;
        }
        if (mCacheTexture512 != NULL && mCacheTexture512->mTexture != NULL) {
            size += mCacheTexture512->mWidth * mCacheTexture512->mHeight;
        }
        return size;
    }

protected:
    friend class Font;

    const uint8_t* mGammaTable;

    void allocateTextureMemory(CacheTexture* cacheTexture);
    void deallocateTextureMemory(CacheTexture* cacheTexture);
    void initTextTexture();
    CacheTexture *createCacheTexture(int width, int height, bool allocate);
    void cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
            uint32_t *retOriginX, uint32_t *retOriginY);

    void flushAllAndInvalidate();
    void initVertexArrayBuffers();

    void checkInit();

    String16 mLatinPrecache;
    void precacheLatin(SkPaint* paint);

    void issueDrawCommand();
    void appendMeshQuad(float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            float x4, float y4, float u4, float v4, CacheTexture* texture);

    uint32_t mSmallCacheWidth;
    uint32_t mSmallCacheHeight;

    Vector<CacheTextureLine*> mCacheLines;
    uint32_t getRemainingCacheCapacity();

    Font* mCurrentFont;
    Vector<Font*> mActiveFonts;

    CacheTexture* mCurrentCacheTexture;
    CacheTexture* mLastCacheTexture;
    CacheTexture* mCacheTextureSmall;
    CacheTexture* mCacheTexture128;
    CacheTexture* mCacheTexture256;
    CacheTexture* mCacheTexture512;

    void checkTextureUpdate();
    bool mUploadTexture;

    // Pointer to vertex data to speed up frame to frame work
    float *mTextMeshPtr;
    uint32_t mCurrentQuadIndex;
    uint32_t mMaxNumberOfQuads;

    uint32_t mIndexBufferID;

    const Rect* mClip;
    Rect* mBounds;
    bool mDrawn;

    bool mInitialized;

    bool mLinearFiltering;

    void computeGaussianWeights(float* weights, int32_t radius);
    void horizontalBlur(float* weights, int32_t radius, const uint8_t *source, uint8_t *dest,
            int32_t width, int32_t height);
    void verticalBlur(float* weights, int32_t radius, const uint8_t *source, uint8_t *dest,
            int32_t width, int32_t height);
    void blurImage(uint8_t* image, int32_t width, int32_t height, int32_t radius);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FONT_RENDERER_H
