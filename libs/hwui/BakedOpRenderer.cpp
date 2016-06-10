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

#include <algorithm>

namespace android {
namespace uirenderer {

OffscreenBuffer* BakedOpRenderer::startTemporaryLayer(uint32_t width, uint32_t height) {
    LOG_ALWAYS_FATAL_IF(mRenderTarget.offscreenBuffer, "already has layer...");

    OffscreenBuffer* buffer = mRenderState.layerPool().get(mRenderState, width, height);
    startRepaintLayer(buffer, Rect(width, height));
    return buffer;
}

void BakedOpRenderer::recycleTemporaryLayer(OffscreenBuffer* offscreenBuffer) {
    mRenderState.layerPool().putOrDelete(offscreenBuffer);
}

void BakedOpRenderer::startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) {
    LOG_ALWAYS_FATAL_IF(mRenderTarget.offscreenBuffer, "already has layer...");

    // subtract repaintRect from region, since it will be regenerated
    if (repaintRect.contains(0, 0,
                offscreenBuffer->viewportWidth, offscreenBuffer->viewportHeight)) {
        // repaint full layer, so throw away entire region
        offscreenBuffer->region.clear();
    } else {
        offscreenBuffer->region.subtractSelf(android::Rect(repaintRect.left, repaintRect.top,
                repaintRect.right, repaintRect.bottom));
    }

    mRenderTarget.offscreenBuffer = offscreenBuffer;
    mRenderTarget.offscreenBuffer->hasRenderedSinceRepaint = false;

    // create and bind framebuffer
    mRenderTarget.frameBufferId = mRenderState.createFramebuffer();
    mRenderState.bindFramebuffer(mRenderTarget.frameBufferId);

    // attach the texture to the FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            offscreenBuffer->texture.id(), 0);
    GL_CHECKPOINT(LOW);

    int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    LOG_ALWAYS_FATAL_IF(status != GL_FRAMEBUFFER_COMPLETE,
            "framebuffer incomplete, status %d, textureId %d, size %dx%d",
            status,
            offscreenBuffer->texture.id(),
            offscreenBuffer->texture.width(),
            offscreenBuffer->texture.height());

    // Change the viewport & ortho projection
    setViewport(offscreenBuffer->viewportWidth, offscreenBuffer->viewportHeight);

    clearColorBuffer(repaintRect);
}

void BakedOpRenderer::endLayer() {
    if (mRenderTarget.stencil) {
        // if stencil was used for clipping, detach it and return it to pool
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
        GL_CHECKPOINT(MODERATE);
        mCaches.renderBufferCache.put(mRenderTarget.stencil);
        mRenderTarget.stencil = nullptr;
    }
    mRenderTarget.lastStencilClip = nullptr;

    mRenderTarget.offscreenBuffer->updateMeshFromRegion();
    mRenderTarget.offscreenBuffer = nullptr; // It's in drawLayerOp's hands now.

    // Detach the texture from the FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    GL_CHECKPOINT(LOW);
    mRenderState.deleteFramebuffer(mRenderTarget.frameBufferId);
    mRenderTarget.frameBufferId = 0;
}

OffscreenBuffer* BakedOpRenderer::copyToLayer(const Rect& area) {
    const uint32_t width = area.getWidth();
    const uint32_t height = area.getHeight();
    OffscreenBuffer* buffer = mRenderState.layerPool().get(mRenderState, width, height);
    if (!area.isEmpty() && width != 0 && height != 0) {
        mCaches.textureState().activateTexture(0);
        mCaches.textureState().bindTexture(buffer->texture.id());

        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                area.left, mRenderTarget.viewportHeight - area.bottom, width, height);
    }
    return buffer;
}

void BakedOpRenderer::startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) {
    LOG_ALWAYS_FATAL_IF(mRenderTarget.frameBufferId != 0, "primary framebufferId must be 0");
    mRenderState.bindFramebuffer(0);
    setViewport(width, height);

    if (!mOpaque) {
        clearColorBuffer(repaintRect);
    }

    mRenderState.debugOverdraw(true, true);
}

void BakedOpRenderer::endFrame(const Rect& repaintRect) {
    if (CC_UNLIKELY(Properties::debugOverdraw)) {
        ClipRect overdrawClip(repaintRect);
        Rect viewportRect(mRenderTarget.viewportWidth, mRenderTarget.viewportHeight);
        // overdraw visualization
        for (int i = 1; i <= 4; i++) {
            if (i < 4) {
                // nth level of overdraw tests for n+1 draws per pixel
                mRenderState.stencil().enableDebugTest(i + 1, false);
            } else {
                // 4th level tests for 4 or higher draws per pixel
                mRenderState.stencil().enableDebugTest(4, true);
            }

            SkPaint paint;
            paint.setColor(mCaches.getOverdrawColor(i));
            Glop glop;
            GlopBuilder(mRenderState, mCaches, &glop)
                    .setRoundRectClipState(nullptr)
                    .setMeshUnitQuad()
                    .setFillPaint(paint, 1.0f)
                    .setTransform(Matrix4::identity(), TransformFlags::None)
                    .setModelViewMapUnitToRect(viewportRect)
                    .build();
            renderGlop(nullptr, &overdrawClip, glop);
        }
        mRenderState.stencil().disable();
    }

    // Note: we leave FBO 0 renderable here, for post-frame-content decoration
}

