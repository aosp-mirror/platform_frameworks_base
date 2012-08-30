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

#include <utils/Log.h>

#include <SkMatrix.h>

#include "Caches.h"
#include "SkiaShader.h"
#include "Texture.h"
#include "Matrix.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Support
///////////////////////////////////////////////////////////////////////////////

static const GLint gTileModes[] = {
        GL_CLAMP_TO_EDGE,   // == SkShader::kClamp_TileMode
        GL_REPEAT,          // == SkShader::kRepeat_Mode
        GL_MIRRORED_REPEAT  // == SkShader::kMirror_TileMode
};

/**
 * This function does not work for n == 0.
 */
static inline bool isPowerOfTwo(unsigned int n) {
    return !(n & (n - 1));
}

static inline void bindUniformColor(int slot, uint32_t color) {
    const float a = ((color >> 24) & 0xff) / 255.0f;
    glUniform4f(slot,
            a * ((color >> 16) & 0xff) / 255.0f,
            a * ((color >>  8) & 0xff) / 255.0f,
            a * ((color      ) & 0xff) / 255.0f,
            a);
}

///////////////////////////////////////////////////////////////////////////////
// Base shader
///////////////////////////////////////////////////////////////////////////////

void SkiaShader::copyFrom(const SkiaShader& shader) {
    mType = shader.mType;
    mKey = shader.mKey;
    mTileX = shader.mTileX;
    mTileY = shader.mTileY;
    mBlend = shader.mBlend;
    mUnitMatrix = shader.mUnitMatrix;
    mShaderMatrix = shader.mShaderMatrix;
    mGenerationId = shader.mGenerationId;
}

SkiaShader::SkiaShader(Type type, SkShader* key, SkShader::TileMode tileX,
        SkShader::TileMode tileY, SkMatrix* matrix, bool blend):
        mType(type), mKey(key), mTileX(tileX), mTileY(tileY), mBlend(blend) {
    setMatrix(matrix);
    mGenerationId = 0;
}

SkiaShader::~SkiaShader() {
}

void SkiaShader::describe(ProgramDescription& description, const Extensions& extensions) {
}

void SkiaShader::setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
        GLuint* textureUnit) {
}

void SkiaShader::bindTexture(Texture* texture, GLenum wrapS, GLenum wrapT) {
    glBindTexture(GL_TEXTURE_2D, texture->id);
    texture->setWrapST(wrapS, wrapT);
}

void SkiaShader::computeScreenSpaceMatrix(mat4& screenSpace, const mat4& modelView) {
    screenSpace.loadMultiply(mUnitMatrix, mShaderMatrix);
    screenSpace.multiply(modelView);
}

///////////////////////////////////////////////////////////////////////////////
// Bitmap shader
///////////////////////////////////////////////////////////////////////////////

SkiaBitmapShader::SkiaBitmapShader(SkBitmap* bitmap, SkShader* key, SkShader::TileMode tileX,
        SkShader::TileMode tileY, SkMatrix* matrix, bool blend):
        SkiaShader(kBitmap, key, tileX, tileY, matrix, blend), mBitmap(bitmap), mTexture(NULL) {
    updateLocalMatrix(matrix);
}

SkiaShader* SkiaBitmapShader::copy() {
    SkiaBitmapShader* copy = new SkiaBitmapShader();
    copy->copyFrom(*this);
    copy->mBitmap = mBitmap;
    return copy;
}

void SkiaBitmapShader::describe(ProgramDescription& description, const Extensions& extensions) {
    Texture* texture = mTextureCache->get(mBitmap);
    if (!texture) return;
    mTexture = texture;

    const float width = texture->width;
    const float height = texture->height;

    description.hasBitmap = true;
    // The driver does not support non-power of two mirrored/repeated
    // textures, so do it ourselves
    if (!extensions.hasNPot() && (!isPowerOfTwo(width) || !isPowerOfTwo(height)) &&
            (mTileX != SkShader::kClamp_TileMode || mTileY != SkShader::kClamp_TileMode)) {
        description.isBitmapNpot = true;
        description.bitmapWrapS = gTileModes[mTileX];
        description.bitmapWrapT = gTileModes[mTileY];
        mWrapS = GL_CLAMP_TO_EDGE;
        mWrapT = GL_CLAMP_TO_EDGE;
    } else {
        mWrapS = gTileModes[mTileX];
        mWrapT = gTileModes[mTileY];
    }
}

void SkiaBitmapShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    GLuint textureSlot = (*textureUnit)++;
    Caches::getInstance().activeTexture(textureSlot);

    Texture* texture = mTexture;
    mTexture = NULL;
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float width = texture->width;
    const float height = texture->height;

    mat4 textureTransform;
    computeScreenSpaceMatrix(textureTransform, modelView);

    // Uniforms
    bindTexture(texture, mWrapS, mWrapT);
    texture->setFilter(GL_LINEAR);

    glUniform1i(program->getUniform("bitmapSampler"), textureSlot);
    glUniformMatrix4fv(program->getUniform("textureTransform"), 1,
            GL_FALSE, &textureTransform.data[0]);
    glUniform2f(program->getUniform("textureDimension"), 1.0f / width, 1.0f / height);
}

