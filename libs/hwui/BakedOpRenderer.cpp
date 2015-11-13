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

#include "BakedOpRenderer.h"

#include "Caches.h"
#include "Glop.h"
#include "GlopBuilder.h"
#include "renderstate/OffscreenBufferPool.h"
#include "renderstate/RenderState.h"
#include "utils/GLUtils.h"
#include "VertexBuffer.h"

namespace android {
namespace uirenderer {

////////////////////////////////////////////////////////////////////////////////
// BakedOpRenderer
////////////////////////////////////////////////////////////////////////////////

OffscreenBuffer* BakedOpRenderer::startTemporaryLayer(uint32_t width, uint32_t height) {
    LOG_ALWAYS_FATAL_IF(mRenderTarget.offscreenBuffer, "already has layer...");

    OffscreenBuffer* buffer = mRenderState.layerPool().get(mRenderState, width, height);
    startRepaintLayer(buffer, Rect(width, height));
    return buffer;
}

void BakedOpRenderer::startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) {
    LOG_ALWAYS_FATAL_IF(mRenderTarget.offscreenBuffer, "already has layer...");

    mRenderTarget.offscreenBuffer = offscreenBuffer;

    // create and bind framebuffer
    mRenderTarget.frameBufferId = mRenderState.genFramebuffer();
    mRenderState.bindFramebuffer(mRenderTarget.frameBufferId);

    // attach the texture to the FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            offscreenBuffer->texture.id, 0);
    LOG_ALWAYS_FATAL_IF(GLUtils::dumpGLErrors(), "startLayer FAILED");
    LOG_ALWAYS_FATAL_IF(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE,
            "framebuffer incomplete!");

    // Change the viewport & ortho projection
    setViewport(offscreenBuffer->viewportWidth, offscreenBuffer->viewportHeight);

    clearColorBuffer(repaintRect);
}

void BakedOpRenderer::endLayer() {
    mRenderTarget.offscreenBuffer->updateMeshFromRegion();
    mRenderTarget.offscreenBuffer = nullptr;

    // Detach the texture from the FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    LOG_ALWAYS_FATAL_IF(GLUtils::dumpGLErrors(), "endLayer FAILED");
    mRenderState.deleteFramebuffer(mRenderTarget.frameBufferId);
    mRenderTarget.frameBufferId = -1;
}

void BakedOpRenderer::startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) {
    mRenderState.bindFramebuffer(0);
    setViewport(width, height);
    mCaches.clearGarbage();

    if (!mOpaque) {
        clearColorBuffer(repaintRect);
    }
}

void BakedOpRenderer::endFrame() {
    mCaches.pathCache.trim();
    mCaches.tessellationCache.trim();

#if DEBUG_OPENGL
    GLUtils::dumpGLErrors();
#endif

#if DEBUG_MEMORY_USAGE
    mCaches.dumpMemoryUsage();
#else
    if (Properties::debugLevel & kDebugMemory) {
        mCaches.dumpMemoryUsage();
    }
#endif
}

void BakedOpRenderer::setViewport(uint32_t width, uint32_t height) {
    mRenderTarget.viewportWidth = width;
    mRenderTarget.viewportHeight = height;
    mRenderTarget.orthoMatrix.loadOrtho(width, height);

    mRenderState.setViewport(width, height);
    mRenderState.blend().syncEnabled();
}

void BakedOpRenderer::clearColorBuffer(const Rect& rect) {
    if (Rect(mRenderTarget.viewportWidth, mRenderTarget.viewportHeight).contains(rect)) {
        // Full viewport is being cleared - disable scissor
        mRenderState.scissor().setEnabled(false);
    } else {
        // Requested rect is subset of viewport - scissor to it to avoid over-clearing
        mRenderState.scissor().setEnabled(true);
        mRenderState.scissor().set(rect.left, mRenderTarget.viewportHeight - rect.bottom,
                rect.getWidth(), rect.getHeight());
    }
    glClear(GL_COLOR_BUFFER_BIT);
    if (!mRenderTarget.frameBufferId) mHasDrawn = true;
}

Texture* BakedOpRenderer::getTexture(const SkBitmap* bitmap) {
    Texture* texture = mRenderState.assetAtlas().getEntryTexture(bitmap);
    if (!texture) {
        return mCaches.textureCache.get(bitmap);
    }
    return texture;
}

void BakedOpRenderer::renderGlop(const BakedOpState& state, const Glop& glop) {
    bool useScissor = state.computedState.clipSideFlags != OpClipSideFlags::None;
    mRenderState.scissor().setEnabled(useScissor);
    if (useScissor) {
        const Rect& clip = state.computedState.clipRect;
        mRenderState.scissor().set(clip.left, mRenderTarget.viewportHeight - clip.bottom,
            clip.getWidth(), clip.getHeight());
    }
    if (mRenderTarget.offscreenBuffer) { // TODO: not with multi-draw
        // register layer damage to draw-back region
        const Rect& uiDirty = state.computedState.clippedBounds;
        android::Rect dirty(uiDirty.left, uiDirty.top, uiDirty.right, uiDirty.bottom);
        mRenderTarget.offscreenBuffer->region.orSelf(dirty);
    }
    mRenderState.render(glop, mRenderTarget.orthoMatrix);
    if (!mRenderTarget.frameBufferId) mHasDrawn = true;
}

////////////////////////////////////////////////////////////////////////////////
// static BakedOpDispatcher methods
////////////////////////////////////////////////////////////////////////////////

void BakedOpDispatcher::onRenderNodeOp(BakedOpRenderer&, const RenderNodeOp&, const BakedOpState&) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpDispatcher::onBeginLayerOp(BakedOpRenderer& renderer, const BeginLayerOp& op, const BakedOpState& state) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpDispatcher::onEndLayerOp(BakedOpRenderer& renderer, const EndLayerOp& op, const BakedOpState& state) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpDispatcher::onBitmapOp(BakedOpRenderer& renderer, const BitmapOp& op, const BakedOpState& state) {
    renderer.caches().textureState().activateTexture(0); // TODO: should this be automatic, and/or elsewhere?
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

void BakedOpDispatcher::onRectOp(BakedOpRenderer& renderer, const RectOp& op, const BakedOpState& state) {
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

namespace VertexBufferRenderFlags {
    enum {
        Offset = 0x1,
        ShadowInterp = 0x2,
    };
}

static void renderVertexBuffer(BakedOpRenderer& renderer, const BakedOpState& state,
        const VertexBuffer& vertexBuffer, float translateX, float translateY,
        SkPaint& paint, int vertexBufferRenderFlags) {
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

void BakedOpDispatcher::onLayerOp(BakedOpRenderer& renderer, const LayerOp& op, const BakedOpState& state) {
    OffscreenBuffer* buffer = *op.layerHandle;

    // TODO: extend this to handle HW layers & paint properties which
    // reside in node.properties().layerProperties()
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
