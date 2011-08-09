
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

#include "rsContext.h"

#include "rsFont.h"
#include "rsProgramFragment.h"
#include <cutils/properties.h>

#ifndef ANDROID_RS_SERIALIZE
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_BITMAP_H
#endif //ANDROID_RS_SERIALIZE

using namespace android;
using namespace android::renderscript;

Font::Font(Context *rsc) : ObjectBase(rsc), mCachedGlyphs(NULL) {
    mInitialized = false;
    mHasKerning = false;
    mFace = NULL;
}

bool Font::init(const char *name, float fontSize, uint32_t dpi, const void *data, uint32_t dataLen) {
#ifndef ANDROID_RS_SERIALIZE
    if (mInitialized) {
        LOGE("Reinitialization of fonts not supported");
        return false;
    }

    FT_Error error = 0;
    if (data != NULL && dataLen > 0) {
        error = FT_New_Memory_Face(mRSC->mStateFont.getLib(), (const FT_Byte*)data, dataLen, 0, &mFace);
    } else {
        error = FT_New_Face(mRSC->mStateFont.getLib(), name, 0, &mFace);
    }

    if (error) {
        LOGE("Unable to initialize font %s", name);
        return false;
    }

    mFontName = name;
    mFontSize = fontSize;
    mDpi = dpi;

    error = FT_Set_Char_Size(mFace, (FT_F26Dot6)(fontSize * 64.0f), 0, dpi, 0);
    if (error) {
        LOGE("Unable to set font size on %s", name);
        return false;
    }

    mHasKerning = FT_HAS_KERNING(mFace);

    mInitialized = true;
#endif //ANDROID_RS_SERIALIZE
    return true;
}

void Font::preDestroy() const {
    for (uint32_t ct = 0; ct < mRSC->mStateFont.mActiveFonts.size(); ct++) {
        if (mRSC->mStateFont.mActiveFonts[ct] == this) {
            mRSC->mStateFont.mActiveFonts.removeAt(ct);
            break;
        }
    }
}

void Font::invalidateTextureCache() {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i ++) {
        mCachedGlyphs.valueAt(i)->mIsValid = false;
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo *glyph, int32_t x, int32_t y) {
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

    // 0, 0 is top left, so bottom is a positive number
    if (bounds->bottom < nPenY) {
        bounds->bottom = nPenY;
    }
    if (bounds->left > nPenX) {
        bounds->left = nPenX;
    }
    if (bounds->right < nPenX + width) {
        bounds->right = nPenX + width;
    }
    if (bounds->top > nPenY - height) {
        bounds->top = nPenY - height;
    }
}

