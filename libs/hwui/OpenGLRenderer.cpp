/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <SkCanvas.h>

#include <utils/Log.h>

#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define SV(x, y) { { x, y } }
#define FV(x, y, u, v) { { x, y }, { u, v } }

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

const SimpleVertex gDrawColorVertices[] = {
        SV(0.0f, 0.0f),
        SV(1.0f, 0.0f),
        SV(0.0f, 1.0f),
        SV(1.0f, 1.0f)
};
const GLsizei gDrawColorVertexStride = sizeof(SimpleVertex);
const GLsizei gDrawColorVertexCount = 4;

TextureVertex gDrawTextureVertices[] = {
        FV(0.0f, 0.0f, 0.0f, 1.0f),
        FV(1.0f, 0.0f, 1.0f, 1.0f),
        FV(0.0f, 1.0f, 0.0f, 0.0f),
        FV(1.0f, 1.0f, 1.0f, 0.0f)
};
const GLsizei gDrawTextureVertexStride = sizeof(TextureVertex);
const GLsizei gDrawTextureVertexCount = 4;

static inline void resetDrawTextureTexCoords(float u1, float v1, float u2, float v2) {
    gDrawTextureVertices[0].texture[0] = u1;
    gDrawTextureVertices[0].texture[1] = v2;
    gDrawTextureVertices[1].texture[0] = u2;
    gDrawTextureVertices[1].texture[1] = v2;
    gDrawTextureVertices[2].texture[0] = u1;
    gDrawTextureVertices[2].texture[1] = v1;
    gDrawTextureVertices[3].texture[0] = u2;
    gDrawTextureVertices[3].texture[1] = v1;
}

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer() {
    LOGD("Create OpenGLRenderer");

    mDrawColorShader = new DrawColorProgram;
    mDrawTextureShader = new DrawTextureProgram;
}

OpenGLRenderer::~OpenGLRenderer() {
    LOGD("Destroy OpenGLRenderer");
}

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setViewport(int width, int height) {
    glViewport(0, 0, width, height);

    mat4 ortho;
    ortho.loadOrtho(0, width, height, 0, -1, 1);
    ortho.copyTo(mOrthoMatrix);

    mWidth = width;
    mHeight = height;
}

void OpenGLRenderer::prepare() {
    mSnapshot = &mFirstSnapshot;
    mSaveCount = 0;

    glDisable(GL_SCISSOR_TEST);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);
    glScissor(0, 0, mWidth, mHeight);

    mSnapshot->clipRect.set(0.0f, 0.0f, mWidth, mHeight);
}

///////////////////////////////////////////////////////////////////////////////
// State management
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::getSaveCount() const {
    return mSaveCount;
}

int OpenGLRenderer::save(int flags) {
    return saveSnapshot();
}

void OpenGLRenderer::restore() {
    if (mSaveCount == 0) return;

    if (restoreSnapshot()) {
        setScissorFromClip();
    }
}

void OpenGLRenderer::restoreToCount(int saveCount) {
    if (saveCount <= 0 || saveCount > mSaveCount) return;

    bool restoreClip = false;

    while (mSaveCount != saveCount - 1) {
        restoreClip |= restoreSnapshot();
    }

    if (restoreClip) {
        setScissorFromClip();
    }
}

int OpenGLRenderer::saveSnapshot() {
    mSnapshot = new Snapshot(mSnapshot);
    return ++mSaveCount;
}

bool OpenGLRenderer::restoreSnapshot() {
    bool restoreClip = mSnapshot->flags & Snapshot::kFlagClipSet;
    bool restoreLayer = mSnapshot->flags & Snapshot::kFlagIsLayer;

    sp<Snapshot> current = mSnapshot;
    sp<Snapshot> previous = mSnapshot->previous;

    if (restoreLayer) {
        // Unbind current FBO and restore previous one
        // Most of the time, previous->fbo will be 0 to bind the default buffer
        glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);

        // Restore the clip from the previous snapshot
        const Rect& clip = previous->getMappedClip();
        glScissor(clip.left, mHeight - clip.bottom, clip.getWidth(), clip.getHeight());

        // Compute the correct texture coordinates for the FBO texture
        // The texture is currently as big as the window but drawn with
        // a quad of the appropriate size
        const Rect& layer = current->layer;
        Rect texCoords(current->layer);
        mSnapshot->transform.mapRect(texCoords);

        const float u1 = texCoords.left / float(mWidth);
        const float v1 = (mHeight - texCoords.top) / float(mHeight);
        const float u2 = texCoords.right / float(mWidth);
        const float v2 = (mHeight - texCoords.bottom) / float(mHeight);

        resetDrawTextureTexCoords(u1, v1, u2, v1);

        drawTextureRect(layer.left, layer.top, layer.right, layer.bottom,
                current->texture, current->alpha);

        resetDrawTextureTexCoords(0.0f, 1.0f, 1.0f, 0.0f);

        glDeleteFramebuffers(1, &current->fbo);
        glDeleteTextures(1, &current->texture);
    }

    mSnapshot = previous;
    mSaveCount--;

    return restoreClip;
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* p, int flags) {
    // TODO Implement
    return saveSnapshot();
}

int OpenGLRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    int count = saveSnapshot();

    mSnapshot->flags |= Snapshot::kFlagIsLayer;
    mSnapshot->alpha = alpha / 255.0f;
    mSnapshot->layer.set(left, top, right, bottom);

    // Generate the FBO and attach the texture
    glGenFramebuffers(1, &mSnapshot->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, mSnapshot->fbo);

    // Generate the texture in which the FBO will draw
    glGenTextures(1, &mSnapshot->texture);
    glBindTexture(GL_TEXTURE_2D, mSnapshot->texture);

    // The FBO will not be scaled, so we can use lower quality filtering
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // TODO ***** IMPORTANT *****
    // Creating a texture-backed FBO works only if the texture is the same size
    // as the original rendering buffer (in this case, mWidth and mHeight.)
    // This is expensive and wasteful and must be fixed.
    // TODO Additionally we should use an FBO cache

    const GLsizei width = mWidth; //right - left;
    const GLsizei height = mHeight; //bottom - right;

    const GLint format = (flags & SkCanvas::kHasAlphaLayer_SaveFlag) ? GL_RGBA : GL_RGB;
    glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, GL_UNSIGNED_BYTE, NULL);
    glBindTexture(GL_TEXTURE_2D, 0);

    // Bind texture to FBO
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            mSnapshot->texture, 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGD("Framebuffer incomplete %d", status);

        glDeleteFramebuffers(1, &mSnapshot->fbo);
        glDeleteTextures(1, &mSnapshot->texture);
    }

    return count;
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::translate(float dx, float dy) {
    mSnapshot->transform.translate(dx, dy, 0.0f);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::rotate(float degrees) {
    mSnapshot->transform.rotate(degrees, 0.0f, 0.0f, 1.0f);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::scale(float sx, float sy) {
    mSnapshot->transform.scale(sx, sy, 1.0f);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::setMatrix(SkMatrix* matrix) {
    mSnapshot->transform.load(*matrix);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
    mSnapshot->transform.copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
    mat4 m(*matrix);
    mSnapshot->transform.multiply(m);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
    const Rect& clip = mSnapshot->getMappedClip();
    glScissor(clip.left, mHeight - clip.bottom, clip.getWidth(), clip.getHeight());
}

const Rect& OpenGLRenderer::getClipBounds() {
    return mSnapshot->clipRect;
}

bool OpenGLRenderer::quickReject(float left, float top, float right, float bottom) {
    /*
     * The documentation of quickReject() indicates that the specified rect
     * is transformed before being compared to the clip rect. However, the
     * clip rect is not stored transformed in the snapshot and can thus be
     * compared directly
     *
     * The following code can be used instead to performed a mapped comparison:
     *
     *     mSnapshot->transform.mapRect(r);
     *     const Rect& clip = mSnapshot->getMappedClip();
     *     return !clip.intersects(r);
     */
    Rect r(left, top, right, bottom);
    return !mSnapshot->clipRect.intersects(r);
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom) {
    bool clipped = mSnapshot->clipRect.intersect(left, top, right, bottom);
    if (clipped) {
        mSnapshot->flags |= Snapshot::kFlagClipSet;
        setScissorFromClip();
    }
    return clipped;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    // TODO: Set the transfer mode
    const Rect& clip = mSnapshot->clipRect;
    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color);
}

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom, const SkPaint* p) {
    // TODO Support more than  just color
    // TODO: Set the transfer mode
    drawColorRect(left, top, right, bottom, p->getColor());
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom, int color) {
    GLfloat a = ((color >> 24) & 0xFF) / 255.0f;
    GLfloat r = ((color >> 16) & 0xFF) / 255.0f;
    GLfloat g = ((color >>  8) & 0xFF) / 255.0f;
    GLfloat b = ((color      ) & 0xFF) / 255.0f;

    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);

    mDrawColorShader->use(&mOrthoMatrix[0], &mModelView.data[0], &mSnapshot->transform.data[0]);

    const GLvoid* p = &gDrawColorVertices[0].position[0];

    glEnableVertexAttribArray(mDrawColorShader->position);
    glVertexAttribPointer(mDrawColorShader->position, 2, GL_FLOAT, GL_FALSE,
            gDrawColorVertexStride, p);
    glVertexAttrib4f(mDrawColorShader->color, r, g, b, a);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gDrawColorVertexCount);

    glDisableVertexAttribArray(mDrawColorShader->position);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        GLuint texture, float alpha) {
    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);

    mDrawTextureShader->use(&mOrthoMatrix[0], &mModelView.data[0], &mSnapshot->transform.data[0]);

    // TODO Correctly set the blend function, based on texture format and xfermode
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);

    glBindTexture(GL_TEXTURE_2D, texture);

    glActiveTexture(GL_TEXTURE0);
    glUniform1i(mDrawTextureShader->sampler, 0);

    const GLvoid* p = &gDrawTextureVertices[0].position[0];
    const GLvoid* t = &gDrawTextureVertices[0].texture[0];

    glEnableVertexAttribArray(mDrawTextureShader->position);
    glVertexAttribPointer(mDrawTextureShader->position, 2, GL_FLOAT, GL_FALSE,
            gDrawTextureVertexStride, p);

    glEnableVertexAttribArray(mDrawTextureShader->texCoords);
    glVertexAttribPointer(mDrawTextureShader->texCoords, 2, GL_FLOAT, GL_FALSE,
            gDrawTextureVertexStride, t);

    glVertexAttrib4f(mDrawTextureShader->color, 1.0f, 1.0f, 1.0f, alpha);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gDrawTextureVertexCount);

    glDisableVertexAttribArray(mDrawTextureShader->position);
    glDisableVertexAttribArray(mDrawTextureShader->texCoords);

    glBindTexture(GL_TEXTURE_2D, 0);
    glDisable(GL_BLEND);
}

}; // namespace uirenderer
}; // namespace android
