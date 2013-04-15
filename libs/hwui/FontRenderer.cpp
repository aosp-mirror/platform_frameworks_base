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

#define LOG_TAG "OpenGLRenderer"

#include <SkGlyph.h>
#include <SkUtils.h>

#include <cutils/properties.h>

#include <utils/Functor.h>
#include <utils/Log.h>

#include <RenderScript.h>

#include "utils/Blur.h"
#include "utils/Timing.h"

#include "Caches.h"
#include "Debug.h"
#include "Extensions.h"
#include "FontRenderer.h"
#include "PixelBuffer.h"
#include "Rect.h"

namespace android {
namespace uirenderer {

// blur inputs smaller than this constant will bypass renderscript
#define RS_MIN_INPUT_CUTOFF 10000

///////////////////////////////////////////////////////////////////////////////
// FontRenderer
///////////////////////////////////////////////////////////////////////////////

static bool sLogFontRendererCreate = true;

FontRenderer::FontRenderer() :
        mActiveFonts(LruCache<Font::FontDescription, Font*>::kUnlimitedCapacity) {

    if (sLogFontRendererCreate) {
        INIT_LOGD("Creating FontRenderer");
    }

    mGammaTable = NULL;
    mInitialized = false;

    mCurrentCacheTexture = NULL;

    mLinearFiltering = false;

    mIndexBufferID = 0;

    mSmallCacheWidth = DEFAULT_TEXT_SMALL_CACHE_WIDTH;
    mSmallCacheHeight = DEFAULT_TEXT_SMALL_CACHE_HEIGHT;
    mLargeCacheWidth = DEFAULT_TEXT_LARGE_CACHE_WIDTH;
    mLargeCacheHeight = DEFAULT_TEXT_LARGE_CACHE_HEIGHT;

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXT_SMALL_CACHE_WIDTH, property, NULL) > 0) {
        mSmallCacheWidth = atoi(property);
    }

    if (property_get(PROPERTY_TEXT_SMALL_CACHE_HEIGHT, property, NULL) > 0) {
        mSmallCacheHeight = atoi(property);
    }

    if (property_get(PROPERTY_TEXT_LARGE_CACHE_WIDTH, property, NULL) > 0) {
        mLargeCacheWidth = atoi(property);
    }

    if (property_get(PROPERTY_TEXT_LARGE_CACHE_HEIGHT, property, NULL) > 0) {
        mLargeCacheHeight = atoi(property);
    }

    uint32_t maxTextureSize = (uint32_t) Caches::getInstance().maxTextureSize;
    mSmallCacheWidth = mSmallCacheWidth > maxTextureSize ? maxTextureSize : mSmallCacheWidth;
    mSmallCacheHeight = mSmallCacheHeight > maxTextureSize ? maxTextureSize : mSmallCacheHeight;
    mLargeCacheWidth = mLargeCacheWidth > maxTextureSize ? maxTextureSize : mLargeCacheWidth;
    mLargeCacheHeight = mLargeCacheHeight > maxTextureSize ? maxTextureSize : mLargeCacheHeight;

    if (sLogFontRendererCreate) {
        INIT_LOGD("  Text cache sizes, in pixels: %i x %i, %i x %i, %i x %i, %i x %i",
                mSmallCacheWidth, mSmallCacheHeight,
                mLargeCacheWidth, mLargeCacheHeight >> 1,
                mLargeCacheWidth, mLargeCacheHeight >> 1,
                mLargeCacheWidth, mLargeCacheHeight);
    }

    sLogFontRendererCreate = false;
}

FontRenderer::~FontRenderer() {
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        delete mCacheTextures[i];
    }
    mCacheTextures.clear();

    if (mInitialized) {
        // Unbinding the buffer shouldn't be necessary but it crashes with some drivers
        Caches::getInstance().unbindIndicesBuffer();
        glDeleteBuffers(1, &mIndexBufferID);
    }

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

    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        mCacheTextures[i]->init();
    }
}