///////////////////////////////////////////////////////////////////////////////
// Linear gradient shader
///////////////////////////////////////////////////////////////////////////////

static void toUnitMatrix(const SkPoint pts[2], SkMatrix* matrix) {
    SkVector vec = pts[1] - pts[0];
    const float mag = vec.length();
    const float inv = mag ? 1.0f / mag : 0;

    vec.scale(inv);
    matrix->setSinCos(-vec.fY, vec.fX, pts[0].fX, pts[0].fY);
    matrix->postTranslate(-pts[0].fX, -pts[0].fY);
    matrix->postScale(inv, inv);
}

SkiaLinearGradientShader::SkiaLinearGradientShader(float* bounds, uint32_t* colors,
        float* positions, int count, SkShader* key, SkShader::TileMode tileMode,
        SkMatrix* matrix, bool blend):
        SkiaShader(kLinearGradient, key, tileMode, tileMode, matrix, blend),
        mBounds(bounds), mColors(colors), mPositions(positions), mCount(count) {
    SkPoint points[2];
    points[0].set(bounds[0], bounds[1]);
    points[1].set(bounds[2], bounds[3]);

    SkMatrix unitMatrix;
    toUnitMatrix(points, &unitMatrix);
    mUnitMatrix.load(unitMatrix);

    updateLocalMatrix(matrix);

    mIsSimple = count == 2 && tileMode == SkShader::kClamp_TileMode;
}

SkiaLinearGradientShader::~SkiaLinearGradientShader() {
    delete[] mBounds;
    delete[] mColors;
    delete[] mPositions;
}

SkiaShader* SkiaLinearGradientShader::copy() {
    SkiaLinearGradientShader* copy = new SkiaLinearGradientShader();
    copy->copyFrom(*this);
    copy->mBounds = new float[4];
    memcpy(copy->mBounds, mBounds, sizeof(float) * 4);
    copy->mColors = new uint32_t[mCount];
    memcpy(copy->mColors, mColors, sizeof(uint32_t) * mCount);
    copy->mPositions = new float[mCount];
    memcpy(copy->mPositions, mPositions, sizeof(float) * mCount);
    copy->mCount = mCount;
    copy->mIsSimple = mIsSimple;
    return copy;
}

void SkiaLinearGradientShader::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.hasGradient = true;
    description.gradientType = ProgramDescription::kGradientLinear;
    description.isSimpleGradient = mIsSimple;
}

void SkiaLinearGradientShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    if (CC_UNLIKELY(!mIsSimple)) {
        GLuint textureSlot = (*textureUnit)++;
        Caches::getInstance().activeTexture(textureSlot);

        Texture* texture = mGradientCache->get(mColors, mPositions, mCount);

        // Uniforms
        bindTexture(texture, gTileModes[mTileX], gTileModes[mTileY]);
        glUniform1i(program->getUniform("gradientSampler"), textureSlot);
    } else {
        bindUniformColor(program->getUniform("startColor"), mColors[0]);
        bindUniformColor(program->getUniform("endColor"), mColors[1]);
    }

    Caches::getInstance().dither.setupProgram(program, textureUnit);

    mat4 screenSpace;
    computeScreenSpaceMatrix(screenSpace, modelView);
    glUniformMatrix4fv(program->getUniform("screenSpace"), 1, GL_FALSE, &screenSpace.data[0]);
}

///////////////////////////////////////////////////////////////////////////////
// Circular gradient shader
///////////////////////////////////////////////////////////////////////////////

static void toCircularUnitMatrix(const float x, const float y, const float radius,
        SkMatrix* matrix) {
    const float inv = 1.0f / radius;
    matrix->setTranslate(-x, -y);
    matrix->postScale(inv, inv);
}

SkiaCircularGradientShader::SkiaCircularGradientShader(float x, float y, float radius,
        uint32_t* colors, float* positions, int count, SkShader* key, SkShader::TileMode tileMode,
        SkMatrix* matrix, bool blend):
        SkiaSweepGradientShader(kCircularGradient, x, y, colors, positions, count, key,
                tileMode, matrix, blend) {
    SkMatrix unitMatrix;
    toCircularUnitMatrix(x, y, radius, &unitMatrix);
    mUnitMatrix.load(unitMatrix);

    updateLocalMatrix(matrix);
}

SkiaShader* SkiaCircularGradientShader::copy() {
    SkiaCircularGradientShader* copy = new SkiaCircularGradientShader();
    copy->copyFrom(*this);
    copy->mColors = new uint32_t[mCount];
    memcpy(copy->mColors, mColors, sizeof(uint32_t) * mCount);
    copy->mPositions = new float[mCount];
    memcpy(copy->mPositions, mPositions, sizeof(float) * mCount);
    copy->mCount = mCount;
    copy->mIsSimple = mIsSimple;
    return copy;
}

