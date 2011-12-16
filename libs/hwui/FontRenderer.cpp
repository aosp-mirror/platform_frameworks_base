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

#include <SkUtils.h>

#include <cutils/properties.h>

#include <utils/Log.h>

#include "Caches.h"
#include "Debug.h"
#include "FontRenderer.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define DEFAULT_TEXT_CACHE_WIDTH 1024
#define DEFAULT_TEXT_CACHE_HEIGHT 256
#define MAX_TEXT_CACHE_WIDTH 2048
#define TEXTURE_BORDER_SIZE 2

///////////////////////////////////////////////////////////////////////////////
// CacheTextureLine
///////////////////////////////////////////////////////////////////////////////

bool CacheTextureLine::fitBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY) {
    if (glyph.fHeight + TEXTURE_BORDER_SIZE > mMaxHeight) {
        return false;
    }

    if (mCurrentCol + glyph.fWidth + TEXTURE_BORDER_SIZE < mMaxWidth) {
        *retOriginX = mCurrentCol + 1;
        *retOriginY = mCurrentRow + 1;
        mCurrentCol += glyph.fWidth + TEXTURE_BORDER_SIZE;
        mDirty = true;
        return true;
    }

    return false;
}

///////////////////////////////////////////////////////////////////////////////
// Font
///////////////////////////////////////////////////////////////////////////////

Font::Font(FontRenderer* state, uint32_t fontId, float fontSize,
        int flags, uint32_t italicStyle, uint32_t scaleX,
        SkPaint::Style style, uint32_t strokeWidth) :
        mState(state), mFontId(fontId), mFontSize(fontSize),
        mFlags(flags), mItalicStyle(italicStyle), mScaleX(scaleX),
        mStyle(style), mStrokeWidth(mStrokeWidth) {
}


Font::~Font() {
    for (uint32_t ct = 0; ct < mState->mActiveFonts.size(); ct++) {
        if (mState->mActiveFonts[ct] == this) {
            mState->mActiveFonts.removeAt(ct);
            break;
        }
    }

    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        delete mCachedGlyphs.valueAt(i);
    }
}

void Font::invalidateTextureCache() {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        mCachedGlyphs.valueAt(i)->mIsValid = false;
    }
}

void Font::measureCachedGlyph(CachedGlyphInfo *glyph, int x, int y, Rect *bounds) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop;

    int width = (int) glyph->mBitmapWidth;
    int height = (int) glyph->mBitmapHeight;

    if (bounds->bottom > nPenY) {
        bounds->bottom = nPenY;
    }
    if (bounds->left > nPenX) {
        bounds->left = nPenX;
    }
    if (bounds->right < nPenX + width) {
        bounds->right = nPenX + width;
    }
    if (bounds->top < nPenY + height) {
        bounds->top = nPenY + height;
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop + glyph->mBitmapHeight;

    float u1 = glyph->mBitmapMinU;
    float u2 = glyph->mBitmapMaxU;
    float v1 = glyph->mBitmapMinV;
    float v2 = glyph->mBitmapMaxV;

    int width = (int) glyph->mBitmapWidth;
    int height = (int) glyph->mBitmapHeight;

    mState->appendMeshQuad(nPenX, nPenY, u1, v2,
            nPenX + width, nPenY, u2, v2,
            nPenX + width, nPenY - height, u2, v1,
            nPenX, nPenY - height, u1, v1, glyph->mCachedTextureLine->mCacheTexture);
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
        uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop;

    uint32_t endX = glyph->mStartX + glyph->mBitmapWidth;
    uint32_t endY = glyph->mStartY + glyph->mBitmapHeight;

    CacheTexture *cacheTexture = glyph->mCachedTextureLine->mCacheTexture;
    uint32_t cacheWidth = cacheTexture->mWidth;
    const uint8_t* cacheBuffer = cacheTexture->mTexture;

    uint32_t cacheX = 0, cacheY = 0;
    int32_t bX = 0, bY = 0;
    for (cacheX = glyph->mStartX, bX = nPenX; cacheX < endX; cacheX++, bX++) {
        for (cacheY = glyph->mStartY, bY = nPenY; cacheY < endY; cacheY++, bY++) {
            if (bX < 0 || bY < 0 || bX >= (int32_t) bitmapW || bY >= (int32_t) bitmapH) {
                LOGE("Skipping invalid index");
                continue;
            }
            uint8_t tempCol = cacheBuffer[cacheY * cacheWidth + cacheX];
            bitmap[bY * bitmapW + bX] = tempCol;
        }
    }
}