void FontRenderer::flushLargeCaches() {
    // Start from 1; don't deallocate smallest/default texture
    for (uint32_t i = 1; i < mCacheTextures.size(); i++) {
        CacheTexture* cacheTexture = mCacheTextures[i];
        if (cacheTexture->getPixelBuffer()) {
            cacheTexture->init();
            LruCache<Font::FontDescription, Font*>::Iterator it(mActiveFonts);
            while (it.next()) {
                it.value()->invalidateTextureCache(cacheTexture);
            }
            cacheTexture->releaseTexture();
        }
    }
}

CacheTexture* FontRenderer::cacheBitmapInTexture(const SkGlyph& glyph,
        uint32_t* startX, uint32_t* startY) {
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        if (mCacheTextures[i]->fitBitmap(glyph, startX, startY)) {
            return mCacheTextures[i];
        }
    }
    // Could not fit glyph into current cache textures
    return NULL;
}

void FontRenderer::cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
        uint32_t* retOriginX, uint32_t* retOriginY, bool precaching) {
    checkInit();

    // If the glyph bitmap is empty let's assum the glyph is valid
    // so we can avoid doing extra work later on
    if (glyph.fWidth == 0 || glyph.fHeight == 0) {
        cachedGlyph->mIsValid = true;
        cachedGlyph->mCacheTexture = NULL;
        return;
    }

    cachedGlyph->mIsValid = false;

    // If the glyph is too tall, don't cache it
    if (glyph.fHeight + TEXTURE_BORDER_SIZE * 2 >
                mCacheTextures[mCacheTextures.size() - 1]->getHeight()) {
        ALOGE("Font size too large to fit in cache. width, height = %i, %i",
                (int) glyph.fWidth, (int) glyph.fHeight);
        return;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    CacheTexture* cacheTexture = cacheBitmapInTexture(glyph, &startX, &startY);

    if (!cacheTexture) {
        if (!precaching) {
            // If the new glyph didn't fit and we are not just trying to precache it,
            // clear out the cache and try again
            flushAllAndInvalidate();
            cacheTexture = cacheBitmapInTexture(glyph, &startX, &startY);
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
        Caches::getInstance().activeTexture(0);
        // Large-glyph texture memory is allocated only as needed
        cacheTexture->allocateTexture();
    }
    if (!cacheTexture->mesh()) {
        cacheTexture->allocateMesh();
    }

    // Tells us whether the glyphs is B&W (1 bit per pixel)
    // or anti-aliased (8 bits per pixel)
    SkMask::Format format = static_cast<SkMask::Format>(glyph.fMaskFormat);

    uint8_t* cacheBuffer = cacheTexture->getPixelBuffer()->map();
    uint32_t cacheX = 0, bX = 0, cacheY = 0, bY = 0;

    // Copy the glyph image, taking the mask format into account
    uint8_t* bitmapBuffer = (uint8_t*) glyph.fImage;
    int stride = glyph.rowBytes();

    uint32_t row = (startY - TEXTURE_BORDER_SIZE) * cacheWidth + startX - TEXTURE_BORDER_SIZE;
    memset(&cacheBuffer[row], 0, glyph.fWidth + 2 * TEXTURE_BORDER_SIZE);

    switch (format) {
        case SkMask::kA8_Format: {
            if (mGammaTable) {
                for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY += stride) {
                    row = cacheY * cacheWidth;
                    cacheBuffer[row + startX - TEXTURE_BORDER_SIZE] = 0;
                    for (cacheX = startX, bX = 0; cacheX < endX; cacheX++, bX++) {
                        uint8_t tempCol = bitmapBuffer[bY + bX];
                        cacheBuffer[row + cacheX] = mGammaTable[tempCol];
                    }
                    cacheBuffer[row + endX + TEXTURE_BORDER_SIZE - 1] = 0;
                }
            } else {
                for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY += stride) {
                    row = cacheY * cacheWidth;
                    memcpy(&cacheBuffer[row + startX], &bitmapBuffer[bY], glyph.fWidth);
                    cacheBuffer[row + startX - TEXTURE_BORDER_SIZE] = 0;
                    cacheBuffer[row + endX + TEXTURE_BORDER_SIZE - 1] = 0;
                }
            }
            break;
        }
        case SkMask::kBW_Format: {
            static const uint8_t COLORS[2] = { 0, 255 };

            for (cacheY = startY; cacheY < endY; cacheY++) {
                cacheX = startX;
                int rowBytes = stride;
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

                bitmapBuffer += stride;
            }
            break;
        }
        default:
            ALOGW("Unkown glyph format: 0x%x", format);
            break;
    }

    row = (endY + TEXTURE_BORDER_SIZE - 1) * cacheWidth + startX - TEXTURE_BORDER_SIZE;
    memset(&cacheBuffer[row], 0, glyph.fWidth + 2 * TEXTURE_BORDER_SIZE);

    cachedGlyph->mIsValid = true;
}

