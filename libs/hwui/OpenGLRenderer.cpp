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

#include <ui/Rect.h>

#include "OpenGLRenderer.h"
#include "DisplayListRenderer.h"
#include "Vector.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define RAD_TO_DEG (180.0f / 3.14159265f)
#define MIN_ANGLE 0.001f

// TODO: This should be set in properties
#define ALPHA_THRESHOLD (0x7f / PANEL_BIT_DEPTH)

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

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

    mDirtyClip = false;
}

void OpenGLRenderer::prepare(bool opaque) {
    mCaches.clearGarbage();

    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;

    glViewport(0, 0, mWidth, mHeight);

    glDisable(GL_DITHER);

    if (!opaque) {
        glDisable(GL_SCISSOR_TEST);
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
#if DEBUG_MEMORY_USAGE
    mCaches.dumpMemoryUsage();
#else
    if (mCaches.getDebugLevel() & kDebugMemory) {
        mCaches.dumpMemoryUsage();
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
    mCaches.unbindMeshBuffer();
}

void OpenGLRenderer::releaseContext() {
    glViewport(0, 0, mSnapshot->viewport.getWidth(), mSnapshot->viewport.getHeight());

    glEnable(GL_SCISSOR_TEST);
    dirtyClip();

    glDisable(GL_DITHER);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    mCaches.blend = true;
    glEnable(GL_BLEND);
    glBlendFunc(mCaches.lastSrcMode, mCaches.lastDstMode);
    glBlendEquation(GL_FUNC_ADD);
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

    if (restoreClip) {
        dirtyClip();
    }

    if (restoreLayer) {
        composeLayer(current, previous);
    }

    return restoreClip;
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        SkPaint* p, int flags) {
    const GLuint previousFbo = mSnapshot->fbo;
    const int count = saveSnapshot(flags);

    if (!mSnapshot->isIgnored()) {
        int alpha = 255;
        SkXfermode::Mode mode;

        if (p) {
            alpha = p->getAlpha();
            if (!mCaches.extensions.hasFramebufferFetch()) {
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
    }

    return count;
}

int OpenGLRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    if (alpha >= 255 - ALPHA_THRESHOLD) {
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
    if (fboLayer) {
        // Clear the previous layer regions before we change the viewport
        clearLayerRegions();
    } else {
        mSnapshot->transform->mapRect(bounds);

        // Layers only make sense if they are in the framebuffer's bounds
        bounds.intersect(*snapshot->clipRect);

        // We cannot work with sub-pixels in this case
        bounds.snapToPixelBoundaries();

        // When the layer is not an FBO, we may use glCopyTexImage so we
        // need to make sure the layer does not extend outside the bounds
        // of the framebuffer
        bounds.intersect(snapshot->previous->viewport);
    }

    if (bounds.isEmpty() || bounds.getWidth() > mCaches.maxTextureSize ||
            bounds.getHeight() > mCaches.maxTextureSize) {
        snapshot->empty = fboLayer;
    } else {
        snapshot->invisible = snapshot->invisible || (alpha <= ALPHA_THRESHOLD && fboLayer);
    }

    // Bail out if we won't draw in this snapshot
    if (snapshot->invisible || snapshot->empty) {
        return false;
    }

    glActiveTexture(gTextureUnits[0]);
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
        return createFboLayer(layer, bounds, snapshot, previousFbo);
    } else {
        // Copy the framebuffer into the layer
        glBindTexture(GL_TEXTURE_2D, layer->texture);

        if (layer->empty) {
            glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bounds.left,
                    snapshot->height - bounds.bottom, layer->width, layer->height, 0);
            layer->empty = false;
        } else {
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bounds.left,
                    snapshot->height - bounds.bottom, bounds.getWidth(), bounds.getHeight());
        }

        // Enqueue the buffer coordinates to clear the corresponding region later
        mLayers.push(new Rect(bounds));
    }

    return true;
}

bool OpenGLRenderer::createFboLayer(Layer* layer, Rect& bounds, sp<Snapshot> snapshot,
        GLuint previousFbo) {
    layer->fbo = mCaches.fboCache.get();

#if RENDER_LAYERS_AS_REGIONS
    snapshot->region = &snapshot->layer->region;
    snapshot->flags |= Snapshot::kFlagFboTarget;
#endif

    Rect clip(bounds);
    snapshot->transform->mapRect(clip);
    clip.intersect(*snapshot->clipRect);
    clip.snapToPixelBoundaries();
    clip.intersect(snapshot->previous->viewport);

    mat4 inverse;
    inverse.loadInverse(*mSnapshot->transform);

    inverse.mapRect(clip);
    clip.snapToPixelBoundaries();
    clip.intersect(bounds);
    clip.translate(-bounds.left, -bounds.top);

    snapshot->flags |= Snapshot::kFlagIsFboLayer;
    snapshot->fbo = layer->fbo;
    snapshot->resetTransform(-bounds.left, -bounds.top, 0.0f);
    //snapshot->resetClip(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
    snapshot->resetClip(clip.left, clip.top, clip.right, clip.bottom);
    snapshot->viewport.set(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
    snapshot->height = bounds.getHeight();
    snapshot->flags |= Snapshot::kFlagDirtyOrtho;
    snapshot->orthoMatrix.load(mOrthoMatrix);

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

#if DEBUG_LAYERS_AS_REGIONS
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Framebuffer incomplete (GL error code 0x%x)", status);

        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glDeleteTextures(1, &layer->texture);
        mCaches.fboCache.put(layer->fbo);

        delete layer;

        return false;
    }
#endif

    // Clear the FBO, expand the clear region by 1 to get nice bilinear filtering
    glScissor(clip.left - 1.0f, bounds.getHeight() - clip.bottom - 1.0f,
            clip.getWidth() + 2.0f, clip.getHeight() + 2.0f);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    dirtyClip();

    // Change the ortho projection
    glViewport(0, 0, bounds.getWidth(), bounds.getHeight());
    mOrthoMatrix.loadOrtho(0.0f, bounds.getWidth(), bounds.getHeight(), 0.0f, -1.0f, 1.0f);

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

    const bool fboLayer = current->flags & Snapshot::kFlagIsFboLayer;

    if (fboLayer) {
        // Unbind current FBO and restore previous one
        glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);
    }

    Layer* layer = current->layer;
    const Rect& rect = layer->layer;

    if (!fboLayer && layer->alpha < 255) {
        drawColorRect(rect.left, rect.top, rect.right, rect.bottom,
                layer->alpha << 24, SkXfermode::kDstIn_Mode, true);
        // Required below, composeLayerRect() will divide by 255
        layer->alpha = 255;
    }

    mCaches.unbindMeshBuffer();

    glActiveTexture(gTextureUnits[0]);

    // When the layer is stored in an FBO, we can save a bit of fillrate by
    // drawing only the dirty region
    if (fboLayer) {
        dirtyLayer(rect.left, rect.top, rect.right, rect.bottom, *previous->transform);
        composeLayerRegion(layer, rect);
    } else {
        dirtyLayer(rect.left, rect.top, rect.right, rect.bottom);
        composeLayerRect(layer, rect, true);
    }

    if (fboLayer) {
        // Detach the texture from the FBO
        glBindFramebuffer(GL_FRAMEBUFFER, current->fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);

        // Put the FBO name back in the cache, if it doesn't fit, it will be destroyed
        mCaches.fboCache.put(current->fbo);
    }

    dirtyClip();

    // Failing to add the layer to the cache should happen only if the layer is too large
    if (!mCaches.layerCache.put(layer)) {
        LAYER_LOGD("Deleting layer");
        glDeleteTextures(1, &layer->texture);
        delete layer;
    }
}

