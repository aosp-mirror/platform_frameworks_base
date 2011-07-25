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

#include <private/hwui/DrawGlInfo.h>

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
    { SkXfermode::kClear_Mode,   GL_ZERO,                 GL_ONE_MINUS_SRC_ALPHA },
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
    { SkXfermode::kClear_Mode,   GL_ONE_MINUS_DST_ALPHA,  GL_ZERO },
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
    glDisable(GL_DITHER);
    glViewport(0, 0, width, height);
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;

    mFirstSnapshot->height = height;
    mFirstSnapshot->viewport.set(0, 0, width, height);

    mDirtyClip = false;
}

void OpenGLRenderer::prepare(bool opaque) {
    prepareDirty(0.0f, 0.0f, mWidth, mHeight, opaque);
}

void OpenGLRenderer::prepareDirty(float left, float top, float right, float bottom, bool opaque) {
    mCaches.clearGarbage();

    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSnapshot->fbo = getTargetFbo();

    mSaveCount = 1;

    glViewport(0, 0, mWidth, mHeight);

    glEnable(GL_SCISSOR_TEST);
    glScissor(left, mSnapshot->height - bottom, right - left, bottom - top);
    mSnapshot->setClip(left, top, right, bottom);

    if (!opaque) {
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }
}

void OpenGLRenderer::finish() {
#if DEBUG_OPENGL
    GLenum status = GL_NO_ERROR;
    while ((status = glGetError()) != GL_NO_ERROR) {
        LOGD("GL error from OpenGLRenderer: 0x%x", status);
        switch (status) {
            case GL_OUT_OF_MEMORY:
                LOGE("  OpenGLRenderer is out of memory!");
                break;
        }
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

void OpenGLRenderer::interrupt() {
    if (mCaches.currentProgram) {
        if (mCaches.currentProgram->isInUse()) {
            mCaches.currentProgram->remove();
            mCaches.currentProgram = NULL;
        }
    }
    mCaches.unbindMeshBuffer();
}

void OpenGLRenderer::resume() {
    glViewport(0, 0, mSnapshot->viewport.getWidth(), mSnapshot->viewport.getHeight());

    glEnable(GL_SCISSOR_TEST);
    dirtyClip();

    glDisable(GL_DITHER);

    glBindFramebuffer(GL_FRAMEBUFFER, getTargetFbo());
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    mCaches.blend = true;
    glEnable(GL_BLEND);
    glBlendFunc(mCaches.lastSrcMode, mCaches.lastDstMode);
    glBlendEquation(GL_FUNC_ADD);
}

bool OpenGLRenderer::callDrawGLFunction(Functor *functor, Rect& dirty) {
    interrupt();
    if (mDirtyClip) {
        setScissorFromClip();
    }

    Rect clip(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();

#if RENDER_LAYERS_AS_REGIONS
    // Since we don't know what the functor will draw, let's dirty
    // tne entire clip region
    if (hasLayer()) {
        dirtyLayerUnchecked(clip, getRegion());
    }
#endif

    DrawGlInfo info;
    info.clipLeft = clip.left;
    info.clipTop = clip.top;
    info.clipRight = clip.right;
    info.clipBottom = clip.bottom;
    info.isLayer = hasLayer();
    getSnapshot()->transform->copyTo(&info.transform[0]);

    status_t result = (*functor)(0, &info);

    if (result != 0) {
        Rect localDirty(info.dirtyLeft, info.dirtyTop, info.dirtyRight, info.dirtyBottom);
        dirty.unionWith(localDirty);
    }

    resume();
    return result != 0;
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
    if (!fboLayer) {
        mSnapshot->transform->mapRect(bounds);

        // Layers only make sense if they are in the framebuffer's bounds
        if (bounds.intersect(*snapshot->clipRect)) {
            // We cannot work with sub-pixels in this case
            bounds.snapToPixelBoundaries();

            // When the layer is not an FBO, we may use glCopyTexImage so we
            // need to make sure the layer does not extend outside the bounds
            // of the framebuffer
            if (!bounds.intersect(snapshot->previous->viewport)) {
                bounds.setEmpty();
            }
        } else {
            bounds.setEmpty();
        }
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

    layer->setAlpha(alpha, mode);
    layer->layer.set(bounds);
    layer->texCoords.set(0.0f, bounds.getHeight() / float(layer->getHeight()),
            bounds.getWidth() / float(layer->getWidth()), 0.0f);
    layer->setColorFilter(mColorFilter);

    // Save the layer in the snapshot
    snapshot->flags |= Snapshot::kFlagIsLayer;
    snapshot->layer = layer;

    if (fboLayer) {
        return createFboLayer(layer, bounds, snapshot, previousFbo);
    } else {
        // Copy the framebuffer into the layer
        layer->bindTexture();
        if (!bounds.isEmpty()) {
            if (layer->isEmpty()) {
                glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                        bounds.left, snapshot->height - bounds.bottom,
                        layer->getWidth(), layer->getHeight(), 0);
                layer->setEmpty(false);
            } else {
                glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bounds.left,
                        snapshot->height - bounds.bottom, bounds.getWidth(), bounds.getHeight());
            }

            // Enqueue the buffer coordinates to clear the corresponding region later
            mLayers.push(new Rect(bounds));
        }
    }

    return true;
}

bool OpenGLRenderer::createFboLayer(Layer* layer, Rect& bounds, sp<Snapshot> snapshot,
        GLuint previousFbo) {
    layer->setFbo(mCaches.fboCache.get());

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
    snapshot->fbo = layer->getFbo();
    snapshot->resetTransform(-bounds.left, -bounds.top, 0.0f);
    snapshot->resetClip(clip.left, clip.top, clip.right, clip.bottom);
    snapshot->viewport.set(0.0f, 0.0f, bounds.getWidth(), bounds.getHeight());
    snapshot->height = bounds.getHeight();
    snapshot->flags |= Snapshot::kFlagDirtyOrtho;
    snapshot->orthoMatrix.load(mOrthoMatrix);

    // Bind texture to FBO
    glBindFramebuffer(GL_FRAMEBUFFER, layer->getFbo());
    layer->bindTexture();

    // Initialize the texture if needed
    if (layer->isEmpty()) {
        layer->allocateTexture(GL_RGBA, GL_UNSIGNED_BYTE);
        layer->setEmpty(false);
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            layer->getTexture(), 0);

#if DEBUG_LAYERS_AS_REGIONS
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Framebuffer incomplete (GL error code 0x%x)", status);

        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        layer->deleteTexture();
        mCaches.fboCache.put(layer->getFbo());

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

    if (!fboLayer && layer->getAlpha() < 255) {
        drawColorRect(rect.left, rect.top, rect.right, rect.bottom,
                layer->getAlpha() << 24, SkXfermode::kDstIn_Mode, true);
        // Required below, composeLayerRect() will divide by 255
        layer->setAlpha(255);
    }

    mCaches.unbindMeshBuffer();

    glActiveTexture(gTextureUnits[0]);

    // When the layer is stored in an FBO, we can save a bit of fillrate by
    // drawing only the dirty region
    if (fboLayer) {
        dirtyLayer(rect.left, rect.top, rect.right, rect.bottom, *previous->transform);
        if (layer->getColorFilter()) {
            setupColorFilter(layer->getColorFilter());
        }
        composeLayerRegion(layer, rect);
        if (layer->getColorFilter()) {
            resetColorFilter();
        }
    } else if (!rect.isEmpty()) {
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
        layer->deleteTexture();
        delete layer;
    }
}

void OpenGLRenderer::drawTextureLayer(Layer* layer, const Rect& rect) {
    float alpha = layer->getAlpha() / 255.0f;

    setupDraw();
    if (layer->getRenderTarget() == GL_TEXTURE_2D) {
        setupDrawWithTexture();
    } else {
        setupDrawWithExternalTexture();
    }
    setupDrawTextureTransform();
    setupDrawColor(alpha, alpha, alpha, alpha);
    setupDrawColorFilter();
    setupDrawBlending(layer->isBlend() || alpha < 1.0f, layer->getMode());
    setupDrawProgram();
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    if (layer->getRenderTarget() == GL_TEXTURE_2D) {
        setupDrawTexture(layer->getTexture());
    } else {
        setupDrawExternalTexture(layer->getTexture());
    }
    if (mSnapshot->transform->isPureTranslate() &&
            layer->getWidth() == (uint32_t) rect.getWidth() &&
            layer->getHeight() == (uint32_t) rect.getHeight()) {
        const float x = (int) floorf(rect.left + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(rect.top + mSnapshot->transform->getTranslateY() + 0.5f);

        layer->setFilter(GL_NEAREST, GL_NEAREST);
        setupDrawModelView(x, y, x + rect.getWidth(), y + rect.getHeight(), true);
    } else {
        layer->setFilter(GL_LINEAR, GL_LINEAR);
        setupDrawModelView(rect.left, rect.top, rect.right, rect.bottom);
    }
    setupDrawTextureTransformUniforms(layer->getTexTransform());
    setupDrawMesh(&mMeshVertices[0].position[0], &mMeshVertices[0].texture[0]);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);

    finishDrawTexture();
}

void OpenGLRenderer::composeLayerRect(Layer* layer, const Rect& rect, bool swap) {
    if (!layer->isTextureLayer()) {
        const Rect& texCoords = layer->texCoords;
        resetDrawTextureTexCoords(texCoords.left, texCoords.top,
                texCoords.right, texCoords.bottom);

        float x = rect.left;
        float y = rect.top;
        bool simpleTransform = mSnapshot->transform->isPureTranslate() &&
                layer->getWidth() == (uint32_t) rect.getWidth() &&
                layer->getHeight() == (uint32_t) rect.getHeight();

        if (simpleTransform) {
            // When we're swapping, the layer is already in screen coordinates
            if (!swap) {
                x = (int) floorf(rect.left + mSnapshot->transform->getTranslateX() + 0.5f);
                y = (int) floorf(rect.top + mSnapshot->transform->getTranslateY() + 0.5f);
            }

            layer->setFilter(GL_NEAREST, GL_NEAREST, true);
        } else {
            layer->setFilter(GL_LINEAR, GL_LINEAR, true);
        }

        drawTextureMesh(x, y, x + rect.getWidth(), y + rect.getHeight(),
                layer->getTexture(), layer->getAlpha() / 255.0f,
                layer->getMode(), layer->isBlend(),
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, swap, swap || simpleTransform);

        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    } else {
        resetDrawTextureTexCoords(0.0f, 1.0f, 1.0f, 0.0f);
        drawTextureLayer(layer, rect);
        resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
    }
}

void OpenGLRenderer::composeLayerRegion(Layer* layer, const Rect& rect) {
#if RENDER_LAYERS_AS_REGIONS
    if (layer->region.isRect()) {
        layer->setRegionAsRect();

        composeLayerRect(layer, layer->regionRect);

        layer->region.clear();
        return;
    }

    if (!layer->region.isEmpty()) {
        size_t count;
        const android::Rect* rects = layer->region.getArray(&count);

        const float alpha = layer->getAlpha() / 255.0f;
        const float texX = 1.0f / float(layer->getWidth());
        const float texY = 1.0f / float(layer->getHeight());
        const float height = rect.getHeight();

        TextureVertex* mesh = mCaches.getRegionMesh();
        GLsizei numQuads = 0;

        setupDraw();
        setupDrawWithTexture();
        setupDrawColor(alpha, alpha, alpha, alpha);
        setupDrawColorFilter();
        setupDrawBlending(layer->isBlend() || alpha < 1.0f, layer->getMode(), false);
        setupDrawProgram();
        setupDrawDirtyRegionsDisabled();
        setupDrawPureColorUniforms();
        setupDrawColorFilterUniforms();
        setupDrawTexture(layer->getTexture());
        if (mSnapshot->transform->isPureTranslate()) {
            const float x = (int) floorf(rect.left + mSnapshot->transform->getTranslateX() + 0.5f);
            const float y = (int) floorf(rect.top + mSnapshot->transform->getTranslateY() + 0.5f);

            layer->setFilter(GL_NEAREST, GL_NEAREST);
            setupDrawModelViewTranslate(x, y, x + rect.getWidth(), y + rect.getHeight(), true);
        } else {
            layer->setFilter(GL_LINEAR, GL_LINEAR);
            setupDrawModelViewTranslate(rect.left, rect.top, rect.right, rect.bottom);
        }
        setupDrawMesh(&mesh[0].position[0], &mesh[0].texture[0]);

        for (size_t i = 0; i < count; i++) {
            const android::Rect* r = &rects[i];

            const float u1 = r->left * texX;
            const float v1 = (height - r->top) * texY;
            const float u2 = r->right * texX;
            const float v2 = (height - r->bottom) * texY;

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
        finishDrawTexture();

#if DEBUG_LAYERS_AS_REGIONS
        drawRegionRects(layer->region);
#endif

        layer->region.clear();
    }
#else
    composeLayerRect(layer, rect);
#endif
}

void OpenGLRenderer::drawRegionRects(const Region& region) {
#if DEBUG_LAYERS_AS_REGIONS
    size_t count;
    const android::Rect* rects = region.getArray(&count);

    uint32_t colors[] = {
            0x7fff0000, 0x7f00ff00,
            0x7f0000ff, 0x7fff00ff,
    };

    int offset = 0;
    int32_t top = rects[0].top;

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
}

void OpenGLRenderer::dirtyLayer(const float left, const float top,
        const float right, const float bottom, const mat4 transform) {
#if RENDER_LAYERS_AS_REGIONS
    if (hasLayer()) {
        Rect bounds(left, top, right, bottom);
        transform.mapRect(bounds);
        dirtyLayerUnchecked(bounds, getRegion());
    }
#endif
}

void OpenGLRenderer::dirtyLayer(const float left, const float top,
        const float right, const float bottom) {
#if RENDER_LAYERS_AS_REGIONS
    if (hasLayer()) {
        Rect bounds(left, top, right, bottom);
        dirtyLayerUnchecked(bounds, getRegion());
    }
#endif
}

void OpenGLRenderer::dirtyLayerUnchecked(Rect& bounds, Region* region) {
#if RENDER_LAYERS_AS_REGIONS
    if (bounds.intersect(*mSnapshot->clipRect)) {
        bounds.snapToPixelBoundaries();
        android::Rect dirty(bounds.left, bounds.top, bounds.right, bounds.bottom);
        if (!dirty.isEmpty()) {
            region->orSelf(dirty);
        }
    }
#endif
}

void OpenGLRenderer::clearLayerRegions() {
    const size_t count = mLayers.size();
    if (count == 0) return;

    if (!mSnapshot->isIgnored()) {
        // Doing several glScissor/glClear here can negatively impact
        // GPUs with a tiler architecture, instead we draw quads with
        // the Clear blending mode

        // The list contains bounds that have already been clipped
        // against their initial clip rect, and the current clip
        // is likely different so we need to disable clipping here
        glDisable(GL_SCISSOR_TEST);

        Vertex mesh[count * 6];
        Vertex* vertex = mesh;

        for (uint32_t i = 0; i < count; i++) {
            Rect* bounds = mLayers.itemAt(i);

            Vertex::set(vertex++, bounds->left, bounds->bottom);
            Vertex::set(vertex++, bounds->left, bounds->top);
            Vertex::set(vertex++, bounds->right, bounds->top);
            Vertex::set(vertex++, bounds->left, bounds->bottom);
            Vertex::set(vertex++, bounds->right, bounds->top);
            Vertex::set(vertex++, bounds->right, bounds->bottom);

            delete bounds;
        }

        setupDraw(false);
        setupDrawColor(0.0f, 0.0f, 0.0f, 1.0f);
        setupDrawBlending(true, SkXfermode::kClear_Mode);
        setupDrawProgram();
        setupDrawPureColorUniforms();
        setupDrawModelViewTranslate(0.0f, 0.0f, 0.0f, 0.0f, true);

        mCaches.unbindMeshBuffer();
        glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
                gVertexStride, &mesh[0].position[0]);
        glDrawArrays(GL_TRIANGLES, 0, count * 6);

        glEnable(GL_SCISSOR_TEST);
    } else {
        for (uint32_t i = 0; i < count; i++) {
            delete mLayers.itemAt(i);
        }
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

void OpenGLRenderer::skew(float sx, float sy) {
    mSnapshot->transform->skew(sx, sy);
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

void OpenGLRenderer::setupDraw(bool clear) {
    if (clear) clearLayerRegions();
    if (mDirtyClip) {
        setScissorFromClip();
    }
    mDescription.reset();
    mSetShaderColor = false;
    mColorSet = false;
    mColorA = mColorR = mColorG = mColorB = 0.0f;
    mTextureUnit = 0;
    mTrackDirtyRegions = true;
    mTexCoordsSlot = -1;
}

void OpenGLRenderer::setupDrawWithTexture(bool isAlpha8) {
    mDescription.hasTexture = true;
    mDescription.hasAlpha8Texture = isAlpha8;
}

void OpenGLRenderer::setupDrawWithExternalTexture() {
    mDescription.hasExternalTexture = true;
}

void OpenGLRenderer::setupDrawAALine() {
    mDescription.isAA = true;
}

void OpenGLRenderer::setupDrawPoint(float pointSize) {
    mDescription.isPoint = true;
    mDescription.pointSize = pointSize;
}

void OpenGLRenderer::setupDrawColor(int color) {
    setupDrawColor(color, (color >> 24) & 0xFF);
}

void OpenGLRenderer::setupDrawColor(int color, int alpha) {
    mColorA = alpha / 255.0f;
    // Second divide of a by 255 is an optimization, allowing us to simply multiply
    // the rgb values by a instead of also dividing by 255
    const float a = mColorA / 255.0f;
    mColorR = a * ((color >> 16) & 0xFF);
    mColorG = a * ((color >>  8) & 0xFF);
    mColorB = a * ((color      ) & 0xFF);
    mColorSet = true;
    mSetShaderColor = mDescription.setColor(mColorR, mColorG, mColorB, mColorA);
}

void OpenGLRenderer::setupDrawAlpha8Color(int color, int alpha) {
    mColorA = alpha / 255.0f;
    // Double-divide of a by 255 is an optimization, allowing us to simply multiply
    // the rgb values by a instead of also dividing by 255
    const float a = mColorA / 255.0f;
    mColorR = a * ((color >> 16) & 0xFF);
    mColorG = a * ((color >>  8) & 0xFF);
    mColorB = a * ((color      ) & 0xFF);
    mColorSet = true;
    mSetShaderColor = mDescription.setAlpha8Color(mColorR, mColorG, mColorB, mColorA);
}

void OpenGLRenderer::setupDrawColor(float r, float g, float b, float a) {
    mColorA = a;
    mColorR = r;
    mColorG = g;
    mColorB = b;
    mColorSet = true;
    mSetShaderColor = mDescription.setColor(r, g, b, a);
}

void OpenGLRenderer::setupDrawAlpha8Color(float r, float g, float b, float a) {
    mColorA = a;
    mColorR = r;
    mColorG = g;
    mColorB = b;
    mColorSet = true;
    mSetShaderColor = mDescription.setAlpha8Color(r, g, b, a);
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

void OpenGLRenderer::accountForClear(SkXfermode::Mode mode) {
    if (mColorSet && mode == SkXfermode::kClear_Mode) {
        mColorA = 1.0f;
        mColorR = mColorG = mColorB = 0.0f;
        mSetShaderColor = mDescription.modulate = true;
    }
}

void OpenGLRenderer::setupDrawBlending(SkXfermode::Mode mode, bool swapSrcDst) {
    // When the blending mode is kClear_Mode, we need to use a modulate color
    // argb=1,0,0,0
    accountForClear(mode);
    chooseBlending((mColorSet && mColorA < 1.0f) || (mShader && mShader->blend()), mode,
            mDescription, swapSrcDst);
}

void OpenGLRenderer::setupDrawBlending(bool blend, SkXfermode::Mode mode, bool swapSrcDst) {
    // When the blending mode is kClear_Mode, we need to use a modulate color
    // argb=1,0,0,0
    accountForClear(mode);
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

void OpenGLRenderer::setupDrawModelViewIdentity(bool offset) {
    mCaches.currentProgram->set(mOrthoMatrix, mIdentity, *mSnapshot->transform, offset);
}

void OpenGLRenderer::setupDrawModelView(float left, float top, float right, float bottom,
        bool ignoreTransform, bool ignoreModelView) {
    if (!ignoreModelView) {
        mModelView.loadTranslate(left, top, 0.0f);
        mModelView.scale(right - left, bottom - top, 1.0f);
    } else {
        mModelView.loadIdentity();
    }
    bool dirty = right - left > 0.0f && bottom - top > 0.0f;
    if (!ignoreTransform) {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, *mSnapshot->transform);
        if (mTrackDirtyRegions && dirty) {
            dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
        }
    } else {
        mCaches.currentProgram->set(mOrthoMatrix, mModelView, mIdentity);
        if (mTrackDirtyRegions && dirty) dirtyLayer(left, top, right, bottom);
    }
}

void OpenGLRenderer::setupDrawPointUniforms() {
    int slot = mCaches.currentProgram->getUniform("pointSize");
    glUniform1f(slot, mDescription.pointSize);
}

void OpenGLRenderer::setupDrawColorUniforms() {
    if (mColorSet || (mShader && mSetShaderColor)) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
    }
}

void OpenGLRenderer::setupDrawPureColorUniforms() {
    if (mSetShaderColor) {
        mCaches.currentProgram->setColor(mColorR, mColorG, mColorB, mColorA);
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

void OpenGLRenderer::setupDrawShaderIdentityUniforms() {
    if (mShader) {
        mShader->setupProgram(mCaches.currentProgram, mIdentity, *mSnapshot, &mTextureUnit);
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

void OpenGLRenderer::setupDrawExternalTexture(GLuint texture) {
    bindExternalTexture(texture);
    glUniform1i(mCaches.currentProgram->getUniform("sampler"), mTextureUnit++);

    mTexCoordsSlot = mCaches.currentProgram->getAttrib("texCoords");
    glEnableVertexAttribArray(mTexCoordsSlot);
}

void OpenGLRenderer::setupDrawTextureTransform() {
    mDescription.hasTextureTransform = true;
}

void OpenGLRenderer::setupDrawTextureTransformUniforms(mat4& transform) {
    glUniformMatrix4fv(mCaches.currentProgram->getUniform("mainTextureTransform"), 1,
            GL_FALSE, &transform.data[0]);
}

void OpenGLRenderer::setupDrawMesh(GLvoid* vertices, GLvoid* texCoords, GLuint vbo) {
    if (!vertices) {
        mCaches.bindMeshBuffer(vbo == 0 ? mCaches.meshBuffer : vbo);
    } else {
        mCaches.unbindMeshBuffer();
    }
    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, vertices);
    if (mTexCoordsSlot >= 0) {
        glVertexAttribPointer(mTexCoordsSlot, 2, GL_FLOAT, GL_FALSE, gMeshStride, texCoords);
    }
}

void OpenGLRenderer::setupDrawVertices(GLvoid* vertices) {
    mCaches.unbindMeshBuffer();
    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gVertexStride, vertices);
}

/**
 * Sets up the shader to draw an AA line. We draw AA lines with quads, where there is an
 * outer boundary that fades out to 0. The variables set in the shader define the proportion of
 * the width and length of the primitive occupied by the AA region. The vtxWidth and vtxLength
 * attributes (one per vertex) are values from zero to one that tells the fragment
 * shader where the fragment is in relation to the line width/length overall; these values are
 * then used to compute the proper color, based on whether the fragment lies in the fading AA
 * region of the line.
 * Note that we only pass down the width values in this setup function. The length coordinates
 * are set up for each individual segment.
 */
void OpenGLRenderer::setupDrawAALine(GLvoid* vertices, GLvoid* widthCoords,
        GLvoid* lengthCoords, float boundaryWidthProportion) {
    mCaches.unbindMeshBuffer();
    glVertexAttribPointer(mCaches.currentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gAAVertexStride, vertices);
    int widthSlot = mCaches.currentProgram->getAttrib("vtxWidth");
    glEnableVertexAttribArray(widthSlot);
    glVertexAttribPointer(widthSlot, 1, GL_FLOAT, GL_FALSE, gAAVertexStride, widthCoords);
    int lengthSlot = mCaches.currentProgram->getAttrib("vtxLength");
    glEnableVertexAttribArray(lengthSlot);
    glVertexAttribPointer(lengthSlot, 1, GL_FLOAT, GL_FALSE, gAAVertexStride, lengthCoords);
    int boundaryWidthSlot = mCaches.currentProgram->getUniform("boundaryWidth");
    glUniform1f(boundaryWidthSlot, boundaryWidthProportion);
    // Setting the inverse value saves computations per-fragment in the shader
    int inverseBoundaryWidthSlot = mCaches.currentProgram->getUniform("inverseBoundaryWidth");
    glUniform1f(inverseBoundaryWidthSlot, (1 / boundaryWidthProportion));
}

void OpenGLRenderer::finishDrawTexture() {
    glDisableVertexAttribArray(mTexCoordsSlot);
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

bool OpenGLRenderer::drawDisplayList(DisplayList* displayList, uint32_t width, uint32_t height,
        Rect& dirty, uint32_t level) {
    if (quickReject(0.0f, 0.0f, width, height)) {
        return false;
    }

    // All the usual checks and setup operations (quickReject, setupDraw, etc.)
    // will be performed by the display list itself
    if (displayList) {
        return displayList->replay(*this, dirty, level);
    }

    return false;
}

void OpenGLRenderer::outputDisplayList(DisplayList* displayList, uint32_t level) {
    if (displayList) {
        displayList->output(*this, level);
    }
}

void OpenGLRenderer::drawAlphaBitmap(Texture* texture, float left, float top, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    float x = left;
    float y = top;

    GLenum filter = GL_LINEAR;
    bool ignoreTransform = false;
    if (mSnapshot->transform->isPureTranslate()) {
        x = (int) floorf(left + mSnapshot->transform->getTranslateX() + 0.5f);
        y = (int) floorf(top + mSnapshot->transform->getTranslateY() + 0.5f);
        ignoreTransform = true;
        filter = GL_NEAREST;
    }

    setupDraw();
    setupDrawWithTexture(true);
    if (paint) {
        setupDrawAlpha8Color(paint->getColor(), alpha);
    }
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(x, y, x + texture->width, y + texture->height, ignoreTransform);

    setupDrawTexture(texture->id);
    texture->setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);
    texture->setFilter(filter, filter);

    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms();
    setupDrawMesh(NULL, (GLvoid*) gMeshTextureOffset);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);

    finishDrawTexture();
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    const float right = left + bitmap->width();
    const float bottom = top + bitmap->height();

    if (quickReject(left, top, right, bottom)) {
        return;
    }

    glActiveTexture(gTextureUnits[0]);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    if (bitmap->getConfig() == SkBitmap::kA8_Config) {
        drawAlphaBitmap(texture, left, top, paint);
    } else {
        drawTextureRect(left, top, right, bottom, texture, paint);
    }
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint) {
    Rect r(0.0f, 0.0f, bitmap->width(), bitmap->height());
    const mat4 transform(*matrix);
    transform.mapRect(r);

    if (quickReject(r.left, r.top, r.right, r.bottom)) {
        return;
    }

    glActiveTexture(gTextureUnits[0]);
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

void OpenGLRenderer::drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
        float* vertices, int* colors, SkPaint* paint) {
    // TODO: Do a quickReject
    if (!vertices || mSnapshot->isIgnored()) {
        return;
    }

    glActiveTexture(gTextureUnits[0]);
    Texture* texture = mCaches.textureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    texture->setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, true);
    texture->setFilter(GL_LINEAR, GL_LINEAR, true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    const uint32_t count = meshWidth * meshHeight * 6;

    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;

#if RENDER_LAYERS_AS_REGIONS
    bool hasActiveLayer = hasLayer();
#else
    bool hasActiveLayer = false;
#endif

    // TODO: Support the colors array
    TextureVertex mesh[count];
    TextureVertex* vertex = mesh;
    for (int32_t y = 0; y < meshHeight; y++) {
        for (int32_t x = 0; x < meshWidth; x++) {
            uint32_t i = (y * (meshWidth + 1) + x) * 2;

            float u1 = float(x) / meshWidth;
            float u2 = float(x + 1) / meshWidth;
            float v1 = float(y) / meshHeight;
            float v2 = float(y + 1) / meshHeight;

            int ax = i + (meshWidth + 1) * 2;
            int ay = ax + 1;
            int bx = i;
            int by = bx + 1;
            int cx = i + 2;
            int cy = cx + 1;
            int dx = i + (meshWidth + 1) * 2 + 2;
            int dy = dx + 1;

            TextureVertex::set(vertex++, vertices[ax], vertices[ay], u1, v2);
            TextureVertex::set(vertex++, vertices[bx], vertices[by], u1, v1);
            TextureVertex::set(vertex++, vertices[cx], vertices[cy], u2, v1);

            TextureVertex::set(vertex++, vertices[ax], vertices[ay], u1, v2);
            TextureVertex::set(vertex++, vertices[cx], vertices[cy], u2, v1);
            TextureVertex::set(vertex++, vertices[dx], vertices[dy], u2, v2);

#if RENDER_LAYERS_AS_REGIONS
            if (hasActiveLayer) {
                // TODO: This could be optimized to avoid unnecessary ops
                left = fminf(left, fminf(vertices[ax], fminf(vertices[bx], vertices[cx])));
                top = fminf(top, fminf(vertices[ay], fminf(vertices[by], vertices[cy])));
                right = fmaxf(right, fmaxf(vertices[ax], fmaxf(vertices[bx], vertices[cx])));
                bottom = fmaxf(bottom, fmaxf(vertices[ay], fmaxf(vertices[by], vertices[cy])));
            }
#endif
        }
    }

#if RENDER_LAYERS_AS_REGIONS
    if (hasActiveLayer) {
        dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
    }
#endif

    drawTextureMesh(0.0f, 0.0f, 1.0f, 1.0f, texture->id, alpha / 255.0f,
            mode, texture->blend, &mesh[0].position[0], &mesh[0].texture[0],
            GL_TRIANGLES, count, false, false, 0, false, false);
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
    texture->setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, true);

    const float width = texture->width;
    const float height = texture->height;

    const float u1 = (srcLeft + 0.5f) / width;
    const float v1 = (srcTop + 0.5f)  / height;
    const float u2 = (srcRight - 0.5f) / width;
    const float v2 = (srcBottom - 0.5f) / height;

    mCaches.unbindMeshBuffer();
    resetDrawTextureTexCoords(u1, v1, u2, v2);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (mSnapshot->transform->isPureTranslate()) {
        const float x = (int) floorf(dstLeft + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(dstTop + mSnapshot->transform->getTranslateY() + 0.5f);

        texture->setFilter(GL_NEAREST, GL_NEAREST, true);
        drawTextureMesh(x, y, x + (dstRight - dstLeft), y + (dstBottom - dstTop),
                texture->id, alpha / 255.0f, mode, texture->blend,
                &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0],
                GL_TRIANGLE_STRIP, gMeshCount, false, true);
    } else {
        texture->setFilter(GL_LINEAR, GL_LINEAR, true);
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
    texture->setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, true);
    texture->setFilter(GL_LINEAR, GL_LINEAR, true);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    const Patch* mesh = mCaches.patchCache.get(bitmap->width(), bitmap->height(),
            right - left, bottom - top, xDivs, yDivs, colors, width, height, numColors);

    if (mesh && mesh->verticesCount > 0) {
        const bool pureTranslate = mSnapshot->transform->isPureTranslate();
#if RENDER_LAYERS_AS_REGIONS
        // Mark the current layer dirty where we are going to draw the patch
        if (hasLayer() && mesh->hasEmptyQuads) {
            const float offsetX = left + mSnapshot->transform->getTranslateX();
            const float offsetY = top + mSnapshot->transform->getTranslateY();
            const size_t count = mesh->quads.size();
            for (size_t i = 0; i < count; i++) {
                const Rect& bounds = mesh->quads.itemAt(i);
                if (pureTranslate) {
                    const float x = (int) floorf(bounds.left + offsetX + 0.5f);
                    const float y = (int) floorf(bounds.top + offsetY + 0.5f);
                    dirtyLayer(x, y, x + bounds.getWidth(), y + bounds.getHeight());
                } else {
                    dirtyLayer(left + bounds.left, top + bounds.top,
                            left + bounds.right, top + bounds.bottom, *mSnapshot->transform);
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

/**
 * This function uses a similar approach to that of AA lines in the drawLines() function.
 * We expand the rectangle by a half pixel in screen space on all sides, and use a fragment
 * shader to compute the translucency of the color, determined by whether a given pixel is
 * within that boundary region and how far into the region it is.
 */
void OpenGLRenderer::drawAARect(float left, float top, float right, float bottom,
        int color, SkXfermode::Mode mode) {
    float inverseScaleX = 1.0f;
    float inverseScaleY = 1.0f;
    // The quad that we use needs to account for scaling.
    if (!mSnapshot->transform->isPureTranslate()) {
        Matrix4 *mat = mSnapshot->transform;
        float m00 = mat->data[Matrix4::kScaleX];
        float m01 = mat->data[Matrix4::kSkewY];
        float m02 = mat->data[2];
        float m10 = mat->data[Matrix4::kSkewX];
        float m11 = mat->data[Matrix4::kScaleX];
        float m12 = mat->data[6];
        float scaleX = sqrt(m00 * m00 + m01 * m01);
        float scaleY = sqrt(m10 * m10 + m11 * m11);
        inverseScaleX = (scaleX != 0) ? (inverseScaleX / scaleX) : 0;
        inverseScaleY = (scaleY != 0) ? (inverseScaleY / scaleY) : 0;
    }

    setupDraw();
    setupDrawAALine();
    setupDrawColor(color);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelViewIdentity(true);
    setupDrawColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderIdentityUniforms();

    AAVertex rects[4];
    AAVertex* aaVertices = &rects[0];
    void* widthCoords = ((GLbyte*) aaVertices) + gVertexAAWidthOffset;
    void* lengthCoords = ((GLbyte*) aaVertices) + gVertexAALengthOffset;

    float boundarySizeX = .5 * inverseScaleX;
    float boundarySizeY = .5 * inverseScaleY;

    // Adjust the rect by the AA boundary padding
    left -= boundarySizeX;
    right += boundarySizeX;
    top -= boundarySizeY;
    bottom += boundarySizeY;

    float width = right - left;
    float height = bottom - top;

    float boundaryWidthProportion = (width != 0) ? (2 * boundarySizeX) / width : 0;
    float boundaryHeightProportion = (height != 0) ? (2 * boundarySizeY) / height : 0;
    setupDrawAALine((void*) aaVertices, widthCoords, lengthCoords, boundaryWidthProportion);
    int boundaryLengthSlot = mCaches.currentProgram->getUniform("boundaryLength");
    int inverseBoundaryLengthSlot = mCaches.currentProgram->getUniform("inverseBoundaryLength");
    glUniform1f(boundaryLengthSlot, boundaryHeightProportion);
    glUniform1f(inverseBoundaryLengthSlot, (1 / boundaryHeightProportion));

    if (!quickReject(left, top, right, bottom)) {
        AAVertex::set(aaVertices++, left, bottom, 1, 1);
        AAVertex::set(aaVertices++, left, top, 1, 0);
        AAVertex::set(aaVertices++, right, bottom, 0, 1);
        AAVertex::set(aaVertices++, right, top, 0, 0);
        dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
}

/**
 * We draw lines as quads (tristrips). Using GL_LINES can be difficult because the rasterization
 * rules for those lines produces some unexpected results, and may vary between hardware devices.
 * The basics of lines-as-quads is easy; we simply find the normal to the line and position the
 * corners of the quads on either side of each line endpoint, separated by the strokeWidth
 * of the line. Hairlines are more involved because we need to account for transform scaling
 * to end up with a one-pixel-wide line in screen space..
 * Anti-aliased lines add another factor to the approach. We use a specialized fragment shader
 * in combination with values that we calculate and pass down in this method. The basic approach
 * is that the quad we create contains both the core line area plus a bounding area in which
 * the translucent/AA pixels are drawn. The values we calculate tell the shader what
 * proportion of the width and the length of a given segment is represented by the boundary
 * region. The quad ends up being exactly .5 pixel larger in all directions than the non-AA quad.
 * The bounding region is actually 1 pixel wide on all sides (half pixel on the outside, half pixel
 * on the inside). This ends up giving the result we want, with pixels that are completely
 * 'inside' the line area being filled opaquely and the other pixels being filled according to
 * how far into the boundary region they are, which is determined by shader interpolation.
 */
void OpenGLRenderer::drawLines(float* points, int count, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    const bool isAA = paint->isAntiAlias();
    // We use half the stroke width here because we're going to position the quad
    // corner vertices half of the width away from the line endpoints
    float halfStrokeWidth = paint->getStrokeWidth() * 0.5f;
    // A stroke width of 0 has a special meaning in Skia:
    // it draws a line 1 px wide regardless of current transform
    bool isHairLine = paint->getStrokeWidth() == 0.0f;
    float inverseScaleX = 1.0f;
    float inverseScaleY = 1.0f;
    bool scaled = false;
    int alpha;
    SkXfermode::Mode mode;
    int generatedVerticesCount = 0;
    int verticesCount = count;
    if (count > 4) {
        // Polyline: account for extra vertices needed for continuous tri-strip
        verticesCount += (count - 4);
    }

    if (isHairLine || isAA) {
        // The quad that we use for AA and hairlines needs to account for scaling. For hairlines
        // the line on the screen should always be one pixel wide regardless of scale. For
        // AA lines, we only want one pixel of translucent boundary around the quad.
        if (!mSnapshot->transform->isPureTranslate()) {
            Matrix4 *mat = mSnapshot->transform;
            float m00 = mat->data[Matrix4::kScaleX];
            float m01 = mat->data[Matrix4::kSkewY];
            float m02 = mat->data[2];
            float m10 = mat->data[Matrix4::kSkewX];
            float m11 = mat->data[Matrix4::kScaleX];
            float m12 = mat->data[6];
            float scaleX = sqrt(m00*m00 + m01*m01);
            float scaleY = sqrt(m10*m10 + m11*m11);
            inverseScaleX = (scaleX != 0) ? (inverseScaleX / scaleX) : 0;
            inverseScaleY = (scaleY != 0) ? (inverseScaleY / scaleY) : 0;
            if (inverseScaleX != 1.0f || inverseScaleY != 1.0f) {
                scaled = true;
            }
        }
    }

    getAlphaAndMode(paint, &alpha, &mode);
    setupDraw();
    if (isAA) {
        setupDrawAALine();
    }
    setupDrawColor(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    if (isAA) {
        setupDrawBlending(true, mode);
    } else {
        setupDrawBlending(mode);
    }
    setupDrawProgram();
    setupDrawModelViewIdentity(true);
    setupDrawColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderIdentityUniforms();

    if (isHairLine) {
        // Set a real stroke width to be used in quad construction
        halfStrokeWidth = isAA? 1 : .5;
    } else if (isAA && !scaled) {
        // Expand boundary to enable AA calculations on the quad border
        halfStrokeWidth += .5f;
    }
    Vertex lines[verticesCount];
    Vertex* vertices = &lines[0];
    AAVertex wLines[verticesCount];
    AAVertex* aaVertices = &wLines[0];
    if (!isAA) {
        setupDrawVertices(vertices);
    } else {
        void* widthCoords = ((GLbyte*) aaVertices) + gVertexAAWidthOffset;
        void* lengthCoords = ((GLbyte*) aaVertices) + gVertexAALengthOffset;
        // innerProportion is the ratio of the inner (non-AA) part of the line to the total
        // AA stroke width (the base stroke width expanded by a half pixel on either side).
        // This value is used in the fragment shader to determine how to fill fragments.
        // We will need to calculate the actual width proportion on each segment for
        // scaled non-hairlines, since the boundary proportion may differ per-axis when scaled.
        float boundaryWidthProportion = 1 / (2 * halfStrokeWidth);
        setupDrawAALine((void*) aaVertices, widthCoords, lengthCoords, boundaryWidthProportion);
    }

    AAVertex* prevAAVertex = NULL;
    Vertex* prevVertex = NULL;

    int boundaryLengthSlot = -1;
    int inverseBoundaryLengthSlot = -1;
    int boundaryWidthSlot = -1;
    int inverseBoundaryWidthSlot = -1;
    for (int i = 0; i < count; i += 4) {
        // a = start point, b = end point
        vec2 a(points[i], points[i + 1]);
        vec2 b(points[i + 2], points[i + 3]);
        float length = 0;
        float boundaryLengthProportion = 0;
        float boundaryWidthProportion = 0;

        // Find the normal to the line
        vec2 n = (b - a).copyNormalized() * halfStrokeWidth;
        if (isHairLine) {
            if (isAA) {
                float wideningFactor;
                if (fabs(n.x) >= fabs(n.y)) {
                    wideningFactor = fabs(1.0f / n.x);
                } else {
                    wideningFactor = fabs(1.0f / n.y);
                }
                n *= wideningFactor;
            }
            if (scaled) {
                n.x *= inverseScaleX;
                n.y *= inverseScaleY;
            }
        } else if (scaled) {
            // Extend n by .5 pixel on each side, post-transform
            vec2 extendedN = n.copyNormalized();
            extendedN /= 2;
            extendedN.x *= inverseScaleX;
            extendedN.y *= inverseScaleY;
            float extendedNLength = extendedN.length();
            // We need to set this value on the shader prior to drawing
            boundaryWidthProportion = extendedNLength / (halfStrokeWidth + extendedNLength);
            n += extendedN;
        }
        float x = n.x;
        n.x = -n.y;
        n.y = x;

        // aa lines expand the endpoint vertices to encompass the AA boundary
        if (isAA) {
            vec2 abVector = (b - a);
            length = abVector.length();
            abVector.normalize();
            if (scaled) {
                abVector.x *= inverseScaleX;
                abVector.y *= inverseScaleY;
                float abLength = abVector.length();
                boundaryLengthProportion = abLength / (length + abLength);
            } else {
                boundaryLengthProportion = .5 / (length + 1);
            }
            abVector /= 2;
            a -= abVector;
            b += abVector;
        }

        // Four corners of the rectangle defining a thick line
        vec2 p1 = a - n;
        vec2 p2 = a + n;
        vec2 p3 = b + n;
        vec2 p4 = b - n;


        const float left = fmin(p1.x, fmin(p2.x, fmin(p3.x, p4.x)));
        const float right = fmax(p1.x, fmax(p2.x, fmax(p3.x, p4.x)));
        const float top = fmin(p1.y, fmin(p2.y, fmin(p3.y, p4.y)));
        const float bottom = fmax(p1.y, fmax(p2.y, fmax(p3.y, p4.y)));

        if (!quickReject(left, top, right, bottom)) {
            if (!isAA) {
                if (prevVertex != NULL) {
                    // Issue two repeat vertices to create degenerate triangles to bridge
                    // between the previous line and the new one. This is necessary because
                    // we are creating a single triangle_strip which will contain
                    // potentially discontinuous line segments.
                    Vertex::set(vertices++, prevVertex->position[0], prevVertex->position[1]);
                    Vertex::set(vertices++, p1.x, p1.y);
                    generatedVerticesCount += 2;
                }
                Vertex::set(vertices++, p1.x, p1.y);
                Vertex::set(vertices++, p2.x, p2.y);
                Vertex::set(vertices++, p4.x, p4.y);
                Vertex::set(vertices++, p3.x, p3.y);
                prevVertex = vertices - 1;
                generatedVerticesCount += 4;
            } else {
                if (!isHairLine && scaled) {
                    // Must set width proportions per-segment for scaled non-hairlines to use the
                    // correct AA boundary dimensions
                    if (boundaryWidthSlot < 0) {
                        boundaryWidthSlot =
                                mCaches.currentProgram->getUniform("boundaryWidth");
                        inverseBoundaryWidthSlot =
                                mCaches.currentProgram->getUniform("inverseBoundaryWidth");
                    }
                    glUniform1f(boundaryWidthSlot, boundaryWidthProportion);
                    glUniform1f(inverseBoundaryWidthSlot, (1 / boundaryWidthProportion));
                }
                if (boundaryLengthSlot < 0) {
                    boundaryLengthSlot = mCaches.currentProgram->getUniform("boundaryLength");
                    inverseBoundaryLengthSlot =
                            mCaches.currentProgram->getUniform("inverseBoundaryLength");
                }
                glUniform1f(boundaryLengthSlot, boundaryLengthProportion);
                glUniform1f(inverseBoundaryLengthSlot, (1 / boundaryLengthProportion));

                if (prevAAVertex != NULL) {
                    // Issue two repeat vertices to create degenerate triangles to bridge
                    // between the previous line and the new one. This is necessary because
                    // we are creating a single triangle_strip which will contain
                    // potentially discontinuous line segments.
                    AAVertex::set(aaVertices++,prevAAVertex->position[0],
                            prevAAVertex->position[1], prevAAVertex->width, prevAAVertex->length);
                    AAVertex::set(aaVertices++, p4.x, p4.y, 1, 1);
                    generatedVerticesCount += 2;
                }
                AAVertex::set(aaVertices++, p4.x, p4.y, 1, 1);
                AAVertex::set(aaVertices++, p1.x, p1.y, 1, 0);
                AAVertex::set(aaVertices++, p3.x, p3.y, 0, 1);
                AAVertex::set(aaVertices++, p2.x, p2.y, 0, 0);
                prevAAVertex = aaVertices - 1;
                generatedVerticesCount += 4;
            }
            dirtyLayer(a.x == b.x ? left - 1 : left, a.y == b.y ? top - 1 : top,
                    a.x == b.x ? right: right, a.y == b.y ? bottom: bottom,
                    *mSnapshot->transform);
        }
    }
    if (generatedVerticesCount > 0) {
       glDrawArrays(GL_TRIANGLE_STRIP, 0, generatedVerticesCount);
    }
}

void OpenGLRenderer::drawPoints(float* points, int count, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    // TODO: The paint's cap style defines whether the points are square or circular
    // TODO: Handle AA for round points

    // A stroke width of 0 has a special meaning in Skia:
    // it draws an unscaled 1px point
    float strokeWidth = paint->getStrokeWidth();
    const bool isHairLine = paint->getStrokeWidth() == 0.0f;
    if (isHairLine) {
        // Now that we know it's hairline, we can set the effective width, to be used later
        strokeWidth = 1.0f;
    }
    const float halfWidth = strokeWidth / 2;
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    int verticesCount = count >> 1;
    int generatedVerticesCount = 0;

    TextureVertex pointsData[verticesCount];
    TextureVertex* vertex = &pointsData[0];

    setupDraw();
    setupDrawPoint(strokeWidth);
    setupDrawColor(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(mode);
    setupDrawProgram();
    setupDrawModelViewIdentity(true);
    setupDrawColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawPointUniforms();
    setupDrawShaderIdentityUniforms();
    setupDrawMesh(vertex);

    for (int i = 0; i < count; i += 2) {
        TextureVertex::set(vertex++, points[i], points[i + 1], 0.0f, 0.0f);
        generatedVerticesCount++;
        float left = points[i] - halfWidth;
        float right = points[i] + halfWidth;
        float top = points[i + 1] - halfWidth;
        float bottom = points [i + 1] + halfWidth;
        dirtyLayer(left, top, right, bottom, *mSnapshot->transform);
    }

    glDrawArrays(GL_POINTS, 0, generatedVerticesCount);
}

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    // No need to check against the clip, we fill the clip region
    if (mSnapshot->isIgnored()) return;

    Rect& clip(*mSnapshot->clipRect);
    clip.snapToPixelBoundaries();

    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color, mode, true);
}

void OpenGLRenderer::drawShape(float left, float top, const PathTexture* texture, SkPaint* paint) {
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float x = left + texture->left - texture->offset;
    const float y = top + texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);
}

void OpenGLRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    glActiveTexture(gTextureUnits[0]);
    const PathTexture* texture = mCaches.roundRectShapeCache.getRoundRect(
            right - left, bottom - top, rx, ry, paint);
    drawShape(left, top, texture, paint);
}

void OpenGLRenderer::drawCircle(float x, float y, float radius, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    glActiveTexture(gTextureUnits[0]);
    const PathTexture* texture = mCaches.circleShapeCache.getCircle(radius, paint);
    drawShape(x - radius, y - radius, texture, paint);
}

void OpenGLRenderer::drawOval(float left, float top, float right, float bottom, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    glActiveTexture(gTextureUnits[0]);
    const PathTexture* texture = mCaches.ovalShapeCache.getOval(right - left, bottom - top, paint);
    drawShape(left, top, texture, paint);
}

void OpenGLRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    if (fabs(sweepAngle) >= 360.0f) {
        drawOval(left, top, right, bottom, paint);
        return;
    }

    glActiveTexture(gTextureUnits[0]);
    const PathTexture* texture = mCaches.arcShapeCache.getArc(right - left, bottom - top,
            startAngle, sweepAngle, useCenter, paint);
    drawShape(left, top, texture, paint);
}

void OpenGLRenderer::drawRectAsShape(float left, float top, float right, float bottom,
        SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    glActiveTexture(gTextureUnits[0]);
    const PathTexture* texture = mCaches.rectShapeCache.getRect(right - left, bottom - top, paint);
    drawShape(left, top, texture, paint);
}

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom, SkPaint* p) {
    if (p->getStyle() != SkPaint::kFill_Style) {
        drawRectAsShape(left, top, right, bottom, p);
        return;
    }

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

    int color = p->getColor();
    if (p->isAntiAlias() && !mSnapshot->transform->isSimple()) {
        drawAARect(left, top, right, bottom, color, mode);
    } else {
        drawColorRect(left, top, right, bottom, color, mode);
    }
}

void OpenGLRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, SkPaint* paint) {
    if (text == NULL || count == 0) {
        return;
    }
    if (mSnapshot->isIgnored()) return;

    // TODO: We should probably make a copy of the paint instead of modifying
    //       it; modifying the paint will change its generationID the first
    //       time, which might impact caches. More investigation needed to
    //       see if it matters.
    //       If we make a copy, then drawTextDecorations() should *not* make
    //       its own copy as it does right now.
    paint->setAntiAlias(true);
#if RENDER_TEXT_AS_GLYPHS
    paint->setTextEncoding(SkPaint::kGlyphID_TextEncoding);
#endif

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

    const float oldX = x;
    const float oldY = y;
    const bool pureTranslate = mSnapshot->transform->isPureTranslate();
    if (pureTranslate) {
        x = (int) floorf(x + mSnapshot->transform->getTranslateX() + 0.5f);
        y = (int) floorf(y + mSnapshot->transform->getTranslateY() + 0.5f);
    }

    FontRenderer& fontRenderer = mCaches.fontRenderer.getFontRenderer(paint);
    fontRenderer.setFont(paint, SkTypeface::UniqueID(paint->getTypeface()),
            paint->getTextSize());

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    if (mHasShadow) {
        mCaches.dropShadowCache.setFontRenderer(fontRenderer);
        const ShadowTexture* shadow = mCaches.dropShadowCache.get(
                paint, text, bytesCount, count, mShadowRadius);
        const AutoTexture autoCleanup(shadow);

        const float sx = oldX - shadow->left + mShadowDx;
        const float sy = oldY - shadow->top + mShadowDy;

        const int shadowAlpha = ((mShadowColor >> 24) & 0xFF);
        int shadowColor = mShadowColor;
        if (mShader) {
            shadowColor = 0xffffffff;
        }

        glActiveTexture(gTextureUnits[0]);
        setupDraw();
        setupDrawWithTexture(true);
        setupDrawAlpha8Color(shadowColor, shadowAlpha < 255 ? shadowAlpha : alpha);
        setupDrawColorFilter();
        setupDrawShader();
        setupDrawBlending(true, mode);
        setupDrawProgram();
        setupDrawModelView(sx, sy, sx + shadow->width, sy + shadow->height);
        setupDrawTexture(shadow->id);
        setupDrawPureColorUniforms();
        setupDrawColorFilterUniforms();
        setupDrawShaderUniforms();
        setupDrawMesh(NULL, (GLvoid*) gMeshTextureOffset);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);

        finishDrawTexture();
    }

    if (paint->getAlpha() == 0 && paint->getXfermode() == NULL) {
        return;
    }

    // Pick the appropriate texture filtering
    bool linearFilter = mSnapshot->transform->changesBounds();
    if (pureTranslate && !linearFilter) {
        linearFilter = fabs(y - (int) y) > 0.0f || fabs(x - (int) x) > 0.0f;
    }

    glActiveTexture(gTextureUnits[0]);
    setupDraw();
    setupDrawDirtyRegionsDisabled();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(x, y, x, y, pureTranslate, true);
    setupDrawTexture(fontRenderer.getTexture(linearFilter));
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms(pureTranslate);

    const Rect* clip = pureTranslate ? mSnapshot->clipRect : &mSnapshot->getLocalClip();
    Rect bounds(FLT_MAX / 2.0f, FLT_MAX / 2.0f, FLT_MIN / 2.0f, FLT_MIN / 2.0f);

#if RENDER_LAYERS_AS_REGIONS
    bool hasActiveLayer = hasLayer();
#else
    bool hasActiveLayer = false;
#endif
    mCaches.unbindMeshBuffer();

    // Tell font renderer the locations of position and texture coord
    // attributes so it can bind its data properly
    int positionSlot = mCaches.currentProgram->position;
    fontRenderer.setAttributeBindingSlots(positionSlot, mTexCoordsSlot);
    if (fontRenderer.renderText(paint, clip, text, 0, bytesCount, count, x, y,
            hasActiveLayer ? &bounds : NULL)) {
#if RENDER_LAYERS_AS_REGIONS
        if (hasActiveLayer) {
            if (!pureTranslate) {
                mSnapshot->transform->mapRect(bounds);
            }
            dirtyLayerUnchecked(bounds, getRegion());
        }
#endif
    }

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glDisableVertexAttribArray(mCaches.currentProgram->getAttrib("texCoords"));

    drawTextDecorations(text, bytesCount, length, oldX, oldY, paint);
}

void OpenGLRenderer::drawPath(SkPath* path, SkPaint* paint) {
    if (mSnapshot->isIgnored()) return;

    glActiveTexture(gTextureUnits[0]);

    const PathTexture* texture = mCaches.pathCache.get(path, paint);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float x = texture->left - texture->offset;
    const float y = texture->top - texture->offset;

    drawPathTexture(texture, x, y, paint);
}

void OpenGLRenderer::drawLayer(Layer* layer, float x, float y, SkPaint* paint) {
    if (!layer || quickReject(x, y, x + layer->layer.getWidth(), y + layer->layer.getHeight())) {
        return;
    }

    glActiveTexture(gTextureUnits[0]);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    layer->setAlpha(alpha, mode);

#if RENDER_LAYERS_AS_REGIONS
    if (!layer->region.isEmpty()) {
        if (layer->region.isRect()) {
            composeLayerRect(layer, layer->regionRect);
        } else if (layer->mesh) {
            const float a = alpha / 255.0f;
            const Rect& rect = layer->layer;

            setupDraw();
            setupDrawWithTexture();
            setupDrawColor(a, a, a, a);
            setupDrawColorFilter();
            setupDrawBlending(layer->isBlend() || a < 1.0f, layer->getMode(), false);
            setupDrawProgram();
            setupDrawPureColorUniforms();
            setupDrawColorFilterUniforms();
            setupDrawTexture(layer->getTexture());
            if (mSnapshot->transform->isPureTranslate()) {
                x = (int) floorf(x + mSnapshot->transform->getTranslateX() + 0.5f);
                y = (int) floorf(y + mSnapshot->transform->getTranslateY() + 0.5f);

                layer->setFilter(GL_NEAREST, GL_NEAREST);
                setupDrawModelViewTranslate(x, y,
                        x + layer->layer.getWidth(), y + layer->layer.getHeight(), true);
            } else {
                layer->setFilter(GL_LINEAR, GL_LINEAR);
                setupDrawModelViewTranslate(x, y,
                        x + layer->layer.getWidth(), y + layer->layer.getHeight());
            }
            setupDrawMesh(&layer->mesh[0].position[0], &layer->mesh[0].texture[0]);

            glDrawElements(GL_TRIANGLES, layer->meshElementCount,
                    GL_UNSIGNED_SHORT, layer->meshIndices);

            finishDrawTexture();

#if DEBUG_LAYERS_AS_REGIONS
            drawRegionRects(layer->region);
#endif
        }
    }
#else
    const Rect r(x, y, x + layer->layer.getWidth(), y + layer->layer.getHeight());
    composeLayerRect(layer, r);
#endif
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

void OpenGLRenderer::drawPathTexture(const PathTexture* texture,
        float x, float y, SkPaint* paint) {
    if (quickReject(x, y, x + texture->width, y + texture->height)) {
        return;
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    setupDraw();
    setupDrawWithTexture(true);
    setupDrawAlpha8Color(paint->getColor(), alpha);
    setupDrawColorFilter();
    setupDrawShader();
    setupDrawBlending(true, mode);
    setupDrawProgram();
    setupDrawModelView(x, y, x + texture->width, y + texture->height);
    setupDrawTexture(texture->id);
    setupDrawPureColorUniforms();
    setupDrawColorFilterUniforms();
    setupDrawShaderUniforms();
    setupDrawMesh(NULL, (GLvoid*) gMeshTextureOffset);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);

    finishDrawTexture();
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
        SkPaint paintCopy(*paint);
        float underlineWidth = length;
        // If length is > 0.0f, we already measured the text for the text alignment
        if (length <= 0.0f) {
            underlineWidth = paintCopy.measureText(text, bytesCount);
        }

        float offsetX = 0;
        switch (paintCopy.getTextAlign()) {
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
            const float textSize = paintCopy.getTextSize();
            const float strokeWidth = fmax(textSize * kStdUnderline_Thickness, 1.0f);

            const float left = x - offsetX;
            float top = 0.0f;

            int linesCount = 0;
            if (flags & SkPaint::kUnderlineText_Flag) linesCount++;
            if (flags & SkPaint::kStrikeThruText_Flag) linesCount++;

            const int pointsCount = 4 * linesCount;
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

            paintCopy.setStrokeWidth(strokeWidth);

            drawLines(&points[0], pointsCount, &paintCopy);
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

    texture->setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, true);

    if (mSnapshot->transform->isPureTranslate()) {
        const float x = (int) floorf(left + mSnapshot->transform->getTranslateX() + 0.5f);
        const float y = (int) floorf(top + mSnapshot->transform->getTranslateY() + 0.5f);

        texture->setFilter(GL_NEAREST, GL_NEAREST, true);
        drawTextureMesh(x, y, x + texture->width, y + texture->height, texture->id,
                alpha / 255.0f, mode, texture->blend, (GLvoid*) NULL,
                (GLvoid*) gMeshTextureOffset, GL_TRIANGLE_STRIP, gMeshCount, false, true);
    } else {
        texture->setFilter(GL_LINEAR, GL_LINEAR, true);
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
    setupDrawPureColorUniforms();
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
    SkXfermode::Mode resultMode;
    if (!SkXfermode::AsMode(mode, &resultMode)) {
        resultMode = SkXfermode::kSrcOver_Mode;
    }
    return resultMode;
}

}; // namespace uirenderer
}; // namespace android
