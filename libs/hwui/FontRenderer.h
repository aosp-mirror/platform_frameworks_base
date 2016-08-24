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

#include "font/FontUtil.h"
#include "font/CacheTexture.h"
#include "font/CachedGlyphInfo.h"
#include "font/Font.h"

#include <utils/LruCache.h>
#include <utils/StrongPointer.h>

#include <SkPaint.h>

#include <GLES2/gl2.h>

#include <vector>

#ifdef ANDROID_ENABLE_RENDERSCRIPT
#include "RenderScript.h"
namespace RSC {
    class Element;
    class RS;
    class ScriptIntrinsicBlur;
    class sp;
}
#endif

namespace android {
namespace uirenderer {

#if HWUI_NEW_OPS
class BakedOpState;
class BakedOpRenderer;
struct ClipBase;
#else
class OpenGLRenderer;
#endif

class TextDrawFunctor {
public:
    TextDrawFunctor(
#if HWUI_NEW_OPS
            BakedOpRenderer* renderer,
            const BakedOpState* bakedState,
            const ClipBase* clip,
#else
            OpenGLRenderer* renderer,
#endif
            float x, float y, bool pureTranslate,
            int alpha, SkXfermode::Mode mode, const SkPaint* paint)
        : renderer(renderer)
#if HWUI_NEW_OPS
        , bakedState(bakedState)
        , clip(clip)
#endif
        , x(x)
        , y(y)
        , pureTranslate(pureTranslate)
        , alpha(alpha)
        , mode(mode)
        , paint(paint) {
    }

    void draw(CacheTexture& texture, bool linearFiltering);

#if HWUI_NEW_OPS
    BakedOpRenderer* renderer;
    const BakedOpState* bakedState;
    const ClipBase* clip;
#else
    OpenGLRenderer* renderer;
#endif
    float x;
    float y;
    bool pureTranslate;
    int alpha;
    SkXfermode::Mode mode;
    const SkPaint* paint;
};

class FontRenderer {
public:
    FontRenderer(const uint8_t* gammaTable);
    ~FontRenderer();

    void flushLargeCaches(std::vector<CacheTexture*>& cacheTextures);
    void flushLargeCaches();

    void setFont(const SkPaint* paint, const SkMatrix& matrix);

    void precache(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs, const SkMatrix& matrix);
    void endPrecaching();

    bool renderPosText(const SkPaint* paint, const Rect* clip, const glyph_t* glyphs,
            int numGlyphs, int x, int y, const float* positions,
            Rect* outBounds, TextDrawFunctor* functor, bool forceFinish = true);

    bool renderTextOnPath(const SkPaint* paint, const Rect* clip, const glyph_t* glyphs,
            int numGlyphs, const SkPath* path,
            float hOffset, float vOffset, Rect* outBounds, TextDrawFunctor* functor);

    struct DropShadow {
        uint32_t width;
        uint32_t height;
        uint8_t* image;
        int32_t penX;
        int32_t penY;
    };

    // After renderDropShadow returns, the called owns the memory in DropShadow.image
    // and is responsible for releasing it when it's done with it
    DropShadow renderDropShadow(const SkPaint* paint, const glyph_t *glyphs, int numGlyphs,
            float radius, const float* positions);

    void setTextureFiltering(bool linearFiltering) {
        mLinearFiltering = linearFiltering;
    }

    uint32_t getCacheSize(GLenum format) const;

private:
    friend class Font;

    const uint8_t* mGammaTable;

    void allocateTextureMemory(CacheTexture* cacheTexture);
    void deallocateTextureMemory(CacheTexture* cacheTexture);
    void initTextTexture();
    CacheTexture* createCacheTexture(int width, int height, GLenum format, bool allocate);
    void cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
            uint32_t *retOriginX, uint32_t *retOriginY, bool precaching);
    CacheTexture* cacheBitmapInTexture(std::vector<CacheTexture*>& cacheTextures, const SkGlyph& glyph,
            uint32_t* startX, uint32_t* startY);

    void flushAllAndInvalidate();

    void checkInit();
    void initRender(const Rect* clip, Rect* bounds, TextDrawFunctor* functor);
    void finishRender();

    void issueDrawCommand(std::vector<CacheTexture*>& cacheTextures);
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

    void checkTextureUpdate();

    void setTextureDirty() {
        mUploadTexture = true;
    }

    uint32_t mSmallCacheWidth;
    uint32_t mSmallCacheHeight;
    uint32_t mLargeCacheWidth;
    uint32_t mLargeCacheHeight;

    std::vector<CacheTexture*> mACacheTextures;
    std::vector<CacheTexture*> mRGBACacheTextures;

    Font* mCurrentFont;
    LruCache<Font::FontDescription, Font*> mActiveFonts;

    CacheTexture* mCurrentCacheTexture;

    bool mUploadTexture;

    TextDrawFunctor* mFunctor;
    const Rect* mClip;
    Rect* mBounds;
    bool mDrawn;

    bool mInitialized;

    bool mLinearFiltering;

#ifdef ANDROID_ENABLE_RENDERSCRIPT
    // RS constructs
    RSC::sp<RSC::RS> mRs;
    RSC::sp<const RSC::Element> mRsElement;
    RSC::sp<RSC::ScriptIntrinsicBlur> mRsScript;
#endif

    static void computeGaussianWeights(float* weights, int32_t radius);
    static void horizontalBlur(float* weights, int32_t radius, const uint8_t *source, uint8_t *dest,
            int32_t width, int32_t height);
    static void verticalBlur(float* weights, int32_t radius, const uint8_t *source, uint8_t *dest,
            int32_t width, int32_t height);

    // the input image handle may have its pointer replaced (to avoid copies)
    void blurImage(uint8_t** image, int32_t width, int32_t height, float radius);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FONT_RENDERER_H
