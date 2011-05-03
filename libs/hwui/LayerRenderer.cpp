/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <ui/Rect.h>

#include "LayerCache.h"
#include "LayerRenderer.h"
#include "Matrix.h"
#include "Properties.h"
#include "Rect.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Rendering
///////////////////////////////////////////////////////////////////////////////

void LayerRenderer::prepareDirty(float left, float top, float right, float bottom, bool opaque) {
    LAYER_RENDERER_LOGD("Rendering into layer, fbo = %d", mLayer->fbo);

    glBindFramebuffer(GL_FRAMEBUFFER, mLayer->fbo);

    const float width = mLayer->layer.getWidth();
    const float height = mLayer->layer.getHeight();

#if RENDER_LAYERS_AS_REGIONS
    Rect dirty(left, top, right, bottom);
    if (dirty.isEmpty() || (dirty.left <= 0 && dirty.top <= 0 &&
            dirty.right >= width && dirty.bottom >= height)) {
        mLayer->region.clear();
        dirty.set(0.0f, 0.0f, width, height);
    } else {
        dirty.intersect(0.0f, 0.0f, width, height);
        android::Rect r(dirty.left, dirty.top, dirty.right, dirty.bottom);
        mLayer->region.subtractSelf(r);
    }

    OpenGLRenderer::prepareDirty(dirty.left, dirty.top, dirty.right, dirty.bottom, opaque);
#else
    OpenGLRenderer::prepareDirty(0.0f, 0.0f, width, height, opaque);
#endif
}

void LayerRenderer::finish() {
    OpenGLRenderer::finish();

    generateMesh();

    LAYER_RENDERER_LOGD("Finished rendering into layer, fbo = %d", mLayer->fbo);

    // No need to unbind our FBO, this will be taken care of by the caller
    // who will invoke OpenGLRenderer::resume()
}

GLint LayerRenderer::getTargetFbo() {
    return mLayer->fbo;
}

///////////////////////////////////////////////////////////////////////////////
// Dirty region tracking
///////////////////////////////////////////////////////////////////////////////

bool LayerRenderer::hasLayer() {
    return true;
}

Region* LayerRenderer::getRegion() {
#if RENDER_LAYERS_AS_REGIONS
    if (getSnapshot()->flags & Snapshot::kFlagFboTarget) {
        return OpenGLRenderer::getRegion();
    }
    return &mLayer->region;
#else
    return OpenGLRenderer::getRegion();
#endif
}

void LayerRenderer::generateMesh() {
#if RENDER_LAYERS_AS_REGIONS
    if (mLayer->region.isRect() || mLayer->region.isEmpty()) {
        if (mLayer->mesh) {
            delete mLayer->mesh;
            delete mLayer->meshIndices;

            mLayer->mesh = NULL;
            mLayer->meshIndices = NULL;
            mLayer->meshElementCount = 0;
        }

        mLayer->setRegionAsRect();
        return;
    }

    size_t count;
    const android::Rect* rects = mLayer->region.getArray(&count);

    GLsizei elementCount = count * 6;

    if (mLayer->mesh && mLayer->meshElementCount < elementCount) {
        delete mLayer->mesh;
        delete mLayer->meshIndices;

        mLayer->mesh = NULL;
        mLayer->meshIndices = NULL;
    }

    bool rebuildIndices = false;
    if (!mLayer->mesh) {
        mLayer->mesh = new TextureVertex[count * 4];
        mLayer->meshIndices = new uint16_t[elementCount];
        rebuildIndices = true;
    }
    mLayer->meshElementCount = elementCount;

    const float texX = 1.0f / float(mLayer->width);
    const float texY = 1.0f / float(mLayer->height);
    const float height = mLayer->layer.getHeight();

    TextureVertex* mesh = mLayer->mesh;
    uint16_t* indices = mLayer->meshIndices;

    for (size_t i = 0; i < count; i++) {
        const android::Rect* r = &rects[i];

        const float u1 = r->left * texX;
        const float v1 = (height - r->top) * texY;
        const float u2 = r->right * texX;
        const float v2 = (height - r->bottom) * texY;

        TextureVertex::set(mesh++, r->left, r->top, u1, v1);
        TextureVertex::set(mesh++, r->right, r->top, u2, v1);
        TextureVertex::set(mesh++, r->left, r->bottom, u1, v2);
        TextureVertex::set(mesh++, r->right, r->bottom, u2, v2);

        if (rebuildIndices) {
            uint16_t quad = i * 4;
            int index = i * 6;
            indices[index    ] = quad;       // top-left
            indices[index + 1] = quad + 1;   // top-right
            indices[index + 2] = quad + 2;   // bottom-left
            indices[index + 3] = quad + 2;   // bottom-left
            indices[index + 4] = quad + 1;   // top-right
            indices[index + 5] = quad + 3;   // bottom-right
        }
    }
#endif
}

