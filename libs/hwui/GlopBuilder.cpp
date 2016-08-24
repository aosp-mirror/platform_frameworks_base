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
#include "Patch.h"
#include "renderstate/MeshState.h"
#include "renderstate/RenderState.h"
#include "SkiaShader.h"
#include "Texture.h"
#include "utils/PaintUtils.h"
#include "VertexBuffer.h"

#include <GLES2/gl2.h>
#include <SkPaint.h>

#define DEBUG_GLOP_BUILDER 0

#if DEBUG_GLOP_BUILDER

#define TRIGGER_STAGE(stageFlag) \
    LOG_ALWAYS_FATAL_IF((stageFlag) & mStageFlags, "Stage %d cannot be run twice", (stageFlag)); \
    mStageFlags = static_cast<StageFlags>(mStageFlags | (stageFlag))

#define REQUIRE_STAGES(requiredFlags) \
    LOG_ALWAYS_FATAL_IF((mStageFlags & (requiredFlags)) != (requiredFlags), \
            "not prepared for current stage")

#else

#define TRIGGER_STAGE(stageFlag) ((void)0)
#define REQUIRE_STAGES(requiredFlags) ((void)0)

#endif

namespace android {
namespace uirenderer {

static void setUnitQuadTextureCoords(Rect uvs, TextureVertex* quadVertex) {
    quadVertex[0] = {0, 0, uvs.left, uvs.top};
    quadVertex[1] = {1, 0, uvs.right, uvs.top};
    quadVertex[2] = {0, 1, uvs.left, uvs.bottom};
    quadVertex[3] = {1, 1, uvs.right, uvs.bottom};
}

GlopBuilder::GlopBuilder(RenderState& renderState, Caches& caches, Glop* outGlop)
        : mRenderState(renderState)
        , mCaches(caches)
        , mShader(nullptr)
        , mOutGlop(outGlop) {
    mStageFlags = kInitialStage;
}

////////////////////////////////////////////////////////////////////////////////
// Mesh
////////////////////////////////////////////////////////////////////////////////

GlopBuilder& GlopBuilder::setMeshTexturedIndexedVbo(GLuint vbo, GLsizei elementCount) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLES;
    mOutGlop->mesh.indices = { mRenderState.meshState().getQuadListIBO(), nullptr };
    mOutGlop->mesh.vertices = {
            vbo,
            VertexAttribFlags::TextureCoord,
            nullptr, (const void*) kMeshTextureOffset, nullptr,
            kTextureVertexStride };
    mOutGlop->mesh.elementCount = elementCount;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshUnitQuad() {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.indices = { 0, nullptr };
    mOutGlop->mesh.vertices = {
            mRenderState.meshState().getUnitQuadVBO(),
            VertexAttribFlags::None,
            nullptr, nullptr, nullptr,
            kTextureVertexStride };
    mOutGlop->mesh.elementCount = 4;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshTexturedUnitQuad(const UvMapper* uvMapper) {
    if (uvMapper) {
        // can't use unit quad VBO, so build UV vertices manually
        return setMeshTexturedUvQuad(uvMapper, Rect(1, 1));
    }

    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.indices = { 0, nullptr };
    mOutGlop->mesh.vertices = {
            mRenderState.meshState().getUnitQuadVBO(),
            VertexAttribFlags::TextureCoord,
            nullptr, (const void*) kMeshTextureOffset, nullptr,
            kTextureVertexStride };
    mOutGlop->mesh.elementCount = 4;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshTexturedUvQuad(const UvMapper* uvMapper, Rect uvs) {
    TRIGGER_STAGE(kMeshStage);

    if (CC_UNLIKELY(uvMapper)) {
        uvMapper->map(uvs);
    }
    setUnitQuadTextureCoords(uvs, &mOutGlop->mesh.mappedVertices[0]);

    const TextureVertex* textureVertex = mOutGlop->mesh.mappedVertices;
    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.indices = { 0, nullptr };
    mOutGlop->mesh.vertices = {
            0,
            VertexAttribFlags::TextureCoord,
            &textureVertex[0].x, &textureVertex[0].u, nullptr,
            kTextureVertexStride };
    mOutGlop->mesh.elementCount = 4;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshIndexedQuads(Vertex* vertexData, int quadCount) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLES;
    mOutGlop->mesh.indices = { mRenderState.meshState().getQuadListIBO(), nullptr };
    mOutGlop->mesh.vertices = {
            0,
            VertexAttribFlags::None,
            vertexData, nullptr, nullptr,
            kVertexStride };
    mOutGlop->mesh.elementCount = 6 * quadCount;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshTexturedIndexedQuads(TextureVertex* vertexData, int elementCount) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLES;
    mOutGlop->mesh.indices = { mRenderState.meshState().getQuadListIBO(), nullptr };
    mOutGlop->mesh.vertices = {
            0,
            VertexAttribFlags::TextureCoord,
            &vertexData[0].x, &vertexData[0].u, nullptr,
            kTextureVertexStride };
    mOutGlop->mesh.elementCount = elementCount;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshTexturedMesh(TextureVertex* vertexData, int elementCount) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLES;
    mOutGlop->mesh.indices = { 0, nullptr };
    mOutGlop->mesh.vertices = {
            0,
            VertexAttribFlags::TextureCoord,
            &vertexData[0].x, &vertexData[0].u, nullptr,
            kTextureVertexStride };
    mOutGlop->mesh.elementCount = elementCount;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshColoredTexturedMesh(ColorTextureVertex* vertexData, int elementCount) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLES;
    mOutGlop->mesh.indices = { 0, nullptr };
    mOutGlop->mesh.vertices = {
            0,
            VertexAttribFlags::TextureCoord | VertexAttribFlags::Color,
            &vertexData[0].x, &vertexData[0].u, &vertexData[0].r,
            kColorTextureVertexStride };
    mOutGlop->mesh.elementCount = elementCount;
    return *this;
}

GlopBuilder& GlopBuilder::setMeshVertexBuffer(const VertexBuffer& vertexBuffer) {
    TRIGGER_STAGE(kMeshStage);

    const VertexBuffer::MeshFeatureFlags flags = vertexBuffer.getMeshFeatureFlags();

    bool alphaVertex = flags & VertexBuffer::kAlpha;
    bool indices = flags & VertexBuffer::kIndices;

    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.indices = { 0, vertexBuffer.getIndices() };
    mOutGlop->mesh.vertices = {
            0,
            alphaVertex ? VertexAttribFlags::Alpha : VertexAttribFlags::None,
            vertexBuffer.getBuffer(), nullptr, nullptr,
            alphaVertex ? kAlphaVertexStride : kVertexStride };
    mOutGlop->mesh.elementCount = indices
                ? vertexBuffer.getIndexCount() : vertexBuffer.getVertexCount();
    return *this;
}

GlopBuilder& GlopBuilder::setMeshPatchQuads(const Patch& patch) {
    TRIGGER_STAGE(kMeshStage);

    mOutGlop->mesh.primitiveMode = GL_TRIANGLES;
    mOutGlop->mesh.indices = { mRenderState.meshState().getQuadListIBO(), nullptr };
    mOutGlop->mesh.vertices = {
            mCaches.patchCache.getMeshBuffer(),
            VertexAttribFlags::TextureCoord,
            (void*)patch.positionOffset, (void*)patch.textureOffset, nullptr,
            kTextureVertexStride };
    mOutGlop->mesh.elementCount = patch.indexCount;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// Fill
////////////////////////////////////////////////////////////////////////////////

void GlopBuilder::setFill(int color, float alphaScale,
        SkXfermode::Mode mode, Blend::ModeOrderSwap modeUsage,
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

    mOutGlop->blend = { GL_ZERO, GL_ZERO };
    if (mOutGlop->fill.color.a < 1.0f
            || (mOutGlop->mesh.vertices.attribFlags & VertexAttribFlags::Alpha)
            || (mOutGlop->fill.texture.texture && mOutGlop->fill.texture.texture->blend)
            || mOutGlop->roundRectClipState
            || PaintUtils::isBlendedShader(shader)
            || PaintUtils::isBlendedColorFilter(colorFilter)
            || mode != SkXfermode::kSrcOver_Mode) {
        if (CC_LIKELY(mode <= SkXfermode::kScreen_Mode)) {
            Blend::getFactors(mode, modeUsage,
                    &mOutGlop->blend.src, &mOutGlop->blend.dst);
        } else {
            // These blend modes are not supported by OpenGL directly and have
            // to be implemented using shaders. Since the shader will perform
            // the blending, don't enable GL blending off here
            // If the blend mode cannot be implemented using shaders, fall
            // back to the default SrcOver blend mode instead
            if (CC_UNLIKELY(mCaches.extensions().hasFramebufferFetch())) {
                mDescription.framebufferMode = mode;
                mDescription.swapSrcDst = (modeUsage == Blend::ModeOrderSwap::Swap);
                // blending in shader, don't enable
            } else {
                // unsupported
                Blend::getFactors(SkXfermode::kSrcOver_Mode, modeUsage,
                        &mOutGlop->blend.src, &mOutGlop->blend.dst);
            }
        }
    }
    mShader = shader; // shader resolved in ::build()

    if (colorFilter) {
        SkColor color;
        SkXfermode::Mode mode;
        SkScalar srcColorMatrix[20];
        if (colorFilter->asColorMode(&color, &mode)) {
            mOutGlop->fill.filterMode = mDescription.colorOp = ProgramDescription::ColorFilterMode::Blend;
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
            mOutGlop->fill.filterMode = mDescription.colorOp = ProgramDescription::ColorFilterMode::Matrix;

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
        mOutGlop->fill.filterMode = ProgramDescription::ColorFilterMode::None;
    }
}

GlopBuilder& GlopBuilder::setFillTexturePaint(Texture& texture,
        const int textureFillFlags, const SkPaint* paint, float alphaScale) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    GLenum filter = (textureFillFlags & TextureFillFlags::ForceFilter)
            ? GL_LINEAR : PaintUtils::getFilter(paint);
    mOutGlop->fill.texture = { &texture,
            GL_TEXTURE_2D, filter, GL_CLAMP_TO_EDGE, nullptr };

    if (paint) {
        int color = paint->getColor();
        SkShader* shader = paint->getShader();

        if (!(textureFillFlags & TextureFillFlags::IsAlphaMaskTexture)) {
            // Texture defines color, so disable shaders, and reset all non-alpha color channels
            color |= 0x00FFFFFF;
            shader = nullptr;
        }
        setFill(color, alphaScale,
                PaintUtils::getXfermode(paint->getXfermode()), Blend::ModeOrderSwap::NoSwap,
                shader, paint->getColorFilter());
    } else {
        mOutGlop->fill.color = { alphaScale, alphaScale, alphaScale, alphaScale };

        if (alphaScale < 1.0f
                || (mOutGlop->mesh.vertices.attribFlags & VertexAttribFlags::Alpha)
                || texture.blend
                || mOutGlop->roundRectClipState) {
            Blend::getFactors(SkXfermode::kSrcOver_Mode, Blend::ModeOrderSwap::NoSwap,
                    &mOutGlop->blend.src, &mOutGlop->blend.dst);
        } else {
            mOutGlop->blend = { GL_ZERO, GL_ZERO };
        }
    }

    if (textureFillFlags & TextureFillFlags::IsAlphaMaskTexture) {
        mDescription.modulate = mOutGlop->fill.color.isNotBlack();
        mDescription.hasAlpha8Texture = true;
    } else {
        mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    }
    return *this;
}

GlopBuilder& GlopBuilder::setFillPaint(const SkPaint& paint, float alphaScale, bool shadowInterp) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    if (CC_LIKELY(!shadowInterp)) {
        mOutGlop->fill.texture = {
                nullptr, GL_INVALID_ENUM, GL_INVALID_ENUM, GL_INVALID_ENUM, nullptr };
    } else {
        mOutGlop->fill.texture = {
                mCaches.textureState().getShadowLutTexture(), GL_TEXTURE_2D,
                GL_INVALID_ENUM, GL_INVALID_ENUM, nullptr };
    }

    setFill(paint.getColor(), alphaScale,
            PaintUtils::getXfermode(paint.getXfermode()), Blend::ModeOrderSwap::NoSwap,
            paint.getShader(), paint.getColorFilter());
    mDescription.useShadowAlphaInterp = shadowInterp;
    mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    return *this;
}

GlopBuilder& GlopBuilder::setFillPathTexturePaint(PathTexture& texture,
        const SkPaint& paint, float alphaScale) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    //specify invalid filter/clamp, since these are always static for PathTextures
    mOutGlop->fill.texture = { &texture, GL_TEXTURE_2D, GL_INVALID_ENUM, GL_INVALID_ENUM, nullptr };

    setFill(paint.getColor(), alphaScale,
            PaintUtils::getXfermode(paint.getXfermode()), Blend::ModeOrderSwap::NoSwap,
            paint.getShader(), paint.getColorFilter());

    mDescription.hasAlpha8Texture = true;
    mDescription.modulate = mOutGlop->fill.color.isNotBlack();
    return *this;
}

GlopBuilder& GlopBuilder::setFillShadowTexturePaint(ShadowTexture& texture, int shadowColor,
        const SkPaint& paint, float alphaScale) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    //specify invalid filter/clamp, since these are always static for ShadowTextures
    mOutGlop->fill.texture = { &texture, GL_TEXTURE_2D, GL_INVALID_ENUM, GL_INVALID_ENUM, nullptr };

    const int ALPHA_BITMASK = SK_ColorBLACK;
    const int COLOR_BITMASK = ~ALPHA_BITMASK;
    if ((shadowColor & ALPHA_BITMASK) == ALPHA_BITMASK) {
        // shadow color is fully opaque: override its alpha with that of paint
        shadowColor &= paint.getColor() | COLOR_BITMASK;
    }

    setFill(shadowColor, alphaScale,
            PaintUtils::getXfermode(paint.getXfermode()), Blend::ModeOrderSwap::NoSwap,
            paint.getShader(), paint.getColorFilter());

    mDescription.hasAlpha8Texture = true;
    mDescription.modulate = mOutGlop->fill.color.isNotBlack();
    return *this;
}

GlopBuilder& GlopBuilder::setFillBlack() {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    mOutGlop->fill.texture = { nullptr, GL_INVALID_ENUM, GL_INVALID_ENUM, GL_INVALID_ENUM, nullptr };
    setFill(SK_ColorBLACK, 1.0f, SkXfermode::kSrcOver_Mode, Blend::ModeOrderSwap::NoSwap,
            nullptr, nullptr);
    return *this;
}

GlopBuilder& GlopBuilder::setFillClear() {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    mOutGlop->fill.texture = { nullptr, GL_INVALID_ENUM, GL_INVALID_ENUM, GL_INVALID_ENUM, nullptr };
    setFill(SK_ColorBLACK, 1.0f, SkXfermode::kClear_Mode, Blend::ModeOrderSwap::NoSwap,
            nullptr, nullptr);
    return *this;
}

GlopBuilder& GlopBuilder::setFillLayer(Texture& texture, const SkColorFilter* colorFilter,
        float alpha, SkXfermode::Mode mode, Blend::ModeOrderSwap modeUsage) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    mOutGlop->fill.texture = { &texture,
            GL_TEXTURE_2D, GL_LINEAR, GL_CLAMP_TO_EDGE, nullptr };

