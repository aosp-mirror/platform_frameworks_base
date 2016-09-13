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

#include "FontRenderer.h"

#include "Caches.h"
#include "Debug.h"
#include "Extensions.h"
#include "Glop.h"
#include "GlopBuilder.h"
#include "PixelBuffer.h"
#include "Rect.h"
#include "renderstate/RenderState.h"
#include "utils/Blur.h"
#include "utils/Timing.h"


#if HWUI_NEW_OPS
#include "BakedOpDispatcher.h"
#include "BakedOpRenderer.h"
#include "BakedOpState.h"
#else
#include "OpenGLRenderer.h"
#endif

#include <algorithm>
#include <cutils/properties.h>
#include <SkGlyph.h>
#include <SkUtils.h>
#include <utils/Log.h>

#ifdef ANDROID_ENABLE_RENDERSCRIPT
#include <RenderScript.h>
#endif

namespace android {
namespace uirenderer {

// blur inputs smaller than this constant will bypass renderscript
#define RS_MIN_INPUT_CUTOFF 10000

///////////////////////////////////////////////////////////////////////////////
// TextSetupFunctor
///////////////////////////////////////////////////////////////////////////////

void TextDrawFunctor::draw(CacheTexture& texture, bool linearFiltering) {
    int textureFillFlags = TextureFillFlags::None;
    if (texture.getFormat() == GL_ALPHA) {
        textureFillFlags |= TextureFillFlags::IsAlphaMaskTexture;
    }
    if (linearFiltering) {
        textureFillFlags |= TextureFillFlags::ForceFilter;
    }
    int transformFlags = pureTranslate
            ? TransformFlags::MeshIgnoresCanvasTransform : TransformFlags::None;
    Glop glop;
#if HWUI_NEW_OPS
    GlopBuilder(renderer->renderState(), renderer->caches(), &glop)
            .setRoundRectClipState(bakedState->roundRectClipState)
            .setMeshTexturedIndexedQuads(texture.mesh(), texture.meshElementCount())
            .setFillTexturePaint(texture.getTexture(), textureFillFlags, paint, bakedState->alpha)
            .setTransform(bakedState->computedState.transform, transformFlags)
            .setModelViewIdentityEmptyBounds()
            .build();
    // Note: don't pass dirty bounds here, so user must manage passing dirty bounds to renderer
    renderer->renderGlop(nullptr, clip, glop);
#else
    GlopBuilder(renderer->mRenderState, renderer->mCaches, &glop)
            .setRoundRectClipState(renderer->currentSnapshot()->roundRectClipState)
            .setMeshTexturedIndexedQuads(texture.mesh(), texture.meshElementCount())
            .setFillTexturePaint(texture.getTexture(), textureFillFlags, paint, renderer->currentSnapshot()->alpha)
            .setTransform(*(renderer->currentSnapshot()), transformFlags)
            .setModelViewOffsetRect(0, 0, Rect())
            .build();
    renderer->renderGlop(glop);
#endif
}

///////////////////////////////////////////////////////////////////////////////
// FontRenderer
///////////////////////////////////////////////////////////////////////////////

static bool sLogFontRendererCreate = true;

FontRenderer::FontRenderer(const uint8_t* gammaTable)
        : mGammaTable(gammaTable)
        , mCurrentFont(nullptr)
        , mActiveFonts(LruCache<Font::FontDescription, Font*>::kUnlimitedCapacity)
        , mCurrentCacheTexture(nullptr)
        , mUploadTexture(false)
        , mFunctor(nullptr)
        , mClip(nullptr)
        , mBounds(nullptr)
        , mDrawn(false)
        , mInitialized(false)
        , mLinearFiltering(false) {

    if (sLogFontRendererCreate) {
        INIT_LOGD("Creating FontRenderer");
    }

    mSmallCacheWidth = property_get_int32(PROPERTY_TEXT_SMALL_CACHE_WIDTH,
            DEFAULT_TEXT_SMALL_CACHE_WIDTH);
    mSmallCacheHeight = property_get_int32(PROPERTY_TEXT_SMALL_CACHE_HEIGHT,
            DEFAULT_TEXT_SMALL_CACHE_HEIGHT);

    mLargeCacheWidth = property_get_int32(PROPERTY_TEXT_LARGE_CACHE_WIDTH,
            DEFAULT_TEXT_LARGE_CACHE_WIDTH);
    mLargeCacheHeight = property_get_int32(PROPERTY_TEXT_LARGE_CACHE_HEIGHT,
            DEFAULT_TEXT_LARGE_CACHE_HEIGHT);

    uint32_t maxTextureSize = (uint32_t) Caches::getInstance().maxTextureSize;

    mSmallCacheWidth = std::min(mSmallCacheWidth, maxTextureSize);
    mSmallCacheHeight = std::min(mSmallCacheHeight, maxTextureSize);
    mLargeCacheWidth = std::min(mLargeCacheWidth, maxTextureSize);
    mLargeCacheHeight = std::min(mLargeCacheHeight, maxTextureSize);

    if (sLogFontRendererCreate) {
        INIT_LOGD("  Text cache sizes, in pixels: %i x %i, %i x %i, %i x %i, %i x %i",
                mSmallCacheWidth, mSmallCacheHeight,
                mLargeCacheWidth, mLargeCacheHeight >> 1,
                mLargeCacheWidth, mLargeCacheHeight >> 1,
                mLargeCacheWidth, mLargeCacheHeight);
    }

    sLogFontRendererCreate = false;
}

void clearCacheTextures(std::vector<CacheTexture*>& cacheTextures) {
    for (uint32_t i = 0; i < cacheTextures.size(); i++) {
        delete cacheTextures[i];
    }
    cacheTextures.clear();
}

FontRenderer::~FontRenderer() {
    clearCacheTextures(mACacheTextures);
    clearCacheTextures(mRGBACacheTextures);

    LruCache<Font::FontDescription, Font*>::Iterator it(mActiveFonts);
    while (it.next()) {
        delete it.value();
    }
    mActiveFonts.clear();
}

void FontRenderer::flushAllAndInvalidate() {
    issueDrawCommand();

    LruCache<Font::FontDescription, Font*>::Iterator it(mActiveFonts);
    while (it.next()) {
        it.value()->invalidateTextureCache();
    }

    for (uint32_t i = 0; i < mACacheTextures.size(); i++) {
        mACacheTextures[i]->init();

#ifdef BUGREPORT_FONT_CACHE_USAGE
        mHistoryTracker.glyphsCleared(mACacheTextures[i]);
#endif
    }

    for (uint32_t i = 0; i < mRGBACacheTextures.size(); i++) {
        mRGBACacheTextures[i]->init();
#ifdef BUGREPORT_FONT_CACHE_USAGE
        mHistoryTracker.glyphsCleared(mRGBACacheTextures[i]);
#endif
    }

    mDrawn = false;
}

void FontRenderer::flushLargeCaches(std::vector<CacheTexture*>& cacheTextures) {
    // Start from 1; don't deallocate smallest/default texture
    for (uint32_t i = 1; i < cacheTextures.size(); i++) {
        CacheTexture* cacheTexture = cacheTextures[i];
        if (cacheTexture->getPixelBuffer()) {
            cacheTexture->init();
#ifdef BUGREPORT_FONT_CACHE_USAGE
            mHistoryTracker.glyphsCleared(cacheTexture);
#endif
            LruCache<Font::FontDescription, Font*>::Iterator it(mActiveFonts);
            while (it.next()) {
                it.value()->invalidateTextureCache(cacheTexture);
            }
            cacheTexture->releasePixelBuffer();
        }
    }
}

void FontRenderer::flushLargeCaches() {
    flushLargeCaches(mACacheTextures);
    flushLargeCaches(mRGBACacheTextures);
}

CacheTexture* FontRenderer::cacheBitmapInTexture(std::vector<CacheTexture*>& cacheTextures,
        const SkGlyph& glyph, uint32_t* startX, uint32_t* startY) {
    for (uint32_t i = 0; i < cacheTextures.size(); i++) {
        if (cacheTextures[i]->fitBitmap(glyph, startX, startY)) {
            return cacheTextures[i];
        }
    }
    // Could not fit glyph into current cache textures
    return nullptr;
}

void FontRenderer::cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
        uint32_t* retOriginX, uint32_t* retOriginY, bool precaching) {
    checkInit();

    // If the glyph bitmap is empty let's assum the glyph is valid
    // so we can avoid doing extra work later on
    if (glyph.fWidth == 0 || glyph.fHeight == 0) {
        cachedGlyph->mIsValid = true;
        cachedGlyph->mCacheTexture = nullptr;
        return;
    }

    cachedGlyph->mIsValid = false;

    // choose an appropriate cache texture list for this glyph format
    SkMask::Format format = static_cast<SkMask::Format>(glyph.fMaskFormat);
    std::vector<CacheTexture*>* cacheTextures = nullptr;
    switch (format) {
        case SkMask::kA8_Format:
        case SkMask::kBW_Format:
            cacheTextures = &mACacheTextures;
            break;
        case SkMask::kARGB32_Format:
            cacheTextures = &mRGBACacheTextures;
            break;
        default:
#if DEBUG_FONT_RENDERER
            ALOGD("getCacheTexturesForFormat: unknown SkMask format %x", format);
#endif
        return;
    }

    // If the glyph is too tall, don't cache it
    if (glyph.fHeight + TEXTURE_BORDER_SIZE * 2 >
                (*cacheTextures)[cacheTextures->size() - 1]->getHeight()) {
        ALOGE("Font size too large to fit in cache. width, height = %i, %i",
                (int) glyph.fWidth, (int) glyph.fHeight);
        return;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    CacheTexture* cacheTexture = cacheBitmapInTexture(*cacheTextures, glyph, &startX, &startY);

    if (!cacheTexture) {
        if (!precaching) {
            // If the new glyph didn't fit and we are not just trying to precache it,
            // clear out the cache and try again
            flushAllAndInvalidate();
            cacheTexture = cacheBitmapInTexture(*cacheTextures, glyph, &startX, &startY);
        }

        if (!cacheTexture) {
            // either the glyph didn't fit or we're precaching and will cache it when we draw
            return;
        }
    }

    cachedGlyph->mCacheTexture = cacheTexture;

    *retOriginX = startX;
    *retOriginY = startY;

    uint32_t endX = startX + glyph.fWidth;
    uint32_t endY = startY + glyph.fHeight;

    uint32_t cacheWidth = cacheTexture->getWidth();

    if (!cacheTexture->getPixelBuffer()) {
        Caches::getInstance().textureState().activateTexture(0);
        // Large-glyph texture memory is allocated only as needed
        cacheTexture->allocatePixelBuffer();
    }
    if (!cacheTexture->mesh()) {
        cacheTexture->allocateMesh();
    }

    uint8_t* cacheBuffer = cacheTexture->getPixelBuffer()->map();
    uint8_t* bitmapBuffer = (uint8_t*) glyph.fImage;
    int srcStride = glyph.rowBytes();

    // Copy the glyph image, taking the mask format into account
    switch (format) {
        case SkMask::kA8_Format: {
            uint32_t cacheX = 0, bX = 0, cacheY = 0, bY = 0;
            uint32_t row = (startY - TEXTURE_BORDER_SIZE) * cacheWidth + startX
                    - TEXTURE_BORDER_SIZE;
            // write leading border line
            memset(&cacheBuffer[row], 0, glyph.fWidth + 2 * TEXTURE_BORDER_SIZE);
            // write glyph data
            if (mGammaTable) {
                for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY += srcStride) {
                    row = cacheY * cacheWidth;
                    cacheBuffer[row + startX - TEXTURE_BORDER_SIZE] = 0;
                    for (cacheX = startX, bX = 0; cacheX < endX; cacheX++, bX++) {
                        uint8_t tempCol = bitmapBuffer[bY + bX];
                        cacheBuffer[row + cacheX] = mGammaTable[tempCol];
                    }
                    cacheBuffer[row + endX + TEXTURE_BORDER_SIZE - 1] = 0;
                }
            } else {
                for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY += srcStride) {
                    row = cacheY * cacheWidth;
                    memcpy(&cacheBuffer[row + startX], &bitmapBuffer[bY], glyph.fWidth);
                    cacheBuffer[row + startX - TEXTURE_BORDER_SIZE] = 0;
                    cacheBuffer[row + endX + TEXTURE_BORDER_SIZE - 1] = 0;
                }
            }
            // write trailing border line
            row = (endY + TEXTURE_BORDER_SIZE - 1) * cacheWidth + startX - TEXTURE_BORDER_SIZE;
            memset(&cacheBuffer[row], 0, glyph.fWidth + 2 * TEXTURE_BORDER_SIZE);
            break;
        }
        case SkMask::kARGB32_Format: {
            // prep data lengths
            const size_t formatSize = PixelBuffer::formatSize(GL_RGBA);
            const size_t borderSize = formatSize * TEXTURE_BORDER_SIZE;
            size_t rowSize = formatSize * glyph.fWidth;
            // prep advances
            size_t dstStride = formatSize * cacheWidth;
            // prep indices
            // - we actually start one row early, and then increment before first copy
            uint8_t* src = &bitmapBuffer[0 - srcStride];
            uint8_t* dst = &cacheBuffer[cacheTexture->getOffset(startX, startY - 1)];
            uint8_t* dstEnd = &cacheBuffer[cacheTexture->getOffset(startX, endY - 1)];
            uint8_t* dstL = dst - borderSize;
            uint8_t* dstR = dst + rowSize;
            // write leading border line
            memset(dstL, 0, rowSize + 2 * borderSize);
            // write glyph data
            while (dst < dstEnd) {
                memset(dstL += dstStride, 0, borderSize); // leading border column
                memcpy(dst += dstStride, src += srcStride, rowSize); // glyph data
                memset(dstR += dstStride, 0, borderSize); // trailing border column
            }
            // write trailing border line
            memset(dstL += dstStride, 0, rowSize + 2 * borderSize);
            break;
        }
        case SkMask::kBW_Format: {
            uint32_t cacheX = 0, cacheY = 0;
            uint32_t row = (startY - TEXTURE_BORDER_SIZE) * cacheWidth + startX
                    - TEXTURE_BORDER_SIZE;
            static const uint8_t COLORS[2] = { 0, 255 };
            // write leading border line
            memset(&cacheBuffer[row], 0, glyph.fWidth + 2 * TEXTURE_BORDER_SIZE);
            // write glyph data
            for (cacheY = startY; cacheY < endY; cacheY++) {
                cacheX = startX;
                int rowBytes = srcStride;
                uint8_t* buffer = bitmapBuffer;

                row = cacheY * cacheWidth;
                cacheBuffer[row + startX - TEXTURE_BORDER_SIZE] = 0;
                while (--rowBytes >= 0) {
                    uint8_t b = *buffer++;
                    for (int8_t mask = 7; mask >= 0 && cacheX < endX; mask--) {
                        cacheBuffer[cacheY * cacheWidth + cacheX++] = COLORS[(b >> mask) & 0x1];
                    }
                }
                cacheBuffer[row + endX + TEXTURE_BORDER_SIZE - 1] = 0;

                bitmapBuffer += srcStride;
            }
            // write trailing border line
            row = (endY + TEXTURE_BORDER_SIZE - 1) * cacheWidth + startX - TEXTURE_BORDER_SIZE;
            memset(&cacheBuffer[row], 0, glyph.fWidth + 2 * TEXTURE_BORDER_SIZE);
            break;
        }
        default:
            ALOGW("Unknown glyph format: 0x%x", format);
            break;
    }

