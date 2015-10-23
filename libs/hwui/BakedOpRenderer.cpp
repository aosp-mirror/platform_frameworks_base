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

void BakedOpRenderer::Info::setViewport(uint32_t width, uint32_t height) {
    viewportWidth = width;
    viewportHeight = height;
    orthoMatrix.loadOrtho(viewportWidth, viewportHeight);

    renderState.setViewport(width, height);
    renderState.blend().syncEnabled();
}

Texture* BakedOpRenderer::Info::getTexture(const SkBitmap* bitmap) {
    Texture* texture = renderState.assetAtlas().getEntryTexture(bitmap);
    if (!texture) {
        return caches.textureCache.get(bitmap);
    }
    return texture;
}

void BakedOpRenderer::Info::renderGlop(const BakedOpState& state, const Glop& glop) {
    bool useScissor = state.computedState.clipSideFlags != OpClipSideFlags::None;
    renderState.scissor().setEnabled(useScissor);
    if (useScissor) {
        const Rect& clip = state.computedState.clipRect;
        renderState.scissor().set(clip.left, viewportHeight - clip.bottom,
            clip.getWidth(), clip.getHeight());
    }
    renderState.render(glop, orthoMatrix);
    didDraw = true;
}

Layer* BakedOpRenderer::startLayer(Info& info, uint32_t width, uint32_t height) {
    info.caches.textureState().activateTexture(0);
    Layer* layer = info.caches.layerCache.get(info.renderState, width, height);
    LOG_ALWAYS_FATAL_IF(!layer, "need layer...");

    info.layer = layer;
    layer->texCoords.set(0.0f, width / float(layer->getHeight()),
            height / float(layer->getWidth()), 0.0f);

    layer->setFbo(info.renderState.genFramebuffer());
    info.renderState.bindFramebuffer(layer->getFbo());
    layer->bindTexture();

    // Initialize the texture if needed
    if (layer->isEmpty()) {
        layer->allocateTexture();
        layer->setEmpty(false);
    }

    // attach the texture to the FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->getTextureId(), 0);
    LOG_ALWAYS_FATAL_IF(GLUtils::dumpGLErrors(), "startLayer FAILED");
    LOG_ALWAYS_FATAL_IF(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE,
            "framebuffer incomplete!");

    // Clear the FBO
    info.renderState.scissor().setEnabled(false);
    glClear(GL_COLOR_BUFFER_BIT);

    // Change the viewport & ortho projection
    info.setViewport(width, height);
    return layer;
}

void BakedOpRenderer::endLayer(Info& info) {
    Layer* layer = info.layer;
    info.layer = nullptr;

    // Detach the texture from the FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    LOG_ALWAYS_FATAL_IF(GLUtils::dumpGLErrors(), "endLayer FAILED");
    layer->removeFbo(false);
}

void BakedOpRenderer::startFrame(Info& info, uint32_t width, uint32_t height) {
    info.renderState.bindFramebuffer(0);
    info.setViewport(width, height);
    Caches::getInstance().clearGarbage();

    if (!info.opaque) {
        // TODO: partial invalidate!
        info.renderState.scissor().setEnabled(false);
        glClear(GL_COLOR_BUFFER_BIT);
        info.didDraw = true;
    }
}
void BakedOpRenderer::endFrame(Info& info) {
    info.caches.pathCache.trim();
    info.caches.tessellationCache.trim();

#if DEBUG_OPENGL
    GLUtils::dumpGLErrors();
#endif

#if DEBUG_MEMORY_USAGE
    info.caches.dumpMemoryUsage();
#else
    if (Properties::debugLevel & kDebugMemory) {
        info.caches.dumpMemoryUsage();
    }
#endif
}

void BakedOpRenderer::onRenderNodeOp(Info&, const RenderNodeOp&, const BakedOpState&) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpRenderer::onBitmapOp(Info& info, const BitmapOp& op, const BakedOpState& state) {
    info.caches.textureState().activateTexture(0); // TODO: should this be automatic, and/or elsewhere?
    Texture* texture = info.getTexture(op.bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const int textureFillFlags = (op.bitmap->colorType() == kAlpha_8_SkColorType)
            ? TextureFillFlags::IsAlphaMaskTexture : TextureFillFlags::None;
    Glop glop;
    GlopBuilder(info.renderState, info.caches, &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUnitQuad(texture->uvMapper)
            .setFillTexturePaint(*texture, textureFillFlags, op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectSnap(Rect(0, 0, texture->width, texture->height))
            .build();
    info.renderGlop(state, glop);
}

void BakedOpRenderer::onRectOp(Info& info, const RectOp& op, const BakedOpState& state) {
    Glop glop;
    GlopBuilder(info.renderState, info.caches, &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshUnitQuad()
            .setFillPaint(*op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRect(op.unmappedBounds)
            .build();
    info.renderGlop(state, glop);
}

void BakedOpRenderer::onSimpleRectsOp(Info& info, const SimpleRectsOp& op, const BakedOpState& state) {
    Glop glop;
    GlopBuilder(info.renderState, info.caches, &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshIndexedQuads(&op.vertices[0], op.vertexCount / 4)
            .setFillPaint(*op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewOffsetRect(0, 0, op.unmappedBounds)
            .build();
    info.renderGlop(state, glop);
}

void BakedOpRenderer::onBeginLayerOp(Info& info, const BeginLayerOp& op, const BakedOpState& state) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpRenderer::onEndLayerOp(Info& info, const EndLayerOp& op, const BakedOpState& state) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpRenderer::onLayerOp(Info& info, const LayerOp& op, const BakedOpState& state) {
    Layer* layer = *op.layerHandle;

    // TODO: make this work for HW layers
    layer->setPaint(op.paint);
    layer->setBlend(true);
    float layerAlpha = (layer->getAlpha() / 255.0f) * state.alpha;

    const bool tryToSnap = state.computedState.transform.isPureTranslate();
    Glop glop;
    GlopBuilder(info.renderState, info.caches, &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUvQuad(nullptr, layer->texCoords)
            .setFillLayer(layer->getTexture(), layer->getColorFilter(), layerAlpha, layer->getMode(), Blend::ModeOrderSwap::NoSwap)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectOptionalSnap(tryToSnap, op.unmappedBounds)
            .build();
    info.renderGlop(state, glop);

    // return layer to cache, since each clipped savelayer is only drawn once.
    layer->setConvexMask(nullptr);
    if (!info.caches.layerCache.put(layer)) {
        // Failing to add the layer to the cache should happen only if the layer is too large
        LAYER_LOGD("Deleting layer");
        layer->decStrong(nullptr);
    }
}

} // namespace uirenderer
} // namespace android
