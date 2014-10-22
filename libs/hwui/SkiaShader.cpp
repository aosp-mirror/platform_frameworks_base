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
#include "Layer.h"
#include "Matrix.h"
#include "SkiaShader.h"
#include "Texture.h"

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

static inline void bindTexture(Caches* caches, Texture* texture, GLenum wrapS, GLenum wrapT) {
    caches->bindTexture(texture->id);
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

// Returns true if one is a bitmap and the other is a gradient
static bool bitmapAndGradient(SkiaShaderType type1, SkiaShaderType type2) {
    return (type1 == kBitmap_SkiaShaderType && type2 == kGradient_SkiaShaderType)
            || (type2 == kBitmap_SkiaShaderType && type1 == kGradient_SkiaShaderType);
}

SkiaShaderType SkiaShader::getType(const SkShader& shader) {
    // First check for a gradient shader.
    switch (shader.asAGradient(NULL)) {
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
    if (shader.asABitmap(NULL, NULL, NULL) == SkShader::kDefault_BitmapType) {
        return kBitmap_SkiaShaderType;
    }

    // Check for a ComposeShader.
    SkShader::ComposeRec rec;
    if (shader.asACompose(&rec)) {
        const SkiaShaderType shaderAType = getType(*rec.fShaderA);
        const SkiaShaderType shaderBType = getType(*rec.fShaderB);

        // Compose is only supported if one is a bitmap and the other is a
        // gradient. Otherwise, return None to skip.
        if (!bitmapAndGradient(shaderAType, shaderBType)) {
            return kNone_SkiaShaderType;
        }
        return kCompose_SkiaShaderType;
    }

    if (shader.asACustomShader(NULL)) {
        return kLayer_SkiaShaderType;
    }

    return kNone_SkiaShaderType;
}

typedef void (*describeProc)(Caches* caches, ProgramDescription& description,
        const Extensions& extensions, const SkShader& shader);

describeProc gDescribeProc[] = {
    InvalidSkiaShader::describe,
    SkiaBitmapShader::describe,
    SkiaGradientShader::describe,
    SkiaComposeShader::describe,
    SkiaLayerShader::describe,
};

typedef void (*setupProgramProc)(Caches* caches, const mat4& modelViewMatrix,
        GLuint* textureUnit, const Extensions& extensions, const SkShader& shader);

setupProgramProc gSetupProgramProc[] = {
    InvalidSkiaShader::setupProgram,
    SkiaBitmapShader::setupProgram,
    SkiaGradientShader::setupProgram,
    SkiaComposeShader::setupProgram,
    SkiaLayerShader::setupProgram,
};

void SkiaShader::describe(Caches* caches, ProgramDescription& description,
        const Extensions& extensions, const SkShader& shader) {
    gDescribeProc[getType(shader)](caches, description, extensions, shader);
}

void SkiaShader::setupProgram(Caches* caches, const mat4& modelViewMatrix,
        GLuint* textureUnit, const Extensions& extensions, const SkShader& shader) {

    gSetupProgramProc[getType(shader)](caches, modelViewMatrix, textureUnit, extensions, shader);
}

///////////////////////////////////////////////////////////////////////////////
// Layer shader
///////////////////////////////////////////////////////////////////////////////

void SkiaLayerShader::describe(Caches*, ProgramDescription& description,
        const Extensions&, const SkShader& shader) {
    description.hasBitmap = true;
}

void SkiaLayerShader::setupProgram(Caches* caches, const mat4& modelViewMatrix,
        GLuint* textureUnit, const Extensions&, const SkShader& shader) {
    Layer* layer;
    if (!shader.asACustomShader(reinterpret_cast<void**>(&layer))) {
        LOG_ALWAYS_FATAL("SkiaLayerShader::setupProgram called on the wrong type of shader!");
    }

    GLuint textureSlot = (*textureUnit)++;
    caches->activeTexture(textureSlot);

    const float width = layer->getWidth();
    const float height = layer->getHeight();

    mat4 textureTransform;
    computeScreenSpaceMatrix(textureTransform, SkMatrix::I(), shader.getLocalMatrix(),
            modelViewMatrix);


    // Uniforms
    layer->bindTexture();
    layer->setWrap(GL_CLAMP_TO_EDGE);
    layer->setFilter(GL_LINEAR);

    Program* program = caches->currentProgram;
    glUniform1i(program->getUniform("bitmapSampler"), textureSlot);
    glUniformMatrix4fv(program->getUniform("textureTransform"), 1,
            GL_FALSE, &textureTransform.data[0]);
    glUniform2f(program->getUniform("textureDimension"), 1.0f / width, 1.0f / height);
}

///////////////////////////////////////////////////////////////////////////////
// Bitmap shader
///////////////////////////////////////////////////////////////////////////////

struct BitmapShaderInfo {
    float width;
    float height;
    GLenum wrapS;
    GLenum wrapT;
    Texture* texture;
};

static bool bitmapShaderHelper(Caches* caches, ProgramDescription* description,
        BitmapShaderInfo* shaderInfo,
        const Extensions& extensions,
        const SkBitmap& bitmap, SkShader::TileMode tileModes[2]) {
    Texture* texture = caches->textureCache.get(&bitmap);
    if (!texture) return false;

    const float width = texture->width;
    const float height = texture->height;
    GLenum wrapS, wrapT;

    if (description) {
        description->hasBitmap = true;
    }
    // The driver does not support non-power of two mirrored/repeated
    // textures, so do it ourselves
    if (!extensions.hasNPot() && (!isPowerOfTwo(width) || !isPowerOfTwo(height)) &&
            (tileModes[0] != SkShader::kClamp_TileMode ||
             tileModes[1] != SkShader::kClamp_TileMode)) {
        if (description) {
            description->isBitmapNpot = true;
            description->bitmapWrapS = gTileModes[tileModes[0]];
            description->bitmapWrapT = gTileModes[tileModes[1]];
        }
        wrapS = GL_CLAMP_TO_EDGE;
        wrapT = GL_CLAMP_TO_EDGE;
    } else {
        wrapS = gTileModes[tileModes[0]];
        wrapT = gTileModes[tileModes[1]];
    }

    if (shaderInfo) {
        shaderInfo->width = width;
        shaderInfo->height = height;
        shaderInfo->wrapS = wrapS;
        shaderInfo->wrapT = wrapT;
        shaderInfo->texture = texture;
    }
    return true;
}

void SkiaBitmapShader::describe(Caches* caches, ProgramDescription& description,
        const Extensions& extensions, const SkShader& shader) {
    SkBitmap bitmap;
    SkShader::TileMode xy[2];
    if (shader.asABitmap(&bitmap, NULL, xy) != SkShader::kDefault_BitmapType) {
        LOG_ALWAYS_FATAL("SkiaBitmapShader::describe called with a different kind of shader!");
    }
    bitmapShaderHelper(caches, &description, NULL, extensions, bitmap, xy);
}

void SkiaBitmapShader::setupProgram(Caches* caches, const mat4& modelViewMatrix,
        GLuint* textureUnit, const Extensions& extensions, const SkShader& shader) {
    SkBitmap bitmap;
    SkShader::TileMode xy[2];
    if (shader.asABitmap(&bitmap, NULL, xy) != SkShader::kDefault_BitmapType) {
        LOG_ALWAYS_FATAL("SkiaBitmapShader::setupProgram called with a different kind of shader!");
    }

    GLuint textureSlot = (*textureUnit)++;
    Caches::getInstance().activeTexture(textureSlot);

    BitmapShaderInfo shaderInfo;
    if (!bitmapShaderHelper(caches, NULL, &shaderInfo, extensions, bitmap, xy)) {
        return;
    }

    Program* program = caches->currentProgram;
    Texture* texture = shaderInfo.texture;

    const AutoTexture autoCleanup(texture);

    mat4 textureTransform;
    computeScreenSpaceMatrix(textureTransform, SkMatrix::I(), shader.getLocalMatrix(),
            modelViewMatrix);

    // Uniforms
    bindTexture(caches, texture, shaderInfo.wrapS, shaderInfo.wrapT);
    texture->setFilter(GL_LINEAR);

    glUniform1i(program->getUniform("bitmapSampler"), textureSlot);
    glUniformMatrix4fv(program->getUniform("textureTransform"), 1,
            GL_FALSE, &textureTransform.data[0]);
    glUniform2f(program->getUniform("textureDimension"), 1.0f / shaderInfo.width,
            1.0f / shaderInfo.height);
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

///////////////////////////////////////////////////////////////////////////////
// Circular gradient shader
///////////////////////////////////////////////////////////////////////////////

static void toCircularUnitMatrix(const float x, const float y, const float radius,
        SkMatrix* matrix) {
    const float inv = 1.0f / radius;
    matrix->setTranslate(-x, -y);
    matrix->postScale(inv, inv);
}

///////////////////////////////////////////////////////////////////////////////
// Sweep gradient shader
///////////////////////////////////////////////////////////////////////////////

static void toSweepUnitMatrix(const float x, const float y, SkMatrix* matrix) {
    matrix->setTranslate(-x, -y);
}

///////////////////////////////////////////////////////////////////////////////
// Common gradient code
///////////////////////////////////////////////////////////////////////////////

static bool isSimpleGradient(const SkShader::GradientInfo& gradInfo) {
    return gradInfo.fColorCount == 2 && gradInfo.fTileMode == SkShader::kClamp_TileMode;
}

void SkiaGradientShader::describe(Caches*, ProgramDescription& description,
        const Extensions& extensions, const SkShader& shader) {
    SkShader::GradientInfo gradInfo;
    gradInfo.fColorCount = 0;
    gradInfo.fColors = NULL;
    gradInfo.fColorOffsets = NULL;

    switch (shader.asAGradient(&gradInfo)) {
        case SkShader::kLinear_GradientType:
            description.gradientType = ProgramDescription::kGradientLinear;
            break;
        case SkShader::kRadial_GradientType:
            description.gradientType = ProgramDescription::kGradientCircular;
            break;
        case SkShader::kSweep_GradientType:
            description.gradientType = ProgramDescription::kGradientSweep;
            break;
        default:
            // Do nothing. This shader is unsupported.
            return;
    }
    description.hasGradient = true;
    description.isSimpleGradient = isSimpleGradient(gradInfo);
}

void SkiaGradientShader::setupProgram(Caches* caches, const mat4& modelViewMatrix,
        GLuint* textureUnit, const Extensions&, const SkShader& shader) {
    // SkShader::GradientInfo.fColorCount is an in/out parameter. As input, it tells asAGradient
    // how much space has been allocated for fColors and fColorOffsets.  10 was chosen
    // arbitrarily, but should be >= 2.
    // As output, it tells the number of actual colors/offsets in the gradient.
    const int COLOR_COUNT = 10;
    SkAutoSTMalloc<COLOR_COUNT, SkColor> colorStorage(COLOR_COUNT);
    SkAutoSTMalloc<COLOR_COUNT, SkScalar> positionStorage(COLOR_COUNT);

    SkShader::GradientInfo gradInfo;
    gradInfo.fColorCount = COLOR_COUNT;
    gradInfo.fColors = colorStorage.get();
    gradInfo.fColorOffsets = positionStorage.get();

    SkShader::GradientType gradType = shader.asAGradient(&gradInfo);

    Program* program = caches->currentProgram;
    if (CC_UNLIKELY(!isSimpleGradient(gradInfo))) {
        if (gradInfo.fColorCount > COLOR_COUNT) {
            // There was not enough room in our arrays for all the colors and offsets. Try again,
            // now that we know the true number of colors.
            gradInfo.fColors = colorStorage.reset(gradInfo.fColorCount);
            gradInfo.fColorOffsets = positionStorage.reset(gradInfo.fColorCount);

            shader.asAGradient(&gradInfo);
        }
        GLuint textureSlot = (*textureUnit)++;
        caches->activeTexture(textureSlot);

#ifndef SK_SCALAR_IS_FLOAT
    #error Need to convert gradInfo.fColorOffsets to float!
#endif
        Texture* texture = caches->gradientCache.get(gradInfo.fColors, gradInfo.fColorOffsets,
                gradInfo.fColorCount);

        // Uniforms
        bindTexture(caches, texture, gTileModes[gradInfo.fTileMode], gTileModes[gradInfo.fTileMode]);
        glUniform1i(program->getUniform("gradientSampler"), textureSlot);
    } else {
        bindUniformColor(program->getUniform("startColor"), gradInfo.fColors[0]);
        bindUniformColor(program->getUniform("endColor"), gradInfo.fColors[1]);
    }

    caches->dither.setupProgram(program, textureUnit);

    SkMatrix unitMatrix;
    switch (gradType) {
        case SkShader::kLinear_GradientType:
            toUnitMatrix(gradInfo.fPoint, &unitMatrix);
            break;
        case SkShader::kRadial_GradientType:
            toCircularUnitMatrix(gradInfo.fPoint[0].fX, gradInfo.fPoint[0].fY,
                    gradInfo.fRadius[0], &unitMatrix);
            break;
        case SkShader::kSweep_GradientType:
            toSweepUnitMatrix(gradInfo.fPoint[0].fX, gradInfo.fPoint[0].fY, &unitMatrix);
            break;
        default:
            LOG_ALWAYS_FATAL("Invalid SkShader gradient type %d", gradType);
    }

    mat4 screenSpace;
    computeScreenSpaceMatrix(screenSpace, unitMatrix, shader.getLocalMatrix(), modelViewMatrix);
    glUniformMatrix4fv(program->getUniform("screenSpace"), 1, GL_FALSE, &screenSpace.data[0]);
}

///////////////////////////////////////////////////////////////////////////////
// Compose shader
///////////////////////////////////////////////////////////////////////////////

void SkiaComposeShader::describe(Caches* caches, ProgramDescription& description,
        const Extensions& extensions, const SkShader& shader) {
    SkShader::ComposeRec rec;
    if (!shader.asACompose(&rec)) {
        LOG_ALWAYS_FATAL("SkiaComposeShader::describe called on the wrong shader type!");
    }
    SkiaShader::describe(caches, description, extensions, *rec.fShaderA);
    SkiaShader::describe(caches, description, extensions, *rec.fShaderB);
    if (SkiaShader::getType(*rec.fShaderA) == kBitmap_SkiaShaderType) {
        description.isBitmapFirst = true;
    }
    if (!SkXfermode::AsMode(rec.fMode, &description.shadersMode)) {
        // TODO: Support other modes.
        description.shadersMode = SkXfermode::kSrcOver_Mode;
    }
}

void SkiaComposeShader::setupProgram(Caches* caches, const mat4& modelViewMatrix,
        GLuint* textureUnit, const Extensions& extensions, const SkShader& shader) {
    SkShader::ComposeRec rec;
    if (!shader.asACompose(&rec)) {
        LOG_ALWAYS_FATAL("SkiaComposeShader::setupProgram called on the wrong shader type!");
    }

    // Apply this compose shader's local transform and pass it down to
    // the child shaders. They will in turn apply their local transform
    // to this matrix.
    mat4 transform;
    computeScreenSpaceMatrix(transform, SkMatrix::I(), shader.getLocalMatrix(),
            modelViewMatrix);

    SkiaShader::setupProgram(caches, transform, textureUnit, extensions, *rec.fShaderA);
    SkiaShader::setupProgram(caches, transform, textureUnit, extensions, *rec.fShaderB);
}

}; // namespace uirenderer
}; // namespace android