    cachedGlyph->mIsValid = true;

#ifdef BUGREPORT_FONT_CACHE_USAGE
    mHistoryTracker.glyphUploaded(cacheTexture, startX, startY, glyph.fWidth, glyph.fHeight);
#endif
}

CacheTexture* FontRenderer::createCacheTexture(int width, int height, GLenum format,
        bool allocate) {
    CacheTexture* cacheTexture = new CacheTexture(width, height, format, kMaxNumberOfQuads);

    if (allocate) {
        Caches::getInstance().textureState().activateTexture(0);
        cacheTexture->allocatePixelBuffer();
        cacheTexture->allocateMesh();
    }

    return cacheTexture;
}

void FontRenderer::initTextTexture() {
    clearCacheTextures(mACacheTextures);
    clearCacheTextures(mRGBACacheTextures);

    mUploadTexture = false;
    mACacheTextures.push_back(createCacheTexture(mSmallCacheWidth, mSmallCacheHeight,
            GL_ALPHA, true));
    mACacheTextures.push_back(createCacheTexture(mLargeCacheWidth, mLargeCacheHeight >> 1,
            GL_ALPHA, false));
    mACacheTextures.push_back(createCacheTexture(mLargeCacheWidth, mLargeCacheHeight >> 1,
            GL_ALPHA, false));
    mACacheTextures.push_back(createCacheTexture(mLargeCacheWidth, mLargeCacheHeight,
            GL_ALPHA, false));
    mRGBACacheTextures.push_back(createCacheTexture(mSmallCacheWidth, mSmallCacheHeight,
            GL_RGBA, false));
    mRGBACacheTextures.push_back(createCacheTexture(mLargeCacheWidth, mLargeCacheHeight >> 1,
            GL_RGBA, false));
    mCurrentCacheTexture = mACacheTextures[0];
}