CachedGlyphInfo* Font::getCachedGlyph(SkPaint* paint, glyph_t textUnit) {
    CachedGlyphInfo* cachedGlyph = NULL;
    ssize_t index = mCachedGlyphs.indexOfKey(textUnit);
    if (index >= 0) {
        cachedGlyph = mCachedGlyphs.valueAt(index);
    } else {
        cachedGlyph = cacheGlyph(paint, textUnit);
    }

    // Is the glyph still in texture cache?
    if (!cachedGlyph->mIsValid) {
        const SkGlyph& skiaGlyph = GET_METRICS(paint, textUnit);
        updateGlyphCache(paint, skiaGlyph, cachedGlyph);
    }

    return cachedGlyph;
}

void Font::render(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, int x, int y, uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH) {
    if (bitmap != NULL && bitmapW > 0 && bitmapH > 0) {
        render(paint, text, start, len, numGlyphs, x, y, BITMAP, bitmap,
                bitmapW, bitmapH, NULL);
    } else {
        render(paint, text, start, len, numGlyphs, x, y, FRAMEBUFFER, NULL,
                0, 0, NULL);
    }
}

void Font::measure(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, Rect *bounds) {
    if (bounds == NULL) {
        LOGE("No return rectangle provided to measure text");
        return;
    }
    bounds->set(1e6, -1e6, -1e6, 1e6);
    render(paint, text, start, len, numGlyphs, 0, 0, MEASURE, NULL, 0, 0, bounds);
}

#define SkAutoKern_AdjustF(prev, next) (((next) - (prev) + 32) >> 6 << 16)

void Font::render(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, int x, int y, RenderMode mode, uint8_t *bitmap,
        uint32_t bitmapW, uint32_t bitmapH,Rect *bounds) {
    if (numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    float penX = x;
    int penY = y;
    int glyphsLeft = 1;
    if (numGlyphs > 0) {
        glyphsLeft = numGlyphs;
    }

    SkFixed prevRsbDelta = 0;
    penX += 0.5f;

    text += start;

    while (glyphsLeft > 0) {
        glyph_t glyph = GET_GLYPH(text);

        // Reached the end of the string
        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);
        penX += SkFixedToFloat(SkAutoKern_AdjustF(prevRsbDelta, cachedGlyph->mLsbDelta));
        prevRsbDelta = cachedGlyph->mRsbDelta;

        // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
        if (cachedGlyph->mIsValid) {
            switch(mode) {
            case FRAMEBUFFER:
                drawCachedGlyph(cachedGlyph, (int) floorf(penX), penY);
                break;
            case BITMAP:
                drawCachedGlyph(cachedGlyph, (int) floorf(penX), penY, bitmap, bitmapW, bitmapH);
                break;
            case MEASURE:
                measureCachedGlyph(cachedGlyph, (int) floorf(penX), penY, bounds);
                break;
            }
        }

        penX += SkFixedToFloat(cachedGlyph->mAdvanceX);

        // If we were given a specific number of glyphs, decrement
        if (numGlyphs > 0) {
            glyphsLeft--;
        }
    }
}

