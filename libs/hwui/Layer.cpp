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

#include "Layer.h"
#include "LayerRenderer.h"
#include "OpenGLRenderer.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

Layer::Layer(const uint32_t layerWidth, const uint32_t layerHeight) {
    mesh = NULL;
    meshIndices = NULL;
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
    stencil = 0;
    debugDrawUpdate = false;
    Caches::getInstance().resourceCache.incrementRefcount(this);
}

Layer::~Layer() {
    if (mesh) delete mesh;
    if (meshIndices) delete meshIndices;
    if (colorFilter) Caches::getInstance().resourceCache.decrementRefcount(colorFilter);
    removeFbo();
    deleteTexture();
}

void Layer::removeFbo(bool flush) {
    if (stencil) {
        // TODO: recycle & cache instead of simply deleting
        GLuint previousFbo;
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &previousFbo);
        if (fbo != previousFbo) glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
        if (fbo != previousFbo) glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

        glDeleteRenderbuffers(1, &stencil);
        stencil = 0;
    }

    if (fbo) {
        if (flush) LayerRenderer::flushLayer(this);
        // If put fails the cache will delete the FBO
        Caches::getInstance().fboCache.put(fbo);
        fbo = 0;
    }
}

void Layer::setPaint(SkPaint* paint) {
    OpenGLRenderer::getAlphaAndModeDirect(paint, &alpha, &mode);
}

void Layer::setColorFilter(SkiaColorFilter* filter) {
    if (colorFilter) {
        Caches::getInstance().resourceCache.decrementRefcount(colorFilter);
    }
    colorFilter = filter;
    if (colorFilter) {
        Caches::getInstance().resourceCache.incrementRefcount(colorFilter);
    }
}

}; // namespace uirenderer
}; // namespace android
