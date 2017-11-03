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

static void storeTexturedRect(TextureVertex* vertices, const Rect& bounds) {
    vertices[0] = {bounds.left, bounds.top, 0, 0};
    vertices[1] = {bounds.right, bounds.top, 1, 0};
    vertices[2] = {bounds.left, bounds.bottom, 0, 1};
    vertices[3] = {bounds.right, bounds.bottom, 1, 1};
}

void BakedOpDispatcher::onMergedBitmapOps(BakedOpRenderer& renderer,
                                          const MergedBakedOpList& opList) {
    const BakedOpState& firstState = *(opList.states[0]);
    Bitmap* bitmap = (static_cast<const BitmapOp*>(opList.states[0]->op))->bitmap;

    Texture* texture = renderer.caches().textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    TextureVertex vertices[opList.count * 4];
    for (size_t i = 0; i < opList.count; i++) {
        const BakedOpState& state = *(opList.states[i]);
        TextureVertex* rectVerts = &vertices[i * 4];

        // calculate unclipped bounds, since they'll determine texture coordinates
        Rect opBounds = state.op->unmappedBounds;
        state.computedState.transform.mapRect(opBounds);
        if (CC_LIKELY(state.computedState.transform.isPureTranslate())) {
            // pure translate, so snap (same behavior as onBitmapOp)
            opBounds.snapToPixelBoundaries();
        }
        storeTexturedRect(rectVerts, opBounds);
        renderer.dirtyRenderTarget(opBounds);
    }

    const int textureFillFlags = (bitmap->colorType() == kAlpha_8_SkColorType)
                                         ? TextureFillFlags::IsAlphaMaskTexture
                                         : TextureFillFlags::None;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(firstState.roundRectClipState)
            .setMeshTexturedIndexedQuads(vertices, opList.count * 6)
            .setFillTexturePaint(*texture, textureFillFlags, firstState.op->paint, firstState.alpha)
            .setTransform(Matrix4::identity(), TransformFlags::None)
            .setModelViewIdentityEmptyBounds()
            .build();
    ClipRect renderTargetClip(opList.clip);
    const ClipBase* clip = opList.clipSideFlags ? &renderTargetClip : nullptr;
    renderer.renderGlop(nullptr, clip, glop);
}

