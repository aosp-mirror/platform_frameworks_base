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

static const SimpleVertex gDrawColorVertices[] = {
        SV(0.0f, 0.0f),
        SV(1.0f, 0.0f),
        SV(0.0f, 1.0f),
        SV(1.0f, 1.0f)
};
static const GLsizei gDrawColorVertexStride = sizeof(SimpleVertex);
static const GLsizei gDrawColorVertexCount = 4;

// This array is never used directly but used as a memcpy source in the
// OpenGLRenderer constructor
static const TextureVertex gDrawTextureVertices[] = {
        FV(0.0f, 0.0f, 0.0f, 1.0f),
        FV(1.0f, 0.0f, 1.0f, 1.0f),
        FV(0.0f, 1.0f, 0.0f, 0.0f),
        FV(1.0f, 1.0f, 1.0f, 0.0f)
};
static const GLsizei gDrawTextureVertexStride = sizeof(TextureVertex);
static const GLsizei gDrawTextureVertexCount = 4;

// In this array, the index of each Blender equals the value of the first
// entry. For instance, gBlends[1] == gBlends[SkXfermode::kSrc_Mode]
static const Blender gBlends[] = {
        { SkXfermode::kClear_Mode,   GL_ZERO,                 GL_ZERO },
        { SkXfermode::kSrc_Mode,     GL_ONE,                  GL_ZERO },
        { SkXfermode::kDst_Mode,     GL_ZERO,                 GL_ONE },
        { SkXfermode::kSrcOver_Mode, GL_ONE,                  GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kDstOver_Mode, GL_ONE_MINUS_DST_ALPHA,  GL_ONE },
        { SkXfermode::kSrcIn_Mode,   GL_DST_ALPHA,            GL_ZERO },
        { SkXfermode::kDstIn_Mode,   GL_ZERO,                 GL_SRC_ALPHA },
        { SkXfermode::kSrcOut_Mode,  GL_ONE_MINUS_DST_ALPHA,  GL_ZERO },
        { SkXfermode::kDstOut_Mode,  GL_ZERO,                 GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kSrcATop_Mode, GL_DST_ALPHA,            GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kDstATop_Mode, GL_ONE_MINUS_DST_ALPHA,  GL_SRC_ALPHA },
        { SkXfermode::kXor_Mode,     GL_ONE_MINUS_DST_ALPHA,  GL_ONE_MINUS_SRC_ALPHA }
};

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer() {
    LOGD("Create OpenGLRenderer");

    mDrawColorShader = new DrawColorProgram;
    mDrawTextureShader = new DrawTextureProgram;

    memcpy(mDrawTextureVertices, gDrawTextureVertices, sizeof(gDrawTextureVertices));
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
        composeLayer(current, previous);
    }

    mSnapshot = previous;
    mSaveCount--;

    return restoreClip;
}

void OpenGLRenderer::composeLayer(sp<Snapshot> current, sp<Snapshot> previous) {
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
            current->texture, current->alpha, current->mode, true);

    resetDrawTextureTexCoords(0.0f, 1.0f, 1.0f, 0.0f);

    glDeleteFramebuffers(1, &current->fbo);
    glDeleteTextures(1, &current->texture);
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* p, int flags) {
    int count = saveSnapshot();

    int alpha = 255;
    SkXfermode::Mode mode;

    if (p) {
        alpha = p->getAlpha();
        const bool isMode = SkXfermode::IsMode(p->getXfermode(), &mode);
        if (!isMode) {
            // Assume SRC_OVER
            mode = SkXfermode::kSrcOver_Mode;
        }
    } else {
        mode = SkXfermode::kSrcOver_Mode;
    }

    createLayer(mSnapshot, left, top, right, bottom, alpha, mode, flags);

    return count;
}

int OpenGLRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    int count = saveSnapshot();
    createLayer(mSnapshot, left, top, right, bottom, alpha, SkXfermode::kSrcOver_Mode, flags);
    return count;
}

bool OpenGLRenderer::createLayer(sp<Snapshot> snapshot, float left, float top,
        float right, float bottom, int alpha, SkXfermode::Mode mode,int flags) {
    // Generate the FBO and attach the texture
    glGenFramebuffers(1, &snapshot->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, snapshot->fbo);

    // Generate the texture in which the FBO will draw
    glGenTextures(1, &snapshot->texture);
    glBindTexture(GL_TEXTURE_2D, snapshot->texture);

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
            snapshot->texture, 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGD("Framebuffer incomplete %d", status);

        glDeleteFramebuffers(1, &snapshot->fbo);
        glDeleteTextures(1, &snapshot->texture);

        return false;
    }

    snapshot->flags |= Snapshot::kFlagIsLayer;
    snapshot->mode = mode;
    snapshot->alpha = alpha / 255.0f;
    snapshot->layer.set(left, top, right, bottom);

    return true;
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
    const Rect& clip = mSnapshot->clipRect;
    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color, mode);
}

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom, const SkPaint* p) {
    SkXfermode::Mode mode;

    const bool isMode = SkXfermode::IsMode(p->getXfermode(), &mode);
    if (!isMode) {
        // Assume SRC_OVER
        mode = SkXfermode::kSrcOver_Mode;
    }

    // Skia draws using the color's alpha channel if < 255
    // Otherwise, it uses the paint's alpha
    int color = p->getColor();
    if (((color >> 24) & 0xFF) == 255) {
        color |= p->getAlpha() << 24;
    }

    drawColorRect(left, top, right, bottom, color, mode);
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        int color, SkXfermode::Mode mode) {
    const int alpha = (color >> 24) & 0xFF;
    const bool blend = alpha < 255 || mode != SkXfermode::kSrcOver_Mode;

    const GLfloat a = alpha                  / 255.0f;
    const GLfloat r = ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = ((color      ) & 0xFF) / 255.0f;

    if (blend) {
        glEnable(GL_BLEND);
        glBlendFunc(gBlends[mode].src, gBlends[mode].dst);
    }

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

    if (blend) {
        glDisable(GL_BLEND);
    }
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool isPremultiplied) {
    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);

    mDrawTextureShader->use(&mOrthoMatrix[0], &mModelView.data[0], &mSnapshot->transform.data[0]);

    GLenum sourceMode = gBlends[mode].src;
    if (!isPremultiplied && sourceMode == GL_ONE) {
        sourceMode = GL_SRC_ALPHA;
    }

    // TODO: Try to disable blending when the texture is opaque and alpha == 1.0f
    glEnable(GL_BLEND);
    glBlendFunc(sourceMode, gBlends[mode].dst);

    glBindTexture(GL_TEXTURE_2D, texture);

    glActiveTexture(GL_TEXTURE0);
    glUniform1i(mDrawTextureShader->sampler, 0);

    const GLvoid* p = &mDrawTextureVertices[0].position[0];
    const GLvoid* t = &mDrawTextureVertices[0].texture[0];

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

void OpenGLRenderer::resetDrawTextureTexCoords(float u1, float v1, float u2, float v2) {
    mDrawTextureVertices[0].texture[0] = u1;
    mDrawTextureVertices[0].texture[1] = v2;
    mDrawTextureVertices[1].texture[0] = u2;
    mDrawTextureVertices[1].texture[1] = v2;
    mDrawTextureVertices[2].texture[0] = u1;
    mDrawTextureVertices[2].texture[1] = v1;
    mDrawTextureVertices[3].texture[0] = u2;
    mDrawTextureVertices[3].texture[1] = v1;
}

}; // namespace uirenderer
}; // namespace android
