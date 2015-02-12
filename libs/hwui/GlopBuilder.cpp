/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "GlopBuilder.h"

#include "Caches.h"
#include "Glop.h"
#include "Matrix.h"
#include "renderstate/MeshState.h"
#include "renderstate/RenderState.h"
#include "SkiaShader.h"
#include "Texture.h"
#include "utils/PaintUtils.h"
#include "VertexBuffer.h"

#include <GLES2/gl2.h>
#include <SkPaint.h>

namespace android {
namespace uirenderer {

#define TRIGGER_STAGE(stageFlag) \
    LOG_ALWAYS_FATAL_IF(stageFlag & mStageFlags, "Stage %d cannot be run twice"); \
    mStageFlags = static_cast<StageFlags>(mStageFlags | (stageFlag))

#define REQUIRE_STAGES(requiredFlags) \
    LOG_ALWAYS_FATAL_IF((mStageFlags & (requiredFlags)) != (requiredFlags), \
            "not prepared for current stage")

static void setUnitQuadTextureCoords(Rect uvs, TextureVertex* quadVertex) {;
    TextureVertex::setUV(quadVertex++, uvs.left, uvs.top);
    TextureVertex::setUV(quadVertex++, uvs.right, uvs.top);
    TextureVertex::setUV(quadVertex++, uvs.left, uvs.bottom);
    TextureVertex::setUV(quadVertex++, uvs.right, uvs.bottom);
}

GlopBuilder::GlopBuilder(RenderState& renderState, Caches& caches, Glop* outGlop)
        : mRenderState(renderState)
        , mCaches(caches)
        , mOutGlop(outGlop) {
    mStageFlags = kInitialStage;
}

////////////////////////////////////////////////////////////////////////////////
// Mesh
////////////////////////////////////////////////////////////////////////////////

GlopBuilder& GlopBuilder::setMeshUnitQuad() {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.vertexFlags = kNone_Attrib;
    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.vertexBufferObject = mRenderState.meshState().getUnitQuadVBO();
    mOutGlop->mesh.vertices = nullptr;
    mOutGlop->mesh.indexBufferObject = 0;
    mOutGlop->mesh.indices = nullptr;
    mOutGlop->mesh.elementCount = 4;
    mOutGlop->mesh.stride = kTextureVertexStride;
    mOutGlop->mesh.texCoordOffset = nullptr;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshTexturedUnitQuad(const UvMapper* uvMapper,
        bool isAlphaMaskTexture) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.vertexFlags = kTextureCoord_Attrib;
    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;

    if (CC_UNLIKELY(uvMapper)) {
        Rect uvs(0, 0, 1, 1);
        uvMapper->map(uvs);
        setUnitQuadTextureCoords(uvs, &mOutGlop->mesh.mappedVertices[0]);

        mOutGlop->mesh.vertexBufferObject = 0;
        mOutGlop->mesh.vertices = &mOutGlop->mesh.mappedVertices[0];
    } else {
        // standard UV coordinates, use regular unit quad VBO
        mOutGlop->mesh.vertexBufferObject = mRenderState.meshState().getUnitQuadVBO();
        mOutGlop->mesh.vertices = nullptr;
    }
    mOutGlop->mesh.indexBufferObject = 0;
    mOutGlop->mesh.indices = nullptr;
    mOutGlop->mesh.elementCount = 4;
    mOutGlop->mesh.stride = kTextureVertexStride;
    mOutGlop->mesh.texCoordOffset = (GLvoid*) kMeshTextureOffset;

    mDescription.hasTexture = true;
    mDescription.hasAlpha8Texture = isAlphaMaskTexture;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshIndexedQuads(void* vertexData, int quadCount) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.vertexFlags = kNone_Attrib;
    mOutGlop->mesh.primitiveMode = GL_TRIANGLES;
    mOutGlop->mesh.vertexBufferObject = 0;
    mOutGlop->mesh.vertices = vertexData;
    mOutGlop->mesh.indexBufferObject = mRenderState.meshState().getQuadListIBO();
    mOutGlop->mesh.indices = nullptr;
    mOutGlop->mesh.elementCount = 6 * quadCount;
    mOutGlop->mesh.stride = kVertexStride;
    mOutGlop->mesh.texCoordOffset = nullptr;

    return *this;
}

GlopBuilder& GlopBuilder::setMeshVertexBuffer(const VertexBuffer& vertexBuffer, bool shadowInterp) {
    TRIGGER_STAGE(kMeshStage);

    const VertexBuffer::MeshFeatureFlags flags = vertexBuffer.getMeshFeatureFlags();

    bool alphaVertex = flags & VertexBuffer::kAlpha;
    bool indices = flags & VertexBuffer::kIndices;
    mOutGlop->mesh.vertexFlags = alphaVertex ? kAlpha_Attrib : kNone_Attrib;
    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.vertexBufferObject = 0;
    mOutGlop->mesh.vertices = vertexBuffer.getBuffer();
    mOutGlop->mesh.indexBufferObject = 0;
    mOutGlop->mesh.indices = vertexBuffer.getIndices();
    mOutGlop->mesh.elementCount = indices
            ? vertexBuffer.getIndexCount() : vertexBuffer.getVertexCount();
    mOutGlop->mesh.stride = alphaVertex ? kAlphaVertexStride : kVertexStride;

    mDescription.hasVertexAlpha = alphaVertex;
    mDescription.useShadowAlphaInterp = shadowInterp;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// Fill
////////////////////////////////////////////////////////////////////////////////

void GlopBuilder::setFill(int color, float alphaScale, SkXfermode::Mode mode,
        const SkShader* shader, const SkColorFilter* colorFilter) {
    if (mode != SkXfermode::kClear_Mode) {
        float alpha = (SkColorGetA(color) / 255.0f) * alphaScale;
        if (!shader) {
            float colorScale = alpha / 255.0f;
            mOutGlop->fill.color = {
                    colorScale * SkColorGetR(color),
                    colorScale * SkColorGetG(color),
                    colorScale * SkColorGetB(color),
                    alpha
            };
        } else {
            mOutGlop->fill.color = { 1, 1, 1, alpha };
        }
    } else {
        mOutGlop->fill.color = { 0, 0, 0, 1 };
    }
    const bool SWAP_SRC_DST = false;

    mOutGlop->blend = { GL_ZERO, GL_ZERO };
    if (mOutGlop->fill.color.a < 1.0f
            || (mOutGlop->mesh.vertexFlags & kAlpha_Attrib)
            || (mOutGlop->fill.texture && mOutGlop->fill.texture->blend)
            || mOutGlop->roundRectClipState
            || PaintUtils::isBlendedShader(shader)
            || PaintUtils::isBlendedColorFilter(colorFilter)
            || mode != SkXfermode::kSrcOver_Mode) {
        if (CC_LIKELY(mode <= SkXfermode::kScreen_Mode)) {
            Blend::getFactors(mode, SWAP_SRC_DST,
                    &mOutGlop->blend.src, &mOutGlop->blend.dst);
        } else {
            // These blend modes are not supported by OpenGL directly and have
            // to be implemented using shaders. Since the shader will perform
            // the blending, don't enable GL blending off here
            // If the blend mode cannot be implemented using shaders, fall
            // back to the default SrcOver blend mode instead
            if (CC_UNLIKELY(mCaches.extensions().hasFramebufferFetch())) {
                mDescription.framebufferMode = mode;
                mDescription.swapSrcDst = SWAP_SRC_DST;
                // blending in shader, don't enable
            } else {
                // unsupported
                Blend::getFactors(SkXfermode::kSrcOver_Mode, SWAP_SRC_DST,
                        &mOutGlop->blend.src, &mOutGlop->blend.dst);
            }
        }
    }

    if (shader) {
        SkiaShader::describe(&mCaches, mDescription, mCaches.extensions(), *shader);
        // TODO: store shader data
        LOG_ALWAYS_FATAL("shaders not yet supported");
    }

    if (colorFilter) {
        SkColor color;
        SkXfermode::Mode mode;
        SkScalar srcColorMatrix[20];
        if (colorFilter->asColorMode(&color, &mode)) {
            mOutGlop->fill.filterMode = mDescription.colorOp = ProgramDescription::kColorBlend;
            mDescription.colorMode = mode;

            const float alpha = SkColorGetA(color) / 255.0f;
            float colorScale = alpha / 255.0f;
            mOutGlop->fill.filter.color = {
                    colorScale * SkColorGetR(color),
                    colorScale * SkColorGetG(color),
                    colorScale * SkColorGetB(color),
                    alpha,
            };
        } else if (colorFilter->asColorMatrix(srcColorMatrix)) {
            mOutGlop->fill.filterMode = mDescription.colorOp = ProgramDescription::kColorMatrix;

            float* colorMatrix = mOutGlop->fill.filter.matrix.matrix;
            memcpy(colorMatrix, srcColorMatrix, 4 * sizeof(float));
            memcpy(&colorMatrix[4], &srcColorMatrix[5], 4 * sizeof(float));
            memcpy(&colorMatrix[8], &srcColorMatrix[10], 4 * sizeof(float));
            memcpy(&colorMatrix[12], &srcColorMatrix[15], 4 * sizeof(float));

            // Skia uses the range [0..255] for the addition vector, but we need
            // the [0..1] range to apply the vector in GLSL
            float* colorVector = mOutGlop->fill.filter.matrix.vector;
            colorVector[0] = srcColorMatrix[4] / 255.0f;
            colorVector[1] = srcColorMatrix[9] / 255.0f;
            colorVector[2] = srcColorMatrix[14] / 255.0f;
            colorVector[3] = srcColorMatrix[19] / 255.0f;
        } else {
            LOG_ALWAYS_FATAL("unsupported ColorFilter");
        }
    } else {
        mOutGlop->fill.filterMode = ProgramDescription::kColorNone;
    }
}

GlopBuilder& GlopBuilder::setFillTexturePaint(Texture& texture, bool isAlphaMaskTexture,
        const SkPaint* paint, float alphaScale) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage);

