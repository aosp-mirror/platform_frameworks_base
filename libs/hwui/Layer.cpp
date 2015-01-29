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

#define LOG_TAG "OpenGLRenderer"

#include <utils/Log.h>

#include "Caches.h"
#include "DeferredDisplayList.h"
#include "Layer.h"
#include "LayerRenderer.h"
#include "OpenGLRenderer.h"
#include "RenderNode.h"
#include "RenderState.h"
#include "utils/TraceUtils.h"

#define ATRACE_LAYER_WORK(label) \
    ATRACE_FORMAT("%s HW Layer DisplayList %s %ux%u", \
            label, \
            (renderNode.get() != NULL) ? renderNode->getName() : "", \
            getWidth(), getHeight())

namespace android {
namespace uirenderer {

Layer::Layer(Type layerType, RenderState& renderState, const uint32_t layerWidth, const uint32_t layerHeight)
        : state(kState_Uncached)
        , caches(Caches::getInstance())
        , renderState(renderState)
        , texture(caches)
        , type(layerType) {
    // TODO: This is a violation of Android's typical ref counting, but it
    // preserves the old inc/dec ref locations. This should be changed...
    incStrong(0);
    mesh = NULL;
    meshElementCount = 0;
    cacheable = true;
    dirty = false;
    renderTarget = GL_TEXTURE_2D;
    texture.width = layerWidth;
    texture.height = layerHeight;
    colorFilter = NULL;
    deferredUpdateScheduled = false;
    renderer = NULL;
    renderNode = NULL;
    fbo = 0;
    stencil = NULL;
    debugDrawUpdate = false;
    hasDrawnSinceUpdate = false;
    forceFilter = false;
    deferredList = NULL;
    convexMask = NULL;
    rendererLightPosDirty = true;
    wasBuildLayered = false;
    renderState.registerLayer(this);
}

Layer::~Layer() {
    renderState.unregisterLayer(this);
    SkSafeUnref(colorFilter);

    if (stencil || fbo || texture.id) {
        renderState.requireGLContext();
        removeFbo();
        deleteTexture();
    }

    delete[] mesh;
    delete deferredList;
    delete renderer;
}

void Layer::onGlContextLost() {
    removeFbo();
    deleteTexture();
}

uint32_t Layer::computeIdealWidth(uint32_t layerWidth) {
    return uint32_t(ceilf(layerWidth / float(LAYER_SIZE)) * LAYER_SIZE);
}

uint32_t Layer::computeIdealHeight(uint32_t layerHeight) {
    return uint32_t(ceilf(layerHeight / float(LAYER_SIZE)) * LAYER_SIZE);
}

void Layer::requireRenderer() {
    if (!renderer) {
        renderer = new LayerRenderer(renderState, this);
        renderer->initProperties();
    }
}

void Layer::updateLightPosFromRenderer(const OpenGLRenderer& rootRenderer) {
    if (renderer && rendererLightPosDirty) {
        // re-init renderer's light position, based upon last cached location in window
        Vector3 lightPos = rootRenderer.getLightCenter();
        cachedInvTransformInWindow.mapPoint3d(lightPos);
        renderer->initLight(lightPos, rootRenderer.getLightRadius(),
                rootRenderer.getAmbientShadowAlpha(), rootRenderer.getSpotShadowAlpha());
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
        caches.activeTexture(0);
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
        stencil = NULL;
    }

    if (fbo) {
        if (flush) LayerRenderer::flushLayer(renderState, this);
        // If put fails the cache will delete the FBO
        caches.fboCache.put(fbo);
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
    OpenGLRenderer::getAlphaAndModeDirect(paint, &alpha, &mode);
    setColorFilter((paint) ? paint->getColorFilter() : NULL);
}

void Layer::setColorFilter(SkColorFilter* filter) {
    SkRefCnt_SafeAssign(colorFilter, filter);
}

void Layer::bindTexture() const {
    if (texture.id) {
        caches.bindTexture(renderTarget, texture.id);
    }
}

void Layer::bindStencilRenderBuffer() const {
    if (stencil) {
        stencil->bind();
    }
}

void Layer::generateTexture() {
    if (!texture.id) {
        glGenTextures(1, &texture.id);
    }
}

void Layer::deleteTexture() {
    if (texture.id) {
        texture.deleteTexture();
        texture.id = 0;
    }
}

void Layer::clearTexture() {
    caches.unbindTexture(texture.id);
    texture.id = 0;
}

void Layer::allocateTexture() {
#if DEBUG_LAYERS
    ALOGD("  Allocate layer: %dx%d", getWidth(), getHeight());
#endif
    if (texture.id) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(renderTarget, 0, GL_RGBA, getWidth(), getHeight(), 0,
                GL_RGBA, GL_UNSIGNED_BYTE, NULL);
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

    delete deferredList;
    deferredList = new DeferredDisplayList(dirtyRect);

    DeferStateStruct deferredState(*deferredList, *renderer,
            RenderNode::kReplayFlag_ClipChildren);

    renderer->setViewport(width, height);
    renderer->setupFrameState(dirtyRect.left, dirtyRect.top,
            dirtyRect.right, dirtyRect.bottom, !isBlend());

    renderNode->computeOrdering();
    renderNode->defer(deferredState, 0);

    deferredUpdateScheduled = false;
}

void Layer::cancelDefer() {
    renderNode = NULL;
    deferredUpdateScheduled = false;
    if (deferredList) {
        delete deferredList;
        deferredList = NULL;
    }
}

void Layer::flush() {
    // renderer is checked as layer may be destroyed/put in layer cache with flush scheduled
    if (deferredList && renderer) {
        ATRACE_LAYER_WORK("Issue");
        renderer->startMark((renderNode.get() != NULL) ? renderNode->getName() : "Layer");

        renderer->setViewport(layer.getWidth(), layer.getHeight());
        renderer->prepareDirty(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom,
                !isBlend());

        deferredList->flush(*renderer, dirtyRect);

        renderer->finish();

        dirtyRect.setEmpty();
        renderNode = NULL;

        renderer->endMark();
    }
}

void Layer::render(const OpenGLRenderer& rootRenderer) {
    ATRACE_LAYER_WORK("Direct-Issue");

    updateLightPosFromRenderer(rootRenderer);
    renderer->setViewport(layer.getWidth(), layer.getHeight());
    renderer->prepareDirty(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom,
            !isBlend());

    renderer->drawRenderNode(renderNode.get(), dirtyRect, RenderNode::kReplayFlag_ClipChildren);

    renderer->finish();

    dirtyRect.setEmpty();

    deferredUpdateScheduled = false;
    renderNode = NULL;
}

void Layer::postDecStrong() {
    renderState.postDecStrong(this);
}

}; // namespace uirenderer
}; // namespace android
