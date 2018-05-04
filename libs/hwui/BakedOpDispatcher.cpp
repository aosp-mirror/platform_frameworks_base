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

#include "BakedOpDispatcher.h"

#include "BakedOpRenderer.h"
#include "Caches.h"
#include "DeferredLayerUpdater.h"
#include "Glop.h"
#include "GlopBuilder.h"
#include "Patch.h"
#include "PathTessellator.h"
#include "VertexBuffer.h"
#include "renderstate/OffscreenBufferPool.h"
#include "renderstate/RenderState.h"
#include "utils/GLUtils.h"

#include <SkPaintDefaults.h>
#include <SkPathOps.h>
#include <math.h>
#include <algorithm>

namespace android {
namespace uirenderer {

void BakedOpDispatcher::onMergedBitmapOps(BakedOpRenderer& renderer,
                                          const MergedBakedOpList& opList) {
    // DEAD CODE
}

void BakedOpDispatcher::onMergedPatchOps(BakedOpRenderer& renderer,
                                         const MergedBakedOpList& opList) {
    // DEAD CODE
}

static void renderTextShadow(BakedOpRenderer& renderer, const TextOp& op,
                             const BakedOpState& textOpState) {
    // DEAD CODE
}

enum class TextRenderType { Defer, Flush };

static void renderText(BakedOpRenderer& renderer, const TextOp& op, const BakedOpState& state,
                       const ClipBase* renderClip, TextRenderType renderType) {
    // DEAD CODE
}

void BakedOpDispatcher::onMergedTextOps(BakedOpRenderer& renderer,
                                        const MergedBakedOpList& opList) {
    for (size_t i = 0; i < opList.count; i++) {
        const BakedOpState& state = *(opList.states[i]);
        const TextOp& op = *(static_cast<const TextOp*>(state.op));
        renderTextShadow(renderer, op, state);
    }

    ClipRect renderTargetClip(opList.clip);
    const ClipBase* clip = opList.clipSideFlags ? &renderTargetClip : nullptr;
    for (size_t i = 0; i < opList.count; i++) {
        const BakedOpState& state = *(opList.states[i]);
        const TextOp& op = *(static_cast<const TextOp*>(state.op));
        TextRenderType renderType =
                (i + 1 == opList.count) ? TextRenderType::Flush : TextRenderType::Defer;
        renderText(renderer, op, state, clip, renderType);
    }
}

namespace VertexBufferRenderFlags {
enum {
    Offset = 0x1,
    ShadowInterp = 0x2,
};
}

static void renderVertexBuffer(BakedOpRenderer& renderer, const BakedOpState& state,
                               const VertexBuffer& vertexBuffer, float translateX, float translateY,
                               const SkPaint& paint, int vertexBufferRenderFlags) {
    if (CC_LIKELY(vertexBuffer.getVertexCount())) {
        bool shadowInterp = vertexBufferRenderFlags & VertexBufferRenderFlags::ShadowInterp;
        const int transformFlags = vertexBufferRenderFlags & VertexBufferRenderFlags::Offset
                                           ? TransformFlags::OffsetByFudgeFactor
                                           : 0;

        Glop glop;
        GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
                .setRoundRectClipState(state.roundRectClipState)
                .setMeshVertexBuffer(vertexBuffer)
                .setFillPaint(paint, state.alpha, shadowInterp)
                .setTransform(state.computedState.transform, transformFlags)
                .setModelViewOffsetRect(translateX, translateY, vertexBuffer.getBounds())
                .build();
        renderer.renderGlop(state, glop);
    }
}

SkRect getBoundsOfFill(const RecordedOp& op) {
    SkRect bounds = op.unmappedBounds.toSkRect();
    if (op.paint->getStyle() == SkPaint::kStrokeAndFill_Style) {
        float outsetDistance = op.paint->getStrokeWidth() / 2;
        bounds.outset(outsetDistance, outsetDistance);
    }
    return bounds;
}

void BakedOpDispatcher::onArcOp(BakedOpRenderer& renderer, const ArcOp& op,
                                const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onBitmapOp(BakedOpRenderer& renderer, const BitmapOp& op,
                                   const BakedOpState& state) {
    Texture* texture = renderer.getTexture(op.bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const int textureFillFlags = (op.bitmap->colorType() == kAlpha_8_SkColorType)
                                         ? TextureFillFlags::IsAlphaMaskTexture
                                         : TextureFillFlags::None;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUnitQuad(texture->uvMapper)
            .setFillTexturePaint(*texture, textureFillFlags, op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectSnap(Rect(texture->width(), texture->height()))
            .build();
    renderer.renderGlop(state, glop);
}

void BakedOpDispatcher::onBitmapMeshOp(BakedOpRenderer& renderer, const BitmapMeshOp& op,
                                       const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onBitmapRectOp(BakedOpRenderer& renderer, const BitmapRectOp& op,
                                       const BakedOpState& state) {
    Texture* texture = renderer.getTexture(op.bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    Rect uv(std::max(0.0f, op.src.left / texture->width()),
            std::max(0.0f, op.src.top / texture->height()),
            std::min(1.0f, op.src.right / texture->width()),
            std::min(1.0f, op.src.bottom / texture->height()));

    const int textureFillFlags = (op.bitmap->colorType() == kAlpha_8_SkColorType)
                                         ? TextureFillFlags::IsAlphaMaskTexture
                                         : TextureFillFlags::None;
    const bool tryToSnap = MathUtils::areEqual(op.src.getWidth(), op.unmappedBounds.getWidth()) &&
                           MathUtils::areEqual(op.src.getHeight(), op.unmappedBounds.getHeight());
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUvQuad(texture->uvMapper, uv)
            .setFillTexturePaint(*texture, textureFillFlags, op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectOptionalSnap(tryToSnap, op.unmappedBounds)
            .build();
    renderer.renderGlop(state, glop);
}

void BakedOpDispatcher::onColorOp(BakedOpRenderer& renderer, const ColorOp& op,
                                  const BakedOpState& state) {
    SkPaint paint;
    paint.setColor(op.color);
    paint.setBlendMode(op.mode);

    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshUnitQuad()
            .setFillPaint(paint, state.alpha)
            .setTransform(Matrix4::identity(), TransformFlags::None)
            .setModelViewMapUnitToRect(state.computedState.clipState->rect)
            .build();
    renderer.renderGlop(state, glop);
}

void BakedOpDispatcher::onFunctorOp(BakedOpRenderer& renderer, const FunctorOp& op,
                                    const BakedOpState& state) {
    renderer.renderFunctor(op, state);
}

void BakedOpDispatcher::onLinesOp(BakedOpRenderer& renderer, const LinesOp& op,
                                  const BakedOpState& state) {
    VertexBuffer buffer;
    PathTessellator::tessellateLines(op.points, op.floatCount, op.paint,
                                     state.computedState.transform, buffer);
    int displayFlags = op.paint->isAntiAlias() ? 0 : VertexBufferRenderFlags::Offset;
    renderVertexBuffer(renderer, state, buffer, 0, 0, *(op.paint), displayFlags);
}

void BakedOpDispatcher::onOvalOp(BakedOpRenderer& renderer, const OvalOp& op,
                                 const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onPatchOp(BakedOpRenderer& renderer, const PatchOp& op,
                                  const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onPathOp(BakedOpRenderer& renderer, const PathOp& op,
                                 const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onPointsOp(BakedOpRenderer& renderer, const PointsOp& op,
                                   const BakedOpState& state) {
    VertexBuffer buffer;
    PathTessellator::tessellatePoints(op.points, op.floatCount, op.paint,
                                      state.computedState.transform, buffer);
    int displayFlags = op.paint->isAntiAlias() ? 0 : VertexBufferRenderFlags::Offset;
    renderVertexBuffer(renderer, state, buffer, 0, 0, *(op.paint), displayFlags);
}

// See SkPaintDefaults.h
#define SkPaintDefaults_MiterLimit SkIntToScalar(4)

void BakedOpDispatcher::onRectOp(BakedOpRenderer& renderer, const RectOp& op,
                                 const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onRoundRectOp(BakedOpRenderer& renderer, const RoundRectOp& op,
                                      const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onSimpleRectsOp(BakedOpRenderer& renderer, const SimpleRectsOp& op,
                                        const BakedOpState& state) {
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshIndexedQuads(&op.vertices[0], op.vertexCount / 4)
            .setFillPaint(*op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewOffsetRect(0, 0, op.unmappedBounds)
            .build();
    renderer.renderGlop(state, glop);
}

void BakedOpDispatcher::onTextOp(BakedOpRenderer& renderer, const TextOp& op,
                                 const BakedOpState& state) {
    renderTextShadow(renderer, op, state);
    renderText(renderer, op, state, state.computedState.getClipIfNeeded(), TextRenderType::Flush);
}

void BakedOpDispatcher::onTextOnPathOp(BakedOpRenderer& renderer, const TextOnPathOp& op,
                                       const BakedOpState& state) {
    // DEAD CODE
}

void BakedOpDispatcher::onTextureLayerOp(BakedOpRenderer& renderer, const TextureLayerOp& op,
                                         const BakedOpState& state) {
    GlLayer* layer = static_cast<GlLayer*>(op.layerHandle->backingLayer());
    if (!layer) {
        return;
    }
    const bool tryToSnap = layer->getForceFilter();
    float alpha = (layer->getAlpha() / 255.0f) * state.alpha;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUvQuad(nullptr, Rect(0, 1, 1, 0))  // TODO: simplify with VBO
            .setFillTextureLayer(*(layer), alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectOptionalSnap(tryToSnap,
                                                   Rect(layer->getWidth(), layer->getHeight()))
            .build();
    renderer.renderGlop(state, glop);
}

void renderRectForLayer(BakedOpRenderer& renderer, const LayerOp& op, const BakedOpState& state,
                        int color, SkBlendMode mode, SkColorFilter* colorFilter) {
    SkPaint paint;
    paint.setColor(color);
    paint.setBlendMode(mode);
    paint.setColorFilter(sk_ref_sp(colorFilter));
    RectOp rectOp(op.unmappedBounds, op.localMatrix, op.localClip, &paint);
    BakedOpDispatcher::onRectOp(renderer, rectOp, state);
}

void BakedOpDispatcher::onLayerOp(BakedOpRenderer& renderer, const LayerOp& op,
                                  const BakedOpState& state) {
    // Note that we don't use op->paint in this function - it's never set on a LayerOp
    OffscreenBuffer* buffer = *op.layerHandle;

    if (CC_UNLIKELY(!buffer)) return;

    float layerAlpha = op.alpha * state.alpha;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedIndexedVbo(buffer->vbo, buffer->elementCount)
            .setFillLayer(buffer->texture, op.colorFilter, layerAlpha, op.mode,
                          Blend::ModeOrderSwap::NoSwap)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewOffsetRectSnap(
                    op.unmappedBounds.left, op.unmappedBounds.top,
                    Rect(op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight()))
            .build();
    renderer.renderGlop(state, glop);

    if (!buffer->hasRenderedSinceRepaint) {
        buffer->hasRenderedSinceRepaint = true;
        if (CC_UNLIKELY(Properties::debugLayersUpdates)) {
            // render debug layer highlight
            renderRectForLayer(renderer, op, state, 0x7f00ff00, SkBlendMode::kSrcOver, nullptr);
        } else if (CC_UNLIKELY(Properties::debugOverdraw)) {
            // render transparent to increment overdraw for repaint area
            renderRectForLayer(renderer, op, state, SK_ColorTRANSPARENT, SkBlendMode::kSrcOver,
                               nullptr);
        }
    }
}

void BakedOpDispatcher::onCopyToLayerOp(BakedOpRenderer& renderer, const CopyToLayerOp& op,
                                        const BakedOpState& state) {
    LOG_ALWAYS_FATAL_IF(*(op.layerHandle) != nullptr, "layer already exists!");
    *(op.layerHandle) = renderer.copyToLayer(state.computedState.clippedBounds);
    LOG_ALWAYS_FATAL_IF(*op.layerHandle == nullptr, "layer copy failed");
}

void BakedOpDispatcher::onCopyFromLayerOp(BakedOpRenderer& renderer, const CopyFromLayerOp& op,
                                          const BakedOpState& state) {
    LOG_ALWAYS_FATAL_IF(*op.layerHandle == nullptr, "no layer to draw underneath!");
    if (!state.computedState.clippedBounds.isEmpty()) {
        if (op.paint && op.paint->getAlpha() < 255) {
            SkPaint layerPaint;
            layerPaint.setAlpha(op.paint->getAlpha());
            layerPaint.setBlendMode(SkBlendMode::kDstIn);
            layerPaint.setColorFilter(sk_ref_sp(op.paint->getColorFilter()));
            RectOp rectOp(state.computedState.clippedBounds, Matrix4::identity(), nullptr,
                          &layerPaint);
            BakedOpDispatcher::onRectOp(renderer, rectOp, state);
        }

        OffscreenBuffer& layer = **(op.layerHandle);
        auto mode = PaintUtils::getBlendModeDirect(op.paint);
        Glop glop;
        GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
                .setRoundRectClipState(state.roundRectClipState)
                .setMeshTexturedUvQuad(nullptr, layer.getTextureCoordinates())
                .setFillLayer(layer.texture, nullptr, 1.0f, mode, Blend::ModeOrderSwap::Swap)
                .setTransform(state.computedState.transform, TransformFlags::None)
                .setModelViewMapUnitToRect(state.computedState.clippedBounds)
                .build();
        renderer.renderGlop(state, glop);
    }
    renderer.renderState().layerPool().putOrDelete(*op.layerHandle);
}

}  // namespace uirenderer
}  // namespace android
