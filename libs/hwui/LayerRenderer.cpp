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
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <ui/Rect.h>

#include <private/hwui/DrawGlInfo.h>

#include "RenderState.h"
#include "LayerCache.h"
#include "LayerRenderer.h"
#include "Matrix.h"
#include "Properties.h"
#include "Rect.h"
#include "utils/TraceUtils.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Rendering
///////////////////////////////////////////////////////////////////////////////

LayerRenderer::LayerRenderer(RenderState& renderState, Layer* layer)
        : OpenGLRenderer(renderState)
        , mLayer(layer) {
}

LayerRenderer::~LayerRenderer() {
}

status_t LayerRenderer::prepareDirty(float left, float top, float right, float bottom,
        bool opaque) {
    LAYER_RENDERER_LOGD("Rendering into layer, fbo = %d", mLayer->getFbo());

    renderState().bindFramebuffer(mLayer->getFbo());

    const float width = mLayer->layer.getWidth();
    const float height = mLayer->layer.getHeight();

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
    mLayer->clipRect.set(dirty);

    return OpenGLRenderer::prepareDirty(dirty.left, dirty.top, dirty.right, dirty.bottom, opaque);
}

status_t LayerRenderer::clear(float left, float top, float right, float bottom, bool opaque) {
    if (mLayer->isDirty()) {
        getCaches().disableScissor();
        glClear(GL_COLOR_BUFFER_BIT);

        getCaches().resetScissor();
        mLayer->setDirty(false);

        return DrawGlInfo::kStatusDone;
    }

    return OpenGLRenderer::clear(left, top, right, bottom, opaque);
}

void LayerRenderer::finish() {
    OpenGLRenderer::finish();

    generateMesh();

    LAYER_RENDERER_LOGD("Finished rendering into layer, fbo = %d", mLayer->getFbo());

    // No need to unbind our FBO, this will be taken care of by the caller
    // who will invoke OpenGLRenderer::resume()
}

GLuint LayerRenderer::getTargetFbo() const {
    return mLayer->getFbo();
}

bool LayerRenderer::suppressErrorChecks() const {
    return true;
}

///////////////////////////////////////////////////////////////////////////////
// Layer support
///////////////////////////////////////////////////////////////////////////////

bool LayerRenderer::hasLayer() const {
    return true;
}

void LayerRenderer::ensureStencilBuffer() {
    attachStencilBufferToLayer(mLayer);
}

///////////////////////////////////////////////////////////////////////////////
// Dirty region tracking
///////////////////////////////////////////////////////////////////////////////

Region* LayerRenderer::getRegion() const {
    if (currentSnapshot()->flags & Snapshot::kFlagFboTarget) {
        return OpenGLRenderer::getRegion();
    }
    return &mLayer->region;
}

// TODO: This implementation uses a very simple approach to fixing T-junctions which keeps the
//       results as rectangles, and is thus not necessarily efficient in the geometry
//       produced. Eventually, it may be better to develop triangle-based mechanism.
void LayerRenderer::generateMesh() {
    if (mLayer->region.isRect() || mLayer->region.isEmpty()) {
        if (mLayer->mesh) {
            delete[] mLayer->mesh;
            mLayer->mesh = NULL;
            mLayer->meshElementCount = 0;
        }

        mLayer->setRegionAsRect();
        return;
    }

    // avoid T-junctions as they cause artifacts in between the resultant
    // geometry when complex transforms occur.
    // TODO: generate the safeRegion only if necessary based on drawing transform (see
    // OpenGLRenderer::composeLayerRegion())
    Region safeRegion = Region::createTJunctionFreeRegion(mLayer->region);

    size_t count;
    const android::Rect* rects = safeRegion.getArray(&count);

    GLsizei elementCount = count * 6;

    if (mLayer->mesh && mLayer->meshElementCount < elementCount) {
        delete[] mLayer->mesh;
        mLayer->mesh = NULL;
    }

    if (!mLayer->mesh) {
        mLayer->mesh = new TextureVertex[count * 4];
    }
    mLayer->meshElementCount = elementCount;

    const float texX = 1.0f / float(mLayer->getWidth());
    const float texY = 1.0f / float(mLayer->getHeight());
    const float height = mLayer->layer.getHeight();

    TextureVertex* mesh = mLayer->mesh;

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
    }
}

///////////////////////////////////////////////////////////////////////////////
// Layers management
///////////////////////////////////////////////////////////////////////////////

