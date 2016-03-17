/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "Layer.h"

#include "Caches.h"
#include "DeferredDisplayList.h"
#include "LayerRenderer.h"
#include "OpenGLRenderer.h"
#include "RenderNode.h"
#include "renderstate/RenderState.h"
#include "utils/TraceUtils.h"

#include <utils/Log.h>

#define ATRACE_LAYER_WORK(label) \
    ATRACE_FORMAT("%s HW Layer DisplayList %s %ux%u", \
            label, \
            (renderNode.get() != NULL) ? renderNode->getName() : "", \
            getWidth(), getHeight())

namespace android {
namespace uirenderer {

Layer::Layer(Type layerType, RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight)
        : GpuMemoryTracker(GpuObjectType::Layer)
        , state(State::Uncached)
        , caches(Caches::getInstance())
        , renderState(renderState)
        , texture(caches)
        , type(layerType) {
    // TODO: This is a violation of Android's typical ref counting, but it
    // preserves the old inc/dec ref locations. This should be changed...
    incStrong(nullptr);
    renderTarget = GL_TEXTURE_2D;
    texture.mWidth = layerWidth;
    texture.mHeight = layerHeight;
    renderState.registerLayer(this);
}

Layer::~Layer() {
    renderState.unregisterLayer(this);
    SkSafeUnref(colorFilter);

    if (stencil || fbo || texture.mId) {
        removeFbo();
        texture.deleteTexture();
    }

    delete[] mesh;
}

void Layer::onGlContextLost() {
    removeFbo();
    texture.deleteTexture();
}

uint32_t Layer::computeIdealWidth(uint32_t layerWidth) {
    return uint32_t(ceilf(layerWidth / float(LAYER_SIZE)) * LAYER_SIZE);
}

uint32_t Layer::computeIdealHeight(uint32_t layerHeight) {
    return uint32_t(ceilf(layerHeight / float(LAYER_SIZE)) * LAYER_SIZE);
}

void Layer::requireRenderer() {
    if (!renderer) {
        renderer.reset(new LayerRenderer(renderState, this));
        renderer->initProperties();
    }
}

void Layer::updateLightPosFromRenderer(const OpenGLRenderer& rootRenderer) {
    if (renderer && rendererLightPosDirty) {
        // re-init renderer's light position, based upon last cached location in window
        Vector3 lightPos = rootRenderer.getLightCenter();
        cachedInvTransformInWindow.mapPoint3d(lightPos);
        renderer->initLight(rootRenderer.getLightRadius(),
                rootRenderer.getAmbientShadowAlpha(),
                rootRenderer.getSpotShadowAlpha());
        renderer->setLightCenter(lightPos);
        rendererLightPosDirty = false;
    }
}

bool Layer::resize(const uint32_t width, const uint32_t height) {
    uint32_t desiredWidth = computeIdealWidth(width);
    uint32_t desiredHeight = computeIdealWidth(height);

    if (desiredWidth <= getWidth() && desiredHeight <= getHeight()) {
        return true;
    }

    ATRACE_NAME("resizeLayer");

    const uint32_t maxTextureSize = caches.maxTextureSize;
    if (desiredWidth > maxTextureSize || desiredHeight > maxTextureSize) {
        ALOGW("Layer exceeds max. dimensions supported by the GPU (%dx%d, max=%dx%d)",
                desiredWidth, desiredHeight, maxTextureSize, maxTextureSize);
        return false;
    }

    uint32_t oldWidth = getWidth();
    uint32_t oldHeight = getHeight();

    setSize(desiredWidth, desiredHeight);

    if (fbo) {
        caches.textureState().activateTexture(0);
        bindTexture();
        allocateTexture();

        if (glGetError() != GL_NO_ERROR) {
            setSize(oldWidth, oldHeight);
            return false;
        }
    }

    if (stencil) {
        stencil->bind();
        stencil->resize(desiredWidth, desiredHeight);

        if (glGetError() != GL_NO_ERROR) {
            setSize(oldWidth, oldHeight);
            return false;
        }
    }

    return true;
}

void Layer::removeFbo(bool flush) {
    if (stencil) {
        GLuint previousFbo = renderState.getFramebuffer();
        renderState.bindFramebuffer(fbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
        renderState.bindFramebuffer(previousFbo);

        caches.renderBufferCache.put(stencil);
        stencil = nullptr;
    }

    if (fbo) {
        if (flush) LayerRenderer::flushLayer(renderState, this);
        renderState.deleteFramebuffer(fbo);
        fbo = 0;
    }
}

void Layer::updateDeferred(RenderNode* renderNode, int left, int top, int right, int bottom) {
    requireRenderer();
    this->renderNode = renderNode;
    const Rect r(left, top, right, bottom);
    dirtyRect.unionWith(r);
    deferredUpdateScheduled = true;
}

void Layer::setPaint(const SkPaint* paint) {
    alpha = PaintUtils::getAlphaDirect(paint);
    mode = PaintUtils::getXfermodeDirect(paint);
    setColorFilter((paint) ? paint->getColorFilter() : nullptr);
}

void Layer::setColorFilter(SkColorFilter* filter) {
    SkRefCnt_SafeAssign(colorFilter, filter);
}

void Layer::bindTexture() const {
    if (texture.mId) {
        caches.textureState().bindTexture(renderTarget, texture.mId);
    }
}

void Layer::bindStencilRenderBuffer() const {
    if (stencil) {
        stencil->bind();
    }
}

void Layer::generateTexture() {
    if (!texture.mId) {
        glGenTextures(1, &texture.mId);
    }
}

void Layer::clearTexture() {
    // There's a rare possibility that Caches could have been destroyed already
    // since this method is queued up as a task.
    // Since this is a reset method, treat this as non-fatal.
    if (caches.isInitialized()) {
        caches.textureState().unbindTexture(texture.mId);
    }
    texture.mId = 0;
}

void Layer::allocateTexture() {
#if DEBUG_LAYERS
    ALOGD("  Allocate layer: %dx%d", getWidth(), getHeight());
#endif
    if (texture.mId) {
        texture.updateSize(getWidth(), getHeight(), GL_RGBA);
        glTexImage2D(renderTarget, 0, GL_RGBA, getWidth(), getHeight(), 0,
                GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
}

void Layer::defer(const OpenGLRenderer& rootRenderer) {
    ATRACE_LAYER_WORK("Optimize");

    updateLightPosFromRenderer(rootRenderer);
    const float width = layer.getWidth();
    const float height = layer.getHeight();

    if (dirtyRect.isEmpty() || (dirtyRect.left <= 0 && dirtyRect.top <= 0 &&
            dirtyRect.right >= width && dirtyRect.bottom >= height)) {
        dirtyRect.set(0, 0, width, height);
    }

    deferredList.reset(new DeferredDisplayList(dirtyRect));

    DeferStateStruct deferredState(*deferredList, *renderer,
            RenderNode::kReplayFlag_ClipChildren);

    renderer->setupFrameState(width, height, dirtyRect.left, dirtyRect.top,
            dirtyRect.right, dirtyRect.bottom, !isBlend());

    renderNode->computeOrdering();
    renderNode->defer(deferredState, 0);

    deferredUpdateScheduled = false;
}

void Layer::cancelDefer() {
    renderNode = nullptr;
    deferredUpdateScheduled = false;
    deferredList.reset(nullptr);
}

void Layer::flush() {
    // renderer is checked as layer may be destroyed/put in layer cache with flush scheduled
    if (deferredList && renderer) {
        ATRACE_LAYER_WORK("Issue");
        renderer->startMark((renderNode.get() != nullptr) ? renderNode->getName() : "Layer");

        renderer->prepareDirty(layer.getWidth(), layer.getHeight(),
                dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom, !isBlend());

        deferredList->flush(*renderer, dirtyRect);

        renderer->finish();

        dirtyRect.setEmpty();
        renderNode = nullptr;

        renderer->endMark();
    }
}

void Layer::render(const OpenGLRenderer& rootRenderer) {
    ATRACE_LAYER_WORK("Direct-Issue");

    updateLightPosFromRenderer(rootRenderer);
    renderer->prepareDirty(layer.getWidth(), layer.getHeight(),
            dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom, !isBlend());

    renderer->drawRenderNode(renderNode.get(), dirtyRect, RenderNode::kReplayFlag_ClipChildren);

    renderer->finish();

    dirtyRect.setEmpty();

    deferredUpdateScheduled = false;
    renderNode = nullptr;
}

void Layer::postDecStrong() {
    renderState.postDecStrong(this);
}

}; // namespace uirenderer
}; // namespace android
