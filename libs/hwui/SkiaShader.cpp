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

#include "SkiaShader.h"

#include "Caches.h"
#include "Extensions.h"
#include "Matrix.h"
#include "Texture.h"
#include "hwui/Bitmap.h"

#include <SkMatrix.h>
#include <utils/Log.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Support
///////////////////////////////////////////////////////////////////////////////

static constexpr GLenum gTileModes[] = {
        GL_CLAMP_TO_EDGE,   // == SkShader::kClamp_TileMode
        GL_REPEAT,          // == SkShader::kRepeat_Mode
        GL_MIRRORED_REPEAT  // == SkShader::kMirror_TileMode
};

static_assert(gTileModes[SkShader::kClamp_TileMode] == GL_CLAMP_TO_EDGE,
              "SkShader TileModes have changed");
static_assert(gTileModes[SkShader::kRepeat_TileMode] == GL_REPEAT,
              "SkShader TileModes have changed");
static_assert(gTileModes[SkShader::kMirror_TileMode] == GL_MIRRORED_REPEAT,
              "SkShader TileModes have changed");

/**
 * This function does not work for n == 0.
 */
static inline bool isPowerOfTwo(unsigned int n) {
    return !(n & (n - 1));
}

static inline void bindUniformColor(int slot, FloatColor color) {
    glUniform4fv(slot, 1, reinterpret_cast<const float*>(&color));
}

static inline void bindTexture(Caches* caches, Texture* texture, GLenum wrapS, GLenum wrapT) {
    caches->textureState().bindTexture(texture->target(), texture->id());
    texture->setWrapST(wrapS, wrapT);
}

/**
 * Compute the matrix to transform to screen space.
 * @param screenSpace Output param for the computed matrix.
 * @param unitMatrix The unit matrix for gradient shaders, as returned by SkShader::asAGradient,
 *      or identity.
 * @param localMatrix Local matrix, as returned by SkShader::getLocalMatrix().
 * @param modelViewMatrix Model view matrix, as supplied by the OpenGLRenderer.
 */
static void computeScreenSpaceMatrix(mat4& screenSpace, const SkMatrix& unitMatrix,
                                     const SkMatrix& localMatrix, const mat4& modelViewMatrix) {
    mat4 shaderMatrix;
    // uses implicit construction
    shaderMatrix.loadInverse(localMatrix);
    // again, uses implicit construction
    screenSpace.loadMultiply(unitMatrix, shaderMatrix);
    screenSpace.multiply(modelViewMatrix);
}

///////////////////////////////////////////////////////////////////////////////
// Gradient shader matrix helpers
///////////////////////////////////////////////////////////////////////////////

static void toLinearUnitMatrix(const SkPoint pts[2], SkMatrix* matrix) {
    SkVector vec = pts[1] - pts[0];
    const float mag = vec.length();
    const float inv = mag ? 1.0f / mag : 0;

    vec.scale(inv);
    matrix->setSinCos(-vec.fY, vec.fX, pts[0].fX, pts[0].fY);
    matrix->postTranslate(-pts[0].fX, -pts[0].fY);
    matrix->postScale(inv, inv);
}

static void toCircularUnitMatrix(const float x, const float y, const float radius,
                                 SkMatrix* matrix) {
    const float inv = 1.0f / radius;
    matrix->setTranslate(-x, -y);
    matrix->postScale(inv, inv);
}

static void toSweepUnitMatrix(const float x, const float y, SkMatrix* matrix) {
    matrix->setTranslate(-x, -y);
}

///////////////////////////////////////////////////////////////////////////////
// Common gradient code
///////////////////////////////////////////////////////////////////////////////

static bool isSimpleGradient(const SkShader::GradientInfo& gradInfo) {
    return gradInfo.fColorCount == 2 && gradInfo.fTileMode == SkShader::kClamp_TileMode;
}

///////////////////////////////////////////////////////////////////////////////
// Store / apply
///////////////////////////////////////////////////////////////////////////////