    mOutGlop->fill.texture = &texture;
    mOutGlop->fill.textureFilter = PaintUtils::getFilter(paint);
    mOutGlop->fill.textureClamp = GL_CLAMP_TO_EDGE;

    if (paint) {
        int color = paint->getColor();
        SkShader* shader = paint->getShader();

        if (!isAlphaMaskTexture) {
            // Texture defines color, so disable shaders, and reset all non-alpha color channels
            color |= 0x00FFFFFF;
            shader = nullptr;
        }
        setFill(color, alphaScale, PaintUtils::getXfermode(paint->getXfermode()),
                shader, paint->getColorFilter());
    } else {
        mOutGlop->fill.color = { alphaScale, alphaScale, alphaScale, alphaScale };

        const bool SWAP_SRC_DST = false;
        if (alphaScale < 1.0f
                || (mOutGlop->mesh.vertexFlags & kAlpha_Attrib)
                || texture.blend
                || mOutGlop->roundRectClipState) {
            Blend::getFactors(SkXfermode::kSrcOver_Mode, SWAP_SRC_DST,
                    &mOutGlop->blend.src, &mOutGlop->blend.dst);
        } else {
            mOutGlop->blend = { GL_ZERO, GL_ZERO };
        }
    }

    if (isAlphaMaskTexture) {
        mDescription.modulate = mOutGlop->fill.color.a < 1.0f
                || mOutGlop->fill.color.r > 0.0f
                || mOutGlop->fill.color.g > 0.0f
                || mOutGlop->fill.color.b > 0.0f;
    } else {
        mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    }
    return *this;
}

GlopBuilder& GlopBuilder::setFillPaint(const SkPaint& paint, float alphaScale) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage);

    mOutGlop->fill.texture = nullptr;
    mOutGlop->fill.textureFilter = GL_INVALID_ENUM;
    mOutGlop->fill.textureClamp = GL_INVALID_ENUM;

    setFill(paint.getColor(), alphaScale, PaintUtils::getXfermode(paint.getXfermode()),
            paint.getShader(), paint.getColorFilter());
    mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    return *this;
}