void Font::updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo* glyph) {
    glyph->mAdvanceX = skiaGlyph.fAdvanceX;
    glyph->mAdvanceY = skiaGlyph.fAdvanceY;
    glyph->mBitmapLeft = skiaGlyph.fLeft;
    glyph->mBitmapTop = skiaGlyph.fTop;
    glyph->mLsbDelta = skiaGlyph.fLsbDelta;
    glyph->mRsbDelta = skiaGlyph.fRsbDelta;

    uint32_t startX = 0;
    uint32_t startY = 0;

    // Get the bitmap for the glyph
    paint->findImage(skiaGlyph);
    mState->cacheBitmap(skiaGlyph, glyph, &startX, &startY);

    if (!glyph->mIsValid) {
        return;
    }

    uint32_t endX = startX + skiaGlyph.fWidth;
    uint32_t endY = startY + skiaGlyph.fHeight;

    glyph->mStartX = startX;
    glyph->mStartY = startY;
    glyph->mBitmapWidth = skiaGlyph.fWidth;
    glyph->mBitmapHeight = skiaGlyph.fHeight;

    uint32_t cacheWidth = glyph->mCachedTextureLine->mCacheTexture->mWidth;
    uint32_t cacheHeight = glyph->mCachedTextureLine->mCacheTexture->mHeight;

    glyph->mBitmapMinU = (float) startX / (float) cacheWidth;
    glyph->mBitmapMinV = (float) startY / (float) cacheHeight;
    glyph->mBitmapMaxU = (float) endX / (float) cacheWidth;
    glyph->mBitmapMaxV = (float) endY / (float) cacheHeight;

    mState->mUploadTexture = true;
}

CachedGlyphInfo* Font::cacheGlyph(SkPaint* paint, glyph_t glyph) {
    CachedGlyphInfo* newGlyph = new CachedGlyphInfo();
    mCachedGlyphs.add(glyph, newGlyph);

    const SkGlyph& skiaGlyph = GET_METRICS(paint, glyph);
    newGlyph->mGlyphIndex = skiaGlyph.fID;
    newGlyph->mIsValid = false;

    updateGlyphCache(paint, skiaGlyph, newGlyph);

    return newGlyph;
}

Font* Font::create(FontRenderer* state, uint32_t fontId, float fontSize,
        int flags, uint32_t italicStyle, uint32_t scaleX,
        SkPaint::Style style, uint32_t strokeWidth) {
    Vector<Font*> &activeFonts = state->mActiveFonts;

    for (uint32_t i = 0; i < activeFonts.size(); i++) {
        Font* font = activeFonts[i];
        if (font->mFontId == fontId && font->mFontSize == fontSize &&
                font->mFlags == flags && font->mItalicStyle == italicStyle &&
                font->mScaleX == scaleX && font->mStyle == style &&
                (style == SkPaint::kFill_Style || font->mStrokeWidth == strokeWidth)) {
            return font;
        }
    }

    Font* newFont = new Font(state, fontId, fontSize, flags, italicStyle,
            scaleX, style, strokeWidth);
    activeFonts.push(newFont);
    return newFont;
}

///////////////////////////////////////////////////////////////////////////////
// FontRenderer
///////////////////////////////////////////////////////////////////////////////

static bool sLogFontRendererCreate = true;

FontRenderer::FontRenderer() {
    if (sLogFontRendererCreate) {
        INIT_LOGD("Creating FontRenderer");
    }

    mGammaTable = NULL;
    mInitialized = false;
    mMaxNumberOfQuads = 1024;
    mCurrentQuadIndex = 0;

    mTextMeshPtr = NULL;
    mCurrentCacheTexture = NULL;
    mLastCacheTexture = NULL;
    mCacheTextureSmall = NULL;
    mCacheTexture128 = NULL;
    mCacheTexture256 = NULL;
    mCacheTexture512 = NULL;

    mLinearFiltering = false;

    mIndexBufferID = 0;

    mSmallCacheWidth = DEFAULT_TEXT_CACHE_WIDTH;
    mSmallCacheHeight = DEFAULT_TEXT_CACHE_HEIGHT;

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXT_CACHE_WIDTH, property, NULL) > 0) {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Setting text cache width to %s pixels", property);
        }
        mSmallCacheWidth = atoi(property);
    } else {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Using default text cache width of %i pixels", mSmallCacheWidth);
        }
    }

    if (property_get(PROPERTY_TEXT_CACHE_HEIGHT, property, NULL) > 0) {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Setting text cache width to %s pixels", property);
        }
        mSmallCacheHeight = atoi(property);
    } else {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Using default text cache height of %i pixels", mSmallCacheHeight);
        }
    }

    sLogFontRendererCreate = false;
}

