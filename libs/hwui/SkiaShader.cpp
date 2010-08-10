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
    bindTexture(texture->id, gTileModes[mTileX], gTileModes[mTileY], textureSlot);
    glUniform1i(program->getUniform("bitmapSampler"), textureSlot);
    glUniformMatrix4fv(program->getUniform("textureTransform"), 1,
            GL_FALSE, &textureTransform.data[0]);
    glUniform2f(program->getUniform("textureDimension"), 1.0f / width, 1.0f / height);
}

///////////////////////////////////////////////////////////////////////////////
// Linear gradient shader
///////////////////////////////////////////////////////////////////////////////

SkiaLinearGradientShader::SkiaLinearGradientShader(float* bounds, uint32_t* colors,
        float* positions, int count, SkShader* key, SkShader::TileMode tileMode,
        SkMatrix* matrix, bool blend):
        SkiaShader(kLinearGradient, key, tileMode, tileMode, matrix, blend),
        mBounds(bounds), mColors(colors), mPositions(positions), mCount(count) {
    for (int i = 0; i < count; i++) {
        LOGD("[GL] Gradient color %d = 0x%x", i, colors[i]);
    }
}

SkiaLinearGradientShader::~SkiaLinearGradientShader() {
    delete[] mBounds;
    delete[] mColors;
    delete[] mPositions;
}

void SkiaLinearGradientShader::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.hasGradient = true;
}

void SkiaLinearGradientShader::setupProgram(Program* program, const mat4& modelView,
        const Snapshot& snapshot, GLuint* textureUnit) {
    GLuint textureSlot = (*textureUnit)++;
    glActiveTexture(gTextureUnitsMap[textureSlot]);

    Texture* texture = mGradientCache->get(mKey);
    if (!texture) {
        texture = mGradientCache->addLinearGradient(mKey, mBounds, mColors, mPositions,
                mCount, mTileX);
    }

    Rect start(mBounds[0], mBounds[1], mBounds[2], mBounds[3]);
    if (mMatrix) {
        mat4 shaderMatrix(*mMatrix);
        shaderMatrix.mapRect(start);
    }
    snapshot.transform.mapRect(start);

    const float gradientX = start.right - start.left;
    const float gradientY = start.bottom - start.top;

    mat4 screenSpace(snapshot.transform);
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