// We don't want to allocate anything unless we actually draw text
void FontRenderer::checkInit() {
    if (mInitialized) {
        return;
    }

    initTextTexture();

    mInitialized = true;
}

void checkTextureUpdateForCache(Caches& caches, std::vector<CacheTexture*>& cacheTextures,
        bool& resetPixelStore, GLuint& lastTextureId) {
    for (uint32_t i = 0; i < cacheTextures.size(); i++) {
        CacheTexture* cacheTexture = cacheTextures[i];
        if (cacheTexture->isDirty() && cacheTexture->getPixelBuffer()) {
            if (cacheTexture->getTextureId() != lastTextureId) {
                lastTextureId = cacheTexture->getTextureId();
                caches.textureState().activateTexture(0);
                caches.textureState().bindTexture(lastTextureId);
            }

            if (cacheTexture->upload()) {
                resetPixelStore = true;
            }
        }
    }
}

void FontRenderer::checkTextureUpdate() {
    if (!mUploadTexture) {
        return;
    }

    Caches& caches = Caches::getInstance();
    GLuint lastTextureId = 0;

    bool resetPixelStore = false;

    // Iterate over all the cache textures and see which ones need to be updated
    checkTextureUpdateForCache(caches, mACacheTextures, resetPixelStore, lastTextureId);
    checkTextureUpdateForCache(caches, mRGBACacheTextures, resetPixelStore, lastTextureId);

    // Unbind any PBO we might have used to update textures
    caches.pixelBufferState().unbind();

    // Reset to default unpack row length to avoid affecting texture
    // uploads in other parts of the renderer
    if (resetPixelStore) {
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    }

    mUploadTexture = false;
}