FontRenderer::~FontRenderer() {
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        delete mCacheLines[i];
    }
    mCacheLines.clear();

    if (mInitialized) {
        delete[] mTextMeshPtr;
        delete mCacheTextureSmall;
        delete mCacheTexture128;
        delete mCacheTexture256;
        delete mCacheTexture512;
    }

    Vector<Font*> fontsToDereference = mActiveFonts;
    for (uint32_t i = 0; i < fontsToDereference.size(); i++) {
        delete fontsToDereference[i];
    }
}

void FontRenderer::flushAllAndInvalidate() {
    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
    for (uint32_t i = 0; i < mActiveFonts.size(); i++) {
        mActiveFonts[i]->invalidateTextureCache();
    }
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        mCacheLines[i]->mCurrentCol = 0;
    }
}

void FontRenderer::allocateTextureMemory(CacheTexture *cacheTexture) {
    int width = cacheTexture->mWidth;
    int height = cacheTexture->mHeight;
    cacheTexture->mTexture = new uint8_t[width * height];
    memset(cacheTexture->mTexture, 0, width * height * sizeof(uint8_t));
    glBindTexture(GL_TEXTURE_2D, cacheTexture->mTextureId);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    // Initialize texture dimensions
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, width, height, 0,
            GL_ALPHA, GL_UNSIGNED_BYTE, 0);

    const GLenum filtering = cacheTexture->mLinearFiltering ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

void FontRenderer::cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
        uint32_t* retOriginX, uint32_t* retOriginY) {
    cachedGlyph->mIsValid = false;
    // If the glyph is too tall, don't cache it
    if (glyph.fHeight + TEXTURE_BORDER_SIZE > mCacheLines[mCacheLines.size() - 1]->mMaxHeight) {
        LOGE("Font size to large to fit in cache. width, height = %i, %i",
                (int) glyph.fWidth, (int) glyph.fHeight);
        return;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    bool bitmapFit = false;
    CacheTextureLine *cacheLine;
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        bitmapFit = mCacheLines[i]->fitBitmap(glyph, &startX, &startY);
        if (bitmapFit) {
            cacheLine = mCacheLines[i];
            break;
        }
    }

    // If the new glyph didn't fit, flush the state so far and invalidate everything
    if (!bitmapFit) {
        flushAllAndInvalidate();

        // Try to fit it again
        for (uint32_t i = 0; i < mCacheLines.size(); i++) {
            bitmapFit = mCacheLines[i]->fitBitmap(glyph, &startX, &startY);
            if (bitmapFit) {
                cacheLine = mCacheLines[i];
                break;
            }
        }

        // if we still don't fit, something is wrong and we shouldn't draw
        if (!bitmapFit) {
            return;
        }
    }

    cachedGlyph->mCachedTextureLine = cacheLine;

    *retOriginX = startX;
    *retOriginY = startY;

    uint32_t endX = startX + glyph.fWidth;
    uint32_t endY = startY + glyph.fHeight;

    uint32_t cacheWidth = cacheLine->mMaxWidth;

    CacheTexture *cacheTexture = cacheLine->mCacheTexture;
    if (cacheTexture->mTexture == NULL) {
        // Large-glyph texture memory is allocated only as needed
        allocateTextureMemory(cacheTexture);
    }
    uint8_t* cacheBuffer = cacheTexture->mTexture;
    uint8_t* bitmapBuffer = (uint8_t*) glyph.fImage;
    unsigned int stride = glyph.rowBytes();

    uint32_t cacheX = 0, bX = 0, cacheY = 0, bY = 0;
    for (cacheX = startX, bX = 0; cacheX < endX; cacheX++, bX++) {
        for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY++) {
            uint8_t tempCol = bitmapBuffer[bY * stride + bX];
            cacheBuffer[cacheY * cacheWidth + cacheX] = mGammaTable[tempCol];
        }
    }
    cachedGlyph->mIsValid = true;
}

