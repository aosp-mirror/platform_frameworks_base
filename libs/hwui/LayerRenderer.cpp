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

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Rendering
///////////////////////////////////////////////////////////////////////////////

void LayerRenderer::prepare(bool opaque) {
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &mPreviousFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, mFbo);
    OpenGLRenderer::prepare(opaque);
}

void LayerRenderer::finish() {
    OpenGLRenderer::finish();
    glBindFramebuffer(GL_FRAMEBUFFER, mPreviousFbo);
}

///////////////////////////////////////////////////////////////////////////////
// Static functions
///////////////////////////////////////////////////////////////////////////////

GLuint LayerRenderer::createLayer(uint32_t width, uint32_t height,
        uint32_t* layerWidth, uint32_t* layerHeight, GLuint* texture) {
    GLuint previousFbo;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, (GLint*) &previousFbo);

    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);

    glActiveTexture(GL_TEXTURE0);
    glGenTextures(1, texture);
    glBindTexture(GL_TEXTURE_2D, *texture);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, NULL);

    if (glGetError() != GL_NO_ERROR) {
        glDeleteBuffers(1, &fbo);
        glDeleteTextures(1, texture);
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        return 0;
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                *texture, 0);

    if (glGetError() != GL_NO_ERROR) {
        glDeleteBuffers(1, &fbo);
        glDeleteTextures(1, texture);
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        return 0;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

    *layerWidth = width;
    *layerHeight = height;

    return fbo;
}

void LayerRenderer::resizeLayer(GLuint fbo, GLuint texture, uint32_t width, uint32_t height,
        uint32_t* layerWidth, uint32_t* layerHeight) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, NULL);

    if (glGetError() != GL_NO_ERROR) {
        glDeleteBuffers(1, &fbo);
        glDeleteTextures(1, texture);
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);

        *layerWidth = 0;
        *layerHeight = 0;

        return;
    }

    *layerWidth = width;
    *layerHeight = height;
}

void LayerRenderer::destroyLayer(GLuint fbo, GLuint texture) {
    if (fbo) glDeleteFramebuffers(1, &fbo);
    if (texture) glDeleteTextures(1, &texture);
}

}; // namespace uirenderer
}; // namespace android