CacheTexture* FontRenderer::createCacheTexture(int width, int height, bool allocate) {
    CacheTexture* cacheTexture = new CacheTexture(width, height, gMaxNumberOfQuads);

    if (allocate) {
        Caches::getInstance().activeTexture(0);
        cacheTexture->allocateTexture();
        cacheTexture->allocateMesh();
    }

    return cacheTexture;
}

void FontRenderer::initTextTexture() {
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        delete mCacheTextures[i];
    }
    mCacheTextures.clear();

    mUploadTexture = false;
    mCacheTextures.push(createCacheTexture(mSmallCacheWidth, mSmallCacheHeight, true));
    mCacheTextures.push(createCacheTexture(mLargeCacheWidth, mLargeCacheHeight >> 1, false));
    mCacheTextures.push(createCacheTexture(mLargeCacheWidth, mLargeCacheHeight >> 1, false));
    mCacheTextures.push(createCacheTexture(mLargeCacheWidth, mLargeCacheHeight, false));
    mCurrentCacheTexture = mCacheTextures[0];
}

// Avoid having to reallocate memory and render quad by quad
void FontRenderer::initVertexArrayBuffers() {
    uint32_t numIndices = gMaxNumberOfQuads * 6;
    uint32_t indexBufferSizeBytes = numIndices * sizeof(uint16_t);
    uint16_t* indexBufferData = (uint16_t*) malloc(indexBufferSizeBytes);

    // Four verts, two triangles , six indices per quad
    for (uint32_t i = 0; i < gMaxNumberOfQuads; i++) {
        int i6 = i * 6;
        int i4 = i * 4;

        indexBufferData[i6 + 0] = i4 + 0;
        indexBufferData[i6 + 1] = i4 + 1;
        indexBufferData[i6 + 2] = i4 + 2;

        indexBufferData[i6 + 3] = i4 + 0;
        indexBufferData[i6 + 4] = i4 + 2;
        indexBufferData[i6 + 5] = i4 + 3;
    }

    glGenBuffers(1, &mIndexBufferID);
    Caches::getInstance().bindIndicesBuffer(mIndexBufferID);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBufferSizeBytes, indexBufferData, GL_STATIC_DRAW);

    free(indexBufferData);
}

// We don't want to allocate anything unless we actually draw text
void FontRenderer::checkInit() {
    if (mInitialized) {
        return;
    }

    initTextTexture();
    initVertexArrayBuffers();

    mInitialized = true;
}

void FontRenderer::checkTextureUpdate() {
    if (!mUploadTexture) {
        return;
    }

    Caches& caches = Caches::getInstance();
    GLuint lastTextureId = 0;

    bool resetPixelStore = false;
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    // Iterate over all the cache textures and see which ones need to be updated
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        CacheTexture* cacheTexture = mCacheTextures[i];
        if (cacheTexture->isDirty() && cacheTexture->getPixelBuffer()) {
            if (cacheTexture->getTextureId() != lastTextureId) {
                lastTextureId = cacheTexture->getTextureId();
                caches.activeTexture(0);
                glBindTexture(GL_TEXTURE_2D, lastTextureId);
            }

            if (cacheTexture->upload()) {
                resetPixelStore = true;
            }

#if DEBUG_FONT_RENDERER
            ALOGD("glTexSubimage for cacheTexture %d: x, y, width height = %d, %d, %d, %d",
                    i, x, y, width, height);
#endif
        }
    }

    // Unbind any PBO we might have used to update textures
    caches.unbindPixelBuffer();

    // Reset to default unpack row length to avoid affecting texture
    // uploads in other parts of the renderer
    if (resetPixelStore) {
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    }

    mUploadTexture = false;
}