GlopBuilder& GlopBuilder::setFillPathTexturePaint(Texture& texture,
        const SkPaint& paint, float alphaScale) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage);

    mOutGlop->fill.texture = &texture;

    //specify invalid, since these are always static for path textures
    mOutGlop->fill.textureFilter = GL_INVALID_ENUM;
    mOutGlop->fill.textureClamp = GL_INVALID_ENUM;

    setFill(paint.getColor(), alphaScale, PaintUtils::getXfermode(paint.getXfermode()),
            paint.getShader(), paint.getColorFilter());

    mDescription.modulate = mOutGlop->fill.color.a < 1.0f
            || mOutGlop->fill.color.r > 0.0f
            || mOutGlop->fill.color.g > 0.0f
            || mOutGlop->fill.color.b > 0.0f;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// Transform
////////////////////////////////////////////////////////////////////////////////

GlopBuilder& GlopBuilder::setTransformClip(const Matrix4& ortho,
        const Matrix4& transform, bool fudgingOffset) {
    TRIGGER_STAGE(kTransformStage);

    mOutGlop->transform.ortho.load(ortho);
    mOutGlop->transform.canvas.load(transform);
    mOutGlop->transform.fudgingOffset = fudgingOffset;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// ModelView
////////////////////////////////////////////////////////////////////////////////

GlopBuilder& GlopBuilder::setModelViewMapUnitToRect(const Rect destination) {
    TRIGGER_STAGE(kModelViewStage);

    mOutGlop->transform.modelView.loadTranslate(destination.left, destination.top, 0.0f);
    mOutGlop->transform.modelView.scale(destination.getWidth(), destination.getHeight(), 1.0f);
    mOutGlop->bounds = destination;
    return *this;
}

GlopBuilder& GlopBuilder::setModelViewMapUnitToRectSnap(const Rect destination) {
    TRIGGER_STAGE(kModelViewStage);
    REQUIRE_STAGES(kTransformStage | kFillStage);

    float left = destination.left;
    float top = destination.top;

    const Matrix4& canvasTransform = mOutGlop->transform.canvas;
    if (CC_LIKELY(canvasTransform.isPureTranslate())) {
        const float translateX = canvasTransform.getTranslateX();
        const float translateY = canvasTransform.getTranslateY();

        left = (int) floorf(left + translateX + 0.5f) - translateX;
        top = (int) floorf(top + translateY + 0.5f) - translateY;
        mOutGlop->fill.textureFilter = GL_NEAREST;
    }

    mOutGlop->transform.modelView.loadTranslate(left, top, 0.0f);
    mOutGlop->transform.modelView.scale(destination.getWidth(), destination.getHeight(), 1.0f);
    mOutGlop->bounds = destination;
    return *this;
}

GlopBuilder& GlopBuilder::setModelViewOffsetRect(float offsetX, float offsetY, const Rect source) {
    TRIGGER_STAGE(kModelViewStage);

    mOutGlop->transform.modelView.loadTranslate(offsetX, offsetY, 0.0f);
    mOutGlop->bounds = source;
    mOutGlop->bounds.translate(offsetX, offsetY);
    return *this;
}

GlopBuilder& GlopBuilder::setRoundRectClipState(const RoundRectClipState* roundRectClipState) {
    TRIGGER_STAGE(kRoundRectClipStage);

    mOutGlop->roundRectClipState = roundRectClipState;
    mDescription.hasRoundRectClip = roundRectClipState != nullptr;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// Build
////////////////////////////////////////////////////////////////////////////////

void GlopBuilder::build() {
    REQUIRE_STAGES(kAllStages);

    mOutGlop->fill.program = mCaches.programCache.get(mDescription);
    mOutGlop->transform.canvas.mapRect(mOutGlop->bounds);

    // duplicates ProgramCache's definition of color uniform presence
    const bool singleColor = !mDescription.hasTexture
            && !mDescription.hasExternalTexture
            && !mDescription.hasGradient
            && !mDescription.hasBitmap;
    mOutGlop->fill.colorEnabled = mDescription.modulate || singleColor;
}

} /* namespace uirenderer */
} /* namespace android */