void FontRenderer::issueDrawCommand(std::vector<CacheTexture*>& cacheTextures) {
    if (!mFunctor) return;

    bool first = true;
    for (uint32_t i = 0; i < cacheTextures.size(); i++) {
        CacheTexture* texture = cacheTextures[i];
        if (texture->canDraw()) {
            if (first) {
                checkTextureUpdate();
                first = false;
                mDrawn = true;
            }

            mFunctor->draw(*texture, mLinearFiltering);

            texture->resetMesh();
        }
    }
}

void FontRenderer::issueDrawCommand() {
    issueDrawCommand(mACacheTextures);
    issueDrawCommand(mRGBACacheTextures);
}

void FontRenderer::appendMeshQuadNoClip(float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4, CacheTexture* texture) {
    if (texture != mCurrentCacheTexture) {
        // Now use the new texture id
        mCurrentCacheTexture = texture;
    }

    mCurrentCacheTexture->addQuad(x1, y1, u1, v1, x2, y2, u2, v2,
            x3, y3, u3, v3, x4, y4, u4, v4);
}

void FontRenderer::appendMeshQuad(float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4, CacheTexture* texture) {

    if (mClip &&
            (x1 > mClip->right || y1 < mClip->top || x2 < mClip->left || y4 > mClip->bottom)) {
        return;
    }

    appendMeshQuadNoClip(x1, y1, u1, v1, x2, y2, u2, v2, x3, y3, u3, v3, x4, y4, u4, v4, texture);

    if (mBounds) {
        mBounds->left = std::min(mBounds->left, x1);
        mBounds->top = std::min(mBounds->top, y3);
        mBounds->right = std::max(mBounds->right, x3);
        mBounds->bottom = std::max(mBounds->bottom, y1);
    }

    if (mCurrentCacheTexture->endOfMesh()) {
        issueDrawCommand();
    }
}