void Font::renderUTF(const char *text, uint32_t len, int32_t x, int32_t y,
                     uint32_t start, int32_t numGlyphs,
                     RenderMode mode, Rect *bounds,
                     uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH) {
    if (!mInitialized || numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    if (mode == Font::MEASURE) {
        if (bounds == NULL) {
            LOGE("No return rectangle provided to measure text");
            return;
        }
        // Reset min and max of the bounding box to something large
        bounds->set(1e6, -1e6, 1e6, -1e6);
    }

    int32_t penX = x, penY = y;
    int32_t glyphsLeft = 1;
    if (numGlyphs > 0) {
        glyphsLeft = numGlyphs;
    }

    size_t index = start;
    size_t nextIndex = 0;

    while (glyphsLeft > 0) {

        int32_t utfChar = utf32_from_utf8_at(text, len, index, &nextIndex);

        // Reached the end of the string or encountered
        if (utfChar < 0) {
            break;
        }

        // Move to the next character in the array
        index = nextIndex;

        CachedGlyphInfo *cachedGlyph = getCachedUTFChar(utfChar);

        // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
        if (cachedGlyph->mIsValid) {
            switch (mode) {
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

        penX += (cachedGlyph->mAdvanceX >> 6);

        // If we were given a specific number of glyphs, decrement
        if (numGlyphs > 0) {
            glyphsLeft --;
        }
    }
}

Font::CachedGlyphInfo* Font::getCachedUTFChar(int32_t utfChar) {

    CachedGlyphInfo *cachedGlyph = mCachedGlyphs.valueFor((uint32_t)utfChar);
    if (cachedGlyph == NULL) {
        cachedGlyph = cacheGlyph((uint32_t)utfChar);
    }
    // Is the glyph still in texture cache?
    if (!cachedGlyph->mIsValid) {
        updateGlyphCache(cachedGlyph);
    }

    return cachedGlyph;
}

void Font::updateGlyphCache(CachedGlyphInfo *glyph) {
#ifndef ANDROID_RS_SERIALIZE
    FT_Error error = FT_Load_Glyph( mFace, glyph->mGlyphIndex, FT_LOAD_RENDER );
    if (error) {
        LOGE("Couldn't load glyph.");
        return;
    }

    glyph->mAdvanceX = mFace->glyph->advance.x;
    glyph->mAdvanceY = mFace->glyph->advance.y;
    glyph->mBitmapLeft = mFace->glyph->bitmap_left;
    glyph->mBitmapTop = mFace->glyph->bitmap_top;

    FT_Bitmap *bitmap = &mFace->glyph->bitmap;

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    // Let the font state figure out where to put the bitmap
    FontState *state = &mRSC->mStateFont;
    glyph->mIsValid = state->cacheBitmap(bitmap, &startX, &startY);

    if (!glyph->mIsValid) {
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
#endif //ANDROID_RS_SERIALIZE
}

Font::CachedGlyphInfo *Font::cacheGlyph(uint32_t glyph) {
    CachedGlyphInfo *newGlyph = new CachedGlyphInfo();
    mCachedGlyphs.add(glyph, newGlyph);
#ifndef ANDROID_RS_SERIALIZE
    newGlyph->mGlyphIndex = FT_Get_Char_Index(mFace, glyph);
    newGlyph->mIsValid = false;
#endif //ANDROID_RS_SERIALIZE
    updateGlyphCache(newGlyph);

    return newGlyph;
}

Font * Font::create(Context *rsc, const char *name, float fontSize, uint32_t dpi,
                    const void *data, uint32_t dataLen) {
    rsc->mStateFont.checkInit();
    Vector<Font*> &activeFonts = rsc->mStateFont.mActiveFonts;

    for (uint32_t i = 0; i < activeFonts.size(); i ++) {
        Font *ithFont = activeFonts[i];
        if (ithFont->mFontName == name && ithFont->mFontSize == fontSize && ithFont->mDpi == dpi) {
            return ithFont;
        }
    }

    Font *newFont = new Font(rsc);
    bool isInitialized = newFont->init(name, fontSize, dpi, data, dataLen);
    if (isInitialized) {
        activeFonts.push(newFont);
        rsc->mStateFont.precacheLatin(newFont);
        return newFont;
    }

    ObjectBase::checkDelete(newFont);
    return NULL;
}

Font::~Font() {
#ifndef ANDROID_RS_SERIALIZE
    if (mFace) {
        FT_Done_Face(mFace);
    }
#endif

    for (uint32_t i = 0; i < mCachedGlyphs.size(); i ++) {
        CachedGlyphInfo *glyph = mCachedGlyphs.valueAt(i);
        delete glyph;
    }
}

FontState::FontState() {
    mInitialized = false;
    mMaxNumberOfQuads = 1024;
    mCurrentQuadIndex = 0;
    mRSC = NULL;
#ifndef ANDROID_RS_SERIALIZE
    mLibrary = NULL;
#endif //ANDROID_RS_SERIALIZE

    // Get the renderer properties
    char property[PROPERTY_VALUE_MAX];

    // Get the gamma
    float gamma = DEFAULT_TEXT_GAMMA;
    if (property_get(PROPERTY_TEXT_GAMMA, property, NULL) > 0) {
        gamma = atof(property);
    }

    // Get the black gamma threshold
    int32_t blackThreshold = DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD;
    if (property_get(PROPERTY_TEXT_BLACK_GAMMA_THRESHOLD, property, NULL) > 0) {
        blackThreshold = atoi(property);
    }
    mBlackThreshold = (float)(blackThreshold) / 255.0f;

    // Get the white gamma threshold
    int32_t whiteThreshold = DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD;
    if (property_get(PROPERTY_TEXT_WHITE_GAMMA_THRESHOLD, property, NULL) > 0) {
        whiteThreshold = atoi(property);
    }
    mWhiteThreshold = (float)(whiteThreshold) / 255.0f;

    // Compute the gamma tables
    mBlackGamma = gamma;
    mWhiteGamma = 1.0f / gamma;

    setFontColor(0.1f, 0.1f, 0.1f, 1.0f);
}

FontState::~FontState() {
    for (uint32_t i = 0; i < mCacheLines.size(); i ++) {
        delete mCacheLines[i];
    }

    rsAssert(!mActiveFonts.size());
}
#ifndef ANDROID_RS_SERIALIZE
FT_Library FontState::getLib() {
    if (!mLibrary) {
        FT_Error error = FT_Init_FreeType(&mLibrary);
        if (error) {
            LOGE("Unable to initialize freetype");
            return NULL;
        }
    }

    return mLibrary;
}
#endif //ANDROID_RS_SERIALIZE


void FontState::init(Context *rsc) {
    mRSC = rsc;
}

void FontState::flushAllAndInvalidate() {
    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
    for (uint32_t i = 0; i < mActiveFonts.size(); i ++) {
        mActiveFonts[i]->invalidateTextureCache();
    }
    for (uint32_t i = 0; i < mCacheLines.size(); i ++) {
        mCacheLines[i]->mCurrentCol = 0;
    }
}

#ifndef ANDROID_RS_SERIALIZE
bool FontState::cacheBitmap(FT_Bitmap *bitmap, uint32_t *retOriginX, uint32_t *retOriginY) {
    // If the glyph is too tall, don't cache it
    if ((uint32_t)bitmap->rows > mCacheLines[mCacheLines.size()-1]->mMaxHeight) {
        LOGE("Font size to large to fit in cache. width, height = %i, %i", (int)bitmap->width, (int)bitmap->rows);
        return false;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    bool bitmapFit = false;
    for (uint32_t i = 0; i < mCacheLines.size(); i ++) {
        bitmapFit = mCacheLines[i]->fitBitmap(bitmap, &startX, &startY);
        if (bitmapFit) {
            break;
        }
    }

    // If the new glyph didn't fit, flush the state so far and invalidate everything
    if (!bitmapFit) {
        flushAllAndInvalidate();

        // Try to fit it again
        for (uint32_t i = 0; i < mCacheLines.size(); i ++) {
            bitmapFit = mCacheLines[i]->fitBitmap(bitmap, &startX, &startY);
            if (bitmapFit) {
                break;
            }
        }

        // if we still don't fit, something is wrong and we shouldn't draw
        if (!bitmapFit) {
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
    for (cacheX = startX, bX = 0; cacheX < endX; cacheX ++, bX ++) {
        for (cacheY = startY, bY = 0; cacheY < endY; cacheY ++, bY ++) {
            uint8_t tempCol = bitmapBuffer[bY * bitmap->width + bX];
            cacheBuffer[cacheY*cacheWidth + cacheX] = tempCol;
        }
    }

    // This will dirty the texture and the shader so next time
    // we draw it will upload the data

    mTextTexture->sendDirty(mRSC);
    mFontShaderF->bindTexture(mRSC, 0, mTextTexture.get());

    // Some debug code
    /*for (uint32_t i = 0; i < mCacheLines.size(); i ++) {
        LOGE("Cache Line: H: %u Empty Space: %f",
             mCacheLines[i]->mMaxHeight,
              (1.0f - (float)mCacheLines[i]->mCurrentCol/(float)mCacheLines[i]->mMaxWidth)*100.0f);

    }*/

    return true;
}
#endif //ANDROID_RS_SERIALIZE

void FontState::initRenderState() {
    String8 shaderString("varying vec2 varTex0;\n");
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

    Type *inputType = Type::getType(mRSC, constInput, 1, 0, 0, false, false);

    uint32_t tmp[4];
    tmp[0] = RS_PROGRAM_PARAM_CONSTANT;
    tmp[1] = (uint32_t)inputType;
    tmp[2] = RS_PROGRAM_PARAM_TEXTURE_TYPE;
    tmp[3] = RS_TEXTURE_2D;

    mFontShaderFConstant.set(Allocation::createAllocation(mRSC, inputType,
                                            RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS));
    ProgramFragment *pf = new ProgramFragment(mRSC, shaderString.string(),
                                              shaderString.length(), tmp, 4);
    mFontShaderF.set(pf);
    mFontShaderF->bindAllocation(mRSC, mFontShaderFConstant.get(), 0);

    Sampler *sampler = new Sampler(mRSC, RS_SAMPLER_NEAREST, RS_SAMPLER_NEAREST,
                                      RS_SAMPLER_CLAMP, RS_SAMPLER_CLAMP, RS_SAMPLER_CLAMP);
    mFontSampler.set(sampler);
    mFontShaderF->bindSampler(mRSC, 0, sampler);

    ProgramStore *fontStore = new ProgramStore(mRSC, true, true, true, true,
                                               false, false,
                                               RS_BLEND_SRC_SRC_ALPHA,
                                               RS_BLEND_DST_ONE_MINUS_SRC_ALPHA,
                                               RS_DEPTH_FUNC_ALWAYS);
    mFontProgramStore.set(fontStore);
    mFontProgramStore->init();
}

void FontState::initTextTexture() {
    const Element *alphaElem = Element::create(mRSC, RS_TYPE_UNSIGNED_8, RS_KIND_PIXEL_A, true, 1);

    // We will allocate a texture to initially hold 32 character bitmaps
    Type *texType = Type::getType(mRSC, alphaElem, 1024, 256, 0, false, false);

    Allocation *cacheAlloc = Allocation::createAllocation(mRSC, texType,
                                RS_ALLOCATION_USAGE_SCRIPT | RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE);
    mTextTexture.set(cacheAlloc);
    mTextTexture->syncAll(mRSC, RS_ALLOCATION_USAGE_SCRIPT);

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
void FontState::initVertexArrayBuffers() {
    // Now lets write index data
    const Element *indexElem = Element::create(mRSC, RS_TYPE_UNSIGNED_16, RS_KIND_USER, false, 1);
    uint32_t numIndicies = mMaxNumberOfQuads * 6;
    Type *indexType = Type::getType(mRSC, indexElem, numIndicies, 0, 0, false, false);

    Allocation *indexAlloc = Allocation::createAllocation(mRSC, indexType,
                                                          RS_ALLOCATION_USAGE_SCRIPT |
                                                          RS_ALLOCATION_USAGE_GRAPHICS_VERTEX);
    uint16_t *indexPtr = (uint16_t*)indexAlloc->getPtr();

    // Four verts, two triangles , six indices per quad
    for (uint32_t i = 0; i < mMaxNumberOfQuads; i ++) {
        int32_t i6 = i * 6;
        int32_t i4 = i * 4;

        indexPtr[i6 + 0] = i4 + 0;
        indexPtr[i6 + 1] = i4 + 1;
        indexPtr[i6 + 2] = i4 + 2;

        indexPtr[i6 + 3] = i4 + 0;
        indexPtr[i6 + 4] = i4 + 2;
        indexPtr[i6 + 5] = i4 + 3;
    }

    indexAlloc->sendDirty(mRSC);

    const Element *posElem = Element::create(mRSC, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 3);
    const Element *texElem = Element::create(mRSC, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 2);

    mRSC->mStateElement.elementBuilderBegin();
    mRSC->mStateElement.elementBuilderAdd(posElem, "position", 1);
    mRSC->mStateElement.elementBuilderAdd(texElem, "texture0", 1);
    const Element *vertexDataElem = mRSC->mStateElement.elementBuilderCreate(mRSC);

    Type *vertexDataType = Type::getType(mRSC, vertexDataElem,
                                         mMaxNumberOfQuads * 4,
                                         0, 0, false, false);

    Allocation *vertexAlloc = Allocation::createAllocation(mRSC, vertexDataType,
                                                           RS_ALLOCATION_USAGE_SCRIPT);
    mTextMeshPtr = (float*)vertexAlloc->getPtr();

    mMesh.set(new Mesh(mRSC, 1, 1));
    mMesh->setVertexBuffer(vertexAlloc, 0);
    mMesh->setPrimitive(indexAlloc, RS_PRIMITIVE_TRIANGLE, 0);
    mMesh->init();
}

// We don't want to allocate anything unless we actually draw text
void FontState::checkInit() {
    if (mInitialized) {
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
    Context::PushState ps(mRSC);

    mRSC->setProgramVertex(mRSC->getDefaultProgramVertex());
    mRSC->setProgramRaster(mRSC->getDefaultProgramRaster());
    mRSC->setProgramFragment(mFontShaderF.get());
    mRSC->setProgramStore(mFontProgramStore.get());

    if (mConstantsDirty) {
        mFontShaderFConstant->data(mRSC, 0, 0, 1, &mConstants, sizeof(mConstants));
        mConstantsDirty = false;
    }

    if (!mRSC->setupCheck()) {
        return;
    }

    mMesh->renderPrimitiveRange(mRSC, 0, 0, mCurrentQuadIndex * 6);
}

void FontState::appendMeshQuad(float x1, float y1, float z1,
                               float u1, float v1,
                               float x2, float y2, float z2,
                               float u2, float v2,
                               float x3, float y3, float z3,
                               float u3, float v3,
                               float x4, float y4, float z4,
                               float u4, float v4) {
    const uint32_t vertsPerQuad = 4;
    const uint32_t floatsPerVert = 5;
    float *currentPos = mTextMeshPtr + mCurrentQuadIndex * vertsPerQuad * floatsPerVert;

    // Cull things that are off the screen
    float width = (float)mRSC->getWidth();
    float height = (float)mRSC->getHeight();

    if (x1 > width || y1 < 0.0f || x2 < 0 || y4 > height) {
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

    if (mCurrentQuadIndex == mMaxNumberOfQuads) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

uint32_t FontState::getRemainingCacheCapacity() {
    uint32_t remainingCapacity = 0;
    uint32_t totalPixels = 0;
    for (uint32_t i = 0; i < mCacheLines.size(); i ++) {
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
    while (remainingCapacity > 25 && precacheIdx < mLatinPrecache.size()) {
        font->getCachedUTFChar((int32_t)mLatinPrecache[precacheIdx]);
        remainingCapacity = getRemainingCacheCapacity();
        precacheIdx ++;
    }
}


void FontState::renderText(const char *text, uint32_t len, int32_t x, int32_t y,
                           uint32_t startIndex, int32_t numGlyphs,
                           Font::RenderMode mode,
                           Font::Rect *bounds,
                           uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH) {
    checkInit();

    // Render code here
    Font *currentFont = mRSC->getFont();
    if (!currentFont) {
        if (!mDefault.get()) {
            String8 fontsDir("/fonts/Roboto-Regular.ttf");
            String8 fullPath(getenv("ANDROID_ROOT"));
            fullPath += fontsDir;

            mDefault.set(Font::create(mRSC, fullPath.string(), 8, mRSC->getDPI()));
        }
        currentFont = mDefault.get();
    }
    if (!currentFont) {
        LOGE("Unable to initialize any fonts");
        return;
    }

    currentFont->renderUTF(text, len, x, y, startIndex, numGlyphs,
                           mode, bounds, bitmap, bitmapW, bitmapH);

    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

void FontState::measureText(const char *text, uint32_t len, Font::Rect *bounds) {
    renderText(text, len, 0, 0, 0, -1, Font::MEASURE, bounds);
    bounds->bottom = - bounds->bottom;
    bounds->top = - bounds->top;
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

void FontState::deinit(Context *rsc) {
    mInitialized = false;

    mFontShaderFConstant.clear();

    mMesh.clear();

    mFontShaderF.clear();
    mFontSampler.clear();
    mFontProgramStore.clear();

    mTextTexture.clear();
    for (uint32_t i = 0; i < mCacheLines.size(); i ++) {
        delete mCacheLines[i];
    }
    mCacheLines.clear();

    mDefault.clear();
#ifndef ANDROID_RS_SERIALIZE
    if (mLibrary) {
        FT_Done_FreeType( mLibrary );
        mLibrary = NULL;
    }
#endif //ANDROID_RS_SERIALIZE
}

#ifndef ANDROID_RS_SERIALIZE
bool FontState::CacheTextureLine::fitBitmap(FT_Bitmap_ *bitmap, uint32_t *retOriginX, uint32_t *retOriginY) {
    if ((uint32_t)bitmap->rows > mMaxHeight) {
        return false;
    }

    if (mCurrentCol + (uint32_t)bitmap->width < mMaxWidth) {
        *retOriginX = mCurrentCol;
        *retOriginY = mCurrentRow;
        mCurrentCol += bitmap->width;
        mDirty = true;
       return true;
    }

    return false;
}
#endif //ANDROID_RS_SERIALIZE

namespace android {
namespace renderscript {

RsFont rsi_FontCreateFromFile(Context *rsc,
                              char const *name, size_t name_length,
                              float fontSize, uint32_t dpi) {
    Font *newFont = Font::create(rsc, name, fontSize, dpi);
    if (newFont) {
        newFont->incUserRef();
    }
    return newFont;
}

RsFont rsi_FontCreateFromMemory(Context *rsc,
                                char const *name, size_t name_length,
                                float fontSize, uint32_t dpi,
                                const void *data, size_t data_length) {
    Font *newFont = Font::create(rsc, name, fontSize, dpi, data, data_length);
    if (newFont) {
        newFont->incUserRef();
    }
    return newFont;
}

} // renderscript
} // android
