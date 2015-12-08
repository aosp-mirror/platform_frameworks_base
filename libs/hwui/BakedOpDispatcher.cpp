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
#include "Glop.h"
#include "GlopBuilder.h"
#include "PathTessellator.h"
#include "renderstate/OffscreenBufferPool.h"
#include "renderstate/RenderState.h"
#include "utils/GLUtils.h"
#include "VertexBuffer.h"

#include <algorithm>
#include <math.h>
#include <SkPaintDefaults.h>

namespace android {
namespace uirenderer {

static void storeTexturedRect(TextureVertex* vertices, const Rect& bounds, const Rect& texCoord) {
    vertices[0] = { bounds.left, bounds.top, texCoord.left, texCoord.top };
    vertices[1] = { bounds.right, bounds.top, texCoord.right, texCoord.top };
    vertices[2] = { bounds.left, bounds.bottom, texCoord.left, texCoord.bottom };
    vertices[3] = { bounds.right, bounds.bottom, texCoord.right, texCoord.bottom };
}

void BakedOpDispatcher::onMergedBitmapOps(BakedOpRenderer& renderer,
        const MergedBakedOpList& opList) {

    const BakedOpState& firstState = *(opList.states[0]);
    const SkBitmap* bitmap = (static_cast<const BitmapOp*>(opList.states[0]->op))->bitmap;

    AssetAtlas::Entry* entry = renderer.renderState().assetAtlas().getEntry(bitmap->pixelRef());
    Texture* texture = entry ? entry->texture : renderer.caches().textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    TextureVertex vertices[opList.count * 4];
    Rect texCoords(0, 0, 1, 1);
    if (entry) {
        entry->uvMapper.map(texCoords);
    }
    // init to non-empty, so we can safely expandtoCoverRect
    Rect totalBounds = firstState.computedState.clippedBounds;
    for (size_t i = 0; i < opList.count; i++) {
        const BakedOpState& state = *(opList.states[i]);
        TextureVertex* rectVerts = &vertices[i * 4];
        Rect opBounds = state.computedState.clippedBounds;
        if (CC_LIKELY(state.computedState.transform.isPureTranslate())) {
            // pure translate, so snap (same behavior as onBitmapOp)
            opBounds.snapToPixelBoundaries();
        }
        storeTexturedRect(rectVerts, opBounds, texCoords);
        renderer.dirtyRenderTarget(opBounds);

        totalBounds.expandToCover(opBounds);
    }

    const int textureFillFlags = (bitmap->colorType() == kAlpha_8_SkColorType)
            ? TextureFillFlags::IsAlphaMaskTexture : TextureFillFlags::None;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(firstState.roundRectClipState)
            .setMeshTexturedIndexedQuads(vertices, opList.count * 6)
            .setFillTexturePaint(*texture, textureFillFlags, firstState.op->paint, firstState.alpha)
            .setTransform(Matrix4::identity(), TransformFlags::None)
            .setModelViewOffsetRect(0, 0, totalBounds) // don't snap here, we snap per-quad above
            .build();
    renderer.renderGlop(nullptr, opList.clipSideFlags ? &opList.clip : nullptr, glop);
}

static void renderTextShadow(BakedOpRenderer& renderer, FontRenderer& fontRenderer,
        const TextOp& op, const BakedOpState& state) {
    renderer.caches().textureState().activateTexture(0);

    PaintUtils::TextShadow textShadow;
    if (!PaintUtils::getTextShadow(op.paint, &textShadow)) {
        LOG_ALWAYS_FATAL("failed to query shadow attributes");
    }

    renderer.caches().dropShadowCache.setFontRenderer(fontRenderer);
    ShadowTexture* texture = renderer.caches().dropShadowCache.get(
            op.paint, (const char*) op.glyphs,
            op.glyphCount, textShadow.radius, op.positions);
    // If the drop shadow exceeds the max texture size or couldn't be
    // allocated, skip drawing
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float sx = op.x - texture->left + textShadow.dx;
    const float sy = op.y - texture->top + textShadow.dy;

    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUnitQuad(nullptr)
            .setFillShadowTexturePaint(*texture, textShadow.color, *op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRect(Rect(sx, sy, sx + texture->width, sy + texture->height))
            .build();
    renderer.renderGlop(state, glop);
}

enum class TextRenderType {
    Defer,
    Flush
};

static void renderTextOp(BakedOpRenderer& renderer, const TextOp& op, const BakedOpState& state,
        const Rect* renderClip, TextRenderType renderType) {
    FontRenderer& fontRenderer = renderer.caches().fontRenderer.getFontRenderer();

    if (CC_UNLIKELY(PaintUtils::hasTextShadow(op.paint))) {
        fontRenderer.setFont(op.paint, SkMatrix::I());
        renderTextShadow(renderer, fontRenderer, op, state);
    }

    float x = op.x;
    float y = op.y;
    const Matrix4& transform = state.computedState.transform;
    const bool pureTranslate = transform.isPureTranslate();
    if (CC_LIKELY(pureTranslate)) {
        x = floorf(x + transform.getTranslateX() + 0.5f);
        y = floorf(y + transform.getTranslateY() + 0.5f);
        fontRenderer.setFont(op.paint, SkMatrix::I());
        fontRenderer.setTextureFiltering(false);
    } else if (CC_UNLIKELY(transform.isPerspective())) {
        fontRenderer.setFont(op.paint, SkMatrix::I());
        fontRenderer.setTextureFiltering(true);
    } else {
        // We only pass a partial transform to the font renderer. That partial
        // matrix defines how glyphs are rasterized. Typically we want glyphs
        // to be rasterized at their final size on screen, which means the partial
        // matrix needs to take the scale factor into account.
        // When a partial matrix is used to transform glyphs during rasterization,
        // the mesh is generated with the inverse transform (in the case of scale,
        // the mesh is generated at 1.0 / scale for instance.) This allows us to
        // apply the full transform matrix at draw time in the vertex shader.
        // Applying the full matrix in the shader is the easiest way to handle
        // rotation and perspective and allows us to always generated quads in the
        // font renderer which greatly simplifies the code, clipping in particular.
        float sx, sy;
        transform.decomposeScale(sx, sy);
        fontRenderer.setFont(op.paint, SkMatrix::MakeScale(
                roundf(std::max(1.0f, sx)),
                roundf(std::max(1.0f, sy))));
        fontRenderer.setTextureFiltering(true);
    }
    Rect layerBounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    int alpha = PaintUtils::getAlphaDirect(op.paint) * state.alpha;
    SkXfermode::Mode mode = PaintUtils::getXfermodeDirect(op.paint);
    TextDrawFunctor functor(&renderer, &state, renderClip,
            x, y, pureTranslate, alpha, mode, op.paint);

    bool forceFinish = (renderType == TextRenderType::Flush);
    bool mustDirtyRenderTarget = renderer.offscreenRenderTarget();
    const Rect* localOpClip = pureTranslate ? &state.computedState.clipRect : nullptr;
    fontRenderer.renderPosText(op.paint, localOpClip,
            (const char*) op.glyphs, op.glyphCount, x, y,
            op.positions, mustDirtyRenderTarget ? &layerBounds : nullptr, &functor, forceFinish);

    if (mustDirtyRenderTarget) {
        if (!pureTranslate) {
            transform.mapRect(layerBounds);
        }
        renderer.dirtyRenderTarget(layerBounds);
    }
}

void BakedOpDispatcher::onMergedTextOps(BakedOpRenderer& renderer,
        const MergedBakedOpList& opList) {
    const Rect* clip = opList.clipSideFlags ? &opList.clip : nullptr;
    for (size_t i = 0; i < opList.count; i++) {
        const BakedOpState& state = *(opList.states[i]);
        const TextOp& op = *(static_cast<const TextOp*>(state.op));
        TextRenderType renderType = (i + 1 == opList.count)
                ? TextRenderType::Flush : TextRenderType::Defer;
        renderTextOp(renderer, op, state, clip, renderType);
    }
}

void BakedOpDispatcher::onRenderNodeOp(BakedOpRenderer&, const RenderNodeOp&, const BakedOpState&) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpDispatcher::onBeginLayerOp(BakedOpRenderer&, const BeginLayerOp&, const BakedOpState&) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpDispatcher::onEndLayerOp(BakedOpRenderer&, const EndLayerOp&, const BakedOpState&) {
    LOG_ALWAYS_FATAL("unsupported operation");
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
        const int transformFlags = TransformFlags::OffsetByFudgeFactor;
        Glop glop;
        GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
                .setRoundRectClipState(state.roundRectClipState)
                .setMeshVertexBuffer(vertexBuffer, shadowInterp)
                .setFillPaint(paint, state.alpha)
                .setTransform(state.computedState.transform, transformFlags)
                .setModelViewOffsetRect(translateX, translateY, vertexBuffer.getBounds())
                .build();
        renderer.renderGlop(state, glop);
    }
}

static void renderConvexPath(BakedOpRenderer& renderer, const BakedOpState& state,
        const SkPath& path, const SkPaint& paint) {
    VertexBuffer vertexBuffer;
    // TODO: try clipping large paths to viewport
    PathTessellator::tessellatePath(path, &paint, state.computedState.transform, vertexBuffer);
    renderVertexBuffer(renderer, state, vertexBuffer, 0.0f, 0.0f, paint, 0);
}

static void renderPathTexture(BakedOpRenderer& renderer, const BakedOpState& state,
        PathTexture& texture, const RecordedOp& op) {
    Rect dest(texture.width, texture.height);
    dest.translate(texture.left + op.unmappedBounds.left - texture.offset,
            texture.top + op.unmappedBounds.top - texture.offset);
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUnitQuad(nullptr)
            .setFillPathTexturePaint(texture, *(op.paint), state.alpha)
            .setTransform(state.computedState.transform,  TransformFlags::None)
            .setModelViewMapUnitToRect(dest)
            .build();
    renderer.renderGlop(state, glop);
}

SkRect getBoundsOfFill(const RecordedOp& op) {
    SkRect bounds = op.unmappedBounds.toSkRect();
    if (op.paint->getStyle() == SkPaint::kStrokeAndFill_Style) {
        float outsetDistance = op.paint->getStrokeWidth() / 2;
        bounds.outset(outsetDistance, outsetDistance);
    }
    return bounds;
}

void BakedOpDispatcher::onArcOp(BakedOpRenderer& renderer, const ArcOp& op, const BakedOpState& state) {
    // TODO: support fills (accounting for concavity if useCenter && sweepAngle > 180)
    if (op.paint->getStyle() != SkPaint::kStroke_Style
            || op.paint->getPathEffect() != nullptr
            || op.useCenter) {
        PathTexture* texture = renderer.caches().pathCache.getArc(
                op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(),
                op.startAngle, op.sweepAngle, op.useCenter, op.paint);
        const AutoTexture holder(texture);
        if (CC_LIKELY(holder.texture)) {
            renderPathTexture(renderer, state, *texture, op);
        }
    } else {
        SkRect rect = getBoundsOfFill(op);
        SkPath path;
        if (op.useCenter) {
            path.moveTo(rect.centerX(), rect.centerY());
        }
        path.arcTo(rect, op.startAngle, op.sweepAngle, !op.useCenter);
        if (op.useCenter) {
            path.close();
        }
        renderConvexPath(renderer, state, path, *(op.paint));
    }
}

void BakedOpDispatcher::onBitmapOp(BakedOpRenderer& renderer, const BitmapOp& op, const BakedOpState& state) {
    Texture* texture = renderer.getTexture(op.bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const int textureFillFlags = (op.bitmap->colorType() == kAlpha_8_SkColorType)
            ? TextureFillFlags::IsAlphaMaskTexture : TextureFillFlags::None;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUnitQuad(texture->uvMapper)
            .setFillTexturePaint(*texture, textureFillFlags, op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectSnap(Rect(0, 0, texture->width, texture->height))
            .build();
    renderer.renderGlop(state, glop);
}

void BakedOpDispatcher::onLinesOp(BakedOpRenderer& renderer, const LinesOp& op, const BakedOpState& state) {
    VertexBuffer buffer;
    PathTessellator::tessellateLines(op.points, op.floatCount, op.paint,
            state.computedState.transform, buffer);
    int displayFlags = op.paint->isAntiAlias() ? 0 : VertexBufferRenderFlags::Offset;
    renderVertexBuffer(renderer, state, buffer, 0, 0, *(op.paint), displayFlags);
}

void BakedOpDispatcher::onOvalOp(BakedOpRenderer& renderer, const OvalOp& op, const BakedOpState& state) {
    if (op.paint->getPathEffect() != nullptr) {
        PathTexture* texture = renderer.caches().pathCache.getOval(
                op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(), op.paint);
        const AutoTexture holder(texture);
        if (CC_LIKELY(holder.texture)) {
            renderPathTexture(renderer, state, *texture, op);
        }
    } else {
        SkPath path;
        SkRect rect = getBoundsOfFill(op);
        path.addOval(rect);
        renderConvexPath(renderer, state, path, *(op.paint));
    }
}

void BakedOpDispatcher::onPathOp(BakedOpRenderer& renderer, const PathOp& op, const BakedOpState& state) {
    PathTexture* texture = renderer.caches().pathCache.get(op.path, op.paint);
    const AutoTexture holder(texture);
    if (CC_LIKELY(holder.texture)) {
        renderPathTexture(renderer, state, *texture, op);
    }
}

void BakedOpDispatcher::onPointsOp(BakedOpRenderer& renderer, const PointsOp& op, const BakedOpState& state) {
    VertexBuffer buffer;
    PathTessellator::tessellatePoints(op.points, op.floatCount, op.paint,
            state.computedState.transform, buffer);
    int displayFlags = op.paint->isAntiAlias() ? 0 : VertexBufferRenderFlags::Offset;
    renderVertexBuffer(renderer, state, buffer, 0, 0, *(op.paint), displayFlags);
}

// See SkPaintDefaults.h
#define SkPaintDefaults_MiterLimit SkIntToScalar(4)

void BakedOpDispatcher::onRectOp(BakedOpRenderer& renderer, const RectOp& op, const BakedOpState& state) {
    if (op.paint->getStyle() != SkPaint::kFill_Style) {
        // only fill + default miter is supported by drawConvexPath, since others must handle joins
        static_assert(SkPaintDefaults_MiterLimit == 4.0f, "Miter limit has changed");
        if (CC_UNLIKELY(op.paint->getPathEffect() != nullptr
                || op.paint->getStrokeJoin() != SkPaint::kMiter_Join
                || op.paint->getStrokeMiter() != SkPaintDefaults_MiterLimit)) {
             PathTexture* texture = renderer.caches().pathCache.getRect(
                     op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(), op.paint);
             const AutoTexture holder(texture);
             if (CC_LIKELY(holder.texture)) {
                 renderPathTexture(renderer, state, *texture, op);
             }
        } else {
            SkPath path;
            path.addRect(getBoundsOfFill(op));
            renderConvexPath(renderer, state, path, *(op.paint));
        }
    } else {
        if (op.paint->isAntiAlias() && !state.computedState.transform.isSimple()) {
            SkPath path;
            path.addRect(op.unmappedBounds.toSkRect());
            renderConvexPath(renderer, state, path, *(op.paint));
        } else {
            // render simple unit quad, no tessellation required
            Glop glop;
            GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
                    .setRoundRectClipState(state.roundRectClipState)
                    .setMeshUnitQuad()
                    .setFillPaint(*op.paint, state.alpha)
                    .setTransform(state.computedState.transform, TransformFlags::None)
                    .setModelViewMapUnitToRect(op.unmappedBounds)
                    .build();
            renderer.renderGlop(state, glop);
        }
    }
}

void BakedOpDispatcher::onRoundRectOp(BakedOpRenderer& renderer, const RoundRectOp& op, const BakedOpState& state) {
    if (op.paint->getPathEffect() != nullptr) {
        PathTexture* texture = renderer.caches().pathCache.getRoundRect(
                op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(),
                op.rx, op.ry, op.paint);
        const AutoTexture holder(texture);
        if (CC_LIKELY(holder.texture)) {
            renderPathTexture(renderer, state, *texture, op);
        }
    } else {
        const VertexBuffer* buffer = renderer.caches().tessellationCache.getRoundRect(
                state.computedState.transform, *(op.paint),
                op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(), op.rx, op.ry);
        renderVertexBuffer(renderer, state, *buffer,
                op.unmappedBounds.left, op.unmappedBounds.top, *(op.paint), 0);
    }
}

static void renderShadow(BakedOpRenderer& renderer, const BakedOpState& state, float casterAlpha,
        const VertexBuffer* ambientShadowVertexBuffer, const VertexBuffer* spotShadowVertexBuffer) {
    SkPaint paint;
    paint.setAntiAlias(true); // want to use AlphaVertex

    // The caller has made sure casterAlpha > 0.
    uint8_t ambientShadowAlpha = renderer.getLightInfo().ambientShadowAlpha;
    if (CC_UNLIKELY(Properties::overrideAmbientShadowStrength >= 0)) {
        ambientShadowAlpha = Properties::overrideAmbientShadowStrength;
    }
    if (ambientShadowVertexBuffer && ambientShadowAlpha > 0) {
        paint.setAlpha((uint8_t)(casterAlpha * ambientShadowAlpha));
        renderVertexBuffer(renderer, state, *ambientShadowVertexBuffer, 0, 0,
                paint, VertexBufferRenderFlags::ShadowInterp);
    }

    uint8_t spotShadowAlpha = renderer.getLightInfo().spotShadowAlpha;
    if (CC_UNLIKELY(Properties::overrideSpotShadowStrength >= 0)) {
        spotShadowAlpha = Properties::overrideSpotShadowStrength;
    }
    if (spotShadowVertexBuffer && spotShadowAlpha > 0) {
        paint.setAlpha((uint8_t)(casterAlpha * spotShadowAlpha));
        renderVertexBuffer(renderer, state, *spotShadowVertexBuffer, 0, 0,
                paint, VertexBufferRenderFlags::ShadowInterp);
    }
}

void BakedOpDispatcher::onShadowOp(BakedOpRenderer& renderer, const ShadowOp& op, const BakedOpState& state) {
    TessellationCache::vertexBuffer_pair_t buffers;
    renderer.caches().tessellationCache.getShadowBuffers(&state.computedState.transform,
            op.localClipRect, op.casterAlpha >= 1.0f, op.casterPath,
            &op.shadowMatrixXY, &op.shadowMatrixZ,
            op.lightCenter, renderer.getLightInfo().lightRadius,
            buffers);

    renderShadow(renderer, state, op.casterAlpha, buffers.first, buffers.second);
}

void BakedOpDispatcher::onSimpleRectsOp(BakedOpRenderer& renderer, const SimpleRectsOp& op, const BakedOpState& state) {
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

void BakedOpDispatcher::onTextOp(BakedOpRenderer& renderer, const TextOp& op, const BakedOpState& state) {
    const Rect* clip = state.computedState.clipSideFlags ? &state.computedState.clipRect : nullptr;
    renderTextOp(renderer, op, state, clip, TextRenderType::Flush);
}

void BakedOpDispatcher::onLayerOp(BakedOpRenderer& renderer, const LayerOp& op, const BakedOpState& state) {
    OffscreenBuffer* buffer = *op.layerHandle;

    float layerAlpha = op.alpha * state.alpha;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedIndexedVbo(buffer->vbo, buffer->elementCount)
            .setFillLayer(buffer->texture, op.colorFilter, layerAlpha, op.mode, Blend::ModeOrderSwap::NoSwap)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewOffsetRectSnap(op.unmappedBounds.left, op.unmappedBounds.top,
                    Rect(op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight()))
            .build();
    renderer.renderGlop(state, glop);

    if (op.destroy) {
        renderer.renderState().layerPool().putOrDelete(buffer);
    }
}

} // namespace uirenderer
} // namespace android