CacheTexture* FontRenderer::createCacheTexture(int width, int height, bool allocate) {
    GLuint textureId;
    glGenTextures(1, &textureId);
    uint8_t* textureMemory = NULL;

    CacheTexture* cacheTexture = new CacheTexture(textureMemory, textureId, width, height);
    if (allocate) {
        allocateTextureMemory(cacheTexture);
    }
    return cacheTexture;
}

void FontRenderer::initTextTexture() {
    mCacheLines.clear();

    // Next, use other, separate caches for large glyphs.
    uint16_t maxWidth = 0;
    if (Caches::hasInstance()) {
        maxWidth = Caches::getInstance().maxTextureSize;
    }
    if (maxWidth > MAX_TEXT_CACHE_WIDTH || maxWidth == 0) {
        maxWidth = MAX_TEXT_CACHE_WIDTH;
    }
    if (mCacheTextureSmall != NULL) {
        delete mCacheTextureSmall;
        delete mCacheTexture128;
        delete mCacheTexture256;
        delete mCacheTexture512;
    }
    mCacheTextureSmall = createCacheTexture(mSmallCacheWidth, mSmallCacheHeight, true);
    mCacheTexture128 = createCacheTexture(maxWidth, 256, false);
    mCacheTexture256 = createCacheTexture(maxWidth, 256, false);
    mCacheTexture512 = createCacheTexture(maxWidth, 512, false);
    mCurrentCacheTexture = mCacheTextureSmall;

    mUploadTexture = false;
    // Split up our default cache texture into lines of certain widths
    int nextLine = 0;
    mCacheLines.push(new CacheTextureLine(mSmallCacheWidth, 18, nextLine, 0, mCacheTextureSmall));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mSmallCacheWidth, 26, nextLine, 0, mCacheTextureSmall));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mSmallCacheWidth, 26, nextLine, 0, mCacheTextureSmall));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mSmallCacheWidth, 34, nextLine, 0, mCacheTextureSmall));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mSmallCacheWidth, 34, nextLine, 0, mCacheTextureSmall));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mSmallCacheWidth, 42, nextLine, 0, mCacheTextureSmall));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mSmallCacheWidth, mSmallCacheHeight - nextLine,
            nextLine, 0, mCacheTextureSmall));

    //  The first cache is split into 2 lines of height 128, the rest have just one cache line.
    mCacheLines.push(new CacheTextureLine(maxWidth, 128, 0, 0, mCacheTexture128));
    mCacheLines.push(new CacheTextureLine(maxWidth, 128, 128, 0, mCacheTexture128));
    mCacheLines.push(new CacheTextureLine(maxWidth, 256, 0, 0, mCacheTexture256));
    mCacheLines.push(new CacheTextureLine(maxWidth, 512, 0, 0, mCacheTexture512));
}