void FontRenderer::appendRotatedMeshQuad(float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4, CacheTexture* texture) {

    appendMeshQuadNoClip(x1, y1, u1, v1, x2, y2, u2, v2, x3, y3, u3, v3, x4, y4, u4, v4, texture);

    if (mBounds) {
        mBounds->left = std::min(mBounds->left, std::min(x1, std::min(x2, std::min(x3, x4))));
        mBounds->top = std::min(mBounds->top, std::min(y1, std::min(y2, std::min(y3, y4))));
        mBounds->right = std::max(mBounds->right, std::max(x1, std::max(x2, std::max(x3, x4))));
        mBounds->bottom = std::max(mBounds->bottom, std::max(y1, std::max(y2, std::max(y3, y4))));
    }

    if (mCurrentCacheTexture->endOfMesh()) {
        issueDrawCommand();
    }
}

void FontRenderer::setFont(const SkPaint* paint, const SkMatrix& matrix) {
    mCurrentFont = Font::create(this, paint, matrix);
}

FontRenderer::DropShadow FontRenderer::renderDropShadow(const SkPaint* paint, const glyph_t *glyphs,
        int numGlyphs, float radius, const float* positions) {
    checkInit();

    DropShadow image;
    image.width = 0;
    image.height = 0;
    image.image = nullptr;
    image.penX = 0;
    image.penY = 0;

    if (!mCurrentFont) {
        return image;
    }

    mDrawn = false;
    mClip = nullptr;
    mBounds = nullptr;

    Rect bounds;
    mCurrentFont->measure(paint, glyphs, numGlyphs, &bounds, positions);

    uint32_t intRadius = Blur::convertRadiusToInt(radius);
    uint32_t paddedWidth = (uint32_t) (bounds.right - bounds.left) + 2 * intRadius;
    uint32_t paddedHeight = (uint32_t) (bounds.top - bounds.bottom) + 2 * intRadius;

    uint32_t maxSize = Caches::getInstance().maxTextureSize;
    if (paddedWidth > maxSize || paddedHeight > maxSize) {
        return image;
    }

#ifdef ANDROID_ENABLE_RENDERSCRIPT
    // Align buffers for renderscript usage
    if (paddedWidth & (RS_CPU_ALLOCATION_ALIGNMENT - 1)) {
        paddedWidth += RS_CPU_ALLOCATION_ALIGNMENT - paddedWidth % RS_CPU_ALLOCATION_ALIGNMENT;
    }
    int size = paddedWidth * paddedHeight;
    uint8_t* dataBuffer = (uint8_t*) memalign(RS_CPU_ALLOCATION_ALIGNMENT, size);
#else
    int size = paddedWidth * paddedHeight;
    uint8_t* dataBuffer = (uint8_t*) malloc(size);
#endif

    memset(dataBuffer, 0, size);

    int penX = intRadius - bounds.left;
    int penY = intRadius - bounds.bottom;

    if ((bounds.right > bounds.left) && (bounds.top > bounds.bottom)) {
        // text has non-whitespace, so draw and blur to create the shadow
        // NOTE: bounds.isEmpty() can't be used here, since vertical coordinates are inverted
        // TODO: don't draw pure whitespace in the first place, and avoid needing this check
        mCurrentFont->render(paint, glyphs, numGlyphs, penX, penY,
                Font::BITMAP, dataBuffer, paddedWidth, paddedHeight, nullptr, positions);

        // Unbind any PBO we might have used
        Caches::getInstance().pixelBufferState().unbind();

        blurImage(&dataBuffer, paddedWidth, paddedHeight, radius);
    }

    image.width = paddedWidth;
    image.height = paddedHeight;
    image.image = dataBuffer;
    image.penX = penX;
    image.penY = penY;

    return image;
}