void SkiaCircularGradientShader::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.hasGradient = true;
    description.gradientType = ProgramDescription::kGradientCircular;
    description.isSimpleGradient = mIsSimple;
}

///////////////////////////////////////////////////////////////////////////////
// Sweep gradient shader
///////////////////////////////////////////////////////////////////////////////

static void toSweepUnitMatrix(const float x, const float y, SkMatrix* matrix) {
    matrix->setTranslate(-x, -y);
}

SkiaSweepGradientShader::SkiaSweepGradientShader(float x, float y, uint32_t* colors,
        float* positions, int count, SkShader* key, SkMatrix* matrix, bool blend):
        SkiaShader(kSweepGradient, key, SkShader::kClamp_TileMode,
                SkShader::kClamp_TileMode, matrix, blend),
        mColors(colors), mPositions(positions), mCount(count) {
    SkMatrix unitMatrix;
    toSweepUnitMatrix(x, y, &unitMatrix);
    mUnitMatrix.load(unitMatrix);

    updateLocalMatrix(matrix);

    mIsSimple = count == 2;
}

SkiaSweepGradientShader::SkiaSweepGradientShader(Type type, float x, float y, uint32_t* colors,
        float* positions, int count, SkShader* key, SkShader::TileMode tileMode,
        SkMatrix* matrix, bool blend):
        SkiaShader(type, key, tileMode, tileMode, matrix, blend),
        mColors(colors), mPositions(positions), mCount(count) {

    mIsSimple = count == 2 && tileMode == SkShader::kClamp_TileMode;
}

SkiaSweepGradientShader::~SkiaSweepGradientShader() {
    delete[] mColors;
    delete[] mPositions;
}

SkiaShader* SkiaSweepGradientShader::copy() {
    SkiaSweepGradientShader* copy = new SkiaSweepGradientShader();
    copy->copyFrom(*this);
    copy->mColors = new uint32_t[mCount];
    memcpy(copy->mColors, mColors, sizeof(uint32_t) * mCount);
    copy->mPositions = new float[mCount];
    memcpy(copy->mPositions, mPositions, sizeof(float) * mCount);
    copy->mCount = mCount;
    copy->mIsSimple = mIsSimple;
    return copy;
}

void SkiaSweepGradientShader::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.hasGradient = true;
    description.gradientType = ProgramDescription::kGradientSweep;
    description.isSimpleGradient = mIsSimple;
}

void SkiaSweepGradientShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    if (CC_UNLIKELY(!mIsSimple)) {
        GLuint textureSlot = (*textureUnit)++;
        Caches::getInstance().activeTexture(textureSlot);

        Texture* texture = mGradientCache->get(mColors, mPositions, mCount);

        // Uniforms
        bindTexture(texture, gTileModes[mTileX], gTileModes[mTileY]);
        glUniform1i(program->getUniform("gradientSampler"), textureSlot);
    } else {
       bindUniformColor(program->getUniform("startColor"), mColors[0]);
       bindUniformColor(program->getUniform("endColor"), mColors[1]);
    }

    Caches::getInstance().dither.setupProgram(program, textureUnit);

    mat4 screenSpace;
    computeScreenSpaceMatrix(screenSpace, modelView);
    glUniformMatrix4fv(program->getUniform("screenSpace"), 1, GL_FALSE, &screenSpace.data[0]);
}

///////////////////////////////////////////////////////////////////////////////
// Compose shader
///////////////////////////////////////////////////////////////////////////////

SkiaComposeShader::SkiaComposeShader(SkiaShader* first, SkiaShader* second,
        SkXfermode::Mode mode, SkShader* key):
        SkiaShader(kCompose, key, SkShader::kClamp_TileMode, SkShader::kClamp_TileMode,
        NULL, first->blend() || second->blend()),
        mFirst(first), mSecond(second), mMode(mode), mCleanup(false) {
}

SkiaComposeShader::~SkiaComposeShader() {
    if (mCleanup) {
        delete mFirst;
        delete mSecond;
    }
}

SkiaShader* SkiaComposeShader::copy() {
    SkiaComposeShader* copy = new SkiaComposeShader();
    copy->copyFrom(*this);
    copy->mFirst = mFirst->copy();
    copy->mSecond = mSecond->copy();
    copy->mMode = mMode;
    copy->cleanup();
    return copy;
}

void SkiaComposeShader::set(TextureCache* textureCache, GradientCache* gradientCache) {
    SkiaShader::set(textureCache, gradientCache);
    mFirst->set(textureCache, gradientCache);
    mSecond->set(textureCache, gradientCache);
}

void SkiaComposeShader::describe(ProgramDescription& description, const Extensions& extensions) {
    mFirst->describe(description, extensions);
    mSecond->describe(description, extensions);
    if (mFirst->type() == kBitmap) {
        description.isBitmapFirst = true;
    }
    description.shadersMode = mMode;
}

void SkiaComposeShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    mFirst->setupProgram(program, modelView, snapshot, textureUnit);
    mSecond->setupProgram(program, modelView, snapshot, textureUnit);
}

}; // namespace uirenderer
}; // namespace android
