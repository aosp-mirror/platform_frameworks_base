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

void BakedOpRenderer::startFrame(Info& info) {
    info.renderState.setViewport(info.viewportWidth, info.viewportHeight);
    info.renderState.blend().syncEnabled();
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

void BakedOpRenderer::onRenderNodeOp(Info*, const RenderNodeOp&, const BakedOpState&) {
    LOG_ALWAYS_FATAL("unsupported operation");
}

void BakedOpRenderer::onBitmapOp(Info* info, const BitmapOp& op, const BakedOpState& state) {
    info->caches.textureState().activateTexture(0); // TODO: should this be automatic, and/or elsewhere?
    Texture* texture = info->getTexture(op.bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const int textureFillFlags = (op.bitmap->colorType() == kAlpha_8_SkColorType)
            ? TextureFillFlags::IsAlphaMaskTexture : TextureFillFlags::None;
    Glop glop;
    GlopBuilder(info->renderState, info->caches, &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshTexturedUnitQuad(texture->uvMapper)
            .setFillTexturePaint(*texture, textureFillFlags, op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRectSnap(Rect(0, 0, texture->width, texture->height))
            .build();
    info->renderGlop(state, glop);
}

void BakedOpRenderer::onRectOp(Info* info, const RectOp& op, const BakedOpState& state) {
    Glop glop;
    GlopBuilder(info->renderState, info->caches, &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshUnitQuad()
            .setFillPaint(*op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewMapUnitToRect(op.unmappedBounds)
            .build();
    info->renderGlop(state, glop);
}

void BakedOpRenderer::onSimpleRectsOp(Info* info, const SimpleRectsOp& op, const BakedOpState& state) {
    Glop glop;
    GlopBuilder(info->renderState, info->caches, &glop)
            .setRoundRectClipState(state.roundRectClipState)
            .setMeshIndexedQuads(&op.vertices[0], op.vertexCount / 4)
            .setFillPaint(*op.paint, state.alpha)
            .setTransform(state.computedState.transform, TransformFlags::None)
            .setModelViewOffsetRect(0, 0, op.unmappedBounds)
            .build();
    info->renderGlop(state, glop);
}


} // namespace uirenderer
} // namespace android