void FontRenderer::initRender(const Rect* clip, Rect* bounds, TextDrawFunctor* functor) {
    checkInit();

    mDrawn = false;
    mBounds = bounds;
    mFunctor = functor;
    mClip = clip;
}

void FontRenderer::finishRender() {
    mBounds = nullptr;
    mClip = nullptr;

    issueDrawCommand();
}

void FontRenderer::precache(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs,
        const SkMatrix& matrix) {
    Font* font = Font::create(this, paint, matrix);
    font->precache(paint, glyphs, numGlyphs);
}

void FontRenderer::endPrecaching() {
    checkTextureUpdate();
}

bool FontRenderer::renderPosText(const SkPaint* paint, const Rect* clip, const glyph_t* glyphs,
        int numGlyphs, int x, int y, const float* positions,
        Rect* bounds, TextDrawFunctor* functor, bool forceFinish) {
    if (!mCurrentFont) {
        ALOGE("No font set");
        return false;
    }

    initRender(clip, bounds, functor);
    mCurrentFont->render(paint, glyphs, numGlyphs, x, y, positions);

    if (forceFinish) {
        finishRender();
    }

    return mDrawn;
}

bool FontRenderer::renderTextOnPath(const SkPaint* paint, const Rect* clip, const glyph_t* glyphs,
        int numGlyphs, const SkPath* path, float hOffset, float vOffset,
        Rect* bounds, TextDrawFunctor* functor) {
    if (!mCurrentFont) {
        ALOGE("No font set");
        return false;
    }

    initRender(clip, bounds, functor);
    mCurrentFont->render(paint, glyphs, numGlyphs, path, hOffset, vOffset);
    finishRender();

    return mDrawn;
}