Layer* LayerRenderer::createRenderLayer(RenderState& renderState, uint32_t width, uint32_t height) {
    ATRACE_FORMAT("Allocate %ux%u HW Layer", width, height);
    LAYER_RENDERER_LOGD("Requesting new render layer %dx%d", width, height);

    Caches& caches = Caches::getInstance();
    GLuint fbo = caches.fboCache.get();
    if (!fbo) {
        ALOGW("Could not obtain an FBO");
        return NULL;
    }

    caches.activeTexture(0);
    Layer* layer = caches.layerCache.get(renderState, width, height);
    if (!layer) {
        ALOGW("Could not obtain a layer");
        return NULL;
    }

    // We first obtain a layer before comparing against the max texture size
    // because layers are not allocated at the exact desired size. They are
    // always created slighly larger to improve recycling
    const uint32_t maxTextureSize = caches.maxTextureSize;
    if (layer->getWidth() > maxTextureSize || layer->getHeight() > maxTextureSize) {
        ALOGW("Layer exceeds max. dimensions supported by the GPU (%dx%d, max=%dx%d)",
                width, height, maxTextureSize, maxTextureSize);

        // Creating a new layer always increment its refcount by 1, this allows
        // us to destroy the layer object if one was created for us
        layer->decStrong(0);

        return NULL;
    }

    layer->setFbo(fbo);
    layer->layer.set(0.0f, 0.0f, width, height);
    layer->texCoords.set(0.0f, height / float(layer->getHeight()),
            width / float(layer->getWidth()), 0.0f);
    layer->setAlpha(255, SkXfermode::kSrcOver_Mode);
    layer->setColorFilter(NULL);
    layer->setDirty(true);
    layer->region.clear();

    GLuint previousFbo = renderState.getFramebuffer();

    renderState.bindFramebuffer(layer->getFbo());
    layer->bindTexture();

    // Initialize the texture if needed
    if (layer->isEmpty()) {
        layer->setEmpty(false);
        layer->allocateTexture();

        // This should only happen if we run out of memory
        if (glGetError() != GL_NO_ERROR) {
            ALOGE("Could not allocate texture for layer (fbo=%d %dx%d)", fbo, width, height);
            renderState.bindFramebuffer(previousFbo);
            layer->decStrong(0);
            return NULL;
        }
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->getTexture(), 0);

    renderState.bindFramebuffer(previousFbo);

    return layer;
}

bool LayerRenderer::resizeLayer(Layer* layer, uint32_t width, uint32_t height) {
    if (layer) {
        LAYER_RENDERER_LOGD("Resizing layer fbo = %d to %dx%d", layer->getFbo(), width, height);

        if (layer->resize(width, height)) {
            layer->layer.set(0.0f, 0.0f, width, height);
            layer->texCoords.set(0.0f, height / float(layer->getHeight()),
                    width / float(layer->getWidth()), 0.0f);
        } else {
            return false;
        }
    }

    return true;
}

Layer* LayerRenderer::createTextureLayer(RenderState& renderState) {
    LAYER_RENDERER_LOGD("Creating new texture layer");

    Layer* layer = new Layer(Layer::kType_Texture, renderState, 0, 0);
    layer->setCacheable(false);
    layer->setEmpty(true);
    layer->setFbo(0);
    layer->setAlpha(255, SkXfermode::kSrcOver_Mode);
    layer->layer.set(0.0f, 0.0f, 0.0f, 0.0f);
    layer->texCoords.set(0.0f, 1.0f, 1.0f, 0.0f);
    layer->region.clear();
    layer->setRenderTarget(GL_NONE); // see ::updateTextureLayer()

    Caches::getInstance().activeTexture(0);
    layer->generateTexture();

    return layer;
}

void LayerRenderer::updateTextureLayer(Layer* layer, uint32_t width, uint32_t height,
        bool isOpaque, bool forceFilter, GLenum renderTarget, float* textureTransform) {
    if (layer) {
        layer->setBlend(!isOpaque);
        layer->setForceFilter(forceFilter);
        layer->setSize(width, height);
        layer->layer.set(0.0f, 0.0f, width, height);
        layer->region.set(width, height);
        layer->regionRect.set(0.0f, 0.0f, width, height);
        layer->getTexTransform().load(textureTransform);

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
        ATRACE_FORMAT("Destroy %ux%u HW Layer", layer->getWidth(), layer->getHeight());
        LAYER_RENDERER_LOGD("Recycling layer, %dx%d fbo = %d",
                layer->getWidth(), layer->getHeight(), layer->getFbo());

        if (!Caches::getInstance().layerCache.put(layer)) {
            LAYER_RENDERER_LOGD("  Destroyed!");
            layer->decStrong(0);
        } else {
            LAYER_RENDERER_LOGD("  Cached!");
#if DEBUG_LAYER_RENDERER
            Caches::getInstance().layerCache.dump();
#endif
            layer->removeFbo();
            layer->region.clear();
        }
    }
}