void OpenGLRenderer::composeLayerRect(Layer* layer, const Rect& rect, bool swap) {
    const Rect& texCoords = layer->texCoords;
    resetDrawTextureTexCoords(texCoords.left, texCoords.top, texCoords.right, texCoords.bottom);

    drawTextureMesh(rect.left, rect.top, rect.right, rect.bottom, layer->texture,
            layer->alpha / 255.0f, layer->mode, layer->blend, &mMeshVertices[0].position[0],
            &mMeshVertices[0].texture[0], GL_TRIANGLE_STRIP, gMeshCount, swap, swap);

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
}

void OpenGLRenderer::composeLayerRegion(Layer* layer, const Rect& rect) {
#if RENDER_LAYERS_AS_REGIONS
    if (layer->region.isRect()) {
        composeLayerRect(layer, rect);
        layer->region.clear();
        return;
    }

    if (!layer->region.isEmpty()) {
        size_t count;
        const android::Rect* rects = layer->region.getArray(&count);

        setupDraw();

        ProgramDescription description;
        description.hasTexture = true;

        const float alpha = layer->alpha / 255.0f;
        const bool setColor = description.setColor(alpha, alpha, alpha, alpha);
        chooseBlending(layer->blend || layer->alpha < 255, layer->mode, description, false);

        useProgram(mCaches.programCache.get(description));

        // Texture
        bindTexture(layer->texture);
        glUniform1i(mCaches.currentProgram->getUniform("sampler"), 0);

        // Always premultiplied
        if (setColor) {
            mCaches.currentProgram->setColor(alpha, alpha, alpha, alpha);
        }

        // Mesh
        int texCoordsSlot = mCaches.currentProgram->getAttrib("texCoords");
        glEnableVertexAttribArray(texCoordsSlot);

        mModelView.loadIdentity();
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);

        const float texX = 1.0f / float(layer->width);
        const float texY = 1.0f / float(layer->height);

        TextureVertex* mesh = mCaches.getRegionMesh();
        GLsizei numQuads = 0;

        glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
                gMeshStride, &mesh[0].position[0]);
        glVertexAttribPointer(texCoordsSlot, 2, GL_FLOAT, GL_FALSE,
                gMeshStride, &mesh[0].texture[0]);

        for (size_t i = 0; i < count; i++) {
            const android::Rect* r = &rects[i];

            const float u1 = r->left * texX;
            const float v1 = (rect.getHeight() - r->top) * texY;
            const float u2 = r->right * texX;
            const float v2 = (rect.getHeight() - r->bottom) * texY;

            // TODO: Reject quads outside of the clip
            TextureVertex::set(mesh++, r->left, r->top, u1, v1);
            TextureVertex::set(mesh++, r->right, r->top, u2, v1);
            TextureVertex::set(mesh++, r->left, r->bottom, u1, v2);
            TextureVertex::set(mesh++, r->right, r->bottom, u2, v2);

            numQuads++;

            if (numQuads >= REGION_MESH_QUAD_COUNT) {
                glDrawElements(GL_TRIANGLES, numQuads * 6, GL_UNSIGNED_SHORT, NULL);
                numQuads = 0;
                mesh = mCaches.getRegionMesh();
            }
        }

        if (numQuads > 0) {
            glDrawElements(GL_TRIANGLES, numQuads * 6, GL_UNSIGNED_SHORT, NULL);
        }

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glDisableVertexAttribArray(texCoordsSlot);

