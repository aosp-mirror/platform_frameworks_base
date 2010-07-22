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

#include "FontRenderer.h"

#include <SkUtils.h>

namespace android {
namespace uirenderer {

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
        CachedGlyphInfo *glyph = mCachedGlyphs.valueAt(i);
        delete glyph;
    }
}

void Font::invalidateTextureCache() {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        mCachedGlyphs.valueAt(i)->mIsValid = false;
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y) {
    FontRenderer *state = mState;

    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop + glyph->mBitmapHeight;

    state->appendMeshQuad(nPenX, nPenY, 0, glyph->mBitmapMinU, glyph->mBitmapMaxV,
            nPenX + (int) glyph->mBitmapWidth, nPenY, 0, glyph->mBitmapMaxU, glyph->mBitmapMaxV,
            nPenX + (int) glyph->mBitmapWidth, nPenY - (int) glyph->mBitmapHeight,
            0, glyph->mBitmapMaxU, glyph->mBitmapMinV, nPenX, nPenY - (int) glyph->mBitmapHeight,
            0, glyph->mBitmapMinU, glyph->mBitmapMinV);
}

void Font::renderUTF(SkPaint* paint, const char *text, uint32_t len, uint32_t start,
        int numGlyphs, int x, int y) {
    if (numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    int penX = x, penY = y;
    int glyphsLeft = 1;
    if (numGlyphs > 0) {
        glyphsLeft = numGlyphs;
    }

    //size_t index = start;
    //size_t nextIndex = 0;

    text += start;

    while (glyphsLeft > 0) {
        //int32_t utfChar = utf32_at(text, len, index, &nextIndex);
        int32_t utfChar = SkUTF16_NextUnichar((const uint16_t**) &text);

        // Reached the end of the string or encountered
        if (utfChar < 0) {
            break;
        }

        // Move to the next character in the array
        //index = nextIndex;

        CachedGlyphInfo *cachedGlyph = mCachedGlyphs.valueFor(utfChar);

        if (cachedGlyph == NULL) {
            cachedGlyph = cacheGlyph(paint, utfChar);
        }
        // Is the glyph still in texture cache?
        if (!cachedGlyph->mIsValid) {
            const SkGlyph& skiaGlyph = paint->getUnicharMetrics(utfChar);
            updateGlyphCache(paint, skiaGlyph, cachedGlyph);
        }

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

void Font::updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo *glyph) {
    glyph->mAdvanceX = skiaGlyph.fAdvanceX;
    glyph->mAdvanceY = skiaGlyph.fAdvanceY;
    glyph->mBitmapLeft = skiaGlyph.fLeft;
    glyph->mBitmapTop = skiaGlyph.fTop;

    uint32_t startX = 0;
    uint32_t startY = 0;

    // Let the font state figure out where to put the bitmap
    FontRenderer *state = mState;
    // Get the bitmap for the glyph
    paint->findImage(skiaGlyph);
    glyph->mIsValid = state->cacheBitmap(skiaGlyph, &startX, &startY);

    if (!glyph->mIsValid) {
        return;
    }

    uint32_t endX = startX + skiaGlyph.fWidth;
    uint32_t endY = startY + skiaGlyph.fHeight;

    glyph->mBitmapWidth = skiaGlyph.fWidth;
    glyph->mBitmapHeight = skiaGlyph.fHeight;

    uint32_t cacheWidth = state->getCacheWidth();
    uint32_t cacheHeight = state->getCacheHeight();

    glyph->mBitmapMinU = (float) startX / (float) cacheWidth;
    glyph->mBitmapMinV = (float) startY / (float) cacheHeight;
    glyph->mBitmapMaxU = (float) endX / (float) cacheWidth;
    glyph->mBitmapMaxV = (float) endY / (float) cacheHeight;

    state->mUploadTexture = true;
}

Font::CachedGlyphInfo *Font::cacheGlyph(SkPaint* paint, int32_t glyph) {
    CachedGlyphInfo *newGlyph = new CachedGlyphInfo();
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
        Font *ithFont = activeFonts[i];
        if (ithFont->mFontId == fontId && ithFont->mFontSize == fontSize) {
            return ithFont;
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
    mInitialized = false;
    mMaxNumberOfQuads = 1024;
    mCurrentQuadIndex = 0;

    mIndexBufferID = 0;

    mCacheWidth = 1024;
    mCacheHeight = 256;
}

FontRenderer::~FontRenderer() {
    for (uint32_t i = 0; i < mCacheLines.size(); i++) {
        delete mCacheLines[i];
    }
    mCacheLines.clear();

    delete mTextTexture;

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

bool FontRenderer::cacheBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY) {
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

    unsigned char *cacheBuffer = mTextTexture;
    unsigned char *bitmapBuffer = (unsigned char*) glyph.fImage;
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

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Split up our cache texture into lines of certain widths
    int nextLine = 0;
    mCacheLines.push(new CacheTextureLine(16, mCacheWidth, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(24, mCacheWidth, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(32, mCacheWidth, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(32, mCacheWidth, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(40, mCacheWidth, nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(mCacheHeight - nextLine, mCacheWidth, nextLine, 0));
}

// Avoid having to reallocate memory and render quad by quad
void FontRenderer::initVertexArrayBuffers() {
    uint32_t numIndicies = mMaxNumberOfQuads * 6;
    uint32_t indexBufferSizeBytes = numIndicies * sizeof(uint16_t);
    uint16_t *indexBufferData = (uint16_t*) malloc(indexBufferSizeBytes);

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
    uint32_t vertexBufferSizeBytes = mMaxNumberOfQuads * vertsPerQuad * coordSize *
            uvSize * sizeof(float);
    mTextMeshPtr = (float*) malloc(vertexBufferSizeBytes);
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

void FontRenderer::issueDrawCommand() {
    if (mUploadTexture) {
        glBindTexture(GL_TEXTURE_2D, mTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, mCacheWidth, mCacheHeight, 0, GL_ALPHA,
                GL_UNSIGNED_BYTE, mTextTexture);
        mUploadTexture = false;
    }

    float *vtx = mTextMeshPtr;
    float *tex = vtx + 3;

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
    float *currentPos = mTextMeshPtr + mCurrentQuadIndex * vertsPerQuad * floatsPerVert;

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

void FontRenderer::setFont(uint32_t fontId, float fontSize) {
    mCurrentFont = Font::create(this, fontId, fontSize);
}

void FontRenderer::renderText(SkPaint* paint, const Rect* clip, const char *text, uint32_t len,
        uint32_t startIndex, int numGlyphs, int x, int y) {
    checkInit();

    if (!mCurrentFont) {
        LOGE("No font set");
        return;
    }

    mClip = clip;
    mCurrentFont->renderUTF(paint, text, len, startIndex, numGlyphs, x, y);

    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

}; // namespace uirenderer
}; // namespace android