void LayerRenderer::flushLayer(RenderState& renderState, Layer* layer) {
#ifdef GL_EXT_discard_framebuffer
    if (!layer) return;

    GLuint fbo = layer->getFbo();
    if (fbo) {
        // If possible, discard any enqueud operations on deferred
        // rendering architectures
        if (Extensions::getInstance().hasDiscardFramebuffer()) {
            GLuint previousFbo = renderState.getFramebuffer();
            if (fbo != previousFbo) {
                renderState.bindFramebuffer(fbo);
            }

            const GLenum attachments[] = { GL_COLOR_ATTACHMENT0 };
            glDiscardFramebufferEXT(GL_FRAMEBUFFER, 1, attachments);

            if (fbo != previousFbo) {
                renderState.bindFramebuffer(previousFbo);
            }
        }
    }
#endif
}

bool LayerRenderer::copyLayer(RenderState& renderState, Layer* layer, SkBitmap* bitmap) {
    Caches& caches = Caches::getInstance();
    if (layer && bitmap->width() <= caches.maxTextureSize &&
            bitmap->height() <= caches.maxTextureSize) {

        GLuint fbo = caches.fboCache.get();
        if (!fbo) {
            ALOGW("Could not obtain an FBO");
            return false;
        }

        SkAutoLockPixels alp(*bitmap);

        GLuint texture;
        GLuint previousFbo;
        GLsizei previousViewportWidth;
        GLsizei previousViewportHeight;

        GLenum format;
        GLenum type;

        GLenum error = GL_NO_ERROR;
        bool status = false;

        switch (bitmap->colorType()) {
            case kAlpha_8_SkColorType:
                format = GL_ALPHA;
                type = GL_UNSIGNED_BYTE;
                break;
            case kRGB_565_SkColorType:
                format = GL_RGB;
                type = GL_UNSIGNED_SHORT_5_6_5;
                break;
            case kARGB_4444_SkColorType:
                format = GL_RGBA;
                type = GL_UNSIGNED_SHORT_4_4_4_4;
                break;
            case kN32_SkColorType:
            default:
                format = GL_RGBA;
                type = GL_UNSIGNED_BYTE;
                break;
        }

        float alpha = layer->getAlpha();
        SkXfermode::Mode mode = layer->getMode();
        GLuint previousLayerFbo = layer->getFbo();

        layer->setAlpha(255, SkXfermode::kSrc_Mode);
        layer->setFbo(fbo);

        previousFbo = renderState.getFramebuffer();
        renderState.getViewport(&previousViewportWidth, &previousViewportHeight);
        renderState.bindFramebuffer(fbo);

        glGenTextures(1, &texture);
        if ((error = glGetError()) != GL_NO_ERROR) goto error;

        caches.activeTexture(0);
        caches.bindTexture(texture);

        glPixelStorei(GL_PACK_ALIGNMENT, bitmap->bytesPerPixel());

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
            LayerRenderer renderer(renderState, layer);
            renderer.setViewport(bitmap->width(), bitmap->height());
            renderer.OpenGLRenderer::prepareDirty(0.0f, 0.0f,
                    bitmap->width(), bitmap->height(), !layer->isBlend());

            caches.disableScissor();
            renderer.translate(0.0f, bitmap->height());
            renderer.scale(1.0f, -1.0f);

            mat4 texTransform(layer->getTexTransform());

            mat4 invert;
            invert.translate(0.0f, 1.0f);
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
            ALOGD("GL error while copying layer into bitmap = 0x%x", error);
        }
#endif

        renderState.bindFramebuffer(previousFbo);
        layer->setAlpha(alpha, mode);
        layer->setFbo(previousLayerFbo);
        caches.deleteTexture(texture);
        caches.fboCache.put(fbo);
        renderState.setViewport(previousViewportWidth, previousViewportHeight);

        return status;
    }
    return false;
}

}; // namespace uirenderer
}; // namespace android