bool tryStoreGradient(Caches& caches, const SkShader& shader, const Matrix4 modelViewMatrix,
                      GLuint* textureUnit, ProgramDescription* description,
                      SkiaShaderData::GradientShaderData* outData) {
    SkShader::GradientInfo gradInfo;
    gradInfo.fColorCount = 0;
    gradInfo.fColors = nullptr;
    gradInfo.fColorOffsets = nullptr;

    SkMatrix unitMatrix;
    switch (shader.asAGradient(&gradInfo)) {
        case SkShader::kLinear_GradientType:
            description->gradientType = ProgramDescription::kGradientLinear;

            toLinearUnitMatrix(gradInfo.fPoint, &unitMatrix);
            break;
        case SkShader::kRadial_GradientType:
            description->gradientType = ProgramDescription::kGradientCircular;

            toCircularUnitMatrix(gradInfo.fPoint[0].fX, gradInfo.fPoint[0].fY, gradInfo.fRadius[0],
                                 &unitMatrix);
            break;
        case SkShader::kSweep_GradientType:
            description->gradientType = ProgramDescription::kGradientSweep;

            toSweepUnitMatrix(gradInfo.fPoint[0].fX, gradInfo.fPoint[0].fY, &unitMatrix);
            break;
        default:
            // Do nothing. This shader is unsupported.
            return false;
    }
    description->hasGradient = true;
    description->isSimpleGradient = isSimpleGradient(gradInfo);

    computeScreenSpaceMatrix(outData->screenSpace, unitMatrix, shader.getLocalMatrix(),
                             modelViewMatrix);

    // re-query shader to get full color / offset data
    std::unique_ptr<SkColor[]> colorStorage(new SkColor[gradInfo.fColorCount]);
    std::unique_ptr<SkScalar[]> colorOffsets(new SkScalar[gradInfo.fColorCount]);
    gradInfo.fColors = &colorStorage[0];
    gradInfo.fColorOffsets = &colorOffsets[0];
    shader.asAGradient(&gradInfo);

    if (CC_UNLIKELY(!description->isSimpleGradient)) {
        outData->gradientSampler = (*textureUnit)++;

#ifndef SK_SCALAR_IS_FLOAT
#error Need to convert gradInfo.fColorOffsets to float!
#endif
        outData->gradientTexture = caches.gradientCache.get(
                gradInfo.fColors, gradInfo.fColorOffsets, gradInfo.fColorCount);
        outData->wrapST = gTileModes[gradInfo.fTileMode];
    } else {
        outData->gradientSampler = 0;
        outData->gradientTexture = nullptr;

        outData->startColor.set(gradInfo.fColors[0]);
        outData->endColor.set(gradInfo.fColors[1]);
    }

    return true;
}

void applyGradient(Caches& caches, const SkiaShaderData::GradientShaderData& data,
                   const GLsizei width, const GLsizei height) {
    if (CC_UNLIKELY(data.gradientTexture)) {
        caches.textureState().activateTexture(data.gradientSampler);
        bindTexture(&caches, data.gradientTexture, data.wrapST, data.wrapST);
        glUniform1i(caches.program().getUniform("gradientSampler"), data.gradientSampler);
    } else {
        bindUniformColor(caches.program().getUniform("startColor"), data.startColor);
        bindUniformColor(caches.program().getUniform("endColor"), data.endColor);
    }

    glUniform2f(caches.program().getUniform("screenSize"), 1.0f / width, 1.0f / height);
    glUniformMatrix4fv(caches.program().getUniform("screenSpace"), 1, GL_FALSE,
                       &data.screenSpace.data[0]);
}

bool tryStoreBitmap(Caches& caches, const SkShader& shader, const Matrix4& modelViewMatrix,
                    GLuint* textureUnit, ProgramDescription* description,
                    SkiaShaderData::BitmapShaderData* outData) {
    SkBitmap bitmap;
    SkShader::TileMode xy[2];
    if (!shader.isABitmap(&bitmap, nullptr, xy)) {
        return false;
    }

    // TODO: create  hwui-owned BitmapShader.
    Bitmap* hwuiBitmap = static_cast<Bitmap*>(bitmap.pixelRef());
    outData->bitmapTexture = caches.textureCache.get(hwuiBitmap);
    if (!outData->bitmapTexture) return false;

    outData->bitmapSampler = (*textureUnit)++;

    const float width = outData->bitmapTexture->width();
    const float height = outData->bitmapTexture->height();

    Texture* texture = outData->bitmapTexture;

    description->hasBitmap = true;
    description->hasLinearTexture = texture->isLinear();
    description->hasColorSpaceConversion = texture->hasColorSpaceConversion();
    description->transferFunction = texture->getTransferFunctionType();
    description->hasTranslucentConversion = texture->blend;
    description->isShaderBitmapExternal = hwuiBitmap->isHardware();
    // gralloc doesn't support non-clamp modes
    if (hwuiBitmap->isHardware() ||
        (!caches.extensions().hasNPot() && (!isPowerOfTwo(width) || !isPowerOfTwo(height)) &&
         (xy[0] != SkShader::kClamp_TileMode || xy[1] != SkShader::kClamp_TileMode))) {
        // need non-clamp mode, but it's not supported for this draw,
        // so enable custom shader logic to mimic
        description->useShaderBasedWrap = true;
        description->bitmapWrapS = gTileModes[xy[0]];
        description->bitmapWrapT = gTileModes[xy[1]];

        outData->wrapS = GL_CLAMP_TO_EDGE;
        outData->wrapT = GL_CLAMP_TO_EDGE;
    } else {
        outData->wrapS = gTileModes[xy[0]];
        outData->wrapT = gTileModes[xy[1]];
    }

    computeScreenSpaceMatrix(outData->textureTransform, SkMatrix::I(), shader.getLocalMatrix(),
                             modelViewMatrix);
    outData->textureDimension[0] = 1.0f / width;
    outData->textureDimension[1] = 1.0f / height;

    return true;
}

