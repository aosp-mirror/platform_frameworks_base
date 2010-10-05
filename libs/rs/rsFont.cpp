
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
#include <cutils/properties.h>
#include FT_BITMAP_H

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;

Font::Font(Context *rsc) : ObjectBase(rsc), mCachedGlyphs(NULL)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mInitialized = false;
    mHasKerning = false;
    mFace = NULL;
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

    FT_Error error = FT_New_Face(mRSC->mStateFont.getLib(), fullPath.string(), 0, &mFace);
    if(error) {
        LOGE("Unable to initialize font %s", fullPath.string());
        return false;
    }

    mFontName = name;
    mFontSize = fontSize;
    mDpi = dpi;

    error = FT_Set_Char_Size(mFace, fontSize * 64, 0, dpi, 0);
    if(error) {
        LOGE("Unable to set font size on %s", fullPath.string());
        return false;
    }

    mHasKerning = FT_HAS_KERNING(mFace);

    mInitialized = true;
    return true;
}

void Font::invalidateTextureCache()
{
    for(uint32_t i = 0; i < mCachedGlyphs.size(); i ++) {
        mCachedGlyphs.valueAt(i)->mIsValid = false;
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo *glyph, int32_t x, int32_t y)
{
    FontState *state = &mRSC->mStateFont;

    int32_t nPenX = x + glyph->mBitmapLeft;
    int32_t nPenY = y - glyph->mBitmapTop + glyph->mBitmapHeight;

    float u1 = glyph->mBitmapMinU;
    float u2 = glyph->mBitmapMaxU;
    float v1 = glyph->mBitmapMinV;
    float v2 = glyph->mBitmapMaxV;

    int32_t width = (int32_t) glyph->mBitmapWidth;
    int32_t height = (int32_t) glyph->mBitmapHeight;

    state->appendMeshQuad(nPenX, nPenY, 0, u1, v2,
                          nPenX + width, nPenY, 0, u2, v2,
                          nPenX + width, nPenY - height, 0, u2, v1,
                          nPenX, nPenY - height, 0, u1, v1);
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, int32_t x, int32_t y,
                           uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH) {
    int32_t nPenX = x + glyph->mBitmapLeft;
    int32_t nPenY = y + glyph->mBitmapTop;

    uint32_t endX = glyph->mBitmapMinX + glyph->mBitmapWidth;
    uint32_t endY = glyph->mBitmapMinY + glyph->mBitmapHeight;

    FontState *state = &mRSC->mStateFont;
    uint32_t cacheWidth = state->getCacheTextureType()->getDimX();
    const uint8_t* cacheBuffer = state->getTextTextureData();

    uint32_t cacheX = 0, cacheY = 0;
    int32_t bX = 0, bY = 0;
    for (cacheX = glyph->mBitmapMinX, bX = nPenX; cacheX < endX; cacheX++, bX++) {
        for (cacheY = glyph->mBitmapMinY, bY = nPenY; cacheY < endY; cacheY++, bY++) {
            if (bX < 0 || bY < 0 || bX >= (int32_t) bitmapW || bY >= (int32_t) bitmapH) {
                LOGE("Skipping invalid index");
                continue;
            }
            uint8_t tempCol = cacheBuffer[cacheY * cacheWidth + cacheX];
            bitmap[bY * bitmapW + bX] = tempCol;
        }
    }

}

void Font::measureCachedGlyph(CachedGlyphInfo *glyph, int32_t x, int32_t y, Rect *bounds) {
    int32_t nPenX = x + glyph->mBitmapLeft;
    int32_t nPenY = y - glyph->mBitmapTop + glyph->mBitmapHeight;

    int32_t width = (int32_t) glyph->mBitmapWidth;
    int32_t height = (int32_t) glyph->mBitmapHeight;

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

void Font::renderUTF(const char *text, uint32_t len, int32_t x, int32_t y,
                     uint32_t start, int32_t numGlyphs,
                     RenderMode mode, Rect *bounds,
                     uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH)
{
    if(!mInitialized || numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    if(mode == Font::MEASURE) {
        if (bounds == NULL) {
            LOGE("No return rectangle provided to measure text");
            return;
        }
        // Reset min and max of the bounding box to something large
        bounds->set(1e6, -1e6, -1e6, 1e6);
    }

    int32_t penX = x, penY = y;
    int32_t glyphsLeft = 1;
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

        CachedGlyphInfo *cachedGlyph = getCachedUTFChar(utfChar);

        // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
        if(cachedGlyph->mIsValid) {
            switch(mode) {
            case FRAMEBUFFER:
                drawCachedGlyph(cachedGlyph, penX, penY);
                break;
            case BITMAP:
                drawCachedGlyph(cachedGlyph, penX, penY, bitmap, bitmapW, bitmapH);
                break;
            case MEASURE:
                measureCachedGlyph(cachedGlyph, penX, penY, bounds);
                break;
            }
        }

        penX += (cachedGlyph->mAdvance.x >> 6);

        // If we were given a specific number of glyphs, decrement
        if(numGlyphs > 0) {
            glyphsLeft --;
        }
    }
}

Font::CachedGlyphInfo* Font::getCachedUTFChar(int32_t utfChar) {

    CachedGlyphInfo *cachedGlyph = mCachedGlyphs.valueFor((uint32_t)utfChar);
    if(cachedGlyph == NULL) {
        cachedGlyph = cacheGlyph((uint32_t)utfChar);
    }
    // Is the glyph still in texture cache?
    if(!cachedGlyph->mIsValid) {
        updateGlyphCache(cachedGlyph);
    }

    return cachedGlyph;
}

void Font::updateGlyphCache(CachedGlyphInfo *glyph)
{
    FT_Error error = FT_Load_Glyph( mFace, glyph->mGlyphIndex, FT_LOAD_RENDER );
    if(error) {
        LOGE("Couldn't load glyph.");
        return;
    }

    glyph->mAdvance = mFace->glyph->advance;
    glyph->mBitmapLeft = mFace->glyph->bitmap_left;
    glyph->mBitmapTop = mFace->glyph->bitmap_top;

    FT_Bitmap *bitmap = &mFace->glyph->bitmap;

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    // Let the font state figure out where to put the bitmap
    FontState *state = &mRSC->mStateFont;
    glyph->mIsValid = state->cacheBitmap(bitmap, &startX, &startY);

    if(!glyph->mIsValid) {
        return;
    }

    uint32_t endX = startX + bitmap->width;
    uint32_t endY = startY + bitmap->rows;

    glyph->mBitmapMinX = startX;
    glyph->mBitmapMinY = startY;
    glyph->mBitmapWidth = bitmap->width;
    glyph->mBitmapHeight = bitmap->rows;

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

    updateGlyphCache(newGlyph);

    return newGlyph;
}

Font * Font::create(Context *rsc, const char *name, uint32_t fontSize, uint32_t dpi)
{
    rsc->mStateFont.checkInit();
    Vector<Font*> &activeFonts = rsc->mStateFont.mActiveFonts;

    for(uint32_t i = 0; i < activeFonts.size(); i ++) {
        Font *ithFont = activeFonts[i];
        if(ithFont->mFontName == name && ithFont->mFontSize == fontSize && ithFont->mDpi == dpi) {
            return ithFont;
        }
    }

    Font *newFont = new Font(rsc);
    bool isInitialized = newFont->init(name, fontSize, dpi);
    if(isInitialized) {
        activeFonts.push(newFont);
        rsc->mStateFont.precacheLatin(newFont);
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
        delete glyph;
    }
}

FontState::FontState()
{
    mInitialized = false;
    mMaxNumberOfQuads = 1024;
    mCurrentQuadIndex = 0;
    mRSC = NULL;
    mLibrary = NULL;
    setFontColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Get the renderer properties
    char property[PROPERTY_VALUE_MAX];

    // Get the gamma
    float gamma = DEFAULT_TEXT_GAMMA;
    if (property_get(PROPERTY_TEXT_GAMMA, property, NULL) > 0) {
        LOGD("  Setting text gamma to %s", property);
        gamma = atof(property);
    } else {
        LOGD("  Using default text gamma of %.2f", DEFAULT_TEXT_GAMMA);
    }

    // Get the black gamma threshold
    int32_t blackThreshold = DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD;
    if (property_get(PROPERTY_TEXT_BLACK_GAMMA_THRESHOLD, property, NULL) > 0) {
        LOGD("  Setting text black gamma threshold to %s", property);
        blackThreshold = atoi(property);
    } else {
        LOGD("  Using default text black gamma threshold of %d",
                DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD);
    }
    mBlackThreshold = (float)(blackThreshold) / 255.0f;

    // Get the white gamma threshold
    int32_t whiteThreshold = DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD;
    if (property_get(PROPERTY_TEXT_WHITE_GAMMA_THRESHOLD, property, NULL) > 0) {
        LOGD("  Setting text white gamma threshold to %s", property);
        whiteThreshold = atoi(property);
    } else {
        LOGD("  Using default white black gamma threshold of %d",
                DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD);
    }
    mWhiteThreshold = (float)(whiteThreshold) / 255.0f;

    // Compute the gamma tables
    mBlackGamma = gamma;
    mWhiteGamma = 1.0f / gamma;
}

FontState::~FontState()
{
    for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
        delete mCacheLines[i];
    }

    rsAssert(!mActiveFonts.size());
}

FT_Library FontState::getLib()
{
    if(!mLibrary) {
        FT_Error error = FT_Init_FreeType(&mLibrary);
        if(error) {
            LOGE("Unable to initialize freetype");
            return NULL;
        }
    }

    return mLibrary;
}

void FontState::init(Context *rsc)
{
    mRSC = rsc;
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

    uint32_t cacheWidth = getCacheTextureType()->getDimX();

    uint8_t *cacheBuffer = (uint8_t*)mTextTexture->getPtr();
    uint8_t *bitmapBuffer = bitmap->buffer;

    uint32_t cacheX = 0, bX = 0, cacheY = 0, bY = 0;
    for(cacheX = startX, bX = 0; cacheX < endX; cacheX ++, bX ++) {
        for(cacheY = startY, bY = 0; cacheY < endY; cacheY ++, bY ++) {
            uint8_t tempCol = bitmapBuffer[bY * bitmap->width + bX];
            cacheBuffer[cacheY*cacheWidth + cacheX] = tempCol;
        }
    }

    // This will dirty the texture and the shader so next time
    // we draw it will upload the data
    mTextTexture->deferedUploadToTexture(mRSC, false, 0);
    mFontShaderF->bindTexture(mRSC, 0, mTextTexture.get());

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
    String8 shaderString("varying vec4 varTex0;\n");
    shaderString.append("void main() {\n");
    shaderString.append("  lowp vec4 col = UNI_Color;\n");
    shaderString.append("  col.a = texture2D(UNI_Tex0, varTex0.xy).a;\n");
    shaderString.append("  col.a = pow(col.a, UNI_Gamma);\n");
    shaderString.append("  gl_FragColor = col;\n");
    shaderString.append("}\n");

    const Element *colorElem = Element::create(mRSC, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 4);
    const Element *gammaElem = Element::create(mRSC, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 1);
    mRSC->mStateElement.elementBuilderBegin();
    mRSC->mStateElement.elementBuilderAdd(colorElem, "Color", 1);
    mRSC->mStateElement.elementBuilderAdd(gammaElem, "Gamma", 1);
    const Element *constInput = mRSC->mStateElement.elementBuilderCreate(mRSC);

    Type *inputType = new Type(mRSC);
    inputType->setElement(constInput);
    inputType->setDimX(1);
    inputType->compute();

    uint32_t tmp[4];
    tmp[0] = RS_PROGRAM_PARAM_CONSTANT;
    tmp[1] = (uint32_t)inputType;
    tmp[2] = RS_PROGRAM_PARAM_TEXTURE_COUNT;
    tmp[3] = 1;

    mFontShaderFConstant.set(new Allocation(mRSC, inputType));
    ProgramFragment *pf = new ProgramFragment(mRSC, shaderString.string(),
                                              shaderString.length(), tmp, 4);
    mFontShaderF.set(pf);
    mFontShaderF->bindAllocation(mRSC, mFontShaderFConstant.get(), 0);

    Sampler *sampler = new Sampler(mRSC, RS_SAMPLER_NEAREST, RS_SAMPLER_NEAREST,
                                      RS_SAMPLER_CLAMP, RS_SAMPLER_CLAMP, RS_SAMPLER_CLAMP);
    mFontSampler.set(sampler);
    mFontShaderF->bindSampler(mRSC, 0, sampler);

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
    int32_t nextLine = 0;
    mCacheLines.push(new CacheTextureLine(16, texType->getDimX(), nextLine, 0));
    nextLine += mCacheLines.top()->mMaxHeight;
    mCacheLines.push(new CacheTextureLine(24, texType->getDimX(), nextLine, 0));
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
        int32_t i6 = i * 6;
        int32_t i4 = i * 4;

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
    uint32_t arraySizes[2] = {1, 1};

    const Element *vertexDataElem = Element::create(mRSC, 2, elemArray, nameArray, lengths, arraySizes);

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

    // We store a string with letters in a rough frequency of occurrence
    mLatinPrecache = String8(" eisarntolcdugpmhbyfvkwzxjq");
    mLatinPrecache += String8("EISARNTOLCDUGPMHBYFVKWZXJQ");
    mLatinPrecache += String8(",.?!()-+@;:`'");
    mLatinPrecache += String8("0123456789");

    mInitialized = true;
}

void FontState::issueDrawCommand() {

    ObjectBaseRef<const ProgramVertex> tmpV(mRSC->getVertex());
    mRSC->setVertex(mRSC->getDefaultProgramVertex());

    ObjectBaseRef<const ProgramRaster> tmpR(mRSC->getRaster());
    mRSC->setRaster(mRSC->getDefaultProgramRaster());

    ObjectBaseRef<const ProgramFragment> tmpF(mRSC->getFragment());
    mRSC->setFragment(mFontShaderF.get());

    ObjectBaseRef<const ProgramStore> tmpPS(mRSC->getFragmentStore());
    mRSC->setFragmentStore(mFontProgramStore.get());

    if(mConstantsDirty) {
        mFontShaderFConstant->data(mRSC, &mConstants, sizeof(mConstants));
        mConstantsDirty = false;
    }

    if (!mRSC->setupCheck()) {
        mRSC->setVertex((ProgramVertex *)tmpV.get());
        mRSC->setRaster((ProgramRaster *)tmpR.get());
        mRSC->setFragment((ProgramFragment *)tmpF.get());
        mRSC->setFragmentStore((ProgramStore *)tmpPS.get());
        return;
    }

    float *vtx = (float*)mVertexArray->getPtr();
    float *tex = vtx + 3;

    VertexArray va;
    va.add(GL_FLOAT, 3, 20, false, (uint32_t)vtx, "ATTRIB_position");
    va.add(GL_FLOAT, 2, 20, false, (uint32_t)tex, "ATTRIB_texture0");
    va.setupGL2(mRSC, &mRSC->mStateVertexArray, &mRSC->mShaderCache);

    mIndexBuffer->uploadCheck(mRSC);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexBuffer->getBufferObjectID());
    glDrawElements(GL_TRIANGLES, mCurrentQuadIndex * 6, GL_UNSIGNED_SHORT, (uint16_t *)(0));

    // Reset the state
    mRSC->setVertex((ProgramVertex *)tmpV.get());
    mRSC->setRaster((ProgramRaster *)tmpR.get());
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

uint32_t FontState::getRemainingCacheCapacity() {
    uint32_t remainingCapacity = 0;
    uint32_t totalPixels = 0;
    for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
         remainingCapacity += (mCacheLines[i]->mMaxWidth - mCacheLines[i]->mCurrentCol);
         totalPixels += mCacheLines[i]->mMaxWidth;
    }
    remainingCapacity = (remainingCapacity * 100) / totalPixels;
    return remainingCapacity;
}

