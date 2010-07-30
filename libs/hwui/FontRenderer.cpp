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

#include "FontRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define DEFAULT_TEXT_CACHE_WIDTH 1024
#define DEFAULT_TEXT_CACHE_HEIGHT 256

///////////////////////////////////////////////////////////////////////////////
// Font
///////////////////////////////////////////////////////////////////////////////

Font::Font(FontRenderer* state, uint32_t fontId, float fontSize) :
    mState(state), mFontId(fontId), mFontSize(fontSize) {
}


Font::~Font() {
    for (uint32_t ct = 0; ct < mState->mActiveFonts.size(); ct++) {
        if (mState->mActiveFonts[ct] == this) {
            mState->mActiveFonts.removeAt(ct);
            break;
        }
    }

    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        CachedGlyphInfo* glyph = mCachedGlyphs.valueAt(i);
        delete glyph;
    }
}

void Font::invalidateTextureCache() {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        mCachedGlyphs.valueAt(i)->mIsValid = false;
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

    mState->appendMeshQuad(nPenX, nPenY, 0, u1, v2,
            nPenX + width, nPenY, 0, u2, v2,
            nPenX + width, nPenY - height, 0, u2, v1,
            nPenX, nPenY - height, 0, u1, v1);
}

Font::CachedGlyphInfo* Font::getCachedUTFChar(SkPaint* paint, int32_t utfChar) {
    CachedGlyphInfo* cachedGlyph = mCachedGlyphs.valueFor(utfChar);
    if (cachedGlyph == NULL) {
        cachedGlyph = cacheGlyph(paint, utfChar);
    }

    // Is the glyph still in texture cache?
    if (!cachedGlyph->mIsValid) {
        const SkGlyph& skiaGlyph = paint->getUnicharMetrics(utfChar);
        updateGlyphCache(paint, skiaGlyph, cachedGlyph);
    }

    return cachedGlyph;
}

void Font::renderUTF(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, int x, int y) {
    if (numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    int penX = x, penY = y;
    int glyphsLeft = 1;
    if (numGlyphs > 0) {
        glyphsLeft = numGlyphs;
    }

    text += start;

    while (glyphsLeft > 0) {
        int32_t utfChar = SkUTF16_NextUnichar((const uint16_t**) &text);

        // Reached the end of the string or encountered
        if (utfChar < 0) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedUTFChar(paint, utfChar);

        // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
        if (cachedGlyph->mIsValid) {
            drawCachedGlyph(cachedGlyph, penX, penY);
        }

        penX += SkFixedFloor(cachedGlyph->mAdvanceX);

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

    uint32_t startX = 0;
    uint32_t startY = 0;

    // Get the bitmap for the glyph
    paint->findImage(skiaGlyph);
    glyph->mIsValid = mState->cacheBitmap(skiaGlyph, &startX, &startY);

    if (!glyph->mIsValid) {
        return;
    }

    uint32_t endX = startX + skiaGlyph.fWidth;
    uint32_t endY = startY + skiaGlyph.fHeight;

    glyph->mBitmapWidth = skiaGlyph.fWidth;
    glyph->mBitmapHeight = skiaGlyph.fHeight;

    uint32_t cacheWidth = mState->getCacheWidth();
    uint32_t cacheHeight = mState->getCacheHeight();

    glyph->mBitmapMinU = (float) startX / (float) cacheWidth;
    glyph->mBitmapMinV = (float) startY / (float) cacheHeight;
    glyph->mBitmapMaxU = (float) endX / (float) cacheWidth;
    glyph->mBitmapMaxV = (float) endY / (float) cacheHeight;

    mState->mUploadTexture = true;
}

Font::CachedGlyphInfo* Font::cacheGlyph(SkPaint* paint, int32_t glyph) {
    CachedGlyphInfo* newGlyph = new CachedGlyphInfo();
    mCachedGlyphs.add(glyph, newGlyph);

    const SkGlyph& skiaGlyph = paint->getUnicharMetrics(glyph);
    newGlyph->mGlyphIndex = skiaGlyph.fID;
    newGlyph->mIsValid = false;

    updateGlyphCache(paint, skiaGlyph, newGlyph);

    return newGlyph;
}

Font* Font::create(FontRenderer* state, uint32_t fontId, float fontSize) {
    Vector<Font*> &activeFonts = state->mActiveFonts;

    for (uint32_t i = 0; i < activeFonts.size(); i++) {
        Font* font = activeFonts[i];
        if (font->mFontId == fontId && font->mFontSize == fontSize) {
            return font;
        }
    }

    Font* newFont = new Font(state, fontId, fontSize);
    activeFonts.push(newFont);
    return newFont;
}

///////////////////////////////////////////////////////////////////////////////
// FontRenderer
///////////////////////////////////////////////////////////////////////////////

FontRenderer::FontRenderer() {
    LOGD("Creating FontRenderer");

    mInitialized = false;
    mMaxNumberOfQuads = 1024;
    mCurrentQuadIndex = 0;
    mTextureId = 0;

    mIndexBufferID = 0;

    mCacheWidth = DEFAULT_TEXT_CACHE_WIDTH;
    mCacheHeight = DEFAULT_TEXT_CACHE_HEIGHT;

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXT_CACHE_WIDTH, property, NULL) > 0) {
        LOGD("  Setting text cache width to %s pixels", property);
        mCacheWidth = atoi(property);
    } else {
        LOGD("  Using default text cache width of %i pixels", mCacheWidth);
    }

    if (property_get(PROPERTY_TEXT_CACHE_HEIGHT, property, NULL) > 0) {
        LOGD("  Setting text cache width to %s pixels", property);
        mCacheHeight = atoi(property);
    } else {
        LOGD("  Using default text cache height of %i pixels", mCacheHeight);
    }
}

