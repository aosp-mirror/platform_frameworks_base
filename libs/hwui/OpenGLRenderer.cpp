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
#include <SkTypeface.h>

#include <utils/Log.h>
#include <utils/StopWatch.h>

#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define REQUIRED_TEXTURE_UNITS_COUNT 3

// Generates simple and textured vertices
#define FV(x, y, u, v) { { x, y }, { u, v } }

#define RAD_TO_DEG (180.0f / 3.14159265f)
#define MIN_ANGLE 0.001f

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

// This array is never used directly but used as a memcpy source in the
// OpenGLRenderer constructor
static const TextureVertex gMeshVertices[] = {
        FV(0.0f, 0.0f, 0.0f, 0.0f),
        FV(1.0f, 0.0f, 1.0f, 0.0f),
        FV(0.0f, 1.0f, 0.0f, 1.0f),
        FV(1.0f, 1.0f, 1.0f, 1.0f)
};
static const GLsizei gMeshStride = sizeof(TextureVertex);
static const GLsizei gMeshCount = 4;

/**
 * Structure mapping Skia xfermodes to OpenGL blending factors.
 */
struct Blender {
    SkXfermode::Mode mode;
    GLenum src;
    GLenum dst;
}; // struct Blender

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

// This array contains the swapped version of each SkXfermode. For instance
// this array's SrcOver blending mode is actually DstOver. You can refer to
// createLayer() for more information on the purpose of this array.
static const Blender gBlendsSwap[] = {
        { SkXfermode::kClear_Mode,   GL_ZERO,                 GL_ZERO },
        { SkXfermode::kSrc_Mode,     GL_ZERO,                 GL_ONE },
        { SkXfermode::kDst_Mode,     GL_ONE,                  GL_ZERO },
        { SkXfermode::kSrcOver_Mode, GL_ONE_MINUS_DST_ALPHA,  GL_ONE },
        { SkXfermode::kDstOver_Mode, GL_ONE,                  GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kSrcIn_Mode,   GL_ZERO,                 GL_SRC_ALPHA },
        { SkXfermode::kDstIn_Mode,   GL_DST_ALPHA,            GL_ZERO },
        { SkXfermode::kSrcOut_Mode,  GL_ZERO,                 GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kDstOut_Mode,  GL_ONE_MINUS_DST_ALPHA,  GL_ZERO },
        { SkXfermode::kSrcATop_Mode, GL_ONE_MINUS_DST_ALPHA,  GL_SRC_ALPHA },
        { SkXfermode::kDstATop_Mode, GL_DST_ALPHA,            GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kXor_Mode,     GL_ONE_MINUS_DST_ALPHA,  GL_ONE_MINUS_SRC_ALPHA }
};

static const GLenum gTextureUnits[] = {
        GL_TEXTURE0,
        GL_TEXTURE1,
        GL_TEXTURE2
};

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer(): mCaches(Caches::getInstance()) {
    mShader = NULL;
    mColorFilter = NULL;
    mHasShadow = false;

    memcpy(mMeshVertices, gMeshVertices, sizeof(gMeshVertices));

    mFirstSnapshot = new Snapshot;

    GLint maxTextureUnits;
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &maxTextureUnits);
    if (maxTextureUnits < REQUIRED_TEXTURE_UNITS_COUNT) {
        LOGW("At least %d texture units are required!", REQUIRED_TEXTURE_UNITS_COUNT);
    }

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &mMaxTextureSize);
}

OpenGLRenderer::~OpenGLRenderer() {
    // The context has already been destroyed at this point, do not call
    // GL APIs. All GL state should be kept in Caches.h
}

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setViewport(int width, int height) {
    glViewport(0, 0, width, height);
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;

    mFirstSnapshot->height = height;
    mFirstSnapshot->viewport.set(0, 0, width, height);
}

void OpenGLRenderer::prepare(bool opaque) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;

    glViewport(0, 0, mWidth, mHeight);

    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);

    if (!opaque) {
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    glEnable(GL_SCISSOR_TEST);
    glScissor(0, 0, mWidth, mHeight);

    mSnapshot->setClip(0.0f, 0.0f, mWidth, mHeight);
}

void OpenGLRenderer::finish() {
#if DEBUG_OPENGL
    GLenum status = GL_NO_ERROR;
    while ((status = glGetError()) != GL_NO_ERROR) {
        LOGD("GL error from OpenGLRenderer: 0x%x", status);
    }
#endif
}

void OpenGLRenderer::acquireContext() {
    if (mCaches.currentProgram) {
        if (mCaches.currentProgram->isInUse()) {
            mCaches.currentProgram->remove();
            mCaches.currentProgram = NULL;
        }
    }
}