void FontState::precacheLatin(Font *font) {
    // Remaining capacity is measured in %
    uint32_t remainingCapacity = getRemainingCacheCapacity();
    uint32_t precacheIdx = 0;
    while(remainingCapacity > 25 && precacheIdx < mLatinPrecache.size()) {
        font->getCachedUTFChar((int32_t)mLatinPrecache[precacheIdx]);
        remainingCapacity = getRemainingCacheCapacity();
        precacheIdx ++;
    }
}


void FontState::renderText(const char *text, uint32_t len, int32_t x, int32_t y,
                           uint32_t startIndex, int32_t numGlyphs,
                           Font::RenderMode mode,
                           Font::Rect *bounds,
                           uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH)
{
    checkInit();

    // Render code here
    Font *currentFont = mRSC->getFont();
    if(!currentFont) {
        if(!mDefault.get()) {
            mDefault.set(Font::create(mRSC, "DroidSans.ttf", 16, 96));
        }
        currentFont = mDefault.get();
    }
    if(!currentFont) {
        LOGE("Unable to initialize any fonts");
        return;
    }

    currentFont->renderUTF(text, len, x, y, startIndex, numGlyphs,
                           mode, bounds, bitmap, bitmapW, bitmapH);

    if(mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

void FontState::measureText(const char *text, uint32_t len, Font::Rect *bounds) {
    renderText(text, len, 0, 0, 0, -1, Font::MEASURE, bounds);
}

void FontState::setFontColor(float r, float g, float b, float a) {
    mConstants.mFontColor[0] = r;
    mConstants.mFontColor[1] = g;
    mConstants.mFontColor[2] = b;
    mConstants.mFontColor[3] = a;

    mConstants.mGamma = 1.0f;
    const float luminance = (r * 2.0f + g * 5.0f + b) / 8.0f;
    if (luminance <= mBlackThreshold) {
        mConstants.mGamma = mBlackGamma;
    } else if (luminance >= mWhiteThreshold) {
        mConstants.mGamma = mWhiteGamma;
    }
    mConstantsDirty = true;
}

void FontState::getFontColor(float *r, float *g, float *b, float *a) const {
    *r = mConstants.mFontColor[0];
    *g = mConstants.mFontColor[1];
    *b = mConstants.mFontColor[2];
    *a = mConstants.mFontColor[3];
}

void FontState::deinit(Context *rsc)
{
    mInitialized = false;

    mFontShaderFConstant.clear();

    mIndexBuffer.clear();
    mVertexArray.clear();

    mFontShaderF.clear();
    mFontSampler.clear();
    mFontProgramStore.clear();

    mTextTexture.clear();
    for(uint32_t i = 0; i < mCacheLines.size(); i ++) {
        delete mCacheLines[i];
    }
    mCacheLines.clear();

    mDefault.clear();

    Vector<Font*> fontsToDereference = mActiveFonts;
    for(uint32_t i = 0; i < fontsToDereference.size(); i ++) {
        fontsToDereference[i]->zeroUserRef();
    }

    if(mLibrary) {
        FT_Done_FreeType( mLibrary );
        mLibrary = NULL;
    }
}

namespace android {
namespace renderscript {

RsFont rsi_FontCreateFromFile(Context *rsc, char const *name, uint32_t fontSize, uint32_t dpi)
{
    Font *newFont = Font::create(rsc, name, fontSize, dpi);
    if(newFont) {
        newFont->incUserRef();
    }
    return newFont;
}

} // renderscript
} // android