void BakedOpRenderer::setViewport(uint32_t width, uint32_t height) {
    mRenderTarget.viewportWidth = width;
    mRenderTarget.viewportHeight = height;
    mRenderTarget.orthoMatrix.loadOrtho(width, height);

    mRenderState.setViewport(width, height);
    mRenderState.blend().syncEnabled();
}

void BakedOpRenderer::clearColorBuffer(const Rect& rect) {
    if (rect.contains(Rect(mRenderTarget.viewportWidth, mRenderTarget.viewportHeight))) {
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
    Texture* texture = mRenderState.assetAtlas().getEntryTexture(bitmap->pixelRef());
    if (!texture) {
        return mCaches.textureCache.get(bitmap);
    }
    return texture;
}

void BakedOpRenderer::drawRects(const float* rects, int count, const SkPaint* paint) {
    std::vector<Vertex> vertices;
    vertices.reserve(count);
    Vertex* vertex = vertices.data();

    for (int index = 0; index < count; index += 4) {
        float l = rects[index + 0];
        float t = rects[index + 1];
        float r = rects[index + 2];
        float b = rects[index + 3];

        Vertex::set(vertex++, l, t);
        Vertex::set(vertex++, r, t);
        Vertex::set(vertex++, l, b);
        Vertex::set(vertex++, r, b);
    }

    LOG_ALWAYS_FATAL_IF(mRenderTarget.frameBufferId != 0, "decoration only supported for FBO 0");
    // TODO: Currently assume full FBO damage, due to FrameInfoVisualizer::unionDirty.
    // Should should scissor/set mHasDrawn safely.
    mRenderState.scissor().setEnabled(false);
    mHasDrawn = true;
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(nullptr)
            .setMeshIndexedQuads(vertices.data(), count / 4)
            .setFillPaint(*paint, 1.0f)
            .setTransform(Matrix4::identity(), TransformFlags::None)
            .setModelViewIdentityEmptyBounds()
            .build();
    mRenderState.render(glop, mRenderTarget.orthoMatrix);
}

// clears and re-fills stencil with provided rendertarget space quads,
// and then put stencil into test mode
void BakedOpRenderer::setupStencilQuads(std::vector<Vertex>& quadVertices,
        int incrementThreshold) {
    mRenderState.stencil().enableWrite(incrementThreshold);
    mRenderState.stencil().clear();
    Glop glop;
    GlopBuilder(mRenderState, mCaches, &glop)
            .setRoundRectClipState(nullptr)
            .setMeshIndexedQuads(quadVertices.data(), quadVertices.size() / 4)
            .setFillBlack()
            .setTransform(Matrix4::identity(), TransformFlags::None)
            .setModelViewIdentityEmptyBounds()
            .build();
    mRenderState.render(glop, mRenderTarget.orthoMatrix);
    mRenderState.stencil().enableTest(incrementThreshold);
}

void BakedOpRenderer::setupStencilRectList(const ClipBase* clip) {
    LOG_ALWAYS_FATAL_IF(clip->mode != ClipMode::RectangleList, "can't rectlist clip without rectlist");
    auto&& rectList = reinterpret_cast<const ClipRectList*>(clip)->rectList;
    int quadCount = rectList.getTransformedRectanglesCount();
    std::vector<Vertex> rectangleVertices;
    rectangleVertices.reserve(quadCount * 4);
    for (int i = 0; i < quadCount; i++) {
        const TransformedRectangle& tr(rectList.getTransformedRectangle(i));
        const Matrix4& transform = tr.getTransform();
        Rect bounds = tr.getBounds();
        if (transform.rectToRect()) {
            // If rectToRect, can simply map bounds before storing verts
            transform.mapRect(bounds);
            bounds.doIntersect(clip->rect);
            if (bounds.isEmpty()) {
                continue; // will be outside of scissor, skip
            }
        }

        rectangleVertices.push_back(Vertex{bounds.left, bounds.top});
        rectangleVertices.push_back(Vertex{bounds.right, bounds.top});
        rectangleVertices.push_back(Vertex{bounds.left, bounds.bottom});
        rectangleVertices.push_back(Vertex{bounds.right, bounds.bottom});

        if (!transform.rectToRect()) {
            // If not rectToRect, must map each point individually
            for (auto cur = rectangleVertices.end() - 4; cur < rectangleVertices.end(); cur++) {
                transform.mapPoint(cur->x, cur->y);
            }
        }
    }
    setupStencilQuads(rectangleVertices, rectList.getTransformedRectanglesCount());
}

void BakedOpRenderer::setupStencilRegion(const ClipBase* clip) {
    LOG_ALWAYS_FATAL_IF(clip->mode != ClipMode::Region, "can't region clip without region");
    auto&& region = reinterpret_cast<const ClipRegion*>(clip)->region;

    std::vector<Vertex> regionVertices;
    SkRegion::Cliperator it(region, clip->rect.toSkIRect());
    while (!it.done()) {
        const SkIRect& r = it.rect();
        regionVertices.push_back(Vertex{(float)r.fLeft, (float)r.fTop});
        regionVertices.push_back(Vertex{(float)r.fRight, (float)r.fTop});
        regionVertices.push_back(Vertex{(float)r.fLeft, (float)r.fBottom});
        regionVertices.push_back(Vertex{(float)r.fRight, (float)r.fBottom});
        it.next();
    }
    setupStencilQuads(regionVertices, 0);
}

void BakedOpRenderer::prepareRender(const Rect* dirtyBounds, const ClipBase* clip) {
    // Prepare scissor (done before stencil, to simplify filling stencil)
    mRenderState.scissor().setEnabled(clip != nullptr);
    if (clip) {
        mRenderState.scissor().set(mRenderTarget.viewportHeight, clip->rect);
    }

    // If stencil may be used for clipping, enable it, fill it, or disable it as appropriate
    if (CC_LIKELY(!Properties::debugOverdraw)) {
        // only modify stencil mode and content when it's not used for overdraw visualization
        if (CC_UNLIKELY(clip && clip->mode != ClipMode::Rectangle)) {
            // NOTE: this pointer check is only safe for non-rect clips,
            // since rect clips may be created on the stack
            if (mRenderTarget.lastStencilClip != clip) {
                // Stencil needed, but current stencil isn't up to date
                mRenderTarget.lastStencilClip = clip;

                if (mRenderTarget.frameBufferId != 0 && !mRenderTarget.stencil) {
                    OffscreenBuffer* layer = mRenderTarget.offscreenBuffer;
                    mRenderTarget.stencil = mCaches.renderBufferCache.get(
                            Stencil::getLayerStencilFormat(),
                            layer->texture.width(), layer->texture.height());
                    // stencil is bound + allocated - associate it with current FBO
                    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                            GL_RENDERBUFFER, mRenderTarget.stencil->getName());
                }

                if (clip->mode == ClipMode::RectangleList) {
                    setupStencilRectList(clip);
                } else {
                    setupStencilRegion(clip);
                }
            } else {
                // stencil is up to date - just need to ensure it's enabled (since an unclipped
                // or scissor-only clipped op may have been drawn, disabling the stencil)
                int incrementThreshold = 0;
                if (CC_LIKELY(clip->mode == ClipMode::RectangleList)) {
                    auto&& rectList = reinterpret_cast<const ClipRectList*>(clip)->rectList;
                    incrementThreshold = rectList.getTransformedRectanglesCount();
                }
                mRenderState.stencil().enableTest(incrementThreshold);
            }
        } else {
            // either scissor or no clip, so disable stencil test
            mRenderState.stencil().disable();
        }
    }

    if (dirtyBounds) {
        // dirty offscreenbuffer if present
        dirtyRenderTarget(*dirtyBounds);
    }
}

