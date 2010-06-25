
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

#ifndef ANDROID_RS_BUILD_FOR_HOST
#include "rsContext.h"
#else
#include "rsContextHostStub.h"
#endif

#include "rsFont.h"
#include "rsProgramFragment.h"
#include FT_BITMAP_H

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;

Font::Font(Context *rsc) : ObjectBase(rsc), mCachedGlyphs(NULL)
{
    mInitialized = false;
    mHasKerning = false;
}

bool Font::init(const char *name, uint32_t fontSize, uint32_t dpi)
{
    if(mInitialized) {
        LOGE("Reinitialization of fonts not supported");
        return false;
    }

    String8 fontsDir("/fonts/");
    String8 fullPath(getenv("ANDROID_ROOT"));
    fullPath += fontsDir;
    fullPath += name;

    FT_Error error = FT_New_Face(mRSC->mStateFont.mLibrary, fullPath.string(), 0, &mFace);
    if(error) {
        LOGE("Unable to initialize font %s", fullPath.string());
        return false;
    }

    mFontName = name;
    mFontSize = fontSize;
    mDpi = dpi;

    //LOGE("Font initialized: %s", fullPath.string());

    error = FT_Set_Char_Size(mFace, fontSize * 64, 0, dpi, 0);
    if(error) {
        LOGE("Unable to set font size on %s", fullPath.string());
        return false;
    }

    mHasKerning = FT_HAS_KERNING(mFace);
    LOGE("Kerning: %i", mHasKerning);

    mInitialized = true;
    return true;
}

