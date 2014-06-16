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

#include "DisplayList.h"
#include "DeferredDisplayList.h"
#include "Layer.h"
#include "LayerRenderer.h"
#include "OpenGLRenderer.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

Layer::Layer(const uint32_t layerWidth, const uint32_t layerHeight):
        caches(Caches::getInstance()), texture(caches) {
    mesh = NULL;
    meshElementCount = 0;
    cacheable = true;
    dirty = false;
    textureLayer = false;
    renderTarget = GL_TEXTURE_2D;
    texture.width = layerWidth;
    texture.height = layerHeight;
    colorFilter = NULL;
    deferredUpdateScheduled = false;
    renderer = NULL;
    displayList = NULL;
    fbo = 0;
    stencil = NULL;
    debugDrawUpdate = false;
    hasDrawnSinceUpdate = false;
    deferredList = NULL;
    caches.resourceCache.incrementRefcount(this);
}

Layer::~Layer() {
    if (colorFilter) caches.resourceCache.decrementRefcount(colorFilter);
    removeFbo();
    deleteTexture();

    delete[] mesh;
    delete deferredList;
}

uint32_t Layer::computeIdealWidth(uint32_t layerWidth) {
    return uint32_t(ceilf(layerWidth / float(LAYER_SIZE)) * LAYER_SIZE);
}

uint32_t Layer::computeIdealHeight(uint32_t layerHeight) {
    return uint32_t(ceilf(layerHeight / float(LAYER_SIZE)) * LAYER_SIZE);
}

bool Layer::resize(const uint32_t width, const uint32_t height) {
    uint32_t desiredWidth = computeIdealWidth(width);
    uint32_t desiredHeight = computeIdealWidth(height);

    if (desiredWidth <= getWidth() && desiredHeight <= getHeight()) {
        return true;
    }

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
        GLuint previousFbo;
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &previousFbo);
        if (fbo != previousFbo) glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
        if (fbo != previousFbo) glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

        caches.renderBufferCache.put(stencil);
        stencil = NULL;
    }

    if (fbo) {
        if (flush) LayerRenderer::flushLayer(this);
        // If put fails the cache will delete the FBO
        caches.fboCache.put(fbo);
        fbo = 0;
    }
}

void Layer::setPaint(SkPaint* paint) {
    OpenGLRenderer::getAlphaAndModeDirect(paint, &alpha, &mode);
}

void Layer::setColorFilter(SkiaColorFilter* filter) {
    if (colorFilter) {
        caches.resourceCache.decrementRefcount(colorFilter);
    }
    colorFilter = filter;
    if (colorFilter) {
        caches.resourceCache.incrementRefcount(colorFilter);
    }
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

void Layer::defer() {
    const float width = layer.getWidth();
    const float height = layer.getHeight();

    if (dirtyRect.isEmpty() || (dirtyRect.left <= 0 && dirtyRect.top <= 0 &&
            dirtyRect.right >= width && dirtyRect.bottom >= height)) {
        dirtyRect.set(0, 0, width, height);
    }

    if (deferredList) {
        deferredList->reset(dirtyRect);
    } else {
        deferredList = new DeferredDisplayList(dirtyRect);
    }
    DeferStateStruct deferredState(*deferredList, *renderer,
            DisplayList::kReplayFlag_ClipChildren);

    renderer->initViewport(width, height);
    renderer->setupFrameState(dirtyRect.left, dirtyRect.top,
            dirtyRect.right, dirtyRect.bottom, !isBlend());

    displayList->defer(deferredState, 0);

    deferredUpdateScheduled = false;
}

void Layer::cancelDefer() {
    renderer = NULL;
    displayList = NULL;
    deferredUpdateScheduled = false;
    if (deferredList) {
        delete deferredList;
        deferredList = NULL;
    }
}

void Layer::flush() {
    // renderer is checked as layer may be destroyed/put in layer cache with flush scheduled
    if (deferredList && renderer) {
        renderer->setViewport(layer.getWidth(), layer.getHeight());
        renderer->prepareDirty(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom,
                !isBlend());

        deferredList->flush(*renderer, dirtyRect);

        renderer->finish();
        renderer = NULL;

        dirtyRect.setEmpty();
        displayList = NULL;
    }
}

void Layer::render() {
    renderer->setViewport(layer.getWidth(), layer.getHeight());
    renderer->prepareDirty(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom,
            !isBlend());

    renderer->drawDisplayList(displayList, dirtyRect, DisplayList::kReplayFlag_ClipChildren);

    renderer->finish();
    renderer = NULL;

    dirtyRect.setEmpty();

    deferredUpdateScheduled = false;
    displayList = NULL;
}

}; // namespace uirenderer
}; // namespace android