void BakedOpRenderer::renderGlopImpl(const Rect* dirtyBounds, const ClipBase* clip,
        const Glop& glop) {
    prepareRender(dirtyBounds, clip);
    mRenderState.render(glop, mRenderTarget.orthoMatrix);
    if (!mRenderTarget.frameBufferId) mHasDrawn = true;
}

void BakedOpRenderer::renderFunctor(const FunctorOp& op, const BakedOpState& state) {
    prepareRender(&state.computedState.clippedBounds, state.computedState.getClipIfNeeded());

    DrawGlInfo info;
    auto&& clip = state.computedState.clipRect();
    info.clipLeft = clip.left;
    info.clipTop = clip.top;
    info.clipRight = clip.right;
    info.clipBottom = clip.bottom;
    info.isLayer = offscreenRenderTarget();
    info.width = mRenderTarget.viewportWidth;
    info.height = mRenderTarget.viewportHeight;
    state.computedState.transform.copyTo(&info.transform[0]);

    mRenderState.invokeFunctor(op.functor, DrawGlInfo::kModeDraw, &info);
}

void BakedOpRenderer::dirtyRenderTarget(const Rect& uiDirty) {
    if (mRenderTarget.offscreenBuffer) {
        mRenderTarget.offscreenBuffer->dirty(uiDirty);
    }
}

} // namespace uirenderer
} // namespace android