void FontRenderer::blurImage(uint8_t** image, int32_t width, int32_t height, float radius) {
    uint32_t intRadius = Blur::convertRadiusToInt(radius);
#ifdef ANDROID_ENABLE_RENDERSCRIPT
    if (width * height * intRadius >= RS_MIN_INPUT_CUTOFF && radius <= 25.0f) {
        uint8_t* outImage = (uint8_t*) memalign(RS_CPU_ALLOCATION_ALIGNMENT, width * height);

        if (mRs == nullptr) {
            mRs = new RSC::RS();
            // a null path is OK because there are no custom kernels used
            // hence nothing gets cached by RS
            if (!mRs->init("", RSC::RS_INIT_LOW_LATENCY | RSC::RS_INIT_SYNCHRONOUS)) {
                mRs.clear();
                ALOGE("blur RS failed to init");
            } else {
                mRsElement = RSC::Element::A_8(mRs);
                mRsScript = RSC::ScriptIntrinsicBlur::create(mRs, mRsElement);
            }
        }
        if (mRs != nullptr) {
            RSC::sp<const RSC::Type> t = RSC::Type::create(mRs, mRsElement, width, height, 0);
            RSC::sp<RSC::Allocation> ain = RSC::Allocation::createTyped(mRs, t,
                    RS_ALLOCATION_MIPMAP_NONE,
                    RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_SHARED,
                    *image);
            RSC::sp<RSC::Allocation> aout = RSC::Allocation::createTyped(mRs, t,
                    RS_ALLOCATION_MIPMAP_NONE,
                    RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_SHARED,
                    outImage);

            mRsScript->setRadius(radius);
            mRsScript->setInput(ain);
            mRsScript->forEach(aout);

            // replace the original image's pointer, avoiding a copy back to the original buffer
            free(*image);
            *image = outImage;

            return;
        }
    }
#endif

    std::unique_ptr<float[]> gaussian(new float[2 * intRadius + 1]);
    Blur::generateGaussianWeights(gaussian.get(), radius);

    std::unique_ptr<uint8_t[]> scratch(new uint8_t[width * height]);
    Blur::horizontal(gaussian.get(), intRadius, *image, scratch.get(), width, height);
    Blur::vertical(gaussian.get(), intRadius, scratch.get(), *image, width, height);
}

