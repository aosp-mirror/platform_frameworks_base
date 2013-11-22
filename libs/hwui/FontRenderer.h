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

#include <utils/Functor.h>
#include <utils/LruCache.h>
#include <utils/Vector.h>
#include <utils/StrongPointer.h>

#include <SkPaint.h>

#include <GLES2/gl2.h>

#include "font/FontUtil.h"
#include "font/CacheTexture.h"
#include "font/CachedGlyphInfo.h"
#include "font/Font.h"
#include "utils/SortedList.h"
#include "Matrix.h"
#include "Properties.h"

#ifdef ANDROID_ENABLE_RENDERSCRIPT
#include "RenderScript.h"
namespace RSC {
    class Element;
    class RS;
    class ScriptIntrinsicBlur;
    class sp;
}
#endif

class Functor;

namespace android {
namespace uirenderer {

class OpenGLRenderer;

///////////////////////////////////////////////////////////////////////////////
// TextSetupFunctor
///////////////////////////////////////////////////////////////////////////////
class TextSetupFunctor: public Functor {
public:
    struct Data {
        Data(GLenum glyphFormat) : glyphFormat(glyphFormat) {
        }

        GLenum glyphFormat;
    };

    TextSetupFunctor(OpenGLRenderer* renderer, float x, float y, bool pureTranslate,
            int alpha, SkXfermode::Mode mode, SkPaint* paint): Functor(),
            renderer(renderer), x(x), y(y), pureTranslate(pureTranslate),
            alpha(alpha), mode(mode), paint(paint) {
    }
    ~TextSetupFunctor() { }

    status_t operator ()(int what, void* data);

    OpenGLRenderer* renderer;
    float x;
    float y;
    bool pureTranslate;
    int alpha;
    SkXfermode::Mode mode;
    SkPaint* paint;
};

///////////////////////////////////////////////////////////////////////////////
// FontRenderer
///////////////////////////////////////////////////////////////////////////////

class FontRenderer {
public:
    FontRenderer();
    ~FontRenderer();

    void flushLargeCaches(Vector<CacheTexture*>& cacheTextures);
    void flushLargeCaches();

    void setGammaTable(const uint8_t* gammaTable) {
        mGammaTable = gammaTable;
    }

    void setFont(SkPaint* paint, const mat4& matrix);

    void precache(SkPaint* paint, const char* text, int numGlyphs, const mat4& matrix);
    void endPrecaching();

    // bounds is an out parameter
    bool renderPosText(SkPaint* paint, const Rect* clip, const char *text, uint32_t startIndex,
            uint32_t len, int numGlyphs, int x, int y, const float* positions, Rect* bounds,
            Functor* functor, bool forceFinish = true);

    // bounds is an out parameter
    bool renderTextOnPath(SkPaint* paint, const Rect* clip, const char *text, uint32_t startIndex,
            uint32_t len, int numGlyphs, SkPath* path, float hOffset, float vOffset, Rect* bounds,
            Functor* functor);

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
    CacheTexture* cacheBitmapInTexture(Vector<CacheTexture*>& cacheTextures, const SkGlyph& glyph,
            uint32_t* startX, uint32_t* startY);

    void flushAllAndInvalidate();

    void checkInit();
    void initRender(const Rect* clip, Rect* bounds, Functor* functor);
    void finishRender();

    void issueDrawCommand(Vector<CacheTexture*>& cacheTextures);
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

    Vector<CacheTexture*> mACacheTextures;
    Vector<CacheTexture*> mRGBACacheTextures;

    Font* mCurrentFont;
    LruCache<Font::FontDescription, Font*> mActiveFonts;

    CacheTexture* mCurrentCacheTexture;

    bool mUploadTexture;

    Functor* mFunctor;
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
    void blurImage(uint8_t** image, int32_t width, int32_t height, int32_t radius);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FONT_RENDERER_H