///////////////////////////////////////////////////////////////////////////////
// Layers management
///////////////////////////////////////////////////////////////////////////////

Layer* LayerRenderer::createTextureLayer() {
    LAYER_RENDERER_LOGD("Creating new texture layer");

    Layer* layer = new Layer(0, 0);
    layer->isCacheable = false;
    layer->isTextureLayer = true;
    layer->blend = true;
    layer->empty = true;
    layer->fbo = 0;
    layer->colorFilter = NULL;
    layer->fbo = 0;
    layer->layer.set(0.0f, 0.0f, 0.0f, 0.0f);
    layer->texCoords.set(0.0f, 1.0f, 0.0f, 1.0f);
    layer->alpha = 255;
    layer->mode = SkXfermode::kSrcOver_Mode;
    layer->colorFilter = NULL;
    layer->region.clear();

    glActiveTexture(GL_TEXTURE0);
    glGenTextures(1, &layer->texture);

    return layer;
}

Layer* LayerRenderer::createLayer(uint32_t width, uint32_t height, bool isOpaque) {
    LAYER_RENDERER_LOGD("Creating new layer %dx%d", width, height);

    GLuint fbo = Caches::getInstance().fboCache.get();
    if (!fbo) {
        LOGW("Could not obtain an FBO");
        return NULL;
    }

    glActiveTexture(GL_TEXTURE0);
    Layer* layer = Caches::getInstance().layerCache.get(width, height);
    if (!layer) {
        LOGW("Could not obtain a layer");
        return NULL;
    }

    layer->fbo = fbo;
    layer->layer.set(0.0f, 0.0f, width, height);
    layer->texCoords.set(0.0f, height / float(layer->height),
            width / float(layer->width), 0.0f);
    layer->alpha = 255;
    layer->mode = SkXfermode::kSrcOver_Mode;
    layer->blend = !isOpaque;
    layer->colorFilter = NULL;
    layer->region.clear();

    GLuint previousFbo;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &previousFbo);

    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
    glBindTexture(GL_TEXTURE_2D, layer->texture);

    // Initialize the texture if needed
    if (layer->empty) {
        layer->empty = false;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, layer->width, layer->height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, NULL);

        if (glGetError() != GL_NO_ERROR) {
            LOGD("Could not allocate texture");
            glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
            glDeleteTextures(1, &layer->texture);
            Caches::getInstance().fboCache.put(fbo);
            delete layer;
            return NULL;
        }
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->texture, 0);

    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

    return layer;
}

bool LayerRenderer::resizeLayer(Layer* layer, uint32_t width, uint32_t height) {
    if (layer) {
        LAYER_RENDERER_LOGD("Resizing layer fbo = %d to %dx%d", layer->fbo, width, height);

        if (Caches::getInstance().layerCache.resize(layer, width, height)) {
            layer->layer.set(0.0f, 0.0f, width, height);
            layer->texCoords.set(0.0f, height / float(layer->height),
                    width / float(layer->width), 0.0f);
        } else {
            if (layer->texture) glDeleteTextures(1, &layer->texture);
            delete layer;
            return false;
        }
    }

    return true;
}

void LayerRenderer::updateTextureLayer(Layer* layer, uint32_t width, uint32_t height,
        GLenum renderTarget, float* transform) {
    if (layer) {
        layer->width = width;
        layer->height = height;
        layer->layer.set(0.0f, 0.0f, width, height);
        layer->region.set(width, height);
        layer->regionRect.set(0.0f, 0.0f, width, height);
        layer->texTransform.load(transform);
        layer->renderTarget = renderTarget;

        glBindTexture(layer->renderTarget, layer->texture);

        glTexParameteri(layer->renderTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(layer->renderTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexParameteri(layer->renderTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(layer->renderTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
}

void LayerRenderer::destroyLayer(Layer* layer) {
    if (layer) {
        LAYER_RENDERER_LOGD("Destroying layer, fbo = %d", layer->fbo);

        if (layer->fbo) {
            Caches::getInstance().fboCache.put(layer->fbo);
        }

        if (!Caches::getInstance().layerCache.put(layer)) {
            if (layer->texture) glDeleteTextures(1, &layer->texture);
            delete layer;
        } else {
            layer->region.clear();
        }
    }
}

void LayerRenderer::destroyLayerDeferred(Layer* layer) {
    if (layer) {
        LAYER_RENDERER_LOGD("Deferring layer destruction, fbo = %d", layer->fbo);

        Caches::getInstance().deleteLayerDeferred(layer);
    }
}

}; // namespace uirenderer
}; // namespace android