FontRenderer::~FontRenderer() {
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        delete mCacheLines[i];
    }
    mCacheLines.clear();

    delete mTextMeshPtr;

    delete mTextTexture;
    if(mTextureId) {
        glDeleteTextures(1, &mTextureId);
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

bool FontRenderer::cacheBitmap(const SkGlyph& glyph, uint32_t* retOriginX, uint32_t* retOriginY) {
    // If the glyph is too tall, don't cache it
    if (glyph.fWidth > mCacheLines[mCacheLines.size() - 1]->mMaxHeight) {
        LOGE("Font size to large to fit in cache. width, height = %i, %i",
                (int) glyph.fWidth, (int) glyph.fHeight);
        return false;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    bool bitmapFit = false;
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        bitmapFit = mCacheLines[i]->fitBitmap(glyph, &startX, &startY);
        if (bitmapFit) {
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
                break;
            }
        }

        // if we still don't fit, something is wrong and we shouldn't draw
        if (!bitmapFit) {
            LOGE("Bitmap doesn't fit in cache. width, height = %i, %i",
                    (int) glyph.fWidth, (int) glyph.fHeight);
            return false;
        }
    }

    *retOriginX = startX;
    *retOriginY = startY;

    uint32_t endX = startX + glyph.fWidth;
    uint32_t endY = startY + glyph.fHeight;

    uint32_t cacheWidth = mCacheWidth;

    unsigned char* cacheBuffer = mTextTexture;
    unsigned char* bitmapBuffer = (unsigned char*) glyph.fImage;
    unsigned int stride = glyph.rowBytes();

    uint32_t cacheX = 0, bX = 0, cacheY = 0, bY = 0;
    for (cacheX = startX, bX = 0; cacheX < endX; cacheX++, bX++) {
        for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY++) {
            unsigned char tempCol = bitmapBuffer[bY * stride + bX];
            cacheBuffer[cacheY * cacheWidth + cacheX] = tempCol;
        }
    }

    return true;
}

void FontRenderer::initTextTexture() {
    mTextTexture = new unsigned char[mCacheWidth * mCacheHeight];
    mUploadTexture = false;

    glGenTextures(1, &mTextureId);
    glBindTexture(GL_TEXTURE_2D, mTextureId);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    // Initialize texture dimentions
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, mCacheWidth, mCacheHeight, 0,
                  GL_ALPHA, GL_UNSIGNED_BYTE, 0);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Split up our cache texture into lines of certain widths
    int nextLine = 0;
    mCacheLines.push(new CacheTextureLine(mCacheWidth, 16, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mCacheWidth, 24, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mCacheWidth, 24, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mCacheWidth, 32, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mCacheWidth, 32, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mCacheWidth, 40, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mCacheWidth, mCacheHeight - nextLine, nextLine, 0));
}