void applyBitmap(Caches& caches, const SkiaShaderData::BitmapShaderData& data) {
    caches.textureState().activateTexture(data.bitmapSampler);
    bindTexture(&caches, data.bitmapTexture, data.wrapS, data.wrapT);
    data.bitmapTexture->setFilter(GL_LINEAR);

    glUniform1i(caches.program().getUniform("bitmapSampler"), data.bitmapSampler);
    glUniformMatrix4fv(caches.program().getUniform("textureTransform"), 1, GL_FALSE,
                       &data.textureTransform.data[0]);
    glUniform2fv(caches.program().getUniform("textureDimension"), 1, &data.textureDimension[0]);
}

SkiaShaderType getComposeSubType(const SkShader& shader) {
    // First check for a gradient shader.
    switch (shader.asAGradient(nullptr)) {
        case SkShader::kNone_GradientType:
            // Not a gradient shader. Fall through to check for other types.
            break;
        case SkShader::kLinear_GradientType:
        case SkShader::kRadial_GradientType:
        case SkShader::kSweep_GradientType:
            return kGradient_SkiaShaderType;
        default:
            // This is a Skia gradient that has no SkiaShader equivalent. Return None to skip.
            return kNone_SkiaShaderType;
    }

    // The shader is not a gradient. Check for a bitmap shader.
    if (shader.isABitmap()) {
        return kBitmap_SkiaShaderType;
    }
    return kNone_SkiaShaderType;
}

void storeCompose(Caches& caches, const SkShader& bitmapShader, const SkShader& gradientShader,
                  const Matrix4& modelViewMatrix, GLuint* textureUnit,
                  ProgramDescription* description, SkiaShaderData* outData) {
    LOG_ALWAYS_FATAL_IF(!tryStoreBitmap(caches, bitmapShader, modelViewMatrix, textureUnit,
                                        description, &outData->bitmapData),
                        "failed storing bitmap shader data");
    LOG_ALWAYS_FATAL_IF(!tryStoreGradient(caches, gradientShader, modelViewMatrix, textureUnit,
                                          description, &outData->gradientData),
                        "failing storing gradient shader data");
}

bool tryStoreCompose(Caches& caches, const SkShader& shader, const Matrix4& modelViewMatrix,
                     GLuint* textureUnit, ProgramDescription* description,
                     SkiaShaderData* outData) {
    SkShader::ComposeRec rec;
    if (!shader.asACompose(&rec)) return false;

    const SkiaShaderType shaderAType = getComposeSubType(*rec.fShaderA);
    const SkiaShaderType shaderBType = getComposeSubType(*rec.fShaderB);

    // check that type enum values are the 2 flags that compose the kCompose value
    if ((shaderAType & shaderBType) != 0) return false;
    if ((shaderAType | shaderBType) != kCompose_SkiaShaderType) return false;

    mat4 transform;
    computeScreenSpaceMatrix(transform, SkMatrix::I(), shader.getLocalMatrix(), modelViewMatrix);
    if (shaderAType == kBitmap_SkiaShaderType) {
        description->isBitmapFirst = true;
        storeCompose(caches, *rec.fShaderA, *rec.fShaderB, transform, textureUnit, description,
                     outData);
    } else {
        description->isBitmapFirst = false;
        storeCompose(caches, *rec.fShaderB, *rec.fShaderA, transform, textureUnit, description,
                     outData);
    }
    description->shadersMode = rec.fBlendMode;
    return true;
}

void SkiaShader::store(Caches& caches, const SkShader& shader, const Matrix4& modelViewMatrix,
                       GLuint* textureUnit, ProgramDescription* description,
                       SkiaShaderData* outData) {
    if (tryStoreGradient(caches, shader, modelViewMatrix, textureUnit, description,
                         &outData->gradientData)) {
        outData->skiaShaderType = kGradient_SkiaShaderType;
        return;
    }

    if (tryStoreBitmap(caches, shader, modelViewMatrix, textureUnit, description,
                       &outData->bitmapData)) {
        outData->skiaShaderType = kBitmap_SkiaShaderType;
        return;
    }

    if (tryStoreCompose(caches, shader, modelViewMatrix, textureUnit, description, outData)) {
        outData->skiaShaderType = kCompose_SkiaShaderType;
        return;
    }

    // Unknown/unsupported type, so explicitly ignore shader
    outData->skiaShaderType = kNone_SkiaShaderType;
}

void SkiaShader::apply(Caches& caches, const SkiaShaderData& data, const GLsizei width,
                       const GLsizei height) {
    if (!data.skiaShaderType) return;

    if (data.skiaShaderType & kGradient_SkiaShaderType) {
        applyGradient(caches, data.gradientData, width, height);
    }
    if (data.skiaShaderType & kBitmap_SkiaShaderType) {
        applyBitmap(caches, data.bitmapData);
    }
}

};  // namespace uirenderer
};  // namespace android
