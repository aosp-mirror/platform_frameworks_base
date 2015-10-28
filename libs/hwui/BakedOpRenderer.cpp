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
#include "renderstate/RenderState.h"
#include "utils/GLUtils.h"

namespace android {
namespace uirenderer {

////////////////////////////////////////////////////////////////////////////////
// OffscreenBuffer
////////////////////////////////////////////////////////////////////////////////

OffscreenBuffer::OffscreenBuffer(Caches& caches, uint32_t textureWidth, uint32_t textureHeight,
        uint32_t viewportWidth, uint32_t viewportHeight)
        : texture(caches)
        , texCoords(0, viewportHeight / float(textureHeight), viewportWidth / float(textureWidth), 0) {
    texture.width = textureWidth;
    texture.height = textureHeight;

    caches.textureState().activateTexture(0);
    glGenTextures(1, &texture.id);
    caches.textureState().bindTexture(GL_TEXTURE_2D, texture.id);

    texture.setWrap(GL_CLAMP_TO_EDGE, false, false, GL_TEXTURE_2D);
    // not setting filter on texture, since it's set when drawing, based on transform

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, texture.width, texture.height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
}

////////////////////////////////////////////////////////////////////////////////
// BakedOpRenderer
////////////////////////////////////////////////////////////////////////////////

OffscreenBuffer* BakedOpRenderer::startLayer(uint32_t width, uint32_t height) {
    LOG_ALWAYS_FATAL_IF(mRenderTarget.offscreenBuffer, "already has layer...");

    // TODO: really should be caching these!
    OffscreenBuffer* buffer = new OffscreenBuffer(mCaches, width, height, width, height);
    mRenderTarget.offscreenBuffer = buffer;

    // create and bind framebuffer
    mRenderTarget.frameBufferId = mRenderState.genFramebuffer();
    mRenderState.bindFramebuffer(mRenderTarget.frameBufferId);

    // attach the texture to the FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            buffer->texture.id, 0);
    LOG_ALWAYS_FATAL_IF(GLUtils::dumpGLErrors(), "startLayer FAILED");
    LOG_ALWAYS_FATAL_IF(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE,
            "framebuffer incomplete!");

    // Clear the FBO
    mRenderState.scissor().setEnabled(false);
    glClear(GL_COLOR_BUFFER_BIT);

    // Change the viewport & ortho projection
    setViewport(width, height);
    return buffer;
}

void BakedOpRenderer::endLayer() {
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
    mRenderState.render(glop, mRenderTarget.orthoMatrix);
    mHasDrawn = true;
}

////////////////////////////////////////////////////////////////////////////////
// static BakedOpDispatcher methods
////////////////////////////////////////////////////////////////////////////////

void BakedOpDispatcher::onRenderNodeOp(BakedOpRenderer&, const RenderNodeOp&, const BakedOpState&) {
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

void BakedOpDispatcher::onBeginLayerOp(BakedOpRenderer& renderer, const BeginLayerOp& op, const BakedOpState& state) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpDispatcher::onEndLayerOp(BakedOpRenderer& renderer, const EndLayerOp& op, const BakedOpState& state) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpDispatcher::onLayerOp(BakedOpRenderer& renderer, const LayerOp& op, const BakedOpState& state) {
    OffscreenBuffer* buffer = *op.layerHandle;

    // TODO: extend this to handle HW layers & paint properties which
    // reside in node.properties().layerProperties()
    float layerAlpha = (op.paint->getAlpha() / 255.0f) * state.alpha;
    const bool tryToSnap = state.computedState.transform.isPureTranslate();
    Glop glop;
    GlopBuilder(renderer.renderState(), renderer.caches(), &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUvQuad(nullptr, buffer->texCoords)
            .setFillLayer(buffer->texture, op.paint->getColorFilter(), layerAlpha, PaintUtils::getXfermodeDirect(op.paint), Blend::ModeOrderSwap::NoSwap)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectOptionalSnap(tryToSnap, op.unmappedBounds)
            .build();
    renderer.renderGlop(state, glop);

    // destroy and delete, since each clipped saveLayer is only drawn once.
    buffer->texture.deleteTexture();

    // TODO: return texture/offscreenbuffer to cache!
    delete buffer;
}

} // namespace uirenderer
} // namespace android
