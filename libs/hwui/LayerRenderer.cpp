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

#include "LayerRenderer.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Rendering
///////////////////////////////////////////////////////////////////////////////

void LayerRenderer::prepare(bool opaque) {
    LAYER_RENDERER_LOGD("Rendering into layer, fbo = %d", mLayer->fbo);

    glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &mPreviousFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, mLayer->fbo);

    OpenGLRenderer::prepare(opaque);
}

void LayerRenderer::finish() {
    OpenGLRenderer::finish();
    glBindFramebuffer(GL_FRAMEBUFFER, mPreviousFbo);

    LAYER_RENDERER_LOGD("Finished rendering into layer, fbo = %d", mLayer->mFbo);
}

///////////////////////////////////////////////////////////////////////////////
// Static functions
///////////////////////////////////////////////////////////////////////////////

Layer* LayerRenderer::createLayer(uint32_t width, uint32_t height, bool isOpaque) {
    LAYER_RENDERER_LOGD("Creating new layer %dx%d", width, height);

    Layer* layer = new Layer(width, height);

    GLuint previousFbo;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &previousFbo);

    glGenFramebuffers(1, &layer->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);

    if (glGetError() != GL_NO_ERROR) {
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glDeleteBuffers(1, &layer->fbo);
        return 0;
    }

    glActiveTexture(GL_TEXTURE0);
    glGenTextures(1, &layer->texture);
    glBindTexture(GL_TEXTURE_2D, layer->texture);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, NULL);

    if (glGetError() != GL_NO_ERROR) {
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glDeleteBuffers(1, &layer->fbo);
        glDeleteTextures(1, &layer->texture);
        delete layer;
        return 0;
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->texture, 0);

    if (glGetError() != GL_NO_ERROR) {
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glDeleteBuffers(1, &layer->fbo);
        glDeleteTextures(1, &layer->texture);
        delete layer;
        return 0;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

    layer->layer.set(0.0f, 0.0f, width, height);
    layer->texCoords.set(0.0f, 1.0f, 1.0f, 0.0f);
    layer->alpha = 255;
    layer->mode = SkXfermode::kSrcOver_Mode;
    layer->blend = !isOpaque;
    layer->empty = false;
    layer->colorFilter = NULL;

    return layer;
}

bool LayerRenderer::resizeLayer(Layer* layer, uint32_t width, uint32_t height) {
    if (layer) {
        LAYER_RENDERER_LOGD("Resizing layer fbo = %d to %dx%d", layer->fbo, width, height);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, layer->texture);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, NULL);

        if (glGetError() != GL_NO_ERROR) {
            glDeleteBuffers(1, &layer->fbo);
            glDeleteTextures(1, &layer->texture);

            layer->width = 0;
            layer->height = 0;
            layer->fbo = 0;
            layer->texture = 0;

            return false;
        }

        layer->width = width;
        layer->height = height;
    }
    return true;
}

void LayerRenderer::destroyLayer(Layer* layer) {
    if (layer) {
        LAYER_RENDERER_LOGD("Destroying layer, fbo = %d", layer->fbo);

        if (layer->fbo) glDeleteFramebuffers(1, &layer->fbo);
        if (layer->texture) glDeleteTextures(1, &layer->texture);

        delete layer;
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