// Avoid having to reallocate memory and render quad by quad
void FontRenderer::initVertexArrayBuffers() {
    uint32_t numIndices = mMaxNumberOfQuads * 6;
    uint32_t indexBufferSizeBytes = numIndices * sizeof(uint16_t);
    uint16_t* indexBufferData = (uint16_t*) malloc(indexBufferSizeBytes);

    // Four verts, two triangles , six indices per quad
    for (uint32_t i = 0; i < mMaxNumberOfQuads; i++) {
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

    uint32_t coordSize = 2;
    uint32_t uvSize = 2;
    uint32_t vertsPerQuad = 4;
    uint32_t vertexBufferSize = mMaxNumberOfQuads * vertsPerQuad * coordSize * uvSize;
    mTextMeshPtr = new float[vertexBufferSize];
}

// We don't want to allocate anything unless we actually draw text
void FontRenderer::checkInit() {
    if (mInitialized) {
        return;
    }

    initTextTexture();
    initVertexArrayBuffers();

    // We store a string with letters in a rough frequency of occurrence
    mLatinPrecache = String16("eisarntolcdugpmhbyfvkwzxjq ");
    mLatinPrecache += String16("EISARNTOLCDUGPMHBYFVKWZXJQ");
    mLatinPrecache += String16(",.?!()-+@;:`'");
    mLatinPrecache += String16("0123456789");

    mInitialized = true;
}

void FontRenderer::checkTextureUpdate() {
    if (!mUploadTexture && mLastCacheTexture == mCurrentCacheTexture) {
        return;
    }

    Caches& caches = Caches::getInstance();
    GLuint lastTextureId = 0;
    // Iterate over all the cache lines and see which ones need to be updated
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        CacheTextureLine* cl = mCacheLines[i];
        if (cl->mDirty && cl->mCacheTexture->mTexture != NULL) {
            CacheTexture* cacheTexture = cl->mCacheTexture;
            uint32_t xOffset = 0;
            uint32_t yOffset = cl->mCurrentRow;
            uint32_t width   = cl->mMaxWidth;
            uint32_t height  = cl->mMaxHeight;
            void* textureData = cacheTexture->mTexture + (yOffset * width);

            if (cacheTexture->mTextureId != lastTextureId) {
                caches.activeTexture(0);
                glBindTexture(GL_TEXTURE_2D, cacheTexture->mTextureId);
                lastTextureId = cacheTexture->mTextureId;
            }
            glTexSubImage2D(GL_TEXTURE_2D, 0, xOffset, yOffset, width, height,
                    GL_ALPHA, GL_UNSIGNED_BYTE, textureData);

            cl->mDirty = false;
        }
    }

    glBindTexture(GL_TEXTURE_2D, mCurrentCacheTexture->mTextureId);
    if (mLinearFiltering != mCurrentCacheTexture->mLinearFiltering) {
        const GLenum filtering = mLinearFiltering ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);
        mCurrentCacheTexture->mLinearFiltering = mLinearFiltering;
    }
    mLastCacheTexture = mCurrentCacheTexture;

    mUploadTexture = false;
}

void FontRenderer::issueDrawCommand() {
    checkTextureUpdate();

    Caches& caches = Caches::getInstance();
    caches.bindIndicesBuffer(mIndexBufferID);
    if (!mDrawn) {
        float* buffer = mTextMeshPtr;
        int offset = 2;

        bool force = caches.unbindMeshBuffer();
        caches.bindPositionVertexPointer(force, caches.currentProgram->position, buffer);
        caches.bindTexCoordsVertexPointer(force, caches.currentProgram->texCoords,
                buffer + offset);
    }

    glDrawElements(GL_TRIANGLES, mCurrentQuadIndex * 6, GL_UNSIGNED_SHORT, NULL);

    mDrawn = true;
}

void FontRenderer::appendMeshQuad(float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2,
        float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4, CacheTexture* texture) {

    if (mClip &&
            (x1 > mClip->right || y1 < mClip->top || x2 < mClip->left || y4 > mClip->bottom)) {
        return;
    }
    if (texture != mCurrentCacheTexture) {
        if (mCurrentQuadIndex != 0) {
            // First, draw everything stored already which uses the previous texture
            issueDrawCommand();
            mCurrentQuadIndex = 0;
        }
        // Now use the new texture id
        mCurrentCacheTexture = texture;
    }

    const uint32_t vertsPerQuad = 4;
    const uint32_t floatsPerVert = 4;
    float* currentPos = mTextMeshPtr + mCurrentQuadIndex * vertsPerQuad * floatsPerVert;

    (*currentPos++) = x1;
    (*currentPos++) = y1;
    (*currentPos++) = u1;
    (*currentPos++) = v1;

    (*currentPos++) = x2;
    (*currentPos++) = y2;
    (*currentPos++) = u2;
    (*currentPos++) = v2;

    (*currentPos++) = x3;
    (*currentPos++) = y3;
    (*currentPos++) = u3;
    (*currentPos++) = v3;

    (*currentPos++) = x4;
    (*currentPos++) = y4;
    (*currentPos++) = u4;
    (*currentPos++) = v4;

    mCurrentQuadIndex++;

    if (mBounds) {
        mBounds->left = fmin(mBounds->left, x1);
        mBounds->top = fmin(mBounds->top, y3);
        mBounds->right = fmax(mBounds->right, x3);
        mBounds->bottom = fmax(mBounds->bottom, y1);
    }

    if (mCurrentQuadIndex == mMaxNumberOfQuads) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

uint32_t FontRenderer::getRemainingCacheCapacity() {
    uint32_t remainingCapacity = 0;
    float totalPixels = 0;
    for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
         remainingCapacity += (mCacheLines[i]->mMaxWidth - mCacheLines[i]->mCurrentCol);
         totalPixels += mCacheLines[i]->mMaxWidth;
    }
    remainingCapacity = (remainingCapacity * 100) / totalPixels;
    return remainingCapacity;
}