void BakedOpDispatcher::onMergedPatchOps(BakedOpRenderer& renderer,
                                         const MergedBakedOpList& opList) {
    const PatchOp& firstOp = *(static_cast<const PatchOp*>(opList.states[0]->op));
    const BakedOpState& firstState = *(opList.states[0]);

    // Batches will usually contain a small number of items so it's
    // worth performing a first iteration to count the exact number
    // of vertices we need in the new mesh
    uint32_t totalVertices = 0;

    for (size_t i = 0; i < opList.count; i++) {
        const PatchOp& op = *(static_cast<const PatchOp*>(opList.states[i]->op));

        // TODO: cache mesh lookups
        const Patch* opMesh = renderer.caches().patchCache.get(
                op.bitmap->width(), op.bitmap->height(), op.unmappedBounds.getWidth(),
                op.unmappedBounds.getHeight(), op.patch);
        totalVertices += opMesh->verticesCount;
    }

    const bool dirtyRenderTarget = renderer.offscreenRenderTarget();

    uint32_t indexCount = 0;

    TextureVertex vertices[totalVertices];
    TextureVertex* vertex = &vertices[0];
    // Create a mesh that contains the transformed vertices for all the
    // 9-patch objects that are part of the batch. Note that onDefer()
    // enforces ops drawn by this function to have a pure translate or
    // identity matrix
    for (size_t i = 0; i < opList.count; i++) {
        const PatchOp& op = *(static_cast<const PatchOp*>(opList.states[i]->op));
        const BakedOpState& state = *opList.states[i];

        // TODO: cache mesh lookups
        const Patch* opMesh = renderer.caches().patchCache.get(
                op.bitmap->width(), op.bitmap->height(), op.unmappedBounds.getWidth(),
                op.unmappedBounds.getHeight(), op.patch);

        uint32_t vertexCount = opMesh->verticesCount;
        if (vertexCount == 0) continue;

        // We use the bounds to know where to translate our vertices
        // Using patchOp->state.mBounds wouldn't work because these
        // bounds are clipped
        const float tx = floorf(state.computedState.transform.getTranslateX() +
                                op.unmappedBounds.left + 0.5f);
        const float ty = floorf(state.computedState.transform.getTranslateY() +
                                op.unmappedBounds.top + 0.5f);

        // Copy & transform all the vertices for the current operation
        TextureVertex* opVertices = opMesh->vertices.get();
        for (uint32_t j = 0; j < vertexCount; j++, opVertices++) {
            TextureVertex::set(vertex++, opVertices->x + tx, opVertices->y + ty, opVertices->u,
                               opVertices->v);
        }

        // Dirty the current layer if possible. When the 9-patch does not
        // contain empty quads we can take a shortcut and simply set the
        // dirty rect to the object's bounds.
        if (dirtyRenderTarget) {
            if (!opMesh->hasEmptyQuads) {
                renderer.dirtyRenderTarget(Rect(tx, ty, tx + op.unmappedBounds.getWidth(),
                                                ty + op.unmappedBounds.getHeight()));
            } else {
                const size_t count = opMesh->quads.size();
                for (size_t i = 0; i < count; i++) {
                    const Rect& quadBounds = opMesh->quads[i];
                    const float x = tx + quadBounds.left;
                    const float y = ty + quadBounds.top;
                    renderer.dirtyRenderTarget(
                            Rect(x, y, x + quadBounds.getWidth(), y + quadBounds.getHeight()));
                }
            }
        }

        indexCount += opMesh->indexCount;
    }

    Texture* texture = renderer.caches().textureCache.get(firstOp.bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    // 9 patches are built for stretching - always filter
    int textureFillFlags = TextureFillFlags::ForceFilter;
    if (firstOp.bitmap->colorType() == kAlpha_8_SkColorType) {
        textureFillFlags |= TextureFillFlags::IsAlphaMaskTexture;
    }
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(firstState.roundRectClipState)
            .setMeshTexturedIndexedQuads(vertices, indexCount)
            .setFillTexturePaint(*texture, textureFillFlags, firstOp.paint, firstState.alpha)
            .setTransform(Matrix4::identity(), TransformFlags::None)
            .setModelViewIdentityEmptyBounds()
            .build();
    ClipRect renderTargetClip(opList.clip);
    const ClipBase* clip = opList.clipSideFlags ? &renderTargetClip : nullptr;
    renderer.renderGlop(nullptr, clip, glop);
}

static void renderTextShadow(BakedOpRenderer& renderer, const TextOp& op,
                             const BakedOpState& textOpState) {
    if (CC_LIKELY(!PaintUtils::hasTextShadow(op.paint))) return;

    FontRenderer& fontRenderer = renderer.caches().fontRenderer.getFontRenderer();
    fontRenderer.setFont(op.paint, SkMatrix::I());
    renderer.caches().textureState().activateTexture(0);

    PaintUtils::TextShadow textShadow;
    if (!PaintUtils::getTextShadow(op.paint, &textShadow)) {
        LOG_ALWAYS_FATAL("failed to query shadow attributes");
    }

    renderer.caches().dropShadowCache.setFontRenderer(fontRenderer);
    ShadowTexture* texture = renderer.caches().dropShadowCache.get(
            op.paint, op.glyphs, op.glyphCount, textShadow.radius, op.positions);
    // If the drop shadow exceeds the max texture size or couldn't be
    // allocated, skip drawing
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float sx = op.x - texture->left + textShadow.dx;
    const float sy = op.y - texture->top + textShadow.dy;

    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(textOpState.roundRectClipState)
            .setMeshTexturedUnitQuad(nullptr)
            .setFillShadowTexturePaint(*texture, textShadow.color, *op.paint, textOpState.alpha)
            .setTransform(textOpState.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRect(Rect(sx, sy, sx + texture->width(), sy + texture->height()))
            .build();

    // Compute damage bounds and clip (since may differ from those in textOpState).
    // Bounds should be same as text op, but with dx/dy offset and radius outset
    // applied in local space.
    auto& transform = textOpState.computedState.transform;
    Rect shadowBounds = op.unmappedBounds;  // STROKE
    const bool expandForStroke = op.paint->getStyle() != SkPaint::kFill_Style;
    if (expandForStroke) {
        shadowBounds.outset(op.paint->getStrokeWidth() * 0.5f);
    }
    shadowBounds.translate(textShadow.dx, textShadow.dy);
    shadowBounds.outset(textShadow.radius, textShadow.radius);
    transform.mapRect(shadowBounds);
    if (CC_UNLIKELY(expandForStroke &&
                    (!transform.isPureTranslate() || op.paint->getStrokeWidth() < 1.0f))) {
        shadowBounds.outset(0.5f);
    }

    auto clipState = textOpState.computedState.clipState;
    if (clipState->mode != ClipMode::Rectangle || !clipState->rect.contains(shadowBounds)) {
        // need clip, so pass it and clip bounds
        shadowBounds.doIntersect(clipState->rect);
    } else {
        // don't need clip, ignore
        clipState = nullptr;
    }

    renderer.renderGlop(&shadowBounds, clipState, glop);
}

enum class TextRenderType { Defer, Flush };

static void renderText(BakedOpRenderer& renderer, const TextOp& op, const BakedOpState& state,
                       const ClipBase* renderClip, TextRenderType renderType) {
    FontRenderer& fontRenderer = renderer.caches().fontRenderer.getFontRenderer();
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
        fontRenderer.setFont(op.paint, SkMatrix::MakeScale(roundf(std::max(1.0f, sx)),
                                                           roundf(std::max(1.0f, sy))));
        fontRenderer.setTextureFiltering(true);
    }
    Rect layerBounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    int alpha = PaintUtils::getAlphaDirect(op.paint) * state.alpha;
    SkBlendMode mode = PaintUtils::getBlendModeDirect(op.paint);
    TextDrawFunctor functor(&renderer, &state, renderClip, x, y, pureTranslate, alpha, mode,
                            op.paint);

    bool forceFinish = (renderType == TextRenderType::Flush);
    bool mustDirtyRenderTarget = renderer.offscreenRenderTarget();
    const Rect* localOpClip = pureTranslate ? &state.computedState.clipRect() : nullptr;
    fontRenderer.renderPosText(op.paint, localOpClip, op.glyphs, op.glyphCount, x, y, op.positions,
                               mustDirtyRenderTarget ? &layerBounds : nullptr, &functor,
                               forceFinish);

    if (mustDirtyRenderTarget) {
        if (!pureTranslate) {
            transform.mapRect(layerBounds);
        }
        renderer.dirtyRenderTarget(layerBounds);
    }
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

static void renderConvexPath(BakedOpRenderer& renderer, const BakedOpState& state,
                             const SkPath& path, const SkPaint& paint) {
    VertexBuffer vertexBuffer;
    // TODO: try clipping large paths to viewport
    PathTessellator::tessellatePath(path, &paint, state.computedState.transform, vertexBuffer);
    renderVertexBuffer(renderer, state, vertexBuffer, 0.0f, 0.0f, paint, 0);
}

static void renderPathTexture(BakedOpRenderer& renderer, const BakedOpState& state, float xOffset,
                              float yOffset, PathTexture& texture, const SkPaint& paint) {
    Rect dest(texture.width(), texture.height());
    dest.translate(xOffset + texture.left - texture.offset, yOffset + texture.top - texture.offset);
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUnitQuad(nullptr)
            .setFillPathTexturePaint(texture, paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
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

void BakedOpDispatcher::onArcOp(BakedOpRenderer& renderer, const ArcOp& op,
                                const BakedOpState& state) {
    // TODO: support fills (accounting for concavity if useCenter && sweepAngle > 180)
    if (op.paint->getStyle() != SkPaint::kStroke_Style || op.paint->getPathEffect() != nullptr ||
        op.useCenter) {
        PathTexture* texture = renderer.caches().pathCache.getArc(
                op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(), op.startAngle,
                op.sweepAngle, op.useCenter, op.paint);
        const AutoTexture holder(texture);
        if (CC_LIKELY(holder.texture)) {
            renderPathTexture(renderer, state, op.unmappedBounds.left, op.unmappedBounds.top,
                              *texture, *(op.paint));
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
    Texture* texture = renderer.caches().textureCache.get(op.bitmap);
    if (!texture) {
        return;
    }
    const AutoTexture autoCleanup(texture);

    const uint32_t elementCount = op.meshWidth * op.meshHeight * 6;

    std::unique_ptr<ColorTextureVertex[]> mesh(new ColorTextureVertex[elementCount]);
    ColorTextureVertex* vertex = &mesh[0];

    const int* colors = op.colors;
    std::unique_ptr<int[]> tempColors;
    if (!colors) {
        uint32_t colorsCount = (op.meshWidth + 1) * (op.meshHeight + 1);
        tempColors.reset(new int[colorsCount]);
        memset(tempColors.get(), 0xff, colorsCount * sizeof(int));
        colors = tempColors.get();
    }

    for (int32_t y = 0; y < op.meshHeight; y++) {
        for (int32_t x = 0; x < op.meshWidth; x++) {
            uint32_t i = (y * (op.meshWidth + 1) + x) * 2;

            float u1 = float(x) / op.meshWidth;
            float u2 = float(x + 1) / op.meshWidth;
            float v1 = float(y) / op.meshHeight;
            float v2 = float(y + 1) / op.meshHeight;

            int ax = i + (op.meshWidth + 1) * 2;
            int ay = ax + 1;
            int bx = i;
            int by = bx + 1;
            int cx = i + 2;
            int cy = cx + 1;
            int dx = i + (op.meshWidth + 1) * 2 + 2;
            int dy = dx + 1;

            const float* vertices = op.vertices;
            ColorTextureVertex::set(vertex++, vertices[dx], vertices[dy], u2, v2, colors[dx / 2]);
            ColorTextureVertex::set(vertex++, vertices[ax], vertices[ay], u1, v2, colors[ax / 2]);
            ColorTextureVertex::set(vertex++, vertices[bx], vertices[by], u1, v1, colors[bx / 2]);

            ColorTextureVertex::set(vertex++, vertices[dx], vertices[dy], u2, v2, colors[dx / 2]);
            ColorTextureVertex::set(vertex++, vertices[bx], vertices[by], u1, v1, colors[bx / 2]);
            ColorTextureVertex::set(vertex++, vertices[cx], vertices[cy], u2, v1, colors[cx / 2]);
        }
    }

    /*
     * TODO: handle alpha_8 textures correctly by applying paint color, but *not*
     * shader in that case to mimic the behavior in SkiaCanvas::drawBitmapMesh.
     */
    const int textureFillFlags = TextureFillFlags::None;
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshColoredTexturedMesh(mesh.get(), elementCount)
            .setFillTexturePaint(*texture, textureFillFlags, op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewOffsetRect(0, 0, op.unmappedBounds)
            .build();
    renderer.renderGlop(state, glop);
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
    if (op.paint->getPathEffect() != nullptr) {
        PathTexture* texture = renderer.caches().pathCache.getOval(
                op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(), op.paint);
        const AutoTexture holder(texture);
        if (CC_LIKELY(holder.texture)) {
            renderPathTexture(renderer, state, op.unmappedBounds.left, op.unmappedBounds.top,
                              *texture, *(op.paint));
        }
    } else {
        SkPath path;
        SkRect rect = getBoundsOfFill(op);
        path.addOval(rect);

        if (state.computedState.localProjectionPathMask != nullptr) {
            // Mask the ripple path by the local space projection mask in local space.
            // Note that this can create CCW paths.
            Op(path, *state.computedState.localProjectionPathMask, kIntersect_SkPathOp, &path);
        }
        renderConvexPath(renderer, state, path, *(op.paint));
    }
}

void BakedOpDispatcher::onPatchOp(BakedOpRenderer& renderer, const PatchOp& op,
                                  const BakedOpState& state) {
    // 9 patches are built for stretching - always filter
    int textureFillFlags = TextureFillFlags::ForceFilter;
    if (op.bitmap->colorType() == kAlpha_8_SkColorType) {
        textureFillFlags |= TextureFillFlags::IsAlphaMaskTexture;
    }

    // TODO: avoid redoing the below work each frame:
    const Patch* mesh = renderer.caches().patchCache.get(op.bitmap->width(), op.bitmap->height(),
                                                         op.unmappedBounds.getWidth(),
                                                         op.unmappedBounds.getHeight(), op.patch);

    Texture* texture = renderer.caches().textureCache.get(op.bitmap);
    if (CC_LIKELY(texture)) {
        const AutoTexture autoCleanup(texture);
        Glop glop;
        GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
                .setRoundRectClipState(state.roundRectClipState)
                .setMeshPatchQuads(*mesh)
                .setFillTexturePaint(*texture, textureFillFlags, op.paint, state.alpha)
                .setTransform(state.computedState.transform, TransformFlags::None)
                .setModelViewOffsetRectSnap(
                        op.unmappedBounds.left, op.unmappedBounds.top,
                        Rect(op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight()))
                .build();
        renderer.renderGlop(state, glop);
    }
}

void BakedOpDispatcher::onPathOp(BakedOpRenderer& renderer, const PathOp& op,
                                 const BakedOpState& state) {
    PathTexture* texture = renderer.caches().pathCache.get(op.path, op.paint);
    const AutoTexture holder(texture);
    if (CC_LIKELY(holder.texture)) {
        // Unlike other callers to renderPathTexture, no offsets are used because PathOp doesn't
        // have any translate built in, other than what's in the SkPath itself
        renderPathTexture(renderer, state, 0, 0, *texture, *(op.paint));
    }
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
    if (op.paint->getStyle() != SkPaint::kFill_Style) {
        // only fill + default miter is supported by drawConvexPath, since others must handle joins
        static_assert(SkPaintDefaults_MiterLimit == 4.0f, "Miter limit has changed");
        if (CC_UNLIKELY(op.paint->getPathEffect() != nullptr ||
                        op.paint->getStrokeJoin() != SkPaint::kMiter_Join ||
                        op.paint->getStrokeMiter() != SkPaintDefaults_MiterLimit)) {
            PathTexture* texture = renderer.caches().pathCache.getRect(
                    op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(), op.paint);
            const AutoTexture holder(texture);
            if (CC_LIKELY(holder.texture)) {
                renderPathTexture(renderer, state, op.unmappedBounds.left, op.unmappedBounds.top,
                                  *texture, *(op.paint));
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

void BakedOpDispatcher::onRoundRectOp(BakedOpRenderer& renderer, const RoundRectOp& op,
                                      const BakedOpState& state) {
    if (op.paint->getPathEffect() != nullptr) {
        PathTexture* texture = renderer.caches().pathCache.getRoundRect(
                op.unmappedBounds.getWidth(), op.unmappedBounds.getHeight(), op.rx, op.ry,
                op.paint);
        const AutoTexture holder(texture);
        if (CC_LIKELY(holder.texture)) {
            renderPathTexture(renderer, state, op.unmappedBounds.left, op.unmappedBounds.top,
                              *texture, *(op.paint));
        }
    } else {
        const VertexBuffer* buffer = renderer.caches().tessellationCache.getRoundRect(
                state.computedState.transform, *(op.paint), op.unmappedBounds.getWidth(),
                op.unmappedBounds.getHeight(), op.rx, op.ry);
        renderVertexBuffer(renderer, state, *buffer, op.unmappedBounds.left, op.unmappedBounds.top,
                           *(op.paint), 0);
    }
}

static void renderShadow(BakedOpRenderer& renderer, const BakedOpState& state, float casterAlpha,
                         const VertexBuffer* ambientShadowVertexBuffer,
                         const VertexBuffer* spotShadowVertexBuffer) {
    SkPaint paint;
    paint.setAntiAlias(true);  // want to use AlphaVertex

    // The caller has made sure casterAlpha > 0.
    uint8_t ambientShadowAlpha = renderer.getLightInfo().ambientShadowAlpha;
    if (CC_UNLIKELY(Properties::overrideAmbientShadowStrength >= 0)) {
        ambientShadowAlpha = Properties::overrideAmbientShadowStrength;
    }
    if (ambientShadowVertexBuffer && ambientShadowAlpha > 0) {
        paint.setAlpha((uint8_t)(casterAlpha * ambientShadowAlpha));
        renderVertexBuffer(renderer, state, *ambientShadowVertexBuffer, 0, 0, paint,
                           VertexBufferRenderFlags::ShadowInterp);
    }

    uint8_t spotShadowAlpha = renderer.getLightInfo().spotShadowAlpha;
    if (CC_UNLIKELY(Properties::overrideSpotShadowStrength >= 0)) {
        spotShadowAlpha = Properties::overrideSpotShadowStrength;
    }
    if (spotShadowVertexBuffer && spotShadowAlpha > 0) {
        paint.setAlpha((uint8_t)(casterAlpha * spotShadowAlpha));
        renderVertexBuffer(renderer, state, *spotShadowVertexBuffer, 0, 0, paint,
                           VertexBufferRenderFlags::ShadowInterp);
    }
}

void BakedOpDispatcher::onShadowOp(BakedOpRenderer& renderer, const ShadowOp& op,
                                   const BakedOpState& state) {
    TessellationCache::vertexBuffer_pair_t buffers = op.shadowTask->getResult();
    renderShadow(renderer, state, op.casterAlpha, buffers.first, buffers.second);
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
    // Note: can't trust clipSideFlags since we record with unmappedBounds == clip.
    // TODO: respect clipSideFlags, once we record with bounds
    auto renderTargetClip = state.computedState.clipState;

    FontRenderer& fontRenderer = renderer.caches().fontRenderer.getFontRenderer();
    fontRenderer.setFont(op.paint, SkMatrix::I());
    fontRenderer.setTextureFiltering(true);

    Rect layerBounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

    int alpha = PaintUtils::getAlphaDirect(op.paint) * state.alpha;
    SkBlendMode mode = PaintUtils::getBlendModeDirect(op.paint);
    TextDrawFunctor functor(&renderer, &state, renderTargetClip, 0.0f, 0.0f, false, alpha, mode,
                            op.paint);

    bool mustDirtyRenderTarget = renderer.offscreenRenderTarget();
    const Rect localSpaceClip = state.computedState.computeLocalSpaceClip();
    if (fontRenderer.renderTextOnPath(op.paint, &localSpaceClip, op.glyphs, op.glyphCount, op.path,
                                      op.hOffset, op.vOffset,
                                      mustDirtyRenderTarget ? &layerBounds : nullptr, &functor)) {
        if (mustDirtyRenderTarget) {
            // manually dirty render target, since TextDrawFunctor won't
            state.computedState.transform.mapRect(layerBounds);
            renderer.dirtyRenderTarget(layerBounds);
        }
    }
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