void Font::invalidateTextureCache()
{
    for(uint32_t i = 0; i < mCachedGlyphs.size(); i ++) {
        mCachedGlyphs.valueAt(i)->mIsValid = false;
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo *glyph, int x, int y)
{
    FontState *state = &mRSC->mStateFont;

    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y - glyph->mBitmapTop + glyph->mBitmapHeight;

    state->appendMeshQuad(nPenX, nPenY, 0,
                            glyph->mBitmapMinU, glyph->mBitmapMaxV,

                            nPenX + (int)glyph->mBitmapWidth, nPenY, 0,
                            glyph->mBitmapMaxU, glyph->mBitmapMaxV,

                            nPenX + (int)glyph->mBitmapWidth, nPenY - (int)glyph->mBitmapHeight, 0,
                            glyph->mBitmapMaxU, glyph->mBitmapMinV,

                            nPenX, nPenY - (int)glyph->mBitmapHeight, 0,
                            glyph->mBitmapMinU, glyph->mBitmapMinV);
}

void Font::renderUTF(const char *text, uint32_t len, uint32_t start, int numGlyphs, int x, int y)
{
    if(!mInitialized || numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    int penX = x, penY = y;
    int glyphsLeft = 1;
    if(numGlyphs > 0) {
        glyphsLeft = numGlyphs;
    }

    size_t index = start;
    size_t nextIndex = 0;

    while (glyphsLeft > 0) {

        int32_t utfChar = utf32_at(text, len, index, &nextIndex);

        // Reached the end of the string or encountered
        if(utfChar < 0) {
            break;
        }

        // Move to the next character in the array
        index = nextIndex;

        CachedGlyphInfo *cachedGlyph = mCachedGlyphs.valueFor((uint32_t)utfChar);

        if(cachedGlyph == NULL) {
            cachedGlyph = cacheGlyph((uint32_t)utfChar);
        }
        // Is the glyph still in texture cache?
        if(!cachedGlyph->mIsValid) {
            updateGlyphCache(cachedGlyph);
        }

        // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
        if(cachedGlyph->mIsValid) {
            drawCachedGlyph(cachedGlyph, penX, penY);
        }

        penX += (cachedGlyph->mAdvance.x >> 6);

        // If we were given a specific number of glyphs, decrement
        if(numGlyphs > 0) {
            glyphsLeft --;
        }
    }
}

void Font::updateGlyphCache(CachedGlyphInfo *glyph)
{
    if(!glyph->mBitmapValid) {

        FT_Error error = FT_Load_Glyph( mFace, glyph->mGlyphIndex, FT_LOAD_RENDER );
        if(error) {
            LOGE("Couldn't load glyph.");
            return;
        }

        glyph->mAdvance = mFace->glyph->advance;
        glyph->mBitmapLeft = mFace->glyph->bitmap_left;
        glyph->mBitmapTop = mFace->glyph->bitmap_top;

        FT_Bitmap *bitmap = &mFace->glyph->bitmap;

        FT_Bitmap_New(&glyph->mBitmap);
        FT_Bitmap_Copy(mRSC->mStateFont.mLibrary, bitmap, &glyph->mBitmap);

        glyph->mBitmapValid = true;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    // Let the font state figure out where to put the bitmap
    FontState *state = &mRSC->mStateFont;
    glyph->mIsValid = state->cacheBitmap(&glyph->mBitmap, &startX, &startY);

    if(!glyph->mIsValid) {
        return;
    }

    uint32_t endX = startX + glyph->mBitmap.width;
    uint32_t endY = startY + glyph->mBitmap.rows;

    glyph->mBitmapMinX = startX;
    glyph->mBitmapMinY = startY;
    glyph->mBitmapWidth = glyph->mBitmap.width;
    glyph->mBitmapHeight = glyph->mBitmap.rows;

    uint32_t cacheWidth = state->getCacheTextureType()->getDimX();
    uint32_t cacheHeight = state->getCacheTextureType()->getDimY();

    glyph->mBitmapMinU = (float)startX / (float)cacheWidth;
    glyph->mBitmapMinV = (float)startY / (float)cacheHeight;
    glyph->mBitmapMaxU = (float)endX / (float)cacheWidth;
    glyph->mBitmapMaxV = (float)endY / (float)cacheHeight;
}

Font::CachedGlyphInfo *Font::cacheGlyph(uint32_t glyph)
{
    CachedGlyphInfo *newGlyph = new CachedGlyphInfo();
    mCachedGlyphs.add(glyph, newGlyph);

    newGlyph->mGlyphIndex = FT_Get_Char_Index(mFace, glyph);
    newGlyph->mIsValid = false;
    newGlyph->mBitmapValid = false;

    //LOGE("Glyph = %c, face index: %u", (unsigned char)glyph, newGlyph->mGlyphIndex);

    updateGlyphCache(newGlyph);

    return newGlyph;
}

Font * Font::create(Context *rsc, const char *name, uint32_t fontSize, uint32_t dpi)
{
    Vector<Font*> &activeFonts = rsc->mStateFont.mActiveFonts;

    for(uint32_t i = 0; i < activeFonts.size(); i ++) {
        Font *ithFont = activeFonts[i];
        if(ithFont->mFontName == name && ithFont->mFontSize == fontSize && ithFont->mDpi == dpi) {
            ithFont->incUserRef();
            return ithFont;
        }
    }

    Font *newFont = new Font(rsc);
    bool isInitialized = newFont->init(name, fontSize, dpi);
    if(isInitialized) {
        newFont->incUserRef();
        activeFonts.push(newFont);
        return newFont;
    }

    delete newFont;
    return NULL;

}

Font::~Font()
{
    if(mFace) {
        FT_Done_Face(mFace);
    }

    for (uint32_t ct = 0; ct < mRSC->mStateFont.mActiveFonts.size(); ct++) {
        if (mRSC->mStateFont.mActiveFonts[ct] == this) {
            mRSC->mStateFont.mActiveFonts.removeAt(ct);
            break;
        }
    }

    for(uint32_t i = 0; i < mCachedGlyphs.size(); i ++) {
        CachedGlyphInfo *glyph = mCachedGlyphs.valueAt(i);
        if(glyph->mBitmapValid) {
            FT_Bitmap_Done(mRSC->mStateFont.mLibrary, &glyph->mBitmap);
        }
        delete glyph;
    }
}

FontState::FontState()
{
    mInitialized = false;
    mMaxNumberOfQuads = 1024;
    mCurrentQuadIndex = 0;
    mRSC = NULL;
}

FontState::~FontState()
{
    for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
        delete mCacheLines[i];
    }

    rsAssert(!mActiveFonts.size());
}

void FontState::init(Context *rsc)
{
    FT_Error error;

    if(!mLibrary) {
        error = FT_Init_FreeType(&mLibrary);
        if(error) {
            LOGE("Unable to initialize freetype");
            return;
        }
    }

    mRSC = rsc;

    mDefault.set(Font::create(rsc, "DroidSans.ttf", 16, 96));
}

void FontState::flushAllAndInvalidate()
{
    if(mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
    for(uint32_t i = 0; i < mActiveFonts.size(); i ++) {
        mActiveFonts[i]->invalidateTextureCache();
    }
    for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
        mCacheLines[i]->mCurrentCol = 0;
    }
}

bool FontState::cacheBitmap(FT_Bitmap *bitmap, uint32_t *retOriginX, uint32_t *retOriginY)
{
    // If the glyph is too tall, don't cache it
    if((uint32_t)bitmap->rows > mCacheLines[mCacheLines.size()-1]->mMaxHeight) {
        LOGE("Font size to large to fit in cache. width, height = %i, %i", (int)bitmap->width, (int)bitmap->rows);
        return false;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    bool bitmapFit = false;
    for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
        bitmapFit = mCacheLines[i]->fitBitmap(bitmap, &startX, &startY);
        if(bitmapFit) {
            break;
        }
    }

    // If the new glyph didn't fit, flush the state so far and invalidate everything
    if(!bitmapFit) {
        flushAllAndInvalidate();

        // Try to fit it again
        for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
            bitmapFit = mCacheLines[i]->fitBitmap(bitmap, &startX, &startY);
            if(bitmapFit) {
                break;
            }
        }

        // if we still don't fit, something is wrong and we shouldn't draw
        if(!bitmapFit) {
            LOGE("Bitmap doesn't fit in cache. width, height = %i, %i", (int)bitmap->width, (int)bitmap->rows);
            return false;
        }
    }

    *retOriginX = startX;
    *retOriginY = startY;

    uint32_t endX = startX + bitmap->width;
    uint32_t endY = startY + bitmap->rows;

    //LOGE("Bitmap width, height = %i, %i", (int)bitmap->width, (int)bitmap->rows);

    uint32_t cacheWidth = getCacheTextureType()->getDimX();

    unsigned char *cacheBuffer = (unsigned char*)mTextTexture->getPtr();
    unsigned char *bitmapBuffer = bitmap->buffer;

    uint32_t cacheX = 0, bX = 0, cacheY = 0, bY = 0;
    for(cacheX = startX, bX = 0; cacheX < endX; cacheX ++, bX ++) {
        for(cacheY = startY, bY = 0; cacheY < endY; cacheY ++, bY ++) {
            unsigned char tempCol = bitmapBuffer[bY * bitmap->width + bX];
            cacheBuffer[cacheY*cacheWidth + cacheX] = tempCol;
        }
    }

    // This will dirty the texture and the shader so next time
    // we draw it will upload the data
    mTextTexture->deferedUploadToTexture(mRSC, false, 0);
    mFontShaderF->bindTexture(0, mTextTexture.get());

    // Some debug code
    /*for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
        LOGE("Cache Line: H: %u Empty Space: %f",
             mCacheLines[i]->mMaxHeight,
              (1.0f - (float)mCacheLines[i]->mCurrentCol/(float)mCacheLines[i]->mMaxWidth)*100.0f);

    }*/

    return true;
}

void FontState::initRenderState()
{
    uint32_t tmp[5] = {
        RS_TEX_ENV_MODE_REPLACE, 1,
        RS_TEX_ENV_MODE_NONE, 0,
        0
    };
    ProgramFragment *pf = new ProgramFragment(mRSC, tmp, 5);
    mFontShaderF.set(pf);
    mFontShaderF->init(mRSC);

    Sampler *sampler = new Sampler(mRSC, RS_SAMPLER_NEAREST, RS_SAMPLER_NEAREST,
                                      RS_SAMPLER_CLAMP, RS_SAMPLER_CLAMP, RS_SAMPLER_CLAMP);
    mFontSampler.set(sampler);
    mFontShaderF->bindSampler(0, sampler);

    ProgramStore *fontStore = new ProgramStore(mRSC);
    mFontProgramStore.set(fontStore);
    mFontProgramStore->setDepthFunc(RS_DEPTH_FUNC_ALWAYS);
    mFontProgramStore->setBlendFunc(RS_BLEND_SRC_SRC_ALPHA, RS_BLEND_DST_ONE_MINUS_SRC_ALPHA);
    mFontProgramStore->setDitherEnable(false);
    mFontProgramStore->setDepthMask(false);
}

void FontState::initTextTexture()
{
    const Element *alphaElem = Element::create(mRSC, RS_TYPE_UNSIGNED_8, RS_KIND_PIXEL_A, true, 1);

    // We will allocate a texture to initially hold 32 character bitmaps
    Type *texType = new Type(mRSC);
    texType->setElement(alphaElem);
    texType->setDimX(1024);
    texType->setDimY(256);
    texType->compute();

    Allocation *cacheAlloc = new Allocation(mRSC, texType);
    mTextTexture.set(cacheAlloc);
    mTextTexture->deferedUploadToTexture(mRSC, false, 0);

    // Split up our cache texture into lines of certain widths
    int nextLine = 0;
    mCacheLines.push(new CacheTextureLine(16, texType->getDimX(), nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(24, texType->getDimX(), nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(32, texType->getDimX(), nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(32, texType->getDimX(), nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(40, texType->getDimX(), nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(texType->getDimY() - nextLine, texType->getDimX(), nextLine, 0));
}

// Avoid having to reallocate memory and render quad by quad
void FontState::initVertexArrayBuffers()
{
    // Now lets write index data
    const Element *indexElem = Element::create(mRSC, RS_TYPE_UNSIGNED_16, RS_KIND_USER, false, 1);
    Type *indexType = new Type(mRSC);
    uint32_t numIndicies = mMaxNumberOfQuads * 6;
    indexType->setDimX(numIndicies);
    indexType->setElement(indexElem);
    indexType->compute();

    Allocation *indexAlloc = new Allocation(mRSC, indexType);
    uint16_t *indexPtr = (uint16_t*)indexAlloc->getPtr();

    // Four verts, two triangles , six indices per quad
    for(uint32_t i = 0; i < mMaxNumberOfQuads; i ++) {
        int i6 = i * 6;
        int i4 = i * 4;

        indexPtr[i6 + 0] = i4 + 0;
        indexPtr[i6 + 1] = i4 + 1;
        indexPtr[i6 + 2] = i4 + 2;

        indexPtr[i6 + 3] = i4 + 0;
        indexPtr[i6 + 4] = i4 + 2;
        indexPtr[i6 + 5] = i4 + 3;
    }

    indexAlloc->deferedUploadToBufferObject(mRSC);
    mIndexBuffer.set(indexAlloc);

    const Element *posElem = Element::create(mRSC, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 3);
    const Element *texElem = Element::create(mRSC, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 2);

    const Element *elemArray[2];
    elemArray[0] = posElem;
    elemArray[1] = texElem;

    String8 posName("position");
    String8 texName("texture0");

    const char *nameArray[2];
    nameArray[0] = posName.string();
    nameArray[1] = texName.string();
    size_t lengths[2];
    lengths[0] = posName.size();
    lengths[1] = texName.size();

    const Element *vertexDataElem = Element::create(mRSC, 2, elemArray, nameArray, lengths);

    Type *vertexDataType = new Type(mRSC);
    vertexDataType->setDimX(mMaxNumberOfQuads * 4);
    vertexDataType->setElement(vertexDataElem);
    vertexDataType->compute();

    Allocation *vertexAlloc = new Allocation(mRSC, vertexDataType);
    mTextMeshPtr = (float*)vertexAlloc->getPtr();

    mVertexArray.set(vertexAlloc);
}

// We don't want to allocate anything unless we actually draw text
void FontState::checkInit()
{
    if(mInitialized) {
        return;
    }

    initTextTexture();
    initRenderState();

    initVertexArrayBuffers();

    /*mTextMeshRefs = new ObjectBaseRef<SimpleMesh>[mNumMeshes];

    for(uint32_t i = 0; i < mNumMeshes; i ++){
        SimpleMesh *textMesh = createTextMesh();
        mTextMeshRefs[i].set(textMesh);
    }*/

    mInitialized = true;
}

void FontState::issueDrawCommand() {

    ObjectBaseRef<const ProgramVertex> tmpV(mRSC->getVertex());
    mRSC->setVertex(mRSC->getDefaultProgramVertex());

    ObjectBaseRef<const ProgramFragment> tmpF(mRSC->getFragment());
    mRSC->setFragment(mFontShaderF.get());

    ObjectBaseRef<const ProgramStore> tmpPS(mRSC->getFragmentStore());
    mRSC->setFragmentStore(mFontProgramStore.get());

    if (!mRSC->setupCheck()) {
        mRSC->setVertex((ProgramVertex *)tmpV.get());
        mRSC->setFragment((ProgramFragment *)tmpF.get());
        mRSC->setFragmentStore((ProgramStore *)tmpPS.get());
        return;
    }

    float *vtx = (float*)mVertexArray->getPtr();
    float *tex = vtx + 3;

    VertexArray va;
    va.add(GL_FLOAT, 3, 20, false, (uint32_t)vtx, "position");
    va.add(GL_FLOAT, 2, 20, false, (uint32_t)tex, "texture0");
    va.setupGL2(mRSC, &mRSC->mStateVertexArray, &mRSC->mShaderCache);

    mIndexBuffer->uploadCheck(mRSC);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexBuffer->getBufferObjectID());
    glDrawElements(GL_TRIANGLES, mCurrentQuadIndex * 6, GL_UNSIGNED_SHORT, (uint16_t *)(0));

    // Reset the state
    mRSC->setVertex((ProgramVertex *)tmpV.get());
    mRSC->setFragment((ProgramFragment *)tmpF.get());
    mRSC->setFragmentStore((ProgramStore *)tmpPS.get());
}

void FontState::appendMeshQuad(float x1, float y1, float z1,
                                  float u1, float v1,
                                  float x2, float y2, float z2,
                                  float u2, float v2,
                                  float x3, float y3, float z3,
                                  float u3, float v3,
                                  float x4, float y4, float z4,
                                  float u4, float v4)
{
    const uint32_t vertsPerQuad = 4;
    const uint32_t floatsPerVert = 5;
    float *currentPos = mTextMeshPtr + mCurrentQuadIndex * vertsPerQuad * floatsPerVert;

    // Cull things that are off the screen
    float width = (float)mRSC->getWidth();
    float height = (float)mRSC->getHeight();

    if(x1 > width || y1 < 0.0f || x2 < 0 || y4 > height) {
        return;
    }

    /*LOGE("V0 x: %f y: %f z: %f", x1, y1, z1);
    LOGE("V1 x: %f y: %f z: %f", x2, y2, z2);
    LOGE("V2 x: %f y: %f z: %f", x3, y3, z3);
    LOGE("V3 x: %f y: %f z: %f", x4, y4, z4);*/

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

    mCurrentQuadIndex ++;

    if(mCurrentQuadIndex == mMaxNumberOfQuads) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

void FontState::renderText(const char *text, uint32_t len, uint32_t startIndex, int numGlyphs, int x, int y)
{
    checkInit();

    String8 text8(text);

    // Render code here
    Font *currentFont = mRSC->getFont();
    currentFont->renderUTF(text, len, startIndex, numGlyphs, x, y);

    if(mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

void FontState::renderText(const char *text, int x, int y)
{
    size_t textLen = strlen(text);
    renderText(text, textLen, 0, -1, x, y);
}

void FontState::renderText(Allocation *alloc, int x, int y)
{
    if(!alloc) {
        return;
    }

    const char *text = (const char *)alloc->getPtr();
    size_t allocSize = alloc->getType()->getSizeBytes();
    renderText(text, allocSize, 0, -1, x, y);
}

void FontState::renderText(Allocation *alloc, uint32_t start, int len, int x, int y)
{
    if(!alloc) {
        return;
    }

    const char *text = (const char *)alloc->getPtr();
    size_t allocSize = alloc->getType()->getSizeBytes();
    renderText(text, allocSize, start, len, x, y);
}

void FontState::deinit(Context *rsc)
{
    if(mLibrary) {
        FT_Done_FreeType( mLibrary );
    }

    delete mDefault.get();
    mDefault.clear();
}

namespace android {
namespace renderscript {

RsFont rsi_FontCreateFromFile(Context *rsc, char const *name, uint32_t fontSize, uint32_t dpi)
{
    return Font::create(rsc, name, fontSize, dpi);
}

} // renderscript
} // android