static uint32_t calculateCacheSize(const std::vector<CacheTexture*>& cacheTextures) {
    uint32_t size = 0;
    for (uint32_t i = 0; i < cacheTextures.size(); i++) {
        CacheTexture* cacheTexture = cacheTextures[i];
        if (cacheTexture && cacheTexture->getPixelBuffer()) {
            size += cacheTexture->getPixelBuffer()->getSize();
        }
    }
    return size;
}

static uint32_t calculateFreeCacheSize(const std::vector<CacheTexture*>& cacheTextures) {
    uint32_t size = 0;
    for (uint32_t i = 0; i < cacheTextures.size(); i++) {
        CacheTexture* cacheTexture = cacheTextures[i];
        if (cacheTexture && cacheTexture->getPixelBuffer()) {
            size += cacheTexture->calculateFreeMemory();
        }
    }
    return size;
}

const std::vector<CacheTexture*>& FontRenderer::cacheTexturesForFormat(GLenum format) const {
    switch (format) {
        case GL_ALPHA: {
            return mACacheTextures;
        }
        case GL_RGBA: {
            return mRGBACacheTextures;
        }
        default: {
            LOG_ALWAYS_FATAL("Unsupported format: %d", format);
            // Impossible to hit this, but the compiler doesn't know that
            return *(new std::vector<CacheTexture*>());
        }
    }
}

static void dumpTextures(String8& log, const char* tag,
        const std::vector<CacheTexture*>& cacheTextures) {
    for (uint32_t i = 0; i < cacheTextures.size(); i++) {
        CacheTexture* cacheTexture = cacheTextures[i];
        if (cacheTexture && cacheTexture->getPixelBuffer()) {
            uint32_t free = cacheTexture->calculateFreeMemory();
            uint32_t total = cacheTexture->getPixelBuffer()->getSize();
            log.appendFormat("    %-4s texture %d     %8d / %8d\n", tag, i, total - free, total);
        }
    }
}

void FontRenderer::dumpMemoryUsage(String8& log) const {
    const uint32_t sizeA8 = getCacheSize(GL_ALPHA);
    const uint32_t usedA8 = sizeA8 - getFreeCacheSize(GL_ALPHA);
    const uint32_t sizeRGBA = getCacheSize(GL_RGBA);
    const uint32_t usedRGBA = sizeRGBA - getFreeCacheSize(GL_RGBA);
    log.appendFormat("  FontRenderer A8      %8d / %8d\n", usedA8, sizeA8);
    dumpTextures(log, "A8", cacheTexturesForFormat(GL_ALPHA));
    log.appendFormat("  FontRenderer RGBA    %8d / %8d\n", usedRGBA, sizeRGBA);
    dumpTextures(log, "RGBA", cacheTexturesForFormat(GL_RGBA));
    log.appendFormat("  FontRenderer total   %8d / %8d\n", usedA8 + usedRGBA, sizeA8 + sizeRGBA);
}

uint32_t FontRenderer::getCacheSize(GLenum format) const {
    return calculateCacheSize(cacheTexturesForFormat(format));
}

uint32_t FontRenderer::getFreeCacheSize(GLenum format) const {
    return calculateFreeCacheSize(cacheTexturesForFormat(format));
}

uint32_t FontRenderer::getSize() const {
    return getCacheSize(GL_ALPHA) + getCacheSize(GL_RGBA);
}

}; // namespace uirenderer
}; // namespace android