    setFill(SK_ColorWHITE, alpha, mode, modeUsage, nullptr, colorFilter);

    mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    return *this;
}

GlopBuilder& GlopBuilder::setFillTextureLayer(Layer& layer, float alpha) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    mOutGlop->fill.texture = { &(layer.getTexture()),
            layer.getRenderTarget(), GL_LINEAR, GL_CLAMP_TO_EDGE, &layer.getTexTransform() };

    setFill(SK_ColorWHITE, alpha, layer.getMode(), Blend::ModeOrderSwap::NoSwap,
            nullptr, layer.getColorFilter());

    mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    mDescription.hasTextureTransform = true;
    return *this;
}

GlopBuilder& GlopBuilder::setFillExternalTexture(Texture& texture, Matrix4& textureTransform) {
    TRIGGER_STAGE(kFillStage);
    REQUIRE_STAGES(kMeshStage | kRoundRectClipStage);

    mOutGlop->fill.texture = { &texture,
            GL_TEXTURE_EXTERNAL_OES, GL_LINEAR, GL_CLAMP_TO_EDGE,
            &textureTransform };

    setFill(SK_ColorWHITE, 1.0f, SkXfermode::kSrc_Mode, Blend::ModeOrderSwap::NoSwap,
            nullptr, nullptr);

    mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    mDescription.hasTextureTransform = true;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// Transform
////////////////////////////////////////////////////////////////////////////////

GlopBuilder& GlopBuilder::setTransform(const Matrix4& canvas, const int transformFlags) {
    TRIGGER_STAGE(kTransformStage);

    mOutGlop->transform.canvas = canvas;
    mOutGlop->transform.transformFlags = transformFlags;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// ModelView
////////////////////////////////////////////////////////////////////////////////

GlopBuilder& GlopBuilder::setModelViewMapUnitToRect(const Rect destination) {
    TRIGGER_STAGE(kModelViewStage);

    mOutGlop->transform.modelView.loadTranslate(destination.left, destination.top, 0.0f);
    mOutGlop->transform.modelView.scale(destination.getWidth(), destination.getHeight(), 1.0f);
#if !HWUI_NEW_OPS
    mOutGlop->bounds = destination;
#endif
    return *this;
}

GlopBuilder& GlopBuilder::setModelViewMapUnitToRectSnap(const Rect destination) {
    TRIGGER_STAGE(kModelViewStage);
    REQUIRE_STAGES(kTransformStage | kFillStage);

    float left = destination.left;
    float top = destination.top;

    const Matrix4& meshTransform = mOutGlop->transform.meshTransform();
    if (CC_LIKELY(meshTransform.isPureTranslate())) {
        // snap by adjusting the model view matrix
        const float translateX = meshTransform.getTranslateX();
        const float translateY = meshTransform.getTranslateY();

        left = (int) floorf(left + translateX + 0.5f) - translateX;
        top = (int) floorf(top + translateY + 0.5f) - translateY;
        mOutGlop->fill.texture.filter = GL_NEAREST;
    }

    mOutGlop->transform.modelView.loadTranslate(left, top, 0.0f);
    mOutGlop->transform.modelView.scale(destination.getWidth(), destination.getHeight(), 1.0f);
#if !HWUI_NEW_OPS
    mOutGlop->bounds = destination;
#endif
    return *this;
}

GlopBuilder& GlopBuilder::setModelViewOffsetRect(float offsetX, float offsetY, const Rect source) {
    TRIGGER_STAGE(kModelViewStage);

    mOutGlop->transform.modelView.loadTranslate(offsetX, offsetY, 0.0f);
#if !HWUI_NEW_OPS
    mOutGlop->bounds = source;
    mOutGlop->bounds.translate(offsetX, offsetY);
#endif
    return *this;
}

GlopBuilder& GlopBuilder::setModelViewOffsetRectSnap(float offsetX, float offsetY, const Rect source) {
    TRIGGER_STAGE(kModelViewStage);
    REQUIRE_STAGES(kTransformStage | kFillStage);

    const Matrix4& meshTransform = mOutGlop->transform.meshTransform();
    if (CC_LIKELY(meshTransform.isPureTranslate())) {
        // snap by adjusting the model view matrix
        const float translateX = meshTransform.getTranslateX();
        const float translateY = meshTransform.getTranslateY();

        offsetX = (int) floorf(offsetX + translateX + source.left + 0.5f) - translateX - source.left;
        offsetY = (int) floorf(offsetY + translateY + source.top + 0.5f) - translateY - source.top;
        mOutGlop->fill.texture.filter = GL_NEAREST;
    }

    mOutGlop->transform.modelView.loadTranslate(offsetX, offsetY, 0.0f);
#if !HWUI_NEW_OPS
    mOutGlop->bounds = source;
    mOutGlop->bounds.translate(offsetX, offsetY);
#endif
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// RoundRectClip
////////////////////////////////////////////////////////////////////////////////

GlopBuilder& GlopBuilder::setRoundRectClipState(const RoundRectClipState* roundRectClipState) {
    TRIGGER_STAGE(kRoundRectClipStage);

    mOutGlop->roundRectClipState = roundRectClipState;
    mDescription.hasRoundRectClip = roundRectClipState != nullptr;
    return *this;
}

////////////////////////////////////////////////////////////////////////////////
// Build
////////////////////////////////////////////////////////////////////////////////

void verify(const ProgramDescription& description, const Glop& glop) {
    if (glop.fill.texture.texture != nullptr) {
        LOG_ALWAYS_FATAL_IF(((description.hasTexture && description.hasExternalTexture)
                        || (!description.hasTexture
                                && !description.hasExternalTexture
                                && !description.useShadowAlphaInterp)
                        || ((glop.mesh.vertices.attribFlags & VertexAttribFlags::TextureCoord) == 0
                                && !description.useShadowAlphaInterp)),
                "Texture %p, hT%d, hET %d, attribFlags %x",
                glop.fill.texture.texture,
                description.hasTexture, description.hasExternalTexture,
                glop.mesh.vertices.attribFlags);
    } else {
        LOG_ALWAYS_FATAL_IF((description.hasTexture
                        || description.hasExternalTexture
                        || ((glop.mesh.vertices.attribFlags & VertexAttribFlags::TextureCoord) != 0)),
                "No texture, hT%d, hET %d, attribFlags %x",
                description.hasTexture, description.hasExternalTexture,
                glop.mesh.vertices.attribFlags);
    }

    if ((glop.mesh.vertices.attribFlags & VertexAttribFlags::Alpha)
            && glop.mesh.vertices.bufferObject) {
        LOG_ALWAYS_FATAL("VBO and alpha attributes are not currently compatible");
    }

    if (description.hasTextureTransform != (glop.fill.texture.textureTransform != nullptr)) {
        LOG_ALWAYS_FATAL("Texture transform incorrectly specified");
    }
}

void GlopBuilder::build() {
    REQUIRE_STAGES(kAllStages);
    if (mOutGlop->mesh.vertices.attribFlags & VertexAttribFlags::TextureCoord) {
        if (mOutGlop->fill.texture.target == GL_TEXTURE_2D) {
            mDescription.hasTexture = true;
        } else {
            mDescription.hasExternalTexture = true;
        }
    }

    mDescription.hasColors = mOutGlop->mesh.vertices.attribFlags & VertexAttribFlags::Color;
    mDescription.hasVertexAlpha = mOutGlop->mesh.vertices.attribFlags & VertexAttribFlags::Alpha;

    // Enable debug highlight when what we're about to draw is tested against
    // the stencil buffer and if stencil highlight debugging is on
    mDescription.hasDebugHighlight = !Properties::debugOverdraw
            && Properties::debugStencilClip == StencilClipDebug::ShowHighlight
            && mRenderState.stencil().isTestEnabled();

    // serialize shader info into ShaderData
    GLuint textureUnit = mOutGlop->fill.texture.texture ? 1 : 0;

    if (CC_LIKELY(!mShader)) {
        mOutGlop->fill.skiaShaderData.skiaShaderType = kNone_SkiaShaderType;
    } else {
        Matrix4 shaderMatrix;
        if (mOutGlop->transform.transformFlags & TransformFlags::MeshIgnoresCanvasTransform) {
            // canvas level transform was built into the modelView and geometry,
            // so the shader matrix must reverse this
            shaderMatrix.loadInverse(mOutGlop->transform.canvas);
            shaderMatrix.multiply(mOutGlop->transform.modelView);
        } else {
            shaderMatrix = mOutGlop->transform.modelView;
        }
        SkiaShader::store(mCaches, *mShader, shaderMatrix,
                &textureUnit, &mDescription, &(mOutGlop->fill.skiaShaderData));
    }

    // duplicates ProgramCache's definition of color uniform presence
    const bool singleColor = !mDescription.hasTexture
            && !mDescription.hasExternalTexture
            && !mDescription.hasGradient
            && !mDescription.hasBitmap;
    mOutGlop->fill.colorEnabled = mDescription.modulate || singleColor;

    verify(mDescription, *mOutGlop);

    // Final step: populate program and map bounds into render target space
    mOutGlop->fill.program = mCaches.programCache.get(mDescription);
#if !HWUI_NEW_OPS
    mOutGlop->transform.meshTransform().mapRect(mOutGlop->bounds);
#endif
}

void GlopBuilder::dump(const Glop& glop) {
    ALOGD("Glop Mesh");
    const Glop::Mesh& mesh = glop.mesh;
    ALOGD("    primitive mode: %d", mesh.primitiveMode);
    ALOGD("    indices: buffer obj %x, indices %p", mesh.indices.bufferObject, mesh.indices.indices);

    const Glop::Mesh::Vertices& vertices = glop.mesh.vertices;
    ALOGD("    vertices: buffer obj %x, flags %x, pos %p, tex %p, clr %p, stride %d",
            vertices.bufferObject, vertices.attribFlags,
            vertices.position, vertices.texCoord, vertices.color, vertices.stride);
    ALOGD("    element count: %d", mesh.elementCount);

    ALOGD("Glop Fill");
    const Glop::Fill& fill = glop.fill;
    ALOGD("    program %p", fill.program);
    if (fill.texture.texture) {
        ALOGD("    texture %p, target %d, filter %d, clamp %d",
                fill.texture.texture, fill.texture.target, fill.texture.filter, fill.texture.clamp);
        if (fill.texture.textureTransform) {
            fill.texture.textureTransform->dump("texture transform");
        }
    }
    ALOGD_IF(fill.colorEnabled, "    color (argb) %.2f %.2f %.2f %.2f",
            fill.color.a, fill.color.r, fill.color.g, fill.color.b);
    ALOGD_IF(fill.filterMode != ProgramDescription::ColorFilterMode::None,
            "    filterMode %d", (int)fill.filterMode);
    ALOGD_IF(fill.skiaShaderData.skiaShaderType, "    shader type %d",
            fill.skiaShaderData.skiaShaderType);

    ALOGD("Glop transform");
    glop.transform.modelView.dump("  model view");
    glop.transform.canvas.dump("  canvas");
    ALOGD_IF(glop.transform.transformFlags, "  transformFlags 0x%x", glop.transform.transformFlags);

    ALOGD_IF(glop.roundRectClipState, "Glop RRCS %p", glop.roundRectClipState);

    ALOGD("Glop blend %d %d", glop.blend.src, glop.blend.dst);
#if !HWUI_NEW_OPS
    ALOGD("Glop bounds " RECT_STRING, RECT_ARGS(glop.bounds));
#endif
}

} /* namespace uirenderer */
} /* namespace android */