void FontRenderer::issueDrawCommand() {
    bool first = true;
    bool force = false;

    GLuint lastId = 0;
    Caches& caches = Caches::getInstance();

    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        CacheTexture* texture = mCacheTextures[i];
        if (texture->canDraw()) {
            if (first) {
                if (mFunctor) (*mFunctor)(0, NULL);

                checkTextureUpdate();
                caches.bindIndicesBuffer(mIndexBufferID);

                if (!mDrawn) {
                    // If returns true, a VBO was bound and we must
                    // rebind our vertex attrib pointers even if
                    // they have the same values as the current pointers
                    force = caches.unbindMeshBuffer();
                }

                caches.activeTexture(0);
                first = false;
            }

            glBindTexture(GL_TEXTURE_2D, texture->getTextureId());
            texture->setLinearFiltering(mLinearFiltering, false);

            TextureVertex* mesh = texture->mesh();
            caches.bindPositionVertexPointer(force, &mesh[0].position[0]);
            caches.bindTexCoordsVertexPointer(force, &mesh[0].texture[0]);
            force = false;

            glDrawElements(GL_TRIANGLES, texture->meshElementCount(),
                    GL_UNSIGNED_SHORT, texture->indices());

            texture->resetMesh();
        }
    }

    mDrawn = true;
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
        mBounds->left = fmin(mBounds->left, x1);
        mBounds->top = fmin(mBounds->top, y3);
        mBounds->right = fmax(mBounds->right, x3);
        mBounds->bottom = fmax(mBounds->bottom, y1);
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
        mBounds->left = fmin(mBounds->left, fmin(x1, fmin(x2, fmin(x3, x4))));
        mBounds->top = fmin(mBounds->top, fmin(y1, fmin(y2, fmin(y3, y4))));
        mBounds->right = fmax(mBounds->right, fmax(x1, fmax(x2, fmax(x3, x4))));
        mBounds->bottom = fmax(mBounds->bottom, fmax(y1, fmax(y2, fmax(y3, y4))));
    }

    if (mCurrentCacheTexture->endOfMesh()) {
        issueDrawCommand();
    }
}

void FontRenderer::setFont(SkPaint* paint, const mat4& matrix) {
    mCurrentFont = Font::create(this, paint, matrix);
}

FontRenderer::DropShadow FontRenderer::renderDropShadow(SkPaint* paint, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, uint32_t radius, const float* positions) {
    checkInit();

    DropShadow image;
    image.width = 0;
    image.height = 0;
    image.image = NULL;
    image.penX = 0;
    image.penY = 0;

    if (!mCurrentFont) {
        return image;
    }

    mDrawn = false;
    mClip = NULL;
    mBounds = NULL;

    Rect bounds;
    mCurrentFont->measure(paint, text, startIndex, len, numGlyphs, &bounds, positions);

    uint32_t paddedWidth = (uint32_t) (bounds.right - bounds.left) + 2 * radius;
    uint32_t paddedHeight = (uint32_t) (bounds.top - bounds.bottom) + 2 * radius;

    uint32_t maxSize = Caches::getInstance().maxTextureSize;
    if (paddedWidth > maxSize || paddedHeight > maxSize) {
        return image;
    }

    // Align buffers for renderscript usage
    if (paddedWidth & (RS_CPU_ALLOCATION_ALIGNMENT - 1)) {
        paddedWidth += RS_CPU_ALLOCATION_ALIGNMENT - paddedWidth % RS_CPU_ALLOCATION_ALIGNMENT;
    }

    int size = paddedWidth * paddedHeight;
    uint8_t* dataBuffer = (uint8_t*) memalign(RS_CPU_ALLOCATION_ALIGNMENT, size);
    memset(dataBuffer, 0, size);

    int penX = radius - bounds.left;
    int penY = radius - bounds.bottom;

    if ((bounds.right > bounds.left) && (bounds.top > bounds.bottom)) {
        // text has non-whitespace, so draw and blur to create the shadow
        // NOTE: bounds.isEmpty() can't be used here, since vertical coordinates are inverted
        // TODO: don't draw pure whitespace in the first place, and avoid needing this check
        mCurrentFont->render(paint, text, startIndex, len, numGlyphs, penX, penY,
                Font::BITMAP, dataBuffer, paddedWidth, paddedHeight, NULL, positions);

        // Unbind any PBO we might have used
        Caches::getInstance().unbindPixelBuffer();

        blurImage(&dataBuffer, paddedWidth, paddedHeight, radius);
    }

    image.width = paddedWidth;
    image.height = paddedHeight;
    image.image = dataBuffer;
    image.penX = penX;
    image.penY = penY;

    return image;
}