#if DEBUG_LAYERS_AS_REGIONS
        uint32_t colors[] = {
                0x7fff0000, 0x7f00ff00,
                0x7f0000ff, 0x7fff00ff,
        };

        int offset = 0;
        int32_t top = rects[0].top;
        int i = 0;

        for (size_t i = 0; i < count; i++) {
            if (top != rects[i].top) {
                offset ^= 0x2;
                top = rects[i].top;
            }

            Rect r(rects[i].left, rects[i].top, rects[i].right, rects[i].bottom);
            drawColorRect(r.left, r.top, r.right, r.bottom, colors[offset + (i & 0x1)],
                    SkXfermode::kSrcOver_Mode);
        }
#endif

        layer->region.clear();
    }
#else
    composeLayerRect(layer, rect);
#endif
}

void OpenGLRenderer::dirtyLayer(const float left, const float top,
        const float right, const float bottom, const mat4 transform) {
#if RENDER_LAYERS_AS_REGIONS
    if ((mSnapshot->flags & Snapshot::kFlagFboTarget) && mSnapshot->region) {
        Rect bounds(left, top, right, bottom);
        transform.mapRect(bounds);
        bounds.intersect(*mSnapshot->clipRect);
        bounds.snapToPixelBoundaries();

        android::Rect dirty(bounds.left, bounds.top, bounds.right, bounds.bottom);
        if (!dirty.isEmpty()) {
            mSnapshot->region->orSelf(dirty);
        }
    }
#endif
}

void OpenGLRenderer::dirtyLayer(const float left, const float top,
        const float right, const float bottom) {
#if RENDER_LAYERS_AS_REGIONS
    if ((mSnapshot->flags & Snapshot::kFlagFboTarget) && mSnapshot->region) {
        Rect bounds(left, top, right, bottom);
        bounds.intersect(*mSnapshot->clipRect);
        bounds.snapToPixelBoundaries();

        android::Rect dirty(bounds.left, bounds.top, bounds.right, bounds.bottom);
        if (!dirty.isEmpty()) {
            mSnapshot->region->orSelf(dirty);
        }
    }
#endif
}