void OpenGLRenderer::releaseContext() {
    glViewport(0, 0, mSnapshot->viewport.getWidth(), mSnapshot->viewport.getHeight());

    glEnable(GL_SCISSOR_TEST);
    setScissorFromClip();

    glDisable(GL_DITHER);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (mCaches.blend) {
        glEnable(GL_BLEND);
        glBlendFunc(mCaches.lastSrcMode, mCaches.lastDstMode);
        glBlendEquation(GL_FUNC_ADD);
    } else {
        glDisable(GL_BLEND);
    }
}

///////////////////////////////////////////////////////////////////////////////
// State management
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::getSaveCount() const {
    return mSaveCount;
}

int OpenGLRenderer::save(int flags) {
    return saveSnapshot(flags);
}

void OpenGLRenderer::restore() {
    if (mSaveCount > 1) {
        restoreSnapshot();
    }
}

void OpenGLRenderer::restoreToCount(int saveCount) {
    if (saveCount < 1) saveCount = 1;

    while (mSaveCount > saveCount) {
        restoreSnapshot();
    }
}

int OpenGLRenderer::saveSnapshot(int flags) {
    mSnapshot = new Snapshot(mSnapshot, flags);
    return mSaveCount++;
}

bool OpenGLRenderer::restoreSnapshot() {
    bool restoreClip = mSnapshot->flags & Snapshot::kFlagClipSet;
    bool restoreLayer = mSnapshot->flags & Snapshot::kFlagIsLayer;
    bool restoreOrtho = mSnapshot->flags & Snapshot::kFlagDirtyOrtho;

    sp<Snapshot> current = mSnapshot;
    sp<Snapshot> previous = mSnapshot->previous;

    if (restoreOrtho) {
        Rect& r = previous->viewport;
        glViewport(r.left, r.top, r.right, r.bottom);
        mOrthoMatrix.load(current->orthoMatrix);
    }

    mSaveCount--;
    mSnapshot = previous;

    if (restoreLayer) {
        composeLayer(current, previous);
    }

    if (restoreClip) {
        setScissorFromClip();
    }

    return restoreClip;
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* p, int flags) {
    const GLuint previousFbo = mSnapshot->fbo;
    const int count = saveSnapshot(flags);

    int alpha = 255;
    SkXfermode::Mode mode;

    if (p) {
        alpha = p->getAlpha();
        if (!mExtensions.hasFramebufferFetch()) {
            const bool isMode = SkXfermode::IsMode(p->getXfermode(), &mode);
            if (!isMode) {
                // Assume SRC_OVER
                mode = SkXfermode::kSrcOver_Mode;
            }
        } else {
            mode = getXfermode(p->getXfermode());
        }
    } else {
        mode = SkXfermode::kSrcOver_Mode;
    }

    createLayer(mSnapshot, left, top, right, bottom, alpha, mode, flags, previousFbo);

    return count;
}

int OpenGLRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    if (alpha == 0xff) {
        return saveLayer(left, top, right, bottom, NULL, flags);
    } else {
        SkPaint paint;
        paint.setAlpha(alpha);
        return saveLayer(left, top, right, bottom, &paint, flags);
    }
}

/**
 * Layers are viewed by Skia are slightly different than layers in image editing
 * programs (for instance.) When a layer is created, previously created layers
 * and the frame buffer still receive every drawing command. For instance, if a
 * layer is created and a shape intersecting the bounds of the layers and the
 * framebuffer is draw, the shape will be drawn on both (unless the layer was
 * created with the SkCanvas::kClipToLayer_SaveFlag flag.)
 *
 * A way to implement layers is to create an FBO for each layer, backed by an RGBA
 * texture. Unfortunately, this is inefficient as it requires every primitive to
 * be drawn n + 1 times, where n is the number of active layers. In practice this
 * means, for every primitive:
 *   - Switch active frame buffer
 *   - Change viewport, clip and projection matrix
 *   - Issue the drawing
 *
 * Switching rendering target n + 1 times per drawn primitive is extremely costly.
 * To avoid this, layers are implemented in a different way here, at least in the
 * general case. FBOs are used, as an optimization, when the "clip to layer" flag
 * is set. When this flag is set we can redirect all drawing operations into a
 * single FBO.
 *
 * This implementation relies on the frame buffer being at least RGBA 8888. When
 * a layer is created, only a texture is created, not an FBO. The content of the
 * frame buffer contained within the layer's bounds is copied into this texture
 * using glCopyTexImage2D(). The layer's region is then cleared(1) in the frame
 * buffer and drawing continues as normal. This technique therefore treats the
 * frame buffer as a scratch buffer for the layers.
 *
 * To compose the layers back onto the frame buffer, each layer texture
 * (containing the original frame buffer data) is drawn as a simple quad over
 * the frame buffer. The trick is that the quad is set as the composition
 * destination in the blending equation, and the frame buffer becomes the source
 * of the composition.
 *
 * Drawing layers with an alpha value requires an extra step before composition.
 * An empty quad is drawn over the layer's region in the frame buffer. This quad
 * is drawn with the rgba color (0,0,0,alpha). The alpha value offered by the
 * quad is used to multiply the colors in the frame buffer. This is achieved by
 * changing the GL blend functions for the GL_FUNC_ADD blend equation to
 * GL_ZERO, GL_SRC_ALPHA.
 *
 * Because glCopyTexImage2D() can be slow, an alternative implementation might
 * be use to draw a single clipped layer. The implementation described above
 * is correct in every case.
 *
 * (1) The frame buffer is actually not cleared right away. To allow the GPU
 *     to potentially optimize series of calls to glCopyTexImage2D, the frame
 *     buffer is left untouched until the first drawing operation. Only when
 *     something actually gets drawn are the layers regions cleared.
 */
