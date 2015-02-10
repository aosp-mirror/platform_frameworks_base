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
    mStageFlags = static_cast<StageFlags>(mStageFlags | stageFlag)

#define REQUIRE_STAGES(requiredFlags) \
    LOG_ALWAYS_FATAL_IF((mStageFlags & requiredFlags) != requiredFlags, \
            "not prepared for current stage")

GlopBuilder::GlopBuilder(RenderState& renderState, Caches& caches, Glop* outGlop)
        : mRenderState(renderState)
        , mCaches(caches)
        , mOutGlop(outGlop){
    mStageFlags = kInitialStage;
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
    mOutGlop->mesh.vertexCount = indices
            ? vertexBuffer.getIndexCount() : vertexBuffer.getVertexCount();
    mOutGlop->mesh.stride = alphaVertex ? kAlphaVertexStride : kVertexStride;

    mDescription.hasVertexAlpha = alphaVertex;
    mDescription.useShadowAlphaInterp = shadowInterp;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshUnitQuad() {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.vertexFlags = kNone_Attrib;
    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.vertexBufferObject = mRenderState.meshState().getUnitQuadVBO();
    mOutGlop->mesh.vertices = nullptr;
    mOutGlop->mesh.indexBufferObject = 0;
    mOutGlop->mesh.indices = nullptr;
    mOutGlop->mesh.vertexCount = 4;
    mOutGlop->mesh.stride = kTextureVertexStride;
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
    mOutGlop->mesh.vertexCount = 6 * quadCount;
    mOutGlop->mesh.stride = kVertexStride;

    return *this;
}

GlopBuilder& GlopBuilder::setTransform(const Matrix4& ortho,
        const Matrix4& transform, bool fudgingOffset) {
    TRIGGER_STAGE(kTransformStage);

    mOutGlop->transform.ortho.load(ortho);
    mOutGlop->transform.canvas.load(transform);
    mOutGlop->transform.fudgingOffset = fudgingOffset;
    return *this;
}

GlopBuilder& GlopBuilder::setModelViewMapUnitToRect(const Rect destination) {
    TRIGGER_STAGE(kModelViewStage);

    mOutGlop->transform.modelView.loadTranslate(destination.left, destination.top, 0.0f);
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

GlopBuilder& GlopBuilder::setOptionalPaint(const SkPaint* paint, float alphaScale) {
    if (paint) {
        return setPaint(*paint, alphaScale);
    }

    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage);

    mOutGlop->fill.color = { alphaScale, alphaScale, alphaScale, alphaScale };

    const bool SWAP_SRC_DST = false;
    // TODO: account for texture blend
    if (alphaScale < 1.0f
            || (mOutGlop->mesh.vertexFlags & kAlpha_Attrib)) {
        Blend::getFactors(SkXfermode::kSrcOver_Mode, SWAP_SRC_DST,
                &mOutGlop->blend.src, &mOutGlop->blend.dst);
    } else {
        mOutGlop->blend = { GL_ZERO, GL_ZERO };
    }

    return *this;
}
GlopBuilder& GlopBuilder::setPaint(const SkPaint& paint, float alphaScale) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage);

    const SkShader* shader = paint.getShader();
    const SkColorFilter* colorFilter = paint.getColorFilter();

    SkXfermode::Mode mode = PaintUtils::getXfermode(paint.getXfermode());
    if (mode != SkXfermode::kClear_Mode) {
        int color = paint.getColor();
        float alpha = (SkColorGetA(color) / 255.0f) * alphaScale;
        if (!shader) {
            float colorScale = alpha / 255.0f;
            mOutGlop->fill.color = {
                    alpha,
                    colorScale * SkColorGetR(color),
                    colorScale * SkColorGetG(color),
                    colorScale * SkColorGetB(color)
            };
        } else {
            mOutGlop->fill.color = { alpha, 1, 1, 1 };
        }
    } else {
        mOutGlop->fill.color = { 1, 0, 0, 0 };
    }
    const bool SWAP_SRC_DST = false;

    mOutGlop->blend = { GL_ZERO, GL_ZERO };
    if (mOutGlop->fill.color.a < 1.0f
            || (mOutGlop->mesh.vertexFlags & kAlpha_Attrib)
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
                    alpha,
                    colorScale * SkColorGetR(color),
                    colorScale * SkColorGetG(color),
                    colorScale * SkColorGetB(color),
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

    return *this;
}

void GlopBuilder::build() {
    REQUIRE_STAGES(kAllStages);

    mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    mOutGlop->fill.program = mCaches.programCache.get(mDescription);
    mOutGlop->transform.canvas.mapRect(mOutGlop->bounds);
}

} /* namespace uirenderer */
} /* namespace android */