void FontRenderer::precacheLatin(SkPaint* paint) {
    // Remaining capacity is measured in %
    uint32_t remainingCapacity = getRemainingCacheCapacity();
    uint32_t precacheIdx = 0;
    while (remainingCapacity > 25 && precacheIdx < mLatinPrecache.size()) {
        mCurrentFont->getCachedGlyph(paint, (int32_t) mLatinPrecache[precacheIdx]);
        remainingCapacity = getRemainingCacheCapacity();
        precacheIdx ++;
    }
}

void FontRenderer::setFont(SkPaint* paint, uint32_t fontId, float fontSize) {
    uint32_t currentNumFonts = mActiveFonts.size();
    int flags = 0;
    if (paint->isFakeBoldText()) {
        flags |= Font::kFakeBold;
    }

    const float skewX = paint->getTextSkewX();
    uint32_t italicStyle = *(uint32_t*) &skewX;
    const float scaleXFloat = paint->getTextScaleX();
    uint32_t scaleX = *(uint32_t*) &scaleXFloat;
    SkPaint::Style style = paint->getStyle();
    const float strokeWidthFloat = paint->getStrokeWidth();
    uint32_t strokeWidth = *(uint32_t*) &strokeWidthFloat;
    mCurrentFont = Font::create(this, fontId, fontSize, flags, italicStyle,
            scaleX, style, strokeWidth);

    const float maxPrecacheFontSize = 40.0f;
    bool isNewFont = currentNumFonts != mActiveFonts.size();

    if (isNewFont && fontSize <= maxPrecacheFontSize) {
        precacheLatin(paint);
    }
}

FontRenderer::DropShadow FontRenderer::renderDropShadow(SkPaint* paint, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, uint32_t radius) {
    checkInit();

    if (!mCurrentFont) {
        DropShadow image;
        image.width = 0;
        image.height = 0;
        image.image = NULL;
        image.penX = 0;
        image.penY = 0;
        return image;
    }

    mDrawn = false;
    mClip = NULL;
    mBounds = NULL;

    Rect bounds;
    mCurrentFont->measure(paint, text, startIndex, len, numGlyphs, &bounds);

    uint32_t paddedWidth = (uint32_t) (bounds.right - bounds.left) + 2 * radius;
    uint32_t paddedHeight = (uint32_t) (bounds.top - bounds.bottom) + 2 * radius;
    uint8_t* dataBuffer = new uint8_t[paddedWidth * paddedHeight];

    for (uint32_t i = 0; i < paddedWidth * paddedHeight; i++) {
        dataBuffer[i] = 0;
    }

    int penX = radius - bounds.left;
    int penY = radius - bounds.bottom;

    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, penX, penY,
            dataBuffer, paddedWidth, paddedHeight);
    blurImage(dataBuffer, paddedWidth, paddedHeight, radius);

    DropShadow image;
    image.width = paddedWidth;
    image.height = paddedHeight;
    image.image = dataBuffer;
    image.penX = penX;
    image.penY = penY;

    return image;
}

bool FontRenderer::renderText(SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, int x, int y, Rect* bounds) {
    checkInit();

    if (!mCurrentFont) {
        LOGE("No font set");
        return false;
    }

    mDrawn = false;
    mBounds = bounds;
    mClip = clip;

    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, x, y);

    mBounds = NULL;
    mClip = NULL;

    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }

    return mDrawn;
}