bool OpenGLRenderer::createLayer(sp<Snapshot> snapshot, float left, float top,
        float right, float bottom, int alpha, SkXfermode::Mode mode,
        int flags, GLuint previousFbo) {
    LAYER_LOGD("Requesting layer %.2fx%.2f", right - left, bottom - top);
    LAYER_LOGD("Layer cache size = %d", mCaches.layerCache.getSize());

    const bool fboLayer = flags & SkCanvas::kClipToLayer_SaveFlag;

    // Window coordinates of the layer
    Rect bounds(left, top, right, bottom);
    if (!fboLayer) {
        mSnapshot->transform->mapRect(bounds);
        // Layers only make sense if they are in the framebuffer's bounds
        bounds.intersect(*mSnapshot->clipRect);
        bounds.snapToPixelBoundaries();
    }

    if (bounds.isEmpty() || bounds.getWidth() > mMaxTextureSize ||
            bounds.getHeight() > mMaxTextureSize) {
        return false;
    }

    glActiveTexture(GL_TEXTURE0);

    Layer* layer = mCaches.layerCache.get(bounds.getWidth(), bounds.getHeight());
    if (!layer) {
        return false;
    }

    layer->mode = mode;
    layer->alpha = alpha;
    layer->layer.set(bounds);
    layer->texCoords.set(0.0f, bounds.getHeight() / float(layer->height),
            bounds.getWidth() / float(layer->width), 0.0f);

    // Save the layer in the snapshot
    snapshot->flags |= Snapshot::kFlagIsLayer;
    snapshot->layer = layer;

    if (fboLayer) {
        layer->fbo = mCaches.fboCache.get();

        snapshot->flags |= Snapshot::kFlagIsFboLayer;
        snapshot->fbo = layer->fbo;
        snapshot->resetTransform(-bounds.left, -bounds.top, 0.0f);
        snapshot->resetClip(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
        snapshot->viewport.set(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
        snapshot->height = bounds.getHeight();
        snapshot->flags |= Snapshot::kFlagDirtyOrtho;
        snapshot->orthoMatrix.load(mOrthoMatrix);

        setScissorFromClip();

        // Bind texture to FBO
        glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
        glBindTexture(GL_TEXTURE_2D, layer->texture);

        // Initialize the texture if needed
        if (layer->empty) {
            layer->empty = false;
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, layer->width, layer->height, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        }

        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                layer->texture, 0);

        GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("Framebuffer incomplete (GL error code 0x%x)", status);

            glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
            glDeleteTextures(1, &layer->texture);
            mCaches.fboCache.put(layer->fbo);

            delete layer;

            return false;
        }

        // Clear the FBO
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);

        // Change the ortho projection
        glViewport(0, 0, bounds.getWidth(), bounds.getHeight());
        mOrthoMatrix.loadOrtho(0.0f, bounds.getWidth(), bounds.getHeight(), 0.0f, -1.0f, 1.0f);
    } else {
        // Copy the framebuffer into the layer
        glBindTexture(GL_TEXTURE_2D, layer->texture);

        // TODO: Workaround for b/3054204
        glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bounds.left, mHeight - bounds.bottom,
                layer->width, layer->height, 0);

        // TODO: Waiting for b/3054204 to be fixed
        // if (layer->empty) {
        //     glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bounds.left, mHeight - bounds.bottom,
        //             layer->width, layer->height, 0);
        //     layer->empty = false;
        // } else {
        //     glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bounds.left, mHeight - bounds.bottom,
        //             bounds.getWidth(), bounds.getHeight());
        //  }

        // Enqueue the buffer coordinates to clear the corresponding region later
        mLayers.push(new Rect(bounds));
    }

    return true;
}

/**
 * Read the documentation of createLayer() before doing anything in this method.
 */
