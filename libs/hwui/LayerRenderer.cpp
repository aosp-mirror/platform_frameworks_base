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

LayerRenderer::LayerRenderer(Layer* layer): mLayer(layer) {
}

LayerRenderer::~LayerRenderer() {
}

void LayerRenderer::prepareDirty(float left, float top, float right, float bottom, bool opaque) {
    LAYER_RENDERER_LOGD("Rendering into layer, fbo = %d", mLayer->getFbo());

    glBindFramebuffer(GL_FRAMEBUFFER, mLayer->getFbo());

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

    LAYER_RENDERER_LOGD("Finished rendering into layer, fbo = %d", mLayer->getFbo());

    // No need to unbind our FBO, this will be taken care of by the caller
    // who will invoke OpenGLRenderer::resume()
}

GLint LayerRenderer::getTargetFbo() {
    return mLayer->getFbo();
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

// TODO: This implementation is flawed and can generate T-junctions
//       in the mesh, which will in turn produce cracks when the
//       mesh is rotated/skewed. The easiest way to fix this would
//       be, for each row, to add new vertices shared with the previous
//       row when the two rows share an edge.
//       In practice, T-junctions do not appear often so this has yet
//       to be fixed.
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

    const float texX = 1.0f / float(mLayer->getWidth());
    const float texY = 1.0f / float(mLayer->getHeight());
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

Layer* LayerRenderer::createLayer(uint32_t width, uint32_t height, bool isOpaque) {
    LAYER_RENDERER_LOGD("Requesting new render layer %dx%d", width, height);

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

    layer->setFbo(fbo);
    layer->layer.set(0.0f, 0.0f, width, height);
    layer->texCoords.set(0.0f, height / float(layer->getHeight()),
            width / float(layer->getWidth()), 0.0f);
    layer->setAlpha(255, SkXfermode::kSrcOver_Mode);
    layer->setBlend(!isOpaque);
    layer->setColorFilter(NULL);
    layer->region.clear();

    GLuint previousFbo;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &previousFbo);

    glBindFramebuffer(GL_FRAMEBUFFER, layer->getFbo());
    layer->bindTexture();

    // Initialize the texture if needed
    if (layer->isEmpty()) {
        layer->setEmpty(false);
        layer->allocateTexture(GL_RGBA, GL_UNSIGNED_BYTE);

        if (glGetError() != GL_NO_ERROR) {
            LOGD("Could not allocate texture for layer (fbo=%d %dx%d)",
                    fbo, width, height);

            glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
            Caches::getInstance().fboCache.put(fbo);

            layer->deleteTexture();
            delete layer;

            return NULL;
        }
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->getTexture(), 0);

    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

    return layer;
}

bool LayerRenderer::resizeLayer(Layer* layer, uint32_t width, uint32_t height) {
    if (layer) {
        LAYER_RENDERER_LOGD("Resizing layer fbo = %d to %dx%d", layer->getFbo(), width, height);

        if (Caches::getInstance().layerCache.resize(layer, width, height)) {
            layer->layer.set(0.0f, 0.0f, width, height);
            layer->texCoords.set(0.0f, height / float(layer->getHeight()),
                    width / float(layer->getWidth()), 0.0f);
        } else {
            layer->deleteTexture();
            delete layer;
            return false;
        }
    }

    return true;
}

Layer* LayerRenderer::createTextureLayer(bool isOpaque) {
    LAYER_RENDERER_LOGD("Creating new texture layer");

    Layer* layer = new Layer(0, 0);
    layer->setCacheable(false);
    layer->setTextureLayer(true);
    layer->setBlend(!isOpaque);
    layer->setEmpty(true);
    layer->setFbo(0);
    layer->setAlpha(255, SkXfermode::kSrcOver_Mode);
    layer->layer.set(0.0f, 0.0f, 0.0f, 0.0f);
    layer->texCoords.set(0.0f, 1.0f, 1.0f, 0.0f);
    layer->region.clear();
    layer->setRenderTarget(GL_NONE); // see ::updateTextureLayer()

    glActiveTexture(GL_TEXTURE0);
    layer->generateTexture();

    return layer;
}

void LayerRenderer::updateTextureLayer(Layer* layer, uint32_t width, uint32_t height,
        bool isOpaque, GLenum renderTarget, float* transform) {
    if (layer) {
        layer->setBlend(!isOpaque);
        layer->setSize(width, height);
        layer->layer.set(0.0f, 0.0f, width, height);
        layer->region.set(width, height);
        layer->regionRect.set(0.0f, 0.0f, width, height);
        layer->getTexTransform().load(transform);

        if (renderTarget != layer->getRenderTarget()) {
            layer->setRenderTarget(renderTarget);
            layer->bindTexture();
            layer->setFilter(GL_NEAREST, false, true);
            layer->setWrap(GL_CLAMP_TO_EDGE, false, true);
        }
    }
}