void FontRenderer::computeGaussianWeights(float* weights, int32_t radius) {
    // Compute gaussian weights for the blur
    // e is the euler's number
    float e = 2.718281828459045f;
    float pi = 3.1415926535897932f;
    // g(x) = ( 1 / sqrt( 2 * pi ) * sigma) * e ^ ( -x^2 / 2 * sigma^2 )
    // x is of the form [-radius .. 0 .. radius]
    // and sigma varies with radius.
    // Based on some experimental radius values and sigma's
    // we approximately fit sigma = f(radius) as
    // sigma = radius * 0.3  + 0.6
    // The larger the radius gets, the more our gaussian blur
    // will resemble a box blur since with large sigma
    // the gaussian curve begins to lose its shape
    float sigma = 0.3f * (float) radius + 0.6f;

    // Now compute the coefficints
    // We will store some redundant values to save some math during
    // the blur calculations
    // precompute some values
    float coeff1 = 1.0f / (sqrt( 2.0f * pi ) * sigma);
    float coeff2 = - 1.0f / (2.0f * sigma * sigma);

    float normalizeFactor = 0.0f;
    for (int32_t r = -radius; r <= radius; r ++) {
        float floatR = (float) r;
        weights[r + radius] = coeff1 * pow(e, floatR * floatR * coeff2);
        normalizeFactor += weights[r + radius];
    }

    //Now we need to normalize the weights because all our coefficients need to add up to one
    normalizeFactor = 1.0f / normalizeFactor;
    for (int32_t r = -radius; r <= radius; r ++) {
        weights[r + radius] *= normalizeFactor;
    }
}

void FontRenderer::horizontalBlur(float* weights, int32_t radius,
        const uint8_t* source, uint8_t* dest, int32_t width, int32_t height) {
    float blurredPixel = 0.0f;
    float currentPixel = 0.0f;

    for (int32_t y = 0; y < height; y ++) {

        const uint8_t* input = source + y * width;
        uint8_t* output = dest + y * width;

        for (int32_t x = 0; x < width; x ++) {
            blurredPixel = 0.0f;
            const float* gPtr = weights;
            // Optimization for non-border pixels
            if (x > radius && x < (width - radius)) {
                const uint8_t *i = input + (x - radius);
                for (int r = -radius; r <= radius; r ++) {
                    currentPixel = (float) (*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                    i++;
                }
            } else {
                for (int32_t r = -radius; r <= radius; r ++) {
                    // Stepping left and right away from the pixel
                    int validW = x + r;
                    if (validW < 0) {
                        validW = 0;
                    }
                    if (validW > width - 1) {
                        validW = width - 1;
                    }

                    currentPixel = (float) input[validW];
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                }
            }
            *output = (uint8_t)blurredPixel;
            output ++;
        }
    }
}

void FontRenderer::verticalBlur(float* weights, int32_t radius,
        const uint8_t* source, uint8_t* dest, int32_t width, int32_t height) {
    float blurredPixel = 0.0f;
    float currentPixel = 0.0f;

    for (int32_t y = 0; y < height; y ++) {

        uint8_t* output = dest + y * width;

        for (int32_t x = 0; x < width; x ++) {
            blurredPixel = 0.0f;
            const float* gPtr = weights;
            const uint8_t* input = source + x;
            // Optimization for non-border pixels
            if (y > radius && y < (height - radius)) {
                const uint8_t *i = input + ((y - radius) * width);
                for (int32_t r = -radius; r <= radius; r ++) {
                    currentPixel = (float)(*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                    i += width;
                }
            } else {
                for (int32_t r = -radius; r <= radius; r ++) {
                    int validH = y + r;
                    // Clamp to zero and width
                    if (validH < 0) {
                        validH = 0;
                    }
                    if (validH > height - 1) {
                        validH = height - 1;
                    }

                    const uint8_t *i = input + validH * width;
                    currentPixel = (float) (*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                }
            }
            *output = (uint8_t) blurredPixel;
            output ++;
        }
    }
}


void FontRenderer::blurImage(uint8_t *image, int32_t width, int32_t height, int32_t radius) {
    float *gaussian = new float[2 * radius + 1];
    computeGaussianWeights(gaussian, radius);

    uint8_t* scratch = new uint8_t[width * height];

    horizontalBlur(gaussian, radius, image, scratch, width, height);
    verticalBlur(gaussian, radius, scratch, image, width, height);

    delete[] gaussian;
    delete[] scratch;
}

}; // namespace uirenderer
}; // namespace android
