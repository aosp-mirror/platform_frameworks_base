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

#include <utils/Vector.h>

#include <SkPaint.h>

#include <GLES2/gl2.h>

#include "font/FontUtil.h"
#include "font/CacheTexture.h"
#include "font/CachedGlyphInfo.h"
#include "font/Font.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

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

        mCurrentCacheTexture->setLinearFiltering(linearFiltering);
        mLinearFiltering = linearFiltering;

        return mCurrentCacheTexture->getTextureId();
    }

    uint32_t getCacheSize() const {
        uint32_t size = 0;
        for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
            CacheTexture* cacheTexture = mCacheTextures[i];
            if (cacheTexture && cacheTexture->getTexture()) {
                size += cacheTexture->getWidth() * cacheTexture->getHeight();
            }
        }
        return size;
    }

private:
    friend class Font;

    const uint8_t* mGammaTable;

    void allocateTextureMemory(CacheTexture* cacheTexture);
    void deallocateTextureMemory(CacheTexture* cacheTexture);
    void initTextTexture();
    CacheTexture* createCacheTexture(int width, int height, bool allocate);
    void cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
            uint32_t *retOriginX, uint32_t *retOriginY, bool precaching);
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

    void removeFont(const Font* font);

    void checkTextureUpdate();

    void setTextureDirty() {
        mUploadTexture = true;
    }

    uint32_t mSmallCacheWidth;
    uint32_t mSmallCacheHeight;
    uint32_t mLargeCacheWidth;
    uint32_t mLargeCacheHeight;

    Vector<CacheTexture*> mCacheTextures;

    Font* mCurrentFont;
    Vector<Font*> mActiveFonts;

    CacheTexture* mCurrentCacheTexture;
    CacheTexture* mLastCacheTexture;

    bool mUploadTexture;

    // Pointer to vertex data to speed up frame to frame work
    float* mTextMesh;
    uint32_t mCurrentQuadIndex;
    uint32_t mMaxNumberOfQuads;

    uint32_t mIndexBufferID;

    const Rect* mClip;
    Rect* mBounds;
    bool mDrawn;

    bool mInitialized;

    bool mLinearFiltering;

    /** We should consider multi-threading this code or using Renderscript **/
    static void computeGaussianWeights(float* weights, int32_t radius);
    static void horizontalBlur(float* weights, int32_t radius, const uint8_t *source, uint8_t *dest,
            int32_t width, int32_t height);
    static void verticalBlur(float* weights, int32_t radius, const uint8_t *source, uint8_t *dest,
            int32_t width, int32_t height);
    static void blurImage(uint8_t* image, int32_t width, int32_t height, int32_t radius);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FONT_RENDERER_H