void OpenGLRenderer::composeLayer(sp<Snapshot> current, sp<Snapshot> previous) {
    if (!current->layer) {
        LOGE("Attempting to compose a layer that does not exist");
        return;
    }

    const bool fboLayer = current->flags & SkCanvas::kClipToLayer_SaveFlag;

    if (fboLayer) {
        // Unbind current FBO and restore previous one
        glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);
    }

    // Restore the clip from the previous snapshot
    const Rect& clip = *previous->clipRect;
    glScissor(clip.left, previous->height - clip.bottom, clip.getWidth(), clip.getHeight());

    Layer* layer = current->layer;
    const Rect& rect = layer->layer;

    if (!fboLayer && layer->alpha < 255) {
        drawColorRect(rect.left, rect.top, rect.right, rect.bottom,
                layer->alpha << 24, SkXfermode::kDstIn_Mode, true);
    }

    const Rect& texCoords = layer->texCoords;
    resetDrawTextureTexCoords(texCoords.left, texCoords.top, texCoords.right, texCoords.bottom);

    if (fboLayer) {
        drawTextureRect(rect.left, rect.top, rect.right, rect.bottom,
                layer->texture, layer->alpha / 255.0f, layer->mode, layer->blend);
    } else {
        drawTextureMesh(rect.left, rect.top, rect.right, rect.bottom, layer->texture,
                1.0f, layer->mode, layer->blend, &mMeshVertices[0].position[0],
                &mMeshVertices[0].texture[0], GL_TRIANGLE_STRIP, gMeshCount, true, true);
    }

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);

    if (fboLayer) {
        // Detach the texture from the FBO
        glBindFramebuffer(GL_FRAMEBUFFER, current->fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);

        // Put the FBO name back in the cache, if it doesn't fit, it will be destroyed
        mCaches.fboCache.put(current->fbo);
    }

    // Failing to add the layer to the cache should happen only if the layer is too large
    if (!mCaches.layerCache.put(layer)) {
        LAYER_LOGD("Deleting layer");
        glDeleteTextures(1, &layer->texture);
        delete layer;
    }
}

