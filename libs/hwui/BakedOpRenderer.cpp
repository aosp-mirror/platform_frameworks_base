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
#include "VertexBuffer.h"
#include "renderstate/RenderState.h"
#include "utils/FatVector.h"
#include "utils/GLUtils.h"

namespace android {
namespace uirenderer {

////////////////////////////////////////////////////////////////////////////////
// OffscreenBuffer
////////////////////////////////////////////////////////////////////////////////

OffscreenBuffer::OffscreenBuffer(RenderState& renderState, Caches& caches,
        uint32_t textureWidth, uint32_t textureHeight,
        uint32_t viewportWidth, uint32_t viewportHeight)
        : renderState(renderState)
        , viewportWidth(viewportWidth)
        , viewportHeight(viewportHeight)
        , texture(caches) {
    texture.width = textureWidth;
    texture.height = textureHeight;

    caches.textureState().activateTexture(0);
    glGenTextures(1, &texture.id);
    caches.textureState().bindTexture(GL_TEXTURE_2D, texture.id);

    texture.setWrap(GL_CLAMP_TO_EDGE, false, false, GL_TEXTURE_2D);
    // not setting filter on texture, since it's set when rendering, based on transform

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, texture.width, texture.height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
}

void OffscreenBuffer::updateMeshFromRegion() {
    // avoid T-junctions as they cause artifacts in between the resultant
    // geometry when complex transforms occur.
    // TODO: generate the safeRegion only if necessary based on rendering transform
    Region safeRegion = Region::createTJunctionFreeRegion(region);

    size_t count;
    const android::Rect* rects = safeRegion.getArray(&count);

    const float texX = 1.0f / float(viewportWidth);
    const float texY = 1.0f / float(viewportHeight);

    FatVector<TextureVertex, 64> meshVector(count * 4); // uses heap if more than 64 vertices needed
    TextureVertex* mesh = &meshVector[0];
    for (size_t i = 0; i < count; i++) {
        const android::Rect* r = &rects[i];

        const float u1 = r->left * texX;
        const float v1 = (viewportHeight - r->top) * texY;
        const float u2 = r->right * texX;
        const float v2 = (viewportHeight - r->bottom) * texY;

        TextureVertex::set(mesh++, r->left, r->top, u1, v1);
        TextureVertex::set(mesh++, r->right, r->top, u2, v1);
        TextureVertex::set(mesh++, r->left, r->bottom, u1, v2);
        TextureVertex::set(mesh++, r->right, r->bottom, u2, v2);
    }
    elementCount = count * 6;
    renderState.meshState().genOrUpdateMeshBuffer(&vbo,
            sizeof(TextureVertex) * count * 4,
            &meshVector[0],
            GL_DYNAMIC_DRAW); // TODO: GL_STATIC_DRAW if savelayer
}

OffscreenBuffer::~OffscreenBuffer() {
    texture.deleteTexture();
    renderState.meshState().deleteMeshBuffer(vbo);
    elementCount = 0;
    vbo = 0;
}

////////////////////////////////////////////////////////////////////////////////
// BakedOpRenderer
////////////////////////////////////////////////////////////////////////////////

OffscreenBuffer* BakedOpRenderer::createOffscreenBuffer(RenderState& renderState,
        uint32_t width, uint32_t height) {
    // TODO: get from cache!
    return new OffscreenBuffer(renderState, Caches::getInstance(), width, height, width, height);
}

void BakedOpRenderer::destroyOffscreenBuffer(OffscreenBuffer* offscreenBuffer) {
    // TODO: return texture/offscreenbuffer to cache!
    delete offscreenBuffer;
}

OffscreenBuffer* BakedOpRenderer::startTemporaryLayer(uint32_t width, uint32_t height) {
    OffscreenBuffer* buffer = createOffscreenBuffer(mRenderState, width, height);
    startRepaintLayer(buffer);
    return buffer;
}

void BakedOpRenderer::startRepaintLayer(OffscreenBuffer* offscreenBuffer) {
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

    // Clear the FBO
    mRenderState.scissor().setEnabled(false);
    glClear(GL_COLOR_BUFFER_BIT);

    // Change the viewport & ortho projection
    setViewport(offscreenBuffer->viewportWidth, offscreenBuffer->viewportHeight);
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

void BakedOpRenderer::startFrame(uint32_t width, uint32_t height) {
    mRenderState.bindFramebuffer(0);
    setViewport(width, height);
    mCaches.clearGarbage();

    if (!mOpaque) {
        // TODO: partial invalidate!
        mRenderState.scissor().setEnabled(false);
        glClear(GL_COLOR_BUFFER_BIT);
        mHasDrawn = true;
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
    mHasDrawn = true;
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
    uint8_t ambientShadowAlpha = 128u; //TODO: mAmbientShadowAlpha;
    if (CC_UNLIKELY(Properties::overrideAmbientShadowStrength >= 0)) {
        ambientShadowAlpha = Properties::overrideAmbientShadowStrength;
    }
    if (ambientShadowVertexBuffer && ambientShadowAlpha > 0) {
        paint.setAlpha((uint8_t)(casterAlpha * ambientShadowAlpha));
        renderVertexBuffer(renderer, state, *ambientShadowVertexBuffer, 0, 0,
                paint, VertexBufferRenderFlags::ShadowInterp);
    }

    uint8_t spotShadowAlpha = 128u; //TODO: mSpotShadowAlpha;
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
    Vector3 lightCenter = { 300, 300, 300 }; // TODO!
    float lightRadius = 150; // TODO!

    renderer.caches().tessellationCache.getShadowBuffers(&state.computedState.transform,
            op.localClipRect, op.casterAlpha >= 1.0f, op.casterPath,
            &op.shadowMatrixXY, &op.shadowMatrixZ, lightCenter, lightRadius,
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
        BakedOpRenderer::destroyOffscreenBuffer(buffer);
    }
}

} // namespace uirenderer
} // namespace android
