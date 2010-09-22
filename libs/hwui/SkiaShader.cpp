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

#include "SkiaShader.h"
#include "Texture.h"
#include "Matrix.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Support
///////////////////////////////////////////////////////////////////////////////

static const GLenum gTextureUnitsMap[] = {
        GL_TEXTURE0,
        GL_TEXTURE1,
        GL_TEXTURE2
};

static const GLint gTileModes[] = {
        GL_CLAMP_TO_EDGE,   // == SkShader::kClamp_TileMode
        GL_REPEAT,          // == SkShader::kRepeat_Mode
        GL_MIRRORED_REPEAT  // == SkShader::kMirror_TileMode
};

///////////////////////////////////////////////////////////////////////////////
// Base shader
///////////////////////////////////////////////////////////////////////////////

SkiaShader::SkiaShader(Type type, SkShader* key, SkShader::TileMode tileX,
        SkShader::TileMode tileY, SkMatrix* matrix, bool blend):
        mType(type), mKey(key), mTileX(tileX), mTileY(tileY), mMatrix(matrix), mBlend(blend) {
}

SkiaShader::~SkiaShader() {
}

void SkiaShader::describe(ProgramDescription& description, const Extensions& extensions) {
}

void SkiaShader::setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
        GLuint* textureUnit) {
}

void SkiaShader::bindTexture(GLuint texture, GLenum wrapS, GLenum wrapT, GLuint textureUnit) {
    glActiveTexture(gTextureUnitsMap[textureUnit]);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapS);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapT);
}

///////////////////////////////////////////////////////////////////////////////
// Bitmap shader
///////////////////////////////////////////////////////////////////////////////

SkiaBitmapShader::SkiaBitmapShader(SkBitmap* bitmap, SkShader* key, SkShader::TileMode tileX,
        SkShader::TileMode tileY, SkMatrix* matrix, bool blend):
        SkiaShader(kBitmap, key, tileX, tileY, matrix, blend), mBitmap(bitmap), mTexture(NULL) {
}

void SkiaBitmapShader::describe(ProgramDescription& description, const Extensions& extensions) {
    const Texture* texture = mTextureCache->get(mBitmap);
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
    glActiveTexture(gTextureUnitsMap[textureSlot]);

    const Texture* texture = mTexture;
    mTexture = NULL;
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float width = texture->width;
    const float height = texture->height;

    mat4 textureTransform;
    if (mMatrix) {
        SkMatrix inverse;
        mMatrix->invert(&inverse);
        textureTransform.load(inverse);
        textureTransform.multiply(modelView);
    } else {
        textureTransform.load(modelView);
    }

    // Uniforms
    bindTexture(texture->id, mWrapS, mWrapT, textureSlot);
    glUniform1i(program->getUniform("bitmapSampler"), textureSlot);
    glUniformMatrix4fv(program->getUniform("textureTransform"), 1,
            GL_FALSE, &textureTransform.data[0]);
    glUniform2f(program->getUniform("textureDimension"), 1.0f / width, 1.0f / height);
}

void SkiaBitmapShader::updateTransforms(Program* program, const mat4& modelView,
        const Snapshot& snapshot) {
    mat4 textureTransform;
    if (mMatrix) {
        SkMatrix inverse;
        mMatrix->invert(&inverse);
        textureTransform.load(inverse);
        textureTransform.multiply(modelView);
    } else {
        textureTransform.load(modelView);
    }

    glUniformMatrix4fv(program->getUniform("textureTransform"), 1,
            GL_FALSE, &textureTransform.data[0]);
}

///////////////////////////////////////////////////////////////////////////////
// Linear gradient shader
///////////////////////////////////////////////////////////////////////////////

SkiaLinearGradientShader::SkiaLinearGradientShader(float* bounds, uint32_t* colors,
        float* positions, int count, SkShader* key, SkShader::TileMode tileMode,
        SkMatrix* matrix, bool blend):
        SkiaShader(kLinearGradient, key, tileMode, tileMode, matrix, blend),
        mBounds(bounds), mColors(colors), mPositions(positions), mCount(count) {
}

SkiaLinearGradientShader::~SkiaLinearGradientShader() {
    delete[] mBounds;
    delete[] mColors;
    delete[] mPositions;
}

void SkiaLinearGradientShader::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.hasGradient = true;
    description.gradientType = ProgramDescription::kGradientLinear;
}

void SkiaLinearGradientShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    GLuint textureSlot = (*textureUnit)++;
    glActiveTexture(gTextureUnitsMap[textureSlot]);

    Texture* texture = mGradientCache->get(mKey);
    if (!texture) {
        texture = mGradientCache->addLinearGradient(mKey, mColors, mPositions, mCount, mTileX);
    }

    Rect start(mBounds[0], mBounds[1], mBounds[2], mBounds[3]);
    if (mMatrix) {
        mat4 shaderMatrix(*mMatrix);
        shaderMatrix.mapPoint(start.left, start.top);
        shaderMatrix.mapPoint(start.right, start.bottom);
    }
    snapshot.transform->mapRect(start);

    const float gradientX = start.right - start.left;
    const float gradientY = start.bottom - start.top;

    mat4 screenSpace(*snapshot.transform);
    screenSpace.multiply(modelView);

    // Uniforms
    bindTexture(texture->id, gTileModes[mTileX], gTileModes[mTileY], textureSlot);
    glUniform1i(program->getUniform("gradientSampler"), textureSlot);
    glUniform2f(program->getUniform("gradientStart"), start.left, start.top);
    glUniform2f(program->getUniform("gradient"), gradientX, gradientY);
    glUniform1f(program->getUniform("gradientLength"),
            1.0f / (gradientX * gradientX + gradientY * gradientY));
    glUniformMatrix4fv(program->getUniform("screenSpace"), 1, GL_FALSE, &screenSpace.data[0]);
}