void OpenGLRenderer::clearLayerRegions() {
    if (mLayers.size() == 0) return;

    for (uint32_t i = 0; i < mLayers.size(); i++) {
        Rect* bounds = mLayers.itemAt(i);

        // Clear the framebuffer where the layer will draw
        glScissor(bounds->left, mHeight - bounds->bottom,
                bounds->getWidth(), bounds->getHeight());
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        delete bounds;
    }
    mLayers.clear();

    // Restore the clip
    setScissorFromClip();
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::translate(float dx, float dy) {
    mSnapshot->transform->translate(dx, dy, 0.0f);
}

void OpenGLRenderer::rotate(float degrees) {
    mSnapshot->transform->rotate(degrees, 0.0f, 0.0f, 1.0f);
}

void OpenGLRenderer::scale(float sx, float sy) {
    mSnapshot->transform->scale(sx, sy, 1.0f);
}

void OpenGLRenderer::setMatrix(SkMatrix* matrix) {
    mSnapshot->transform->load(*matrix);
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
    mSnapshot->transform->copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
    mat4 m(*matrix);
    mSnapshot->transform->multiply(m);
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
    const Rect& clip = *mSnapshot->clipRect;
    glScissor(clip.left, mSnapshot->height - clip.bottom, clip.getWidth(), clip.getHeight());
}

const Rect& OpenGLRenderer::getClipBounds() {
    return mSnapshot->getLocalClip();
}

bool OpenGLRenderer::quickReject(float left, float top, float right, float bottom) {
    Rect r(left, top, right, bottom);
    mSnapshot->transform->mapRect(r);
    return !mSnapshot->clipRect->intersects(r);
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    bool clipped = mSnapshot->clip(left, top, right, bottom, op);
    if (clipped) {
        setScissorFromClip();
    }
    return !mSnapshot->clipRect->isEmpty();
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, const SkPaint* paint) {
    const float right = left + bitmap->width();
    const float bottom = top + bitmap->height();

    if (quickReject(left, top, right, bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    drawTextureRect(left, top, right, bottom, texture, paint);
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix, const SkPaint* paint) {
    Rect r(0.0f, 0.0f, bitmap->width(), bitmap->height());
    const mat4 transform(*matrix);
    transform.mapRect(r);

    if (quickReject(r.left, r.top, r.right, r.bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    drawTextureRect(r.left, r.top, r.right, r.bottom, texture, paint);
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap,
         float srcLeft, float srcTop, float srcRight, float srcBottom,
         float dstLeft, float dstTop, float dstRight, float dstBottom,
         const SkPaint* paint) {
    if (quickReject(dstLeft, dstTop, dstRight, dstBottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float width = texture->width;
    const float height = texture->height;

    const float u1 = srcLeft / width;
    const float v1 = srcTop / height;
    const float u2 = srcRight / width;
    const float v2 = srcBottom / height;

    resetDrawTextureTexCoords(u1, v1, u2, v2);

    drawTextureRect(dstLeft, dstTop, dstRight, dstBottom, texture, paint);

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
}

void OpenGLRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        uint32_t width, uint32_t height, float left, float top, float right, float bottom,
        const SkPaint* paint) {
    if (quickReject(left, top, right, bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    const Patch* mesh = mCaches.patchCache.get(bitmap->width(), bitmap->height(),
            right - left, bottom - top, xDivs, yDivs, width, height);

    // Specify right and bottom as +1.0f from left/top to prevent scaling since the
    // patch mesh already defines the final size
    drawTextureMesh(left, top, left + 1.0f, top + 1.0f, texture->id, alpha / 255.0f,
            mode, texture->blend, &mesh->vertices[0].position[0],
            &mesh->vertices[0].texture[0], GL_TRIANGLES, mesh->verticesCount);
}

void OpenGLRenderer::drawLines(float* points, int count, const SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    uint32_t color = paint->getColor();
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    const bool isAA = paint->isAntiAlias();
    if (isAA) {
        GLuint textureUnit = 0;
        setupTextureAlpha8(mCaches.line.getTexture(), 0, 0, textureUnit, 0.0f, 0.0f, r, g, b, a,
                mode, false, true, mCaches.line.getVertices(), mCaches.line.getTexCoords());
    } else {
        setupColorRect(0.0f, 0.0f, 1.0f, 1.0f, r, g, b, a, mode, false);
    }

    const float strokeWidth = paint->getStrokeWidth();
    const GLsizei elementsCount = isAA ? mCaches.line.getElementsCount() : gMeshCount;
    const GLenum drawMode = isAA ? GL_TRIANGLES : GL_TRIANGLE_STRIP;

    for (int i = 0; i < count; i += 4) {
        float tx = 0.0f;
        float ty = 0.0f;

        if (isAA) {
            mCaches.line.update(points[i], points[i + 1], points[i + 2], points[i + 3],
                    strokeWidth, tx, ty);
        } else {
            ty = strokeWidth <= 1.0f ? 0.0f : -strokeWidth * 0.5f;
        }

        const float dx = points[i + 2] - points[i];
        const float dy = points[i + 3] - points[i + 1];
        const float mag = sqrtf(dx * dx + dy * dy);
        const float angle = acos(dx / mag);

        mModelView.loadTranslate(points[i], points[i + 1], 0.0f);
        if (angle > MIN_ANGLE || angle < -MIN_ANGLE) {
            mModelView.rotate(angle * RAD_TO_DEG, 0.0f, 0.0f, 1.0f);
        }
        mModelView.translate(tx, ty, 0.0f);
        if (!isAA) {
            float length = mCaches.line.getLength(points[i], points[i + 1],
                    points[i + 2], points[i + 3]);
            mModelView.scale(length, strokeWidth, 1.0f);
        }
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);

        if (mShader) {
            mShader->updateTransforms(mCaches.currentProgram, mModelView, *mSnapshot);
        }

        glDrawArrays(drawMode, 0, elementsCount);
    }

    if (isAA) {
        glDisableVertexAttribArray(mCaches.currentProgram->getAttrib("texCoords"));
    }
}

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    const Rect& clip = *mSnapshot->clipRect;
    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color, mode, true);
}

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom, const SkPaint* p) {
    if (quickReject(left, top, right, bottom)) {
        return;
    }

    SkXfermode::Mode mode;
    if (!mExtensions.hasFramebufferFetch()) {
        const bool isMode = SkXfermode::IsMode(p->getXfermode(), &mode);
        if (!isMode) {
            // Assume SRC_OVER
            mode = SkXfermode::kSrcOver_Mode;
        }
    } else {
        mode = getXfermode(p->getXfermode());
    }

    // Skia draws using the color's alpha channel if < 255
    // Otherwise, it uses the paint's alpha
    int color = p->getColor();
    if (((color >> 24) & 0xff) == 255) {
        color |= p->getAlpha() << 24;
    }

    drawColorRect(left, top, right, bottom, color, mode);
}

void OpenGLRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, SkPaint* paint) {
    if (text == NULL || count == 0 || (paint->getAlpha() == 0 && paint->getXfermode() == NULL)) {
        return;
    }

    paint->setAntiAlias(true);

    float length = -1.0f;
    switch (paint->getTextAlign()) {
        case SkPaint::kCenter_Align:
            length = paint->measureText(text, bytesCount);
            x -= length / 2.0f;
            break;
        case SkPaint::kRight_Align:
            length = paint->measureText(text, bytesCount);
            x -= length;
            break;
        default:
            break;
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    uint32_t color = paint->getColor();
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    FontRenderer& fontRenderer = mCaches.fontRenderer.getFontRenderer(paint);
    fontRenderer.setFont(paint, SkTypeface::UniqueID(paint->getTypeface()),
            paint->getTextSize());

    if (mHasShadow) {
        glActiveTexture(gTextureUnits[0]);
        mCaches.dropShadowCache.setFontRenderer(fontRenderer);
        const ShadowTexture* shadow = mCaches.dropShadowCache.get(paint, text, bytesCount,
                count, mShadowRadius);
        const AutoTexture autoCleanup(shadow);

        setupShadow(shadow, x, y, mode, a);

        // Draw the mesh
        glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
        glDisableVertexAttribArray(mCaches.currentProgram->getAttrib("texCoords"));
    }

    GLuint textureUnit = 0;
    glActiveTexture(gTextureUnits[textureUnit]);

    // Assume that the modelView matrix does not force scales, rotates, etc.
    const bool linearFilter = mSnapshot->transform->changesBounds();
    setupTextureAlpha8(fontRenderer.getTexture(linearFilter), 0, 0, textureUnit,
            x, y, r, g, b, a, mode, false, true);

    const Rect& clip = mSnapshot->getLocalClip();
    clearLayerRegions();
    fontRenderer.renderText(paint, &clip, text, 0, bytesCount, count, x, y);

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glDisableVertexAttribArray(mCaches.currentProgram->getAttrib("texCoords"));

    drawTextDecorations(text, bytesCount, length, x, y, paint);
}

void OpenGLRenderer::drawPath(SkPath* path, SkPaint* paint) {
    GLuint textureUnit = 0;
    glActiveTexture(gTextureUnits[textureUnit]);

    const PathTexture* texture = mCaches.pathCache.get(path, paint);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float x = texture->left - texture->offset;
    const float y = texture->top - texture->offset;

    if (quickReject(x, y, x + texture->width, y + texture->height)) {
        return;
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    uint32_t color = paint->getColor();
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    setupTextureAlpha8(texture, textureUnit, x, y, r, g, b, a, mode, true, true);

    clearLayerRegions();

    // Draw the mesh
    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
    glDisableVertexAttribArray(mCaches.currentProgram->getAttrib("texCoords"));
}

///////////////////////////////////////////////////////////////////////////////
// Shaders
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShader() {
    mShader = NULL;
}

void OpenGLRenderer::setupShader(SkiaShader* shader) {
    mShader = shader;
    if (mShader) {
        mShader->set(&mCaches.textureCache, &mCaches.gradientCache);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Color filters
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetColorFilter() {
    mColorFilter = NULL;
}

void OpenGLRenderer::setupColorFilter(SkiaColorFilter* filter) {
    mColorFilter = filter;
}

///////////////////////////////////////////////////////////////////////////////
// Drop shadow
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShadow() {
    mHasShadow = false;
}

void OpenGLRenderer::setupShadow(float radius, float dx, float dy, int color) {
    mHasShadow = true;
    mShadowRadius = radius;
    mShadowDx = dx;
    mShadowDy = dy;
    mShadowColor = color;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing implementation
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setupShadow(const ShadowTexture* texture, float x, float y,
        SkXfermode::Mode mode, float alpha) {
    const float sx = x - texture->left + mShadowDx;
    const float sy = y - texture->top + mShadowDy;

    const int shadowAlpha = ((mShadowColor >> 24) & 0xFF);
    const GLfloat a = shadowAlpha < 255 ? shadowAlpha / 255.0f : alpha;
    const GLfloat r = a * ((mShadowColor >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((mShadowColor >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((mShadowColor      ) & 0xFF) / 255.0f;

    GLuint textureUnit = 0;
    setupTextureAlpha8(texture, textureUnit, sx, sy, r, g, b, a, mode, true, false);
}

void OpenGLRenderer::setupTextureAlpha8(const Texture* texture, GLuint& textureUnit,
        float x, float y, float r, float g, float b, float a, SkXfermode::Mode mode,
        bool transforms, bool applyFilters) {
    setupTextureAlpha8(texture->id, texture->width, texture->height, textureUnit,
            x, y, r, g, b, a, mode, transforms, applyFilters,
            &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0]);
}

void OpenGLRenderer::setupTextureAlpha8(GLuint texture, uint32_t width, uint32_t height,
        GLuint& textureUnit, float x, float y, float r, float g, float b, float a,
        SkXfermode::Mode mode, bool transforms, bool applyFilters) {
    setupTextureAlpha8(texture, width, height, textureUnit,
            x, y, r, g, b, a, mode, transforms, applyFilters,
            &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0]);
}

void OpenGLRenderer::setupTextureAlpha8(GLuint texture, uint32_t width, uint32_t height,
        GLuint& textureUnit, float x, float y, float r, float g, float b, float a,
        SkXfermode::Mode mode, bool transforms, bool applyFilters,
        GLvoid* vertices, GLvoid* texCoords) {
     // Describe the required shaders
     ProgramDescription description;
     description.hasTexture = true;
     description.hasAlpha8Texture = true;
     const bool setColor = description.setAlpha8Color(r, g, b, a);

     if (applyFilters) {
         if (mShader) {
             mShader->describe(description, mExtensions);
         }
         if (mColorFilter) {
             mColorFilter->describe(description, mExtensions);
         }
     }

     // Setup the blending mode
     chooseBlending(true, mode, description);

     // Build and use the appropriate shader
     useProgram(mCaches.programCache.get(description));

     bindTexture(texture, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, textureUnit);
     glUniform1i(mCaches.currentProgram->getUniform("sampler"), textureUnit);

     int texCoordsSlot = mCaches.currentProgram->getAttrib("texCoords");
     glEnableVertexAttribArray(texCoordsSlot);

     // Setup attributes
     glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
             gMeshStride, vertices);
     glVertexAttribPointer(texCoordsSlot, 2, GL_FLOAT, GL_FALSE,
             gMeshStride, texCoords);

     // Setup uniforms
     if (transforms) {
         mModelView.loadTranslate(x, y, 0.0f);
         mModelView.scale(width, height, 1.0f);
     } else {
         mModelView.loadIdentity();
     }
     mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
     if (setColor) {
         mCaches.currentProgram->setColor(r, g, b, a);
     }

     textureUnit++;
     if (applyFilters) {
         // Setup attributes and uniforms required by the shaders
         if (mShader) {
             mShader->setupProgram(mCaches.currentProgram, mModelView, *mSnapshot, &textureUnit);
         }
         if (mColorFilter) {
             mColorFilter->setupProgram(mCaches.currentProgram);
         }
     }
}

// Same values used by Skia
#define kStdStrikeThru_Offset   (-6.0f / 21.0f)
#define kStdUnderline_Offset    (1.0f / 9.0f)
#define kStdUnderline_Thickness (1.0f / 18.0f)

void OpenGLRenderer::drawTextDecorations(const char* text, int bytesCount, float length,
        float x, float y, SkPaint* paint) {
    // Handle underline and strike-through
    uint32_t flags = paint->getFlags();
    if (flags & (SkPaint::kUnderlineText_Flag | SkPaint::kStrikeThruText_Flag)) {
        float underlineWidth = length;
        // If length is > 0.0f, we already measured the text for the text alignment
        if (length <= 0.0f) {
            underlineWidth = paint->measureText(text, bytesCount);
        }

        float offsetX = 0;
        switch (paint->getTextAlign()) {
            case SkPaint::kCenter_Align:
                offsetX = underlineWidth * 0.5f;
                break;
            case SkPaint::kRight_Align:
                offsetX = underlineWidth;
                break;
            default:
                break;
        }

        if (underlineWidth > 0.0f) {
            const float textSize = paint->getTextSize();
            const float strokeWidth = textSize * kStdUnderline_Thickness;

            const float left = x - offsetX;
            float top = 0.0f;

            const int pointsCount = 4 * (flags & SkPaint::kStrikeThruText_Flag ? 2 : 1);
            float points[pointsCount];
            int currentPoint = 0;

            if (flags & SkPaint::kUnderlineText_Flag) {
                top = y + textSize * kStdUnderline_Offset;
                points[currentPoint++] = left;
                points[currentPoint++] = top;
                points[currentPoint++] = left + underlineWidth;
                points[currentPoint++] = top;
            }

            if (flags & SkPaint::kStrikeThruText_Flag) {
                top = y + textSize * kStdStrikeThru_Offset;
                points[currentPoint++] = left;
                points[currentPoint++] = top;
                points[currentPoint++] = left + underlineWidth;
                points[currentPoint++] = top;
            }

            SkPaint linesPaint(*paint);
            linesPaint.setStrokeWidth(strokeWidth);

            drawLines(&points[0], pointsCount, &linesPaint);
        }
    }
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        int color, SkXfermode::Mode mode, bool ignoreTransform) {
    clearLayerRegions();

    // If a shader is set, preserve only the alpha
    if (mShader) {
        color |= 0x00ffffff;
    }

    // Render using pre-multiplied alpha
    const int alpha = (color >> 24) & 0xFF;
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    setupColorRect(left, top, right, bottom, r, g, b, a, mode, ignoreTransform);

    // Draw the mesh
    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
}

void OpenGLRenderer::setupColorRect(float left, float top, float right, float bottom,
        float r, float g, float b, float a, SkXfermode::Mode mode, bool ignoreTransform) {
    GLuint textureUnit = 0;

    // Describe the required shaders
    ProgramDescription description;
    const bool setColor = description.setColor(r, g, b, a);

    if (mShader) {
        mShader->describe(description, mExtensions);
    }
    if (mColorFilter) {
        mColorFilter->describe(description, mExtensions);
    }

    // Setup the blending mode
    chooseBlending(a < 1.0f || (mShader && mShader->blend()), mode, description);

    // Build and use the appropriate shader
    useProgram(mCaches.programCache.get(description));

    // Setup attributes
    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, &mMeshVertices[0].position[0]);

    // Setup uniforms
    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);
    if (!ignoreTransform) {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
    } else {
        mat4 identity;
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, identity);
    }
    mCaches.currentProgram->setColor(r, g, b, a);

    // Setup attributes and uniforms required by the shaders
    if (mShader) {
        mShader->setupProgram(mCaches.currentProgram, mModelView, *mSnapshot, &textureUnit);
    }
    if (mColorFilter) {
        mColorFilter->setupProgram(mCaches.currentProgram);
    }
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        const Texture* texture, const SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f, mode,
            texture->blend, &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
            GL_TRIANGLE_STRIP, gMeshCount);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend) {
    drawTextureMesh(left, top, right, bottom, texture, alpha, mode, blend,
            &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
            GL_TRIANGLE_STRIP, gMeshCount);
}

void OpenGLRenderer::drawTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool swapSrcDst, bool ignoreTransform) {
    clearLayerRegions();

    ProgramDescription description;
    description.hasTexture = true;
    const bool setColor = description.setColor(alpha, alpha, alpha, alpha);
    if (mColorFilter) {
        mColorFilter->describe(description, mExtensions);
    }

    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);

    chooseBlending(blend || alpha < 1.0f, mode, description, swapSrcDst);

    useProgram(mCaches.programCache.get(description));
    if (!ignoreTransform) {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
    } else {
        mat4 m;
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, m);
    }

    // Texture
    bindTexture(texture, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, 0);
    glUniform1i(mCaches.currentProgram->getUniform("sampler"), 0);

    // Always premultiplied
    if (setColor) {
        mCaches.currentProgram->setColor(alpha, alpha, alpha, alpha);
    }

    // Mesh
    int texCoordsSlot = mCaches.currentProgram->getAttrib("texCoords");
    glEnableVertexAttribArray(texCoordsSlot);
    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, vertices);
    glVertexAttribPointer(texCoordsSlot, 2, GL_FLOAT, GL_FALSE, gMeshStride, texCoords);

    // Color filter
    if (mColorFilter) {
        mColorFilter->setupProgram(mCaches.currentProgram);
    }

    glDrawArrays(drawMode, 0, elementsCount);
    glDisableVertexAttribArray(texCoordsSlot);
}

void OpenGLRenderer::chooseBlending(bool blend, SkXfermode::Mode mode,
        ProgramDescription& description, bool swapSrcDst) {
    blend = blend || mode != SkXfermode::kSrcOver_Mode;
    if (blend) {
        if (mode < SkXfermode::kPlus_Mode) {
            if (!mCaches.blend) {
                glEnable(GL_BLEND);
            }

            GLenum sourceMode = swapSrcDst ? gBlendsSwap[mode].src : gBlends[mode].src;
            GLenum destMode = swapSrcDst ? gBlendsSwap[mode].dst : gBlends[mode].dst;

            if (sourceMode != mCaches.lastSrcMode || destMode != mCaches.lastDstMode) {
                glBlendFunc(sourceMode, destMode);
                mCaches.lastSrcMode = sourceMode;
                mCaches.lastDstMode = destMode;
            }
        } else {
            // These blend modes are not supported by OpenGL directly and have
            // to be implemented using shaders. Since the shader will perform
            // the blending, turn blending off here
            if (mExtensions.hasFramebufferFetch()) {
                description.framebufferMode = mode;
                description.swapSrcDst = swapSrcDst;
            }

            if (mCaches.blend) {
                glDisable(GL_BLEND);
            }
            blend = false;
        }
    } else if (mCaches.blend) {
        glDisable(GL_BLEND);
    }
    mCaches.blend = blend;
}

bool OpenGLRenderer::useProgram(Program* program) {
    if (!program->isInUse()) {
        if (mCaches.currentProgram != NULL) mCaches.currentProgram->remove();
        program->use();
        mCaches.currentProgram = program;
        return false;
    }
    return true;
}

void OpenGLRenderer::resetDrawTextureTexCoords(float u1, float v1, float u2, float v2) {
    TextureVertex* v = &mMeshVertices[0];
    TextureVertex::setUV(v++, u1, v1);
    TextureVertex::setUV(v++, u2, v1);
    TextureVertex::setUV(v++, u1, v2);
    TextureVertex::setUV(v++, u2, v2);
}

void OpenGLRenderer::getAlphaAndMode(const SkPaint* paint, int* alpha, SkXfermode::Mode* mode) {
    if (paint) {
        if (!mExtensions.hasFramebufferFetch()) {
            const bool isMode = SkXfermode::IsMode(paint->getXfermode(), mode);
            if (!isMode) {
                // Assume SRC_OVER
                *mode = SkXfermode::kSrcOver_Mode;
            }
        } else {
            *mode = getXfermode(paint->getXfermode());
        }

        // Skia draws using the color's alpha channel if < 255
        // Otherwise, it uses the paint's alpha
        int color = paint->getColor();
        *alpha = (color >> 24) & 0xFF;
        if (*alpha == 255) {
            *alpha = paint->getAlpha();
        }
    } else {
        *mode = SkXfermode::kSrcOver_Mode;
        *alpha = 255;
    }
}

SkXfermode::Mode OpenGLRenderer::getXfermode(SkXfermode* mode) {
    if (mode == NULL) {
        return SkXfermode::kSrcOver_Mode;
    }
    return mode->fMode;
}

void OpenGLRenderer::bindTexture(GLuint texture, GLenum wrapS, GLenum wrapT, GLuint textureUnit) {
    glActiveTexture(gTextureUnits[textureUnit]);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapS);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapT);
}

}; // namespace uirenderer
}; // namespace android