// Avoid having to reallocate memory and render quad by quad
void FontRenderer::initVertexArrayBuffers() {
    uint32_t numIndicies = mMaxNumberOfQuads * 6;
    uint32_t indexBufferSizeBytes = numIndicies * sizeof(uint16_t);
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
    glBindBuffer(GL_ARRAY_BUFFER, mIndexBufferID);
    glBufferData(GL_ARRAY_BUFFER, indexBufferSizeBytes, indexBufferData, GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    free(indexBufferData);

    uint32_t coordSize = 3;
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
    if (!mUploadTexture) {
        return;
    }

    glBindTexture(GL_TEXTURE_2D, mTextureId);

    // Iterate over all the cache lines and see which ones need to be updated
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        CacheTextureLine* cl = mCacheLines[i];
        if(cl->mDirty) {
            uint32_t xOffset = 0;
            uint32_t yOffset = cl->mCurrentRow;
            uint32_t width   = mCacheWidth;
            uint32_t height  = cl->mMaxHeight;
            void*    textureData = mTextTexture + yOffset*width;

            glTexSubImage2D(GL_TEXTURE_2D, 0, xOffset, yOffset, width, height,
                             GL_ALPHA, GL_UNSIGNED_BYTE, textureData);

            cl->mDirty = false;
        }
    }

    mUploadTexture = false;
}

void FontRenderer::issueDrawCommand() {

    checkTextureUpdate();

    float* vtx = mTextMeshPtr;
    float* tex = vtx + 3;

    // position is slot 0
    uint32_t slot = 0;
    glVertexAttribPointer(slot, 3, GL_FLOAT, false, 20, vtx);

    // texture0 is slot 1
    slot = 1;
    glVertexAttribPointer(slot, 2, GL_FLOAT, false, 20, tex);

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexBufferID);
    glDrawElements(GL_TRIANGLES, mCurrentQuadIndex * 6, GL_UNSIGNED_SHORT, NULL);
}

void FontRenderer::appendMeshQuad(float x1, float y1, float z1, float u1, float v1, float x2,
        float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3,
        float x4, float y4, float z4, float u4, float v4) {
    if (x1 > mClip->right || y1 < mClip->top || x2 < mClip->left || y4 > mClip->bottom) {
        return;
    }

    const uint32_t vertsPerQuad = 4;
    const uint32_t floatsPerVert = 5;
    float* currentPos = mTextMeshPtr + mCurrentQuadIndex * vertsPerQuad * floatsPerVert;

    (*currentPos++) = x1;
    (*currentPos++) = y1;
    (*currentPos++) = z1;
    (*currentPos++) = u1;
    (*currentPos++) = v1;

    (*currentPos++) = x2;
    (*currentPos++) = y2;
    (*currentPos++) = z2;
    (*currentPos++) = u2;
    (*currentPos++) = v2;

    (*currentPos++) = x3;
    (*currentPos++) = y3;
    (*currentPos++) = z3;
    (*currentPos++) = u3;
    (*currentPos++) = v3;

    (*currentPos++) = x4;
    (*currentPos++) = y4;
    (*currentPos++) = z4;
    (*currentPos++) = u4;
    (*currentPos++) = v4;

    mCurrentQuadIndex++;

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
    while(remainingCapacity > 25 && precacheIdx < mLatinPrecache.size()) {
        mCurrentFont->getCachedUTFChar(paint, (int32_t)mLatinPrecache[precacheIdx]);
        remainingCapacity = getRemainingCacheCapacity();
        precacheIdx ++;
    }
}

void FontRenderer::setFont(SkPaint* paint, uint32_t fontId, float fontSize) {
    uint32_t currentNumFonts = mActiveFonts.size();
    mCurrentFont = Font::create(this, fontId, fontSize);

    const float maxPrecacheFontSize = 40.0f;
    bool isNewFont = currentNumFonts != mActiveFonts.size();

    if(isNewFont && fontSize <= maxPrecacheFontSize ){
        precacheLatin(paint);
    }
}

void FontRenderer::renderText(SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, int x, int y) {
    checkInit();

    if (!mCurrentFont) {
        LOGE("No font set");
        return;
    }

    mClip = clip;
    mCurrentFont->renderUTF(paint, text, startIndex, len, numGlyphs, x, y);

    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

}; // namespace uirenderer
}; // namespace android
