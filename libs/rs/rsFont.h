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

struct FT_LibraryRec_;
struct FT_FaceRec_;
struct FT_Bitmap_;

// ---------------------------------------------------------------------------
namespace android {

namespace renderscript {

// Gamma (>= 1.0, <= 10.0)
#define PROPERTY_TEXT_GAMMA "ro.text_gamma"
#define PROPERTY_TEXT_BLACK_GAMMA_THRESHOLD "ro.text_gamma.black_threshold"
#define PROPERTY_TEXT_WHITE_GAMMA_THRESHOLD "ro.text_gamma.white_threshold"

#define DEFAULT_TEXT_GAMMA 1.4f
#define DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD 64
#define DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD 192

class FontState;

class Font : public ObjectBase {
public:
    enum RenderMode {
        FRAMEBUFFER,
        BITMAP,
        MEASURE,
    };

    struct Rect {
        int32_t left;
        int32_t top;
        int32_t right;
        int32_t bottom;
        void set(int32_t l, int32_t r, int32_t t, int32_t b) {
            left = l;
            right = r;
            top = t;
            bottom = b;
        }
    };

    ~Font();

    // Currently files do not get serialized,
    // but we need to inherit from ObjectBase for ref tracking
    virtual void serialize(OStream *stream) const {
    }
    virtual RsA3DClassID getClassId() const {
        return RS_A3D_CLASS_ID_UNKNOWN;
    }

    static Font * create(Context *rsc, const char *name, float fontSize, uint32_t dpi,
                         const void *data = NULL, uint32_t dataLen = 0);

protected:

    friend class FontState;

    // Pointer to the utf data, length of data, where to start, number of glyphs ot read
    // (each glyph may be longer than a char because we are dealing with utf data)
    // Last two variables are the initial pen position
    void renderUTF(const char *text, uint32_t len, int32_t x, int32_t y,
                   uint32_t start, int32_t numGlyphs,
                   RenderMode mode = FRAMEBUFFER, Rect *bounds = NULL,
                   uint8_t *bitmap = NULL, uint32_t bitmapW = 0, uint32_t bitmapH = 0);

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
        int32_t mGlyphIndex;
        int32_t mAdvanceX;
        int32_t mAdvanceY;
        // Values below contain a glyph's origin in the bitmap
        int32_t mBitmapLeft;
        int32_t mBitmapTop;
    };

    String8 mFontName;
    float mFontSize;
    uint32_t mDpi;

    Font(Context *rsc);
    bool init(const char *name, float fontSize, uint32_t dpi, const void *data = NULL, uint32_t dataLen = 0);

    virtual void preDestroy() const;
    FT_FaceRec_ *mFace;
    bool mInitialized;
    bool mHasKerning;

    DefaultKeyedVector<uint32_t, CachedGlyphInfo* > mCachedGlyphs;
    CachedGlyphInfo* getCachedUTFChar(int32_t utfChar);

    CachedGlyphInfo *cacheGlyph(uint32_t glyph);
    void updateGlyphCache(CachedGlyphInfo *glyph);
    void measureCachedGlyph(CachedGlyphInfo *glyph, int32_t x, int32_t y, Rect *bounds);
    void drawCachedGlyph(CachedGlyphInfo *glyph, int32_t x, int32_t y);
    void drawCachedGlyph(CachedGlyphInfo *glyph, int32_t x, int32_t y,
                         uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH);
};

class FontState {
public:
    FontState();
    ~FontState();

    void init(Context *rsc);
    void deinit(Context *rsc);

    ObjectBaseRef<Font> mDefault;

    void renderText(const char *text, uint32_t len, int32_t x, int32_t y,
                    uint32_t startIndex = 0, int numGlyphs = -1,
                    Font::RenderMode mode = Font::FRAMEBUFFER,
                    Font::Rect *bounds = NULL,
                    uint8_t *bitmap = NULL, uint32_t bitmapW = 0, uint32_t bitmapH = 0);

    void measureText(const char *text, uint32_t len, Font::Rect *bounds);

    void setFontColor(float r, float g, float b, float a);
    void getFontColor(float *r, float *g, float *b, float *a) const;

protected:

    float mSurfaceWidth;
    float mSurfaceHeight;

    friend class Font;

    struct CacheTextureLine {
        uint32_t mMaxHeight;
        uint32_t mMaxWidth;
        uint32_t mCurrentRow;
        uint32_t mCurrentCol;
        bool mDirty;

        CacheTextureLine(uint32_t maxHeight, uint32_t maxWidth, uint32_t currentRow, uint32_t currentCol)
            : mMaxHeight(maxHeight), mMaxWidth(maxWidth), mCurrentRow(currentRow),
              mCurrentCol(currentCol), mDirty(false)  {
        }

        bool fitBitmap(FT_Bitmap_ *bitmap, uint32_t *retOriginX, uint32_t *retOriginY);
    };

    Vector<CacheTextureLine*> mCacheLines;
    uint32_t getRemainingCacheCapacity();

    void precacheLatin(Font *font);
    String8 mLatinPrecache;

    Context *mRSC;

    struct {
        float mFontColor[4];
        float mGamma;
    } mConstants;
    bool mConstantsDirty;

    float mBlackGamma;
    float mWhiteGamma;

    float mBlackThreshold;
    float mWhiteThreshold;

    // Free type library, we only need one copy
#ifndef ANDROID_RS_SERIALIZE
    FT_LibraryRec_ *mLibrary;
    FT_LibraryRec_ *getLib();
#endif //ANDROID_RS_SERIALIZE
    Vector<Font*> mActiveFonts;

    // Render state for the font
    ObjectBaseRef<Allocation> mFontShaderFConstant;
    ObjectBaseRef<ProgramFragment> mFontShaderF;
    ObjectBaseRef<Sampler> mFontSampler;
    ObjectBaseRef<ProgramStore> mFontProgramStore;
    void initRenderState();

    // Texture to cache glyph bitmaps
    ObjectBaseRef<Allocation> mTextTexture;
    void initTextTexture();
    const uint8_t* getTextTextureData() const {
        return (uint8_t*)mTextTexture->getPtr();
    }

#ifndef ANDROID_RS_SERIALIZE
    bool cacheBitmap(FT_Bitmap_ *bitmap, uint32_t *retOriginX, uint32_t *retOriginY);
#endif //ANDROID_RS_SERIALIZE
    const Type* getCacheTextureType() {
        return mTextTexture->getType();
    }

    void flushAllAndInvalidate();

    // Pointer to vertex data to speed up frame to frame work
    float *mTextMeshPtr;
    uint32_t mCurrentQuadIndex;
    uint32_t mMaxNumberOfQuads;

    void initVertexArrayBuffers();
    ObjectBaseRef<Mesh> mMesh;

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