void LayerRenderer::destroyLayer(Layer* layer) {
    if (layer) {
        LAYER_RENDERER_LOGD("Recycling layer, %dx%d fbo = %d",
                layer->getWidth(), layer->getHeight(), layer->getFbo());

        if (layer->getFbo()) {
            Caches::getInstance().fboCache.put(layer->getFbo());
        }

        if (!Caches::getInstance().layerCache.put(layer)) {
            LAYER_RENDERER_LOGD("  Destroyed!");
            layer->deleteTexture();
            delete layer;
        } else {
            LAYER_RENDERER_LOGD("  Cached!");
#if DEBUG_LAYER_RENDERER
            Caches::getInstance().layerCache.dump();
#endif
            layer->region.clear();
        }
    }
}

void LayerRenderer::destroyLayerDeferred(Layer* layer) {
    if (layer) {
        LAYER_RENDERER_LOGD("Deferring layer destruction, fbo = %d", layer->getFbo());

        Caches::getInstance().deleteLayerDeferred(layer);
    }
}

bool LayerRenderer::copyLayer(Layer* layer, SkBitmap* bitmap) {
    Caches& caches = Caches::getInstance();
    if (layer && layer->isTextureLayer() && bitmap->width() <= caches.maxTextureSize &&
            bitmap->height() <= caches.maxTextureSize) {

        GLuint fbo = caches.fboCache.get();
        if (!fbo) {
            LOGW("Could not obtain an FBO");
            return false;
        }

        SkAutoLockPixels alp(*bitmap);

        GLuint texture;
        GLuint previousFbo;

        GLenum format;
        GLenum type;

        GLenum error = GL_NO_ERROR;
        bool status = false;

        switch (bitmap->config()) {
            case SkBitmap::kA8_Config:
                format = GL_ALPHA;
                type = GL_UNSIGNED_BYTE;
                break;
            case SkBitmap::kRGB_565_Config:
                format = GL_RGB;
                type = GL_UNSIGNED_SHORT_5_6_5;
                break;
            case SkBitmap::kARGB_4444_Config:
                format = GL_RGBA;
                type = GL_UNSIGNED_SHORT_4_4_4_4;
                break;
            case SkBitmap::kARGB_8888_Config:
            default:
                format = GL_RGBA;
                type = GL_UNSIGNED_BYTE;
                break;
        }

        float alpha = layer->getAlpha();
        SkXfermode::Mode mode = layer->getMode();

        layer->setAlpha(255, SkXfermode::kSrc_Mode);
        layer->setFbo(fbo);

        glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &previousFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        glGenTextures(1, &texture);
        if ((error = glGetError()) != GL_NO_ERROR) goto error;

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glTexImage2D(GL_TEXTURE_2D, 0, format, bitmap->width(), bitmap->height(),
                0, format, type, NULL);
        if ((error = glGetError()) != GL_NO_ERROR) goto error;

        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, texture, 0);
        if ((error = glGetError()) != GL_NO_ERROR) goto error;

        {
            LayerRenderer renderer(layer);
            renderer.setViewport(bitmap->width(), bitmap->height());
            renderer.OpenGLRenderer::prepareDirty(0.0f, 0.0f,
                    bitmap->width(), bitmap->height(), !layer->isBlend());

            glDisable(GL_SCISSOR_TEST);
            renderer.translate(0.0f, bitmap->height());
            renderer.scale(1.0f, -1.0f);

            mat4 texTransform(layer->getTexTransform());

            mat4 invert;
            invert.translate(0.0f, 1.0f, 0.0f);
            invert.scale(1.0f, -1.0f, 1.0f);
            layer->getTexTransform().multiply(invert);

            if ((error = glGetError()) != GL_NO_ERROR) goto error;

            {
                Rect bounds;
                bounds.set(0.0f, 0.0f, bitmap->width(), bitmap->height());
                renderer.drawTextureLayer(layer, bounds);

                glReadPixels(0, 0, bitmap->width(), bitmap->height(), format,
                        type, bitmap->getPixels());

                if ((error = glGetError()) != GL_NO_ERROR) goto error;
            }

            layer->getTexTransform().load(texTransform);
            status = true;
        }

error:
#if DEBUG_OPENGL
        if (error != GL_NO_ERROR) {
            LOGD("GL error while copying layer into bitmap = 0x%x", error);
        }
#endif

        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        layer->setAlpha(alpha, mode);
        layer->setFbo(0);
        glDeleteTextures(1, &texture);
        caches.fboCache.put(fbo);

        return status;
    }
    return false;
}

}; // namespace uirenderer
}; // namespace android