void FontRenderer::initRender(const Rect* clip, Rect* bounds, Functor* functor) {
    checkInit();

    mDrawn = false;
    mBounds = bounds;
    mFunctor = functor;
    mClip = clip;
}

void FontRenderer::finishRender() {
    mBounds = NULL;
    mClip = NULL;

    issueDrawCommand();
}

void FontRenderer::precache(SkPaint* paint, const char* text, int numGlyphs, const mat4& matrix) {
    Font* font = Font::create(this, paint, matrix);
    font->precache(paint, text, numGlyphs);
}

void FontRenderer::endPrecaching() {
    checkTextureUpdate();
}

bool FontRenderer::renderPosText(SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, int x, int y,
        const float* positions, Rect* bounds, Functor* functor, bool forceFinish) {
    if (!mCurrentFont) {
        ALOGE("No font set");
        return false;
    }

    initRender(clip, bounds, functor);
    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, x, y, positions);

    if (forceFinish) {
        finishRender();
    }

    return mDrawn;
}

bool FontRenderer::renderTextOnPath(SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, SkPath* path,
        float hOffset, float vOffset, Rect* bounds) {
    if (!mCurrentFont) {
        ALOGE("No font set");
        return false;
    }

    initRender(clip, bounds, NULL);
    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, path, hOffset, vOffset);
    finishRender();

    return mDrawn;
}

void FontRenderer::removeFont(const Font* font) {
    mActiveFonts.remove(font->getDescription());

    if (mCurrentFont == font) {
        mCurrentFont = NULL;
    }
}

void FontRenderer::blurImage(uint8_t** image, int32_t width, int32_t height, int32_t radius) {
    if (width * height * radius < RS_MIN_INPUT_CUTOFF) {
        float *gaussian = new float[2 * radius + 1];
        Blur::generateGaussianWeights(gaussian, radius);

        uint8_t* scratch = new uint8_t[width * height];
        Blur::horizontal(gaussian, radius, *image, scratch, width, height);
        Blur::vertical(gaussian, radius, scratch, *image, width, height);

        delete[] gaussian;
        delete[] scratch;
        return;
    }

    uint8_t* outImage = (uint8_t*) memalign(RS_CPU_ALLOCATION_ALIGNMENT, width * height);

    if (mRs.get() == 0) {
        mRs = new RSC::RS();
        if (!mRs->init(true, true)) {
            ALOGE("blur RS failed to init");
        }

        mRsElement = RSC::Element::A_8(mRs);
        mRsScript = new RSC::ScriptIntrinsicBlur(mRs, mRsElement);
    }

    sp<const RSC::Type> t = RSC::Type::create(mRs, mRsElement, width, height, 0);
    sp<RSC::Allocation> ain = RSC::Allocation::createTyped(mRs, t, RS_ALLOCATION_MIPMAP_NONE,
            RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_SHARED, *image);
    sp<RSC::Allocation> aout = RSC::Allocation::createTyped(mRs, t, RS_ALLOCATION_MIPMAP_NONE,
            RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_SHARED, outImage);

    mRsScript->setRadius(radius);
    mRsScript->blur(ain, aout);

    // replace the original image's pointer, avoiding a copy back to the original buffer
    free(*image);
    *image = outImage;
}

uint32_t FontRenderer::getCacheSize() const {
    uint32_t size = 0;
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        CacheTexture* cacheTexture = mCacheTextures[i];
        if (cacheTexture && cacheTexture->getPixelBuffer()) {
            size += cacheTexture->getPixelBuffer()->getSize();
        }
    }
    return size;
}

}; // namespace uirenderer
}; // namespace android
