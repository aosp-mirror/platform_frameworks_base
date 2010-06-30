/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_FONT_H
#define ANDROID_RS_FONT_H

#include "RenderScript.h"
#include "rsStream.h"
#include <utils/String8.h>
#include <utils/Vector.h>
#include <utils/KeyedVector.h>

#include <ft2build.h>
#include FT_FREETYPE_H

// ---------------------------------------------------------------------------
namespace android {

namespace renderscript {

class FontState;

class Font : public ObjectBase
{
public:
    ~Font();

    // Pointer to the utf data, length of data, where to start, number of glyphs ot read
    // (each glyph may be longer than a char because we are dealing with utf data)
    // Last two variables are the initial pen position
    void renderUTF(const char *text, uint32_t len, uint32_t start, int numGlyphs, int x, int y);

    // Currently files do not get serialized,
    // but we need to inherit from ObjectBase for ref tracking
    virtual void serialize(OStream *stream) const {
    }
    virtual RsA3DClassID getClassId() const {
        return RS_A3D_CLASS_ID_UNKNOWN;
    }

    static Font * create(Context *rsc, const char *name, uint32_t fontSize, uint32_t dpi);

protected:

    friend class FontState;

    void invalidateTextureCache();
    struct CachedGlyphInfo
    {
        // Has the cache been invalidated?
        bool mIsValid;
        // Location of the cached glyph in the bitmap
        // in case we need to resize the texture
        uint32_t mBitmapMinX;
        uint32_t mBitmapMinY;
        uint32_t mBitmapWidth;
        uint32_t mBitmapHeight;
        // Also cache texture coords for the quad
        float mBitmapMinU;
        float mBitmapMinV;
        float mBitmapMaxU;
        float mBitmapMaxV;
        // Minimize how much we call freetype
        FT_UInt mGlyphIndex;
        FT_Vector mAdvance;
        // Values below contain a glyph's origin in the bitmap
        FT_Int mBitmapLeft;
        FT_Int mBitmapTop;
    };

    String8 mFontName;
    uint32_t mFontSize;
    uint32_t mDpi;

    Font(Context *rsc);
    bool init(const char *name, uint32_t fontSize, uint32_t dpi);

    FT_Face mFace;
    bool mInitialized;
    bool mHasKerning;

    DefaultKeyedVector<uint32_t, CachedGlyphInfo* > mCachedGlyphs;

    CachedGlyphInfo *cacheGlyph(uint32_t glyph);
    void updateGlyphCache(CachedGlyphInfo *glyph);
    void drawCachedGlyph(CachedGlyphInfo *glyph, int x, int y);
};

class FontState
{
public:
    FontState();
    ~FontState();

    void init(Context *rsc);
    void deinit(Context *rsc);

    ObjectBaseRef<Font> mDefault;
    ObjectBaseRef<Font> mLast;

    void renderText(const char *text, uint32_t len, uint32_t startIndex, int numGlyphs, int x, int y);
    void renderText(const char *text, int x, int y);
    void renderText(Allocation *alloc, int x, int y);
    void renderText(Allocation *alloc, uint32_t start, int len, int x, int y);

protected:

    friend class Font;

    struct CacheTextureLine
    {
        uint32_t mMaxHeight;
        uint32_t mMaxWidth;
        uint32_t mCurrentRow;
        uint32_t mCurrentCol;

        CacheTextureLine(uint32_t maxHeight, uint32_t maxWidth, uint32_t currentRow, uint32_t currentCol) :
            mMaxHeight(maxHeight), mMaxWidth(maxWidth), mCurrentRow(currentRow), mCurrentCol(currentCol) {
        }

        bool fitBitmap(FT_Bitmap *bitmap, uint32_t *retOriginX, uint32_t *retOriginY) {
            if((uint32_t)bitmap->rows > mMaxHeight) {
                return false;
            }

            if(mCurrentCol + (uint32_t)bitmap->width < mMaxWidth) {
               *retOriginX = mCurrentCol;
               *retOriginY = mCurrentRow;
               mCurrentCol += bitmap->width;
               return true;
            }

            return false;
        }
    };

    Vector<CacheTextureLine*> mCacheLines;

    Context *mRSC;

    // Free type library, we only need one copy
    FT_Library mLibrary;
    FT_Library getLib();
    Vector<Font*> mActiveFonts;

    // Render state for the font
    ObjectBaseRef<ProgramFragment> mFontShaderF;
    ObjectBaseRef<Sampler> mFontSampler;
    ObjectBaseRef<ProgramStore> mFontProgramStore;
    void initRenderState();

    // Texture to cache glyph bitmaps
    ObjectBaseRef<Allocation> mTextTexture;
    void initTextTexture();

    bool cacheBitmap(FT_Bitmap *bitmap, uint32_t *retOriginX, uint32_t *retOriginY);
    const Type* getCacheTextureType() {
        return mTextTexture->getType();
    }

    void flushAllAndInvalidate();

    // Pointer to vertex data to speed up frame to frame work
    float *mTextMeshPtr;
    uint32_t mCurrentQuadIndex;
    uint32_t mMaxNumberOfQuads;

    void initVertexArrayBuffers();
    ObjectBaseRef<Allocation> mIndexBuffer;
    ObjectBaseRef<Allocation> mVertexArray;


    bool mInitialized;

    void checkInit();

    void issueDrawCommand();

    void appendMeshQuad(float x1, float y1, float z1,
                          float u1, float v1,
                          float x2, float y2, float z2,
                          float u2, float v2,
                          float x3, float y3, float z3,
                          float u3, float v3,
                          float x4, float y4, float z4,
                          float u4, float v4);

};


}
}

#endif