void SkiaLinearGradientShader::updateTransforms(Program* program, const mat4& modelView,
        const Snapshot& snapshot) {
    mat4 screenSpace(*snapshot.transform);
    screenSpace.multiply(modelView);
    glUniformMatrix4fv(program->getUniform("screenSpace"), 1, GL_FALSE, &screenSpace.data[0]);
}

///////////////////////////////////////////////////////////////////////////////
// Circular gradient shader
///////////////////////////////////////////////////////////////////////////////

SkiaCircularGradientShader::SkiaCircularGradientShader(float x, float y, float radius,
        uint32_t* colors, float* positions, int count, SkShader* key, SkShader::TileMode tileMode,
        SkMatrix* matrix, bool blend):
        SkiaSweepGradientShader(kCircularGradient, x, y, colors, positions, count, key,
                tileMode, matrix, blend),
        mRadius(radius) {
}

void SkiaCircularGradientShader::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.hasGradient = true;
    description.gradientType = ProgramDescription::kGradientCircular;
}

void SkiaCircularGradientShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    SkiaSweepGradientShader::setupProgram(program, modelView, snapshot, textureUnit);
    glUniform1f(program->getUniform("gradientRadius"), 1.0f / mRadius);
}

///////////////////////////////////////////////////////////////////////////////
// Sweep gradient shader
///////////////////////////////////////////////////////////////////////////////

SkiaSweepGradientShader::SkiaSweepGradientShader(float x, float y, uint32_t* colors,
        float* positions, int count, SkShader* key, SkMatrix* matrix, bool blend):
        SkiaShader(kSweepGradient, key, SkShader::kClamp_TileMode,
                SkShader::kClamp_TileMode, matrix, blend),
        mX(x), mY(y), mColors(colors), mPositions(positions), mCount(count) {
}

SkiaSweepGradientShader::SkiaSweepGradientShader(Type type, float x, float y, uint32_t* colors,
        float* positions, int count, SkShader* key, SkShader::TileMode tileMode,
        SkMatrix* matrix, bool blend):
        SkiaShader(type, key, tileMode, tileMode, matrix, blend),
        mX(x), mY(y), mColors(colors), mPositions(positions), mCount(count) {
}

SkiaSweepGradientShader::~SkiaSweepGradientShader() {
    delete[] mColors;
    delete[] mPositions;
}

void SkiaSweepGradientShader::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.hasGradient = true;
    description.gradientType = ProgramDescription::kGradientSweep;
}

void SkiaSweepGradientShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    GLuint textureSlot = (*textureUnit)++;
    glActiveTexture(gTextureUnitsMap[textureSlot]);

    Texture* texture = mGradientCache->get(mKey);
    if (!texture) {
        texture = mGradientCache->addLinearGradient(mKey, mColors, mPositions, mCount);
    }

    float left = mX;
    float top = mY;

    mat4 shaderMatrix;
    if (mMatrix) {
        shaderMatrix.load(*mMatrix);
        shaderMatrix.mapPoint(left, top);
    }

    mat4 copy(shaderMatrix);
    shaderMatrix.loadInverse(copy);

    snapshot.transform->mapPoint(left, top);

    mat4 screenSpace(*snapshot.transform);
    screenSpace.multiply(modelView);

    // Uniforms
    bindTexture(texture->id, gTileModes[mTileX], gTileModes[mTileY], textureSlot);
    glUniform1i(program->getUniform("gradientSampler"), textureSlot);
    glUniformMatrix4fv(program->getUniform("gradientMatrix"), 1, GL_FALSE, &shaderMatrix.data[0]);
    glUniform2f(program->getUniform("gradientStart"), left, top);
    glUniformMatrix4fv(program->getUniform("screenSpace"), 1, GL_FALSE, &screenSpace.data[0]);
}

void SkiaSweepGradientShader::updateTransforms(Program* program, const mat4& modelView,
        const Snapshot& snapshot) {
    mat4 screenSpace(*snapshot.transform);
    screenSpace.multiply(modelView);
    glUniformMatrix4fv(program->getUniform("screenSpace"), 1, GL_FALSE, &screenSpace.data[0]);
}

///////////////////////////////////////////////////////////////////////////////
// Compose shader
///////////////////////////////////////////////////////////////////////////////

SkiaComposeShader::SkiaComposeShader(SkiaShader* first, SkiaShader* second,
        SkXfermode::Mode mode, SkShader* key):
        SkiaShader(kCompose, key, SkShader::kClamp_TileMode, SkShader::kClamp_TileMode,
        NULL, first->blend() || second->blend()), mFirst(first), mSecond(second), mMode(mode) {
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