void OpenGLRenderer::clearLayerRegions() {
    if (mLayers.size() == 0 || mSnapshot->isIgnored()) return;

    Rect clipRect(*mSnapshot->clipRect);
    clipRect.snapToPixelBoundaries();

    for (uint32_t i = 0; i < mLayers.size(); i++) {
        Rect* bounds = mLayers.itemAt(i);
        if (clipRect.intersects(*bounds)) {
            // Clear the framebuffer where the layer will draw
            glScissor(bounds->left, mSnapshot->height - bounds->bottom,
                    bounds->getWidth(), bounds->getHeight());
            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            glClear(GL_COLOR_BUFFER_BIT);

            // Restore the clip
            dirtyClip();
        }

        delete bounds;
    }

    mLayers.clear();
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

const float* OpenGLRenderer::getMatrix() const {
    if (mSnapshot->fbo != 0) {
        return &mSnapshot->transform->data[0];
    }
    return &mIdentity.data[0];
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
    mSnapshot->transform->copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
    SkMatrix transform;
    mSnapshot->transform->copyTo(transform);
    transform.preConcat(*matrix);
    mSnapshot->transform->load(transform);
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
    Rect clip(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();
    glScissor(clip.left, mSnapshot->height - clip.bottom, clip.getWidth(), clip.getHeight());
    mDirtyClip = false;
}

const Rect& OpenGLRenderer::getClipBounds() {
    return mSnapshot->getLocalClip();
}

bool OpenGLRenderer::quickReject(float left, float top, float right, float bottom) {
    if (mSnapshot->isIgnored()) {
        return true;
    }

    Rect r(left, top, right, bottom);
    mSnapshot->transform->mapRect(r);
    r.snapToPixelBoundaries();

    Rect clipRect(*mSnapshot->clipRect);
    clipRect.snapToPixelBoundaries();

    return !clipRect.intersects(r);
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    bool clipped = mSnapshot->clip(left, top, right, bottom, op);
    if (clipped) {
        dirtyClip();
    }
    return !mSnapshot->clipRect->isEmpty();
}

///////////////////////////////////////////////////////////////////////////////
// Drawing commands
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setupDraw() {
    clearLayerRegions();
    if (mDirtyClip) {
        setScissorFromClip();
    }
    mDescription.reset();
    mSetShaderColor = false;
    mColorSet = false;
    mColorA = mColorR = mColorG = mColorB = 0.0f;
    mTextureUnit = 0;
    mTrackDirtyRegions = true;
}

void OpenGLRenderer::setupDrawWithTexture(bool isAlpha8) {
    mDescription.hasTexture = true;
    mDescription.hasAlpha8Texture = isAlpha8;
}

void OpenGLRenderer::setupDrawColor(int color) {
    mColorA = ((color >> 24) & 0xFF) / 255.0f;
    const float a = mColorA / 255.0f;
    mColorR = a * ((color >> 16) & 0xFF);
    mColorG = a * ((color >>  8) & 0xFF);
    mColorB = a * ((color      ) & 0xFF);
    mColorSet = true;
    mSetShaderColor = mDescription.setColor(mColorR, mColorG, mColorB, mColorA);
}

void OpenGLRenderer::setupDrawColor(float r, float g, float b, float a) {
    mColorA = a;
    mColorR = r;
    mColorG = g;
    mColorB = b;
    mColorSet = true;
    mSetShaderColor = mDescription.setColor(r, g, b, a);
}

void OpenGLRenderer::setupDrawShader() {
    if (mShader) {
        mShader->describe(mDescription, mCaches.extensions);
    }
}

void OpenGLRenderer::setupDrawColorFilter() {
    if (mColorFilter) {
        mColorFilter->describe(mDescription, mCaches.extensions);
    }
}

void OpenGLRenderer::setupDrawBlending(SkXfermode::Mode mode, bool swapSrcDst) {
    chooseBlending((mColorSet && mColorA < 1.0f) || (mShader && mShader->blend()), mode,
            mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawBlending(bool blend, SkXfermode::Mode mode, bool swapSrcDst) {
    chooseBlending(blend || (mColorSet && mColorA < 1.0f) || (mShader && mShader->blend()), mode,
            mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawProgram() {
    useProgram(mCaches.programCache.get(mDescription));
}

void OpenGLRenderer::setupDrawDirtyRegionsDisabled() {
    mTrackDirtyRegions = false;
}

void OpenGLRenderer::setupDrawModelViewTranslate(float left, float top, float right, float bottom,
        bool ignoreTransform) {
    mModelView.loadTranslate(left, top, 0.0f);
    if (!ignoreTransform) {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
        if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
    } else {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, mIdentity);
        if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom);
    }
}

void OpenGLRenderer::setupDrawModelView(float left, float top, float right, float bottom,
        bool ignoreTransform, bool ignoreModelView) {
    if (!ignoreModelView) {
        mModelView.loadTranslate(left, top, 0.0f);
        mModelView.scale(right - left, bottom - top, 1.0f);
        if (!ignoreTransform) {
            mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
            if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
        } else {
            mCaches.currentProgram->set(mOrthoMatrix, mModelView, mIdentity);
            if (mTrackDirtyRegions) dirtyLayer(left, top, right, bottom);
        }
    } else {
        mModelView.loadIdentity();
    }
}

void OpenGLRenderer::setupDrawColorUniforms() {
    if (mColorSet || (mShader && mSetShaderColor)) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawColorAlphaUniforms() {
    if (mSetShaderColor) {
        mCaches.currentProgram->setColor(mColorA, mColorA, mColorA, mColorA);
    }
}

void OpenGLRenderer::setupDrawShaderUniforms(bool ignoreTransform) {
    if (mShader) {
        if (ignoreTransform) {
            mModelView.loadInverse(*mSnapshot->transform);
        }
        mShader->setupProgram(mCaches.currentProgram, mModelView, *mSnapshot, &mTextureUnit);
    }
}

void OpenGLRenderer::setupDrawColorFilterUniforms() {
    if (mColorFilter) {
        mColorFilter->setupProgram(mCaches.currentProgram);
    }
}

void OpenGLRenderer::setupDrawSimpleMesh() {
    mCaches.bindMeshBuffer();
    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, 0);
}

void OpenGLRenderer::setupDrawTexture(GLuint texture) {
    bindTexture(texture);
    glUniform1i(mCaches.currentProgram->getUniform("sampler"), mTextureUnit++);

    mTexCoordsSlot = mCaches.currentProgram->getAttrib("texCoords");
    glEnableVertexAttribArray(mTexCoordsSlot);
}

void OpenGLRenderer::setupDrawMesh(GLvoid* vertices, GLvoid* texCoords, GLuint vbo) {
    if (!vertices) {
        mCaches.bindMeshBuffer(vbo == 0 ? mCaches.meshBuffer : vbo);
    } else {
        mCaches.unbindMeshBuffer();
    }
    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, vertices);
    glVertexAttribPointer(mTexCoordsSlot, 2, GL_FLOAT, GL_FALSE, gMeshStride, texCoords);
}

void OpenGLRenderer::finishDrawTexture() {
    glDisableVertexAttribArray(mTexCoordsSlot);
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawDisplayList(DisplayList* displayList) {
    // All the usual checks and setup operations (quickReject, setupDraw, etc.)
    // will be performed by the display list itself
    if (displayList) {
        displayList->replay(*this);
    }
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    const float right = left + bitmap->width();
    const float bottom = top + bitmap->height();

    if (quickReject(left, top, right, bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    drawTextureRect(left, top, right, bottom, texture, paint);
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint) {
    Rect r(0.0f, 0.0f, bitmap->width(), bitmap->height());
    const mat4 transform(*matrix);
    transform.mapRect(r);

    if (quickReject(r.left, r.top, r.right, r.bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    // This could be done in a cheaper way, all we need is pass the matrix
    // to the vertex shader. The save/restore is a bit overkill.
    save(SkCanvas::kMatrix_SaveFlag);
    concatMatrix(matrix);
    drawTextureRect(0.0f, 0.0f, bitmap->width(), bitmap->height(), texture, paint);
    restore();
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap,
         float srcLeft, float srcTop, float srcRight, float srcBottom,
         float dstLeft, float dstTop, float dstRight, float dstBottom,
         SkPaint* paint) {
    if (quickReject(dstLeft, dstTop, dstRight, dstBottom)) {
        return;
    }

    glActiveTexture(gTextureUnits[0]);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);
    setTextureWrapModes(texture, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);

    const float width = texture->width;
    const float height = texture->height;

    const float u1 = srcLeft / width;
    const float v1 = srcTop / height;
    const float u2 = srcRight / width;
    const float v2 = srcBottom / height;

    mCaches.unbindMeshBuffer();
    resetDrawTextureTexCoords(u1, v1, u2, v2);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (mSnapshot->transform->isPureTranslate()) {
        const float x = (int) floorf(dstLeft + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(dstTop + mSnapshot->transform->getTranslateY() + 0.5f);

        drawTextureMesh(x, y, x + (dstRight - dstLeft), y + (dstBottom - dstTop),
                texture->id, alpha / 255.0f, mode, texture->blend,
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, false, true);
    } else {
        drawTextureMesh(dstLeft, dstTop, dstRight, dstBottom, texture->id, alpha / 255.0f,
                mode, texture->blend, &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount);
    }

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
}

void OpenGLRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
        float left, float top, float right, float bottom, SkPaint* paint) {
    if (quickReject(left, top, right, bottom)) {
        return;
    }

    glActiveTexture(gTextureUnits[0]);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);
    setTextureWrapModes(texture, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    const Patch* mesh = mCaches.patchCache.get(bitmap->width(), bitmap->height(),
            right - left, bottom - top, xDivs, yDivs, colors, width, height, numColors);

    if (mesh && mesh->verticesCount > 0) {
        const bool pureTranslate = mSnapshot->transform->isPureTranslate();
#if RENDER_LAYERS_AS_REGIONS
        // Mark the current layer dirty where we are going to draw the patch
        if ((mSnapshot->flags & Snapshot::kFlagFboTarget) &&
                mSnapshot->region && mesh->hasEmptyQuads) {
            const size_t count = mesh->quads.size();
            for (size_t i = 0; i < count; i++) {
                const Rect& bounds = mesh->quads.itemAt(i);
                if (pureTranslate) {
                    const float x = (int) floorf(bounds.left + 0.5f);
                    const float y = (int) floorf(bounds.top + 0.5f);
                    dirtyLayer(x, y, x + bounds.getWidth(), y + bounds.getHeight(),
                            *mSnapshot->transform);
                } else {
                    dirtyLayer(bounds.left, bounds.top, bounds.right, bounds.bottom,
                            *mSnapshot->transform);
                }
            }
        }
#endif

        if (pureTranslate) {
            const float x = (int) floorf(left + mSnapshot->transform->getTranslateX() + 0.5f);
            const float y = (int) floorf(top + mSnapshot->transform->getTranslateY() + 0.5f);

            drawTextureMesh(x, y, x + right - left, y + bottom - top, texture->id, alpha / 255.0f,
                    mode, texture->blend, (GLvoid*) 0, (GLvoid*) gMeshTextureOffset,
                    GL_TRIANGLES, mesh->verticesCount, false, true, mesh->meshBuffer,
                    true, !mesh->hasEmptyQuads);
        } else {
            drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f,
                    mode, texture->blend, (GLvoid*) 0, (GLvoid*) gMeshTextureOffset,
                    GL_TRIANGLES, mesh->verticesCount, false, false, mesh->meshBuffer,
                    true, !mesh->hasEmptyQuads);
        }
    }
}

void OpenGLRenderer::drawLines(float* points, int count, SkPaint* paint) {
    // TODO: Should do quickReject for each line
    if (mSnapshot->isIgnored()) return;

    const bool isAA = paint->isAntiAlias();
    const float strokeWidth = paint->getStrokeWidth() * 0.5f;
    // A stroke width of 0 has a special meaningin Skia:
    // it draws an unscaled 1px wide line
    const bool isHairLine = paint->getStrokeWidth() == 0.0f;

    setupDraw();

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    uint32_t color = paint->getColor();
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    // Used only with AA lines
    GLuint textureUnit = 0;

    // Describe the required shaders
    ProgramDescription description;
    const bool setColor = description.setColor(r, g, b, a);

    if (mShader) {
        mShader->describe(description, mCaches.extensions);
    }
    if (mColorFilter) {
        mColorFilter->describe(description, mCaches.extensions);
    }

    // Setup the blending mode
    chooseBlending(a < 1.0f || (mShader && mShader->blend()), mode, description);

    // We're not drawing with VBOs here
    mCaches.unbindMeshBuffer();

    int verticesCount = count >> 2;
    if (!isHairLine) {
        // TODO: AA needs more vertices
        verticesCount *= 6;
    } else {
        // TODO: AA will be different
        verticesCount *= 2;
    }

    TextureVertex lines[verticesCount];
    TextureVertex* vertex = &lines[0];

    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, vertex);

    // Build and use the appropriate shader
    useProgram(mCaches.programCache.get(description));
    mCaches.currentProgram->set(mOrthoMatrix, mIdentity, *mSnapshot->transform);

    if (!mShader || (mShader && setColor)) {
        mCaches.currentProgram->setColor(r, g, b, a);
    }

    if (mShader) {
        mShader->setupProgram(mCaches.currentProgram, mIdentity, *mSnapshot, &textureUnit);
    }
    if (mColorFilter) {
        mColorFilter->setupProgram(mCaches.currentProgram);
    }

    if (!isHairLine) {
        // TODO: Handle the AA case
        for (int i = 0; i < count; i += 4) {
            // a = start point, b = end point
            vec2 a(points[i], points[i + 1]);
            vec2 b(points[i + 2], points[i + 3]);

            // Bias to snap to the same pixels as Skia
            a += 0.375;
            b += 0.375;

            // Find the normal to the line
            vec2 n = (b - a).copyNormalized() * strokeWidth;
            float x = n.x;
            n.x = -n.y;
            n.y = x;

            // Four corners of the rectangle defining a thick line
            vec2 p1 = a - n;
            vec2 p2 = a + n;
            vec2 p3 = b + n;
            vec2 p4 = b - n;

            // Draw the line as 2 triangles, could be optimized
            // by using only 4 vertices and the correct indices
            // Also we should probably used non textured vertices
            // when line AA is disabled to save on bandwidth
            TextureVertex::set(vertex++, p1.x, p1.y, 0.0f, 0.0f);
            TextureVertex::set(vertex++, p2.x, p2.y, 0.0f, 0.0f);
            TextureVertex::set(vertex++, p3.x, p3.y, 0.0f, 0.0f);
            TextureVertex::set(vertex++, p1.x, p1.y, 0.0f, 0.0f);
            TextureVertex::set(vertex++, p3.x, p3.y, 0.0f, 0.0f);
            TextureVertex::set(vertex++, p4.x, p4.y, 0.0f, 0.0f);

            // TODO: Mark the dirty regions when RENDER_LAYERS_AS_REGIONS is set
        }

        // GL_LINE does not give the result we want to match Skia
        glDrawArrays(GL_TRIANGLES, 0, verticesCount);
    } else {
        // TODO: Handle the AA case
        for (int i = 0; i < count; i += 4) {
            TextureVertex::set(vertex++, points[i], points[i + 1], 0.0f, 0.0f);
            TextureVertex::set(vertex++, points[i + 2], points[i + 3], 0.0f, 0.0f);
        }
        glLineWidth(1.0f);
        glDrawArrays(GL_LINES, 0, verticesCount);
    }
}

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    // No need to check against the clip, we fill the clip region
    if (mSnapshot->isIgnored()) return;

    Rect& clip(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();

    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color, mode, true);
}

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom, SkPaint* p) {
    if (quickReject(left, top, right, bottom)) {
        return;
    }

    SkXfermode::Mode mode;
    if (!mCaches.extensions.hasFramebufferFetch()) {
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
    if (mSnapshot->isIgnored()) return;

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

    // TODO: Handle paint->getTextScaleX()
    const float oldX = x;
    const float oldY = y;
    const bool pureTranslate = mSnapshot->transform->isPureTranslate();
    if (pureTranslate) {
        x = (int) floorf(x + mSnapshot->transform->getTranslateX() + 0.5f);
        y = (int) floorf(y + mSnapshot->transform->getTranslateY() + 0.5f);
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

    setupDraw();

    if (mHasShadow) {
        glActiveTexture(gTextureUnits[0]);
        mCaches.dropShadowCache.setFontRenderer(fontRenderer);
        const ShadowTexture* shadow = mCaches.dropShadowCache.get(paint, text, bytesCount,
                count, mShadowRadius);
        const AutoTexture autoCleanup(shadow);

        setupShadow(shadow, x, y, mode, a, pureTranslate);

        // Draw the mesh
        glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
        glDisableVertexAttribArray(mCaches.currentProgram->getAttrib("texCoords"));
    }

    GLuint textureUnit = 0;
    glActiveTexture(gTextureUnits[textureUnit]);

    // Pick the appropriate texture filtering
    bool linearFilter = mSnapshot->transform->changesBounds();
    if (pureTranslate && !linearFilter) {
        linearFilter = fabs(y - (int) y) > 0.0f || fabs(x - (int) x) > 0.0f;
    }

    // Dimensions are set to (0,0), the layer (if any) won't be dirtied
    setupTextureAlpha8(fontRenderer.getTexture(linearFilter), 0, 0, textureUnit,
            x, y, r, g, b, a, mode, false, true, NULL, NULL, 0, pureTranslate);

    const Rect* clip = pureTranslate ? mSnapshot->clipRect : &mSnapshot->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

#if RENDER_LAYERS_AS_REGIONS
    bool hasLayer = (mSnapshot->flags & Snapshot::kFlagFboTarget) && mSnapshot->region;
#else
    bool hasLayer = false;
#endif

    mCaches.unbindMeshBuffer();
    if (fontRenderer.renderText(paint, clip, text, 0, bytesCount, count, x, y,
            hasLayer ? &bounds : NULL)) {
#if RENDER_LAYERS_AS_REGIONS
        if (hasLayer) {
            if (!pureTranslate) {
                mSnapshot->transform->mapRect(bounds);
            }
            bounds.intersect(*mSnapshot->clipRect);
            bounds.snapToPixelBoundaries();

            android::Rect dirty(bounds.left, bounds.top, bounds.right, bounds.bottom);
            mSnapshot->region->orSelf(dirty);
        }
#endif
    }

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glDisableVertexAttribArray(mCaches.currentProgram->getAttrib("texCoords"));

    drawTextDecorations(text, bytesCount, length, oldX, oldY, paint);
}

void OpenGLRenderer::drawPath(SkPath* path, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

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

    setupDraw();

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
        SkXfermode::Mode mode, float alpha, bool ignoreTransforms) {
    const float sx = x - texture->left + mShadowDx;
    const float sy = y - texture->top + mShadowDy;

    const int shadowAlpha = ((mShadowColor >> 24) & 0xFF);
    const GLfloat a = shadowAlpha < 255 ? shadowAlpha / 255.0f : alpha;
    const GLfloat r = a * ((mShadowColor >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((mShadowColor >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((mShadowColor      ) & 0xFF) / 255.0f;

    GLuint textureUnit = 0;
    setupTextureAlpha8(texture->id, texture->width, texture->height, textureUnit,
            sx, sy, r, g, b, a, mode, true, false,
            (GLvoid*) 0, (GLvoid*) gMeshTextureOffset, 0, ignoreTransforms);
}

void OpenGLRenderer::setupTextureAlpha8(const Texture* texture, GLuint& textureUnit,
        float x, float y, float r, float g, float b, float a, SkXfermode::Mode mode,
        bool transforms, bool applyFilters) {
    setupTextureAlpha8(texture->id, texture->width, texture->height, textureUnit,
            x, y, r, g, b, a, mode, transforms, applyFilters,
            (GLvoid*) 0, (GLvoid*) gMeshTextureOffset);
}

void OpenGLRenderer::setupTextureAlpha8(GLuint texture, uint32_t width, uint32_t height,
        GLuint& textureUnit, float x, float y, float r, float g, float b, float a,
        SkXfermode::Mode mode, bool transforms, bool applyFilters) {
    setupTextureAlpha8(texture, width, height, textureUnit, x, y, r, g, b, a, mode,
            transforms, applyFilters, (GLvoid*) 0, (GLvoid*) gMeshTextureOffset);
}

void OpenGLRenderer::setupTextureAlpha8(GLuint texture, uint32_t width, uint32_t height,
        GLuint& textureUnit, float x, float y, float r, float g, float b, float a,
        SkXfermode::Mode mode, bool transforms, bool applyFilters,
        GLvoid* vertices, GLvoid* texCoords, GLuint vbo, bool ignoreTransform) {
     // Describe the required shaders
     ProgramDescription description;
     description.hasTexture = true;
     description.hasAlpha8Texture = true;
     const bool setColor = description.setAlpha8Color(r, g, b, a);

     if (applyFilters) {
         if (mShader) {
             mShader->describe(description, mCaches.extensions);
         }
         if (mColorFilter) {
             mColorFilter->describe(description, mCaches.extensions);
         }
     }

     // Setup the blending mode
     chooseBlending(true, mode, description);

     // Build and use the appropriate shader
     useProgram(mCaches.programCache.get(description));

     bindTexture(texture);
     glUniform1i(mCaches.currentProgram->getUniform("sampler"), textureUnit);

     int texCoordsSlot = mCaches.currentProgram->getAttrib("texCoords");
     glEnableVertexAttribArray(texCoordsSlot);

     if (texCoords) {
         // Setup attributes
         if (!vertices) {
             mCaches.bindMeshBuffer(vbo == 0 ? mCaches.meshBuffer : vbo);
         } else {
             mCaches.unbindMeshBuffer();
         }
         glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
                 gMeshStride, vertices);
         glVertexAttribPointer(texCoordsSlot, 2, GL_FLOAT, GL_FALSE, gMeshStride, texCoords);
     }

     // Setup uniforms
     if (transforms) {
         mModelView.loadTranslate(x, y, 0.0f);
         mModelView.scale(width, height, 1.0f);
     } else {
         mModelView.loadIdentity();
     }

     mat4 t;
     if (!ignoreTransform) {
         t.load(*mSnapshot->transform);
     }

     mCaches.currentProgram->set(mOrthoMatrix, mModelView, t);
     if (width > 0 && height > 0) {
         dirtyLayer(x, y, x + width, y + height, t);
     }

     if (setColor) {
         mCaches.currentProgram->setColor(r, g, b, a);
     }

     textureUnit++;
     if (applyFilters) {
         // Setup attributes and uniforms required by the shaders
         if (mShader) {
             if (ignoreTransform) {
                 mModelView.loadInverse(*mSnapshot->transform);
             }
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
    // If a shader is set, preserve only the alpha
    if (mShader) {
        color |= 0x00ffffff;
    }

    setupDraw();
    setupDrawColor(color);
    setupDrawShader();
    setupDrawColorFilter();
    setupDrawBlending(mode);
    setupDrawProgram();
    setupDrawModelView(left, top, right, bottom, ignoreTransform);
    setupDrawColorUniforms();
    setupDrawShaderUniforms(ignoreTransform);
    setupDrawColorFilterUniforms();
    setupDrawSimpleMesh();

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        Texture* texture, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    setTextureWrapModes(texture, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);

    if (mSnapshot->transform->isPureTranslate()) {
        const float x = (int) floorf(left + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(top + mSnapshot->transform->getTranslateY() + 0.5f);

        drawTextureMesh(x, y, x + texture->width, y + texture->height, texture->id,
                alpha / 255.0f, mode, texture->blend, (GLvoid*) NULL,
                (GLvoid*) gMeshTextureOffset, GL_TRIANGLE_STRIP, gMeshCount, false, true);
    } else {
        drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f, mode,
                texture->blend, (GLvoid*) NULL, (GLvoid*) gMeshTextureOffset,
                GL_TRIANGLE_STRIP, gMeshCount);
    }
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend) {
    drawTextureMesh(left, top, right, bottom, texture, alpha, mode, blend,
            (GLvoid*) NULL, (GLvoid*) gMeshTextureOffset, GL_TRIANGLE_STRIP, gMeshCount);
}

void OpenGLRenderer::drawTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend,
        GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
        bool swapSrcDst, bool ignoreTransform, GLuint vbo, bool ignoreScale, bool dirty) {

    setupDraw();
    setupDrawWithTexture();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter();
    setupDrawBlending(blend, mode, swapSrcDst);
    setupDrawProgram();
    if (!dirty) {
        setupDrawDirtyRegionsDisabled();
    }
    if (!ignoreScale) {
        setupDrawModelView(left, top, right, bottom, ignoreTransform);
    } else {
        setupDrawModelViewTranslate(left, top, right, bottom, ignoreTransform);
    }
    setupDrawColorAlphaUniforms();
    setupDrawColorFilterUniforms();
    setupDrawTexture(texture);
    setupDrawMesh(vertices, texCoords, vbo);

    glDrawArrays(drawMode, 0, elementsCount);

    finishDrawTexture();
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
            if (mCaches.extensions.hasFramebufferFetch()) {
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

void OpenGLRenderer::getAlphaAndMode(SkPaint* paint, int* alpha, SkXfermode::Mode* mode) {
    if (paint) {
        if (!mCaches.extensions.hasFramebufferFetch()) {
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

void OpenGLRenderer::setTextureWrapModes(Texture* texture, GLenum wrapS, GLenum wrapT) {
    bool bound = false;
    if (wrapS != texture->wrapS) {
        glBindTexture(GL_TEXTURE_2D, texture->id);
        bound = true;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapS);
        texture->wrapS = wrapS;
    }
    if (wrapT != texture->wrapT) {
        if (!bound) {
            glBindTexture(GL_TEXTURE_2D, texture->id);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapT);
        texture->wrapT = wrapT;
    }
}

}; // namespace uirenderer
}; // namespace android
