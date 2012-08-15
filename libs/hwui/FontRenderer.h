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
#include <SkPathMeasure.h>
#include <SkPoint.h>

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
    #define TO_GLYPH(g) g
    #define GET_METRICS(paint, glyph) paint->getGlyphMetrics(glyph)
    #define GET_GLYPH(text) nextGlyph((const uint16_t**) &text)
    #define IS_END_OF_STRING(glyph) false
#else
    typedef SkUnichar glyph_t;
    #define TO_GLYPH(g) ((SkUnichar) g)
    #define GET_METRICS(paint, glyph) paint->getUnicharMetrics(glyph)
    #define GET_GLYPH(text) SkUTF16_NextUnichar((const uint16_t**) &text)
    #define IS_END_OF_STRING(glyph) glyph < 0
#endif

#define TEXTURE_BORDER_SIZE 1

///////////////////////////////////////////////////////////////////////////////
// Declarations
///////////////////////////////////////////////////////////////////////////////

class FontRenderer;

/**
 * CacheBlock is a node in a linked list of current free space areas in a CacheTexture.
 * Using CacheBlocks enables us to pack the cache from top to bottom as well as left to right.
 * When we add a glyph to the cache, we see if it fits within one of the existing columns that
 * have already been started (this is the case if the glyph fits vertically as well as
 * horizontally, and if its width is sufficiently close to the column width to avoid
 * sub-optimal packing of small glyphs into wide columns). If there is no column in which the
 * glyph fits, we check the final node, which is the remaining space in the cache, creating
 * a new column as appropriate.
 *
 * As columns fill up, we remove their CacheBlock from the list to avoid having to check
 * small blocks in the future.
 */
struct CacheBlock {
    uint16_t mX;
    uint16_t mY;
    uint16_t mWidth;
    uint16_t mHeight;
    CacheBlock* mNext;
    CacheBlock* mPrev;

    CacheBlock(uint16_t x, uint16_t y, uint16_t width, uint16_t height, bool empty = false):
        mX(x), mY(y), mWidth(width), mHeight(height), mNext(NULL), mPrev(NULL)
    {
    }

    static CacheBlock* insertBlock(CacheBlock* head, CacheBlock *newBlock);

    static CacheBlock* removeBlock(CacheBlock* head, CacheBlock *blockToRemove);

    void output() {
        CacheBlock *currBlock = this;
        while (currBlock) {
            ALOGD("Block: this, x, y, w, h = %p, %d, %d, %d, %d",
                    currBlock, currBlock->mX, currBlock->mY, currBlock->mWidth, currBlock->mHeight);
            currBlock = currBlock->mNext;
        }
    }
};

class CacheTexture {
public:
    CacheTexture(uint16_t width, uint16_t height) :
            mTexture(NULL), mTextureId(0), mWidth(width), mHeight(height),
            mLinearFiltering(false), mDirty(false), mNumGlyphs(0) {
        mCacheBlocks = new CacheBlock(TEXTURE_BORDER_SIZE, TEXTURE_BORDER_SIZE,
                mWidth - TEXTURE_BORDER_SIZE, mHeight - TEXTURE_BORDER_SIZE, true);
    }

    ~CacheTexture() {
        if (mTexture) {
            delete[] mTexture;
        }
        if (mTextureId) {
            glDeleteTextures(1, &mTextureId);
        }
        reset();
    }

    void reset() {
        // Delete existing cache blocks
        while (mCacheBlocks != NULL) {
            CacheBlock* tmpBlock = mCacheBlocks;
            mCacheBlocks = mCacheBlocks->mNext;
            delete tmpBlock;
        }
        mNumGlyphs = 0;
    }

    void init() {
        // reset, then create a new remainder space to start again
        reset();
        mCacheBlocks = new CacheBlock(TEXTURE_BORDER_SIZE, TEXTURE_BORDER_SIZE,
                mWidth - TEXTURE_BORDER_SIZE, mHeight - TEXTURE_BORDER_SIZE, true);
    }

    bool fitBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY);

    uint8_t* mTexture;
    GLuint mTextureId;
    uint16_t mWidth;
    uint16_t mHeight;
    bool mLinearFiltering;
    bool mDirty;
    uint16_t mNumGlyphs;
    CacheBlock* mCacheBlocks;
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
    CacheTexture* mCacheTexture;
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

    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, const float* positions);

    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, SkPath* path, float hOffset, float vOffset);

    /**
     * Creates a new font associated with the specified font state.
     */
    static Font* create(FontRenderer* state, uint32_t fontId, float fontSize,
            int flags, uint32_t italicStyle, uint32_t scaleX, SkPaint::Style style,
            uint32_t strokeWidth);

protected:
    friend class FontRenderer;
    typedef void (Font::*RenderGlyph)(CachedGlyphInfo*, int, int, uint8_t*,
            uint32_t, uint32_t, Rect*, const float*);

    enum RenderMode {
        FRAMEBUFFER,
        BITMAP,
        MEASURE,
    };

    void precache(SkPaint* paint, const char* text, int numGlyphs);

    void render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, RenderMode mode, uint8_t *bitmap,
            uint32_t bitmapW, uint32_t bitmapH, Rect *bounds, const float* positions);

    void measure(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
            int numGlyphs, Rect *bounds, const float* positions);

    Font(FontRenderer* state, uint32_t fontId, float fontSize, int flags, uint32_t italicStyle,
            uint32_t scaleX, SkPaint::Style style, uint32_t strokeWidth);

    // Cache of glyphs
    DefaultKeyedVector<glyph_t, CachedGlyphInfo*> mCachedGlyphs;

    void invalidateTextureCache(CacheTexture *cacheTexture = NULL);

    CachedGlyphInfo* cacheGlyph(SkPaint* paint, glyph_t glyph);
    void updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo* glyph);

    void measureCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyphBitmap(CachedGlyphInfo* glyph, int x, int y,
            uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH,
            Rect* bounds, const float* pos);
    void drawCachedGlyph(CachedGlyphInfo* glyph, float x, float hOffset, float vOffset,
            SkPathMeasure& measure, SkPoint* position, SkVector* tangent);

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

    void flushLargeCaches();

    void setGammaTable(const uint8_t* gammaTable) {
        mGammaTable = gammaTable;
    }

    void setFont(SkPaint* paint, uint32_t fontId, float fontSize);

    void precache(SkPaint* paint, const char* text, int numGlyphs);

    // bounds is an out parameter
    bool renderText(SkPaint* paint, const Rect* clip, const char *text, uint32_t startIndex,
            uint32_t len, int numGlyphs, int x, int y, Rect* bounds);
    // bounds is an out parameter
    bool renderPosText(SkPaint* paint, const Rect* clip, const char *text, uint32_t startIndex,
            uint32_t len, int numGlyphs, int x, int y, const float* positions, Rect* bounds);
    // bounds is an out parameter
    bool renderTextOnPath(SkPaint* paint, const Rect* clip, const char *text, uint32_t startIndex,
            uint32_t len, int numGlyphs, SkPath* path, float hOffset, float vOffset, Rect* bounds);

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
            uint32_t len, int numGlyphs, uint32_t radius, const float* positions);

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
        for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
            CacheTexture* cacheTexture = mCacheTextures[i];
            if (cacheTexture != NULL && cacheTexture->mTexture != NULL) {
                size += cacheTexture->mWidth * cacheTexture->mHeight;
            }
        }
        return size;
    }

protected:
    friend class Font;

    const uint8_t* mGammaTable;

    void allocateTextureMemory(CacheTexture* cacheTexture);
    void deallocateTextureMemory(CacheTexture* cacheTexture);
    void initTextTexture();
    CacheTexture* createCacheTexture(int width, int height, bool allocate);
    void cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
            uint32_t *retOriginX, uint32_t *retOriginY);
    CacheTexture* cacheBitmapInTexture(const SkGlyph& glyph, uint32_t* startX, uint32_t* startY);

    void flushAllAndInvalidate();
    void initVertexArrayBuffers();

    void checkInit();
    void initRender(const Rect* clip, Rect* bounds);
    void finishRender();

    void issueDrawCommand();
    void appendMeshQuadNoClip(float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            float x4, float y4, float u4, float v4, CacheTexture* texture);
    void appendMeshQuad(float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            float x4, float y4, float u4, float v4, CacheTexture* texture);
    void appendRotatedMeshQuad(float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            float x4, float y4, float u4, float v4, CacheTexture* texture);

    uint32_t mSmallCacheWidth;
    uint32_t mSmallCacheHeight;

    Vector<CacheTexture*> mCacheTextures;

    Font* mCurrentFont;
    Vector<Font*> mActiveFonts;

    CacheTexture* mCurrentCacheTexture;
    CacheTexture* mLastCacheTexture;

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
